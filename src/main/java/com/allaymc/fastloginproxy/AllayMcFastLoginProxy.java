package com.allaymc.fastloginproxy;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Plugin(
        id = "allaymcfastloginproxy",
        name = "AllayMcFastLoginProxy",
        version = "1.0.0",
        authors = {"AllayMc"}
)
public class AllayMcFastLoginProxy {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private MojangLookupService mojangLookupService;
    private ProxyDatabase database;

    private String backendServerName = "lobby";
    private String pluginChannel = "allaymc:auth";
    private int verificationWindowSeconds = 20;
    private String reconnectKickMessage = "Verification complete. Reconnect now.";

    private final Map<String, Long> verificationMap = new ConcurrentHashMap<>();
    private final Map<UUID, AuthMode> resolvedModes = new ConcurrentHashMap<>();

    @Inject
    public AllayMcFastLoginProxy(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(com.velocitypowered.api.event.proxy.ProxyInitializeEvent event) {
        try {
            Files.createDirectories(dataDirectory);

            Path configPath = dataDirectory.resolve("config.properties");
            if (!Files.exists(configPath)) {
                Files.writeString(configPath, """
                        backend-server-name=lobby
                        plugin-message-channel=allaymc:auth
                        verification-window-seconds=20
                        reconnect-kick-message=Verification complete. Reconnect now.
                        mojang-timeout-ms=1500
                        """, StandardCharsets.UTF_8);
            }

            Properties properties = new Properties();
            try (var reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }

            backendServerName = properties.getProperty("backend-server-name", "lobby");
            pluginChannel = properties.getProperty("plugin-message-channel", "allaymc:auth");
            verificationWindowSeconds = Integer.parseInt(properties.getProperty("verification-window-seconds", "20"));
            reconnectKickMessage = properties.getProperty("reconnect-kick-message", "Verification complete. Reconnect now.");
            int timeoutMs = Integer.parseInt(properties.getProperty("mojang-timeout-ms", "1500"));

            mojangLookupService = new MojangLookupService(timeoutMs);
            database = new ProxyDatabase(dataDirectory);
            database.connect();

            proxy.getChannelRegistrar().register(MinecraftChannelIdentifier.from(pluginChannel));
            logger.info("AllayMcFastLoginProxy enabled.");
        } catch (Exception e) {
            logger.error("Failed to initialize AllayMcFastLoginProxy", e);
        }
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        String username = player.getUsername();
        long now = System.currentTimeMillis();

        Long firstSeen = verificationMap.get(username);

        if (firstSeen == null || (now - firstSeen) > verificationWindowSeconds * 1000L) {
            verificationMap.put(username, now);
            player.disconnect(Component.text(reconnectKickMessage));
        }
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        String username = player.getUsername();

        Long firstSeen = verificationMap.get(username);
        if (firstSeen == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if ((now - firstSeen) > verificationWindowSeconds * 1000L) {
            verificationMap.remove(username);
            return;
        }

        AuthMode mode = mojangLookupService.isPremiumName(username) ? AuthMode.PREMIUM : AuthMode.CRACKED;
        resolvedModes.put(player.getUniqueId(), mode);

        if (mode == AuthMode.PREMIUM) {
            try (PreparedStatement ps = database.getConnection().prepareStatement("""
                    INSERT INTO premium_profiles(username, premium, last_verified_at)
                    VALUES (?, 1, ?)
                    ON CONFLICT(username) DO UPDATE SET
                        premium = 1,
                        last_verified_at = excluded.last_verified_at
                    """)) {
                ps.setString(1, username);
                ps.setLong(2, now);
                ps.executeUpdate();
            } catch (Exception e) {
                logger.warn("Failed to save premium profile", e);
            }
        }

        Optional<RegisteredServer> currentServer = player.getCurrentServer().map(conn -> conn.getServer());
        if (currentServer.isPresent()) {
            String payload = "AUTH|" + username + "|" + player.getUniqueId() + "|" + mode.name();
            currentServer.get().sendPluginMessage(
                    MinecraftChannelIdentifier.from(pluginChannel),
                    payload.getBytes(StandardCharsets.UTF_8)
            );
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        resolvedModes.remove(event.getPlayer().getUniqueId());
    }
}
