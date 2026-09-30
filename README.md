# SF BetterStructures Link

A lightweight multi-ecosystem loot bridge for [MagmaGuy's BetterStructures](https://github.com/MagmaGuy/BetterStructures).

The plugin can add **real registered items** from optional content systems to BetterStructures-generated chests and barrels without patching BetterStructures.

## Optional item providers

Only BetterStructures is a hard dependency.

At startup the bridge detects:

- **Slimefun** — resolves registered Slimefun item IDs.
- **Rebar** — resolves registered Rebar/Rebar-addon NamespacedKeys.
- **Pylon** — resolves `pylon:*` items through Rebar's live item registry.

If one provider is missing, its loot entries are automatically removed from the effective weighted roll. The remaining installed providers continue normally.

Pylon depends on Rebar, so Pylon item resolution intentionally uses Rebar's canonical registry rather than copying Pylon ItemStacks.

## No special BetterStructures treasure file

Do **not** create `treasure_slimefun.yml`, `treasure_pylon.yml`, or similar files.

BetterStructures generates its structure and normal treasure first. Its public `ChestFillEvent` then gives this plugin the generated container snapshot. The bridge independently performs its rare item roll and adds the real registered ItemStack to a free slot.

## Default loot

The migrated Slimefun table preserves the old:

- Common: 70
- Rare: 25
- Epic: 5

rarity split and the original item-specific amounts/weights.

The default configuration also contains a conservative set of Pylon resources and consumables. Powerful machines, creative sources, debug items and administrative items are intentionally not auto-discovered.

Rebar itself is primarily the framework rather than the content pack, so no Rebar core item is forced into the default pool. Any registered Rebar or Rebar-addon item can be added by NamespacedKey:

```yaml
- provider: rebar
  id: youraddon:your_item
  amount: 1
  weight: 1.0
```

## Configuration example

```yaml
providers:
  slimefun:
    enabled: true
  rebar:
    enabled: true
  pylon:
    enabled: true

loot:
  enabled: true
  chance-percent: 1.0
  rolls: 1
  max-items-per-container: 1
  default-pool: mixed

pools:
  mixed:
    common:
      weight: 70.0
      items:
        - provider: slimefun
          id: IRON_DUST
          amount: 1-4
          weight: 14.0
        - provider: pylon
          id: pylon:iron_dust
          amount: 1-4
          weight: 10.0
```

## Commands

- `/sfbsl status`
- `/sfbsl reload`
- `/sfbsl validate`

`status` shows which optional providers are active. `validate` checks every configured item against the currently installed registries.

## Compatibility

- Java 21 bytecode
- Minecraft 1.21.11+
- Paper / Purpur
- BetterStructures
- Optional Slimefun
- Optional Rebar
- Optional Pylon

The project keeps BetterStructures independent and avoids changing Slimefun/Rebar/Pylon persistence, block storage, machine data, or registries.
