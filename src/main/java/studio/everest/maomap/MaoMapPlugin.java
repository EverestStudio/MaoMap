package studio.everest.maomap;

import org.bukkit.plugin.java.JavaPlugin;
import studio.everest.maomap.capture.ChunkCaptureService;
import studio.everest.maomap.command.MaoMapCommand;
import studio.everest.maomap.render.TileRenderer;
import studio.everest.maomap.web.WebMapServer;
import studio.everest.maomap.marker.MarkerStore;
import java.io.IOException;

public final class MaoMapPlugin extends JavaPlugin {
    private ChunkCaptureService captureService;
    private WebMapServer webServer;
    private MarkerStore markerStore;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        TileRenderer renderer = new TileRenderer(
                getDataFolder().toPath().resolve(getConfig().getString("render.output-directory", "tiles")),
                getConfig().getInt("render.worker-threads", 2),
                getConfig().getInt("render.queue-capacity", 128),
                message -> getLogger().warning(message));
        captureService = new ChunkCaptureService(this, renderer);
        markerStore = new MarkerStore(this);
        getServer().getPluginManager().registerEvents(captureService, this);
        getServer().getScheduler().runTaskTimer(this, captureService::processCaptureQueue, 1L, 1L);
        if (getCommand("maomap") != null) {
            getCommand("maomap").setExecutor(new MaoMapCommand(captureService, markerStore));
        } else {
            getLogger().severe("Command 'maomap' is missing from plugin.yml; disabling MaoMap.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (getConfig().getBoolean("web.enabled", true)) {
            webServer = new WebMapServer(this, getDataFolder().toPath().resolve(getConfig().getString("render.output-directory", "tiles")), markerStore);
            try {
                webServer.start(getConfig().getString("web.host", "127.0.0.1"), getConfig().getInt("web.port", 8123));
            } catch (IOException exception) {
                getLogger().severe("Could not start MaoMap web server: " + exception.getMessage());
                webServer = null;
            }
        }
        getLogger().info("MaoMap enabled.");
    }

    @Override
    public void onDisable() {
        if (webServer != null) webServer.stop();
        if (captureService != null) captureService.shutdown();
    }
}
