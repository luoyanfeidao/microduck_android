package com.microduck.sim;

/** Embedded Rust controller; every call and synchronous inference runs on the simulation worker. */
final class RustControl {
    static { System.loadLibrary("microduck_m1"); }
    static native long open();
    static native void close(long core);
    static native void command(long core,int operation); // reset=0, roll=1, cancel=2
    static native double[] tick(long core,double[] sensors,double[] twist,boolean filter,Object inference);
    private RustControl() {}
}
