package com.microduck.sim;

/** All handles are owned and used by the Activity's single simulation worker. */
final class NativeSim {
    static {
        System.loadLibrary("mujoco");
        System.loadLibrary("microduck_m1");
    }
    static native long open(String xmlPath, String keyframe);
    static native String state(long handle);
    static native String step(long handle, int count);
    static native void advance(long handle, int count);
    static native String reset(long handle, String keyframe);
    static native String benchmark(long handle, int steps);
    static native String resetHome(long handle);
    static native float[] observation(long handle, float[] previousAction, float[] command);
    static native void applyAction(long handle, float[] rawAction, boolean standing, boolean filter);
    static native void applyRouladeAction(long handle, float[] rawAction, boolean filter);
    static native void setBam(long handle, boolean enabled);
    static native String bodySensors(long handle);
    static native double[] bodySample(long handle); // pos15, vel15, gyro3, gravity3, quat4
    static native void bodyTargets(long handle, double[] wireTargets);
    static native void bodyGain(long handle, int kp);
    static native void bodyTorque(long handle, boolean on);
    static native VisualMesh[] visualMeshes(long handle);
    static native float[] visualSnapshot(long handle);
    static native void close(long handle);
    private NativeSim() {}
}
