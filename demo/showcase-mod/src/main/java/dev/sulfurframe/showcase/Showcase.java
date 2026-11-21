package dev.sulfurframe.showcase;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demo and self-test mod for SulfurFrame. Everything on screen moves on its own, so no input is needed while
 * recording; SfHotswapProbe is what the plugin's self-test edits.
 */
public class Showcase implements ModInitializer {
    public static final String ID = "showcase";
    public static final Logger LOGGER = LoggerFactory.getLogger(ID);

    @Override
    public void onInitialize() {
        ShowcaseBlocks.register();
        ShowcaseItems.initialize();
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> stage(handler.player)));
        SfHotswapProbe.start();
    }

    /** Same view every time: noon, a tiled floor, the camera at a fixed spot, the newest item in hand. */
    private static void stage(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        world.setTimeOfDay(6000);
        world.setWeather(1_000_000, 0, false, false);

        BlockPos feet = player.getBlockPos();
        int floor = feet.getY() - 1;
        if (!world.getBlockState(new BlockPos(0, floor, 0)).isOf(ShowcaseBlocks.TILE)) {
            for (int x = -96; x <= 96; x++)
                for (int z = -96; z <= 96; z++)
                    world.setBlockState(new BlockPos(x, floor, z), ShowcaseBlocks.TILE.getDefaultState());
        }
        player.teleport(world, 0.5, floor + 1, 0.5, 0f, 8f);

        var inv = player.getInventory();
        for (int i = 0; i < 9; i++) inv.setStack(i, ItemStack.EMPTY);
        var items = ShowcaseItems.ALL;
        for (int i = 0; i < items.size() && i < 9; i++) inv.setStack(i, new ItemStack(items.get(items.size() - 1 - i)));
        inv.selectedSlot = 0;
        player.networkHandler.sendPacket(new UpdateSelectedSlotS2CPacket(0));
    }
}
