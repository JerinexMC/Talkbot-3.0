
package com.talkbot.plugin.listener;

import com.talkbot.plugin.TalkBotPlugin;
import com.talkbot.plugin.manager.ChatManager;

import org.bukkit.Bukkit;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@SuppressWarnings("deprecation")
public final class ChatListener implements Listener {

    private final TalkBotPlugin plugin;
    private final ChatManager chat;

    public ChatListener(TalkBotPlugin plugin, ChatManager chat) {
        this.plugin = plugin;
        this.chat = chat;
    }

    // --------------------------------------------------
    // NORMAL PLAYER AND BOT CHAT
    // --------------------------------------------------

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }

            if (plugin.getBotRegistry().isBot(player.getName())) {
                chat.handleBotMessage(player, message);
            } else {
                chat.handlePlayerMessage(player, message);
            }
        });
    }

    // --------------------------------------------------
    // WHISPER COMMANDS
    // --------------------------------------------------

    @EventHandler(
            priority = EventPriority.LOWEST,
            ignoreCancelled = true
    )
    public void onWhisperCommand(PlayerCommandPreprocessEvent event) {
        String fullCommand = event.getMessage();

        if (fullCommand == null || fullCommand.length() < 2
                || fullCommand.charAt(0) != '/') {
            return;
        }

        String raw = fullCommand.substring(1).trim();

        if (raw.isEmpty()) {
            return;
        }

        String[] arguments = raw.split("\\s+", 3);

        if (arguments.length < 3) {
            return;
        }

        String commandName = arguments[0]
                .toLowerCase(Locale.ROOT);

        // Default Minecraft/private-message aliases.
        List<String> aliases = new ArrayList<>(List.of(
                "w",
                "msg",
                "tell",
                "message",
                "whisper"
        ));

        // Additional aliases from config.yml.
        List<String> configuredAliases =
                plugin.getConfig().getStringList("chat.whisper-aliases");

        if (configuredAliases != null) {
            for (String alias : configuredAliases) {
                if (alias != null && !alias.isBlank()) {
                    aliases.add(
                            alias.replace("/", "")
                                    .trim()
                                    .toLowerCase(Locale.ROOT)
                    );
                }
            }
        }

        String configuredCommand = plugin.getConfig().getString(
                "chat.whisper-command",
                "whisper"
        );

        if (configuredCommand != null && !configuredCommand.isBlank()) {
            aliases.add(
                    configuredCommand.replace("/", "")
                            .trim()
                            .toLowerCase(Locale.ROOT)
            );
        }

        if (!aliases.contains(commandName)) {
            return;
        }

        String target = arguments[1];
        String message = arguments[2].trim();

        if (message.isEmpty()) {
            return;
        }

        Player sender = event.getPlayer();

        // Run on the main server thread.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!sender.isOnline()) {
                return;
            }

            chat.handleWhisper(sender, target, message);
        });
    }

    // --------------------------------------------------
    // BOT DAMAGE REACTIONS
    // --------------------------------------------------

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        if (!plugin.getBotRegistry().isBot(player.getName())) {
            return;
        }

        boolean visible = player.getNearbyEntities(8, 5, 8)
                .stream()
                .anyMatch(entity ->
                        entity instanceof Player
                                || entity instanceof Monster
                );

        if (!visible) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    chat.onDamage(player, false);
                }
            });
        }

        if (player.getHealth() - event.getFinalDamage() <= 3.0) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    chat.onLowHealth(player);
                }
            });
        }
    }

    // --------------------------------------------------
    // BOT DEATH
    // --------------------------------------------------

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();

        if (!plugin.getBotRegistry().isBot(player.getName())) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            chat.onDeath(player);
        });
    }

    // --------------------------------------------------
    // MOLE DETECTION
    // --------------------------------------------------

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();

        if (!plugin.getBotRegistry().isBot(player.getName())) {
            return;
        }

        Material material = event.getBlockPlaced().getType();

        if (!plugin.getConfig().getStringList("chat.mole-materials")
                .contains(material.name())) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                chat.onMole(player, material.name());
            }
        });
    }
}
