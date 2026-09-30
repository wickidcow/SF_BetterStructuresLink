package io.github.wickidcow.sfbetterstructureslink;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

final class ItemProviderRegistry {

    enum ResolutionStatus {
        AVAILABLE,
        MISSING,
        DISABLED,
        PROVIDER_UNAVAILABLE,
        ERROR
    }

    record Resolution(ResolutionStatus status, ItemStack stack, String detail) {
        static Resolution available(ItemStack stack) {
            return new Resolution(ResolutionStatus.AVAILABLE, stack, null);
        }

        static Resolution of(ResolutionStatus status, String detail) {
            return new Resolution(status, null, detail);
        }
    }

    record ProviderState(String id, boolean configuredEnabled, boolean available, String detail) {}

    private final SFBetterStructuresLink plugin;
    private final Map<String, ProviderSlot> providers = new LinkedHashMap<>();

    ItemProviderRegistry(SFBetterStructuresLink plugin) {
        this.plugin = plugin;
    }

    void refresh() {
        providers.clear();

        register("slimefun", createSlimefunProvider());
        register("rebar", createRebarProvider("rebar", null));
        register("pylon", createRebarProvider("pylon", "Pylon"));
    }

    boolean isAvailable(String providerId) {
        ProviderSlot slot = providers.get(normalize(providerId));
        return slot != null && slot.available();
    }

    Resolution resolve(String providerId, String itemId) {
        ProviderSlot slot = providers.get(normalize(providerId));
        if (slot == null) {
            return Resolution.of(
                    ResolutionStatus.PROVIDER_UNAVAILABLE,
                    "Unknown provider '" + providerId + "'");
        }
        if (!slot.configuredEnabled()) {
            return Resolution.of(
                    ResolutionStatus.PROVIDER_UNAVAILABLE,
                    "Provider is disabled in config");
        }
        if (slot.provider() == null || !slot.provider().available()) {
            return Resolution.of(
                    ResolutionStatus.PROVIDER_UNAVAILABLE,
                    slot.detail());
        }

        return slot.provider().resolve(itemId);
    }

    List<ProviderState> states() {
        List<ProviderState> states = new ArrayList<>();
        for (Map.Entry<String, ProviderSlot> entry : providers.entrySet()) {
            ProviderSlot slot = entry.getValue();
            states.add(new ProviderState(
                    entry.getKey(),
                    slot.configuredEnabled(),
                    slot.available(),
                    slot.detail()));
        }
        return List.copyOf(states);
    }

    private void register(String id, ProviderSlot slot) {
        providers.put(id, slot);
    }

    private ProviderSlot createSlimefunProvider() {
        boolean enabled = plugin.getConfig().getBoolean("providers.slimefun.enabled", true);
        if (!enabled) {
            return ProviderSlot.disabled();
        }

        Plugin slimefun = plugin.getServer().getPluginManager().getPlugin("Slimefun");
        if (slimefun == null || !slimefun.isEnabled()) {
            return ProviderSlot.unavailable("Slimefun is not installed/enabled");
        }

        try {
            ClassLoader loader = slimefun.getClass().getClassLoader();
            Class<?> itemClass =
                    Class.forName("io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem", true, loader);
            Method getById = itemClass.getMethod("getById", String.class);
            Method isDisabled = itemClass.getMethod("isDisabled");
            Method getItem = itemClass.getMethod("getItem");

            return ProviderSlot.available(
                    new SlimefunReflectionProvider(getById, isDisabled, getItem),
                    "Slimefun registry available");
        } catch (ReflectiveOperationException | LinkageError exception) {
            return ProviderSlot.unavailable(
                    "Slimefun API hook failed: " + exception.getClass().getSimpleName());
        }
    }

    private ProviderSlot createRebarProvider(String providerId, String additionallyRequiredPlugin) {
        boolean enabled = plugin.getConfig().getBoolean("providers." + providerId + ".enabled", true);
        if (!enabled) {
            return ProviderSlot.disabled();
        }

        Plugin rebar = plugin.getServer().getPluginManager().getPlugin("Rebar");
        if (rebar == null || !rebar.isEnabled()) {
            return ProviderSlot.unavailable("Rebar is not installed/enabled");
        }

        if (additionallyRequiredPlugin != null) {
            Plugin required =
                    plugin.getServer().getPluginManager().getPlugin(additionallyRequiredPlugin);
            if (required == null || !required.isEnabled()) {
                return ProviderSlot.unavailable(
                        additionallyRequiredPlugin + " is not installed/enabled");
            }
        }

        try {
            ClassLoader loader = rebar.getClass().getClassLoader();
            Class<?> registryClass =
                    Class.forName("io.github.pylonmc.rebar.registry.RebarRegistry", true, loader);
            Class<?> schemaClass =
                    Class.forName("io.github.pylonmc.rebar.item.RebarItemSchema", true, loader);

            Field itemsField = registryClass.getField("ITEMS");
            Object itemRegistry = itemsField.get(null);
            Method registryGet = registryClass.getMethod("get", NamespacedKey.class);
            Method getItemStack = schemaClass.getMethod("getItemStack");
            Method isDisabled = schemaClass.getMethod("isDisabled");

            String enforcedNamespace = "pylon".equals(providerId) ? "pylon" : null;
            return ProviderSlot.available(
                    new RebarReflectionProvider(
                            providerId,
                            itemRegistry,
                            registryGet,
                            getItemStack,
                            isDisabled,
                            enforcedNamespace),
                    providerId.equals("pylon")
                            ? "Pylon items available through Rebar registry"
                            : "Rebar registry available");
        } catch (ReflectiveOperationException | LinkageError exception) {
            return ProviderSlot.unavailable(
                    "Rebar API hook failed: " + exception.getClass().getSimpleName());
        }
    }

    private static String normalize(String providerId) {
        return providerId == null ? "" : providerId.trim().toLowerCase(Locale.ROOT);
    }

    private interface ItemProvider {
        boolean available();

        Resolution resolve(String itemId);
    }

    private record ProviderSlot(
            boolean configuredEnabled,
            ItemProvider provider,
            String detail) {

        static ProviderSlot disabled() {
            return new ProviderSlot(false, null, "disabled in config");
        }

        static ProviderSlot unavailable(String detail) {
            return new ProviderSlot(true, null, detail);
        }

        static ProviderSlot available(ItemProvider provider, String detail) {
            return new ProviderSlot(true, provider, detail);
        }

        boolean available() {
            return configuredEnabled && provider != null && provider.available();
        }
    }

    private static final class SlimefunReflectionProvider implements ItemProvider {
        private final Method getById;
        private final Method isDisabled;
        private final Method getItem;

        private SlimefunReflectionProvider(Method getById, Method isDisabled, Method getItem) {
            this.getById = getById;
            this.isDisabled = isDisabled;
            this.getItem = getItem;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public Resolution resolve(String itemId) {
            if (itemId == null || itemId.isBlank()) {
                return Resolution.of(ResolutionStatus.MISSING, "Blank Slimefun item ID");
            }

            try {
                Object item = getById.invoke(null, itemId);
                if (item == null) {
                    return Resolution.of(
                            ResolutionStatus.MISSING,
                            "Slimefun item '" + itemId + "' is not registered");
                }

                if (Boolean.TRUE.equals(isDisabled.invoke(item))) {
                    return Resolution.of(
                            ResolutionStatus.DISABLED,
                            "Slimefun item '" + itemId + "' is disabled");
                }

                Object stack = getItem.invoke(item);
                if (!(stack instanceof ItemStack itemStack)) {
                    return Resolution.of(
                            ResolutionStatus.ERROR,
                            "Slimefun item '" + itemId + "' did not return an ItemStack");
                }

                return Resolution.available(itemStack.clone());
            } catch (IllegalAccessException | InvocationTargetException exception) {
                return Resolution.of(
                        ResolutionStatus.ERROR,
                        "Slimefun lookup failed for '" + itemId + "': "
                                + exception.getClass().getSimpleName());
            }
        }
    }

    private static final class RebarReflectionProvider implements ItemProvider {
        private final String providerId;
        private final Object itemRegistry;
        private final Method registryGet;
        private final Method getItemStack;
        private final Method isDisabled;
        private final String enforcedNamespace;

        private RebarReflectionProvider(
                String providerId,
                Object itemRegistry,
                Method registryGet,
                Method getItemStack,
                Method isDisabled,
                String enforcedNamespace) {
            this.providerId = providerId;
            this.itemRegistry = itemRegistry;
            this.registryGet = registryGet;
            this.getItemStack = getItemStack;
            this.isDisabled = isDisabled;
            this.enforcedNamespace = enforcedNamespace;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public Resolution resolve(String itemId) {
            NamespacedKey key = parseKey(itemId);
            if (key == null) {
                return Resolution.of(
                        ResolutionStatus.MISSING,
                        "Invalid " + providerId + " item key '" + itemId + "'");
            }

            if (enforcedNamespace != null && !enforcedNamespace.equals(key.getNamespace())) {
                return Resolution.of(
                        ResolutionStatus.MISSING,
                        providerId + " provider requires namespace '" + enforcedNamespace + "'");
            }

            try {
                Object schema = registryGet.invoke(itemRegistry, key);
                if (schema == null) {
                    return Resolution.of(
                            ResolutionStatus.MISSING,
                            "Rebar item '" + key + "' is not registered");
                }

                if (Boolean.TRUE.equals(isDisabled.invoke(schema))) {
                    return Resolution.of(
                            ResolutionStatus.DISABLED,
                            "Rebar item '" + key + "' is disabled");
                }

                Object stack = getItemStack.invoke(schema);
                if (!(stack instanceof ItemStack itemStack)) {
                    return Resolution.of(
                            ResolutionStatus.ERROR,
                            "Rebar item '" + key + "' did not return an ItemStack");
                }

                return Resolution.available(itemStack.clone());
            } catch (IllegalAccessException | InvocationTargetException exception) {
                return Resolution.of(
                        ResolutionStatus.ERROR,
                        "Rebar lookup failed for '" + key + "': "
                                + exception.getClass().getSimpleName());
            }
        }

        private NamespacedKey parseKey(String itemId) {
            if (itemId == null || itemId.isBlank()) {
                return null;
            }

            String normalized = itemId.trim().toLowerCase(Locale.ROOT);
            if (!normalized.contains(":")) {
                normalized = providerId + ":" + normalized;
            }
            return NamespacedKey.fromString(normalized);
        }
    }
}
