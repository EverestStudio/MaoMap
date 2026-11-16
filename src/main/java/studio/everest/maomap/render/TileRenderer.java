package studio.everest.maomap.render;

import studio.everest.maomap.capture.ChunkSnapshot;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal custom 16x16 atlas tile renderer. All Bukkit access happens before this class is called. */
public final class TileRenderer {
    private final Path output;
    private final ExecutorService workers = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "MaoMap-Renderer");
        thread.setDaemon(true);
        return thread;
    });

    public TileRenderer(Path output) { this.output = output; }

    public void renderAsync(ChunkSnapshot snapshot) {
        workers.submit(() -> render(snapshot));
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
            // Logging is deliberately left to a later centralized diagnostics component.
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

    public void shutdown() { workers.shutdown(); }
}
