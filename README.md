# MiraOutposts

MiraOutposts provides persistent faction-controlled capture objectives for the Mira Paper server suite. Factions contest world locations for configurable multiplier channels that other Mira systems can consume through a stable service API.

## Download

[**Download MiraOutposts v0.1.1**](https://github.com/FiveSOCE/Mira-Outposts/releases/download/v0.1.1/MiraOutposts-0.1.1.jar)

[View All Releases](https://github.com/FiveSOCE/Mira-Outposts/releases)

## Requirements / Dependencies

- Paper 1.21.11
- Java 21
- MiraCore 0.2.0 or newer
- MiraFactions 0.2.8 or newer
- MiraBoosters optional for combined player multiplier calculations

MiraFactions is now a real required dependency because faction identity is fundamental to capture ownership.

## Capture Rules

Each outpost stores:

- a persistent ID and world center
- capture radius
- required capture seconds
- reward channel
- reward multiplier
- current owning faction

Exactly one faction must control the radius for progress to advance. The owning faction cannot recapture its own objective. Empty/contested reset behavior is configurable.

Outposts cannot be created inside a SafeZone because SafeZone combat immunity would create an invalid capture objective. WarZones are allowed.

## Multiplier Authority

`OutpostsApi.multiplier(factionId, channel)` returns the persistent multiplier produced by every outpost that faction holds for that channel.

`OutpostsApi.playerMultiplier(playerId, channel)` is the ecosystem-facing combined path. It resolves the player's current MiraFactions faction multiplier and, when MiraBoosters is installed, multiplies that with MiraBoosters' existing global/personal channel multiplier. This keeps persistent faction ownership in MiraOutposts and timed global/personal boosters in MiraBoosters without abusing either system.

Effective stacking is finite and capped by `api.max-effective-multiplier`.

## Commands

| Command | Permission | What it does |
| --- | --- | --- |
| `/outpost list` | `miraoutposts.use` | Lists ownership, channels and active capture progress. |
| `/outpost info <id>` | `miraoutposts.use` | Shows full details for an outpost. |
| `/outpost create <id> <radius> <captureSeconds> <channel> <multiplier>` | `miraoutposts.admin` | Creates a validated outpost at the admin's position. |
| `/outpost remove <id>` | `miraoutposts.admin` | Removes an outpost. |

Duplicate/blank IDs, unsafe numeric values and SafeZone creation are rejected.

## API / Events

`OutpostsApi` is registered through Bukkit ServicesManager and MiraCore. It exposes:

- all outpost snapshots
- lookup by ID
- current capture progress
- faction multiplier by channel
- combined player multiplier with optional MiraBoosters
- outposts held by a faction

A typed `OutpostCapturedEvent` fires after ownership changes.

Create/remove/capture operations are written to MiraCore audit history.

## Configuration

`config.yml` controls:

- empty/contested capture reset behavior
- maximum creation radius/capture time/multiplier
- maximum effective stacked multiplier
- optional MiraBoosters combination in `playerMultiplier`

## Building

```bash
gradle clean build
```

The output JAR is created in `build/libs/`.
