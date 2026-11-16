package studio.everest.maomap.render;

import studio.everest.maomap.capture.ChunkSnapshot;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Persists immutable chunk snapshots and composes 256x256 atlas tiles.
 * This class never accesses Bukkit; all world reads happen before submission.
 */
public final class TileRenderer {
    private static final int CHUNKS_PER_TILE = 16;
    private static final int TILE_SIZE = 256;
    private static final int DATA_MAGIC = 0x4D414F31; // MAO1
    private static final int DATA_VERSION = 2;

    private final Path output;
    private final Consumer<String> errorLogger;
    private final ThreadPoolExecutor workers;
    private final ConcurrentHashMap<ChunkKey, AtomicReference<ChunkSnapshot>> pending =
            new ConcurrentHashMap<>();
    private final Object[] tileLocks = new Object[64];

    public TileRenderer(Path output, int workerCount, int queueCapacity, Consumer<String> errorLogger) {
        this.output = output;
        this.errorLogger = errorLogger;
        for (int i = 0; i < tileLocks.length; i++) tileLocks[i] = new Object();
        int threads = Math.max(1, workerCount);
        int capacity = Math.max(1, queueCapacity);
        this.workers = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), runnable -> {
                    Thread thread = new Thread(runnable, "MaoMap-Renderer");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * @return true when accepted (including an update coalesced into an active task),
     *         false when the bounded worker queue is full.
     */
    public boolean renderAsync(ChunkSnapshot snapshot) {
        ChunkKey key = new ChunkKey(snapshot.worldId(), snapshot.chunkX(), snapshot.chunkZ());
        while (true) {
            AtomicReference<ChunkSnapshot> existing = pending.get(key);
            if (existing != null) {
                synchronized (existing) {
                    if (pending.get(key) != existing) continue;
                    existing.set(snapshot);
                    return true;
                }
            }

            AtomicReference<ChunkSnapshot> state = new AtomicReference<>(snapshot);
            if (pending.putIfAbsent(key, state) != null) continue;

            try {
                workers.execute(() -> processLatest(key, state));
                return true;
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                pending.remove(key, state);
                return false;
            }
        }
    }

    private void processLatest(ChunkKey key, AtomicReference<ChunkSnapshot> state) {
        while (true) {
            ChunkSnapshot snapshot = state.getAndSet(null);
            if (snapshot != null) {
                try {
                    persistAndRender(snapshot);
                } catch (IOException exception) {
                    errorLogger.accept("MaoMap could not save/render chunk " + key.x + "," + key.z
                            + " in world " + key.worldId + ": " + exception.getMessage());
                }
            }

            synchronized (state) {
                if (state.get() != null) continue;
                pending.remove(key, state);
                return;
            }
        }
    }

    private void persistAndRender(ChunkSnapshot snapshot) throws IOException {
        Path worldDirectory = output.resolve(snapshot.worldId().toString());
        Path chunkDirectory = worldDirectory.resolve("chunks");
        Path tileDirectory = worldDirectory.resolve("tiles");
        Files.createDirectories(chunkDirectory);
        Files.createDirectories(tileDirectory);

        Path dataFile = chunkDirectory.resolve(snapshot.chunkX() + "_" + snapshot.chunkZ() + ".mcs");
        writeSnapshotAtomically(dataFile, snapshot);

        int tileX = TileCoordinates.tileOfChunk(snapshot.chunkX());
        int tileZ = TileCoordinates.tileOfChunk(snapshot.chunkZ());
        Object lock = tileLocks[Math.floorMod(31 * snapshot.worldId().hashCode()
                + 17 * tileX + tileZ, tileLocks.length)];
        synchronized (lock) {
            renderTile(worldDirectory, tileDirectory, snapshot.worldId(), tileX, tileZ);
        }
    }

    private void writeSnapshotAtomically(Path target, ChunkSnapshot snapshot) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        int[] heights = snapshot.surfaceHeights();
        String[] materials = snapshot.surfaceMaterials();
        String[] biomes = snapshot.surfaceBiomes();
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(temporary))) {
            out.writeInt(DATA_MAGIC);
            out.writeInt(DATA_VERSION);
            out.writeInt(snapshot.minHeight());
            out.writeUTF(snapshot.worldName());
            for (int i = 0; i < 256; i++) {
                out.writeInt(heights[i]);
                out.writeUTF(materials[i]);
                out.writeUTF(biomes[i]);
            }
        }
        moveAtomically(temporary, target);
    }

    private void renderTile(Path worldDirectory, Path tileDirectory, UUID worldId,
                            int tileX, int tileZ) throws IOException {
        int[] heights = new int[TILE_SIZE * TILE_SIZE];
        int[] colors = new int[TILE_SIZE * TILE_SIZE];
        boolean[] present = new boolean[TILE_SIZE * TILE_SIZE];

        for (int localChunkZ = 0; localChunkZ < CHUNKS_PER_TILE; localChunkZ++) {
            for (int localChunkX = 0; localChunkX < CHUNKS_PER_TILE; localChunkX++) {
                int chunkX = tileX * CHUNKS_PER_TILE + localChunkX;
                int chunkZ = tileZ * CHUNKS_PER_TILE + localChunkZ;
                Path dataFile = worldDirectory.resolve("chunks")
                        .resolve(chunkX + "_" + chunkZ + ".mcs");
                if (!Files.isRegularFile(dataFile)) continue;
                readChunkIntoTile(dataFile, localChunkX * 16, localChunkZ * 16,
                        heights, colors, present);
            }
        }

        BufferedImage image = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int z = 0; z < TILE_SIZE; z++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                int index = z * TILE_SIZE + x;
                if (!present[index]) continue;
                int left = heightAt(heights, present, x - 1, z, heights[index]);
                int right = heightAt(heights, present, x + 1, z, heights[index]);
                int up = heightAt(heights, present, x, z - 1, heights[index]);
                int down = heightAt(heights, present, x, z + 1, heights[index]);
                double slope = ((left - right) + (up - down) * 0.65) / 18.0;
                int shade = (int) Math.round(Math.max(-38, Math.min(38, slope)));
                Color base = new Color(colors[index], true);
                image.setRGB(x, z, new Color(clamp(base.getRed() + shade),
                        clamp(base.getGreen() + shade), clamp(base.getBlue() + shade)).getRGB());
            }
        }

        Path target = tileDirectory.resolve(tileX + "_" + tileZ + ".png");
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        if (!ImageIO.write(image, "png", temporary.toFile())) {
            throw new IOException("No PNG writer is available");
        }
        moveAtomically(temporary, target);
    }

    private void readChunkIntoTile(Path file, int offsetX, int offsetZ, int[] heights,
                                   int[] colors, boolean[] present) throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            if (in.readInt() != DATA_MAGIC) throw new IOException("Invalid chunk data header: " + file);
            int version = in.readInt();
            if (version < 1 || version > DATA_VERSION) {
                throw new IOException("Unsupported chunk data version " + version + ": " + file);
            }
            in.readInt(); // minHeight, reserved for future rendering styles
            in.readUTF(); // world name
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int local = z * 16 + x;
                    int target = (offsetZ + z) * TILE_SIZE + offsetX + x;
                    int height = in.readInt();
                    String material = in.readUTF();
                    String biome = version >= 2 ? in.readUTF() : "PLAINS";
                    heights[target] = height;
                    colors[target] = colorFor(material, biome).getRGB();
                    present[target] = true;
                }
            }
        }
    }

    private int heightAt(int[] heights, boolean[] present, int x, int z, int fallback) {
        if (x < 0 || x >= TILE_SIZE || z < 0 || z >= TILE_SIZE) return fallback;
        int index = z * TILE_SIZE + x;
        return present[index] ? heights[index] : fallback;
    }

    private Color colorFor(String materialName, String biomeName) {
        String material = materialName.toUpperCase(Locale.ROOT);
        String biome = biomeName.toUpperCase(Locale.ROOT);

        if (material.contains("LAVA")) return new Color(220, 83, 25);
        if (material.contains("WATER") || biome.contains("OCEAN") || biome.contains("RIVER")) {
            if (biome.contains("FROZEN") || material.contains("ICE")) return new Color(166, 205, 218);
            if (biome.contains("WARM") || biome.contains("LUKEWARM")) return new Color(65, 157, 164);
            if (biome.contains("COLD") || biome.contains("DEEP")) return new Color(48, 91, 145);
            return new Color(57, 119, 166);
        }
        if (material.contains("ICE") || biome.contains("SNOWY") || biome.contains("FROZEN")
                || biome.contains("GROVE") || biome.contains("PEAKS")) return new Color(218, 225, 224);
        if (biome.contains("DESERT") || biome.contains("BADLANDS") || biome.contains("SAVANNA")) {
            if (material.contains("TERRACOTTA") || biome.contains("BADLANDS")) {
                return new Color(184, 111, 65);
            }
            return new Color(205, 184, 119);
        }
        if (biome.contains("SWAMP") || biome.contains("MANGROVE")) return new Color(91, 119, 76);
        if (biome.contains("JUNGLE") || biome.contains("BAMBOO")) return new Color(67, 133, 63);
        if (biome.contains("TAIGA") || biome.contains("OLD_GROWTH_PINE")
                || biome.contains("OLD_GROWTH_SPRUCE")) return new Color(76, 119, 82);
        if (biome.contains("MUSHROOM")) return new Color(142, 119, 155);
        if (biome.contains("MEADOW") || biome.contains("PLAINS") || biome.contains("CHERRY_GROVE")) {
            return biome.contains("CHERRY") ? new Color(157, 137, 151) : new Color(126, 166, 100);
        }
        if (biome.contains("FOREST") || biome.contains("GROVE")) return new Color(83, 139, 78);

        if (material.contains("SAND") || material.contains("SANDSTONE")) return new Color(211, 190, 125);
        if (material.contains("SNOW") || material.contains("QUARTZ") || material.contains("CALCITE")) {
            return new Color(220, 222, 218);
        }
        if (material.contains("LEAVES") || material.contains("MOSS") || material.contains("GRASS")) {
            return new Color(92, 151, 82);
        }
        if (material.contains("LOG") || material.contains("WOOD")) return new Color(112, 87, 57);
        if (material.contains("STONE") || material.contains("DEEPSLATE") || material.contains("ORE")) {
            return new Color(115, 116, 119);
        }
        return new Color(119, 155, 91);
    }

    private int clamp(int value) { return Math.max(0, Math.min(255, value)); }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public int queuedTaskCount() { return workers.getQueue().size(); }
    public int activeTaskCount() { return workers.getActiveCount(); }

    public void shutdown() {
        workers.shutdown();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) workers.shutdownNow();
        } catch (InterruptedException interrupted) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private record ChunkKey(UUID worldId, int x, int z) { }
}
