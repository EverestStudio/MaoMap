package studio.everest.maomap.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TileCoordinatesTest {
    @Test
    void mapsPositiveChunkCoordinates() {
        assertEquals(0, TileCoordinates.tileOfChunk(0));
        assertEquals(0, TileCoordinates.tileOfChunk(15));
        assertEquals(1, TileCoordinates.tileOfChunk(16));
        assertEquals(1, TileCoordinates.tileOfChunk(31));
        assertEquals(2, TileCoordinates.tileOfChunk(32));
    }

    @Test
    void mapsNegativeChunkCoordinatesUsingFloorDivision() {
        assertEquals(-1, TileCoordinates.tileOfChunk(-1));
        assertEquals(-1, TileCoordinates.tileOfChunk(-16));
        assertEquals(-2, TileCoordinates.tileOfChunk(-17));
        assertEquals(-2, TileCoordinates.tileOfChunk(-32));
        assertEquals(-3, TileCoordinates.tileOfChunk(-33));
    }

    @Test
    void calculatesLocalChunkAndPixelOffsetsAcrossZero() {
        assertEquals(15, TileCoordinates.localChunkOf(-1));
        assertEquals(0, TileCoordinates.localChunkOf(-16));
        assertEquals(15, TileCoordinates.localChunkOf(-17));
        assertEquals(0, TileCoordinates.localChunkOf(0));
        assertEquals(15, TileCoordinates.localChunkOf(15));
        assertEquals(0, TileCoordinates.localChunkOf(16));

        assertEquals(240, TileCoordinates.pixelOffsetOfChunk(-1));
        assertEquals(0, TileCoordinates.pixelOffsetOfChunk(-16));
        assertEquals(240, TileCoordinates.pixelOffsetOfChunk(15));
        assertEquals(0, TileCoordinates.pixelOffsetOfChunk(16));
    }
}
