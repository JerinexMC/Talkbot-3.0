
package com.talkbot.plugin.manager;

import com.talkbot.plugin.TalkBotPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;

public final class BotRegistry {

    private final TalkBotPlugin plugin;
    private final DatabaseManager database;

    private final Set<String> bots =
            new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    public BotRegistry(TalkBotPlugin plugin, DatabaseManager database) {
        this.plugin = plugin;
        this.database = database;

        reload();
    }

    /**
     * Reload registered bots from SQLite.
     *
     * The current registry is only replaced after a successful query.
     */
    public synchronized void reload() {
        Set<String> loadedBots =
                new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        try {
            Connection connection = database.connection();

            try (PreparedStatement statement =
                         connection.prepareStatement(
                                 "SELECT username FROM registered_bots"
                         );
                 ResultSet results = statement.executeQuery()) {

                while (results.next()) {
                    String username = results.getString("username");

                    if (isValidUsername(username)) {
                        loadedBots.add(username);
                    }
                }
            }

            bots.clear();
            bots.addAll(loadedBots);

            plugin.getLogger().info(
                    "Loaded " + bots.size() + " registered TalkBots."
            );

        } catch (SQLException exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Failed to load registered TalkBots. "
                            + "The existing registry was preserved.",
                    exception
            );
        }
    }

    /**
     * Register a bot.
     */
    public synchronized boolean registerBot(String username) {
        if (!isValidUsername(username)) {
            return false;
        }

        if (bots.contains(username)) {
            return false;
        }

        String normalizedUsername = username.trim();

        try {
            Connection connection = database.connection();

            try (PreparedStatement statement =
                         connection.prepareStatement(
                                 "INSERT INTO registered_bots(username) VALUES(?)"
                         )) {

                statement.setString(1, normalizedUsername);

                int affectedRows = statement.executeUpdate();

                if (affectedRows <= 0) {
                    return false;
                }
            }

            bots.add(normalizedUsername);

            plugin.getLogger().info(
                    "Registered TalkBot: " + normalizedUsername
            );

            return true;

        } catch (SQLException exception) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Failed to register TalkBot: " + normalizedUsername,
                    exception
            );

            return false;
        }
    }

    /**
     * Unregister a bot.
     */
    public synchronized boolean unregisterBot(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }

        String normalizedUsername = username.trim();

        try {
            Connection connection = database.connection();

            try (PreparedStatement statement =
                         connection.prepareStatement(
                                 "DELETE FROM registered_bots "
                                         + "WHERE username = ? COLLATE NOCASE"
                         )) {

                statement.setString(1, normalizedUsername);

                int affectedRows = statement.executeUpdate();

                if (affectedRows <= 0) {
                    return false;
                }
            }

            bots.remove(normalizedUsername);

            plugin.getLogger().info(
                    "Unregistered TalkBot: " + normalizedUsername
            );

            return true;

        } catch (SQLException exception) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Failed to unregister TalkBot: " + normalizedUsername,
                    exception
            );

            return false;
        }
    }

    /**
     * Check whether a player is a registered bot.
     */
    public synchronized boolean isBot(String username) {
        return username != null && bots.contains(username);
    }

    /**
     * Return a read-only snapshot of registered bots.
     */
    public synchronized Set<String> getRegisteredBots() {
        Set<String> copy =
                new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        copy.addAll(bots);

        return Collections.unmodifiableSet(copy);
    }

    /**
     * Retrieve a bot's saved personality.
     */
    public synchronized String getStyle(String username) {
        if (username != null && !username.isBlank()) {
            try {
                Connection connection = database.connection();

                try (PreparedStatement statement =
                             connection.prepareStatement(
                                     "SELECT style FROM bot_styles "
                                             + "WHERE username = ? COLLATE NOCASE"
                             )) {

                    statement.setString(1, username.trim());

                    try (ResultSet results = statement.executeQuery()) {
                        if (results.next()) {
                            String style = results.getString("style");

                            if (style != null && !style.isBlank()) {
                                return style.trim().toLowerCase(Locale.ROOT);
                            }
                        }
                    }
                }

            } catch (SQLException exception) {
                plugin.getLogger().log(
                        Level.WARNING,
                        "Failed to retrieve personality for " + username,
                        exception
                );
            }
        }

        return plugin.getConfig().getString(
                "personality.default-style",
                "chill"
        );
    }

    /**
     * Save or update a bot's personality.
     */
    public synchronized boolean setStyle(
            String username,
            String style
    ) {
        if (!isValidUsername(username)
                || style == null
                || style.isBlank()) {
            return false;
        }

        String normalizedUsername = username.trim();
        String normalizedStyle = style.trim().toLowerCase(Locale.ROOT);

        try {
            Connection connection = database.connection();

            try (PreparedStatement statement =
                         connection.prepareStatement(
                                 "INSERT INTO bot_styles(username, style) "
                                         + "VALUES(?, ?) "
                                         + "ON CONFLICT(username) "
                                         + "DO UPDATE SET style = excluded.style"
                         )) {

                statement.setString(1, normalizedUsername);
                statement.setString(2, normalizedStyle);

                return statement.executeUpdate() > 0;
            }

        } catch (SQLException exception) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Failed to save personality for " + normalizedUsername,
                    exception
            );

            return false;
        }
    }

    /**
     * Validate a Minecraft Java username.
     */
    private static boolean isValidUsername(String username) {
        return username != null
                && username.matches("[A-Za-z0-9_]{3,16}");
    }
}
