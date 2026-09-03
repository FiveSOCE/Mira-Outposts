# MiraOutposts

MiraOutposts provides faction-controlled capture objectives for the Mira Paper server suite. Outposts are persistent world locations that factions can contest and capture to gain configurable server bonuses.

## Download

[**Download MiraOutposts v0.1.0**](https://github.com/FiveSOCE/Mira-Outposts/releases/download/v0.1.0/MiraOutposts-0.1.0.jar)

## Requirements / Dependencies

- Paper 1.21.11
- Java 21
- MiraFactions optional/recommended as the faction identity source
- MiraBoosters optional integration

## How MiraOutposts Works

Each outpost has a persistent ID, world location, capture radius, capture duration and reward multiplier channel. Players from a faction enter the capture radius and remain in control long enough to complete the capture. If competing factions are present, the objective becomes contested and capture progress is prevented or interrupted according to the outpost logic.

Ownership is persisted and successful captures are announced globally. An owned outpost can expose a generic multiplier such as `shop_sell`, `spawner_rate` or another ecosystem-defined channel. Other Mira plugins can consume these values through the public Bukkit ServicesManager API without duplicating capture logic.

## Commands

| Command | Permission | What it does |
| --- | --- | --- |
| `/outpost list` | `miraoutposts.use` | Lists configured outposts and their ownership/state. |
| `/outpost info <id>` | `miraoutposts.use` | Shows details for a specific outpost. |
| `/outpost create <id> <radius> <captureSeconds> <channel> <multiplier>` | `miraoutposts.admin` | Creates an outpost at the administrator's current location. |
| `/outpost remove <id>` | `miraoutposts.admin` | Removes an outpost definition. |

## Permissions

| Permission | Default | What it does |
| --- | --- | --- |
| `miraoutposts.use` | Everyone | Allows normal outpost listing and inspection. |
| `miraoutposts.admin` | OP | Allows creating and removing outposts. |
