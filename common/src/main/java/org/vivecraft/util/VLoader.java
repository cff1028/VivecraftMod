package org.vivecraft.util;

public class VLoader {
    static {
        System.loadLibrary("vloader");
    }

    public static native long getVulkanFlatSurfaceGeneration();
    public static native int createVulkanFlatSurface(long generation, long getInstanceProcAddr, long instance, long[] surfaceOut);
    public static native void onVulkanFlatFramePresented(long generation);

    public static native long getEGLContext();
    public static native long getEGLConfig();
    public static native long getEGLDisplay();
    public static native long getDalvikVM();
    public static native long getDalvikActivity();
    public static native long getDalvikApplicationContext();
    public static native void setupAndroid();
}
