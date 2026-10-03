package com.microduck.sim;

import java.util.Arrays;

/** Immutable-by-ownership CPU snapshot; only fresh JNI arrays may be passed in. */
final class SceneFrame {
    static final int HEADER=9, STRIDE=17;
    static final int CAMERA_TAIL=16;
    final float[] values;
    final long capturedNs;
    final int geoms, contacts;
    final HeadCamera camera;
    SceneFrame(float[] values,long capturedNs) {
        if(values==null || values.length<HEADER+CAMERA_TAIL || values[0]!=2)
            throw new IllegalArgumentException("Unsupported scene packet");
        geoms=(int)values[1]; contacts=(int)values[2];
        if(geoms<0 || geoms>10000 || contacts<0 || contacts>10000 ||
           values[1]!=geoms || values[2]!=contacts || values.length!=HEADER+geoms*STRIDE+contacts*3+CAMERA_TAIL)
            throw new IllegalArgumentException("Invalid scene packet size");
        for(float v:values) if(!Float.isFinite(v)) throw new IllegalArgumentException("Non-finite scene packet");
        int at=HEADER+geoms*STRIDE+contacts*3;
        if(values[at]!=0 && values[at]!=1) throw new IllegalArgumentException("Invalid head camera flag");
        camera=values[at]==1?new HeadCamera(values,at):null;
        this.values=values; this.capturedNs=capturedNs;
    }
    void modelMatrix(int geom,float[] matrix) {
        if(geom<0 || geom>=geoms || matrix.length!=16) throw new IllegalArgumentException("Invalid matrix request");
        int at=HEADER+geom*STRIDE;
        Arrays.fill(matrix,0);
        // MuJoCo row-major 3x3 -> GLES column-major 4x4, translation in final column.
        for(int row=0;row<3;row++) for(int col=0;col<3;col++) matrix[col*4+row]=values[at+4+row*3+col];
        for(int i=0;i<3;i++) matrix[12+i]=values[at+1+i];
        matrix[15]=1;
    }
}
