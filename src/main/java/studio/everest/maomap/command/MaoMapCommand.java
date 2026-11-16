package studio.everest.maomap.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import studio.everest.maomap.capture.ChunkCaptureService;

public final class MaoMapCommand implements CommandExecutor {
    private final ChunkCaptureService capture;

    public MaoMapCommand(ChunkCaptureService capture) {
        this.capture = capture;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("§aMaoMap §7| Ожидают захвата: §f" + capture.pendingCount()
                    + " §7| В очереди рендера: §f" + capture.renderQueueCount()
                    + " §7| Рендерятся: §f" + capture.activeRenderCount());
            return true;
        }
        sender.sendMessage("§eMaoMap §7| Команды: /maomap status");
        return true;
    }
}
