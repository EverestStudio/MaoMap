package studio.everest.maomap.marker;

import java.util.UUID;

public record MapMarker(String id, UUID worldId, int x, int z, String emoji, String name) { }
