package studio.everest.maomap;

import org.bukkit.plugin.java.JavaPlugin;
import studio.everest.maomap.capture.ChunkCaptureService;
import studio.everest.maomap.command.MaoMapCommand;
import studio.everest.maomap.render.TileRenderer;

public final class MaoMapPlugin extends JavaPlugin {
    private ChunkCaptureService captureService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        TileRenderer renderer = new TileRenderer(
                getDataFolder().toPath().resolve(getConfig().getString("render.output-directory", "tiles")),
                getConfig().getInt("render.worker-threads", 2),
                getConfig().getInt("render.queue-capacity", 128),
                message -> getLogger().warning(message));
        captureService = new ChunkCaptureService(this, renderer);
        getServer().getPluginManager().registerEvents(captureService, this);
        getServer().getScheduler().runTaskTimer(this, captureService::processCaptureQueue, 1L, 1L);
        if (getCommand("maomap") != null) {
            getCommand("maomap").setExecutor(new MaoMapCommand(captureService));
        } else {
            getLogger().severe("Command 'maomap' is missing from plugin.yml; disabling MaoMap.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("MaoMap enabled. Map rendering is in early development.");
    }

    @Override
    public void onDisable() {
        if (captureService != null) captureService.shutdown();
    }
}
