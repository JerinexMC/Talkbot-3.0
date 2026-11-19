
package com.talkbot.plugin.listener;

import com.talkbot.plugin.TalkBotPlugin;
import com.talkbot.plugin.manager.ChatManager;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;

public final class WhisperListener implements Listener {

    private final TalkBotPlugin plugin;
    private final ChatManager chat;

    public WhisperListener(TalkBotPlugin plugin, ChatManager chat) {
        this.plugin = plugin;
        this.chat = chat;
    }

    @EventHandler(
        priority = EventPriority.MONITOR,
        ignoreCancelled = true
    )
    public void onWhisper(PlayerCommandPreprocessEvent event) {

        String command = event.getMessage();

        if (command == null || !command.startsWith("/")) {
            return;
        }

        String raw = command.substring(1).trim();

        if (raw.isEmpty()) {
            return;
        }

        String[] args = raw.split("\\s+", 3);

        if (args.length < 3) {
            return;
        }

        String commandName = args[0].toLowerCase(Locale.ROOT);

        // Actual private-message commands.
        if (!commandName.equals("w")
                && !commandName.equals("msg")
                && !commandName.equals("tell")
                && !commandName.equals("message")) {
            return;
        }

        String target = args[1];
        String message = args[2].trim();

        if (message.isEmpty()) {
            return;
        }

        Player sender = event.getPlayer();

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!sender.isOnline()) {
                return;
            }

            chat.handleWhisper(sender, target, message);
        });
    }
}
