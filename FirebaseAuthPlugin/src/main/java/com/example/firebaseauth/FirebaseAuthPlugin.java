package com.example.firebaseauth;

import javax.inject.Inject;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import net.kyori.adventure.text.Component;

import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

@Plugin(
        id = "firebaseauth",
        name = "FirebaseAuthPlugin",
        version = "1.0.0"
)
public class FirebaseAuthPlugin {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private String firebaseUrl;
    private String authSecret;
    private Set<String> restrictedServers;
    private String needLinkMessage;
    private String fallbackServer;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Inject
    public FirebaseAuthPlugin(
            ProxyServer server,
            Logger logger,
            @DataDirectory Path dataDirectory
    ) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    // ★ これが無いのが原因だった
    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        loadConfig();
        logger.info("FirebaseAuthPlugin initialized");
    }

    private void loadConfig() {
        try {
            if (Files.notExists(dataDirectory)) {
                Files.createDirectories(dataDirectory);
            }

            Path configPath = dataDirectory.resolve("config.conf");

            if (Files.notExists(configPath)) {
                Files.writeString(configPath, DEFAULT_CONFIG);
                logger.info("config.conf を生成しました");
            }

            HoconConfigurationLoader loader =
                    HoconConfigurationLoader.builder()
                            .path(configPath)
                            .build();

            ConfigurationNode root = loader.load();

            firebaseUrl = root.node("firebase", "url").getString("");
            authSecret = root.node("firebase", "auth-secret").getString("");

            restrictedServers =
                    root.node("restricted-servers")
                            .getList(String.class, java.util.List.of())
                            .stream()
                            .collect(Collectors.toSet());

            needLinkMessage =
                    root.node("messages", "need-link")
                            .getString("Discord 連携が必要です");

            fallbackServer =
                    root.node("messages", "fallback-server")
                            .getString("lobby");

        } catch (Exception e) {
            logger.error("config 読み込み失敗", e);
        }
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        String serverName = event.getServer().getServerInfo().getName();

        if (!restrictedServers.contains(serverName)) {
            return;
        }

        executor.execute(() -> {
            boolean registered = checkRegistered(player);

            if (!registered) {
                player.sendMessage(Component.text(needLinkMessage));
                server.getCommandManager()
                        .executeAsync(player, "server " + fallbackServer);
            }
        });
    }

    private boolean checkRegistered(Player player) {
        try {
            boolean isBedrock =
                    player.getUniqueId().toString().startsWith("00000000-0000-0000");

            String platform = isBedrock ? "Bedrock" : "Java";
            String username = player.getUsername();

            String url = firebaseUrl + "/users.json?auth=" + authSecret;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            return response.body().contains(
                    "\"" + platform + "\":\"" + username + "\""
            );

        } catch (Exception e) {
            logger.error("Firebase チェック失敗", e);
            return false;
        }
    }

    private static final String DEFAULT_CONFIG = """
            firebase {
              url = ""
              auth-secret = "PUT_YOUR_SECRET_HERE"
            }

            restricted-servers = [
              "vip1",
              "vip2"
            ]

            messages {
              need-link = "VIP サーバーに入るには Discord 連携が必要です。"
              fallback-server = "lobby"
            }
            """;
}
