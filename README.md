# MaoMap

A custom, lightweight 2D Minecraft world map for Paper servers.

## Goals

- Render explored Minecraft chunks into a custom 2D atlas-style map.
- Update only chunks that have changed.
- Never force-load chunks for map rendering.
- Keep Bukkit/Paper world access on the server thread; pass immutable snapshots to background workers.
- Use bounded render queues and configurable per-tick capture budgets to protect TPS.
- Expose player positions and configurable map markers through a small web client.

## Project status

Early development. The initial milestone is the Paper plugin foundation and safe chunk snapshot pipeline. No production deployment is intended yet.

## Planned modules

- `plugin`: Paper plugin lifecycle, chunk capture, change tracking, queue limits.
- `renderer`: deterministic 2D tile rendering from immutable snapshots.
- `web`: map viewer, pan/zoom, worlds, player and marker overlays.

## Build

The project will use Gradle and Java. The exact Paper API version will be pinned in the build configuration once the target server version is confirmed.
