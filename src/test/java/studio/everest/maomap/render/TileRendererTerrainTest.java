package studio.everest.maomap.render;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.everest.maomap.capture.ChunkSnapshot;

import javax.imageio.ImageIO;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TileRendererTerrainTest {
    @TempDir Path tempDir;

    @Test
    void persistsV3CanopyAndBathymetryAndProducesTile() throws Exception {
        UUID worldId = UUID.randomUUID();
        int[] ground = new int[256];
        String[] materials = new String[256];
        int[] canopy = new int[256];
        String[] canopyMaterials = new String[256];
        int[] water = new int[256];
        String[] biomes = new String[256];
        Arrays.fill(ground, 64);
        Arrays.fill(materials, "GRASS_BLOCK");
        Arrays.fill(canopy, -64);
        Arrays.fill(canopyMaterials, "");
        Arrays.fill(biomes, "FOREST");
        canopy[0] = 78;
        canopyMaterials[0] = "OAK_LEAVES";
        water[1] = 22;
        materials[1] = "SAND";
        biomes[1] = "OCEAN";

        ChunkSnapshot snapshot = new ChunkSnapshot(worldId, "world", 0, 0, -64,
                ground, materials, canopy, canopyMaterials, water, biomes);
        AtomicReference<String> renderError = new AtomicReference<>();
        TileRenderer renderer = new TileRenderer(tempDir, 1, 4, renderError::set);
        try {
            assertTrue(renderer.renderAsync(snapshot));
            Path tile = tempDir.resolve(worldId.toString()).resolve("tiles/0_0.png");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.isRegularFile(tile) && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(Files.isRegularFile(tile), "rendered tile should appear");

            Path data = tempDir.resolve(worldId.toString()).resolve("chunks/0_0.mcs");
            try (DataInputStream in = new DataInputStream(Files.newInputStream(data))) {
                assertEquals(0x4D414F31, in.readInt());
                assertEquals(3, in.readInt());
                assertEquals(-64, in.readInt());
                assertEquals("world", in.readUTF());
                assertEquals(64, in.readInt());
                assertEquals("GRASS_BLOCK", in.readUTF());
                assertEquals(78, in.readInt());
                assertEquals("OAK_LEAVES", in.readUTF());
                assertEquals(0, in.readInt());
                assertEquals("FOREST", in.readUTF());
                assertEquals(64, in.readInt());
                assertEquals("SAND", in.readUTF());
                assertEquals(-64, in.readInt());
                assertEquals("", in.readUTF());
                assertEquals(22, in.readInt());
                assertEquals("OCEAN", in.readUTF());
            }
            var rendered = ImageIO.read(tile.toFile());
            assertNotNull(rendered);
            assertTrue((rendered.getRGB(0, 0) >>> 24) != 0);
            assertNull(renderError.get(), renderError.get());
        } finally {
            renderer.shutdown();
        }
    }


    @Test
    void readsLegacyV1AndV2ChunkSnapshotsWhenRebuildingTile() throws Exception {
        UUID worldId = UUID.randomUUID();
        Path worldDir = tempDir.resolve(worldId.toString());
        Path chunks = worldDir.resolve("chunks");
        Files.createDirectories(chunks);
        writeLegacyChunk(chunks.resolve("0_0.mcs"), 1, "PLAINS");
        writeLegacyChunk(chunks.resolve("1_0.mcs"), 2, "FOREST");

        int[] heights = new int[256];
        String[] materials = new String[256];
        int[] canopy = new int[256];
        String[] canopyMaterials = new String[256];
        int[] water = new int[256];
        String[] biomes = new String[256];
        Arrays.fill(heights, 70);
        Arrays.fill(materials, "STONE");
        Arrays.fill(canopy, -64);
        Arrays.fill(canopyMaterials, "");
        Arrays.fill(biomes, "PLAINS");
        ChunkSnapshot trigger = new ChunkSnapshot(worldId, "world", 2, 0, -64,
                heights, materials, canopy, canopyMaterials, water, biomes);
        AtomicReference<String> renderError = new AtomicReference<>();
        TileRenderer renderer = new TileRenderer(tempDir, 1, 4, renderError::set);
        try {
            assertTrue(renderer.renderAsync(trigger));
            Path tile = worldDir.resolve("tiles/0_0.png");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.isRegularFile(tile) && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(Files.isRegularFile(tile));
            assertNotNull(ImageIO.read(tile.toFile()));
            assertNull(renderError.get(), renderError.get());
        } finally {
            renderer.shutdown();
        }
    }

    private void writeLegacyChunk(Path file, int version, String biome) throws Exception {
        try (var out = new java.io.DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(0x4D414F31);
            out.writeInt(version);
            out.writeInt(-64);
            out.writeUTF("world");
            for (int i = 0; i < 256; i++) {
                out.writeInt(64);
                out.writeUTF("GRASS_BLOCK");
                if (version >= 2) out.writeUTF(biome);
            }
        }
    }

    @Test
    void rendersDistinctTreeCanopiesAndHighlightsShorelines() throws Exception {
        UUID worldId = UUID.randomUUID();
        int[] ground = new int[256];
        String[] materials = new String[256];
        int[] canopy = new int[256];
        String[] canopyMaterials = new String[256];
        int[] water = new int[256];
        String[] biomes = new String[256];
        Arrays.fill(ground, 64);
        Arrays.fill(materials, "GRASS_BLOCK");
        Arrays.fill(canopy, -64);
        Arrays.fill(canopyMaterials, "");
        Arrays.fill(biomes, "PLAINS");
        canopy[0] = 78;
        canopyMaterials[0] = "OAK_LEAVES";
        canopy[1] = 78;
        canopyMaterials[1] = "BIRCH_LEAVES";
        water[18] = 2; // shallow water at (2, 1), beside the shoreline pixel (1, 1)

        ChunkSnapshot snapshot = new ChunkSnapshot(worldId, "world", 0, 0, -64,
                ground, materials, canopy, canopyMaterials, water, biomes);
        AtomicReference<String> renderError = new AtomicReference<>();
        TileRenderer renderer = new TileRenderer(tempDir, 1, 4, renderError::set);
        try {
            assertTrue(renderer.renderAsync(snapshot));
            Path tile = tempDir.resolve(worldId.toString()).resolve("tiles/0_0.png");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.isRegularFile(tile) && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(Files.isRegularFile(tile), "rendered tile should appear");
            var image = ImageIO.read(tile.toFile());
            assertNotEquals(image.getRGB(0, 0), image.getRGB(1, 0),
                    "oak and birch canopy should have distinct colors");
            assertNotEquals(image.getRGB(1, 1), image.getRGB(5, 5),
                    "shoreline should be highlighted compared with ordinary grass");
            assertNotEquals(image.getRGB(2, 1), image.getRGB(5, 5),
                    "shallow water should have its own color");
            assertNull(renderError.get(), renderError.get());
        } finally {
            renderer.shutdown();
        }
    }

    @Test
    void snapshotDefensivelyCopiesTerrainArrays() {
        int[] heights = new int[256];
        String[] materials = new String[256];
        int[] canopy = new int[256];
        String[] canopyMaterials = new String[256];
        int[] water = new int[256];
        String[] biomes = new String[256];
        Arrays.fill(materials, "STONE");
        Arrays.fill(canopyMaterials, "");
        Arrays.fill(biomes, "PLAINS");
        ChunkSnapshot snapshot = new ChunkSnapshot(UUID.randomUUID(), "world", 0, 0, -64,
                heights, materials, canopy, canopyMaterials, water, biomes);
        heights[0] = 99;
        assertEquals(0, snapshot.groundHeights()[0]);
        int[] exposed = snapshot.groundHeights();
        exposed[0] = 42;
        assertEquals(0, snapshot.groundHeights()[0]);
    }
}
