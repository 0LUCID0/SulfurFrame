package dev.sulfurframe.showcase.client.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No crosshair in the middle of the shot. */
@Mixin(InGameHud.class)
public class CrosshairMixin {
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void showcase$noCrosshair(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        ci.cancel();
    }
}
