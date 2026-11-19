
package com.talkbot.plugin.manager;

import com.talkbot.plugin.TalkBotPlugin;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;

public final class BotRegistry {

    private final TalkBotPlugin plugin;
    private final DatabaseManager db;

    private final Set<String> bots =
        new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    public BotRegistry(TalkBotPlugin plugin, DatabaseManager database) {
        this.plugin = plugin;
        this.db = database;

        reload();
    }

    /**
     * Reloads registered bots from the database.
     */
    public synchronized void reload() {
        Set<String> loadedBots =
            new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        try (PreparedStatement statement =
                 db.connection().prepareStatement(
                     "SELECT username FROM registered_bots"
                 );
             ResultSet results = statement.executeQuery()) {

            while (results.next()) {
                String username = results.getString("username");

                if (valid(username)) {
                    loadedBots.add(username);
                }
            }

            bots.clear();
            bots.addAll(loadedBots);

        } catch (SQLException exception) {
            plugin.getLogger().log(
                Level.SEVERE,
                "Could not load registered bots from the database.",
                exception
            );
        }
    }

    /**
     * Registers a bot.
     */
    public synchronized boolean registerBot(String username) {
        if (!valid(username)) {
            return false;
        }

        if (bots.contains(username)) {
            return false;
        }

        try (PreparedStatement statement =
                 db.connection().prepareStatement(
                     "INSERT INTO registered_bots(username) VALUES(?)"
                 )) {

            statement.setString(1, username);
            statement.executeUpdate();

            bots.add(username);

            plugin.getLogger().info(
                "Registered bot: " + username
            );

            return true;

        } catch (SQLException exception) {
            plugin.getLogger().log(
                Level.WARNING,
                "Could not register bot " + username,
                exception
            );

            return false;
        }
    }

    /**
     * Unregisters a bot.
     */
    public synchronized boolean unregisterBot(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }

        try (PreparedStatement statement =
                 db.connection().prepareStatement(
                     "DELETE FROM registered_bots "
                     + "WHERE username = ? COLLATE NOCASE"
                 )) {

            statement.setString(1, username);

            int affectedRows = statement.executeUpdate();

            if (affectedRows == 0) {
                return false;
            }

            bots.remove(username);

            plugin.getLogger().info(
                "Unregistered bot: " + username
            );

            return true;

        } catch (SQLException exception) {
            plugin.getLogger().log(
                Level.WARNING,
                "Could not unregister bot " + username,
                exception
            );

            return false;
        }
    }

    /**
     * Checks whether a username belongs to a registered bot.
     */
    public synchronized boolean isBot(String username) {
        return username != null && bots.contains(username);
    }

    /**
     * Returns a safe, read-only copy of registered bots.
     */
    public synchronized Set<String> getRegisteredBots() {
        Set<String> copy =
            new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        copy.addAll(bots);

        return Collections.unmodifiableSet(copy);
    }

    /**
     * Gets a bot's personality style.
     */
    public synchronized String getStyle(String username) {
        if (username != null && !username.isBlank()) {

            try (PreparedStatement statement =
                     db.connection().prepareStatement(
                         "SELECT style FROM bot_styles "
                         + "WHERE username = ? COLLATE NOCASE"
                     )) {

                statement.setString(1, username);

                try (ResultSet results = statement.executeQuery()) {
                    if (results.next()) {
                        String style = results.getString("style");

                        if (style != null && !style.isBlank()) {
                            return style;
                        }
                    }
                }

            } catch (SQLException exception) {
                plugin.getLogger().log(
                    Level.WARNING,
                    "Could not retrieve personality for " + username,
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
     * Saves or updates a bot's personality style.
     */
    public synchronized boolean setStyle(
        String username,
        String style
    ) {
        if (!valid(username)
                || style == null
                || style.isBlank()) {
            return false;
        }

        try (PreparedStatement statement =
                 db.connection().prepareStatement(
                     "INSERT INTO bot_styles(username, style) "
                     + "VALUES(?, ?) "
                     + "ON CONFLICT(username) "
                     + "DO UPDATE SET style = excluded.style"
                 )) {

            statement.setString(1, username);
            statement.setString(2, style.toLowerCase());

            statement.executeUpdate();

            return true;

        } catch (SQLException exception) {
            plugin.getLogger().log(
                Level.WARNING,
                "Could not save personality for " + username,
                exception
            );

            return false;
        }
    }

    /**
     * Validates Minecraft Java usernames.
     */
    private static boolean valid(String username) {
        return username != null
            && username.matches("[A-Za-z0-9_]{3,16}");
    }
}
