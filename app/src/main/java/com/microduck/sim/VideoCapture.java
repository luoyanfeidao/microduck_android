package com.microduck.sim;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.PixelCopy;
import java.io.ByteArrayOutputStream;
import org.json.JSONObject;

/** UI schedules asynchronous SurfaceView copies; a separate thread owns JPEG compression. */
final class VideoCapture implements AutoCloseable {
    static final int FPS=10;
    private final RobotSurfaceView source;
    private final VideoFrames frames=new VideoFrames();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final HandlerThread encoder=new HandlerThread("microduck-video-jpeg");
    private final Handler encode;
    private volatile boolean closed;
    // Main-thread ownership; reused only after the preceding callback finished encoding.
    private Bitmap bitmap;
    private final Runnable tick=this::capture;
    VideoCapture(RobotSurfaceView source) {
        this.source=source; encoder.start(); encode=new Handler(encoder.getLooper()); main.post(tick);
    }
    VideoFrames.Frame frame() { return frames.request(System.nanoTime()); }
    VideoFrames.Status status() { return frames.status(System.nanoTime()); }
    private void capture() {
        if(closed) return;
        main.postDelayed(tick,1000/FPS);
        boolean available=source.isShown() && source.ready() && source.getWidth()>0 && source.getHeight()>0
            && source.getHolder().getSurface().isValid();
        frames.available(available);
        long started=System.nanoTime();
        if(!frames.begin(started)) return;
        try {
            float ratio=Math.min(1f,Math.min(640f/source.getWidth(),480f/source.getHeight()));
            int width=Math.max(1,(int)(source.getWidth()*ratio)),height=Math.max(1,(int)(source.getHeight()*ratio));
            if(bitmap==null || bitmap.getWidth()!=width || bitmap.getHeight()!=height) {
                if(bitmap!=null) bitmap.recycle();
                bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            }
            Bitmap target=bitmap;
            PixelCopy.request(source,target,result->{
                try {
                    if(closed) { frames.failed("Video session closed"); return; }
                    if(result!=PixelCopy.SUCCESS) { frames.failed("PixelCopy result "+result); return; }
                    long copyDone=System.nanoTime(); ByteArrayOutputStream bytes=new ByteArrayOutputStream(64*1024);
                    if(!target.compress(Bitmap.CompressFormat.JPEG,70,bytes)) { frames.failed("JPEG compression failed"); return; }
                    frames.publish(bytes.toByteArray(),width,height,started,(copyDone-started)/1e6,(System.nanoTime()-copyDone)/1e6);
                } catch(RuntimeException e) { frames.failed(e.getClass().getSimpleName()); }
            },encode);
        } catch(RuntimeException e) { frames.failed(e.getClass().getSimpleName()); }
    }
    void annotate(JSONObject data) throws Exception {
        VideoFrames.Status s=status();
        data.put("video_backend","PixelCopy / JPEG latest-frame HTTP"); data.put("video_target_fps",FPS);
        data.put("video_enabled",s.enabled); data.put("video_demand_active",s.demand); data.put("video_copy_pending",s.busy);
        data.put("video_capture_attempts",s.attempts); data.put("video_frames_published",s.published);
        data.put("video_frames_served",s.served);
        data.put("video_capture_failures",s.failures); data.put("video_width",s.width); data.put("video_height",s.height);
        data.put("video_jpeg_bytes",s.bytes); data.put("video_copy_ms",s.copyMs); data.put("video_encode_ms",s.encodeMs);
        data.put("video_frame_age_ms",s.ageMs); data.put("video_error",s.error);
    }
    @Override public void close() {
        closed=true; frames.close(); main.removeCallbacks(tick); encoder.quitSafely();
        // Do not recycle a bitmap while PixelCopy still owns it. Session references are GC-owned.
    }
}
