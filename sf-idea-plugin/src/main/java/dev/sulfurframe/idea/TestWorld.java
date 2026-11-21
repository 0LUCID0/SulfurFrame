package dev.sulfurframe.idea;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Writes a ready-to-play test world (level.dat only) for Minecraft 1.20.5+: superflat, creative, cheats on, peaceful,
 * daylight and weather cycles off, no mob spawning, keep inventory. The game generates the chunks on first join; mod
 * data packs are picked up as new packs ("Found new data pack ..., loading it automatically").
 *
 * Why write the file: --quickPlaySingleplayer only opens existing worlds, and creating one through the game's menus
 * would need version- and mapping-specific code. level.dat is stable NBT; older data versions are upgraded by the
 * game's own data fixers, so one layout serves every version from 1.20.5 on.
 */
final class TestWorld {
    /** Data versions of releases (minecraft.wiki); unknown newer versions use the newest known one (upgraded on load). */
    private static final Map<String, Integer> DATA_VERSIONS = Map.ofEntries(
            Map.entry("1.16.5", 2586), Map.entry("1.17.1", 2730), Map.entry("1.18.2", 2975), Map.entry("1.19.2", 3120),
            Map.entry("1.19.4", 3337), Map.entry("1.20.1", 3465), Map.entry("1.20.4", 3700),
            Map.entry("1.20.5", 3837), Map.entry("1.20.6", 3839), Map.entry("1.21", 3953), Map.entry("1.21.1", 3955),
            Map.entry("1.21.2", 4080), Map.entry("1.21.3", 4082), Map.entry("1.21.4", 4189));

    /**
     * The data version to stamp on a file this game reads, or -1 below the table (older than 1.16.5): the exact one, else
     * the nearest older release in the table (a version between two entries), else the newest (a newer game upgrades
     * it on load). A file without one counts as data version 0 to the game, which then runs every data fixer since
     * 2013 on it.
     */
    static int nearestDataVersion(String mc) {
        if (mc == null) return -1;
        Integer exact = DATA_VERSIONS.get(mc);
        if (exact != null) return exact;
        int[] want = parse(mc);
        if (want == null) return -1;
        String best = null;
        int[] bestV = null;
        for (String k : DATA_VERSIONS.keySet()) {
            int[] v = parse(k);
            if (v != null && compare(v, want) <= 0 && (bestV == null || compare(v, bestV) > 0)) { best = k; bestV = v; }
        }
        return best != null ? DATA_VERSIONS.get(best) : -1;
    }

    private static int[] parse(String mc) {
        String[] p = mc.split("\\.");
        int[] v = new int[3];
        try {
            for (int i = 0; i < Math.min(3, p.length); i++) v[i] = Integer.parseInt(p[i].replaceAll("\\D.*", ""));
            return v;
        } catch (NumberFormatException e) { return null; }
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
        return 0;
    }

    static boolean supported(String mc) {
        if (mc == null) return false;
        String[] v = mc.split("\\.");
        try {
            int a = Integer.parseInt(v[0]), b = v.length > 1 ? Integer.parseInt(v[1]) : 0, c = v.length > 2 ? Integer.parseInt(v[2].replaceAll("\\D.*", "")) : 0;
            return a > 1 || b > 20 || (b == 20 && c >= 5);
        } catch (NumberFormatException e) { return false; }
    }

    static int dataVersion(String mc) { return DATA_VERSIONS.getOrDefault(mc, 4189); }

    /** Create saves/<name>/level.dat (fails if the world exists). */
    static Path create(Path saves, String name, String mc) throws IOException {
        Path dir = saves.resolve(name);
        if (Files.exists(dir)) throw new FileAlreadyExistsException(dir.toString());
        Files.createDirectories(dir);
        int dv = dataVersion(mc);
        var data = new LinkedHashMap<String, Object>();
        data.put("DataVersion", dv);
        data.put("version", 19133);   // level format (Anvil); "Version" below is the game version
        data.put("Version", map("Id", dv, "Name", mc, "Series", "main", "Snapshot", (byte) 0));
        data.put("LevelName", name);
        data.put("GameType", 1);
        data.put("allowCommands", (byte) 1);
        data.put("hardcore", (byte) 0);
        data.put("Difficulty", (byte) 0);
        data.put("DifficultyLocked", (byte) 0);
        data.put("initialized", (byte) 1);
        data.put("SpawnX", 0); data.put("SpawnY", -60); data.put("SpawnZ", 0); data.put("SpawnAngle", 0f);
        data.put("DayTime", 6000L); data.put("Time", 0L);
        data.put("raining", (byte) 0); data.put("thundering", (byte) 0); data.put("clearWeatherTime", 999999);
        data.put("WasModded", (byte) 1);
        data.put("GameRules", map("doDaylightCycle", "false", "doWeatherCycle", "false", "doMobSpawning", "false",
                "keepInventory", "true", "doInsomnia", "false", "doPatrolSpawning", "false", "doTraderSpawning", "false"));
        data.put("DataPacks", map("Enabled", List.of("vanilla"), "Disabled", List.of()));
        data.put("enabled_features", List.of("minecraft:vanilla"));
        var layers = List.of(map("block", "minecraft:bedrock", "height", 1), map("block", "minecraft:dirt", "height", 2),
                map("block", "minecraft:grass_block", "height", 1));
        data.put("WorldGenSettings", map(
                "seed", 0L, "generate_features", (byte) 0, "bonus_chest", (byte) 0,
                "dimensions", map(
                        "minecraft:overworld", map("type", "minecraft:overworld", "generator", map("type", "minecraft:flat",
                                "settings", map("biome", "minecraft:plains", "features", (byte) 0, "lakes", (byte) 0,
                                        "layers", layers, "structure_overrides", List.of()))),
                        "minecraft:the_nether", map("type", "minecraft:the_nether", "generator", map("type", "minecraft:noise",
                                "settings", "minecraft:nether", "biome_source", map("type", "minecraft:multi_noise", "preset", "minecraft:nether"))),
                        "minecraft:the_end", map("type", "minecraft:the_end", "generator", map("type", "minecraft:noise",
                                "settings", "minecraft:end", "biome_source", map("type", "minecraft:the_end"))))));
        try (var out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(dir.resolve("level.dat")))))) {
            out.writeByte(10); out.writeUTF("");                  // root compound
            writeNamed(out, "Data", data);
            out.writeByte(0);
        }
        return dir;
    }

    /**
     * Why the single player of this world cannot play on, or null if they can: dead (health 0), or below the bottom of
     * the overworld (fell out of the world: every join then starts in the void and dies again). Reads level.dat only.
     */
    static String playerLost(Path world) {
        try (var in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(world.resolve("level.dat")))))) {
            if (in.readByte() != 10) return null;
            in.readUTF();
            Object root = readPayload(in, 10);
            if (!(root instanceof Map<?, ?> r) || !(r.get("Data") instanceof Map<?, ?> d) || !(d.get("Player") instanceof Map<?, ?> p)) return null;
            if (p.get("Health") instanceof Float h && h <= 0) return "the player is dead";
            if (p.get("Pos") instanceof List<?> pos && pos.size() == 3 && pos.get(1) instanceof Double y && y < -64
                    && "minecraft:overworld".equals(p.get("Dimension")))
                return "the player is at y " + Math.round(y) + ", below the world";
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /** NBT payload of type {@code t}; arrays are skipped (null), everything else read. */
    private static Object readPayload(DataInputStream in, int t) throws IOException {
        switch (t) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: in.skipNBytes(in.readInt()); return null;
            case 8: return in.readUTF();
            case 9: {
                int et = in.readByte(), n = in.readInt();
                var l = new ArrayList<Object>(Math.max(n, 0));
                for (int i = 0; i < n; i++) l.add(readPayload(in, et));
                return l;
            }
            case 10: {
                var m = new LinkedHashMap<String, Object>();
                for (int tt; (tt = in.readByte()) != 0; ) { String k = in.readUTF(); m.put(k, readPayload(in, tt)); }
                return m;
            }
            case 11: in.skipNBytes(4L * in.readInt()); return null;
            case 12: in.skipNBytes(8L * in.readInt()); return null;
            default: throw new IOException("bad NBT tag " + t);
        }
    }

    private static Map<String, Object> map(Object... kv) {
        var m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ------------------------------------------------------------------ NBT (just what level.dat needs)

    private static int tag(Object v) {
        if (v instanceof Byte) return 1;
        if (v instanceof Integer) return 3;
        if (v instanceof Long) return 4;
        if (v instanceof Float) return 5;
        if (v instanceof String) return 8;
        if (v instanceof List) return 9;
        if (v instanceof Map) return 10;
        throw new IllegalArgumentException("no NBT type for " + v);
    }

    private static void writeNamed(DataOutputStream out, String name, Object v) throws IOException {
        out.writeByte(tag(v));
        out.writeUTF(name);
        writePayload(out, v);
    }

    @SuppressWarnings("unchecked")
    private static void writePayload(DataOutputStream out, Object v) throws IOException {
        switch (tag(v)) {
            case 1 -> out.writeByte((Byte) v);
            case 3 -> out.writeInt((Integer) v);
            case 4 -> out.writeLong((Long) v);
            case 5 -> out.writeFloat((Float) v);
            case 8 -> out.writeUTF((String) v);
            case 9 -> {
                var list = (List<Object>) v;
                out.writeByte(list.isEmpty() ? 8 : tag(list.get(0)));   // empty lists: typed as strings (what the game accepts for these)
                out.writeInt(list.size());
                for (Object e : list) writePayload(out, e);
            }
            case 10 -> {
                for (var e : ((Map<String, Object>) v).entrySet()) writeNamed(out, e.getKey(), e.getValue());
                out.writeByte(0);
            }
            default -> throw new IllegalArgumentException();
        }
    }

    private TestWorld() {}
}
