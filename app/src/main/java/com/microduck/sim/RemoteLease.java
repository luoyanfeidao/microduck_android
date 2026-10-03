package com.microduck.sim;

/** Worker-owned lease. Browser heartbeats keep remote simulation running. */
final class RemoteLease {
    static final long TIMEOUT_NS=3_000_000_000L;
    private boolean active;
    private long refreshedAt;
    void claim(long now) { active=true; refreshedAt=now; }
    void heartbeat(long now) { if(active) refreshedAt=now; }
    void release() { active=false; }
    boolean active() { return active; }
    boolean expired(long now) { return active && now-refreshedAt>=TIMEOUT_NS; }
}
