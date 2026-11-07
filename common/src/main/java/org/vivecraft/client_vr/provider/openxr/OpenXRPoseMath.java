package org.vivecraft.client_vr.provider.openxr;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

final class OpenXRPoseMath {
    private OpenXRPoseMath() {}

    static void eyesToHeadRelative(Matrix4fc head, Matrix4f leftEye, Matrix4f rightEye) {
        Matrix4f inverseHead = new Matrix4f(head).invert();
        inverseHead.mul(leftEye, leftEye);
        inverseHead.mul(rightEye, rightEye);
    }

    static Matrix4f gripRelativeToAim(Matrix4fc aim, Matrix4fc gripRotation) {
        return new Matrix4f().set3x3(aim).invert().mul(gripRotation);
    }
}
