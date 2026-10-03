package com.microduck.sim;

import android.content.Context;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import org.json.JSONObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.function.Consumer;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

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
    void meshes(float[][] meshes) { scene.source=meshes; requestRender(); }
    void frame(float[] packet) { scene.latest=new SceneFrame(packet,System.nanoTime()); requestRender(); }
    void follow(boolean enabled) { scene.follow=enabled; requestRender(); }
    void contacts(boolean enabled) { scene.showContacts=enabled; requestRender(); }
    void resetCamera() { scene.azimuth=-50; scene.elevation=25; scene.distance=.75f; requestRender(); }
    long frames() { return scene.totalFrames; }
    boolean ready() { return scene.ready; }
    void annotate(JSONObject data) throws Exception {
        data.put("render_backend","OpenGL ES 3 custom mesh renderer"); data.put("render_ready",scene.ready);
        data.put("gl_renderer",scene.gpu); data.put("gl_version",scene.glVersion);
        data.put("render_frames",scene.totalFrames); data.put("render_fps",scene.fps);
        data.put("render_cpu_ms",scene.cpuMs); data.put("rendered_sim_time",scene.simTime);
        data.put("render_meshes",scene.meshCount); data.put("render_unique_triangles",scene.triangles);
        data.put("render_error",scene.problem); data.put("camera_follow",scene.follow);
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

    private static final class SceneRenderer implements Renderer {
        volatile float[][] source;
        volatile SceneFrame latest;
        volatile float azimuth=-50,elevation=25,distance=.75f;
        volatile boolean follow=true,showContacts=true,ready;
        volatile String gpu="",glVersion="",problem="";
        volatile long totalFrames;
        volatile double fps,cpuMs,simTime;
        volatile int meshCount,triangles;
        Consumer<String> error;
        private float[][] uploaded;
        private int[] buffers,vertices;
        private int program,ground,points,lines,uModel,uVP,uColor,uMode,width=1,height=1;
        private long fpsAt,fpsFrames,lastPacketNs;
        private final float[] projection=new float[16],view=new float[16],vp=new float[16],model=new float[16];
        private FloatBuffer contactVertices;
        private final float[] trail=new float[512*6];
        private int trailCount;
        private float trailTime=-1;
        private final FloatBuffer trailVertices=direct(new float[512*6]);
        private static final String VERTEX=
            "#version 300 es\n"+
            "layout(location=0) in vec3 aPosition; layout(location=1) in vec3 aNormal;\n"+
            "uniform mat4 uModel,uVP; out vec3 world,normal;\n"+
            "void main(){vec4 p=uModel*vec4(aPosition,1);world=p.xyz;normal=mat3(uModel)*aNormal;"+
            "gl_Position=uVP*p;gl_PointSize=10.0;}\n";
        private static final String FRAGMENT=
            "#version 300 es\nprecision highp float;\n"+
            "in vec3 world,normal; uniform vec4 uColor; uniform int uMode; out vec4 frag;\n"+
            "void main(){vec3 color=uColor.rgb;"+
            "if(uMode==2){if(length(gl_PointCoord-vec2(.5))>.5)discard;frag=uColor;return;}"+
            "if(uMode==3){frag=uColor;return;}"+
            "if(uMode==1){float c=mod(floor(world.x*10.0)+floor(world.y*10.0),2.0);"+
            "color=mix(vec3(.66,.73,.76),vec3(.8,.85,.86),c);"+
            "vec2 q=world.xy*10.0;vec2 edge=abs(fract(q-.5)-.5)/max(fwidth(q),vec2(.0001));"+
            "float grid=1.0-min(min(edge.x,edge.y),1.0);color=mix(color,vec3(.42,.51,.56),grid*.6);}"+
            "float diffuse=max(dot(normalize(normal),normalize(vec3(.4,-.5,.9))),0.0);"+
            "color*=.38+.62*diffuse;frag=vec4(pow(max(color,vec3(0)),vec3(.4545)),uColor.a);}\n";

        @Override public void onSurfaceCreated(GL10 ignored,EGLConfig config) {
            ready=false; uploaded=null; buffers=null; problem="";
            gpu=GLES30.glGetString(GLES30.GL_RENDERER); glVersion=GLES30.glGetString(GLES30.GL_VERSION);
            try {
                program=GLES30.glCreateProgram(); int v=shader(GLES30.GL_VERTEX_SHADER,VERTEX),f=shader(GLES30.GL_FRAGMENT_SHADER,FRAGMENT);
                GLES30.glAttachShader(program,v); GLES30.glAttachShader(program,f); GLES30.glLinkProgram(program);
                GLES30.glDeleteShader(v); GLES30.glDeleteShader(f);
                int[] status=new int[1]; GLES30.glGetProgramiv(program,GLES30.GL_LINK_STATUS,status,0);
                if(status[0]==0) throw new IllegalStateException(GLES30.glGetProgramInfoLog(program));
                uModel=GLES30.glGetUniformLocation(program,"uModel"); uVP=GLES30.glGetUniformLocation(program,"uVP");
                uColor=GLES30.glGetUniformLocation(program,"uColor"); uMode=GLES30.glGetUniformLocation(program,"uMode");
                ground=upload(new float[]{-50,-50,0,0,0,1, 50,-50,0,0,0,1, 50,50,0,0,0,1,
                    -50,-50,0,0,0,1, 50,50,0,0,0,1, -50,50,0,0,0,1});
                int[] ids=new int[2]; GLES30.glGenBuffers(2,ids,0); points=ids[0]; lines=ids[1];
                GLES30.glEnable(GLES30.GL_DEPTH_TEST); GLES30.glDisable(GLES30.GL_CULL_FACE);
                GLES30.glClearColor(.12f,.19f,.25f,1); fpsAt=System.nanoTime(); fpsFrames=totalFrames;
            } catch(RuntimeException e) { failed(e); }
        }
        @Override public void onSurfaceChanged(GL10 ignored,int w,int h) {
            width=Math.max(w,1); height=Math.max(h,1); GLES30.glViewport(0,0,width,height);
        }
        @Override public void onDrawFrame(GL10 ignored) {
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT|GLES30.GL_DEPTH_BUFFER_BIT);
            if(!problem.isEmpty()) return;
            float[][] meshes=source; SceneFrame frame=latest;
            if(meshes==null || frame==null) return;
            long start=System.nanoTime();
            try {
                if(uploaded!=meshes) {
                    if(buffers!=null) GLES30.glDeleteBuffers(buffers.length,buffers,0);
                    buffers=new int[meshes.length]; vertices=new int[meshes.length]; int sum=0;
                    for(int i=0;i<meshes.length;i++) {
                        if(meshes[i].length%18!=0) throw new IllegalArgumentException("Invalid mesh triangle packet");
                        buffers[i]=upload(meshes[i]); vertices[i]=meshes[i].length/6; sum+=vertices[i]/3;
                    }
                    uploaded=meshes; meshCount=meshes.length; triangles=sum;
                }
                float[] data=frame.values;
                float cx=follow?data[4]:0,cy=follow?data[5]:0,cz=follow?Math.max(.1f,data[6]+.05f):.15f;
                double a=Math.toRadians(azimuth),e=Math.toRadians(elevation); float dist=distance;
                Matrix.setLookAtM(view,0,cx+(float)(dist*Math.cos(e)*Math.cos(a)),cy+(float)(dist*Math.cos(e)*Math.sin(a)),
                    cz+(float)(dist*Math.sin(e)),cx,cy,cz,0,0,1);
                Matrix.perspectiveM(projection,0,45,(float)width/height,.01f,100);
                Matrix.multiplyMM(vp,0,projection,0,view,0); GLES30.glUseProgram(program);
                GLES30.glUniformMatrix4fv(uVP,1,false,vp,0);
                Matrix.setIdentityM(model,0); model[14]=data[7];
                draw(ground,6,GLES30.GL_TRIANGLES,model,.7f,.8f,.8f,1,1);
                for(int g=0;g<frame.geoms;g++) {
                    int at=SceneFrame.HEADER+g*SceneFrame.STRIDE,id=(int)data[at];
                    if(id<0 || id>=buffers.length || data[at]!=id) throw new IllegalArgumentException("Invalid visual mesh id");
                    frame.modelMatrix(g,model);
                    draw(buffers[id],vertices[id],GLES30.GL_TRIANGLES,model,data[at+13],data[at+14],data[at+15],data[at+16],0);
                }
                Matrix.setIdentityM(model,0); updateTrail(frame);
                if(trailCount>1) {
                    trailVertices.clear(); trailVertices.put(trail,0,trailCount*6).flip();
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,lines);
                    GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,trailCount*24,trailVertices,GLES30.GL_DYNAMIC_DRAW);
                    draw(lines,trailCount,GLES30.GL_LINE_STRIP,model,1,.48f,.1f,1,3);
                }
                if(showContacts && frame.contacts>0) {
                    int required=frame.contacts*6;
                    if(contactVertices==null || contactVertices.capacity()<required)
                        contactVertices=ByteBuffer.allocateDirect(required*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
                    contactVertices.clear(); int at=SceneFrame.HEADER+frame.geoms*SceneFrame.STRIDE;
                    for(int c=0;c<frame.contacts;c++) contactVertices.put(new float[]{data[at++],data[at++],data[at++]+.002f,0,0,1});
                    contactVertices.flip(); GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,points);
                    GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,required*4,contactVertices,GLES30.GL_DYNAMIC_DRAW);
                    draw(points,frame.contacts,GLES30.GL_POINTS,model,1,.18f,.12f,1,2);
                }
                int glError=GLES30.glGetError(); if(glError!=GLES30.GL_NO_ERROR) throw new IllegalStateException("GLES error 0x"+Integer.toHexString(glError));
                ready=true; simTime=data[3]; totalFrames++; cpuMs=(System.nanoTime()-start)/1e6;
                long now=System.nanoTime(); if(now-fpsAt>=1_000_000_000L) {
                    fps=(totalFrames-fpsFrames)*1e9/(now-fpsAt); fpsAt=now; fpsFrames=totalFrames;
                }
            } catch(RuntimeException e) { failed(e); }
        }
        private void updateTrail(SceneFrame frame) {
            if(lastPacketNs==frame.capturedNs) return; lastPacketNs=frame.capturedNs;
            float[] d=frame.values;
            if(d[3]<trailTime || d[3]==0) trailCount=0;
            trailTime=d[3];
            if(trailCount==512) { System.arraycopy(trail,6,trail,0,511*6); trailCount=511; }
            int at=trailCount++*6; trail[at]=d[4]; trail[at+1]=d[5]; trail[at+2]=d[7]+.002f;
            trail[at+3]=0; trail[at+4]=0; trail[at+5]=1;
        }
        private void draw(int buffer,int count,int primitive,float[] transform,float r,float g,float b,float a,int mode) {
            GLES30.glUniformMatrix4fv(uModel,1,false,transform,0); GLES30.glUniform4f(uColor,r,g,b,a); GLES30.glUniform1i(uMode,mode);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,buffer); GLES30.glEnableVertexAttribArray(0); GLES30.glEnableVertexAttribArray(1);
            GLES30.glVertexAttribPointer(0,3,GLES30.GL_FLOAT,false,24,0); GLES30.glVertexAttribPointer(1,3,GLES30.GL_FLOAT,false,24,12);
            GLES30.glDrawArrays(primitive,0,count);
        }
        private static int shader(int type,String code) {
            int shader=GLES30.glCreateShader(type); GLES30.glShaderSource(shader,code); GLES30.glCompileShader(shader);
            int[] status=new int[1]; GLES30.glGetShaderiv(shader,GLES30.GL_COMPILE_STATUS,status,0);
            if(status[0]==0) throw new IllegalStateException(GLES30.glGetShaderInfoLog(shader)); return shader;
        }
        private static int upload(float[] values) {
            int[] id=new int[1]; GLES30.glGenBuffers(1,id,0); GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,id[0]);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,values.length*4,direct(values),GLES30.GL_STATIC_DRAW); return id[0];
        }
        private static FloatBuffer direct(float[] values) {
            FloatBuffer out=ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer(); out.put(values).flip(); return out;
        }
        private void failed(RuntimeException e) { ready=false; problem=e.toString(); error.accept(problem); }
    }
}
