package com.microduck.sim;

/** Two GL consumers share only immutable CPU data; neither reads the native simulator. */
final class CameraScene {
    final VisualMesh[] meshes;
    final SceneFrame frame;
    CameraScene(VisualMesh[] meshes,SceneFrame frame) { this.meshes=meshes; this.frame=frame; }
    boolean available() { return meshes!=null && frame!=null && frame.camera!=null; }
}
