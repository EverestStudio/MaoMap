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
    private static final int DATA_VERSION = 1;

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
                    // The worker may have removed this state while we waited for its lock.
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

        int tileX = Math.floorDiv(snapshot.chunkX(), CHUNKS_PER_TILE);
        int tileZ = Math.floorDiv(snapshot.chunkZ(), CHUNKS_PER_TILE);
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
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(temporary))) {
            out.writeInt(DATA_MAGIC);
            out.writeInt(DATA_VERSION);
            out.writeInt(snapshot.minHeight());
            out.writeUTF(snapshot.worldName());
            for (int i = 0; i < 256; i++) {
                out.writeInt(heights[i]);
                out.writeUTF(materials[i]);
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
                int red = clamp(base.getRed() + shade);
                int green = clamp(base.getGreen() + shade);
                int blue = clamp(base.getBlue() + shade);
                image.setRGB(x, z, new Color(red, green, blue).getRGB());
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
            if (in.readInt() != DATA_VERSION) throw new IOException("Unsupported chunk data version: " + file);
            in.readInt(); // minHeight, reserved for future rendering styles
            in.readUTF(); // world name
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int local = z * 16 + x;
                    int target = (offsetZ + z) * TILE_SIZE + offsetX + x;
                    heights[target] = in.readInt();
                    colors[target] = colorFor(in.readUTF()).getRGB();
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

    private Color colorFor(String materialName) {
        String name = materialName.toUpperCase(Locale.ROOT);
        if (name.contains("WATER") || name.contains("ICE")) return new Color(55, 115, 175);
        if (name.contains("SAND") || name.contains("SANDSTONE") || name.contains("TERRACOTTA")) return new Color(211, 190, 125);
        if (name.contains("SNOW") || name.contains("QUARTZ") || name.contains("CALCITE")) return new Color(220, 222, 218);
        if (name.contains("LEAVES") || name.contains("MOSS") || name.contains("GRASS")) return new Color(83, 145, 76);
        if (name.contains("LOG") || name.contains("WOOD")) return new Color(112, 87, 57);
        if (name.contains("STONE") || name.contains("DEEPSLATE") || name.contains("ORE")) return new Color(115, 116, 119);
        if (name.contains("LAVA")) return new Color(220, 83, 25);
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
