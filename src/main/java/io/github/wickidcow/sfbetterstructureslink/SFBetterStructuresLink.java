package io.github.wickidcow.sfbetterstructureslink;

import java.util.Objects;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class SFBetterStructuresLink extends JavaPlugin implements Listener {

    private ItemProviderRegistry itemProviders;
    private LootService lootService;
    private BetterStructuresHook betterStructuresHook;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        itemProviders = new ItemProviderRegistry(this);
        itemProviders.refresh();

        lootService = new LootService(this, itemProviders);
        lootService.reload();

        try {
            betterStructuresHook = BetterStructuresHook.register(this, lootService);
        } catch (ReflectiveOperationException | IllegalStateException exception) {
            getLogger().severe("Could not hook BetterStructures ChestFillEvent: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        LinkCommand command = new LinkCommand(this);
        PluginCommand pluginCommand = Objects.requireNonNull(
                getCommand("sfbsl"), "Command 'sfbsl' is missing from plugin.yml");
        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);

        getServer().getPluginManager().registerEvents(this, this);

        logProviderState();
        getLogger().info("Hooked BetterStructures ChestFillEvent. Multi-provider loot injection is ready.");
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        itemProviders.refresh();
        lootService.reload();
        logProviderState();
        logValidation(lootService.validateConfiguredItems());
    }

    public LootService.ValidationReport reloadBridge() {
        reloadConfig();
        itemProviders.refresh();
        lootService.reload();

        LootService.ValidationReport report = lootService.validateConfiguredItems();
        logProviderState();
        logValidation(report);
        return report;
    }

    public LootService getLootService() {
        return lootService;
    }

    ItemProviderRegistry getItemProviders() {
        return itemProviders;
    }

    public boolean isBetterStructuresHookActive() {
        return betterStructuresHook != null;
    }

    private void logProviderState() {
        for (ItemProviderRegistry.ProviderState state : itemProviders.states()) {
            String status = state.available()
                    ? "active"
                    : state.configuredEnabled() ? "unavailable" : "disabled";
            getLogger().info("Item provider " + state.id() + ": " + status + " (" + state.detail() + ")");
        }
    }

    private void logValidation(LootService.ValidationReport report) {
        getLogger().info("Configured external loot entries: " + report.valid() + " valid, "
                + report.disabled() + " disabled, "
                + report.missing() + " missing, "
                + report.unavailable() + " waiting on unavailable providers, "
                + report.errors() + " errors.");

        if (!report.missingEntries().isEmpty()) {
            getLogger().warning("Missing item entries will be skipped: "
                    + String.join(", ", report.missingEntries()));
        }
        if (!report.errorEntries().isEmpty()) {
            getLogger().warning("Item-provider errors: "
                    + String.join(", ", report.errorEntries()));
        }
    }
}
