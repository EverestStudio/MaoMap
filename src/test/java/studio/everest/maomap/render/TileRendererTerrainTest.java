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
        TileRenderer renderer = new TileRenderer(tempDir, 1, 4, message -> fail(message));
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
            assertNotNull(ImageIO.read(tile.toFile()));
            assertTrue((ImageIO.read(tile.toFile()).getRGB(0, 0) >>> 24) != 0);
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
