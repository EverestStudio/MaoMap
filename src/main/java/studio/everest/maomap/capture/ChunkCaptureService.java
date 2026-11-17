package studio.everest.maomap.capture;

import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
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
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                startupScan.add(new ChunkKey(world.getUID(), chunk.getX(), chunk.getZ()));
            }
        }
        plugin.getLogger().info("MaoMap queued " + startupScan.size()
                + " already-loaded chunks for gradual initial capture.");
    }

    @EventHandler public void onChunkLoad(ChunkLoadEvent event) { enqueue(event.getChunk()); }
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
        while (startupScanIndex < startupScan.size() && pending.size() < maxQueue) {
            ChunkKey key = startupScan.get(startupScanIndex++);
            pending.add(key);
        }
        if (startupScanIndex >= startupScan.size()) startupScan.clear();

        int examined = 0;
        var iterator = pending.iterator();
        while (iterator.hasNext() && examined < chunksPerTick) {
            ChunkKey key = iterator.next();
            iterator.remove();
            examined++;
            World world = plugin.getServer().getWorld(key.worldId());
            if (world == null || !world.isChunkLoaded(key.x(), key.z())) continue;
            Chunk chunk = world.getChunkAt(key.x(), key.z());
            if (!renderer.renderAsync(snapshot(chunk))) {
                pending.add(key);
                break;
            }
        }
    }

    private ChunkSnapshot snapshot(Chunk chunk) {
        World world = chunk.getWorld();
        int[] groundHeights = new int[256];
        String[] groundMaterials = new String[256];
        int[] canopyHeights = new int[256];
        String[] canopyMaterials = new String[256];
        int[] waterDepths = new int[256];
        String[] biomes = new String[256];
        int minHeight = world.getMinHeight();
        int maxHeight = world.getMaxHeight();

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int index = z * 16 + x;
                int blockX = chunk.getX() * 16 + x;
                int blockZ = chunk.getZ() * 16 + z;

                int groundY = world.getHighestBlockYAt(blockX, blockZ, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                Block ground = world.getBlockAt(blockX, groundY, blockZ);
                groundHeights[index] = groundY;
                groundMaterials[index] = ground.getType().name();
                biomes[index] = world.getBiome(blockX, groundY, blockZ).name();

                int topY = world.getHighestBlockYAt(blockX, blockZ, HeightMap.WORLD_SURFACE);
                Block top = world.getBlockAt(blockX, topY, blockZ);
                Material topType = top.getType();
                if (topY > groundY && (topType.name().contains("LEAVES")
                        || topType.name().contains("LOG") || topType.name().contains("WOOD"))) {
                    canopyHeights[index] = topY;
                    canopyMaterials[index] = topType.name();
                } else {
                    canopyHeights[index] = minHeight;
                    canopyMaterials[index] = "";
                }

                // Count a contiguous water column immediately above the terrain floor.
                int depth = 0;
                for (int y = groundY + 1; y < maxHeight; y++) {
                    Material type = world.getBlockAt(blockX, y, blockZ).getType();
                    if (type == Material.WATER) depth++;
                    else break;
                }
                waterDepths[index] = depth;
            }
        }
        return new ChunkSnapshot(world.getUID(), world.getName(), chunk.getX(), chunk.getZ(),
                minHeight, groundHeights, groundMaterials, canopyHeights, canopyMaterials,
                waterDepths, biomes);
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
