package com.microduck.sim;

public final class RouladeWindowCheck {
    public static void main(String[] args) {
        RouladeWindow window=new RouladeWindow();
        if(!window.start() || window.remaining()!=50) throw new AssertionError("Roll not started");
        for(int tick=0;tick<49;tick++) {
            if(window.start()) throw new AssertionError("Tap restarted active roll");
            window.advance();
            if(!window.active()) throw new AssertionError("Roll ended early");
        }
        // The caller only advances after physics succeeds; a pause never consumes ticks.
        if(window.remaining()!=1 || window.completed()!=0) throw new AssertionError("Roll progress");
        window.advance();
        if(window.active() || window.remaining()!=0 || window.completed()!=1) throw new AssertionError("Roll deadline");
        window.advance(); if(window.completed()!=1) throw new AssertionError("Repeated completion");
        window.start(); window.advance(); window.cancel();
        if(window.active() || window.completed()!=1) throw new AssertionError("Cancel counted as completion");
        window.reset(); if(window.completed()!=0 || window.active()) throw new AssertionError("Reset");
        System.out.println("{\"exactly_50_completed_steps\":true,\"repeated_requests_do_not_extend\":true,\"cancel_and_reset\":true}");
    }
}
