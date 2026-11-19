
package com.talkbot.plugin;

import com.talkbot.plugin.commands.TalkBotCommand;
import com.talkbot.plugin.listener.ChatListener;
import com.talkbot.plugin.manager.BotRegistry;
import com.talkbot.plugin.manager.ChatManager;
import com.talkbot.plugin.manager.DatabaseManager;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class TalkBotPlugin extends JavaPlugin {

    private DatabaseManager database;
    private BotRegistry registry;
    private ChatManager chat;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Initialize database
        database = new DatabaseManager(this);

        if (!database.open()) {
            getLogger().severe("Could not open TalkBot database. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Initialize bot systems
        registry = new BotRegistry(this, database);
        chat = new ChatManager(this, registry);

        // Register command
        PluginCommand command = getCommand("talkbot");

        if (command == null) {
            getLogger().severe("Missing talkbot command in plugin.yml");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        TalkBotCommand executor = new TalkBotCommand(this, registry, chat);

        command.setExecutor(executor);
        command.setTabCompleter(executor);

        // Register listeners
        getServer().getPluginManager().registerEvents(
            new ChatListener(this, chat),
            this
        );

        getLogger().info(
            "TalkBot 3.0 enabled; registered bots: "
            + registry.getRegisteredBots().size()
        );
    }

    public void reloadTalkBot() {
        reloadConfig();

        if (registry != null) {
            registry.reload();
        }

        if (chat != null) {
            chat.reload();
        }

        getLogger().info("TalkBot configuration reloaded.");
    }

    @Override
    public void onDisable() {
        if (chat != null) {
            chat.shutdown();
        }

        if (database != null) {
            database.close();
        }
    }

    public BotRegistry getBotRegistry() {
        return registry;
    }
}
