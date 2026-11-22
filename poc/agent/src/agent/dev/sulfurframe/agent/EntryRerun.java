package dev.sulfurframe.agent;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Runs a changed mod entry point (onInitialize, onInitializeClient, onInitializeServer) again on the object Fabric
 * Loader made for it, without leaving the first run's hooks behind: before the run, what the owner classes (the entry
 * point and the classes it calls) registered into Fabric's events and into the registrations that refuse a second
 * entry is taken out; the run puts back what the new code registers. Registry entries are handled by the Registry
 * hook (already there: kept; new: added), which needs a game started with the agent.
 *
 * Who registered a listener is read from its class: a lambda or method reference is a hidden class named after the
 * class it was written in ("Owner$$Lambda..."), an anonymous class is "Owner$1". Registrations keyed by an id
 * (payload types, reload listeners) are also matched by the mod's namespace.
 *
 * Side effects other than registering (starting a thread, writing a file) happen again: keep them idempotent.
 * Fabric only for now (entry points come from Fabric Loader). Java 8, reflection only.
 */
final class EntryRerun {
    /**
     * Find the entry point object and take out what the owners registered before; returns what {@link #run} needs.
     * Before the classes are redefined: an old listener may call a lambda method the new class no longer has.
     */
    static Object[] prepare(Class<?> real, String[] methods, List<String> owners) throws Throwable {
        String modId = (String) find(real, methods[0])[1];
        int removed = purge(real.getClassLoader(), owners, modId);
        return new Object[]{real, methods, removed};
    }

    /** Run the entry point methods again (the class is redefined by now). */
    static void run(Object[] prepared, List<String> out) throws Throwable {
        Class<?> real = (Class<?>) prepared[0];
        for (String method : (String[]) prepared[1]) {
            real.getMethod(method).invoke(find(real, method)[0]);
            out.add("ran " + real.getName() + "." + method + " again (" + prepared[2] + " earlier registration(s) replaced)");
        }
    }

    /** {entry point object, mod id} of this class for this entry point method. */
    private static Object[] find(Class<?> real, String method) throws Throwable {
        if (!"true".equals(System.getProperty("dev.sulfurframe.registryHooked")))
            throw new IllegalStateException("the game was not started by SulfurFrame (no registry hook): restart to apply " + method);
        String key = method.equals("onInitializeClient") ? "client" : method.equals("onInitializeServer") ? "server" : "main";
        ClassLoader l = real.getClassLoader();
        Class<?> loaderApi = Class.forName("net.fabricmc.loader.api.FabricLoader", true, l);
        Object loader = loaderApi.getMethod("getInstance").invoke(null);
        Object instance = null;
        String modId = null;
        // the entry point's own type: Fabric hands back the objects it made at startup (another type makes new ones)
        Class<?> type = Class.forName(key.equals("client") ? "net.fabricmc.api.ClientModInitializer"
                : key.equals("server") ? "net.fabricmc.api.DedicatedServerModInitializer" : "net.fabricmc.api.ModInitializer", false, l);
        // through the public API interfaces: the implementing classes are not public (IllegalAccessException)
        Method getEntrypoint = Class.forName("net.fabricmc.loader.api.entrypoint.EntrypointContainer", false, l).getMethod("getEntrypoint");
        Method getProvider = Class.forName("net.fabricmc.loader.api.entrypoint.EntrypointContainer", false, l).getMethod("getProvider");
        Method getMetadata = Class.forName("net.fabricmc.loader.api.ModContainer", false, l).getMethod("getMetadata");
        Method getId = Class.forName("net.fabricmc.loader.api.metadata.ModMetadata", false, l).getMethod("getId");
        for (Object c : (List<?>) loaderApi.getMethod("getEntrypointContainers", String.class, Class.class).invoke(loader, key, type)) {
            Object e = getEntrypoint.invoke(c);
            if (e != null && e.getClass() == real) {
                instance = e;
                modId = (String) getId.invoke(getMetadata.invoke(getProvider.invoke(c)));
            }
        }
        if (instance == null) throw new IllegalStateException(real.getName() + " is not a Fabric '" + key + "' entry point: restart to apply " + method);
        return new Object[]{instance, modId};
    }

    /** Take out what the owners registered before; returns how many. */
    static int purge(ClassLoader l, List<String> owners, String modId) {
        int n = 0;
        n += purgeEvents(l, owners);
        // registrations that throw or pile up on a second entry: {class, static field, how}
        String[][] places = {
                {"net.fabricmc.fabric.impl.client.rendering.BuiltinItemRendererRegistryImpl", "RENDERERS", "values"},
                {"net.fabricmc.fabric.impl.client.rendering.ArmorRendererRegistryImpl", "RENDERERS", "values"},
                {"net.minecraft.client.gui.screen.ingame.HandledScreens", "PROVIDERS", "values"},
                {"net.minecraft.client.gui.screens.MenuScreens", "SCREENS", "values"},
        };
        for (String[] p : places) {
            try { n += purgeContainer(field(Class.forName(p[0], false, l), p[1]).get(null), owners, null); } catch (Throwable ignored) { }
        }
        try {   // payload types: keyed by id
            Class<?> c = Class.forName("net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl", false, l);
            for (String f : new String[]{"CONFIGURATION_C2S", "CONFIGURATION_S2C", "PLAY_C2S", "PLAY_S2C"})
                n += purgeContainer(field(c, "packetTypes").get(field(c, f).get(null)), owners, modId);
        } catch (Throwable ignored) { }
        try {   // resource reload listeners: by class, and their ids
            Class<?> c = Class.forName("net.fabricmc.fabric.impl.resource.loader.ResourceManagerHelperImpl", false, l);
            Method fabricId = Class.forName("net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener", false, l).getMethod("getFabricId");
            for (Object helper : ((Map<?, ?>) field(c, "registryMap").get(null)).values()) {
                Set<?> listeners = (Set<?>) field(c, "addedListeners").get(helper);
                Set<?> ids = (Set<?>) field(c, "addedListenerIds").get(helper);
                for (Iterator<?> it = listeners.iterator(); it.hasNext(); ) {
                    Object listener = it.next();
                    if (!owned(listener, owners)) continue;
                    try { ids.remove(fabricId.invoke(listener)); } catch (Throwable ignored) { }   // the interface: the class may be anonymous
                    it.remove();
                    n++;
                }
            }
        } catch (Throwable ignored) { }
        return n;
    }

    /** Listeners of every Fabric event that came from the owners. */
    private static int purgeEvents(ClassLoader l, List<String> owners) {
        int n = 0;
        try {
            Class<?> factory = Class.forName("net.fabricmc.fabric.impl.base.event.EventFactoryImpl", false, l);
            Class<?> event = Class.forName("net.fabricmc.fabric.impl.base.event.ArrayBackedEvent", false, l);
            Class<?> phaseData = Class.forName("net.fabricmc.fabric.impl.base.event.EventPhaseData", false, l);
            Field lock = field(event, "lock"), sorted = field(event, "sortedPhases"), listeners = field(phaseData, "listeners");
            Method rebuild = event.getDeclaredMethod("rebuildInvoker", int.class);
            rebuild.setAccessible(true);
            for (Object e : new ArrayList<Object>((Set<?>) field(factory, "ARRAY_BACKED_EVENTS").get(null))) {
                synchronized (lock.get(e)) {
                    int total = 0, gone = 0;
                    for (Object phase : (List<?>) sorted.get(e)) {
                        Object[] ls = (Object[]) listeners.get(phase);
                        List<Object> keep = new ArrayList<Object>();
                        for (Object o : ls) if (owned(o, owners)) gone++; else keep.add(o);
                        if (keep.size() != ls.length) {
                            Object[] a = (Object[]) Array.newInstance(ls.getClass().getComponentType(), keep.size());
                            listeners.set(phase, keep.toArray(a));
                        }
                        total += keep.size();
                    }
                    if (gone > 0) { rebuild.invoke(e, total); n += gone; }
                }
            }
        } catch (Throwable t) {
            SfHooks.log("runtime registration: events not cleaned: " + t);
        }
        return n;
    }

    /** Remove owned entries from a Map (by value, or by key namespace) or a Collection. */
    private static int purgeContainer(Object c, List<String> owners, String namespace) {
        int n = 0;
        if (c instanceof Map) {
            for (Iterator<? extends Map.Entry<?, ?>> it = ((Map<?, ?>) c).entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<?, ?> e = it.next();
                boolean mine = owned(e.getValue(), owners) || (namespace != null && String.valueOf(e.getKey()).startsWith(namespace + ":"));
                if (mine) { it.remove(); n++; }
            }
        } else if (c instanceof Collection) {
            for (Iterator<?> it = ((Collection<?>) c).iterator(); it.hasNext(); ) if (owned(it.next(), owners)) { it.remove(); n++; }
        }
        return n;
    }

    /** Was this object (a listener, a provider) written in one of the owner classes? */
    static boolean owned(Object o, List<String> owners) {
        if (o == null) return false;
        String c = o.getClass().getName();
        for (String owner : owners) if (c.equals(owner) || c.startsWith(owner + "$")) return true;
        return false;
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try { Field f = k.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private EntryRerun() {}
}
