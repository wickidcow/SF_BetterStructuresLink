package io.github.wickidcow.sfbetterstructureslink;

import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class LinkCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("status", "reload", "validate");

    private final SFBetterStructuresLink plugin;

    LinkCommand(SFBetterStructuresLink plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args) {
        if (!sender.hasPermission("sfbetterstructureslink.admin")) {
            sender.sendMessage("§cYou do not have permission to use this command.");
            return true;
        }

        String subcommand = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);

        switch (subcommand) {
            case "reload" -> {
                LootService.ValidationReport report = plugin.reloadBridge();
                sender.sendMessage("§aSF BetterStructures Link reloaded.");
                sendProviders(sender);
                sendValidation(sender, report);
            }
            case "validate" -> {
                sendProviders(sender);
                sendValidation(sender, plugin.getLootService().validateConfiguredItems());
            }
            case "status" -> sendStatus(sender);
            default -> sender.sendMessage("§eUsage: /" + label + " <status|reload|validate>");
        }

        return true;
    }

    private void sendStatus(CommandSender sender) {
        LootService.Status status = plugin.getLootService().status();

        sender.sendMessage("§6SF BetterStructures Link");
        sender.sendMessage("§7BetterStructures hook: "
                + (plugin.isBetterStructuresHookActive() ? "§aactive" : "§cinactive"));
        sender.sendMessage("§7Loot injection: " + (status.enabled() ? "§aenabled" : "§cdisabled"));
        sender.sendMessage("§7Default chance: §f" + status.defaultChancePercent() + "%");
        sender.sendMessage("§7Default pool: §f" + status.defaultPool());
        sender.sendMessage("§7Pools / table rules: §f" + status.pools() + " / " + status.tableRules());
        sender.sendMessage("§7Eligible / triggered / injected stacks: §f"
                + status.eligibleContainers() + " / "
                + status.triggeredContainers() + " / "
                + status.injectedStacks());
        sendProviders(sender);
    }

    private void sendProviders(CommandSender sender) {
        sender.sendMessage("§7Item providers:");
        for (ItemProviderRegistry.ProviderState state : plugin.getItemProviders().states()) {
            String status = state.available()
                    ? "§aactive"
                    : state.configuredEnabled() ? "§eunavailable" : "§8disabled";
            sender.sendMessage("§8- §f" + state.id() + ": " + status + " §7(" + state.detail() + ")");
        }
    }

    private void sendValidation(CommandSender sender, LootService.ValidationReport report) {
        sender.sendMessage("§7Configured entries: §a" + report.valid() + " valid§7, §e"
                + report.disabled() + " disabled§7, §c" + report.missing() + " missing§7, §6"
                + report.unavailable() + " provider-unavailable§7, §c"
                + report.errors() + " errors§7.");

        if (!report.missingEntries().isEmpty()) {
            sender.sendMessage("§cMissing: " + String.join(", ", report.missingEntries()));
        }
        if (!report.disabledEntries().isEmpty()) {
            sender.sendMessage("§eDisabled: " + String.join(", ", report.disabledEntries()));
        }
        if (!report.errorEntries().isEmpty()) {
            sender.sendMessage("§cErrors: " + String.join(", ", report.errorEntries()));
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args) {
        if (args.length != 1) {
            return List.of();
        }

        String prefix = args[0].toLowerCase(Locale.ROOT);
        return SUBCOMMANDS.stream().filter(option -> option.startsWith(prefix)).toList();
    }
}
