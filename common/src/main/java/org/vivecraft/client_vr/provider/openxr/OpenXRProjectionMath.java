package org.vivecraft.client_vr.provider.openxr;

import org.joml.Matrix4f;

final class OpenXRProjectionMath {
    private OpenXRProjectionMath() {}

    static Matrix4f projection(float left, float right, float down, float up, float near, float far,
        boolean zeroToOne)
    {
        return new Matrix4f().setPerspectiveOffCenterFov(left, right, down, up, near, far, zeroToOne);
    }
}
