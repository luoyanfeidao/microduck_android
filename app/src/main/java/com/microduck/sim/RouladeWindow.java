package com.microduck.sim;

/** One forward roll lasts 50 completed control steps. Pausing does not advance the window. */
final class RouladeWindow {
    static final int TICKS=50; // Desktop single-roll duration: 1 second at 50 Hz.
    private int remaining;
    private long completed;
    boolean active() { return remaining>0; }
    int remaining() { return remaining; }
    long completed() { return completed; }
    boolean start() {
        if(active()) return false; // Repeated taps cannot keep the roll running indefinitely.
        remaining=TICKS; return true;
    }
    void advance() { if(remaining>0 && --remaining==0) completed++; }
    void cancel() { remaining=0; }
    void reset() { remaining=0; completed=0; }
}
