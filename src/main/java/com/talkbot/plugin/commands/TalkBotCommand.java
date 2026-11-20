package com.talkbot.plugin.commands;

import com.talkbot.plugin.TalkBotPlugin;
import com.talkbot.plugin.manager.BotRegistry;
import com.talkbot.plugin.manager.ChatManager;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class TalkBotCommand implements CommandExecutor, TabCompleter {

    private final TalkBotPlugin plugin;
    private final BotRegistry registry;
    private final ChatManager chat;

    public TalkBotCommand(TalkBotPlugin plugin, BotRegistry registry, ChatManager chat) {
        this.plugin = plugin;
        this.registry = registry;
        this.chat = chat;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("talkbot.admin")) {
            sender.sendMessage("You don't have permission.");
            return true;
        }

        if (args.length == 0) {
            return usage(sender);
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "register" -> {
                if (args.length < 2) {
                    return usage(sender);
                }
                sender.sendMessage(registry.registerBot(args[1])
                        ? "Registered " + args[1]
                        : "Could not register bot.");
            }
            case "unregister" -> {
                if (args.length < 2) {
                    return usage(sender);
                }
                sender.sendMessage(registry.unregisterBot(args[1])
                        ? "Unregistered " + args[1]
                        : "Bot not registered.");
            }
            case "list" -> sender.sendMessage("Registered bots: " + registry.getRegisteredBots());
            case "style" -> {
                if (args.length < 3) {
                    return usage(sender);
                }
                String style = args[2].toLowerCase(Locale.ROOT);
                if (!ChatManager.styles().contains(style)) {
                    sender.sendMessage("Styles: " + ChatManager.styles());
                    return true;
                }
                if (!registry.isBot(args[1])) {
                    sender.sendMessage("Register that bot first.");
                    return true;
                }
                sender.sendMessage(registry.setStyle(args[1], style)
                        ? args[1] + " style set to " + style
                        : "Could not save style.");
            }
            case "tell" -> {
                if (args.length < 3) {
                    sender.sendMessage("Usage: /talkbot tell <bot> <directive/message>");
                    return true;
                }
                String botName = args[1];
                if (!registry.isBot(botName)) {
                    sender.sendMessage("Register that bot first.");
                    return true;
                }
                String directive = String.join(" ", Arrays.copyOfRange(args, 2, args.length)).trim();
                chat.tellBot(botName, directive);
                sender.sendMessage("Sent directive to " + botName + ": " + directive);
            }
            case "memory" -> handleMemoryCommand(sender, args);
            case "reload" -> {
                plugin.reloadTalkBot();
                sender.sendMessage("TalkBot config reloaded.");
            }
            case "status" -> sender.sendMessage(
                    "Registered bots: " + registry.getRegisteredBots().size()
                            + " | AI enabled: " + plugin.getConfig().getBoolean("ai.enabled")
            );
            case "test" -> {
                if (args.length < 2) {
                    return usage(sender);
                }
                Player p = Bukkit.getPlayerExact(args[1]);
                if (p == null) {
                    sender.sendMessage("No online Bukkit player: " + args[1]);
                    return true;
                }
                sender.sendMessage("Found " + p.getName() + " (" + p.getClass().getName() + ")");
                if (args.length > 2 && args[2].equalsIgnoreCase("confirm")) {
                    p.chat("TalkBot diagnostic message");
                    sender.sendMessage("Player#chat called; check global chat.");
                }
            }
            default -> {
                return usage(sender);
            }
        }

        return true;
    }

    private void handleMemoryCommand(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("Usage: /talkbot memory <add|show|delete> <bot> [text|index]");
            return;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        String botName = args[2];

        if (!registry.isBot(botName)) {
            sender.sendMessage("Register that bot first.");
            return;
        }

        switch (action) {
            case "add" -> {
                if (args.length < 4) {
                    sender.sendMessage("Usage: /talkbot memory add <bot> <memory text>");
                    return;
                }
                String memoryText = String.join(" ", Arrays.copyOfRange(args, 3, args.length)).trim();
                boolean saved = registry.addMemory(botName, memoryText);
                sender.sendMessage(saved
                        ? "Saved memory for " + botName + ": " + memoryText
                        : "Could not save memory.");
            }
            case "show" -> {
                List<BotRegistry.MemoryEntry> memories = registry.getMemories(botName);
                if (memories.isEmpty()) {
                    sender.sendMessage("No memories saved for " + botName + ".");
                    return;
                }
                sender.sendMessage("Memories for " + botName + ":");
                for (int i = 0; i < memories.size(); i++) {
                    sender.sendMessage((i + 1) + ". " + memories.get(i).memory());
                }
            }
            case "delete" -> {
                if (args.length < 4) {
                    sender.sendMessage("Usage: /talkbot memory delete <bot> <number>");
                    return;
                }
                int index;
                try {
                    index = Integer.parseInt(args[3]);
                } catch (NumberFormatException ex) {
                    sender.sendMessage("Memory index must be a number.");
                    return;
                }
                boolean deleted = registry.deleteMemory(botName, index);
                sender.sendMessage(deleted
                        ? "Deleted memory #" + index + " for " + botName + "."
                        : "Invalid memory index.");
            }
            default -> sender.sendMessage("Usage: /talkbot memory <add|show|delete> <bot> [text|index]");
        }
    }

    private boolean usage(CommandSender sender) {
        sender.sendMessage("/talkbot <register|unregister|list|style|tell|memory|reload|test|status>");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("register", "unregister", "list", "style", "tell", "memory", "reload", "test", "status");
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("unregister") || sub.equals("test") || sub.equals("style") || sub.equals("tell")) {
                return new ArrayList<>(registry.getRegisteredBots());
            }
            if (sub.equals("memory")) {
                return List.of("add", "show", "delete");
            }
        }
        if (args.length == 3) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("style")) {
                return new ArrayList<>(ChatManager.styles());
            }
            if (sub.equals("memory")) {
                return new ArrayList<>(registry.getRegisteredBots());
            }
        }
        return List.of();
    }
}
