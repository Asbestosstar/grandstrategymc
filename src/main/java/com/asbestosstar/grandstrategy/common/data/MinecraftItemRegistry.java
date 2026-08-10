package com.asbestosstar.grandstrategy.common.data;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loader-independent runtime probe for Minecraft item ids.
 *
 * Reflection keeps the data layer independent of a particular Mojang registry API
 * signature while still allowing technologies/recipes to disappear when a required
 * modded item is absent.
 */
public final class MinecraftItemRegistry {
    private static final Map<String, Boolean> CACHE = new ConcurrentHashMap<>();

    private MinecraftItemRegistry() { }

    public static boolean itemExists(String itemId) {
        if (itemId == null || itemId.isBlank()) return false;
        String normalised = itemId.trim().toLowerCase();
        return CACHE.computeIfAbsent(normalised, MinecraftItemRegistry::probeItem);
    }

    public static void clearCache() { CACHE.clear(); }

    private static boolean probeItem(String itemId) {
        try {
            Class<?> rlClass = Class.forName("net.minecraft.resources.ResourceLocation");
            Object key = parseResourceLocation(rlClass, itemId);
            if (key == null) return false;

            Class<?> registriesClass = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
            Object itemRegistry = registriesClass.getField("ITEM").get(null);
            if (itemRegistry == null) return false;

            for (String methodName : new String[]{"containsKey", "containsKeyOrThrow"}) {
                try {
                    Method method = itemRegistry.getClass().getMethod(methodName, rlClass);
                    Object value = method.invoke(itemRegistry, key);
                    if (value instanceof Boolean bool) return bool;
                } catch (ReflectiveOperationException ignored) { }
            }
            try {
                Method optional = itemRegistry.getClass().getMethod("getOptional", rlClass);
                Object value = optional.invoke(itemRegistry, key);
                if (value instanceof java.util.Optional<?> result) return result.isPresent();
            } catch (ReflectiveOperationException ignored) { }

            // Final fallback: registry.get(key) generally returns AIR/default for a
            // missing item. Compare against minecraft:air rather than accepting it.
            try {
                Method get = itemRegistry.getClass().getMethod("getValue", rlClass);
                Object value = get.invoke(itemRegistry, key);
                if (value != null) return !"minecraft:air".equals(itemId) && !value.toString().contains("air");
            } catch (ReflectiveOperationException ignored) { }
        } catch (Throwable ignored) {
            // Registry may not be initialised yet during very early class loading.
        }
        // Vanilla ids used by built-in definitions are known to exist once Minecraft
        // itself is running; this fallback avoids hiding all starter tech on an API
        // mapping change while modded ids remain conservative.
        return itemId.startsWith("minecraft:");
    }

    private static Object parseResourceLocation(Class<?> type, String id) {
        for (String methodName : new String[]{"parse", "tryParse"}) {
            try {
                Method method = type.getMethod(methodName, String.class);
                return method.invoke(null, id);
            } catch (ReflectiveOperationException ignored) { }
        }
        int colon = id.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : id.substring(0, colon);
        String path = colon < 0 ? id : id.substring(colon + 1);
        try {
            Method method = type.getMethod("fromNamespaceAndPath", String.class, String.class);
            return method.invoke(null, namespace, path);
        } catch (ReflectiveOperationException ignored) { }
        return null;
    }
}
