package com.talkbot.plugin.voice;

import com.talkbot.plugin.TalkBotPlugin;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TalkBotVoicechatPlugin implements VoicechatPlugin {

    private static TalkBotPlugin pluginInstance;
    private final Map<UUID, Long> lastVoiceTriggerMs = new ConcurrentHashMap<>();

    public static void attachPlugin(TalkBotPlugin plugin) {
        pluginInstance = plugin;
        BukkitVoicechatService service = Bukkit.getServicesManager().load(BukkitVoicechatService.class);
        if (service != null && plugin != null) {
            plugin.getLogger().info("Simple Voice Chat service detected on server.");
        }
    }

    @Override
    public String getPluginId() {
        return "talkbot_vc";
    }

    @Override
    public void initialize(VoicechatServerApi api) {
        if (pluginInstance != null) {
            pluginInstance.getLogger().info("TalkBot hooked into Simple Voice Chat API.");
        }
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
    }

    private void onMicrophonePacket(MicrophonePacketEvent event) {
        if (pluginInstance == null) {
            return;
        }

        VoicechatConnection senderConn = event.getSenderConnection();
        if (senderConn == null || senderConn.getPlayer() == null) {
            return;
        }

        Object playerObj = senderConn.getPlayer().getPlayer();
        if (!(playerObj instanceof Player player) || !player.isOnline()) {
            return;
        }

        long now = System.currentTimeMillis();
        long prev = lastVoiceTriggerMs.getOrDefault(player.getUniqueId(), 0L);
        if (now - prev < 6000L) {
            return;
        }
        lastVoiceTriggerMs.put(player.getUniqueId(), now);

        boolean whispering = event.getPacket().isWhispering();

        Bukkit.getScheduler().runTask(pluginInstance, () -> {
            if (player.isOnline() && pluginInstance.getChatManager() != null) {
                pluginInstance.getChatManager().handleVoiceActivity(player, whispering);
            }
        });
    }
}
