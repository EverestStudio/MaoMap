package studio.everest.maomap.marker;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class MarkerStore {
    private final File file;
    private final YamlConfiguration data;

    public MarkerStore(JavaPlugin plugin) {
        this.file = new File(plugin.getDataFolder(), "markers.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    public synchronized MapMarker add(UUID worldId, int x, int z, String emoji, String name) throws IOException {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String path = "markers." + id;
        data.set(path + ".world", worldId.toString());
        data.set(path + ".x", x);
        data.set(path + ".z", z);
        data.set(path + ".emoji", emoji);
        data.set(path + ".name", name);
        data.save(file);
        return new MapMarker(id, worldId, x, z, emoji, name);
    }

    public synchronized boolean remove(String id) throws IOException {
        String path = "markers." + id;
        if (!data.contains(path)) return false;
        data.set(path, null);
        data.save(file);
        return true;
    }

    public synchronized List<MapMarker> all() {
        List<MapMarker> result = new ArrayList<>();
        ConfigurationSection section = data.getConfigurationSection("markers");
        if (section == null) return result;
        for (String id : section.getKeys(false)) {
            String world = section.getString(id + ".world");
            try {
                result.add(new MapMarker(id, UUID.fromString(world),
                        section.getInt(id + ".x"), section.getInt(id + ".z"),
                        section.getString(id + ".emoji", "📍"), section.getString(id + ".name", "")));
            } catch (IllegalArgumentException ignored) { }
        }
        return result;
    }
}
