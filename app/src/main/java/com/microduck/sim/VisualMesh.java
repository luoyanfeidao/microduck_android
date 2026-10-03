package com.microduck.sim;

/** Worker creates once; GL reads without mutation, including after context loss. */
final class VisualMesh {
    final float[] vertices;
    final int[] indices;
    VisualMesh(float[] vertices,int[] indices) {
        if(vertices==null || indices==null || vertices.length%6!=0 || indices.length%3!=0)
            throw new IllegalArgumentException("Invalid indexed mesh packet");
        for(float value:vertices) if(!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite mesh vertex");
        for(int index:indices) if(index<0 || index>=vertices.length/6) throw new IllegalArgumentException("Invalid mesh index");
        this.vertices=vertices; this.indices=indices;
    }
}
