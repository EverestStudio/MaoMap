package studio.everest.maomap.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/** Read-only HTTP surface. Bukkit state is copied on the server thread before HTTP workers read it. */
public final class WebMapServer {
    private final JavaPlugin plugin;
    private final Path output;
    private final AtomicReference<String> worldsJson = new AtomicReference<>("[]");
    private final AtomicReference<String> playersJson = new AtomicReference<>("[]");
    private HttpServer server;

    public WebMapServer(JavaPlugin plugin, Path output) {
        this.plugin = plugin;
        this.output = output.toAbsolutePath().normalize();
    }

    public void start(String host, int port) throws IOException {
        refreshSnapshot();
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshSnapshot, 20L, 40L);
        server = HttpServer.create(new InetSocketAddress(host, port), 32);
        server.setExecutor(Executors.newFixedThreadPool(4, task -> {
            Thread thread = new Thread(task, "MaoMap-Web");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/", this::handle);
        server.start();
        plugin.getLogger().info("MaoMap web map listening on http://" + host + ":" + port);
    }

    private void refreshSnapshot() {
        List<World> loadedWorlds = Bukkit.getWorlds();
        StringBuilder worldJson = new StringBuilder("[");
        boolean first = true;
        for (World world : loadedWorlds) {
            Path tiles = output.resolve(world.getUID().toString()).resolve("tiles");
            if (!Files.isDirectory(tiles)) continue;
            try (Stream<Path> files = Files.list(tiles)) {
                if (files.noneMatch(path -> path.getFileName().toString().endsWith(".png"))) continue;
            } catch (IOException ignored) { continue; }
            if (!first) worldJson.append(',');
            first = false;
            worldJson.append("{\"id\":\"").append(world.getUID()).append("\",\"name\":\"")
                    .append(json(world.getName())).append("\"}");
        }
        worldJson.append(']');
        worldsJson.set(worldJson.toString());

        StringBuilder playerJson = new StringBuilder("[");
        first = true;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!first) playerJson.append(',');
            first = false;
            playerJson.append("{\"id\":\"").append(player.getUniqueId()).append("\",\"name\":\"")
                    .append(json(player.getName())).append("\",\"ping\":").append(Math.max(0, player.getPing()))
                    .append(",\"world\":\"").append(player.getWorld().getUID()).append("\",\"x\":")
                    .append(player.getLocation().getBlockX()).append(",\"z\":")
                    .append(player.getLocation().getBlockZ()).append('}');
        }
        playerJson.append(']');
        playersJson.set(playerJson.toString());
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain; charset=utf-8", "Method not allowed");
            return;
        }
        if (path.equals("/api/worlds")) {
            send(exchange, 200, "application/json; charset=utf-8", worldsJson.get());
        } else if (path.equals("/api/players")) {
            send(exchange, 200, "application/json; charset=utf-8", playersJson.get());
        } else if (path.startsWith("/tiles/") && path.endsWith("/index.json")) {
            serveTileIndex(exchange, path.substring("/tiles/".length(), path.length() - "/index.json".length()));
        } else if (path.startsWith("/tiles/")) {
            serveTile(exchange, path.substring("/tiles/".length()));
        } else {
            String resource = path.equals("/") ? "/web/index.html" : "/web" + path;
            try (InputStream input = plugin.getResource(resource.substring(1))) {
                if (input == null) {
                    send(exchange, 404, "text/plain; charset=utf-8", "Not found");
                    return;
                }
                byte[] bytes = input.readAllBytes();
                String type = resource.endsWith(".css") ? "text/css; charset=utf-8"
                        : resource.endsWith(".js") ? "text/javascript; charset=utf-8" : "text/html; charset=utf-8";
                sendBytes(exchange, 200, type, bytes);
            }
        }
    }

    private void serveTileIndex(HttpExchange exchange, String worldId) throws IOException {
        try {
            UUID.fromString(worldId);
        } catch (IllegalArgumentException invalid) {
            send(exchange, 400, "text/plain; charset=utf-8", "Invalid world id");
            return;
        }
        Path directory = output.resolve(worldId).resolve("tiles").normalize();
        if (!directory.startsWith(output) || !Files.isDirectory(directory)) {
            send(exchange, 200, "application/json; charset=utf-8", "[]");
            return;
        }
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".png"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
                String name = file.getFileName().toString();
                String stem = name.substring(0, name.length() - 4);
                String[] coordinates = stem.split("_", -1);
                if (coordinates.length != 2) continue;
                try {
                    int x = Integer.parseInt(coordinates[0]);
                    int z = Integer.parseInt(coordinates[1]);
                    if (!first) json.append(',');
                    first = false;
                    json.append("{\\\"x\\":").append(x).append(",\\\"z\\":").append(z).append('}');
                } catch (NumberFormatException ignored) { }
            }
        }
        json.append(']');
        send(exchange, 200, "application/json; charset=utf-8", json.toString());
    }

    private void serveTile(HttpExchange exchange, String relative) throws IOException {
        if (relative.contains("..") || relative.contains("\\") || relative.startsWith("/")) {
            send(exchange, 400, "text/plain; charset=utf-8", "Invalid tile path");
            return;
        }
        Path file = output.resolve(relative).normalize();
        if (!file.startsWith(output) || !file.getFileName().toString().endsWith(".png") || !Files.isRegularFile(file)) {
            send(exchange, 404, "text/plain; charset=utf-8", "Tile not found");
            return;
        }
        sendBytes(exchange, 200, "image/png", Files.readAllBytes(file));
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        sendBytes(exchange, status, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", type.equals("image/png") ? "public, max-age=30" : "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) { out.write(body); }
        exchange.close();
    }

    public void stop() {
        if (server != null) server.stop(1);
    }
}
