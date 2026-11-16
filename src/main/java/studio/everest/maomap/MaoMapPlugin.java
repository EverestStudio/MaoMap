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
        TileRenderer renderer = new TileRenderer(getDataFolder().toPath().resolve(getConfig().getString("render.output-directory", "tiles")));
        captureService = new ChunkCaptureService(this, renderer);
        getServer().getPluginManager().registerEvents(captureService, this);
        getServer().getScheduler().runTaskTimer(this, captureService::processCaptureQueue,
                1L, 1L);
        getCommand("maomap").setExecutor(new MaoMapCommand(this, captureService));
        getLogger().info("MaoMap enabled. Map rendering is in early development.");
    }

    @Override
    public void onDisable() {
        if (captureService != null) captureService.shutdown();
    }
}
