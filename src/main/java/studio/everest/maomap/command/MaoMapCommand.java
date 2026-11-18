package studio.everest.maomap.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import studio.everest.maomap.capture.ChunkCaptureService;
import studio.everest.maomap.marker.MapMarker;
import studio.everest.maomap.marker.MarkerStore;

import java.io.IOException;

public final class MaoMapCommand implements CommandExecutor {
    private final ChunkCaptureService capture;
    private final MarkerStore markers;

    public MaoMapCommand(ChunkCaptureService capture, MarkerStore markers) {
        this.capture = capture;
        this.markers = markers;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("§aMaoMap §7| Ожидают захвата: §f" + capture.pendingCount()
                    + " §7| В очереди рендера: §f" + capture.renderQueueCount()
                    + " §7| Рендерятся: §f" + capture.activeRenderCount());
            return true;
        }
        if (args.length >= 4 && args[0].equalsIgnoreCase("marker") && args[1].equalsIgnoreCase("add")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cЭту команду может выполнить только игрок.");
                return true;
            }
            String emoji = args[2];
            String name = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)).trim();
            if (emoji.length() > 16 || name.isBlank() || name.length() > 80) {
                sender.sendMessage("§cИспользование: /maomap marker add <эмодзи> <название>");
                return true;
            }
            try {
                MapMarker marker = markers.add(player.getWorld().getUID(),
                        player.getLocation().getBlockX(), player.getLocation().getBlockZ(), emoji, name);
                sender.sendMessage("§aМетка добавлена: §f" + marker.emoji() + " " + marker.name()
                        + " §7(ID: " + marker.id() + ")");
            } catch (IOException exception) {
                sender.sendMessage("§cНе удалось сохранить метку: " + exception.getMessage());
            }
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("marker") && args[1].equalsIgnoreCase("remove")) {
            try {
                sender.sendMessage(markers.remove(args[2]) ? "§aМетка удалена." : "§cМетка не найдена.");
            } catch (IOException exception) {
                sender.sendMessage("§cНе удалось сохранить изменения: " + exception.getMessage());
            }
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("marker") && args[1].equalsIgnoreCase("list")) {
            var all = markers.all();
            sender.sendMessage("§aМетки MaoMap: §f" + all.size());
            for (MapMarker marker : all) sender.sendMessage("§7" + marker.id() + " §f"
                    + marker.emoji() + " " + marker.name() + " §8(" + marker.x() + ", " + marker.z() + ")");
            return true;
        }
        sender.sendMessage("§eMaoMap §7| /maomap status, /maomap marker add <эмодзи> <название>, /maomap marker list, /maomap marker remove <id>");
        return true;
    }
}
