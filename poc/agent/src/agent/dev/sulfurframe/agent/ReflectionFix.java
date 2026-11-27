package dev.sulfurframe.agent;

import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Reflection that is already in use keeps working when a redefinition moves fields. With enhanced redefinition (JBR),
 * adding a field lays the class out again: a new boolean fills a gap, and the fields after it move (seen: an enum
 * field from offset 124 to 128). Core reflection made its accessor before the swap, a field method handle holding the
 * offset, and a library that caches its Field objects (Gson's type adapters) goes on using it. JBR does move those
 * offsets, but by the field's position in the class, so with a field added in front the handle points at a
 * neighbour: the bytes of other fields taken as an object pointer, and the JVM crashes (EXCEPTION_ACCESS_VIOLATION,
 * a settings autosave thread 80 s after such a swap).
 *
 * Before the redefinition this takes the accessors core reflection made for the class (its root Field objects, in
 * Class.reflectionData); after it, every handle of each field is set to the field's real offset. Field copies share
 * the root's accessor object, so this reaches every user of core reflection. Method handles made directly
 * (MethodHandles.Lookup) are not reachable. Java 17+ internals; anything unexpected skips it (logged).
 * Java 8 source, so the module API is called reflectively.
 */
final class ReflectionFix {
    private ReflectionFix() {}

    /** A field core reflection has an accessor for, with its offset before the redefinition. */
    static final class Entry {
        final Class<?> owner; final String name; final boolean isStatic; final long before; final List<Object> accessors;
        Entry(Class<?> owner, String name, boolean isStatic, long before, List<Object> accessors) {
            this.owner = owner; this.name = name; this.isStatic = isStatic; this.before = before; this.accessors = accessors;
        }
    }

    private static Object U;                 // jdk.internal.misc.Unsafe
    private static boolean failed;
    private static Method objectFieldOffsetCN, objectFieldOffsetF, staticFieldOffset, getReference, getInt, putInt, getLong, putLong;

    private static synchronized boolean init(Instrumentation inst) {
        if (U != null) return true;
        if (failed) return false;
        try {
            Method getModule = Class.class.getMethod("getModule");
            Object base = getModule.invoke(Object.class);
            Object me = getModule.invoke(ReflectionFix.class);
            Class<?> moduleClass = Class.forName("java.lang.Module");
            Map<String, Set<Object>> exports = new HashMap<String, Set<Object>>();
            exports.put("jdk.internal.misc", Collections.singleton(me));
            Method redefineModule = Instrumentation.class.getMethod("redefineModule", moduleClass, Set.class, Map.class, Map.class, Set.class, Map.class);
            redefineModule.invoke(inst, base, Collections.emptySet(), exports, Collections.emptyMap(), Collections.emptySet(), Collections.emptyMap());
            Class<?> uc = Class.forName("jdk.internal.misc.Unsafe");
            Object u = uc.getMethod("getUnsafe").invoke(null);
            objectFieldOffsetCN = uc.getMethod("objectFieldOffset", Class.class, String.class);
            objectFieldOffsetF = uc.getMethod("objectFieldOffset", Field.class);
            staticFieldOffset = uc.getMethod("staticFieldOffset", Field.class);
            getReference = uc.getMethod("getReference", Object.class, long.class);
            getInt = uc.getMethod("getInt", Object.class, long.class);
            putInt = uc.getMethod("putInt", Object.class, long.class, int.class);
            getLong = uc.getMethod("getLong", Object.class, long.class);
            putLong = uc.getMethod("putLong", Object.class, long.class, long.class);
            U = u;
            return true;
        } catch (Throwable t) {
            failed = true;
            SfHooks.log("hotswap: reflection accessors cannot be followed in this JVM (" + t + ")");
            return false;
        }
    }

    private static long off(Class<?> c, String name) throws Exception { return (Long) objectFieldOffsetCN.invoke(U, c, name); }
    private static Object ref(Object o, long off) throws Exception { return getReference.invoke(U, o, off); }

    /** The fields of these classes that core reflection made accessors for, with their offsets now. */
    static List<Entry> before(Instrumentation inst, Collection<Class<?>> classes) {
        List<Entry> out = new ArrayList<Entry>();
        if (classes.isEmpty() || !init(inst)) return out;
        try {
            long rdOff = off(Class.class, "reflectionData");
            long accOff = off(Field.class, "fieldAccessor"), overrideOff = off(Field.class, "overrideFieldAccessor");
            for (Class<?> c : classes) {
                Object sr = ref(c, rdOff);
                Object rd = sr instanceof Reference ? ((Reference<?>) sr).get() : null;
                if (rd == null) continue;   // reflection never used on it (or the cache was dropped)
                Set<String> seen = new HashSet<String>();
                for (String arr : new String[]{"declaredFields", "publicFields"}) {
                    Object fields = ref(rd, off(rd.getClass(), arr));
                    if (!(fields instanceof Field[])) continue;
                    for (Field f : (Field[]) fields) {
                        if (f.getDeclaringClass() != c || !seen.add(f.getName())) continue;
                        List<Object> acc = new ArrayList<Object>(2);
                        Object a = ref(f, accOff), o = ref(f, overrideOff);
                        if (a != null) acc.add(a);
                        if (o != null && o != a) acc.add(o);
                        if (acc.isEmpty()) continue;
                        boolean st = Modifier.isStatic(f.getModifiers());
                        long before = (Long) (st ? staticFieldOffset : objectFieldOffsetF).invoke(U, f);
                        out.add(new Entry(c, f.getName(), st, before, acc));
                    }
                }
            }
        } catch (Throwable t) {
            SfHooks.log("hotswap: could not read reflection accessors (" + t + ")");
        }
        return out;
    }

    /** After the redefinition: accessors of fields that moved get the new offset. Lines for the answer. */
    static List<String> after(List<Entry> entries) {
        List<String> out = new ArrayList<String>();
        if (entries.isEmpty()) return out;
        Map<String, Integer> fixed = new LinkedHashMap<String, Integer>();
        for (Entry e : entries) {
            try {
                Field now;
                try { now = e.owner.getDeclaredField(e.name); } catch (NoSuchFieldException gone) { continue; }   // removed: nothing reads it right anymore, nothing to aim at
                if (Modifier.isStatic(now.getModifiers()) != e.isStatic) continue;
                long after = (Long) (e.isStatic ? staticFieldOffset : objectFieldOffsetF).invoke(U, now);
                // Even a field that stayed can need it: JBR moves the offsets of field method handles itself, but by
                // the field's position in the class (seen: 24 -> 32 for a field that went to 28), so a field added
                // in front points them at a neighbour. Every handle of this field gets its real offset.
                int[] n = new int[2];   // handles found, handles changed
                for (Object a : e.accessors) patch(a, after, now.getType(), 0, Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()), n);
                if (n[1] > 0) {
                    String k = e.owner.getName();
                    fixed.put(k, (fixed.containsKey(k) ? fixed.get(k) : 0) + 1);
                    SfHooks.log("hotswap: " + k + "." + e.name + " (offset " + e.before + " -> " + after + "): " + n[1] + " reflection accessor(s) set to it");
                } else if (n[0] == 0 && after != e.before) SfHooks.log("hotswap: " + e.owner.getName() + "." + e.name + " moved, but its reflection accessor is of an unknown kind ("
                        + e.accessors.get(0).getClass().getName() + "): code that read it by reflection before the swap may crash; restart if it does");
            } catch (Throwable t) {
                SfHooks.log("hotswap: could not update the reflection accessor of " + e.owner.getName() + "." + e.name + " (" + t + ")");
            }
        }
        for (Map.Entry<String, Integer> f : fixed.entrySet())
            out.add("reflection: " + f.getValue() + " moved field(s) of " + f.getKey() + " updated in cached accessors");
        return out;
    }

    /**
     * Point an accessor at the new offset: the offset field of an Unsafe accessor, or of the field method handles
     * (DirectMethodHandle$Accessor / $StaticAccessor) a method handle accessor holds, also inside bound handles.
     */
    private static void patch(Object o, long after, Class<?> type, int depth, Set<Object> seen, int[] n) throws Exception {
        if (o == null || depth > 4 || !seen.add(o)) return;
        String cn = o.getClass().getName();
        boolean instance = cn.equals("java.lang.invoke.DirectMethodHandle$Accessor");
        if (instance || cn.equals("java.lang.invoke.DirectMethodHandle$StaticAccessor")) {
            if (ref(o, off(o.getClass(), "fieldType")) != type) return;   // a handle of another field: not ours
            n[0]++;
            if (instance) {
                long fo = off(o.getClass(), "fieldOffset");
                if ((Integer) getInt.invoke(U, o, fo) != (int) after) { putInt.invoke(U, o, fo, (int) after); n[1]++; }
            } else {
                long so = off(o.getClass(), "staticOffset");
                if ((Long) getLong.invoke(U, o, so) != after) { putLong.invoke(U, o, so, after); n[1]++; }
            }
            return;
        }
        boolean mh = o instanceof MethodHandle;
        if (!mh && !cn.startsWith("jdk.internal.reflect.")) return;
        for (Class<?> k = o.getClass(); k != null && k != Object.class && k != MethodHandle.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                long fo = (Long) objectFieldOffsetF.invoke(U, f);
                if (!mh && f.getType() == long.class && f.getName().equals("fieldOffset")) {   // Unsafe field accessors (one per field)
                    n[0]++;
                    if ((Long) getLong.invoke(U, o, fo) != after) { putLong.invoke(U, o, fo, after); n[1]++; }
                } else if (!f.getType().isPrimitive()) {
                    Object v = ref(o, fo);
                    if (v instanceof MethodHandle) patch(v, after, type, depth + 1, seen, n);
                }
            }
        }
    }
}
