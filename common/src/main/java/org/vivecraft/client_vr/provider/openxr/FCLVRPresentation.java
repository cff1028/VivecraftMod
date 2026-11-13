package org.vivecraft.client_vr.provider.openxr;

import org.lwjgl.system.JNI;
import org.lwjgl.system.Library;
import org.lwjgl.system.SharedLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FCLVRPresentation {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vivecraft/FCL");
    private static SharedLibrary library;
    private static long gameFrame;
    private static long openXrStopped;
    private static boolean flatNotified;
    private static boolean unavailable;

    private FCLVRPresentation() {}

    public static void beginVR() {
        flatNotified = false;
    }

    public static void endFrame(boolean flat) {
        if (!Boolean.getBoolean("fcl.quest.vr")) return;
        if (!flat) {
            flatNotified = false;
            return;
        }
        if (flatNotified || unavailable) return;
        try {
            if (library == null) {
                SharedLibrary candidate = Library.loadNative(FCLVRPresentation.class, "org.vivecraft", "vloader");
                long frame = candidate.getFunctionAddress("quest_vr_notify_game_frame");
                long stopped = candidate.getFunctionAddress("quest_vr_notify_openxr_stopped");
                if (frame == 0L || stopped == 0L) {
                    candidate.free();
                    unavailable = true;
                    LOGGER.info("FCL does not expose flat presentation notifications");
                    return;
                }
                library = candidate;
                gameFrame = frame;
                openXrStopped = stopped;
            }
            JNI.invokeV(gameFrame);
            // The first-frame callback is one-shot; also cover a previously active XR session.
            JNI.invokeV(openXrStopped);
            flatNotified = true;
            LOGGER.info("Vivecraft: handed flat rendering to FCL");
        } catch (LinkageError | RuntimeException exception) {
            unavailable = true;
            LOGGER.warn("Vivecraft: FCL presentation notifications unavailable", exception);
        }
    }
}
