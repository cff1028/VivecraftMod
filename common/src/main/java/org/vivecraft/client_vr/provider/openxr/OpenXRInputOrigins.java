package org.vivecraft.client_vr.provider.openxr;

import org.vivecraft.client_vr.provider.ControllerType;

import java.util.function.LongFunction;

final class OpenXRInputOrigins {
    private OpenXRInputOrigins() {}

    /** Resolves USER_PATH labels; missing or ambiguous labels have no hand. */
    static ControllerType resolve(String sourceUser, String rightUser, String leftUser, boolean reverseHands) {
        if (sourceUser == null || sourceUser.isBlank() ||
            rightUser == null || rightUser.isBlank() ||
            leftUser == null || leftUser.isBlank() || rightUser.equals(leftUser))
        {
            return null;
        }

        boolean right = sourceUser.equals(rightUser);
        if (!right && !sourceUser.equals(leftUser)) return null;

        if (reverseHands) right = !right;
        return right ? ControllerType.RIGHT : ControllerType.LEFT;
    }

    /** Uses the logical hand from the subaction query when available. */
    static ControllerType handForAction(
        ControllerType queriedHand, long origin, LongFunction<ControllerType> resolveOrigin)
    {
        return queriedHand != null ? queriedHand : resolveOrigin.apply(origin);
    }
}
