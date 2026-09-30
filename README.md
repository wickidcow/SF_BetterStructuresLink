# SF BetterStructures Link

A lightweight bridge between [MagmaGuy's BetterStructures](https://github.com/MagmaGuy/BetterStructures) and Slimefun.

SF BetterStructures Link lets server owners add **real registered Slimefun items** to BetterStructures-generated loot containers at configurable, rare rates without maintaining a patched BetterStructures build.

## Goals

- Keep BetterStructures untouched and independently updatable.
- Inject Slimefun loot through BetterStructures' public chest-fill event.
- Resolve items from the live Slimefun registry by item ID.
- Support Slimefun core items and registered addon items.
- Never replace normal BetterStructures loot unless a future option explicitly allows it.
- Keep structure-generation overhead negligible.
- Fail safely when an optional Slimefun addon/item is unavailable.

## Compatibility

- Java 21
- Minecraft 1.21.11+
- Paper / Purpur
- BetterStructures
- Slimefun Legacy and compatible Slimefun API forks

Paper 26.x is a primary target while retaining the 1.21.11 compatibility floor.

## Example

```yaml
loot:
  enabled: true
  chance-percent: 1.0
  rolls: 1
  max-items-per-container: 1
  default-pool: standard

pools:
  standard:
    CARBON:
      weight: 100
      min: 1
      max: 4
    SYNTHETIC_DIAMOND:
      weight: 20
      min: 1
      max: 1
    REINFORCED_ALLOY_INGOT:
      weight: 5
      min: 1
      max: 1
```

A 1% container roll means BetterStructures remains the source of the chest and its normal treasure. This plugin only gets a chance to add one configured Slimefun item afterward.

## Why a bridge plugin?

BetterStructures exposes a `ChestFillEvent` before the generated container is committed. That is a clean integration point and avoids carrying a permanent custom BetterStructures fork just to maintain Slimefun loot support.

## Project status

Early development. Initial test builds are produced automatically by GitHub Actions.
