package com.talkbot.plugin.manager;

import com.talkbot.plugin.TalkBotPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;

public final class DatabaseManager {

    private final TalkBotPlugin plugin;
    private Connection connection;

    public DatabaseManager(TalkBotPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized boolean open() {
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                throw new SQLException("Could not create data folder");
            }

            String filename = plugin.getConfig().getString("database.filename", "talkbot.db");
            File dbFile = new File(plugin.getDataFolder(), filename);

            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS registered_bots("
                                + "username TEXT PRIMARY KEY NOT NULL)"
                );
                statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS bot_styles("
                                + "username TEXT PRIMARY KEY NOT NULL, "
                                + "style TEXT NOT NULL)"
                );
                statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS bot_memories("
                                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                                + "username TEXT NOT NULL, "
                                + "memory TEXT NOT NULL, "
                                + "created_at INTEGER NOT NULL)"
                );
            }

            return true;
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "SQLite initialization failed", exception);
            close();
            return false;
        }
    }

    public synchronized Connection connection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            throw new SQLException("Database unavailable");
        }
        return connection;
    }

    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.WARNING, "SQLite close failed", exception);
            } finally {
                connection = null;
            }
        }
    }
}
