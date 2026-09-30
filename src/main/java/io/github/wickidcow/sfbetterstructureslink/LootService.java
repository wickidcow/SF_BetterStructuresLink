package io.github.wickidcow.sfbetterstructureslink;

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
    private final ItemProviderRegistry providers;

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

    LootService(SFBetterStructuresLink plugin, ItemProviderRegistry providers) {
        this.plugin = plugin;
        this.providers = providers;
    }

    public void reload() {
        FileConfiguration config = plugin.getConfig();

        enabled = config.getBoolean("loot.enabled", true);
        debug = config.getBoolean("loot.debug", false);
        defaultChancePercent = clampChance(config.getDouble("loot.chance-percent", 1.0));
        defaultRolls = Math.max(1, config.getInt("loot.rolls", 1));
        defaultMaxItems = Math.max(1, config.getInt("loot.max-items-per-container", 1));
        defaultPool = config.getString("loot.default-pool", "mixed");

        allowedWorlds = normalizeWorlds(config.getStringList("loot.worlds.allow"));
        deniedWorlds = normalizeWorlds(config.getStringList("loot.worlds.deny"));

        pools = Collections.unmodifiableMap(loadPools(config.getConfigurationSection("pools")));
        tableRules = List.copyOf(loadTableRules(config.getMapList("treasure-tables")));

        if (defaultPool == null || !pools.containsKey(defaultPool)) {
            plugin.getLogger().warning("Default loot pool '" + defaultPool
                    + "' does not exist. Custom structure loot will be skipped until it is fixed.");
        }

        if (pools.isEmpty()) {
            plugin.getLogger().warning("No external-item loot pools are configured.");
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
        if (pool == null || !pool.hasAvailableEntries(providers)) {
            debug("Skipped container because pool '" + rule.pool()
                    + "' has no entries from currently available providers.");
            return;
        }

        eligibleContainers++;

        if (ThreadLocalRandom.current().nextDouble(100.0) >= rule.chancePercent()) {
            return;
        }

        Inventory inventory = container.getSnapshotInventory();
        List<Integer> freeSlots = findFreeSlots(inventory);
        if (freeSlots.isEmpty()) {
            debug("Custom loot roll succeeded, but the generated container had no free slots.");
            return;
        }

        triggeredContainers++;

        int stackLimit = Math.min(rule.rolls(), rule.maxItems());
        for (int roll = 0; roll < stackLimit && !freeSlots.isEmpty(); roll++) {
            boolean inserted = tryInsertOne(pool, inventory, freeSlots, treasureConfigFilename, world);
            if (!inserted) {
                debug("Could not resolve a valid registered item for this loot roll.");
            }
        }
    }

    private boolean tryInsertOne(
            LootPool pool,
            Inventory inventory,
            List<Integer> freeSlots,
            String treasureConfigFilename,
            World world) {
        for (int attempt = 0; attempt < 6; attempt++) {
            LootEntry entry = pool.pick(providers);
            if (entry == null) {
                return false;
            }

            ItemProviderRegistry.Resolution resolution =
                    providers.resolve(entry.provider(), entry.id());
            if (resolution.status() != ItemProviderRegistry.ResolutionStatus.AVAILABLE
                    || resolution.stack() == null) {
                debug("Skipped " + entry.displayId() + ": " + resolution.detail());
                continue;
            }

            ItemStack stack = resolution.stack();
            int requestedAmount = ThreadLocalRandom.current().nextInt(
                    entry.minAmount(), entry.maxAmount() + 1);
            stack.setAmount(Math.max(1, Math.min(requestedAmount, stack.getMaxStackSize())));

            int slotIndex = ThreadLocalRandom.current().nextInt(freeSlots.size());
            int slot = freeSlots.remove(slotIndex);
            inventory.setItem(slot, stack);
            injectedStacks++;

            debug("Added " + stack.getAmount() + "x " + entry.displayId()
                    + " to BetterStructures treasure table "
                    + (treasureConfigFilename == null ? "<unknown>" : treasureConfigFilename)
                    + " in world " + world.getName());
            return true;
        }

        return false;
    }

    public ValidationReport validateConfiguredItems() {
        Set<String> seen = new LinkedHashSet<>();
        int valid = 0;
        int missing = 0;
        int disabled = 0;
        int unavailable = 0;
        int errors = 0;

        List<String> missingEntries = new ArrayList<>();
        List<String> disabledEntries = new ArrayList<>();
        List<String> unavailableEntries = new ArrayList<>();
        List<String> errorEntries = new ArrayList<>();

        for (LootPool pool : pools.values()) {
            for (LootTier tier : pool.tiers()) {
                for (LootEntry entry : tier.entries()) {
                    if (!seen.add(entry.displayId())) {
                        continue;
                    }

                    ItemProviderRegistry.Resolution resolution =
                            providers.resolve(entry.provider(), entry.id());

                    switch (resolution.status()) {
                        case AVAILABLE -> valid++;
                        case MISSING -> {
                            missing++;
                            missingEntries.add(entry.displayId());
                        }
                        case DISABLED -> {
                            disabled++;
                            disabledEntries.add(entry.displayId());
                        }
                        case PROVIDER_UNAVAILABLE -> {
                            unavailable++;
                            unavailableEntries.add(entry.displayId());
                        }
                        case ERROR -> {
                            errors++;
                            errorEntries.add(entry.displayId());
                        }
                    }
                }
            }
        }

        return new ValidationReport(
                seen.size(),
                valid,
                missing,
                disabled,
                unavailable,
                errors,
                List.copyOf(missingEntries),
                List.copyOf(disabledEntries),
                List.copyOf(unavailableEntries),
                List.copyOf(errorEntries));
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

            List<LootTier> tiers = loadTieredPool(poolName, poolSection);
            if (tiers.isEmpty()) {
                tiers = loadLegacyFlatPool(poolName, poolSection);
            }

            double totalWeight = tiers.stream().mapToDouble(LootTier::weight).sum();
            loaded.put(poolName, new LootPool(poolName, List.copyOf(tiers), totalWeight));
        }

        return loaded;
    }

    private List<LootTier> loadTieredPool(String poolName, ConfigurationSection poolSection) {
        ConfigurationSection tierRoot = poolSection.getConfigurationSection("tiers");
        if (tierRoot == null) {
            tierRoot = poolSection;
        }

        List<LootTier> tiers = new ArrayList<>();
        for (String tierName : tierRoot.getKeys(false)) {
            if ("tiers".equalsIgnoreCase(tierName)) {
                continue;
            }

            ConfigurationSection tierSection = tierRoot.getConfigurationSection(tierName);
            if (tierSection == null || !tierSection.isList("items")) {
                continue;
            }

            double tierWeight = tierSection.getDouble("weight", 1.0);
            if (!Double.isFinite(tierWeight) || tierWeight <= 0.0) {
                plugin.getLogger().warning("Ignoring tier " + poolName + "." + tierName
                        + " because its weight is not positive.");
                continue;
            }

            List<LootEntry> entries = new ArrayList<>();
            double totalItemWeight = 0.0;

            for (Map<?, ?> itemMap : tierSection.getMapList("items")) {
                LootEntry entry = parseLootEntry(poolName, tierName, itemMap);
                if (entry == null) {
                    continue;
                }
                entries.add(entry);
                totalItemWeight += entry.weight();
            }

            if (!entries.isEmpty() && totalItemWeight > 0.0) {
                tiers.add(new LootTier(
                        tierName,
                        tierWeight,
                        List.copyOf(entries),
                        totalItemWeight));
            }
        }

        return tiers;
    }

    private LootEntry parseLootEntry(String poolName, String tierName, Map<?, ?> itemMap) {
        String provider = stringValue(itemMap.get("provider"));
        String id = stringValue(itemMap.get("id"));

        String slimefunItem = stringValue(itemMap.get("slimefunItem"));
        String rebarItem = stringValue(itemMap.get("rebarItem"));
        String pylonItem = stringValue(itemMap.get("pylonItem"));

        if (slimefunItem != null && !slimefunItem.isBlank()) {
            provider = "slimefun";
            id = slimefunItem;
        } else if (pylonItem != null && !pylonItem.isBlank()) {
            provider = "pylon";
            id = pylonItem;
        } else if (rebarItem != null && !rebarItem.isBlank()) {
            provider = "rebar";
            id = rebarItem;
        }

        if (provider == null || provider.isBlank() || id == null || id.isBlank()) {
            plugin.getLogger().warning("Ignoring item without provider/id in "
                    + poolName + "." + tierName);
            return null;
        }

        Double weightValue = doubleValue(itemMap.get("weight"));
        double itemWeight = weightValue == null ? 1.0 : weightValue;
        if (!Double.isFinite(itemWeight) || itemWeight <= 0.0) {
            plugin.getLogger().warning("Ignoring " + provider + ":" + id + " in "
                    + poolName + "." + tierName + " because its weight is not positive.");
            return null;
        }

        AmountRange amount = parseAmount(itemMap.get("amount"), 1, 1);
        return new LootEntry(
                provider.trim().toLowerCase(Locale.ROOT),
                id.trim(),
                itemWeight,
                amount.min(),
                amount.max());
    }

    private List<LootTier> loadLegacyFlatPool(String poolName, ConfigurationSection poolSection) {
        List<LootEntry> entries = new ArrayList<>();
        double totalWeight = 0.0;

        for (String itemId : poolSection.getKeys(false)) {
            ConfigurationSection itemSection = poolSection.getConfigurationSection(itemId);
            if (itemSection == null) {
                continue;
            }

            double weight = itemSection.getDouble("weight", 1.0);
            int min = Math.max(1, itemSection.getInt("min", 1));
            int max = Math.max(min, itemSection.getInt("max", min));

            if (!Double.isFinite(weight) || weight <= 0.0) {
                plugin.getLogger().warning("Ignoring " + itemId + " in pool " + poolName
                        + " because its weight is not positive.");
                continue;
            }

            entries.add(new LootEntry("slimefun", itemId, weight, min, max));
            totalWeight += weight;
        }

        if (entries.isEmpty()) {
            return List.of();
        }

        return List.of(new LootTier("default", 1.0, List.copyOf(entries), totalWeight));
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

    private static AmountRange parseAmount(Object value, int fallbackMin, int fallbackMax) {
        if (value instanceof Number number) {
            int amount = Math.max(1, number.intValue());
            return new AmountRange(amount, amount);
        }

        if (value == null) {
            return new AmountRange(fallbackMin, fallbackMax);
        }

        String text = String.valueOf(value).trim();
        try {
            int separator = text.indexOf('-');
            if (separator > 0) {
                int min = Math.max(1, Integer.parseInt(text.substring(0, separator).trim()));
                int max = Math.max(min, Integer.parseInt(text.substring(separator + 1).trim()));
                return new AmountRange(min, max);
            }

            int amount = Math.max(1, Integer.parseInt(text));
            return new AmountRange(amount, amount);
        } catch (NumberFormatException ignored) {
            return new AmountRange(fallbackMin, fallbackMax);
        }
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

    private record AmountRange(int min, int max) {}

    private record LootEntry(
            String provider,
            String id,
            double weight,
            int minAmount,
            int maxAmount) {

        String displayId() {
            return provider + ":" + id;
        }
    }

    private record LootTier(
            String name,
            double weight,
            List<LootEntry> entries,
            double totalItemWeight) {

        boolean hasAvailableEntries(ItemProviderRegistry providers) {
            return entries.stream().anyMatch(entry -> providers.isAvailable(entry.provider()));
        }

        LootEntry pick(ItemProviderRegistry providers) {
            double effectiveTotal = entries.stream()
                    .filter(entry -> providers.isAvailable(entry.provider()))
                    .mapToDouble(LootEntry::weight)
                    .sum();

            if (effectiveTotal <= 0.0) {
                return null;
            }

            double roll = ThreadLocalRandom.current().nextDouble(effectiveTotal);
            double cumulative = 0.0;

            for (LootEntry entry : entries) {
                if (!providers.isAvailable(entry.provider())) {
                    continue;
                }
                cumulative += entry.weight();
                if (roll < cumulative) {
                    return entry;
                }
            }

            return null;
        }
    }

    private record LootPool(String name, List<LootTier> tiers, double totalWeight) {

        boolean hasAvailableEntries(ItemProviderRegistry providers) {
            return tiers.stream().anyMatch(tier -> tier.hasAvailableEntries(providers));
        }

        LootEntry pick(ItemProviderRegistry providers) {
            double effectiveTierWeight = tiers.stream()
                    .filter(tier -> tier.hasAvailableEntries(providers))
                    .mapToDouble(LootTier::weight)
                    .sum();

            if (effectiveTierWeight <= 0.0) {
                return null;
            }

            double roll = ThreadLocalRandom.current().nextDouble(effectiveTierWeight);
            double cumulative = 0.0;

            for (LootTier tier : tiers) {
                if (!tier.hasAvailableEntries(providers)) {
                    continue;
                }
                cumulative += tier.weight();
                if (roll < cumulative) {
                    return tier.pick(providers);
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
            int unavailable,
            int errors,
            List<String> missingEntries,
            List<String> disabledEntries,
            List<String> unavailableEntries,
            List<String> errorEntries) {}

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
