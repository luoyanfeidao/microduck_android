package com.microduck.sim;

import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** One report thread. Keep only the newest pending ordinary status; retain completed tests. */
final class ReportDispatcher implements AutoCloseable {
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final ArrayDeque<Runnable> completed=new ArrayDeque<>();
    private final Consumer<Exception> errors;
    private Runnable latest;
    private boolean running,closed;
    private long coalesced;
    ReportDispatcher(Consumer<Exception> errors) { this.errors=errors; }
    synchronized void submit(Runnable task,boolean completedTest) {
        if(closed) return;
        if(completedTest) {
            completed.add(task);
            if(latest!=null) coalesced++;
            latest=null;
        } else {
            if(latest!=null) coalesced++;
            latest=task;
        }
        if(!running) { running=true; executor.execute(this::drain); }
    }
    synchronized long coalesced() { return coalesced; }
    private void drain() {
        while(true) {
            Runnable task;
            synchronized(this) {
                task=completed.poll();
                if(task==null) { task=latest; latest=null; }
                if(task==null) { running=false; return; }
            }
            try { task.run(); } catch(Exception e) { errors.accept(e); }
        }
    }
    @Override public synchronized void close() {
        closed=true;
        executor.shutdown(); // Accepted tasks drain, including completed-test archives.
    }
}
