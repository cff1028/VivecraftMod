package org.vivecraft.client_vr.provider.openxr;

import com.sun.jna.Platform;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.WGL;
import org.lwjgl.openxr.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Struct;
import org.lwjgl.system.linux.X11;
import org.vivecraft.client_vr.settings.VRSettings;
import org.vivecraft.util.VLoader;

import java.util.Objects;

import static org.lwjgl.opengl.GLX13.*;
import static org.lwjgl.system.MemoryStack.stackInts;
import static org.lwjgl.system.MemoryUtil.NULL;

public interface DeviceCompat {
    default void initializeLoader(MemoryStack stack) {}

    long instancePlatformInfo(MemoryStack stack);

    String getGraphicsExtension();

    XrSwapchainImageOpenGLKHR.Buffer createImageBuffers(int imageCount, MemoryStack stack);

    Struct checkGraphics(MemoryStack stack, XrInstance instance, long systemID);

    static DeviceCompat detectDevice() {
        return isAndroid() ? new Mobile() : new Desktop();
    }

    static boolean isAndroid() {
        return System.getProperty("os.version", "").toLowerCase(java.util.Locale.ROOT).contains("android") ||
            System.getProperty("java.runtime.name", "").toLowerCase(java.util.Locale.ROOT).contains("android");
    }

    class Desktop implements DeviceCompat {
        @Override
        public long instancePlatformInfo(MemoryStack stack) {
            VRSettings.LOGGER.info("Platform: {}", System.getProperty("os.version"));
            return NULL;
        }

        @Override
        public String getGraphicsExtension() {
            return KHROpenGLEnable.XR_KHR_OPENGL_ENABLE_EXTENSION_NAME;
        }

        @Override
        public XrSwapchainImageOpenGLKHR.Buffer createImageBuffers(int imageCount, MemoryStack stack) {
            XrSwapchainImageOpenGLKHR.Buffer swapchainImageBuffer = XrSwapchainImageOpenGLKHR.calloc(imageCount, stack);
            for (XrSwapchainImageOpenGLKHR image : swapchainImageBuffer) {
                image.type(KHROpenGLEnable.XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_KHR);
            }

            return swapchainImageBuffer;
        }

        @Override
        public Struct checkGraphics(MemoryStack stack, XrInstance instance, long systemID) {
            XrGraphicsRequirementsOpenGLKHR graphicsRequirements = XrGraphicsRequirementsOpenGLKHR.calloc(stack)
                .type(KHROpenGLEnable.XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_KHR);
            KHROpenGLEnable.xrGetOpenGLGraphicsRequirementsKHR(instance, systemID, graphicsRequirements);

            if (Platform.getOSType() == Platform.WINDOWS) {
                long deviceContext = WGL.wglGetCurrentDC();
                long glContext = WGL.wglGetCurrentContext(null);
                if (deviceContext == NULL || glContext == NULL) {
                    throw new IllegalStateException("OpenXR requires a current WGL context");
                }
                return XrGraphicsBindingOpenGLWin32KHR.calloc(stack).set(
                    KHROpenGLEnable.XR_TYPE_GRAPHICS_BINDING_OPENGL_WIN32_KHR,
                    NULL,
                    deviceContext,
                    glContext
                );
            } else if (Platform.getOSType() == Platform.LINUX) {
                long xDisplay = glXGetCurrentDisplay();
                long glXContext = glXGetCurrentContext();
                long glXWindowHandle = glXGetCurrentDrawable();
                if (xDisplay == NULL || glXContext == NULL || glXWindowHandle == NULL) {
                    throw new IllegalStateException("OpenXR requires a current GLX context");
                }

                int fbXID = glXQueryDrawable(xDisplay, glXWindowHandle, GLX_FBCONFIG_ID);
                PointerBuffer fbConfigBuf = glXChooseFBConfig(xDisplay, X11.XDefaultScreen(xDisplay),
                    stackInts(GLX_FBCONFIG_ID, fbXID, 0));
                if (fbConfigBuf == null) {
                    throw new IllegalStateException("Your framebuffer config was null, make a github issue");
                }
                long fbConfig = fbConfigBuf.get();

                return XrGraphicsBindingOpenGLXlibKHR.calloc(stack).set(
                    KHROpenGLEnable.XR_TYPE_GRAPHICS_BINDING_OPENGL_XLIB_KHR,
                    NULL,
                    xDisplay,
                    (int) Objects.requireNonNull(glXGetVisualFromFBConfig(xDisplay, fbConfig)).visualid(),
                    fbConfig,
                    glXWindowHandle,
                    glXContext
                );
            } else {
                throw new IllegalStateException("Macos not supported");
            }
        }
    }

    class Mobile implements DeviceCompat {
        private static boolean loaderReady;
        private static boolean applicationLoader;

        static synchronized boolean initializeApplicationLoader(MemoryStack stack) {
            if (loaderReady) return applicationLoader;
            long context;
            try {
                context = VLoader.getDalvikApplicationContext();
            } catch (UnsatisfiedLinkError olderLauncher) {
                return false;
            }
            if (context == NULL) throw new IllegalStateException("FCL returned no Android Application context");
            initializeAndroidLoader(stack, context);
            applicationLoader = true;
            return true;
        }

        private static void initializeAndroidLoader(MemoryStack stack, long context) {
            long vm = VLoader.getDalvikVM();
            if (vm == NULL || context == NULL) throw new IllegalStateException("Missing Android OpenXR loader context");
            var info = XrLoaderInitInfoAndroidKHR.calloc(stack).set(
                KHRLoaderInitAndroid.XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR, NULL, vm, context);
            MCOpenXR.checkResult(KHRLoaderInit.xrInitializeLoaderKHR(
                XrLoaderInitInfoBaseHeaderKHR.create(info.address())), "xrInitializeLoaderKHR");
            loaderReady = true;
        }

        @Override
        public void initializeLoader(MemoryStack stack) {
            synchronized (Mobile.class) {
                if (loaderReady || initializeApplicationLoader(stack)) return;
                VLoader.setupAndroid();
                initializeAndroidLoader(stack, requestActivity());
            }
        }

        private static long requestActivity() {
            long started = System.nanoTime();
            VRSettings.LOGGER.info("Vivecraft: requesting immersive Activity for OpenXR");
            long activity = VLoader.getDalvikActivity();
            if (activity == NULL) throw new IllegalStateException("FCL returned no immersive Activity");
            VRSettings.LOGGER.info("Vivecraft: immersive Activity resumed after {} ms",
                (System.nanoTime() - started) / 1_000_000);
            return activity;
        }

        @Override
        public long instancePlatformInfo(MemoryStack stack) {
            return XrInstanceCreateInfoAndroidKHR.calloc(stack).set(
                KHRAndroidCreateInstance.XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR, NULL,
                VLoader.getDalvikVM(), requestActivity()).address();
        }

        @Override
        public String getGraphicsExtension() {
            return KHROpenGLESEnable.XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME;
        }

        @Override
        public XrSwapchainImageOpenGLKHR.Buffer createImageBuffers(int imageCount, MemoryStack stack) {
            XrSwapchainImageOpenGLKHR.Buffer swapchainImageBuffer = XrSwapchainImageOpenGLKHR.calloc(imageCount, stack);
            for (XrSwapchainImageOpenGLKHR image : swapchainImageBuffer) {
                image.type(KHROpenGLESEnable.XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR);
            }

            return swapchainImageBuffer;
        }

        @Override
        public Struct checkGraphics(MemoryStack stack, XrInstance instance, long systemID) {
            XrGraphicsRequirementsOpenGLESKHR graphicsRequirements = XrGraphicsRequirementsOpenGLESKHR.calloc(stack).type(KHROpenGLESEnable.XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR);
            MCOpenXR.checkResult(KHROpenGLESEnable.xrGetOpenGLESGraphicsRequirementsKHR(
                instance, systemID, graphicsRequirements), "xrGetOpenGLESGraphicsRequirementsKHR");
            if (VLoader.getEGLDisplay() == NULL || VLoader.getEGLContext() == NULL) {
                throw new IllegalStateException("QuestCraft vloader did not supply the EGL display/context");
            }
            XrGraphicsBindingOpenGLESAndroidKHR graphicsBinding = XrGraphicsBindingOpenGLESAndroidKHR.calloc(stack);
            graphicsBinding.set(
                KHROpenGLESEnable.XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR,
                NULL,
                VLoader.getEGLDisplay(),
                VLoader.getEGLConfig(),
                VLoader.getEGLContext()
            );

            return graphicsBinding;
        }
    }
}
