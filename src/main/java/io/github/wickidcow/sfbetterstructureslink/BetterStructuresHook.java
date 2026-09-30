package io.github.wickidcow.sfbetterstructureslink;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.bukkit.block.Container;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

final class BetterStructuresHook implements Listener {

    private static final String CHEST_FILL_EVENT =
            "com.magmaguy.betterstructures.api.ChestFillEvent";

    private final SFBetterStructuresLink plugin;
    private final LootService lootService;
    private final Method getContainer;
    private final Method getTreasureConfigFilename;

    private BetterStructuresHook(
            SFBetterStructuresLink plugin,
            LootService lootService,
            Method getContainer,
            Method getTreasureConfigFilename) {
        this.plugin = plugin;
        this.lootService = lootService;
        this.getContainer = getContainer;
        this.getTreasureConfigFilename = getTreasureConfigFilename;
    }

    static BetterStructuresHook register(SFBetterStructuresLink plugin, LootService lootService)
            throws ReflectiveOperationException {
        Plugin betterStructures = plugin.getServer().getPluginManager().getPlugin("BetterStructures");
        if (betterStructures == null || !betterStructures.isEnabled()) {
            throw new IllegalStateException("BetterStructures is not enabled");
        }

        ClassLoader classLoader = betterStructures.getClass().getClassLoader();
        Class<?> rawEventClass = Class.forName(CHEST_FILL_EVENT, false, classLoader);

        if (!Event.class.isAssignableFrom(rawEventClass)) {
            throw new IllegalStateException(CHEST_FILL_EVENT + " is not a Bukkit event");
        }

        @SuppressWarnings("unchecked")
        Class<? extends Event> eventClass = (Class<? extends Event>) rawEventClass;

        Method getContainer = rawEventClass.getMethod("getContainer");
        Method getTreasureConfigFilename = rawEventClass.getMethod("getTreasureConfigFilename");

        BetterStructuresHook hook =
                new BetterStructuresHook(plugin, lootService, getContainer, getTreasureConfigFilename);

        plugin.getServer()
                .getPluginManager()
                .registerEvent(
                        eventClass,
                        hook,
                        EventPriority.NORMAL,
                        (listener, event) -> hook.onChestFill(event),
                        plugin,
                        true);

        return hook;
    }

    private void onChestFill(Event event) {
        try {
            Object containerObject = getContainer.invoke(event);
            if (!(containerObject instanceof Container container)) {
                return;
            }

            Object filenameObject = getTreasureConfigFilename.invoke(event);
            String treasureConfigFilename =
                    filenameObject instanceof String filename ? filename : null;

            lootService.inject(container, treasureConfigFilename);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            plugin.getLogger().warning(
                    "Could not process BetterStructures chest fill event: " + exception.getMessage());
        }
    }
}
