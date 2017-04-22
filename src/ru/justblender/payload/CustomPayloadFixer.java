package ru.justblender.payload;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.utility.StreamSerializer;
import com.comphenix.protocol.wrappers.nbt.NbtCompound;
import com.comphenix.protocol.wrappers.nbt.NbtFactory;
import com.comphenix.protocol.wrappers.nbt.NbtList;
import io.netty.buffer.ByteBuf;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Iterator;
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
                String name = event.getPacket().getStrings().read(0);
                if ("MC|BSign".equals(name) || "MC|BEdit".equals(name))
                    checkForFlood(event);
            }
        });

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            Iterator<Map.Entry<Player, Long>> iterator = PACKET_USAGE.entrySet().iterator();
            while (iterator.hasNext()) {
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

    @SuppressWarnings("deprecation")
    private void checkForFlood(PacketEvent event) {
        Player player = event.getPlayer();

        if (!elapsed(PACKET_USAGE.getOrDefault(player, -1L), 20L)) {
            PACKET_USAGE.put(player, System.currentTimeMillis());
        } else {
            try {
                PacketContainer container = event.getPacket();

                ByteBuf buffer = container.getSpecificModifier(ByteBuf.class).read(0);
                byte[] bytes = new byte[buffer.readableBytes()];
                buffer.readBytes(bytes);

                DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
                ItemStack itemStack = StreamSerializer.getDefault().deserializeItemStack(input);

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

                    for (String page : pages)
                        if (page.length() > 256)
                            throw new IOException("A very long page");
                }
            } catch (IOException ex) {
                Bukkit.getScheduler().runTask(this, () -> {
                    if (dispatchCommand != null) {
                        getServer().dispatchCommand(Bukkit.getConsoleSender(), dispatchCommand);
                    } else {
                        player.kickPlayer(kickMessage);
                    }
                });

                getLogger().warning(player.getName() + " tried to flood with CUSTOM_PAYLOAD packet");
                event.setCancelled(true);
            }
        }
    }

    private boolean elapsed(long from, long required) {
        return from != -1L && System.currentTimeMillis() - from > required;
    }
}
