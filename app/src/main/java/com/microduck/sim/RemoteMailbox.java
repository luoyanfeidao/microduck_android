package com.microduck.sim;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** At most one unacknowledged LAN command; queued work cancelled on HTTP timeout. */
final class RemoteMailbox {
    interface Apply { String run(RemoteCommand command) throws Exception; }
    private final Executor worker;
    private final AtomicBoolean pending=new AtomicBoolean();
    RemoteMailbox(Executor worker) { this.worker=worker; }
    String submit(RemoteCommand command,Apply apply) throws Exception { return submit(command,apply,1000); }
    String submit(RemoteCommand command,Apply apply,long timeoutMs) throws Exception {
        if(!pending.compareAndSet(false,true)) throw new LanControlServer.CommandFailure(429,"Command pending");
        CompletableFuture<String> reply=new CompletableFuture<>();
        try {
            worker.execute(()->{
                try {
                    if(reply.isCancelled()) return;
                    reply.complete(apply.run(command));
                } catch(Exception e) { reply.completeExceptionally(e); }
                finally { pending.set(false); }
            });
        } catch(RejectedExecutionException e) {
            pending.set(false); throw new LanControlServer.CommandFailure(503,"Worker stopped");
        }
        try { return reply.get(timeoutMs,TimeUnit.MILLISECONDS); }
        catch(TimeoutException e) { reply.cancel(false); throw new LanControlServer.CommandFailure(504,"Command timeout"); }
        catch(InterruptedException e) { reply.cancel(false); Thread.currentThread().interrupt(); throw e; }
        catch(ExecutionException e) {
            if(e.getCause() instanceof Exception) throw (Exception)e.getCause();
            throw new IllegalStateException(e.getCause());
        }
    }
}
