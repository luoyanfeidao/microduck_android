package com.microduck.sim;

/** Worker clock gate: 50Hz control, average 30Hz snapshots, no catch-up bursts. */
final class VisualCadence {
    static final int FPS=30;
    private static final long PERIOD_NS=1_000_000_000L/FPS;
    private boolean started;
    private long nextNs;
    boolean due(long now) {
        if(!started) { started=true; nextNs=now+PERIOD_NS; return true; }
        if(now-nextNs<0) return false;
        nextNs+=((now-nextNs)/PERIOD_NS+1)*PERIOD_NS;
        return true;
    }
    void reset() { started=false; }
}
