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
    private static final int DATA_VERSION = 3;

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
        int[] heights = snapshot.groundHeights();
        String[] materials = snapshot.groundMaterials();
        int[] canopyHeights = snapshot.canopyHeights();
        String[] canopyMaterials = snapshot.canopyMaterials();
        int[] waterDepths = snapshot.waterDepths();
        String[] biomes = snapshot.surfaceBiomes();
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(temporary))) {
            out.writeInt(DATA_MAGIC);
            out.writeInt(DATA_VERSION);
            out.writeInt(snapshot.minHeight());
            out.writeUTF(snapshot.worldName());
            for (int i = 0; i < 256; i++) {
                out.writeInt(heights[i]);
                out.writeUTF(materials[i]);
                out.writeInt(canopyHeights[i]);
                out.writeUTF(canopyMaterials[i]);
                out.writeInt(waterDepths[i]);
                out.writeUTF(biomes[i]);
            }
        }
        moveAtomically(temporary, target);
    }

    private void renderTile(Path worldDirectory, Path tileDirectory, UUID worldId,
                            int tileX, int tileZ) throws IOException {
        int[] heights = new int[TILE_SIZE * TILE_SIZE];
        int[] colors = new int[TILE_SIZE * TILE_SIZE];
        int[] canopyHeights = new int[TILE_SIZE * TILE_SIZE];
        int[] canopyColors = new int[TILE_SIZE * TILE_SIZE];
        int[] waterDepths = new int[TILE_SIZE * TILE_SIZE];
        boolean[] present = new boolean[TILE_SIZE * TILE_SIZE];

        for (int localChunkZ = 0; localChunkZ < CHUNKS_PER_TILE; localChunkZ++) {
            for (int localChunkX = 0; localChunkX < CHUNKS_PER_TILE; localChunkX++) {
                int chunkX = tileX * CHUNKS_PER_TILE + localChunkX;
                int chunkZ = tileZ * CHUNKS_PER_TILE + localChunkZ;
                Path dataFile = worldDirectory.resolve("chunks")
                        .resolve(chunkX + "_" + chunkZ + ".mcs");
                if (!Files.isRegularFile(dataFile)) continue;
                readChunkIntoTile(dataFile, localChunkX * 16, localChunkZ * 16,
                        heights, colors, canopyHeights, canopyColors, waterDepths, present);
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
                int shade = (int) Math.round(Math.max(-48, Math.min(48, slope * 1.25)));
                Color base = new Color(colors[index], true);
                if (waterDepths[index] > 0) {
                    // Shallow water stays bright and transparent-looking; deep oceans become darker.
                    int depth = Math.min(48, waterDepths[index]);
                    Color water = depth <= 3 ? new Color(91, 169, 177) : new Color(31, 78, 132);
                    double amount = depth <= 3 ? 0.48 + depth * 0.035
                            : Math.min(0.82, 0.58 + depth / 180.0);
                    base = blend(base, water, amount);
                } else if (canopyHeights[index] > heights[index] && canopyColors[index] != 0) {
                    int canopyRise = canopyHeights[index] - heights[index];
                    Color canopy = new Color(canopyColors[index], true);
                    base = blend(base, canopy, Math.min(0.9, 0.68 + canopyRise / 64.0));
                }
                // A narrow warm shoreline makes the boundary between land and water readable.
                if (waterDepths[index] == 0 && touchesWater(waterDepths, x, z)) {
                    base = blend(base, new Color(207, 190, 137), 0.42);
                }
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
                                   int[] colors, int[] canopyHeights, int[] canopyColors,
                                   int[] waterDepths, boolean[] present) throws IOException {
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
                    int canopyHeight = version >= 3 ? in.readInt() : height;
                    String canopyMaterial = version >= 3 ? in.readUTF() : "";
                    int waterDepth = version >= 3 ? in.readInt() : 0;
                    String biome = version >= 2 ? in.readUTF() : "PLAINS";
                    heights[target] = height;
                    canopyHeights[target] = canopyHeight;
                    canopyColors[target] = canopyMaterial.isBlank() ? 0 : canopyColorFor(canopyMaterial, biome).getRGB();
                    waterDepths[target] = waterDepth;
                    colors[target] = colorFor(material, biome).getRGB();
                    present[target] = true;
                }
            }
        }
    }

    private boolean touchesWater(int[] waterDepths, int x, int z) {
        return isWaterAt(waterDepths, x - 1, z) || isWaterAt(waterDepths, x + 1, z)
                || isWaterAt(waterDepths, x, z - 1) || isWaterAt(waterDepths, x, z + 1);
    }

    private boolean isWaterAt(int[] waterDepths, int x, int z) {
        return x >= 0 && x < TILE_SIZE && z >= 0 && z < TILE_SIZE
                && waterDepths[z * TILE_SIZE + x] > 0;
    }

    private Color canopyColorFor(String materialName, String biomeName) {
        String material = materialName.toUpperCase(Locale.ROOT);
        String biome = biomeName.toUpperCase(Locale.ROOT);
        if (material.contains("CHERRY") || biome.contains("CHERRY")) return new Color(183, 132, 151);
        if (material.contains("BIRCH")) return new Color(143, 178, 103);
        if (material.contains("SPRUCE") || material.contains("PINE")) return new Color(48, 101, 75);
        if (material.contains("MANGROVE")) return new Color(66, 119, 73);
        if (material.contains("JUNGLE")) return new Color(48, 126, 57);
        if (material.contains("ACACIA")) return new Color(125, 151, 75);
        if (material.contains("DARK_OAK")) return new Color(48, 91, 54);
        if (biome.contains("SNOW") || biome.contains("FROZEN") || biome.contains("GROVE")) {
            return new Color(66, 112, 91);
        }
        return new Color(55, 119, 62);
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
        if (material.contains("WATER")) {
            if (biome.contains("FROZEN")) return new Color(166, 205, 218);
            if (biome.contains("WARM") || biome.contains("LUKEWARM")) return new Color(65, 157, 164);
            if (biome.contains("COLD") || biome.contains("DEEP")) return new Color(48, 91, 145);
            return new Color(57, 119, 166);
        }
        if (material.contains("ICE") || biome.contains("SNOWY") || biome.contains("FROZEN")
                || biome.contains("PEAKS")) return new Color(218, 225, 224);
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

    private Color blend(Color base, Color overlay, double amount) {
        double inverse = 1.0 - amount;
        return new Color((int) (base.getRed() * inverse + overlay.getRed() * amount),
                (int) (base.getGreen() * inverse + overlay.getGreen() * amount),
                (int) (base.getBlue() * inverse + overlay.getBlue() * amount));
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
