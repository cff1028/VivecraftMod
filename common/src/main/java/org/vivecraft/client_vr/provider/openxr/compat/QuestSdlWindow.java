package org.vivecraft.client_vr.provider.openxr.compat;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.sdl.SDL_DisplayMode;
import org.lwjgl.sdl.SDL_Rect;
import org.lwjgl.sdl.SDL_Surface;
import org.lwjgl.sdl.SDLStdinc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vivecraft.client_vr.provider.openxr.QuestVulkanContext;

import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.sdl.SDLEvents.*;
import static org.lwjgl.sdl.SDLPixels.SDL_PIXELFORMAT_RGBA8888;
import static org.lwjgl.sdl.SDLVideo.*;

public final class QuestSdlWindow {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vivecraft/QuestWindow");
    private static final Map<Long, WindowState> WINDOWS = new LinkedHashMap<>();
    private static final Set<Long> CONTEXTS = new HashSet<>();
    private static final Map<Integer, Integer> ATTRIBUTES = new HashMap<>();
    private static boolean initialized;
    private static long host;
    private static long mainWindow;
    private static long currentWindow;
    private static long currentContext;
    private static long nextContext = 0x100000000L;
    private static int nextWindowId = 1;
    private static int framebufferWidth;
    private static int framebufferHeight;
    private static SDL_DisplayMode displayMode;

    private QuestSdlWindow() {}

    private static final class WindowState {
        final int id;
        int width;
        int height;
        int x;
        int y;
        long flags;
        SDL_DisplayMode fullscreenMode;

        WindowState(int id, int width, int height, long flags) {
            this.id = id;
            this.width = width;
            this.height = height;
            this.flags = flags;
            if ((flags & SDL_WINDOW_HIDDEN) == 0) this.flags |= SDL_WINDOW_INPUT_FOCUS | SDL_WINDOW_MOUSE_FOCUS;
        }
    }

    public static boolean SDL_Init(int flags) {
        if (!initialized) initialized = GLFW.glfwInit();
        return initialized;
    }

    public static boolean SDL_InitSubSystem(int flags) {
        return SDL_Init(flags);
    }

    public static void SDL_Quit() {
        for (WindowState window : WINDOWS.values()) if (window.fullscreenMode != null) window.fullscreenMode.free();
        WINDOWS.clear();
        CONTEXTS.clear();
        releaseHost();
        if (displayMode != null) {
            displayMode.free();
            displayMode = null;
        }
        ATTRIBUTES.clear();
        mainWindow = currentWindow = currentContext = 0;
        if (initialized) {
            GLFW.glfwTerminate();
            initialized = false;
        }
    }

    private static WindowState window(long handle) {
        WindowState result = WINDOWS.get(handle);
        if (result == null) throw new IllegalArgumentException("Unknown Quest window: " + handle);
        return result;
    }

    public static boolean isWindow(long handle) { return WINDOWS.containsKey(handle); }
    public static long mainWindow() { return mainWindow; }
    public static int windowId(long handle) { WindowState state = WINDOWS.get(handle); return state == null ? 0 : state.id; }
    public static long windowFromId(int id) { return WINDOWS.containsKey((long) id) ? id : 0; }
    public static long nativeWindow(long handle) { window(handle); return host; }
    public static int getWidth(long handle) { return window(handle).width; }
    public static int getHeight(long handle) { return window(handle).height; }
    public static boolean isFocused(long handle) { return (window(handle).flags & SDL_WINDOW_INPUT_FOCUS) != 0; }
    public static int SDL_GetWindowID(long handle) { return windowId(handle); }
    public static long SDL_GetWindowFromID(int id) { return windowFromId(id); }

    public static long SDL_CreateWindow(CharSequence title, int width, int height, long flags) {
        if ((flags & SDL_WINDOW_VULKAN) != 0 && !QuestVulkanContext.requested()) {
            throw new IllegalStateException("Quest Vulkan was not requested");
        }
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid Quest window size");
        if (!SDL_Init(0)) throw new IllegalStateException("Could not initialize the Quest GLFW bridge");
        if (host == 0) {
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
            host = GLFW.glfwCreateWindow(width, height, title, 0, 0);
            if (host == 0) throw new IllegalStateException("Could not create the Quest EGL pbuffer");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer actualWidth = stack.ints(0), actualHeight = stack.ints(0);
                GLFW.glfwGetFramebufferSize(host, actualWidth, actualHeight);
                framebufferWidth = actualWidth.get(0) > 0 ? actualWidth.get(0) : width;
                framebufferHeight = actualHeight.get(0) > 0 ? actualHeight.get(0) : height;
            }
            QuestSdlInput.attachWindow(host);
            LOGGER.info("Using Quest GLFW/EGL window bridge, {}x{}", framebufferWidth, framebufferHeight);
        }
        int id = nextWindowId++;
        WINDOWS.put((long) id, new WindowState(id, framebufferWidth, framebufferHeight, flags));
        if (mainWindow == 0 || (flags & SDL_WINDOW_HIDDEN) == 0) mainWindow = id;
        if ((flags & SDL_WINDOW_HIDDEN) == 0) {
            GLFW.glfwSetWindowTitle(host, title);
            GLFW.glfwShowWindow(host);
        }
        return id;
    }

    public static void SDL_DestroyWindow(long handle) {
        WindowState removed = WINDOWS.remove(handle);
        if (removed == null) return;
        if (removed.fullscreenMode != null) removed.fullscreenMode.free();
        if (mainWindow == handle) mainWindow = WINDOWS.keySet().stream().findFirst().orElse(0L);
        if (currentWindow == handle) currentWindow = 0;
    }

    private static void releaseHost() {
        // FCL caches GLFW initialization, so the native host lives until SDL_Quit.
        if (host == 0 || !WINDOWS.isEmpty() || !CONTEXTS.isEmpty()) return;
        QuestSdlInput.detachWindow(host);
        GLFW.glfwMakeContextCurrent(host);
        GLFW.glfwDestroyWindow(host);
        GLFW.glfwTerminate();
        host = 0;
        initialized = false;
        currentContext = currentWindow = 0;
    }

    public static boolean SDL_GL_LoadLibrary(CharSequence path) {
        return SDL_Init(0) && GL.getFunctionProvider() != null;
    }

    public static void SDL_GL_UnloadLibrary() {}

    public static long SDL_GL_GetProcAddress(CharSequence name) {
        return GL.getFunctionProvider().getFunctionAddress(name);
    }

    public static boolean SDL_GL_SetAttribute(int attribute, int value) {
        ATTRIBUTES.put(attribute, value);
        return true;
    }

    public static boolean SDL_GL_GetAttribute(int attribute, IntBuffer value) {
        int result = switch (attribute) {
            case SDL_GL_CONTEXT_MAJOR_VERSION -> GLFW.glfwGetWindowAttrib(host, GLFW.GLFW_CONTEXT_VERSION_MAJOR);
            case SDL_GL_CONTEXT_MINOR_VERSION -> GLFW.glfwGetWindowAttrib(host, GLFW.GLFW_CONTEXT_VERSION_MINOR);
            default -> ATTRIBUTES.getOrDefault(attribute, 0);
        };
        value.put(value.position(), result);
        return true;
    }

    public static long SDL_GL_CreateContext(long handle) {
        window(handle);
        long context = nextContext++;
        CONTEXTS.add(context);
        SDL_GL_MakeCurrent(handle, context);
        return context;
    }

    public static boolean SDL_GL_DestroyContext(long context) {
        if (!CONTEXTS.remove(context)) return false;
        if (currentContext == context) currentContext = currentWindow = 0;
        return true;
    }

    public static boolean SDL_GL_MakeCurrent(long handle, long context) {
        if (context == 0) {
            GLFW.glfwMakeContextCurrent(0);
            currentContext = currentWindow = 0;
            return true;
        }
        window(handle);
        if (!CONTEXTS.contains(context)) throw new IllegalArgumentException("Unknown Quest GL context: " + context);
        GLFW.glfwMakeContextCurrent(host);
        currentContext = context;
        currentWindow = handle;
        return true;
    }

    public static long SDL_GL_GetCurrentContext() { return currentContext; }
    public static long SDL_GL_GetCurrentWindow() { return currentWindow; }
    public static boolean SDL_GL_SetSwapInterval(int interval) { GLFW.glfwSwapInterval(interval); return true; }
    public static boolean SDL_GL_SwapWindow(long handle) { window(handle); GLFW.glfwSwapBuffers(host); return true; }

    public static long SDL_GetWindowFlags(long handle) { return window(handle).flags; }
    public static float SDL_GetWindowPixelDensity(long handle) { window(handle); return 1.0F; }
    public static String SDL_GetCurrentVideoDriver() { return "android"; }

    private static void put(IntBuffer target, int value) {
        if (target != null) target.put(target.position(), value);
    }

    public static boolean SDL_GetWindowPosition(long handle, IntBuffer x, IntBuffer y) {
        WindowState state = window(handle);
        put(x, state.x); put(y, state.y);
        return true;
    }

    public static boolean SDL_GetWindowSize(long handle, IntBuffer width, IntBuffer height) {
        WindowState state = window(handle);
        put(width, state.width); put(height, state.height);
        return true;
    }

    public static boolean SDL_GetWindowSizeInPixels(long handle, IntBuffer width, IntBuffer height) {
        window(handle);
        put(width, framebufferWidth); put(height, framebufferHeight);
        return true;
    }

    public static boolean SDL_SetWindowSize(long handle, int width, int height) {
        window(handle);
        if (width <= 0 || height <= 0) return false;
        GLFW.glfwSetWindowSize(host, width, height);
        onWindowSize(host, width, height);
        onFramebufferSize(host, width, height);
        QuestSdlInput.windowEvent(handle, SDL_EVENT_WINDOW_RESIZED, width, height);
        QuestSdlInput.windowEvent(handle, SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED, width, height);
        return true;
    }

    public static boolean SDL_SetWindowPosition(long handle, int x, int y) {
        window(handle);
        if ((x & 0xFFFF0000) != 0 && x >= 0) x = 0;
        if ((y & 0xFFFF0000) != 0 && y >= 0) y = 0;
        GLFW.glfwSetWindowPos(host, x, y);
        onWindowPos(host, x, y);
        QuestSdlInput.windowEvent(handle, SDL_EVENT_WINDOW_MOVED, x, y);
        return true;
    }

    public static boolean SDL_SetWindowTitle(long handle, CharSequence title) { window(handle); GLFW.glfwSetWindowTitle(host, title); return true; }
    public static boolean SDL_SetWindowMinimumSize(long handle, int width, int height) { window(handle); return true; }
    public static boolean SDL_SetWindowMaximumSize(long handle, int width, int height) { window(handle); return true; }
    public static boolean SDL_SetWindowIcon(long handle, SDL_Surface icon) { window(handle); return true; }
    public static boolean SDL_SyncWindow(long handle) { window(handle); return true; }

    public static boolean SDL_SetWindowBordered(long handle, boolean bordered) {
        WindowState state = window(handle);
        state.flags = bordered ? state.flags & ~SDL_WINDOW_BORDERLESS : state.flags | SDL_WINDOW_BORDERLESS;
        return true;
    }

    public static boolean SDL_SetWindowFullscreen(long handle, boolean fullscreen) {
        WindowState state = window(handle);
        state.flags = fullscreen ? state.flags | SDL_WINDOW_FULLSCREEN : state.flags & ~SDL_WINDOW_FULLSCREEN;
        QuestSdlInput.windowEvent(handle, fullscreen ? SDL_EVENT_WINDOW_ENTER_FULLSCREEN : SDL_EVENT_WINDOW_LEAVE_FULLSCREEN, 0, 0);
        return true;
    }

    public static boolean SDL_SetWindowFullscreenMode(long handle, SDL_DisplayMode mode) {
        WindowState state = window(handle);
        if (state.fullscreenMode != null) state.fullscreenMode.free();
        state.fullscreenMode = mode == null ? null : SDL_DisplayMode.calloc().set(mode);
        return true;
    }

    public static SDL_DisplayMode SDL_GetWindowFullscreenMode(long handle) { return window(handle).fullscreenMode; }

    public static boolean SDL_RestoreWindow(long handle) {
        WindowState state = window(handle);
        state.flags &= ~(SDL_WINDOW_MINIMIZED | SDL_WINDOW_MAXIMIZED);
        QuestSdlInput.windowEvent(handle, SDL_EVENT_WINDOW_RESTORED, 0, 0);
        return true;
    }

    public static boolean SDL_SetWindowMouseGrab(long handle, boolean grabbed) {
        WindowState state = window(handle);
        state.flags = grabbed ? state.flags | SDL_WINDOW_MOUSE_GRABBED : state.flags & ~SDL_WINDOW_MOUSE_GRABBED;
        return true;
    }

    public static void setCursorGrabbed(long handle, boolean grabbed) {
        SDL_SetWindowMouseGrab(handle, grabbed);
        GLFW.glfwSetInputMode(host, GLFW.GLFW_CURSOR, grabbed ? GLFW.GLFW_CURSOR_DISABLED : GLFW.GLFW_CURSOR_NORMAL);
    }

    private static SDL_DisplayMode display() {
        if (!SDL_Init(0)) throw new IllegalStateException("Quest GLFW display initialization failed");
        if (displayMode == null) displayMode = SDL_DisplayMode.calloc();
        GLFWVidMode mode = GLFW.glfwGetVideoMode(GLFW.glfwGetPrimaryMonitor());
        int width = mode != null && mode.width() > 0 ? mode.width() : Math.max(1, framebufferWidth);
        int height = mode != null && mode.height() > 0 ? mode.height() : Math.max(1, framebufferHeight);
        int refresh = mode != null && mode.refreshRate() > 0 ? mode.refreshRate() : 60;
        return displayMode.displayID(1).format(SDL_PIXELFORMAT_RGBA8888).w(width).h(height)
            .pixel_density(1.0F).refresh_rate(refresh).refresh_rate_numerator(refresh).refresh_rate_denominator(1);
    }

    private static void checkDisplay(int id) {
        if (id != 1) throw new IllegalArgumentException("Unknown Quest display: " + id);
    }

    public static int SDL_GetPrimaryDisplay() { display(); return 1; }
    public static int SDL_GetDisplayForWindow(long handle) { window(handle); return 1; }
    public static String SDL_GetDisplayName(int id) { checkDisplay(id); return GLFW.glfwGetMonitorName(GLFW.glfwGetPrimaryMonitor()); }
    public static SDL_DisplayMode SDL_GetCurrentDisplayMode(int id) { checkDisplay(id); return display(); }
    public static SDL_DisplayMode SDL_GetDesktopDisplayMode(int id) { checkDisplay(id); return display(); }

    public static IntBuffer SDL_GetDisplays() {
        display();
        long address = SDLStdinc.nSDL_malloc(Integer.BYTES);
        if (address == 0) throw new OutOfMemoryError("Quest display IDs");
        return MemoryUtil.memIntBuffer(address, 1).put(0, 1);
    }

    public static PointerBuffer SDL_GetFullscreenDisplayModes(int id) {
        checkDisplay(id);
        long address = SDLStdinc.nSDL_malloc(PointerBuffer.POINTER_SIZE);
        if (address == 0) throw new OutOfMemoryError("Quest display modes");
        return MemoryUtil.memPointerBuffer(address, 1).put(0, display().address());
    }

    public static boolean SDL_GetClosestFullscreenDisplayMode(int id, int width, int height, float refresh, boolean highDensity, SDL_DisplayMode result) {
        checkDisplay(id);
        result.set(display());
        return true;
    }

    public static boolean SDL_GetDisplayBounds(int id, SDL_Rect result) {
        checkDisplay(id);
        SDL_DisplayMode mode = display();
        result.set(0, 0, mode.w(), mode.h());
        return true;
    }

    public static void onWindowSize(long nativeWindow, int width, int height) {
        if (nativeWindow != host || width <= 0 || height <= 0) return;
        for (WindowState state : WINDOWS.values()) { state.width = width; state.height = height; }
    }

    public static void onFramebufferSize(long nativeWindow, int width, int height) {
        if (nativeWindow != host || width <= 0 || height <= 0) return;
        framebufferWidth = width; framebufferHeight = height;
    }

    public static void onWindowPos(long nativeWindow, int x, int y) {
        if (nativeWindow != host) return;
        for (WindowState state : WINDOWS.values()) { state.x = x; state.y = y; }
    }

    public static void onWindowFocus(long nativeWindow, boolean focused) {
        if (nativeWindow != host) return;
        for (WindowState state : WINDOWS.values()) state.flags = focused ? state.flags | SDL_WINDOW_INPUT_FOCUS : state.flags & ~SDL_WINDOW_INPUT_FOCUS;
    }

    public static void onWindowIconify(long nativeWindow, boolean iconified) {
        if (nativeWindow != host) return;
        for (WindowState state : WINDOWS.values()) state.flags = iconified ? state.flags | SDL_WINDOW_MINIMIZED : state.flags & ~SDL_WINDOW_MINIMIZED;
    }
}
