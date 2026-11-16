package studio.everest.maomap.render;

/**
 * Converts Minecraft chunk coordinates into atlas tile coordinates.
 * Each atlas tile contains 16x16 chunks (256x256 pixels).
 */
public final class TileCoordinates {
    public static final int CHUNKS_PER_TILE = 16;
    public static final int PIXELS_PER_CHUNK = 16;

    private TileCoordinates() { }

    public static int tileOfChunk(int chunkCoordinate) {
        return Math.floorDiv(chunkCoordinate, CHUNKS_PER_TILE);
    }

    public static int localChunkOf(int chunkCoordinate) {
        return Math.floorMod(chunkCoordinate, CHUNKS_PER_TILE);
    }

    public static int pixelOffsetOfChunk(int chunkCoordinate) {
        return localChunkOf(chunkCoordinate) * PIXELS_PER_CHUNK;
    }
}
