package com.microduck.sim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ReportDispatcherCheck {
    public static void main(String[] args) throws Exception {
        List<String> written=Collections.synchronizedList(new ArrayList<>());
        List<Exception> errors=Collections.synchronizedList(new ArrayList<>());
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1),done=new CountDownLatch(1);
        ReportDispatcher reports=new ReportDispatcher(errors::add);
        try {
            reports.submit(()->{ started.countDown(); await(release); written.add("active"); },false);
            await(started);
            reports.submit(()->written.add("stale1"),false);
            reports.submit(()->written.add("stale2"),false);
            reports.submit(()->written.add("archive1"),true);
            reports.submit(()->written.add("stale3"),false);
            reports.submit(()->written.add("stale4"),false);
            reports.submit(()->written.add("archive2"),true);
            reports.submit(()->{ written.add("latest"); done.countDown(); },false);
            reports.close(); // Must return without waiting for blocked report I/O.
            if(reports.submit(()->written.add("closed"),true)) throw new AssertionError("Closed submit accepted");
            release.countDown(); await(done);
            if(!written.equals(Arrays.asList("active","archive1","archive2","latest"))
                || reports.coalesced()!=4 || !errors.isEmpty()) throw new AssertionError(written.toString());
        } finally { release.countDown(); reports.close(); }
        CountDownLatch recovered=new CountDownLatch(1);
        ReportDispatcher failures=new ReportDispatcher(errors::add);
        try {
            failures.submit(()->{ throw new IllegalStateException("expected I/O failure"); },true);
            failures.submit(recovered::countDown,true); failures.close(); await(recovered);
            if(errors.size()!=1) throw new AssertionError("Failure not delivered");
        } finally { failures.close(); }
        // A submit does not signal the consumer: repeated idle reports still get delivered.
        ReportDispatcher idle=new ReportDispatcher(errors::add);
        try {
            for(int i=0;i<100;i++) {
                CountDownLatch woke=new CountDownLatch(1);
                if(!idle.submit(woke::countDown,true)) throw new AssertionError("Open submit rejected");
                await(woke);
            }
        } finally { idle.close(); }
        // Race multiple archive producers against close: every accepted job must drain once.
        for(int round=0;round<20;round++) {
            AtomicInteger accepted=new AtomicInteger(),processed=new AtomicInteger();
            CountDownLatch start=new CountDownLatch(1),attempted=new CountDownLatch(1);
            ReportDispatcher racing=new ReportDispatcher(errors::add);
            List<Thread> producers=new ArrayList<>();
            try {
                if(racing.submit(processed::incrementAndGet,true)) accepted.incrementAndGet();
                for(int p=0;p<4;p++) {
                    Thread producer=new Thread(()->{
                        await(start);
                        for(int j=0;j<200;j++) {
                            if(racing.submit(processed::incrementAndGet,true)) accepted.incrementAndGet();
                            attempted.countDown();
                        }
                    });
                    producers.add(producer); producer.start();
                }
                start.countDown(); await(attempted); racing.close();
                for(Thread producer:producers) {
                    producer.join(5000);
                    if(producer.isAlive()) throw new AssertionError("Producer blocked on close");
                }
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(processed.get()<accepted.get() && System.nanoTime()<deadline) Thread.sleep(1);
                if(processed.get()!=accepted.get() || accepted.get()==0)
                    throw new AssertionError("Accepted archive lost or duplicated");
                if(racing.submit(processed::incrementAndGet,true)) throw new AssertionError("Closed race accepted");
            } finally { start.countDown(); racing.close(); }
        }
        if(errors.size()!=1) throw new AssertionError("Unexpected dispatcher error");
        System.out.println("{\"blocked_io_does_not_block_submit_or_close\":true,\"ordinary_status_coalesced\":true,"
            +"\"completed_tests_preserved\":true,\"accepted_tasks_drain_on_close\":true,\"error_recovery\":true,"
            +"\"idle_polling_delivers_reports\":true,\"concurrent_close_preserves_accepted_archives\":true}");
    }
    private static void await(CountDownLatch latch) {
        try { if(!latch.await(5,TimeUnit.SECONDS)) throw new AssertionError("Report dispatcher timeout"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
