package dev.sulfurframe.showcase;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class ShowcaseItems {
    /** In registration order; the newest one ends up in the player's hand. */
    public static final List<Item> ALL = new ArrayList<>();

    public static final Item SULFUR_CUBE = register("sulfur_cube");
    

    private static Item register(String name) {
        Item item = Registry.register(Registries.ITEM, Identifier.of(Showcase.ID, name), new Item(new Item.Settings()));
        ALL.add(item);
        return item;
    }

    /** Loads the class, which registers the items above. */
    static void initialize() {}

    private ShowcaseItems() {}
}
