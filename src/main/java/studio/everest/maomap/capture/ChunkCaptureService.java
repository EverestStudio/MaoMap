package studio.everest.maomap.capture;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import studio.everest.maomap.render.TileRenderer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Captures loaded chunks on the primary thread and renders immutable snapshots asynchronously. */
public final class ChunkCaptureService implements Listener {
    private final JavaPlugin plugin;
    private final TileRenderer renderer;
    private final Set<ChunkKey> pending = new LinkedHashSet<>();
    private final int maxQueue;
    private final int chunksPerTick;
    private final List<ChunkKey> startupScan = new ArrayList<>();
    private int startupScanIndex;
    private boolean stopped;

    public ChunkCaptureService(JavaPlugin plugin, TileRenderer renderer) {
        this.plugin = plugin;
        this.renderer = renderer;
        this.maxQueue = Math.max(1, plugin.getConfig().getInt("capture.max-queue-size", 256));
        this.chunksPerTick = Math.max(1, plugin.getConfig().getInt("capture.chunks-per-tick", 1));
        // Snapshot only chunks that are already loaded. Never call getChunkAt to discover
        // terrain during startup: this list contains coordinates, not live Chunk objects.
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                startupScan.add(new ChunkKey(world.getUID(), chunk.getX(), chunk.getZ()));
            }
        }
        plugin.getLogger().info("MaoMap queued " + startupScan.size()
                + " already-loaded chunks for gradual initial capture.");
    }

    @EventHandler public void onChunkLoad(ChunkLoadEvent event) {
        enqueue(event.getChunk());
    }

    @EventHandler(ignoreCancelled = true) public void onBlockPlace(BlockPlaceEvent event) {
        enqueue(event.getBlock().getChunk());
    }

    @EventHandler(ignoreCancelled = true) public void onBlockBreak(BlockBreakEvent event) {
        enqueue(event.getBlock().getChunk());
    }

    public void enqueue(Chunk chunk) {
        if (stopped) return;
        ChunkKey key = new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        if (pending.contains(key) || pending.size() >= maxQueue) return;
        pending.add(key);
    }

    /** Must be called on the server thread. */
    public void processCaptureQueue() {
        if (stopped) return;
        // Feed the bounded work queue from the startup snapshot as capacity becomes available.
        while (startupScanIndex < startupScan.size() && pending.size() < maxQueue) {
            ChunkKey key = startupScan.get(startupScanIndex++);
            pending.add(key);
        }
        if (startupScanIndex >= startupScan.size()) startupScan.clear();

        int processed = 0;
        var iterator = pending.iterator();
        while (iterator.hasNext() && processed < chunksPerTick) {
            ChunkKey key = iterator.next();
            iterator.remove();
            World world = plugin.getServer().getWorld(key.worldId());
            if (world == null || !world.isChunkLoaded(key.x(), key.z())) continue;
            Chunk chunk = world.getChunkAt(key.x(), key.z());
            ChunkSnapshot snapshot = snapshot(chunk);
            if (!renderer.renderAsync(snapshot)) {
                // Keep the request for a later tick instead of running rendering on the server thread.
                pending.add(key);
                break;
            }
            processed++;
        }
    }

    private ChunkSnapshot snapshot(Chunk chunk) {
        World world = chunk.getWorld();
        int[] heights = new int[256];
        String[] materials = new String[256];
        int minHeight = world.getMinHeight();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int index = z * 16 + x;
                int blockX = chunk.getX() * 16 + x;
                int blockZ = chunk.getZ() * 16 + z;
                int y = world.getHighestBlockYAt(blockX, blockZ);
                Material material = world.getBlockAt(blockX, y, blockZ).getType();
                heights[index] = y;
                materials[index] = material.name();
            }
        }
        return new ChunkSnapshot(world.getUID(), world.getName(), chunk.getX(), chunk.getZ(), minHeight, heights, materials);
    }

    public int pendingCount() { return pending.size() + (startupScan.size() - startupScanIndex); }
    public int renderQueueCount() { return renderer.queuedTaskCount(); }
    public int activeRenderCount() { return renderer.activeTaskCount(); }

    public void shutdown() {
        stopped = true;
        pending.clear();
        startupScan.clear();
        renderer.shutdown();
    }

    private record ChunkKey(UUID worldId, int x, int z) { }
}
