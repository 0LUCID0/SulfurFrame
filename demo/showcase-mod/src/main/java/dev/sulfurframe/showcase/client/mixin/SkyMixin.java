package dev.sulfurframe.showcase.client.mixin;

import dev.sulfurframe.showcase.client.Palette;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientWorld.class)
public class SkyMixin {
    @Inject(method = "getSkyColor", at = @At("RETURN"), cancellable = true)
    private void showcase$sky(Vec3d cameraPos, float tickDelta, CallbackInfoReturnable<Vec3d> cir) {
        
    }
}
