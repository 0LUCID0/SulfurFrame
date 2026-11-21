package dev.sulfurframe.showcase;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;

public final class ShowcaseBlocks {
    /** The floor of the demo world. Untinted, so its texture shows exactly as drawn. */
    public static final Block TILE = new Block(AbstractBlock.Settings.create().strength(1.0f).sounds(BlockSoundGroup.STONE));

    /** Self-test only: no blockstate, model or translation on purpose (the plugin's problem list must report them). */
    public static final Block PROBE = new Block(AbstractBlock.Settings.create());

    static void register() {
        Identifier id = Identifier.of(Showcase.ID, "tile");
        Registry.register(Registries.BLOCK, id, TILE);
        Registry.register(Registries.ITEM, id, new BlockItem(TILE, new Item.Settings()));
        Identifier probe = Identifier.of(Showcase.ID, "probe_block");
        Registry.register(Registries.BLOCK, probe, PROBE);
        Registry.register(Registries.ITEM, probe, new BlockItem(PROBE, new Item.Settings()));
    }

    private ShowcaseBlocks() {}
}
