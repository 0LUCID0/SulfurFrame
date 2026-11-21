package dev.sulfurframe.showcase.client;

import dev.sulfurframe.showcase.client.mixin.GameRendererAccess;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

/** Keeps the mod's own post-processing shader (shaders/program/showcase_grade.fsh) on, and reloads it with resources. */
public final class Grade {
    private static final Identifier POST = Identifier.of("showcase", "shaders/post/grade.json");

    static void keepOn(MinecraftClient client) {
        if (client.world != null && client.gameRenderer.getPostProcessor() == null) load(client);
    }

    static void reload() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != null) load(client);
    }

    private static void load(MinecraftClient client) {
        ((GameRendererAccess) client.gameRenderer).showcase$loadPostProcessor(POST);
    }

    private Grade() {}
}
