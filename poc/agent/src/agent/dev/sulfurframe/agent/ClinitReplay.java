package dev.sulfurframe.agent;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Static initializers that matter after a change but never run again:
 *
 *   enum         a new constant is a new static field the redefinition left null, missing from $VALUES (values(),
 *                valueOf, EnumSet). The changed initializer runs in a hidden nestmate copy (RegistryPatch.clinitCopy),
 *                which may call the private constructor; the constants the real class lacks are taken from it, get
 *                the ordinals after the existing ones and go to the end of $VALUES (EnumSet/EnumMap index by ordinal,
 *                so existing constants keep theirs), and the JDK's per-class enum caches are cleared.
 *   switchmap    javac's holder of a switch over an enum ("$SwitchMap$..." int arrays, sized and filled once): its
 *                arrays are replaced by the copy's, computed against the enum as it is now.
 *
 * A removed enum constant cannot be taken back (its object is still out there): that one asks for a restart.
 * Java 8 source; the hidden class and Unsafe are reached by reflection.
 */
final class ClinitReplay {
    private static int copies;

    static void run(Class<?> real, String classFile, String mode, List<String> out) throws Throwable {
        byte[] bytes = Files.readAllBytes(Paths.get(classFile));
        String copyName = real.getName().replace('.', '/') + "$SfInit" + (++copies);
        Class<?> patch = Class.forName("dev.sulfurframe.agent.impl.RegistryPatch", true, SfAgent.impl());
        byte[] copyBytes = (byte[]) patch.getMethod("clinitCopy", byte[].class, String.class).invoke(null, bytes, copyName);
        Class<?> copy = defineHiddenNestmate(real, copyBytes);   // initialized: its static initializer has run
        if (mode.equals("enum")) enumConstants(real, copy, out);
        else if (mode.equals("switchmap")) switchMaps(real, copy, out);
    }

    private static void enumConstants(Class<?> real, Class<?> copy, List<String> out) throws Throwable {
        Object unsafe = unsafe();
        Field valuesField = real.getDeclaredField("$VALUES");
        valuesField.setAccessible(true);
        Object[] old = (Object[]) valuesField.get(null);
        Set<String> oldNames = new HashSet<String>();
        for (Object o : old) oldNames.add(((Enum<?>) o).name());
        List<Object> added = new ArrayList<Object>();
        Set<String> nowNames = new HashSet<String>();
        Field ordinal = Enum.class.getDeclaredField("ordinal");
        for (Field f : real.getDeclaredFields()) {
            if (!f.isEnumConstant()) continue;
            nowNames.add(f.getName());
            f.setAccessible(true);
            if (f.get(null) != null) continue;
            Field cf = copy.getDeclaredField(f.getName());
            cf.setAccessible(true);
            Object constant = cf.get(null);
            if (constant == null) continue;
            putInt(unsafe, constant, ordinal, old.length + added.size());   // after the existing ones
            putStatic(unsafe, f, constant);
            added.add(constant);
        }
        List<String> removed = new ArrayList<String>();
        for (String name : oldNames) if (!nowNames.contains(name)) removed.add(name);
        if (!added.isEmpty()) {
            Object[] values = (Object[]) Array.newInstance(real, old.length + added.size());
            System.arraycopy(old, 0, values, 0, old.length);
            for (int i = 0; i < added.size(); i++) values[old.length + i] = added.get(i);
            putStatic(unsafe, valuesField, values);
            for (String cache : new String[]{"enumConstants", "enumConstantDirectory"}) {   // Class.getEnumConstantsShared, valueOf
                try { putObject(unsafe, real, Class.class.getDeclaredField(cache), null); } catch (Throwable t) { out.add("warning enum cache " + cache + ": " + t); }
            }
        }
        StringBuilder names = new StringBuilder();
        for (Object a : added) names.append(names.length() == 0 ? "" : ", ").append(((Enum<?>) a).name());
        out.add("ran " + real.getName() + " (enum: " + (added.isEmpty() ? "no new constant" : "new " + names) + ")");
        if (!removed.isEmpty()) out.add("restart needed: " + real.getName() + " (enum constant removed: " + String.join(", ", removed) + ")");
    }

    private static void switchMaps(Class<?> real, Class<?> copy, List<String> out) throws Throwable {
        Object unsafe = unsafe();
        int n = 0;
        for (Field f : real.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || !f.getName().startsWith("$SwitchMap$")) continue;
            Field cf = copy.getDeclaredField(f.getName());
            cf.setAccessible(true);
            putStatic(unsafe, f, cf.get(null));
            n++;
        }
        out.add("ran " + real.getName() + " (" + n + " enum switch map(s) rebuilt)");
    }

    /**
     * Lookup.defineHiddenClass(bytes, true, NESTMATE) in the real class's nest (Java 15+). That needs a full-privilege
     * lookup on the class, which privateLookupIn does not give: the JDK's trusted lookup, narrowed to the class, does.
     */
    private static Class<?> defineHiddenNestmate(Class<?> real, byte[] bytes) throws Throwable {
        Object unsafe = unsafe();
        Field implField = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
        Class<?> uc = unsafe.getClass();
        Object base = uc.getMethod("staticFieldBase", Field.class).invoke(unsafe, implField);
        long off = (Long) uc.getMethod("staticFieldOffset", Field.class).invoke(unsafe, implField);
        Object trusted = uc.getMethod("getObject", Object.class, long.class).invoke(unsafe, base, off);
        Object lookup = MethodHandles.Lookup.class.getMethod("in", Class.class).invoke(trusted, real);
        Class<?> option = Class.forName("java.lang.invoke.MethodHandles$Lookup$ClassOption");
        Object nestmate = Array.newInstance(option, 1);
        Array.set(nestmate, 0, option.getField("NESTMATE").get(null));
        Method define = lookup.getClass().getMethod("defineHiddenClass", byte[].class, boolean.class, nestmate.getClass());
        Object hidden;
        try { hidden = define.invoke(lookup, bytes, true, nestmate); }
        catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
        return (Class<?>) hidden.getClass().getMethod("lookupClass").invoke(hidden);
    }

    private static Object unsafe() throws Exception {
        Field u = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        u.setAccessible(true);
        return u.get(null);
    }

    /** A static field, final or not (reflection cannot set a static final). */
    private static void putStatic(Object unsafe, Field f, Object v) throws Exception {
        Class<?> uc = unsafe.getClass();
        Object base = uc.getMethod("staticFieldBase", Field.class).invoke(unsafe, f);
        long off = (Long) uc.getMethod("staticFieldOffset", Field.class).invoke(unsafe, f);
        uc.getMethod("putObjectVolatile", Object.class, long.class, Object.class).invoke(unsafe, base, off, v);
    }

    private static void putObject(Object unsafe, Object target, Field f, Object v) throws Exception {
        Class<?> uc = unsafe.getClass();
        long off = (Long) uc.getMethod("objectFieldOffset", Field.class).invoke(unsafe, f);
        uc.getMethod("putObjectVolatile", Object.class, long.class, Object.class).invoke(unsafe, target, off, v);
    }

    private static void putInt(Object unsafe, Object target, Field f, int v) throws Exception {
        Class<?> uc = unsafe.getClass();
        long off = (Long) uc.getMethod("objectFieldOffset", Field.class).invoke(unsafe, f);
        uc.getMethod("putIntVolatile", Object.class, long.class, int.class).invoke(unsafe, target, off, v);
    }

    private ClinitReplay() {}
}
