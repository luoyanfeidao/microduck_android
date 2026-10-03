package com.microduck.sim;

import java.io.ByteArrayOutputStream;

public final class VideoFramesCheck {
    public static void main(String[] args) throws Exception {
        VideoFrames frames=new VideoFrames(); frames.available(true);
        if(frames.begin(0)) throw new AssertionError("Capturing without viewer");
        if(frames.request(0)!=null || !frames.begin(0) || frames.begin(1)) throw new AssertionError("Unbounded capture");
        byte[] jpeg={(byte)255,(byte)216,7,(byte)255,(byte)217};
        frames.publish(jpeg,640,480,0,2,3); jpeg[2]=99;
        VideoFrames.Frame first=frames.request(1); ByteArrayOutputStream out=new ByteArrayOutputStream(); first.writeTo(out);
        if(out.toByteArray()[2]!=7 || first.sequence!=1) throw new AssertionError("Mutable frame");
        if(!frames.begin(2)) throw new AssertionError("Missing next frame");
        frames.publish(new byte[]{(byte)255,(byte)216,8,(byte)255,(byte)217},320,240,2,1,2);
        if(frames.request(3).sequence!=2 || first.sequence!=1) throw new AssertionError("Latest frame replacement");
        if(frames.begin(3+VideoFrames.FRESH_NS)) throw new AssertionError("Capture continued without demand");
        if(frames.request(3+VideoFrames.FRESH_NS)!=null) throw new AssertionError("Serving stale frame");
        if(!frames.begin(4+VideoFrames.FRESH_NS)) throw new AssertionError("Viewer could not restart");
        frames.publish(new byte[VideoFrames.MAX_BYTES+1],640,480,4+VideoFrames.FRESH_NS,0,0);
        if(frames.request(5+VideoFrames.FRESH_NS)!=null || frames.status(5+VideoFrames.FRESH_NS).failures!=1)
            throw new AssertionError("Oversized frame accepted");
        frames.available(false); if(frames.begin(6+VideoFrames.FRESH_NS)) throw new AssertionError("Hidden source capture");
        frames.available(true); if(!frames.begin(7+VideoFrames.FRESH_NS)) throw new AssertionError("Pending close fixture");
        frames.close(); frames.publish(new byte[]{(byte)255,(byte)216,8,(byte)255,(byte)217},320,240,8+VideoFrames.FRESH_NS,0,0);
        if(frames.request(9+VideoFrames.FRESH_NS)!=null || frames.status(9+VideoFrames.FRESH_NS).enabled)
            throw new AssertionError("Late callback revived closed session");
        System.out.println("{\"no_capture_without_viewer\":true,\"one_inflight_capture\":true,\"immutable_latest_frame\":true,"
            +"\"stale_oversized_and_hidden_rejection\":true,\"late_publish_after_close_rejected\":true}");
    }
}
