package org.vivecraft.fabric.mixin.screenhandler.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.vivecraft.client_vr.gameplay.screenhandlers.GuiHandler;

@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.menu.client.ClientNetworking")
public class FabricClientNetworkingVRMixin {
    @Inject(method = "openScreen", at = @At("HEAD"))
    private void vivecraft$markScreenActiveFabric(CallbackInfo ci) {
        GuiHandler.GUI_APPEAR_OVER_BLOCK_ACTIVE = true;
    }
}
