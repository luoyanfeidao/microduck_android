package com.microduck.sim;

import java.nio.ByteBuffer;

public final class HeadCameraCheck {
    public static void main(String[] args) {
        float[] packet=new float[SceneFrame.HEADER+SceneFrame.CAMERA_TAIL]; packet[0]=2;
        int at=SceneFrame.HEADER;
        if(new SceneFrame(packet,0).camera!=null) throw new AssertionError("Missing camera invented");
        packet[at]=1; packet[at+1]=45;
        packet[at+14]=.0035f; packet[at+15]=17.7f;
        packet[at+2]=2; packet[at+3]=3; packet[at+4]=4;
        // Camera right = world +Z, up = world +Y, backward = world -X: looks along +X.
        float[] rotation={0,0,-1, 0,1,0, 1,0,0};
        System.arraycopy(rotation,0,packet,at+5,9);
        SceneFrame frame=new SceneFrame(packet,1); float[] view=new float[16]; frame.camera.viewMatrix(view);
        assertPoint(view,new float[]{2,3,4},new float[]{0,0,0});
        assertPoint(view,new float[]{3,3,4},new float[]{0,0,-1});
        assertPoint(view,new float[]{2,4,4},new float[]{0,1,0});
        packet[at+2]=99; // Camera copy is independent of the JNI packet's later mutation.
        frame.camera.viewMatrix(view); assertPoint(view,new float[]{2,3,4},new float[]{0,0,0});
        packet[at+1]=0; reject(packet);
        packet[at+1]=45; packet[at+5]=.5f; reject(packet);
        packet[at+5]=0; packet[at+7]=1; reject(packet); // Reflection is not a camera rotation.
        packet[at+7]=-1; packet[at+15]=packet[at+14]; reject(packet);
        ByteBuffer rgba=ByteBuffer.allocateDirect(16);
        // GL bottom row red/green, top row blue/white. ARGB must preserve channels and flip Y.
        byte[] pixels={(byte)255,0,0,(byte)255, 0,(byte)255,0,(byte)255,
            0,0,(byte)255,(byte)255, (byte)255,(byte)255,(byte)255,(byte)255};
        rgba.put(pixels); int[] argb=new int[4]; CameraPixels.toArgb(rgba,2,2,argb);
        if(argb[0]!=0xff0000ff || argb[1]!=0xffffffff || argb[2]!=0xffff0000 || argb[3]!=0xff00ff00)
            throw new AssertionError("Camera readback channels/origin incorrect");
        try { CameraPixels.toArgb(rgba,2,1,argb); throw new AssertionError("Wrong pixel buffer accepted"); }
        catch(IllegalArgumentException expected) { /* Buffer sizes must agree. */ }
        System.out.println("{\"camera_pose_inverse\":true,\"forward_axis\":true,\"captured_pose_immutable\":true,"
            +"\"invalid_fov_rotation_and_reflection_rejected\":true,\"invalid_clipping_planes_rejected\":true,\"rgba_channels_and_vertical_flip\":true,\"pixel_buffer_size_checked\":true}");
    }
    private static void assertPoint(float[] view,float[] point,float[] expected) {
        for(int r=0;r<3;r++) {
            float value=view[12+r]; for(int c=0;c<3;c++) value+=view[c*4+r]*point[c];
            if(Math.abs(value-expected[r])>1e-6) throw new AssertionError("Incorrect camera transform");
        }
    }
    private static void reject(float[] packet) {
        try { new SceneFrame(packet,2); throw new AssertionError("Invalid camera accepted"); }
        catch(IllegalArgumentException expected) { /* Invalid intrinsics/extrinsics rejected. */ }
    }
}
