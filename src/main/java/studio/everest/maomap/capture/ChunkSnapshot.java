package studio.everest.maomap.capture;

import java.util.UUID;

/** Immutable per-column terrain, canopy and water data copied from a loaded chunk. */
public record ChunkSnapshot(UUID worldId, String worldName, int chunkX, int chunkZ,
                            int minHeight, int[] groundHeights, String[] groundMaterials,
                            int[] canopyHeights, String[] canopyMaterials, int[] waterDepths,
                            String[] surfaceBiomes) {
    public ChunkSnapshot {
        groundHeights = groundHeights.clone();
        groundMaterials = groundMaterials.clone();
        canopyHeights = canopyHeights.clone();
        canopyMaterials = canopyMaterials.clone();
        waterDepths = waterDepths.clone();
        surfaceBiomes = surfaceBiomes.clone();
        if (groundHeights.length != 256 || groundMaterials.length != 256
                || canopyHeights.length != 256 || canopyMaterials.length != 256
                || waterDepths.length != 256 || surfaceBiomes.length != 256) {
            throw new IllegalArgumentException("A chunk snapshot must contain 256 columns");
        }
    }

    @Override public int[] groundHeights() { return groundHeights.clone(); }
    @Override public String[] groundMaterials() { return groundMaterials.clone(); }
    @Override public int[] canopyHeights() { return canopyHeights.clone(); }
    @Override public String[] canopyMaterials() { return canopyMaterials.clone(); }
    @Override public int[] waterDepths() { return waterDepths.clone(); }
    @Override public String[] surfaceBiomes() { return surfaceBiomes.clone(); }
}
