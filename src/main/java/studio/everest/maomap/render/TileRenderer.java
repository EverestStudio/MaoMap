package studio.everest.maomap.render;

import studio.everest.maomap.capture.ChunkSnapshot;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Custom atlas tile renderer. Receives immutable snapshots only; it never accesses Bukkit.
 * Both the worker count and waiting-task queue are bounded.
 */
public final class TileRenderer {
    private final Path output;
    private final Consumer<String> errorLogger;
    private final ThreadPoolExecutor workers;
    private final Set<ChunkKey> inFlight = ConcurrentHashMap.newKeySet();

    public TileRenderer(Path output, int workerCount, int queueCapacity, Consumer<String> errorLogger) {
        this.output = output;
        this.errorLogger = errorLogger;
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
     * @return true when accepted or already being rendered; false when the bounded queue is full.
     */
    public boolean renderAsync(ChunkSnapshot snapshot) {
        ChunkKey key = new ChunkKey(snapshot.worldId(), snapshot.chunkX(), snapshot.chunkZ());
        if (!inFlight.add(key)) return true;
        try {
            workers.execute(() -> {
                try {
                    render(snapshot);
                } finally {
                    inFlight.remove(key);
                }
            });
            return true;
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            inFlight.remove(key);
            return false;
        }
    }

    private void render(ChunkSnapshot snapshot) {
        try {
            Path directory = output.resolve(snapshot.worldId().toString());
            Files.createDirectories(directory);
            BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            int[] heights = snapshot.surfaceHeights();
            String[] materials = snapshot.surfaceMaterials();
            for (int i = 0; i < 256; i++) {
                Color base = colorFor(materials[i]);
                int shade = Math.max(0, Math.min(30, (heights[i] - snapshot.minHeight()) / 2));
                int rgb = new Color(Math.min(255, base.getRed() + shade),
                        Math.min(255, base.getGreen() + shade), Math.min(255, base.getBlue() + shade)).getRGB();
                image.setRGB(i % 16, i / 16, rgb);
            }
            Path target = directory.resolve(snapshot.chunkX() + "_" + snapshot.chunkZ() + ".png");
            ImageIO.write(image, "png", target.toFile());
        } catch (IOException exception) {
            errorLogger.accept("Could not render chunk " + snapshot.chunkX() + "," + snapshot.chunkZ()
                    + " in world " + snapshot.worldName() + ": " + exception.getMessage());
        }
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

    public int queuedTaskCount() {
        return workers.getQueue().size();
    }

    public int activeTaskCount() {
        return workers.getActiveCount();
    }

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
