package com.microduck.sim;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import java.util.function.Consumer;

/** GLES owns GPU resources; it never holds a native handle or touches mjModel/mjData. */
final class RobotSurfaceView extends GLSurfaceView {
    private final SceneRenderer scene=new SceneRenderer();
    private final ScaleGestureDetector scale;
    private float lastX,lastY;
    RobotSurfaceView(Context context,Consumer<String> error) {
        super(context);
        scene.error=error;
        setEGLContextClientVersion(3); setEGLConfigChooser(8,8,8,0,24,0);
        setPreserveEGLContextOnPause(true); setRenderer(scene); setRenderMode(RENDERMODE_WHEN_DIRTY);
        scale=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                scene.distance=Math.max(.3f,Math.min(5,scene.distance/detector.getScaleFactor()));
                requestRender(); return true;
            }
        });
    }
    void meshes(VisualMesh[] meshes) { scene.source=meshes; requestRender(); }
    void frame(SceneFrame frame) { scene.latest=frame; requestRender(); }
    void follow(boolean enabled) { scene.follow=enabled; requestRender(); }
    void contacts(boolean enabled) { scene.showContacts=enabled; requestRender(); }
    void resetCamera() { scene.azimuth=-50; scene.elevation=25; scene.distance=.75f; requestRender(); }
    long frames() { return scene.totalFrames; }
    boolean ready() { return scene.ready; }
    CapturedReport.Fields captureReport() {
        boolean ready=scene.ready,follow=scene.follow;
        SceneFrame frame=scene.latest;
        boolean hasCamera=frame!=null && frame.camera!=null;
        float fovy=hasCamera?frame.camera.fovy:0;
        String gpu=scene.gpu,version=scene.glVersion,error=scene.problem;
        long frames=scene.totalFrames,bytes=scene.meshBytes;
        double fps=scene.fps,cpuMs=scene.cpuMs,simTime=scene.simTime;
        int meshes=scene.meshCount,triangles=scene.triangles,vertices=scene.uniqueVertices;
        return data->{
            data.put("render_backend","OpenGL ES 3 custom mesh renderer"); data.put("render_ready",ready);
            data.put("gl_renderer",gpu); data.put("gl_version",version);
            data.put("render_frames",frames); data.put("render_fps",fps);
            data.put("render_cpu_ms",cpuMs); data.put("rendered_sim_time",simTime);
            data.put("render_meshes",meshes); data.put("render_unique_triangles",triangles);
            data.put("render_unique_vertices",vertices); data.put("render_mesh_bytes",bytes);
            data.put("render_mesh_format","indexed position-normal pairs / uint32");
            data.put("render_error",error); data.put("camera_follow",follow);
            data.put("head_camera_available",hasCamera); data.put("head_camera_fovy_degrees",fovy);
            data.put("head_camera_back_face_culling",true);
        };
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        getParent().requestDisallowInterceptTouchEvent(true); scale.onTouchEvent(event);
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN) { lastX=event.getX(); lastY=event.getY(); }
        if(event.getActionMasked()==MotionEvent.ACTION_MOVE && !scale.isInProgress() && event.getPointerCount()==1) {
            scene.azimuth+=(event.getX()-lastX)*.3f;
            scene.elevation=Math.max(5,Math.min(85,scene.elevation+(event.getY()-lastY)*.25f));
            lastX=event.getX(); lastY=event.getY(); requestRender();
        }
        if(event.getActionMasked()==MotionEvent.ACTION_UP) performClick();
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }

}
