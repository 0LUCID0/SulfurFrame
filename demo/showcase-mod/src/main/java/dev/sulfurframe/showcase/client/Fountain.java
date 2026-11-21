package dev.sulfurframe.showcase.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

/** A spiral of particles in front of the player. */
public final class Fountain {
    public static void tick(MinecraftClient client) {
        if (client.world == null || client.player == null || client.isPaused()) return;
        Vector3f color = Palette.SULFUR;

        float t = client.world.getTime();
        Vec3d look = client.player.getRotationVec(1f);
        Vec3d center = client.player.getEyePos().add(look.x * 4, -0.4, look.z * 4);
        for (int arm = 0; arm < 3; arm++) {
            for (int sub = 0; sub < 2; sub++) {
                double s = t + sub * 0.5;
                double angle = s * 0.18 + arm * MathHelper.TAU / 3;
                double height = ((s * 0.035 + arm / 3.0) % 1.0) * 2.4 - 1.2;
                double x = center.x + Math.cos(angle) * 0.9;
                double z = center.z + Math.sin(angle) * 0.9;
                client.world.addParticle(new DustParticleEffect(color, 1.9f), x, center.y + height, z, 0, 0, 0);
            }
        }
    }

    private Fountain() {}
}
