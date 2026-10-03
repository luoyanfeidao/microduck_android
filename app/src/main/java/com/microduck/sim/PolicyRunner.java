package com.microduck.sim;

import android.content.Context;
import ai.onnxruntime.*;
import org.json.*;
import java.io.*;
import java.nio.FloatBuffer;
import java.security.MessageDigest;
import java.util.*;

/** CPU-only, one inference thread. Sessions and tensors stay on the simulation worker. */
final class PolicyRunner implements AutoCloseable {
    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final Map<String,OrtSession> sessions = new HashMap<>();
    private final Map<String,String> hashes = new HashMap<>();
    private final float[] previous = new float[14], command = new float[13];
    String active = "none";
    double inferenceMs, smokeMaxError;
    boolean filter = true;

    PolicyRunner(Context context) throws Exception {
        try {
            JSONObject manifest = new JSONObject(new String(read(context,"policies/manifest.json"),java.nio.charset.StandardCharsets.UTF_8));
            JSONArray entries = manifest.getJSONArray("policies");
            for(int i=0;i<entries.length();i++) {
                JSONObject entry=entries.getJSONObject(i);
                byte[] model=read(context,"policies/"+entry.getString("file"));
                String sha=hex(MessageDigest.getInstance("SHA-256").digest(model));
                if(!sha.equals(entry.getString("sha256"))) throw new IllegalStateException("Policy checksum mismatch");
                String name=entry.getString("name");
                try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()) {
                    options.setIntraOpNumThreads(1); options.setInterOpNumThreads(1);
                    options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
                    options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                    OrtSession session=env.createSession(model,options); sessions.put(name,session);
                    validate(session); hashes.put(name,sha);
                }
            }
            if(!sessions.containsKey("alpha_stand") || !sessions.containsKey("alpha_walking"))
                throw new IllegalStateException("Stand/walk pair missing");
            JSONArray tests=new JSONObject(new String(read(context,"policies/smoke.json"),java.nio.charset.StandardCharsets.UTF_8)).getJSONArray("tests");
            for(int i=0;i<tests.length();i++) {
                JSONObject test=tests.getJSONObject(i); String name=test.getString("policy");
                if(!hashes.get(name).equals(test.getString("sha256"))) throw new IllegalStateException("Smoke fixture belongs to a different policy; regenerate it");
                float[] out=infer(sessions.get(name),floats(test.getJSONArray("observation"),61));
                float[] expected=floats(test.getJSONArray("actions"),14);
                for(int j=0;j<14;j++) smokeMaxError=Math.max(smokeMaxError,Math.abs(out[j]-expected[j]));
            }
            if(smokeMaxError>1e-3) throw new IllegalStateException("Desktop/device ONNX smoke mismatch: "+smokeMaxError);
        } catch(Exception e) { close(); throw e; }
    }
    private static void validate(OrtSession s) throws Exception {
        if(s.getInputInfo().size()!=1 || s.getOutputInfo().size()!=1 ||
            !s.getInputInfo().containsKey("obs") || !s.getOutputInfo().containsKey("actions"))
            throw new IllegalStateException("M2 supports feedforward obs/actions only");
        check(s.getInputInfo().get("obs"),61); check(s.getOutputInfo().get("actions"),14);
    }
    private static void check(NodeInfo node,int width) {
        if(!(node.getInfo() instanceof TensorInfo)) throw new IllegalStateException("Expected tensor");
        TensorInfo t=(TensorInfo)node.getInfo();
        if(t.type!=OnnxJavaType.FLOAT || !Arrays.equals(t.getShape(),new long[]{1,width}))
            throw new IllegalStateException("Expected float32[1,"+width+"]");
    }
    private float[] infer(OrtSession session,float[] obs) throws Exception {
        try(OnnxTensor input=OnnxTensor.createTensor(env,FloatBuffer.wrap(obs),new long[]{1,61});
            OrtSession.Result result=session.run(Collections.singletonMap("obs",input))) {
            float[][] matrix=(float[][])result.get("actions").get().getValue();
            if(matrix.length!=1 || matrix[0].length!=14) throw new IllegalStateException("Invalid action shape");
            float[] out=matrix[0].clone();
            for(float v:out) if(!Float.isFinite(v)) throw new IllegalStateException("Non-finite action");
            return out;
        }
    }
    void reset() { Arrays.fill(previous,0); Arrays.fill(command,0); active="none"; inferenceMs=0; }
    void velocity(float vx,float vy,float yaw) { command[0]=vx; command[1]=vy; command[2]=yaw; }
    void tick(long handle) throws Exception {
        boolean standing=Math.sqrt(command[0]*command[0]+command[1]*command[1]+command[2]*command[2])<.05;
        active=standing?"alpha_stand":"alpha_walking";
        float[] obs=NativeSim.observation(handle,previous,command);
        long start=System.nanoTime(); float[] action=infer(sessions.get(active),obs);
        inferenceMs=(System.nanoTime()-start)/1e6;
        NativeSim.applyAction(handle,action,standing,filter);
        System.arraycopy(action,0,previous,0,14); // Feedback remains raw, before scale/filter.
    }
    void annotate(JSONObject data) throws JSONException {
        data.put("policy",active); data.put("onnx_runtime",env.getVersion());
        data.put("policy_smoke_max_abs_error",smokeMaxError); data.put("inference_ms",inferenceMs);
        data.put("action_filter",filter?"runtime defaults: head .5 / legs .7":"disabled");
        data.put("velocity_command",new JSONArray(new double[]{command[0],command[1],command[2]}));
        if(hashes.containsKey(active)) data.put("policy_sha256",hashes.get(active));
    }
    @Override public void close() {
        for(OrtSession s:sessions.values()) try { s.close(); } catch(OrtException ignored) {}
        sessions.clear(); // OrtEnvironment is shared process-wide; do not close it here.
    }
    private static byte[] read(Context c,String path) throws IOException {
        try(InputStream in=c.getAssets().open(path); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[65536]; int n; while((n=in.read(buffer))!=-1) out.write(buffer,0,n); return out.toByteArray();
        }
    }
    private static String hex(byte[] bytes) {
        StringBuilder s=new StringBuilder(); for(byte b:bytes) s.append(String.format(Locale.ROOT,"%02x",b&255)); return s.toString();
    }
    private static float[] floats(JSONArray a,int width) throws JSONException {
        if(a.length()!=width) throw new IllegalStateException("Fixture width mismatch");
        float[] out=new float[width]; for(int i=0;i<width;i++) out[i]=(float)a.getDouble(i); return out;
    }
}
