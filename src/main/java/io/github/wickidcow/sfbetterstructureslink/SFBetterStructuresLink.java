package io.github.wickidcow.sfbetterstructureslink;

import java.util.Objects;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class SFBetterStructuresLink extends JavaPlugin implements Listener {

    private LootService lootService;
    private BetterStructuresHook betterStructuresHook;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        lootService = new LootService(this);
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

        getLogger().info("Hooked BetterStructures ChestFillEvent. Slimefun loot injection is ready.");
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        LootService.ValidationReport report = lootService.validateConfiguredItems();
        logValidation(report);
    }

    public LootService.ValidationReport reloadBridge() {
        reloadConfig();
        lootService.reload();
        LootService.ValidationReport report = lootService.validateConfiguredItems();
        logValidation(report);
        return report;
    }

    public LootService getLootService() {
        return lootService;
    }

    public boolean isBetterStructuresHookActive() {
        return betterStructuresHook != null;
    }

    private void logValidation(LootService.ValidationReport report) {
        getLogger().info("Configured Slimefun loot IDs: " + report.valid() + " valid, "
                + report.disabled() + " disabled, " + report.missing() + " missing.");

        if (!report.missingIds().isEmpty()) {
            getLogger().warning("Missing Slimefun IDs will be skipped: "
                    + String.join(", ", report.missingIds()));
        }
    }
}
