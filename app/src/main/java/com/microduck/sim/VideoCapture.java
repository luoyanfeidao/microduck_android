package com.microduck.sim;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import org.webrtc.EglBase;
import org.webrtc.SurfaceTextureHelper;

/** Independent offscreen EGL owner. No View/Surface visibility and no MuJoCo handle. */
final class VideoCapture implements AutoCloseable {
    static final int FPS=10,RTC_FPS=20;
    private final VideoFrames frames=new VideoFrames();
    private final HandlerThread cameraThread=new HandlerThread("microduck-head-camera");
    private final HandlerThread encoderThread=new HandlerThread("microduck-video-jpeg");
    private final Handler camera,encode;
    private final SceneRenderer renderer=new SceneRenderer();
    private EglBase egl;
    private SurfaceTextureHelper textureHelper;
    private volatile RtcVideo rtc;
    private volatile CameraScene latest;
    private volatile boolean closed,glReady;
    private volatile String glError="",gpu="";
    private volatile long renderCount,textureDrops;
    // Native peer teardown completes before the independent camera producer is released.
    private boolean rtcDisposed,stopping;
    private long nextJpegNs;
    private final ByteBuffer rgba=ByteBuffer.allocateDirect(HeadCamera.WIDTH*HeadCamera.HEIGHT*4);
    private final int[] argb=new int[HeadCamera.WIDTH*HeadCamera.HEIGHT];
    private Bitmap bitmap;
    private final Runnable tick=this::capture;
    VideoCapture(Context context) {
        cameraThread.start(); encoderThread.start();
        camera=new Handler(cameraThread.getLooper()); encode=new Handler(encoderThread.getLooper());
        renderer.error=error->glError=error;
        camera.post(()->{
            try {
                int[] config=EglBase.configBuilder().setOpenGlesVersion(3).setHasAlphaChannel(true)
                    .setSupportsPixelBuffer(true).createConfigAttributes();
                for(int i=0;i+1<config.length;i+=2) if(config[i]==android.opengl.EGL14.EGL_SURFACE_TYPE)
                    config[i+1]=android.opengl.EGL14.EGL_PBUFFER_BIT|android.opengl.EGL14.EGL_WINDOW_BIT;
                egl=EglBase.createEgl14(config);
                egl.createPbufferSurface(1,1); egl.makeCurrent();
                renderer.onSurfaceCreated(null,null);
                if(!renderer.problem.isEmpty()) throw new IllegalStateException(renderer.problem);
                gpu=renderer.gpu;
                textureHelper=SurfaceTextureHelper.create("microduck-rtc-texture",egl.getEglBaseContext());
                if(textureHelper==null) throw new IllegalStateException("Texture helper creation failed");
                textureHelper.setTextureSize(HeadCamera.WIDTH,HeadCamera.HEIGHT);
                textureHelper.setFrameRotation(HeadCamera.MOUNT_ROTATE);
                // The camera has its own window Surface, backed by a SurfaceTexture, with no UI owner.
                egl.releaseSurface(); egl.createSurface(textureHelper.getSurfaceTexture()); egl.makeCurrent();
                if(!closed) {
                    rtc=new RtcVideo(context,egl.getEglBaseContext());
                    textureHelper.startListening(frame->{ RtcVideo video=rtc; if(video!=null) video.frame(frame); });
                }
                glReady=true;
            } catch(RuntimeException | LinkageError e) { glError="Camera initialization failed: "+e.getClass().getSimpleName(); }
            if(closed) stopCamera(); else camera.post(tick);
        });
    }
    void scene(CameraScene scene) { if(!closed) latest=scene; }
    VideoFrames.Frame frame() { return frames.request(System.nanoTime()); }
    VideoFrames.Status status() { return frames.status(System.nanoTime()); }
    String signal(String body) throws Exception {
        RtcVideo video=rtc;
        if(closed || video==null) throw new LanControlServer.CommandFailure(503,"Camera not ready");
        return video.signal(body);
    }
    private void capture() {
        if(closed) return;
        camera.postDelayed(tick,1000/RTC_FPS);
        CameraScene scene=latest;
        boolean available=glReady && scene!=null && scene.available(); frames.available(available);
        if(!available) return;
        if(textureHelper.isTextureInUse()) { textureDrops++; return; } // One acquired texture; no unbounded producer queue.
        long started=System.nanoTime();
        boolean jpeg=started>=nextJpegNs && frames.begin(started);
        RtcVideo video=rtc; boolean streaming=video!=null && video.wantsFrames();
        if(!jpeg && !streaming) return;
        boolean jpegQueued=false;
        try {
            egl.makeCurrent(); renderer.source=scene.meshes; renderer.latest=scene.frame;
            renderer.renderHeadCamera(jpeg?rgba:null); renderCount++;
            if(jpeg) {
                nextJpegNs=started+1_000_000_000L/FPS;
                long rendered=System.nanoTime();
                jpegQueued=encode.post(()->encodeJpeg(started,rendered));
                if(!jpegQueued) frames.failed("JPEG encoder stopped");
            }
            if(streaming) {
                renderer.presentHeadCamera();
                // BufferQueue/EGL fences protect the image until SurfaceTexture releases it.
                egl.swapBuffers(started);
            }
        } catch(RuntimeException e) {
            if(jpeg && !jpegQueued) frames.failed("Camera render failed: "+e.getClass().getSimpleName());
            if(streaming) video.cameraFailed();
            glError="Camera render failed: "+e.getClass().getSimpleName();
        }
    }
    private void encodeJpeg(long started,long rendered) {
        try {
            if(closed) { frames.failed("Video session closed"); return; }
            long encodeStarted=System.nanoTime();
            CameraPixels.toArgb(rgba,HeadCamera.WIDTH,HeadCamera.HEIGHT,argb);
            if(bitmap==null) bitmap=Bitmap.createBitmap(HeadCamera.WIDTH,HeadCamera.HEIGHT,Bitmap.Config.ARGB_8888);
            bitmap.setPixels(argb,0,HeadCamera.WIDTH,0,0,HeadCamera.WIDTH,HeadCamera.HEIGHT);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(64*1024);
            if(!bitmap.compress(Bitmap.CompressFormat.JPEG,70,bytes)) { frames.failed("JPEG compression failed"); return; }
            frames.publish(bytes.toByteArray(),HeadCamera.WIDTH,HeadCamera.HEIGHT,started,(rendered-started)/1e6,
                (System.nanoTime()-encodeStarted)/1e6);
        } catch(RuntimeException e) { frames.failed("JPEG failed: "+e.getClass().getSimpleName()); }
    }
    CapturedReport.Fields captureReport() {
        VideoFrames.Status s=status(); RtcVideo video=rtc;
        CapturedReport.Fields rtcFields=video==null?null:video.captureReport();
        boolean ready=glReady; String error=glError,rendererGpu=gpu; long rendered=renderCount,drops=textureDrops;
        return data->{
            data.put("video_backend","independent head-camera EGL Surface / WebRTC hardware texture + JPEG fallback");
            data.put("video_target_fps",FPS); data.put("rtc_target_fps",RTC_FPS);
            data.put("video_source","head_camera"); data.put("video_mount_rotate",HeadCamera.MOUNT_ROTATE);
            data.put("video_orientation_correction","desktop local camera X 180 degrees; RTC rotation 90 or JPEG CSS 90");
            data.put("camera_context_independent",true); data.put("camera_surface","SurfaceTexture window / no View"); data.put("camera_gl_ready",ready); data.put("camera_gl_renderer",rendererGpu);
            data.put("camera_frames_rendered",rendered); data.put("camera_texture_backpressure_drops",drops); data.put("camera_gl_error",error);
            data.put("video_enabled",s.enabled); data.put("video_demand_active",s.demand); data.put("video_copy_pending",s.busy);
            data.put("video_capture_attempts",s.attempts); data.put("video_frames_published",s.published); data.put("video_frames_served",s.served);
            data.put("video_capture_failures",s.failures); data.put("video_width",s.width); data.put("video_height",s.height);
            data.put("video_jpeg_bytes",s.bytes); data.put("video_copy_ms",s.copyMs); data.put("video_encode_ms",s.encodeMs);
            data.put("video_frame_age_ms",s.ageMs); data.put("video_error",s.error);
            if(rtcFields!=null) rtcFields.putInto(data);
        };
    }
    @Override public void close() {
        if(closed) return;
        closed=true; frames.close();
        camera.post(this::stopCamera);
        encode.post(()->{ if(bitmap!=null) { bitmap.recycle(); bitmap=null; } }); encoderThread.quitSafely();
    }
    private void stopCamera() {
        if(stopping) return; stopping=true;
        camera.removeCallbacks(tick);
        RtcVideo video=rtc;
        if(video!=null) video.close(()->camera.post(()->{ rtcDisposed=true; releaseCameraIfIdle(); }));
        else { rtcDisposed=true; releaseCameraIfIdle(); }
    }
    private void releaseCameraIfIdle() {
        if(!rtcDisposed) return;
        try {
            if(textureHelper!=null) textureHelper.stopListening();
            if(egl!=null) {
                if(egl.hasSurface()) { egl.makeCurrent(); renderer.release(); }
                egl.release(); egl=null;
            }
            if(textureHelper!=null) { textureHelper.dispose(); textureHelper=null; }
        } finally { glReady=false; latest=null; cameraThread.quitSafely(); }
    }
}
