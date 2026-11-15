# MdcPickup

[![Version](https://img.shields.io/badge/version-1.1.0-blue)]()
[![Hytale](https://img.shields.io/badge/Hytale-0.7.7-green)]()
[![License](https://img.shields.io/badge/license-MIT-lightgrey)]()

A Hytale server mod that enables **pick-block in Adventure mode**: middle-click a block to select the matching item from your inventory, just like in Creative mode.

<!-- Screenshots: add your images below, e.g.
![Pick block in action](docs/images/pick-block.gif)
-->

<!-- ![Before / after](docs/images/before-after.png) -->

## Why this mod exists

In the shared game sources, the native `PickBlock` interaction is an unfinished TODO on the server side (`// TODO: Handle pick block?`): the chain starts, but nothing happens in Adventure mode. MdcPickup **completes that behaviour on the server**, following the patterns of the official inventory interactions (`AddItemInteraction`, `ModifyInventoryInteraction`, `InventoryUtils`).

No network packets are intercepted and **no client mod is required**.

## How it works

1. The mod's asset pack adds an interaction root `Nesios_MdcPickup_Pick` (mirroring the native `Pick` chain: Adventure/Creative cooldowns, `Simple` wrapper with animation, then `Next` to the mod's interaction).
2. At startup, the plugin registers its own interaction type `MdcPickup_PickBlock` via `Interaction.CODEC.register(...)`. This is valid because assets are parsed during the `LoadAssetEvent`, which is dispatched after every plugin's `setup()`.
3. A server ECS system checks every Adventure player and adds the missing binding to their `Interactions` component: `Pick` → `Nesios_MdcPickup_Pick`.
4. On middle-click, the client starts the chain (it sees the mod's interaction as the generic `SimpleBlockInteraction` protocol type, so any vanilla client can run it) and sends the targeted block position.
5. The server then runs `MdcPickupPickBlockInteraction`:
   - **item already in the hotbar** → that slot becomes the active slot (and the client's hotbar selector is updated through the native `SetActiveSlot` packet);
   - **otherwise** → the whole stack is moved from storage (then backpack) into an empty hotbar slot, or into the active slot when the hotbar is full (the displaced stack is put back into the inventory; it drops only if the whole inventory is full);
   - **item owned nowhere** → nothing happens.

All mutations go through the native ECS inventory components (`InventoryComponent.Hotbar/Storage/Backpack`, `setActiveSlot`, `addOrDropItemStack`), so client sync (inventory, equipment, active slot) is the game's own, with no extra code.

In Creative mode the mod stays out of the way (`SkipCreative`): vanilla pick-block keeps working as before.

## Compatibility

- Hytale **0.7.7** (`ServerVersion`: `>=0.7.7 <0.8.0`)
- Java **25**
- Server-side only
- No client mod needed

## Installation

1. Download the latest `MdcPickup-x.y.z.jar` from the [Releases](../../releases) page (or build it yourself, see below).
2. Copy the JAR into your Hytale server's `mods/` directory.
3. Restart the server.

## Build it yourself

Requirements:

- **Java 25** (check with `java -version`)
- Git

```bash
# Clone the repository
git clone https://github.com/N3siOS/MiddleClickPickup.git
cd MiddleClickPickup

# Build (the Gradle wrapper downloads Gradle automatically)
./gradlew build
```

The JAR is generated in `build/libs/MdcPickup-1.1.0.jar`.

On Windows, use `gradlew.bat build` instead of `./gradlew build`.

The `manifest.json` inside `src/main/resources/` is generated/updated automatically from `gradle.properties`, and the asset pack is embedded into the JAR thanks to `includes_pack = true`.

## Configuration

On first start, the following file is created:

```text
mods/Nesios_MdcPickup/mdc-pickup.json
```

Available options:

| Option | Default | Description |
| --- | --- | --- |
| `Enabled` | `true` | Enables or disables the `Pick` binding. |
| `SkipCreative` | `true` | In Creative mode, removes the binding added by the mod so vanilla behaviour is preserved. Existing bindings are never overwritten. |

Example:

```json
{
  "Enabled": true,
  "SkipCreative": true
}
```

## Technical notes

- `PickInteractionSystem` is an `EntityTickingSystem<EntityStore>` targeting players: it adds `InteractionType.Pick` → `Nesios_MdcPickup_Pick` to the player's `Interactions` component, never replacing a binding owned by another mod or game mode. A binding pointing to a root that no longer resolves server-side is considered stale and replaced (the server silently cancels every `Pick` chain whose root is unknown).
- `MdcPickupPickBlockInteraction` extends `SimpleBlockInteraction`, like every official block interaction: on the client it travels as the generic `SimpleBlockInteraction` protocol type, so any vanilla client can run the chain.
- The interaction type registry (`Interaction.CODEC`) is populated by the plugin in `setup()`; the server boot order (all `setup()` calls, then `LoadAssetEvent`) guarantees the child asset `{"Type": "MdcPickup_PickBlock"}` is parsed after registration.
- No `PlayerMouseButtonEvent` listener and no packet interception is used.

## Limitations

- Pick only works if the player owns the item (hotbar, storage or backpack): this is the intended behaviour in Adventure/survival.
- The client must receive the server's asset pack, which is the standard case for Hytale mods with `IncludesAssetPack: true`.
- If another mod or a custom game mode already binds `Pick` to another interaction, the mod does not replace that binding (unless it has become invalid).

## Troubleshooting

### `Skipping pack at Nesios_MdcPickup: missing or invalid manifest.json`

This message may appear from the second server start onwards. It is harmless: Hytale scans the `mods/` directory for asset packs and finds the mod's configuration directory (`mods/Nesios_MdcPickup/`), which contains no `manifest.json`. The mod's asset pack itself is embedded in the JAR and loaded by the server. No action is needed.

### The pick does nothing

1. Make sure the loaded JAR is `MdcPickup-1.1.0.jar` (an older `MdcPickup-1.0.0.jar` or `MiddleClickPick-1.0.0.jar` left in `mods/` can cause a duplicate or a stale binding).
2. Try with an empty hand — in vanilla, the `Pick` interaction is only available with an empty hand; the mod makes it available with any held item.
3. Try with the same block present in your inventory.

## AI-assisted development

> **Note:** this mod was created with the help of an AI coding assistant (implementation, debugging and documentation). The behaviour is based on Hytale's official shared server sources (0.7.7). Feel free to review the code — it is intentionally small and follows the game's official interaction and inventory patterns.

## License

[MIT](LICENSE)
