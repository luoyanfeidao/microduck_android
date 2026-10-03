package com.microduck.sim;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/** One report thread. Keep only the newest pending ordinary status; retain completed tests. */
final class ReportDispatcher implements AutoCloseable {
    private static final int CLOSED=Integer.MIN_VALUE;
    static final int POLL_INTERVAL_MS=50;
    private static final long POLL_INTERVAL_NS=POLL_INTERVAL_MS*1_000_000L;
    private final ConcurrentLinkedQueue<Runnable> completed=new ConcurrentLinkedQueue<>();
    private final AtomicReference<Runnable> latest=new AtomicReference<>();
    private final AtomicLong coalesced=new AtomicLong();
    // High bit closes admission; low bits count producers already admitted before close.
    private final AtomicInteger admission=new AtomicInteger();
    private final Consumer<Exception> errors;
    private final Thread thread;
    ReportDispatcher(Consumer<Exception> errors) {
        this.errors=errors;
        thread=new Thread(this::drain,"microduck-report"); thread.start();
    }
    boolean submit(Runnable task,boolean completedTest) { return submit(task,completedTest,null); }
    boolean submit(Runnable task,boolean completedTest,long[] timing) {
        long begin=timing==null?0:System.nanoTime();
        while(true) {
            int state=admission.get();
            if(state<0) return false;
            if(admission.compareAndSet(state,state+1)) break;
        }
        long admitted=timing==null?0:System.nanoTime();
        try {
            Runnable previous=latest.getAndSet(completedTest?null:task);
            if(previous!=null) coalesced.incrementAndGet();
            if(completedTest) completed.add(task);
        } finally {
            admission.decrementAndGet();
        }
        // The consumer checks periodically: no unpark/native wake on the simulation thread.
        if(timing!=null) {
            timing[0]=admitted-begin;
            timing[1]=System.nanoTime()-admitted;
        }
        return true;
    }
    long coalesced() { return coalesced.get(); }
    private void drain() {
        while(true) {
            Runnable task=completed.poll();
            if(task==null) task=latest.getAndSet(null);
            if(task==null) {
                if(admission.get()==CLOSED && completed.isEmpty() && latest.get()==null) return;
                LockSupport.parkNanos(this,POLL_INTERVAL_NS);
                continue;
            }
            try { task.run(); } catch(Exception e) { errors.accept(e); }
        }
    }
    @Override public void close() {
        admission.getAndUpdate(state->state|CLOSED);
        LockSupport.unpark(thread); // Accepted tasks drain; no waiting for report I/O.
    }
}
