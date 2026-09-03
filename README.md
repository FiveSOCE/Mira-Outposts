# MiraOutposts

Faction-controlled capture objectives for Paper 1.21.11 / Java 21.

## Current release

**v0.1.0**

Direct download:
https://github.com/FiveSOCE/Mira-Outposts/releases/download/v0.1.0/MiraOutposts-0.1.0.jar

All releases:
https://github.com/FiveSOCE/Mira-Outposts/releases

## Features

- Persistent outpost definitions and faction ownership
- MiraFactions-backed faction identity
- Radius-based capture zones
- Configurable capture duration
- Contested capture handling
- Global capture announcements
- Generic held-outpost multiplier channels such as `shop_sell`, `spawner_rate` or other ecosystem bonuses
- Multipliers exposed through a public Bukkit ServicesManager API

## Commands

- `/outpost list`
- `/outpost info <id>`
- `/outpost create <id> <radius> <captureSeconds> <channel> <multiplier>`
- `/outpost remove <id>`

## Integration

MiraOutposts uses MiraFactions as the faction identity source when installed. Other Mira plugins can consume the public multiplier API without duplicating capture logic.

## Build

`./gradlew build`

Output: `build/libs/MiraOutposts-0.1.0.jar`
