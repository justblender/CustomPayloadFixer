package ru.justblender.bukkit;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.utility.StreamSerializer;
import com.comphenix.protocol.wrappers.nbt.NbtCompound;
import com.comphenix.protocol.wrappers.nbt.NbtFactory;
import com.comphenix.protocol.wrappers.nbt.NbtList;
import com.google.common.base.Charsets;
import io.netty.buffer.ByteBuf;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
public class CustomPayloadFixer extends JavaPlugin {

    private static final Map<Player, Long> PACKET_USAGE = new ConcurrentHashMap<>();

    private String dispatchCommand, kickMessage;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();

        this.dispatchCommand = this.getConfig().getString("dispatchCommand");
        this.kickMessage = this.getConfig().getString("kickMessage");

        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(this, PacketType.Play.Client.CUSTOM_PAYLOAD) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                checkPacket(event);
            }
        });

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Iterator<Map.Entry<Player, Long>> iterator = PACKET_USAGE.entrySet().iterator(); iterator.hasNext(); ) {
                Player player = iterator.next().getKey();
                if (!player.isOnline() || !player.isValid())
                    iterator.remove();
            }
        }, 20L, 20L);
    }

    @Override
    public void onDisable() {
        ProtocolLibrary.getProtocolManager().removePacketListeners(this);
    }

    private void checkPacket(PacketEvent event) {
        Player player = event.getPlayer();
        long lastPacket = PACKET_USAGE.getOrDefault(player, -1L);

        // This fucker is already detected as an exploiter
        if (lastPacket == -2L) {
            event.setCancelled(true);
            return;
        }

        String name = event.getPacket().getStrings().readSafely(0);
        if (!"MC|BSign".equals(name) && !"MC|BEdit".equals(name) && !"REGISTER".equals(name))
            return;

        try {
            if ("REGISTER".equals(name)) {
                checkChannels(event);
            } else {
                if (elapsed(lastPacket, 100L)) {
                    PACKET_USAGE.put(player, System.currentTimeMillis());
                } else {
                    throw new IOException("Packet flood");
                }

                checkNbtTags(event);
            }
        } catch (Throwable ex) {
            // Set last packet usage to -2 so we wouldn't mind checking him again
            PACKET_USAGE.put(player, -2L);

            Bukkit.getScheduler().runTask(this, () -> {
                player.kickPlayer(kickMessage);

                if (dispatchCommand != null)
                    getServer().dispatchCommand(Bukkit.getConsoleSender(),
                            dispatchCommand.replace("%name%", player.getName()));
            });

            getLogger().warning(player.getName() + " tried to exploit CustomPayload packet");
            event.setCancelled(true);
        }
    }

    @SuppressWarnings("deprecation")
    private void checkNbtTags(PacketEvent event) throws IOException {
        PacketContainer container = event.getPacket();
        ByteBuf buffer = container.getSpecificModifier(ByteBuf.class).read(0).copy();

        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.readBytes(bytes);

        DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
        ItemStack itemStack = StreamSerializer.getDefault().deserializeItemStack(input);

        try {
            if (itemStack == null)
                throw new IOException("Unable to deserialize ItemStack");

            NbtCompound root = (NbtCompound) NbtFactory.fromItemTag(itemStack);
            if (root == null) {
                throw new IOException("No NBT tag?!");
            } else if (!root.containsKey("pages")) {
                throw new IOException("No 'pages' NBT compound was found");
            } else {
                NbtList<String> pages = root.getList("pages");
                if (pages.size() > 50)
                    throw new IOException("Too much pages");

                // Here comes the funny part - Minecraft Wiki says that book allows to have only 256 symbols per page,
                // but in reality it actually can get up to 257. What a jerks. (tested on 1.8.9)

                for (String page : pages)
                    if (page.length() > 257)
                        throw new IOException("A very long page");
            }
        } finally {
            input.close();
            buffer.release();
        }
    }

    private void checkChannels(PacketEvent event) throws Exception {
        int channelsSize = event.getPlayer().getListeningPluginChannels().size();

        PacketContainer container = event.getPacket();
        ByteBuf buffer = container.getSpecificModifier(ByteBuf.class).read(0).copy();

        try {
            for (int i = 0; i < buffer.toString(Charsets.UTF_8).split("\0").length; i++)
                if (++channelsSize > 124)
                    throw new IOException("Too much channels");
        } finally {
            buffer.release();
        }
    }

    private boolean elapsed(long from, long required) {
        return from == -1L || System.currentTimeMillis() - from > required;
    }
}
