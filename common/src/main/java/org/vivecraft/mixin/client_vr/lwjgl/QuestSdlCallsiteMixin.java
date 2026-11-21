package org.vivecraft.mixin.client_vr.lwjgl;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = {
    "com.mojang.blaze3d.platform.ClipboardManager",
    "com.mojang.blaze3d.platform.InputConstants",
    "com.mojang.blaze3d.platform.InputConstants$Type",
    "com.mojang.blaze3d.platform.Monitor",
    "com.mojang.blaze3d.platform.MonitorManager",
    "com.mojang.blaze3d.platform.SDLEventHandler",
    "com.mojang.blaze3d.platform.TextInputManager",
    "com.mojang.blaze3d.platform.Window",
    "com.mojang.blaze3d.platform.cursor.CursorType",
    "com.mojang.blaze3d.systems.RenderSystem",
    "com.mojang.renderpearl.backend.vulkan.VulkanBackend",
    "com.mojang.renderpearl.backend.opengl.GlBackend",
    "com.mojang.renderpearl.backend.opengl.GlDevice",
    "com.mojang.renderpearl.backend.opengl.GlSurface",
    "net.minecraft.client.Minecraft",
    "net.minecraft.client.MouseHandler",
    "net.minecraft.client.input.InputQuirks"
}, priority = 100)
public abstract class QuestSdlCallsiteMixin {}
