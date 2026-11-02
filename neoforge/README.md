# Bolt for NeoForge / OKOCRAFT

Server mod for Minecraft26.2, NeoForge26.2.0.88 and Java25. The common Bolt ownership, access types, groups, trust and SQL schema are reused from upstream. NeoForge supplies native interaction, inventory and automation protection.

This edition includes the behavior of [BoltWorldGuard](https://github.com/pop4959/BoltWorldGuard), [BoltBeaconProtection](https://github.com/okocraft/BoltBeaconProtection) and [BoltCommandAliases](https://github.com/okocraft/BoltCommandAliases). WorldGuard integration uses the OKOCRAFT NeoForge port. GPL-3.0 licenses and original authorship apply to the reused code.

## Operation

Use /bolt help, /lock, /unlock, /caccess, /citemmove, /credstone and /cautoclose. Commands select an action, then a block/entity click applies it. Permissions use LuckPerms when installed, preserving explicit permission denials; normal command defaults follow the original plugin. Confirmation for protection removal follows the existing OKOCRAFT aliases. No permanent scoreboard, tab display or action-bar UI is installed.

Configuration and local SQLite data are under config/bolt. MySQL is also available through database configuration. New defaults follow OKOCRAFT's protection/access/source settings, with action-bar output disabled. Mod containers use namespaced registry identifiers and per-dimension identifiers. Each server keeps its own protection store unless an administrator explicitly configures otherwise.

Shared-password sources retain Bolt's stored representation; information commands conceal the password hash. Do not publish database or configuration files containing credentials.

WorldGuard adds the bolt-protections flag and region-member sources. Without an explicit flag, normal WorldGuard build rights control lock creation. /protectregion and /purgeregion process work over server ticks. Beacon effect confirmation can protect its base blocks for users with boltbeaconprotection.autoprotect, preserving existing owners.

## Build and test

Keep sibling checkouts WorldGuard-NeoForge and CoreProtectNeo alongside this repository, and build their jars first. WorldEdit7.4.5 is resolved from EngineHub's official Maven repository; the runner obtains the server distribution from its ordinary file URL. Then run:

    ./gradlew :bolt-neoforge:build :bolt-common:test
    node neoforge/src/test/node/server-smoke.mjs

The standalone server fixture binds only loopback25586, uses fresh data under neoforge/build/smoke, and runs real DQM1.06.25 with its matching voice assets, WorldGuard, WorldEdit, CoreProtect and LuckPerms. External jars come from ordinary official file URLs; DQM files are checked against the author's SHA-256. DQM code and assets are not included in this repository or release artifacts.

Unit tests cover permission unions, ownership, cache movement and SQLite restart/retry behavior. Dedicated tests exercise player actions, inventory permissions, hopper movement, DQM menus/tools, WorldGuard and beacon behavior, commands and server restart. The server fixture first runs without DQM as a native positive control, then runs the actual DQM pack and restarts it to verify stored locks. It also checks command permission changes and cancellation after a region is removed. A no-effect unprotected DQM control is a failed/inconclusive fixture, never evidence of protection.

## Compatibility

Other mods can supply storage or terrain changes outside the native paths covered here; compatibility must be established for their actual operations. This mod does not claim protection for every mod or every DQM action. Bukkit-only live LWC/Lockette migration stays in the original Bukkit module. Export/import of Bolt storage is supported, but Bukkit world names require explicit mapping before cross-platform migration to namespaced dimensions.
