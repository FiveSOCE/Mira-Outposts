# MiraOutposts

MiraOutposts v0.2.1 is a FAWE-defined faction capture-event system for the Mira Paper server suite. Administrators create capture regions using a normal FastAsyncWorldEdit cuboid selection, configure the event through GUI menus, start it manually or on a repeating schedule, and players receive live boss-bar capture feedback while inside the active region.

## Download

[**Download MiraOutposts v0.2.1**](https://github.com/FiveSOCE/Mira-Outposts/releases/download/v0.2.1/MiraOutposts-0.2.1.jar)

[View All Releases](https://github.com/FiveSOCE/Mira-Outposts/releases)

## Requirements

- Paper 1.21.11
- Java 21
- MiraCore 0.2.0+
- MiraFactions 0.2.10+
- FastAsyncWorldEdit 2.15.3+
- MiraBoosters optional

FAWE replaces the old coordinate/radius creation workflow. WorldEdit itself should not be installed alongside FAWE; FAWE supplies the WorldEdit API and selection system.

## Creating an Outpost

1. Use FAWE to make a cuboid selection:
   - `//pos1`
   - `//pos2`
   - or use the normal FAWE wand.
2. Run `/outpost`.
3. Click **Create From FAWE Selection**.
4. Type the new outpost ID in chat.
5. The editor GUI opens immediately.
6. Configure capture time, channel, multiplier and schedule.
7. Click **Start** when ready.

MiraOutposts uses the selected X/Z rectangle as the capture zone and intentionally ignores Y. A player can therefore capture anywhere vertically inside that selected footprint.

Only cuboid/two-position selections are accepted. Oversized regions are rejected according to `creation.max-area-blocks` and `creation.max-side-length`.

Selections overlapping a MiraFactions SafeZone are rejected.

## GUI Administration

Running `/outpost` opens the main Outposts GUI.

The main GUI provides:

- Create From FAWE Selection
- every configured outpost
- Start All
- Stop All
- pagination

Click an outpost to open its editor.

The editor provides:

- **FAWE Region** - replace the region with your current FAWE selection
- **Capture Time** - chat-input capture seconds
- **Channel** - opens the channel picker
- **Multiplier** - chat-input multiplier
- **Start** - immediately begins a fresh run
- **Stop** - stops the active run
- **Schedule** - selects a repeating automatic start interval
- **Scheduled Run Length** - controls how long automatic runs remain active
- **Delete Outpost** - two-click confirmation

The old long-form create command is intentionally retired. Small `/outpost start <id>` and `/outpost stop <id>` admin fallbacks remain for console-like operational use, but normal administration is GUI-first.

## Channel Picker

The Channel GUI lists the current built-in examples and explains what each one is intended for.

| Channel ID | Purpose |
| --- | --- |
| `xp` | XP multiplier channel |
| `mob_drops` | Mob-drop reward multiplier |
| `shop_sell` | Shop/economy sell-value multiplier |
| `crate_chance` | Crate chance multiplier |
| `spawner_rate` | MiraSpawners production multiplier |

A **Custom Channel** option is also available. Custom IDs are valid, but they only do something if another plugin actually consumes that channel.

Example:

An outpost configured as:

- Channel: `spawner_rate`
- Multiplier: `1.25`

gives its owning faction a 1.25x active outpost multiplier on the `spawner_rate` channel while that outpost run is active.

## Starting and Stopping

Outposts are now explicit events.

### Start

Starting an outpost:

- marks it RUNNING
- clears the previous owner for the new contest
- clears old capture progress
- enables the live boss bar
- enables capture processing
- allows the eventual owner's configured multiplier to become active

### Stop

Stopping an outpost:

- marks it STOPPED
- clears current capture progress
- hides its boss bar
- disables its multiplier contribution
- remembers the last owner for administration/history context

The next Start begins unclaimed again.

## Scheduling

Each outpost may be scheduled to start automatically:

- every 30 minutes
- every 1 hour
- every 2 hours
- every 4 hours
- every 6 hours
- every 12 hours
- every 24 hours
- disabled/manual only

Automatic runs have their own run length. The default is 30 minutes and can be changed per outpost in the editor.

Example:

- Schedule: every 2 hours
- Scheduled Run Length: 30 minutes

The outpost automatically starts every 2 hours, remains active for 30 minutes, then stops. The next scheduled run starts as a fresh unclaimed contest.

Schedule timestamps are persisted as absolute values in `outposts.yml`, so normal restarts do not erase the schedule.

## Capture Rules

Only RUNNING outposts can be captured.

Exactly one faction must be represented inside the FAWE-defined region for capture progress to advance.

- no faction present: progress resets by default
- one non-owner faction present: capture progresses
- multiple factions present: CONTESTED and progress resets by default
- current owning faction present: CONTROLLED
- capture reaches the configured time: ownership changes

## Boss Bar

Players physically inside a RUNNING outpost receive a live boss bar. The boss bar intentionally stays compact and shows only the outpost ID plus capture state/progress; reward/channel text is omitted to avoid overly long boss-bar titles.

States include:

- **UNCLAIMED**
- **<Faction> CAPTURING 12/30s**
- **CONTESTED**
- **CONTROLLED BY <Faction>**

The bar also shows the outpost ID, channel and multiplier.

Progress is updated from the actual capture timer. Leaving the selected region removes the boss bar immediately on the next update; stopping the outpost removes it for everyone.

## Multipliers

`OutpostsApi.multiplier(factionId, channel)` returns only multipliers from currently RUNNING outposts owned by that faction.

`OutpostsApi.playerMultiplier(playerId, channel)` combines:

1. active faction-owned Outpost multipliers
2. optional MiraBoosters global/personal multiplier for the same channel

Persistent faction ownership stays in MiraOutposts. Temporary global/personal boosts stay in MiraBoosters.

## Existing Outpost Migration

v0.2.1 automatically reads old v0.1.x radius-based outposts.

An old center + radius is converted into an equivalent rectangular X/Z region during load. Saving after startup writes the new bounds format.

Old outposts default to STOPPED so administrators can review them in the new GUI before starting a run.

## Permissions

| Permission | Default | Purpose |
| --- | --- | --- |
| `miraoutposts.use` | Everyone | Opens and views the Outposts GUI. |
| `miraoutposts.admin` | OP | Create/edit/start/stop/schedule/delete outposts. |

## API

The public service remains available through Bukkit ServicesManager and MiraCore.

It exposes:

- all outpost snapshots
- lookup by ID
- capture progress
- RUNNING state
- faction multiplier by channel
- combined player multiplier
- outposts held by faction

`OutpostCapturedEvent` still fires after successful ownership changes.

Create/edit/start/stop/schedule/capture/delete actions are written to MiraCore audit history.

## Mira Chat Style

Player-facing outpost broadcasts use the shared Mira prefix:

`&5&lMira &8&l>> &r`

Capture messages use:

`&e&l<Faction> &7Has captured &a&l<Outpost> &7and has gained &c&l<Reward>&7.`

The old `[Outpost]` label is no longer used.

## Building

```bash
gradle clean build
```

The output JAR is created in `build/libs/`.
