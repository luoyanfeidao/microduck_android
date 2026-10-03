package com.microduck.sim;

import java.util.Arrays;

/** Captured MuJoCo head_camera pose, with the desktop simulator's local X correction applied. */
final class HeadCamera {
    static final int WIDTH=640,HEIGHT=360,MOUNT_ROTATE=90;
    final float fovy,near,far;
    private final float[] position,rotation;
    HeadCamera(float[] packet,int at) {
        fovy=packet[at+1]; position=Arrays.copyOfRange(packet,at+2,at+5);
        rotation=Arrays.copyOfRange(packet,at+5,at+14);
        near=packet[at+14]; far=packet[at+15];
        if(!Float.isFinite(fovy) || fovy<=0 || fovy>=180) throw new IllegalArgumentException("Invalid camera FOV");
        if(!Float.isFinite(near) || !Float.isFinite(far) || near<=0 || far<=near)
            throw new IllegalArgumentException("Invalid camera clipping planes");
        for(float v:position) if(!Float.isFinite(v)) throw new IllegalArgumentException("Invalid camera position");
        for(float v:rotation) if(!Float.isFinite(v)) throw new IllegalArgumentException("Invalid camera rotation");
        for(int a=0;a<3;a++) for(int b=0;b<3;b++) {
            float dot=0; for(int k=0;k<3;k++) dot+=rotation[k*3+a]*rotation[k*3+b];
            if(Math.abs(dot-(a==b?1:0))>.001f) throw new IllegalArgumentException("Camera rotation is not orthonormal");
        }
        float determinant=rotation[0]*(rotation[4]*rotation[8]-rotation[5]*rotation[7])
            -rotation[1]*(rotation[3]*rotation[8]-rotation[5]*rotation[6])
            +rotation[2]*(rotation[3]*rotation[7]-rotation[4]*rotation[6]);
        if(Math.abs(determinant-1)>.001f) throw new IllegalArgumentException("Camera rotation is reflected");
    }
    void viewMatrix(float[] matrix) {
        if(matrix.length!=16) throw new IllegalArgumentException("Invalid camera matrix size");
        Arrays.fill(matrix,0);
        // Camera looks along local -Z, +Y up. Invert camera-to-world, then write column-major.
        for(int c=0;c<3;c++) for(int r=0;r<3;r++) matrix[c*4+r]=rotation[c*3+r];
        for(int r=0;r<3;r++) for(int k=0;k<3;k++) matrix[12+r]-=rotation[k*3+r]*position[k];
        matrix[15]=1;
    }
}
