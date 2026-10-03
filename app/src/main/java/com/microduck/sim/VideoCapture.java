package com.microduck.sim;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/** UI schedules head-camera FBO renders; GLES reads pixels, a separate thread builds JPEGs. */
final class VideoCapture implements AutoCloseable {
    static final int FPS=10;
    private final RobotSurfaceView source;
    private final VideoFrames frames=new VideoFrames();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final HandlerThread encoder=new HandlerThread("microduck-video-jpeg");
    private final Handler encode;
    private volatile boolean closed;
    // One GL read/encode owns these buffers until publish/failed clears the cache's busy flag.
    private final ByteBuffer rgba=ByteBuffer.allocateDirect(HeadCamera.WIDTH*HeadCamera.HEIGHT*4);
    private final int[] argb=new int[HeadCamera.WIDTH*HeadCamera.HEIGHT];
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
        boolean available=source.isShown() && source.headCameraReady() && source.getWidth()>0 && source.getHeight()>0
            && source.getHolder().getSurface().isValid();
        frames.available(available);
        long started=System.nanoTime();
        if(!frames.begin(started)) return;
        try {
            source.captureHeadCamera(rgba,error->{
                long rendered=System.nanoTime();
                if(!encode.post(()->{
                    try {
                        if(closed) { frames.failed("Video session closed"); return; }
                        if(error!=null) { frames.failed(error); return; }
                        long encodeStarted=System.nanoTime();
                        CameraPixels.toArgb(rgba,HeadCamera.WIDTH,HeadCamera.HEIGHT,argb);
                        if(bitmap==null) bitmap=Bitmap.createBitmap(HeadCamera.WIDTH,HeadCamera.HEIGHT,Bitmap.Config.ARGB_8888);
                        bitmap.setPixels(argb,0,HeadCamera.WIDTH,0,0,HeadCamera.WIDTH,HeadCamera.HEIGHT);
                        ByteArrayOutputStream bytes=new ByteArrayOutputStream(64*1024);
                        if(!bitmap.compress(Bitmap.CompressFormat.JPEG,70,bytes)) { frames.failed("JPEG compression failed"); return; }
                        frames.publish(bytes.toByteArray(),HeadCamera.WIDTH,HeadCamera.HEIGHT,started,(rendered-started)/1e6,
                            (System.nanoTime()-encodeStarted)/1e6);
                    } catch(RuntimeException e) { frames.failed(e.getClass().getSimpleName()); }
                })) frames.failed("Video encoder stopped");
            });
        } catch(RuntimeException e) { frames.failed(e.getClass().getSimpleName()); }
    }
    CapturedReport.Fields captureReport() {
        VideoFrames.Status s=status();
        return data->{
            data.put("video_backend","head_camera GLES framebuffer / JPEG latest-frame HTTP"); data.put("video_target_fps",FPS);
            data.put("video_source","head_camera"); data.put("video_mount_rotate",HeadCamera.MOUNT_ROTATE);
            data.put("video_orientation_correction","desktop local camera X 180 degrees; browser clockwise 90 degrees");
            data.put("video_enabled",s.enabled); data.put("video_demand_active",s.demand); data.put("video_copy_pending",s.busy);
            data.put("video_capture_attempts",s.attempts); data.put("video_frames_published",s.published);
            data.put("video_frames_served",s.served);
            data.put("video_capture_failures",s.failures); data.put("video_width",s.width); data.put("video_height",s.height);
            data.put("video_jpeg_bytes",s.bytes); data.put("video_copy_ms",s.copyMs); data.put("video_encode_ms",s.encodeMs);
            data.put("video_frame_age_ms",s.ageMs); data.put("video_error",s.error);
        };
    }
    @Override public void close() {
        closed=true; frames.close(); main.removeCallbacks(tick); encoder.quitSafely();
        // GL/encoder callbacks can still hold buffers; the closed session cannot publish them.
    }
}
