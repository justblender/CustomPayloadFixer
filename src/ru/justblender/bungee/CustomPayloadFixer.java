package ru.justblender.bungee;

import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.Connection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import net.md_5.bungee.event.EventHandler;
import com.google.common.base.Charsets;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ****************************************************************
 * Copyright JustBlender (c) 2017. All rights reserved.
 * A code contained within this document, and any associated APIs with similar branding
 * are the sale property of JustBlender. Distribution, reproduction, taking snippets, or
 * claiming any contents as your own will break the terms of the license, and void any
 * agreements with you, the third party.
 * Thanks!
 * ****************************************************************
 */
public class CustomPayloadFixer extends Plugin implements Listener {

    private static final Map<Connection, Long> PACKET_USAGE = new ConcurrentHashMap<>();
    private static final Map<Connection, AtomicInteger> CHANNELS_REGISTERED = new ConcurrentHashMap<>();

    private String dispatchCommand, kickMessage;
    private boolean ignoreForge;

    @Override
    public void onEnable() {
        try {
            Configuration configuration = this.loadConfiguration();

            this.dispatchCommand = configuration.getString("dispatchCommand");
            this.ignoreForge = configuration.getBoolean("ignoreForge", false);
            this.kickMessage = configuration.getString("kickMessage");
        } catch (IOException e) {
            getLogger().severe("Unable to load configuration file, plugin isn't going to work");
            return;
        }

        this.getProxy().getPluginManager().registerListener(this, this);
    }

    @EventHandler
    public void onPacket(PluginMessageEvent event) {
        String name = event.getTag();
        if (!"MC|BSign".equals(name) && !"MC|BEdit".equals(name) && !"REGISTER".equals(name))
            return;

        Connection connection = event.getSender();
        if (!(connection instanceof ProxiedPlayer))
            return;

        try {
            if ("REGISTER".equals(name)) {
                if (!CHANNELS_REGISTERED.containsKey(connection))
                    CHANNELS_REGISTERED.put(connection, new AtomicInteger());

                for (int i = 0; i < new String(event.getData(), Charsets.UTF_8).split("\0").length; i++)
                    if (CHANNELS_REGISTERED.get(connection).incrementAndGet() > 124)
                        throw new IOException("Too many channels");
            } else {
                if (elapsed(PACKET_USAGE.getOrDefault(connection, -1L), 100L)) {
                    PACKET_USAGE.put(connection, System.currentTimeMillis());
                } else {
                    throw new IOException("Packet flood");
                }
            }
        } catch (Throwable ex) {
            connection.disconnect(TextComponent.fromLegacyText(kickMessage));

            if (dispatchCommand != null)
                getProxy().getPluginManager().dispatchCommand(getProxy().getConsole(),
                        dispatchCommand.replace("%name%", ((ProxiedPlayer) connection).getName()));

            getLogger().warning(connection.getAddress() + " tried to exploit CustomPayload packet");
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent event) {
        CHANNELS_REGISTERED.remove(event.getPlayer());
        PACKET_USAGE.remove(event.getPlayer());
    }

    private Configuration loadConfiguration() throws IOException {
        if (!getDataFolder().exists())
            getDataFolder().mkdir();

        File file = new File(getDataFolder(), "config.yml");
        if (!file.exists()) {
            try (InputStream in = getResourceAsStream("config.yml")) {
                Files.copy(in, file.toPath());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return ConfigurationProvider.getProvider(YamlConfiguration.class).load(file);
    }

    private boolean elapsed(long from, long required) {
        return from == -1L || System.currentTimeMillis() - from > required;
    }
}
