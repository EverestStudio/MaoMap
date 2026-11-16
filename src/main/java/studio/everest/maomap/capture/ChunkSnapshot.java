package studio.everest.maomap.capture;

import java.util.UUID;

/** Immutable data copied from a loaded chunk on the server thread. */
public record ChunkSnapshot(UUID worldId, String worldName, int chunkX, int chunkZ,
                            int minHeight, int[] surfaceHeights, String[] surfaceMaterials,
                            String[] surfaceBiomes) {
    public ChunkSnapshot {
        surfaceHeights = surfaceHeights.clone();
        surfaceMaterials = surfaceMaterials.clone();
        surfaceBiomes = surfaceBiomes.clone();
        if (surfaceHeights.length != 256 || surfaceMaterials.length != 256
                || surfaceBiomes.length != 256) {
            throw new IllegalArgumentException("A chunk snapshot must contain 256 columns");
        }
    }

    @Override public int[] surfaceHeights() { return surfaceHeights.clone(); }
    @Override public String[] surfaceMaterials() { return surfaceMaterials.clone(); }
    @Override public String[] surfaceBiomes() { return surfaceBiomes.clone(); }
}
