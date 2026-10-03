package com.microduck.sim;

/** Host checks for the worker clock gate and native mesh packet validation. */
public final class RenderOptimizationCheck {
    public static void main(String[] args) {
        VisualCadence gate=new VisualCadence(); int frames=0;
        for(int tick=0;tick<1500;tick++) if(gate.due(tick*20_000_000L)) frames++;
        if(frames!=900) throw new AssertionError("Expected 900 snapshots, got "+frames);
        if(!gate.due(60_000_000_000L) || gate.due(60_000_000_000L)) throw new AssertionError("Catch-up burst");
        gate.reset(); if(!gate.due(60_000_000_000L)) throw new AssertionError("Reset not immediate");
        int jitterFrames=0; gate.reset();
        for(int tick=0;tick<1500;tick++) if(gate.due(tick*20_000_000L+(tick%5)*1_000_000L)) jitterFrames++;
        if(Math.abs(jitterFrames-900)>1) throw new AssertionError("Cadence drift under jitter");
        new VisualMesh(new float[]{0,0,0,0,0,1},new int[]{0,0,0});
        reject(new float[7],new int[]{0,0,0}); reject(new float[6],new int[]{1,0,0});
        reject(new float[6],new int[]{-1,0,0}); reject(new float[6],new int[]{0,0});
        reject(new float[]{Float.NaN,0,0,0,0,1},new int[]{0,0,0});
        System.out.println("{\"snapshots_per_30s_at_50hz\":"+frames+",\"jitter_frames\":"+jitterFrames+
            ",\"no_catchup_bursts\":true,\"mesh_validation\":true}");
    }
    private static void reject(float[] v,int[] i) {
        try { new VisualMesh(v,i); } catch(IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid mesh accepted");
    }
}
