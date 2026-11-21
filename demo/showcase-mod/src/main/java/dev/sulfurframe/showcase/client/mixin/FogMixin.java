package dev.sulfurframe.showcase.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.render.BackgroundRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The horizon fog leans towards the sky colour (whatever SkyMixin returns), so a changed sky does not end in a blue band. */
@Mixin(BackgroundRenderer.class)
public class FogMixin {
    @Shadow private static float red;
    @Shadow private static float green;
    @Shadow private static float blue;

    @Inject(method = "render", at = @At("TAIL"))
    private static void showcase$fog(Camera camera, float tickDelta, ClientWorld world, int viewDistance, float skyDarkness, CallbackInfo ci) {
        Vec3d sky = world.getSkyColor(camera.getPos(), tickDelta);
        red += ((float) sky.x - red) * 0.7f;
        green += ((float) sky.y - green) * 0.7f;
        blue += ((float) sky.z - blue) * 0.7f;
        RenderSystem.clearColor(red, green, blue, 0f);
    }
}
