package com.asbestosstar.grandstrategy.common.data;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loader-independent runtime probe/resolver for Minecraft registry item ids.
 *
 * Optional integrations use only namespaced ids (for example
 * "drenough_forging:steel_ingot"). No class from an optional mod is loaded or
 * invoked. Reflection is limited to Minecraft's own registry surface so this
 * common code remains independent of Forge/Fabric/NeoForge loader APIs.
 */
public final class MinecraftItemRegistry {
    private static final Map<String, Optional<Item>> ITEM_CACHE = new ConcurrentHashMap<>();

    private MinecraftItemRegistry() { }

    public static boolean itemExists(String itemId) {
        if (itemId == null || itemId.isBlank()) return false;
        String normalised = itemId.trim().toLowerCase();
        Item resolved = item(normalised);
        // Preserve the previous conservative contract for vanilla definitions: if
        // Minecraft's registry method name changes, built-in recipes/tech must not
        // disappear merely because the reflective probe could not resolve them.
        return resolved != null || normalised.startsWith("minecraft:");
    }

    /** Resolves an item by registry id, returning null when the id is absent. */
    public static Item item(String itemId) {
        if (itemId == null || itemId.isBlank()) return null;
        String normalised = itemId.trim().toLowerCase();
        Optional<Item> cached = ITEM_CACHE.get(normalised);
        if (cached != null) return cached.orElse(null);

        Item resolved = probeItem(normalised);
        // These probes are first used after mod discovery/world bootstrap, when the
        // item registry is stable. Cache misses as well as hits so an absent optional
        // mod does not turn every worker tool-tier check into reflective registry work.
        ITEM_CACHE.put(normalised, Optional.ofNullable(resolved));
        return resolved;
    }

    /** Returns the registry id for an Item without consulting any optional-mod API. */
    public static String itemId(Item item) {
        if (item == null) return null;
        try {
            Class<?> registriesClass = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
            Object itemRegistry = registriesClass.getField("ITEM").get(null);
            if (itemRegistry == null) return null;

            for (String methodName : new String[]{"getKey", "getResourceKey"}) {
                try {
                    Method method = itemRegistry.getClass().getMethod(methodName, Object.class);
                    Object value = method.invoke(itemRegistry, item);
                    if (value == null) continue;
                    if (value instanceof Optional<?> optional) {
                        if (optional.isEmpty()) continue;
                        value = optional.get();
                    }
                    String text = value.toString();
                    int minecraft = text.indexOf("minecraft:");
                    int drenough = text.indexOf("drenough_forging:");
                    int start = minecraft >= 0 ? minecraft : drenough;
                    if (start >= 0) {
                        int end = text.indexOf(']', start);
                        return (end > start ? text.substring(start, end) : text.substring(start))
                                .replaceAll("[^a-z0-9_./:-].*$", "");
                    }
                    if (text.contains(":")) return text;
                } catch (ReflectiveOperationException ignored) { }
            }

            // Registry#getKey(Item) normally exists with the concrete Item parameter
            // on modern mappings. Discover it if the erased Object signature above
            // was not exposed.
            for (Method method : itemRegistry.getClass().getMethods()) {
                if (!"getKey".equals(method.getName()) || method.getParameterCount() != 1) continue;
                if (!method.getParameterTypes()[0].isAssignableFrom(item.getClass())
                        && !method.getParameterTypes()[0].isAssignableFrom(Item.class)) continue;
                Object value = method.invoke(itemRegistry, item);
                if (value != null) return value.toString();
            }
        } catch (Throwable ignored) { }
        return null;
    }

    public static void clearCache() {
        ITEM_CACHE.clear();
    }

    private static Item probeItem(String itemId) {
        try {
            Class<?> rlClass = Class.forName("net.minecraft.resources.ResourceLocation");
            Object key = parseResourceLocation(rlClass, itemId);
            if (key == null) return null;

            Class<?> registriesClass = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
            Object itemRegistry = registriesClass.getField("ITEM").get(null);
            if (itemRegistry == null) return null;

            Object value = null;
            for (String methodName : new String[]{"getValue", "get"}) {
                try {
                    Method method = itemRegistry.getClass().getMethod(methodName, rlClass);
                    value = method.invoke(itemRegistry, key);
                    if (value != null) break;
                } catch (ReflectiveOperationException ignored) { }
            }
            if (value == null) {
                try {
                    Method optional = itemRegistry.getClass().getMethod("getOptional", rlClass);
                    Object optionalValue = optional.invoke(itemRegistry, key);
                    if (optionalValue instanceof Optional<?> result) value = result.orElse(null);
                } catch (ReflectiveOperationException ignored) { }
            }
            if (value instanceof Item item && item != Items.AIR) return item;
        } catch (Throwable ignored) {
            // Registry may not be initialised yet during very early class loading.
        }

        // Known vanilla ids are allowed a small direct fallback. Optional ids never
        // receive a fabricated value: absence means the integration stays disabled.
        if ("minecraft:air".equals(itemId)) return null;
        try {
            if ("minecraft:crafting_table".equals(itemId)) return Items.CRAFTING_TABLE;
            if ("minecraft:furnace".equals(itemId)) return Items.FURNACE;
            if ("minecraft:blast_furnace".equals(itemId)) return Items.BLAST_FURNACE;
            if ("minecraft:stone_pickaxe".equals(itemId)) return Items.STONE_PICKAXE;
            if ("minecraft:iron_ingot".equals(itemId)) return Items.IRON_INGOT;
            if ("minecraft:diamond".equals(itemId)) return Items.DIAMOND;
            if ("minecraft:paper".equals(itemId)) return Items.PAPER;
        } catch (Throwable ignored) { }
        return null;
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
