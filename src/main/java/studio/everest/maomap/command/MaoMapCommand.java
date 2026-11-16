package studio.everest.maomap.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import studio.everest.maomap.capture.ChunkCaptureService;

public final class MaoMapCommand implements CommandExecutor {
    private final JavaPlugin plugin;
    private final ChunkCaptureService capture;

    public MaoMapCommand(JavaPlugin plugin, ChunkCaptureService capture) {
        this.plugin = plugin;
        this.capture = capture;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("§aMaoMap §7| Ожидают обработки чанки: §f" + capture.pendingCount());
            return true;
        }
        sender.sendMessage("§eMaoMap §7| Команды: /maomap status");
        return true;
    }
}
