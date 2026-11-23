package dev.sulfurframe.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Changed settings of a block or item that is already registered ("strength(1)" became "strength(5)"). Running the
 * registration code again makes a new object with the new settings, and the registry keeps the existing one (it is
 * what the world, the inventory and every reference hold). So the new object's settings go onto the existing one:
 *
 *   - the object's own fields (settings, sound, components...), except registry holders, the state manager, states,
 *     codecs and collections; a reference to another new object (BlockItem.block) becomes the existing one
 *   - for a block, each block state's cached values (hardness, luminance, map color...), state matched to state by
 *     property values, then its shape cache built again
 *
 * A block or item whose class changed (Block became StairsBlock) cannot be patched: that one asks for a restart.
 * Java 8, reflection only; Yarn and Mojang names.
 */
final class SettingsTransfer {
    /** @param pairs {new object, existing object} from the run; returns true if a block's values changed (redraw). */
    static boolean apply(List<Object[]> pairs, Map<Object, String> ids, List<String> out) {
        Map<Object, Object> toExisting = new IdentityHashMap<Object, Object>();
        for (Object[] p : pairs) toExisting.put(p[0], p[1]);
        boolean blockChanged = false;
        for (Object[] p : pairs) {
            Object fresh = p[0], existing = p[1];
            String id = ids.containsKey(existing) ? ids.get(existing) : String.valueOf(existing);
            if (fresh.getClass() != existing.getClass()) {
                out.add("restart needed: " + id + " (its class changed: " + existing.getClass().getSimpleName() + " -> " + fresh.getClass().getSimpleName() + ")");
                continue;
            }
            try {
                List<String> changed = copyFields(fresh, existing, toExisting);
                if (isBlock(existing)) {
                    List<String> states = copyStates(fresh, existing, toExisting);
                    if (!states.isEmpty()) { changed.addAll(states); blockChanged = true; }
                    if (!changed.isEmpty()) blockChanged = true;
                }
                if (!changed.isEmpty()) out.add("settings changed: " + id + " (" + join(changed, 6) + ")");
            } catch (Throwable t) {
                out.add("warning settings of " + id + ": " + t);
            }
        }
        return blockChanged;
    }

    /** Copy the fields that differ; returns "name a -> b" for each. */
    private static List<String> copyFields(Object from, Object to, Map<Object, Object> toExisting) throws IllegalAccessException {
        List<String> changed = new ArrayList<String>();
        for (Class<?> k = from.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                // mixin-added fields ("fabric_cachedItemVariant", "mod$x") are caches that can point at the new object
                if (Modifier.isStatic(f.getModifiers()) || f.getName().indexOf('_') >= 0 || f.getName().indexOf('$') >= 0) continue;
                try { f.setAccessible(true); } catch (RuntimeException e) { continue; }
                Object v = f.get(from), was = f.get(to);
                if (v == null || v == from || skip(v)) continue;   // null: a lazy cache the new object has not filled
                if (v != null && toExisting.containsKey(v)) v = toExisting.get(v);
                if (v == was) continue;
                if (Modifier.isFinal(f.getModifiers())) putFinal(f, to, v); else f.set(to, v);
                // reported (and a reason to redraw) only when the value really differs: settings objects and lambdas
                // are new instances every run
                if (!same(v, was) && comparable(v, was) && !Objects.equals(v, was)) changed.add(f.getName() + " " + brief(was) + " -> " + brief(v));
            }
        }
        return changed;
    }

    /** The block's states: each existing state takes the cached values of the new state with the same properties. */
    private static List<String> copyStates(Object freshBlock, Object block, Map<Object, Object> toExisting) throws Exception {
        List<?> fresh = states(freshBlock), existing = states(block);
        for (Object f : fresh) initCache(f);   // values filled with the shape cache (solid...): the new states never had it
        Map<String, Object> byProps = new HashMap<String, Object>();
        for (Object s : fresh) byProps.put(props(s), s);
        Map<Object, Object> map = new IdentityHashMap<Object, Object>(toExisting);
        map.put(freshBlock, block);
        for (Object s : existing) { Object f = byProps.get(props(s)); if (f != null) map.put(f, s); }
        List<String> changed = new ArrayList<String>();
        int matched = 0;
        for (Object s : existing) {
            Object f = byProps.get(props(s));
            if (f == null) continue;
            matched++;
            List<String> c = copyFields(f, s, map);
            initCache(s);
            if (changed.isEmpty()) for (String x : c) changed.add("state " + x);   // the first state's changes stand for all
        }
        if (matched < existing.size()) changed.add("states matched " + matched + "/" + existing.size() + " " + byProps.keySet()
                + (existing.isEmpty() ? "" : " vs " + props(existing.get(0))));
        return changed;
    }

    /** A block state's shape cache and the values computed with it (Yarn initShapeCache, Mojang initCache). */
    private static void initCache(Object state) throws Exception {
        for (Method m : allMethods(state.getClass()))
            if ((m.getName().equals("initShapeCache") || m.getName().equals("initCache")) && m.getParameterTypes().length == 0) {
                m.setAccessible(true); m.invoke(state); return;
            }
    }

    private static List<?> states(Object block) throws Exception {
        Object manager = null;
        for (String getter : new String[]{"getStateManager", "getStateDefinition"})
            try { manager = block.getClass().getMethod(getter).invoke(block); break; } catch (NoSuchMethodException ignored) { }
        return (List<?>) manager.getClass().getMethod("getStates", new Class<?>[0]).invoke(manager);
    }

    private static Object defaultState(Object block) {
        for (String getter : new String[]{"getDefaultState", "defaultBlockState"})
            try { return block.getClass().getMethod(getter).invoke(block); } catch (Throwable ignored) { }
        return null;
    }

    /** "facing=north,lit=false": a state's property values (its entries map; Yarn getEntries, Mojang getValues). */
    private static String props(Object state) {
        for (String getter : new String[]{"getEntries", "getValues"}) {
            try {
                Map<?, ?> entries = (Map<?, ?>) state.getClass().getMethod(getter).invoke(state);
                TreeMap<String, String> sorted = new TreeMap<String, String>();
                for (Map.Entry<?, ?> e : entries.entrySet()) {
                    Object p = e.getKey();
                    String name;
                    try { name = String.valueOf(p.getClass().getMethod("getName").invoke(p)); } catch (Throwable t) { name = String.valueOf(p); }
                    sorted.put(name, String.valueOf(e.getValue()));
                }
                return sorted.toString();
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) { return "?" + t; }
        }
        return "?";
    }

    private static boolean isBlock(Object o) {
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass())
            if (k.getName().equals("net.minecraft.block.AbstractBlock") || k.getName().equals("net.minecraft.world.level.block.state.BlockBehaviour")) return true;
        return false;
    }

    /** Not copied: registry holders, state managers, states, codecs, collections (their contents point at the new object). */
    private static boolean skip(Object v) {
        if (v == null) return false;
        for (Class<?> k = v.getClass(); k != null; k = k.getSuperclass()) {
            String n = k.getName();
            if (n.equals("net.minecraft.state.State") || n.equals("net.minecraft.world.level.block.state.StateHolder")
                    || n.equals("net.minecraft.state.StateManager") || n.equals("net.minecraft.world.level.block.state.StateDefinition")) return true;
        }
        String n = v.getClass().getName();
        if (n.contains("RegistryEntry") || n.contains("Holder$") || n.contains("Codec")) return true;
        return v instanceof Map || v instanceof Collection || v.getClass().getName().startsWith("com.google.common.collect.");
    }

    private static boolean same(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        Class<?> c = a.getClass();
        if (c.isPrimitive() || a instanceof Number || a instanceof Boolean || a instanceof String || a instanceof Enum || a instanceof Character) return a.equals(b);
        return a.getClass().isSynthetic() && a.getClass() == b.getClass();   // a lambda from the same place: the same code
    }

    /** Values whose equality means something: simple ones, and types with their own equals (records, component maps). */
    private static boolean comparable(Object a, Object b) {
        Object v = a != null ? a : b;
        if (v == null) return false;
        if (v instanceof Number || v instanceof Boolean || v instanceof String || v instanceof Enum || v instanceof Character) return true;
        try { return v.getClass().getMethod("equals", Object.class).getDeclaringClass() != Object.class; } catch (NoSuchMethodException e) { return false; }
    }

    private static String brief(Object v) {
        if (v == null) return "null";
        String s = v instanceof Number || v instanceof Boolean || v instanceof String || v instanceof Enum ? String.valueOf(v) : v.getClass().getSimpleName();
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    private static String join(List<String> parts, int max) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.size() && i < max; i++) b.append(i == 0 ? "" : ", ").append(parts.get(i));
        if (parts.size() > max) b.append(", +").append(parts.size() - max);
        return b.toString();
    }

    private static List<Method> allMethods(Class<?> c) {
        List<Method> out = new ArrayList<Method>();
        for (Class<?> k = c; k != null; k = k.getSuperclass()) out.addAll(Arrays.asList(k.getDeclaredMethods()));
        return out;
    }

    /** A final instance field, through Unsafe (reflection cannot set it). */
    private static void putFinal(Field f, Object target, Object v) {
        try {
            Field uf = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            uf.setAccessible(true);
            Object u = uf.get(null);
            Class<?> uc = u.getClass();
            long off = (Long) uc.getMethod("objectFieldOffset", Field.class).invoke(u, f);
            Class<?> t = f.getType();
            if (t == int.class) uc.getMethod("putInt", Object.class, long.class, int.class).invoke(u, target, off, ((Integer) v).intValue());
            else if (t == float.class) uc.getMethod("putFloat", Object.class, long.class, float.class).invoke(u, target, off, ((Float) v).floatValue());
            else if (t == boolean.class) uc.getMethod("putBoolean", Object.class, long.class, boolean.class).invoke(u, target, off, ((Boolean) v).booleanValue());
            else if (t == double.class) uc.getMethod("putDouble", Object.class, long.class, double.class).invoke(u, target, off, ((Double) v).doubleValue());
            else if (t == long.class) uc.getMethod("putLong", Object.class, long.class, long.class).invoke(u, target, off, ((Long) v).longValue());
            else if (t.isPrimitive()) { f.set(target, v); }
            else uc.getMethod("putObjectVolatile", Object.class, long.class, Object.class).invoke(u, target, off, v);
        } catch (Throwable e) {
            throw new RuntimeException("cannot set " + f.getName() + ": " + e);
        }
    }

    private SettingsTransfer() {}
}
