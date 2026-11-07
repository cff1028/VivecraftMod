package org.vivecraft.client_vr.gameplay.interact_modules;

import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

final class InteractiveHotbarGeometry {
    private InteractiveHotbarGeometry() {}

    static Vector3f start(int guiWidth, int guiHeight, float hudScale, Vector3fc localOffset,
        Matrix4fc rotation, Vector3fc position)
    {
        // The HUD quad is 1.5 units wide; slot centers are 11 pixels above its bottom.
        float unitsPerPixel = 1.5F * hudScale / guiWidth;
        Vector3f offset = new Vector3f(
            (guiWidth / 2 - 90 - guiWidth * 0.5F) * unitsPerPixel,
            (11 - guiHeight * 0.5F) * unitsPerPixel, 0).add(localOffset);
        return rotation.transformDirection(offset).add(position);
    }

    static Vector3f end(Vector3fc start, int guiWidth, float hudScale, Matrix4fc rotation) {
        Vector3f extent = new Vector3f(180 * 1.5F * hudScale / guiWidth, 0, 0);
        return rotation.transformDirection(extent).add(start);
    }
}
