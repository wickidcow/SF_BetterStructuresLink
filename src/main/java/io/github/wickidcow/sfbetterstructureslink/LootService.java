package io.github.wickidcow.sfbetterstructureslink;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class LootService {

    private final SFBetterStructuresLink plugin;

    private boolean enabled;
    private boolean debug;
    private double defaultChancePercent;
    private int defaultRolls;
    private int defaultMaxItems;
    private String defaultPool;
    private Set<String> allowedWorlds = Set.of();
    private Set<String> deniedWorlds = Set.of();
    private Map<String, LootPool> pools = Map.of();
    private List<TableRule> tableRules = List.of();

    private long eligibleContainers;
    private long triggeredContainers;
    private long injectedStacks;

    LootService(SFBetterStructuresLink plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        FileConfiguration config = plugin.getConfig();

        enabled = config.getBoolean("loot.enabled", true);
        debug = config.getBoolean("loot.debug", false);
        defaultChancePercent = clampChance(config.getDouble("loot.chance-percent", 1.0));
        defaultRolls = Math.max(1, config.getInt("loot.rolls", 1));
        defaultMaxItems = Math.max(1, config.getInt("loot.max-items-per-container", 1));
        defaultPool = config.getString("loot.default-pool", "standard");

        allowedWorlds = normalizeWorlds(config.getStringList("loot.worlds.allow"));
        deniedWorlds = normalizeWorlds(config.getStringList("loot.worlds.deny"));

        pools = Collections.unmodifiableMap(loadPools(config.getConfigurationSection("pools")));
        tableRules = List.copyOf(loadTableRules(config.getMapList("treasure-tables")));

        if (defaultPool == null || !pools.containsKey(defaultPool)) {
            plugin.getLogger().warning("Default loot pool '" + defaultPool
                    + "' does not exist. Slimefun loot injection will be skipped until it is fixed.");
        }

        if (pools.isEmpty()) {
            plugin.getLogger().warning("No Slimefun loot pools are configured.");
        }
    }

    public void inject(Container container, String treasureConfigFilename) {
        if (!enabled || container == null) {
            return;
        }

        World world = container.getWorld();
        if (!isWorldAllowed(world.getName())) {
            return;
        }

        EffectiveRule rule = resolveRule(treasureConfigFilename);
        if (!rule.enabled()) {
            return;
        }

        LootPool pool = pools.get(rule.pool());
        if (pool == null || pool.entries().isEmpty() || pool.totalWeight() <= 0) {
            debug("Skipped container because pool '" + rule.pool() + "' is unavailable.");
            return;
        }

        eligibleContainers++;

        if (ThreadLocalRandom.current().nextDouble(100.0) >= rule.chancePercent()) {
            return;
        }

        Inventory inventory = container.getSnapshotInventory();
        List<Integer> freeSlots = findFreeSlots(inventory);
        if (freeSlots.isEmpty()) {
            debug("Slimefun loot roll succeeded, but the generated container had no free slots.");
            return;
        }

        triggeredContainers++;

        int stackLimit = Math.min(rule.rolls(), rule.maxItems());
        int inserted = 0;

        for (int roll = 0; roll < stackLimit && !freeSlots.isEmpty(); roll++) {
            LootEntry entry = pool.pick();
            if (entry == null) {
                break;
            }

            SlimefunItem slimefunItem = SlimefunItem.getById(entry.id());
            if (slimefunItem == null || slimefunItem.isDisabled()) {
                debug("Skipped unavailable Slimefun item ID " + entry.id());
                continue;
            }

            ItemStack stack = slimefunItem.getItem().clone();
            int requestedAmount = ThreadLocalRandom.current().nextInt(
                    entry.minAmount(), entry.maxAmount() + 1);
            stack.setAmount(Math.max(1, Math.min(requestedAmount, stack.getMaxStackSize())));

            int slotIndex = ThreadLocalRandom.current().nextInt(freeSlots.size());
            int slot = freeSlots.remove(slotIndex);
            inventory.setItem(slot, stack);

            inserted++;
            injectedStacks++;

            debug("Added " + stack.getAmount() + "x " + entry.id()
                    + " to BetterStructures treasure table "
                    + (treasureConfigFilename == null ? "<unknown>" : treasureConfigFilename)
                    + " in world " + world.getName());
        }
    }

    public ValidationReport validateConfiguredItems() {
        Set<String> ids = new LinkedHashSet<>();
        for (LootPool pool : pools.values()) {
            for (LootEntry entry : pool.entries()) {
                ids.add(entry.id());
            }
        }

        int valid = 0;
        int disabled = 0;
        List<String> missingIds = new ArrayList<>();
        List<String> disabledIds = new ArrayList<>();

        for (String id : ids) {
            SlimefunItem item = SlimefunItem.getById(id);
            if (item == null) {
                missingIds.add(id);
            } else if (item.isDisabled()) {
                disabled++;
                disabledIds.add(id);
            } else {
                valid++;
            }
        }

        return new ValidationReport(
                ids.size(),
                valid,
                missingIds.size(),
                disabled,
                List.copyOf(missingIds),
                List.copyOf(disabledIds));
    }

    public Status status() {
        return new Status(
                enabled,
                pools.size(),
                tableRules.size(),
                defaultChancePercent,
                defaultPool,
                eligibleContainers,
                triggeredContainers,
                injectedStacks);
    }

    private Map<String, LootPool> loadPools(ConfigurationSection poolsSection) {
        Map<String, LootPool> loaded = new LinkedHashMap<>();
        if (poolsSection == null) {
            return loaded;
        }

        for (String poolName : poolsSection.getKeys(false)) {
            ConfigurationSection poolSection = poolsSection.getConfigurationSection(poolName);
            if (poolSection == null) {
                continue;
            }

            List<LootEntry> entries = new ArrayList<>();
            int totalWeight = 0;

            for (String itemId : poolSection.getKeys(false)) {
                ConfigurationSection itemSection = poolSection.getConfigurationSection(itemId);
                if (itemSection == null) {
                    continue;
                }

                int weight = itemSection.getInt("weight", 1);
                int min = Math.max(1, itemSection.getInt("min", 1));
                int max = Math.max(min, itemSection.getInt("max", min));

                if (weight <= 0) {
                    plugin.getLogger().warning("Ignoring " + itemId + " in pool " + poolName
                            + " because its weight is not positive.");
                    continue;
                }

                entries.add(new LootEntry(itemId, weight, min, max));
                totalWeight += weight;
            }

            loaded.put(poolName, new LootPool(poolName, List.copyOf(entries), totalWeight));
        }

        return loaded;
    }

    private List<TableRule> loadTableRules(List<Map<?, ?>> maps) {
        List<TableRule> loaded = new ArrayList<>();

        for (Map<?, ?> map : maps) {
            String match = stringValue(map.get("match"));
            if (match == null || match.isBlank()) {
                plugin.getLogger().warning("Ignoring treasure-table rule without a match value.");
                continue;
            }

            boolean ruleEnabled = booleanValue(map.get("enabled"), true);
            Double chance = doubleValue(map.get("chance-percent"));
            Integer rolls = intValue(map.get("rolls"));
            Integer maxItems = intValue(map.get("max-items-per-container"));
            String pool = stringValue(map.get("pool"));

            loaded.add(new TableRule(
                    match,
                    compileGlob(match),
                    ruleEnabled,
                    chance == null ? null : clampChance(chance),
                    rolls == null ? null : Math.max(1, rolls),
                    maxItems == null ? null : Math.max(1, maxItems),
                    pool));
        }

        return loaded;
    }

    private EffectiveRule resolveRule(String treasureConfigFilename) {
        boolean ruleEnabled = true;
        double chance = defaultChancePercent;
        int rolls = defaultRolls;
        int maxItems = defaultMaxItems;
        String pool = defaultPool;

        if (treasureConfigFilename != null) {
            for (TableRule tableRule : tableRules) {
                if (!tableRule.pattern().matcher(treasureConfigFilename).matches()) {
                    continue;
                }

                ruleEnabled = tableRule.enabled();
                if (tableRule.chancePercent() != null) {
                    chance = tableRule.chancePercent();
                }
                if (tableRule.rolls() != null) {
                    rolls = tableRule.rolls();
                }
                if (tableRule.maxItems() != null) {
                    maxItems = tableRule.maxItems();
                }
                if (tableRule.pool() != null && !tableRule.pool().isBlank()) {
                    pool = tableRule.pool();
                }
                break;
            }
        }

        return new EffectiveRule(ruleEnabled, chance, rolls, maxItems, pool);
    }

    private boolean isWorldAllowed(String worldName) {
        String normalized = worldName.toLowerCase(Locale.ROOT);
        if (deniedWorlds.contains(normalized)) {
            return false;
        }
        return allowedWorlds.isEmpty() || allowedWorlds.contains(normalized);
    }

    private static Set<String> normalizeWorlds(List<String> worlds) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String world : worlds) {
            if (world != null && !world.isBlank()) {
                normalized.add(world.toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(normalized);
    }

    private static List<Integer> findFreeSlots(Inventory inventory) {
        List<Integer> freeSlots = new ArrayList<>();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (existing == null || existing.getType().isAir()) {
                freeSlots.add(slot);
            }
        }
        return freeSlots;
    }

    private static double clampChance(double chance) {
        return Math.max(0.0, Math.min(100.0, chance));
    }

    private static Pattern compileGlob(String glob) {
        StringBuilder regex = new StringBuilder("(?i)^");
        String[] parts = glob.split("\\*", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(parts[i]));
        }
        regex.append('$');
        return Pattern.compile(regex.toString());
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private static Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void debug(String message) {
        if (debug) {
            plugin.getLogger().info("[debug] " + message);
        }
    }

    private record LootEntry(String id, int weight, int minAmount, int maxAmount) {}

    private record LootPool(String name, List<LootEntry> entries, int totalWeight) {
        LootEntry pick() {
            if (entries.isEmpty() || totalWeight <= 0) {
                return null;
            }

            int roll = ThreadLocalRandom.current().nextInt(totalWeight);
            int cumulative = 0;
            for (LootEntry entry : entries) {
                cumulative += entry.weight();
                if (roll < cumulative) {
                    return entry;
                }
            }
            return null;
        }
    }

    private record TableRule(
            String match,
            Pattern pattern,
            boolean enabled,
            Double chancePercent,
            Integer rolls,
            Integer maxItems,
            String pool) {}

    private record EffectiveRule(
            boolean enabled,
            double chancePercent,
            int rolls,
            int maxItems,
            String pool) {}

    public record ValidationReport(
            int total,
            int valid,
            int missing,
            int disabled,
            List<String> missingIds,
            List<String> disabledIds) {}

    public record Status(
            boolean enabled,
            int pools,
            int tableRules,
            double defaultChancePercent,
            String defaultPool,
            long eligibleContainers,
            long triggeredContainers,
            long injectedStacks) {}
}
