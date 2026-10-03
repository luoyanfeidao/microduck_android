package com.microduck.sim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
            reports.submit(()->written.add("closed"),true);
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
        System.out.println("{\"blocked_io_does_not_block_submit_or_close\":true,\"ordinary_status_coalesced\":true,"
            +"\"completed_tests_preserved\":true,\"accepted_tasks_drain_on_close\":true,\"error_recovery\":true}");
    }
    private static void await(CountDownLatch latch) {
        try { if(!latch.await(5,TimeUnit.SECONDS)) throw new AssertionError("Report dispatcher timeout"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
