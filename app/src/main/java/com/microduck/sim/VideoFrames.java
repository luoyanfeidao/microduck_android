package com.microduck.sim;

import java.io.IOException;
import java.io.OutputStream;

/** One immutable JPEG and one in-flight copy/encode; no native or Android objects. */
final class VideoFrames {
    static final long FRESH_NS=2_000_000_000L;
    static final int MAX_BYTES=512*1024;
    static final class Frame {
        private final byte[] jpeg;
        final long sequence,capturedNs;
        final int width,height;
        Frame(byte[] jpeg,long sequence,long capturedNs,int width,int height) {
            this.jpeg=jpeg.clone(); this.sequence=sequence; this.capturedNs=capturedNs;
            this.width=width; this.height=height;
        }
        int size() { return jpeg.length; }
        void writeTo(OutputStream output) throws IOException { output.write(jpeg); }
    }
    static final class Status {
        final boolean enabled,demand,busy;
        final long attempts,published,failures,served;
        final int width,height,bytes;
        final double copyMs,encodeMs,ageMs;
        final String error;
        Status(boolean enabled,boolean demand,boolean busy,long attempts,long published,long failures,long served,
               Frame frame,double copyMs,double encodeMs,long now,String error) {
            this.enabled=enabled; this.demand=demand; this.busy=busy;
            this.attempts=attempts; this.published=published; this.failures=failures; this.served=served;
            width=frame==null?0:frame.width; height=frame==null?0:frame.height; bytes=frame==null?0:frame.size();
            ageMs=frame==null?-1:Math.max(0,(now-frame.capturedNs)/1e6);
            this.copyMs=copyMs; this.encodeMs=encodeMs; this.error=error;
        }
    }
    private Frame latest;
    private boolean closed,busy,available;
    private long demandAt,attempts,published,failures,served;
    private boolean hasDemand;
    private double copyMs,encodeMs;
    private String error="";
    synchronized Frame request(long now) {
        if(closed) return null;
        demandAt=now; hasDemand=true;
        if(available && latest!=null && now-latest.capturedNs<FRESH_NS) { served++; return latest; }
        return null;
    }
    private boolean demand(long now) { return hasDemand && now-demandAt<FRESH_NS; }
    synchronized boolean begin(long now) {
        if(closed || !available || busy || !demand(now)) return false;
        busy=true; attempts++; return true;
    }
    synchronized void available(boolean value) { available=value; if(!value) latest=null; }
    synchronized void publish(byte[] jpeg,int width,int height,long capturedNs,double copyMs,double encodeMs) {
        if(!busy) throw new IllegalStateException("Video has no pending copy");
        busy=false;
        if(closed || !available) return;
        if(jpeg.length<4 || jpeg.length>MAX_BYTES || width<1 || width>640 || height<1 || height>480
            || (jpeg[0]&255)!=255 || (jpeg[1]&255)!=216 || (jpeg[jpeg.length-2]&255)!=255 || (jpeg[jpeg.length-1]&255)!=217) {
            failures++; latest=null; error="Invalid or oversized JPEG"; return;
        }
        latest=new Frame(jpeg,++published,capturedNs,width,height);
        this.copyMs=copyMs; this.encodeMs=encodeMs; error="";
    }
    synchronized void failed(String message) { busy=false; failures++; latest=null; error=message; }
    synchronized Status status(long now) {
        return new Status(!closed,demand(now)&&!closed,busy,attempts,published,failures,served,latest,copyMs,encodeMs,now,error);
    }
    synchronized void close() { closed=true; available=false; latest=null; hasDemand=false; }
}
