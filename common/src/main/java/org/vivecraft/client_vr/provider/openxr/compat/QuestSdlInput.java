package org.vivecraft.client_vr.provider.openxr.compat;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.*;
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.sdl.SDL_Rect;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.sdl.SDLEvents.*;
import static org.lwjgl.sdl.SDLKeycode.*;
import static org.lwjgl.sdl.SDLMouse.*;
import static org.lwjgl.sdl.SDLScancode.*;

public final class QuestSdlInput {
    private static final InputQueue INPUT = new InputQueue();
    private static final Map<Integer, Long> CURSORS = new HashMap<>();
    private static long host;
    private static long textWindow;
    private static boolean polling;
    private static boolean pumping;
    private static GLFWKeyCallback keyCallback;
    private static GLFWCharCallback charCallback;
    private static GLFWCursorPosCallback motionCallback;
    private static GLFWMouseButtonCallback buttonCallback;
    private static GLFWScrollCallback wheelCallback;
    private static GLFWCursorEnterCallback enterCallback;
    private static GLFWWindowSizeCallback sizeCallback;
    private static GLFWFramebufferSizeCallback framebufferCallback;
    private static GLFWWindowPosCallback positionCallback;
    private static GLFWWindowFocusCallback focusCallback;
    private static GLFWWindowIconifyCallback iconifyCallback;
    private static GLFWWindowCloseCallback closeCallback;
    private static GLFWDropCallback dropCallback;

    private QuestSdlInput() {}

    public static void attachWindow(long nativeWindow) {
        if (host == nativeWindow) return;
        if (host != 0L) detachWindow(host);
        host = nativeWindow;
        if (keyCallback == null) {
            keyCallback = GLFWKeyCallback.create((window, key, scan, action, mods) ->
                INPUT.key(currentId(), key, scan, action, mods));
            charCallback = GLFWCharCallback.create((window, codepoint) -> {
                if (textWindow != 0L) INPUT.character(QuestSdlWindow.windowId(textWindow), codepoint);
            });
            motionCallback = GLFWCursorPosCallback.create((window, x, y) -> INPUT.motion(currentId(), x, y));
            buttonCallback = GLFWMouseButtonCallback.create((window, button, action, mods) ->
                INPUT.button(currentId(), button, action, mods));
            wheelCallback = GLFWScrollCallback.create((window, x, y) -> INPUT.wheel(currentId(), x, y));
            enterCallback = GLFWCursorEnterCallback.create((window, entered) -> windowEvent(
                QuestSdlWindow.mainWindow(), entered ? SDL_EVENT_WINDOW_MOUSE_ENTER : SDL_EVENT_WINDOW_MOUSE_LEAVE, 0, 0));
            sizeCallback = GLFWWindowSizeCallback.create((window, width, height) -> {
                QuestSdlWindow.onWindowSize(window, width, height);
                windowEvent(QuestSdlWindow.mainWindow(), SDL_EVENT_WINDOW_RESIZED, width, height);
            });
            framebufferCallback = GLFWFramebufferSizeCallback.create((window, width, height) -> {
                QuestSdlWindow.onFramebufferSize(window, width, height);
                windowEvent(QuestSdlWindow.mainWindow(), SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED, width, height);
            });
            positionCallback = GLFWWindowPosCallback.create((window, x, y) -> {
                QuestSdlWindow.onWindowPos(window, x, y);
                windowEvent(QuestSdlWindow.mainWindow(), SDL_EVENT_WINDOW_MOVED, x, y);
            });
            focusCallback = GLFWWindowFocusCallback.create((window, focused) -> {
                if (!focused) INPUT.releaseAll(currentId());
                QuestSdlWindow.onWindowFocus(window, focused);
                windowEvent(QuestSdlWindow.mainWindow(), focused ? SDL_EVENT_WINDOW_FOCUS_GAINED : SDL_EVENT_WINDOW_FOCUS_LOST, 0, 0);
            });
            iconifyCallback = GLFWWindowIconifyCallback.create((window, iconified) -> {
                QuestSdlWindow.onWindowIconify(window, iconified);
                windowEvent(QuestSdlWindow.mainWindow(), iconified ? SDL_EVENT_WINDOW_MINIMIZED : SDL_EVENT_WINDOW_RESTORED, 0, 0);
            });
            closeCallback = GLFWWindowCloseCallback.create(window ->
                windowEvent(QuestSdlWindow.mainWindow(), SDL_EVENT_WINDOW_CLOSE_REQUESTED, 0, 0));
            dropCallback = GLFWDropCallback.create((window, count, names) -> {
                int id = currentId();
                INPUT.add(new PendingEvent(SDL_EVENT_DROP_BEGIN, id));
                for (int i = 0; i < count; i++) {
                    PendingEvent event = new PendingEvent(SDL_EVENT_DROP_FILE, id);
                    event.text = GLFWDropCallback.getName(names, i);
                    INPUT.add(event);
                }
                INPUT.add(new PendingEvent(SDL_EVENT_DROP_COMPLETE, id));
            });
        }
        glfwSetKeyCallback(host, keyCallback);
        glfwSetCharCallback(host, charCallback);
        glfwSetCursorPosCallback(host, motionCallback);
        glfwSetMouseButtonCallback(host, buttonCallback);
        glfwSetScrollCallback(host, wheelCallback);
        glfwSetCursorEnterCallback(host, enterCallback);
        glfwSetWindowSizeCallback(host, sizeCallback);
        glfwSetFramebufferSizeCallback(host, framebufferCallback);
        glfwSetWindowPosCallback(host, positionCallback);
        glfwSetWindowFocusCallback(host, focusCallback);
        glfwSetWindowIconifyCallback(host, iconifyCallback);
        glfwSetWindowCloseCallback(host, closeCallback);
        glfwSetDropCallback(host, dropCallback);
    }

    public static void detachWindow(long nativeWindow) {
        if (nativeWindow != host) return;
        glfwSetKeyCallback(host, null);
        glfwSetCharCallback(host, null);
        glfwSetCursorPosCallback(host, null);
        glfwSetMouseButtonCallback(host, null);
        glfwSetScrollCallback(host, null);
        glfwSetCursorEnterCallback(host, null);
        glfwSetWindowSizeCallback(host, null);
        glfwSetFramebufferSizeCallback(host, null);
        glfwSetWindowPosCallback(host, null);
        glfwSetWindowFocusCallback(host, null);
        glfwSetWindowIconifyCallback(host, null);
        glfwSetWindowCloseCallback(host, null);
        glfwSetDropCallback(host, null);
        for (long cursor : CURSORS.values()) glfwDestroyCursor(cursor);
        CURSORS.clear();
        host = 0L;
        textWindow = 0L;
        polling = false;
        INPUT.reset();
        // FCL retains native callback addresses; keep their allocations until JVM exit.
    }

    private static int currentId() {
        return QuestSdlWindow.windowId(QuestSdlWindow.mainWindow());
    }

    public static void windowEvent(long window, int type, int data1, int data2) {
        PendingEvent event = new PendingEvent(type, QuestSdlWindow.windowId(window));
        event.data1 = data1;
        event.data2 = data2;
        INPUT.add(event);
    }

    public static void SDL_PumpEvents() {
        if (pumping || host == 0L) return;
        pumping = true;
        try {
            glfwPollEvents();
            polling = true;
        } finally {
            pumping = false;
        }
    }

    public static boolean SDL_PollEvent(SDL_Event event) {
        if (!polling) SDL_PumpEvents();
        boolean result = INPUT.poll(event);
        if (!result) polling = false;
        return result;
    }

    public static void SDL_FlushEvents(int first, int last) {
        INPUT.flush(first, last);
    }

    public static long SDL_GetWindowFromEvent(SDL_Event event) {
        int type = event.type();
        if (type >= SDL_EVENT_WINDOW_FIRST && type <= SDL_EVENT_WINDOW_LAST ||
            type >= SDL_EVENT_KEY_DOWN && type <= SDL_EVENT_TEXT_EDITING_CANDIDATES ||
            type >= SDL_EVENT_MOUSE_MOTION && type <= SDL_EVENT_MOUSE_WHEEL ||
            type >= SDL_EVENT_DROP_FILE && type <= SDL_EVENT_DROP_COMPLETE)
        {
            return QuestSdlWindow.windowFromId(event.window().windowID());
        }
        return 0L;
    }

    public static ByteBuffer SDL_GetKeyboardState() {
        return INPUT.keyboard.asReadOnlyBuffer();
    }

    public static short SDL_GetModState() {
        return (short) INPUT.reportedModifiers;
    }

    public static int SDL_GetKeyFromScancode(int scancode, short modifiers, boolean keyEvent) {
        return SDLKeyboard.SDL_GetKeyFromScancode(scancode, modifiers, keyEvent);
    }

    public static String SDL_GetKeyName(int key) {
        return SDLKeyboard.SDL_GetKeyName(key);
    }

    public static String SDL_GetScancodeName(int scancode) {
        return SDLKeyboard.SDL_GetScancodeName(scancode);
    }

    public static boolean SDL_StartTextInput(long window) {
        if (!QuestSdlWindow.isWindow(window)) return false;
        textWindow = window;
        return true;
    }

    public static boolean SDL_StopTextInput(long window) {
        if (!QuestSdlWindow.isWindow(window)) return false;
        if (textWindow == window) textWindow = 0L;
        return true;
    }

    public static boolean SDL_ClearComposition(long window) {
        if (!QuestSdlWindow.isWindow(window)) return false;
        PendingEvent event = new PendingEvent(SDL_EVENT_TEXT_EDITING, QuestSdlWindow.windowId(window));
        event.text = "";
        INPUT.add(event);
        return true;
    }

    public static boolean SDL_SetTextInputArea(long window, SDL_Rect.Buffer rect, int cursor) {
        return QuestSdlWindow.isWindow(window);
    }

    public static boolean SDL_SetWindowRelativeMouseMode(long window, boolean enabled) {
        if (!QuestSdlWindow.isWindow(window)) return false;
        QuestSdlWindow.setCursorGrabbed(window, enabled);
        return true;
    }

    public static void SDL_WarpMouseInWindow(long window, float x, float y) {
        long target = QuestSdlWindow.nativeWindow(window);
        if (target == 0L) return;
        INPUT.setPosition(x, y);
        glfwSetCursorPos(target, x, y);
    }

    public static int SDL_GetMouseState(FloatBuffer x, FloatBuffer y) {
        if (x != null) x.put(x.position(), INPUT.x);
        if (y != null) y.put(y.position(), INPUT.y);
        return INPUT.buttons;
    }

    public static int SDL_GetGlobalMouseState(FloatBuffer x, FloatBuffer y) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer px = stack.callocInt(1);
            IntBuffer py = stack.callocInt(1);
            QuestSdlWindow.SDL_GetWindowPosition(QuestSdlWindow.mainWindow(), px, py);
            if (x != null) x.put(x.position(), INPUT.x + px.get(0));
            if (y != null) y.put(y.position(), INPUT.y + py.get(0));
        }
        return INPUT.buttons;
    }

    public static long SDL_CreateSystemCursor(int id) {
        return CURSORS.computeIfAbsent(id, key -> glfwCreateStandardCursor(switch (key) {
            case SDL_SYSTEM_CURSOR_TEXT -> GLFW_IBEAM_CURSOR;
            case SDL_SYSTEM_CURSOR_CROSSHAIR -> GLFW_CROSSHAIR_CURSOR;
            case SDL_SYSTEM_CURSOR_POINTER -> GLFW_HAND_CURSOR;
            case SDL_SYSTEM_CURSOR_EW_RESIZE, SDL_SYSTEM_CURSOR_E_RESIZE, SDL_SYSTEM_CURSOR_W_RESIZE -> GLFW_HRESIZE_CURSOR;
            case SDL_SYSTEM_CURSOR_NS_RESIZE, SDL_SYSTEM_CURSOR_N_RESIZE, SDL_SYSTEM_CURSOR_S_RESIZE -> GLFW_VRESIZE_CURSOR;
            default -> GLFW_ARROW_CURSOR;
        }));
    }

    public static long SDL_GetDefaultCursor() {
        return SDL_CreateSystemCursor(SDL_SYSTEM_CURSOR_DEFAULT);
    }

    public static boolean SDL_SetCursor(long cursor) {
        if (host == 0L) return false;
        glfwSetCursor(host, cursor);
        return true;
    }

    public static String SDL_GetClipboardText() {
        String text = glfwGetClipboardString(host);
        return text == null ? "" : text;
    }

    public static boolean SDL_SetClipboardText(CharSequence text) {
        if (text == null) return false;
        ByteBuffer bytes = MemoryUtil.memUTF8(text);
        try {
            glfwSetClipboardString(host, bytes);
        } finally {
            MemoryUtil.memFree(bytes);
        }
        return true;
    }

    static int scancode(int key) {
        if (key >= GLFW_KEY_A && key <= GLFW_KEY_Z) return SDL_SCANCODE_A + key - GLFW_KEY_A;
        if (key >= GLFW_KEY_1 && key <= GLFW_KEY_9) return SDL_SCANCODE_1 + key - GLFW_KEY_1;
        if (key >= GLFW_KEY_F1 && key <= GLFW_KEY_F12) return SDL_SCANCODE_F1 + key - GLFW_KEY_F1;
        if (key >= GLFW_KEY_F13 && key <= GLFW_KEY_F24) return SDL_SCANCODE_F13 + key - GLFW_KEY_F13;
        if (key >= GLFW_KEY_KP_1 && key <= GLFW_KEY_KP_9) return SDL_SCANCODE_KP_1 + key - GLFW_KEY_KP_1;
        return switch (key) {
            case GLFW_KEY_0 -> SDL_SCANCODE_0;
            case GLFW_KEY_ENTER -> SDL_SCANCODE_RETURN;
            case GLFW_KEY_ESCAPE -> SDL_SCANCODE_ESCAPE;
            case GLFW_KEY_BACKSPACE -> SDL_SCANCODE_BACKSPACE;
            case GLFW_KEY_TAB -> SDL_SCANCODE_TAB;
            case GLFW_KEY_SPACE -> SDL_SCANCODE_SPACE;
            case GLFW_KEY_MINUS -> SDL_SCANCODE_MINUS;
            case GLFW_KEY_EQUAL -> SDL_SCANCODE_EQUALS;
            case GLFW_KEY_LEFT_BRACKET -> SDL_SCANCODE_LEFTBRACKET;
            case GLFW_KEY_RIGHT_BRACKET -> SDL_SCANCODE_RIGHTBRACKET;
            case GLFW_KEY_BACKSLASH -> SDL_SCANCODE_BACKSLASH;
            case GLFW_KEY_SEMICOLON -> SDL_SCANCODE_SEMICOLON;
            case GLFW_KEY_APOSTROPHE -> SDL_SCANCODE_APOSTROPHE;
            case GLFW_KEY_GRAVE_ACCENT -> SDL_SCANCODE_GRAVE;
            case GLFW_KEY_COMMA -> SDL_SCANCODE_COMMA;
            case GLFW_KEY_PERIOD -> SDL_SCANCODE_PERIOD;
            case GLFW_KEY_SLASH -> SDL_SCANCODE_SLASH;
            case GLFW_KEY_CAPS_LOCK -> SDL_SCANCODE_CAPSLOCK;
            case GLFW_KEY_PRINT_SCREEN -> SDL_SCANCODE_PRINTSCREEN;
            case GLFW_KEY_SCROLL_LOCK -> SDL_SCANCODE_SCROLLLOCK;
            case GLFW_KEY_PAUSE -> SDL_SCANCODE_PAUSE;
            case GLFW_KEY_INSERT -> SDL_SCANCODE_INSERT;
            case GLFW_KEY_HOME -> SDL_SCANCODE_HOME;
            case GLFW_KEY_PAGE_UP -> SDL_SCANCODE_PAGEUP;
            case GLFW_KEY_DELETE -> SDL_SCANCODE_DELETE;
            case GLFW_KEY_END -> SDL_SCANCODE_END;
            case GLFW_KEY_PAGE_DOWN -> SDL_SCANCODE_PAGEDOWN;
            case GLFW_KEY_RIGHT -> SDL_SCANCODE_RIGHT;
            case GLFW_KEY_LEFT -> SDL_SCANCODE_LEFT;
            case GLFW_KEY_DOWN -> SDL_SCANCODE_DOWN;
            case GLFW_KEY_UP -> SDL_SCANCODE_UP;
            case GLFW_KEY_NUM_LOCK -> SDL_SCANCODE_NUMLOCKCLEAR;
            case GLFW_KEY_KP_DIVIDE -> SDL_SCANCODE_KP_DIVIDE;
            case GLFW_KEY_KP_MULTIPLY -> SDL_SCANCODE_KP_MULTIPLY;
            case GLFW_KEY_KP_SUBTRACT -> SDL_SCANCODE_KP_MINUS;
            case GLFW_KEY_KP_ADD -> SDL_SCANCODE_KP_PLUS;
            case GLFW_KEY_KP_ENTER -> SDL_SCANCODE_KP_ENTER;
            case GLFW_KEY_KP_0 -> SDL_SCANCODE_KP_0;
            case GLFW_KEY_KP_DECIMAL -> SDL_SCANCODE_KP_PERIOD;
            case GLFW_KEY_KP_EQUAL -> SDL_SCANCODE_KP_EQUALS;
            case GLFW_KEY_MENU -> SDL_SCANCODE_APPLICATION;
            case GLFW_KEY_LEFT_CONTROL -> SDL_SCANCODE_LCTRL;
            case GLFW_KEY_LEFT_SHIFT -> SDL_SCANCODE_LSHIFT;
            case GLFW_KEY_LEFT_ALT -> SDL_SCANCODE_LALT;
            case GLFW_KEY_LEFT_SUPER -> SDL_SCANCODE_LGUI;
            case GLFW_KEY_RIGHT_CONTROL -> SDL_SCANCODE_RCTRL;
            case GLFW_KEY_RIGHT_SHIFT -> SDL_SCANCODE_RSHIFT;
            case GLFW_KEY_RIGHT_ALT -> SDL_SCANCODE_RALT;
            case GLFW_KEY_RIGHT_SUPER -> SDL_SCANCODE_RGUI;
            case GLFW_KEY_WORLD_1 -> SDL_SCANCODE_NONUSBACKSLASH;
            default -> SDL_SCANCODE_UNKNOWN;
        };
    }

    private static int mouseButton(int button) {
        return switch (button) {
            case GLFW_MOUSE_BUTTON_LEFT -> SDL_BUTTON_LEFT;
            case GLFW_MOUSE_BUTTON_RIGHT -> SDL_BUTTON_RIGHT;
            case GLFW_MOUSE_BUTTON_MIDDLE -> SDL_BUTTON_MIDDLE;
            default -> button + 1;
        };
    }

    static final class InputQueue {
        final ByteBuffer keyboard = BufferUtils.createByteBuffer(SDL_SCANCODE_COUNT);
        private final ArrayDeque<PendingEvent> events = new ArrayDeque<>();
        private ByteBuffer textBuffer;
        private int modifiers;
        private char highSurrogate;
        int reportedModifiers;
        int buttons;
        float x;
        float y;

        synchronized void add(PendingEvent event) {
            if (event.windowId != 0) {
                event.modifiers = modifiers;
                events.addLast(event);
            }
        }

        synchronized void key(int windowId, int key, int raw, int action, int glfwMods) {
            int scan = scancode(key);
            if (scan == SDL_SCANCODE_UNKNOWN) return;
            boolean down = action != GLFW_RELEASE;
            keyboard.put(scan, (byte) (down ? 1 : 0));
            updateModifiers(glfwMods);
            PendingEvent event = new PendingEvent(down ? SDL_EVENT_KEY_DOWN : SDL_EVENT_KEY_UP, windowId);
            event.scancode = scan;
            event.code = SDL_GetKeyFromScancode(scan, (short) modifiers, true);
            event.data1 = raw;
            event.down = down;
            event.repeat = action == GLFW_REPEAT;
            add(event);
        }

        private void updateModifiers(int glfwMods) {
            modifiers = 0;
            int[] scans = {SDL_SCANCODE_LSHIFT, SDL_SCANCODE_RSHIFT, SDL_SCANCODE_LCTRL, SDL_SCANCODE_RCTRL,
                SDL_SCANCODE_LALT, SDL_SCANCODE_RALT, SDL_SCANCODE_LGUI, SDL_SCANCODE_RGUI};
            int[] masks = {SDL_KMOD_LSHIFT, SDL_KMOD_RSHIFT, SDL_KMOD_LCTRL, SDL_KMOD_RCTRL,
                SDL_KMOD_LALT, SDL_KMOD_RALT, SDL_KMOD_LGUI, SDL_KMOD_RGUI};
            for (int i = 0; i < scans.length; i++) if (keyboard.get(scans[i]) != 0) modifiers |= masks[i];
            if ((glfwMods & GLFW_MOD_SHIFT) != 0 && (modifiers & SDL_KMOD_SHIFT) == 0) modifiers |= SDL_KMOD_LSHIFT;
            if ((glfwMods & GLFW_MOD_CONTROL) != 0 && (modifiers & SDL_KMOD_CTRL) == 0) modifiers |= SDL_KMOD_LCTRL;
            if ((glfwMods & GLFW_MOD_ALT) != 0 && (modifiers & SDL_KMOD_ALT) == 0) modifiers |= SDL_KMOD_LALT;
            if ((glfwMods & GLFW_MOD_SUPER) != 0 && (modifiers & SDL_KMOD_GUI) == 0) modifiers |= SDL_KMOD_LGUI;
            if ((glfwMods & GLFW_MOD_CAPS_LOCK) != 0) modifiers |= SDL_KMOD_CAPS;
            if ((glfwMods & GLFW_MOD_NUM_LOCK) != 0) modifiers |= SDL_KMOD_NUM;
            reportedModifiers = modifiers;
        }

        synchronized void text(int windowId, String text) {
            PendingEvent event = new PendingEvent(SDL_EVENT_TEXT_INPUT, windowId);
            event.text = text;
            add(event);
        }

        synchronized void character(int windowId, int codepoint) {
            if (!Character.isValidCodePoint(codepoint)) return;
            if (codepoint <= Character.MAX_VALUE && Character.isHighSurrogate((char) codepoint)) {
                highSurrogate = (char) codepoint;
                return;
            }
            if (codepoint <= Character.MAX_VALUE && Character.isLowSurrogate((char) codepoint)) {
                if (highSurrogate == 0) return;
                codepoint = Character.toCodePoint(highSurrogate, (char) codepoint);
            }
            highSurrogate = 0;
            text(windowId, new String(Character.toChars(codepoint)));
        }

        synchronized void setPosition(float x, float y) {
            this.x = x;
            this.y = y;
        }

        synchronized void motion(int windowId, double x, double y) {
            PendingEvent event = new PendingEvent(SDL_EVENT_MOUSE_MOTION, windowId);
            event.x = (float) x;
            event.y = (float) y;
            event.xrel = event.x - this.x;
            event.yrel = event.y - this.y;
            event.data1 = buttons;
            setPosition(event.x, event.y);
            add(event);
        }

        synchronized void button(int windowId, int glfwButton, int action, int glfwMods) {
            int button = mouseButton(glfwButton);
            if (button < 1 || button > 31) return;
            boolean down = action != GLFW_RELEASE;
            if (down) buttons |= 1 << (button - 1);
            else buttons &= ~(1 << (button - 1));
            updateModifiers(glfwMods);
            PendingEvent event = new PendingEvent(down ? SDL_EVENT_MOUSE_BUTTON_DOWN : SDL_EVENT_MOUSE_BUTTON_UP, windowId);
            event.code = button;
            event.down = down;
            event.x = x;
            event.y = y;
            add(event);
        }

        synchronized void wheel(int windowId, double x, double y) {
            PendingEvent event = new PendingEvent(SDL_EVENT_MOUSE_WHEEL, windowId);
            event.x = (float) x;
            event.y = (float) y;
            event.xrel = this.x;
            event.yrel = this.y;
            add(event);
        }

        synchronized void releaseAll(int windowId) {
            highSurrogate = 0;
            for (int scan = 1; scan < keyboard.capacity(); scan++) {
                if (keyboard.get(scan) == 0) continue;
                keyboard.put(scan, (byte) 0);
                PendingEvent event = new PendingEvent(SDL_EVENT_KEY_UP, windowId);
                event.scancode = scan;
                event.code = SDL_GetKeyFromScancode(scan, (short) 0, true);
                modifiers = 0;
                add(event);
            }
            for (int button = 1; button <= 31; button++) {
                if ((buttons & 1 << (button - 1)) == 0) continue;
                PendingEvent event = new PendingEvent(SDL_EVENT_MOUSE_BUTTON_UP, windowId);
                event.code = button;
                event.x = x;
                event.y = y;
                add(event);
            }
            buttons = 0;
            modifiers = 0;
            reportedModifiers = 0;
        }

        synchronized void flush(int first, int last) {
            events.removeIf(event -> event.type >= first && event.type <= last);
        }

        synchronized boolean poll(SDL_Event target) {
            if (target == null) return !events.isEmpty();
            MemoryUtil.memFree(textBuffer);
            textBuffer = null;
            PendingEvent event = events.pollFirst();
            if (event == null) return false;
            reportedModifiers = event.modifiers;
            MemoryUtil.memSet(target.address(), 0, SDL_Event.SIZEOF);
            target.common().type(event.type).timestamp(event.timestamp);
            switch (event.type) {
                case SDL_EVENT_KEY_DOWN, SDL_EVENT_KEY_UP -> target.key().windowID(event.windowId)
                    .scancode(event.scancode).key(event.code).mod((short) event.modifiers)
                    .raw((short) event.data1).down(event.down).repeat(event.repeat);
                case SDL_EVENT_MOUSE_MOTION -> target.motion().windowID(event.windowId).state(event.data1)
                    .x(event.x).y(event.y).xrel(event.xrel).yrel(event.yrel);
                case SDL_EVENT_MOUSE_BUTTON_DOWN, SDL_EVENT_MOUSE_BUTTON_UP -> target.button().windowID(event.windowId)
                    .button((byte) event.code).down(event.down).clicks((byte) 1).x(event.x).y(event.y);
                case SDL_EVENT_MOUSE_WHEEL -> target.wheel().windowID(event.windowId).x(event.x).y(event.y)
                    .direction(SDL_MOUSEWHEEL_NORMAL).mouse_x(event.xrel).mouse_y(event.yrel);
                case SDL_EVENT_TEXT_INPUT -> {
                    textBuffer = MemoryUtil.memUTF8(event.text);
                    target.text().windowID(event.windowId).text(textBuffer);
                }
                case SDL_EVENT_TEXT_EDITING -> {
                    textBuffer = MemoryUtil.memUTF8(event.text);
                    target.edit().windowID(event.windowId).text(textBuffer).start(0).length(0);
                }
                case SDL_EVENT_DROP_FILE, SDL_EVENT_DROP_BEGIN, SDL_EVENT_DROP_COMPLETE -> {
                    target.drop().windowID(event.windowId);
                    if (event.text != null) {
                        textBuffer = MemoryUtil.memUTF8(event.text);
                        target.drop().data(textBuffer);
                    }
                }
                default -> target.window().windowID(event.windowId).data1(event.data1).data2(event.data2);
            }
            return true;
        }

        synchronized void reset() {
            events.clear();
            MemoryUtil.memFree(textBuffer);
            textBuffer = null;
            MemoryUtil.memSet(MemoryUtil.memAddress(keyboard), 0, keyboard.capacity());
            modifiers = reportedModifiers = buttons = 0;
            highSurrogate = 0;
            x = y = 0F;
        }
    }

    private static final class PendingEvent {
        final int type;
        final int windowId;
        final long timestamp = System.nanoTime();
        int modifiers;
        int scancode;
        int code;
        int data1;
        int data2;
        float x;
        float y;
        float xrel;
        float yrel;
        boolean down;
        boolean repeat;
        String text;

        PendingEvent(int type, int windowId) {
            this.type = type;
            this.windowId = windowId;
        }
    }
}
