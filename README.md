# SF BetterStructures Link

A lightweight bridge between [MagmaGuy's BetterStructures](https://github.com/MagmaGuy/BetterStructures) and Slimefun.

SF BetterStructures Link adds **real registered Slimefun items** to BetterStructures-generated loot containers at configurable rare rates without modifying or patching BetterStructures.

## Important: no Slimefun BetterStructures treasure file is required

Do **not** put `slimefunItem:` entries inside BetterStructures treasure YAML files.

BetterStructures parses its own treasure files and does not understand that key. SF BetterStructures Link now owns the Slimefun loot table completely:

1. BetterStructures generates a structure and fills a chest/barrel with its normal loot.
2. BetterStructures fires `ChestFillEvent`.
3. SF BetterStructures Link rolls its own independent rare chance.
4. If successful, it selects a Slimefun rarity tier and item.
5. The actual registered Slimefun `ItemStack` is cloned into a free slot.

This means `treasure_slimefun.yml` can be deleted.

## Default migrated loot

The default configuration contains the 23 Slimefun items previously used by the old `treasure_slimefun.yml` and preserves its two-stage rarity weighting:

- Common: 70
- Rare: 25
- Epic: 5

Item-specific weights and stack-size ranges are also preserved.

By default, the whole Slimefun table has a **1% chance per BetterStructures-generated loot container** and adds at most one Slimefun stack when the roll succeeds.

## Compatibility

- Java 21
- Minecraft 1.21.11+
- Paper / Purpur
- BetterStructures
- Slimefun Legacy and compatible Slimefun API forks

Paper 26.x is a primary target while retaining the 1.21.11 compatibility floor.

## Configuration

```yaml
loot:
  enabled: true
  chance-percent: 1.0
  rolls: 1
  max-items-per-container: 1
  default-pool: slimefun

pools:
  slimefun:
    common:
      weight: 70.0
      items:
        - slimefunItem: IRON_DUST
          amount: 1-4
          weight: 14.0
    rare:
      weight: 25.0
      items:
        - slimefunItem: STEEL_INGOT
          amount: 1-3
          weight: 16.0
    epic:
      weight: 5.0
      items:
        - slimefunItem: REINFORCED_ALLOY_INGOT
          amount: 1
          weight: 5.0

treasure-tables: []
```

The global default applies to every BetterStructures loot container. Optional `treasure-tables` rules can override the chance, rolls, cap, or pool for BetterStructures' existing filenames such as `treasure_end.yml`, `treasure_nether.yml`, or `treasure_overworld_underground.yml`.

## Commands

- `/sfbsl status`
- `/sfbsl reload`
- `/sfbsl validate`

## Goals

- Keep BetterStructures untouched and independently updatable.
- Resolve items from the live Slimefun registry by item ID.
- Support Slimefun core and registered addon items.
- Never replace normal BetterStructures loot.
- Keep structure-generation overhead negligible.
- Skip missing/disabled optional addon items safely.
