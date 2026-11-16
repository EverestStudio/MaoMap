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

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Captures loaded chunks on the primary thread and renders immutable snapshots asynchronously. */
public final class ChunkCaptureService implements Listener {
    private final JavaPlugin plugin;
    private final TileRenderer renderer;
    private final Set<ChunkKey> pending = new LinkedHashSet<>();
    private final int maxQueue;
    private final int chunksPerTick;
    private boolean stopped;

    public ChunkCaptureService(JavaPlugin plugin, TileRenderer renderer) {
        this.plugin = plugin;
        this.renderer = renderer;
        this.maxQueue = Math.max(1, plugin.getConfig().getInt("capture.max-queue-size", 256));
        this.chunksPerTick = Math.max(1, plugin.getConfig().getInt("capture.chunks-per-tick", 1));
    }

    @EventHandler public void onChunkLoad(ChunkLoadEvent event) {
        if (event.isNewChunk()) enqueue(event.getChunk());
    }

    @EventHandler(ignoreCancelled = true) public void onBlockPlace(BlockPlaceEvent event) {
        enqueue(event.getBlock().getChunk());
    }

    @EventHandler(ignoreCancelled = true) public void onBlockBreak(BlockBreakEvent event) {
        enqueue(event.getBlock().getChunk());
    }

    public void enqueue(Chunk chunk) {
        if (stopped || pending.size() >= maxQueue) return;
        pending.add(new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()));
    }

    /** Must be called on the server thread. */
    public void processCaptureQueue() {
        if (stopped) return;
        int processed = 0;
        var iterator = pending.iterator();
        while (iterator.hasNext() && processed < chunksPerTick) {
            ChunkKey key = iterator.next();
            iterator.remove();
            World world = plugin.getServer().getWorld(key.worldId());
            if (world == null || !world.isChunkLoaded(key.x(), key.z())) continue;
            Chunk chunk = world.getChunkAt(key.x(), key.z());
            renderer.renderAsync(snapshot(chunk));
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
                int y = world.getHighestBlockYAt(chunk.getX() * 16 + x, chunk.getZ() * 16 + z);
                Material material = world.getBlockAt(chunk.getX() * 16 + x, y, chunk.getZ() * 16 + z).getType();
                heights[index] = y;
                materials[index] = material.name();
            }
        }
        return new ChunkSnapshot(world.getUID(), world.getName(), chunk.getX(), chunk.getZ(), minHeight, heights, materials);
    }

    public int pendingCount() { return pending.size(); }
    public void shutdown() { stopped = true; pending.clear(); renderer.shutdown(); }
    private record ChunkKey(UUID worldId, int x, int z) { }
}
