package dev.sulfurframe.mojtest;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SulfurFrame self-test mod built with Mojang's official mappings (the showcase uses Yarn): the game runs with Mojang
 * names, so every name the agent looks up must have its Mojang form too.
 */
public class MojangMod implements ModInitializer {
    public static final String ID = "mojtest";
    public static final Logger LOGGER = LoggerFactory.getLogger(ID);

    @Override
    public void onInitialize() {
        SfHotswapProbe.start();
    }
}
