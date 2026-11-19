
package com.talkbot.plugin.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.talkbot.plugin.TalkBotPlugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public final class ChatManager {

    private static final Set<String> STYLES = Set.of(
            "toxic",
            "chill",
            "funny",
            "silly",
            "cute",
            "girl",
            "boy",
            "tomboy",
            "yn",
            "brainrot",
            "weird",
            "dry",
            "admin-professional",
            "admin-yn"
    );

    private static final Pattern RESOURCE_REQUEST = Pattern.compile(
            "\\b(give|got|have|need|spare|drop)\\b.*\\b(wood|planks|logs|cobblestone|cobble|iron|food|bread)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern SEVERE_LANGUAGE = Pattern.compile(
            "\\b(rape|raped|nigger|nigga)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private final TalkBotPlugin plugin;
    private final BotRegistry registry;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final Map<String, Deque<String>> history =
            new ConcurrentHashMap<>();

    // Normal public-chat cooldowns.
    private final Map<String, Long> cooldown =
            new ConcurrentHashMap<>();

    // Separate private-message cooldowns.
    private final Map<String, Long> whisperCooldown =
            new ConcurrentHashMap<>();

    private final Map<String, Long> ignoredUntil =
            new ConcurrentHashMap<>();

    private final Map<String, Long> moderationCooldown =
            new ConcurrentHashMap<>();

    private final AtomicBoolean stopping = new AtomicBoolean();

    private volatile long lastRequest;
    private volatile long lastWhisperRequest;

    private volatile BukkitTask cleanup;

    public ChatManager(TalkBotPlugin plugin, BotRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;

        cleanup = Bukkit.getScheduler().runTaskTimer(
                plugin,
                this::cleanup,
                1200L,
                1200L
        );
    }

    public static Set<String> styles() {
        return STYLES;
    }

    public void reload() {
        cooldown.clear();
        whisperCooldown.clear();
        ignoredUntil.clear();
        moderationCooldown.clear();

        synchronized (this) {
            lastRequest = 0L;
            lastWhisperRequest = 0L;
        }
    }

    private void cleanup() {
        long now = System.currentTimeMillis();

        cooldown.entrySet().removeIf(
                entry -> now - entry.getValue() > 3600000L
        );

        whisperCooldown.entrySet().removeIf(
                entry -> now - entry.getValue() > 3600000L
        );

        moderationCooldown.entrySet().removeIf(
                entry -> now - entry.getValue() > 3600000L
        );

        ignoredUntil.entrySet().removeIf(
                entry -> now > entry.getValue()
        );
    }

    // --------------------------------------------------
    // PLAYER CHAT
    // --------------------------------------------------

    public void handlePlayerMessage(Player sender, String message) {
        if (stopping.get() || sender == null || message == null || message.isBlank()) {
            return;
        }

        moderate(sender, message);
        handleActions(sender, message);

        if (!aiReady()) {
            return;
        }

        String low = message.toLowerCase(Locale.ROOT);

        for (String botName : registry.getRegisteredBots()) {
            if (botName.equalsIgnoreCase(sender.getName())) {
                continue;
            }

            Player bot = Bukkit.getPlayerExact(botName);

            if (bot == null || !bot.isOnline()) {
                continue;
            }

            boolean mentioned = matchesBot(low, botName);

            double chance = plugin.getConfig().getDouble(
                    "chat.random-reply-chance",
                    0.25
            );

            chance = Math.max(0.0, Math.min(1.0, chance));

            if (mentioned || ThreadLocalRandom.current().nextDouble() < chance) {
                request(
                        botName,
                        sender.getName(),
                        message,
                        mentioned,
                        false,
                        false
                );
            }
        }
    }

    // --------------------------------------------------
    // WHISPERS
    // --------------------------------------------------

    public void handleWhisper(Player sender, String target, String message) {
        if (stopping.get()
                || sender == null
                || target == null
                || message == null
                || message.isBlank()) {
            return;
        }

        Player bot = Bukkit.getPlayerExact(target);

        if (bot == null || !bot.isOnline() || !registry.isBot(target)) {
            return;
        }

        if (!aiReady()) {
            return;
        }

        request(
                target,
                sender.getName(),
                message,
                true,
                false,
                true
        );
    }

    // --------------------------------------------------
    // BOT-TO-BOT CHAT
    // --------------------------------------------------

    public void handleBotMessage(Player bot, String message) {
        if (stopping.get()
                || bot == null
                || message == null
                || !aiReady()) {
            return;
        }

        double chance = plugin.getConfig().getDouble(
                "chat.bot-to-bot-chance",
                0.10
        );

        chance = Math.max(0.0, Math.min(1.0, chance));

        if (ThreadLocalRandom.current().nextDouble() > chance) {
            return;
        }

        for (String name : registry.getRegisteredBots()) {
            if (name.equalsIgnoreCase(bot.getName())) {
                continue;
            }

            Player other = Bukkit.getPlayerExact(name);

            if (other == null || !other.isOnline()) {
                continue;
            }

            request(
                    name,
                    bot.getName(),
                    message,
                    true,
                    true,
                    false
            );

            break;
        }
    }

    // --------------------------------------------------
    // AI REQUEST
    // --------------------------------------------------

    private void request(
            String bot,
            String speaker,
            String message,
            boolean addressed,
            boolean botChat,
            boolean whisper
    ) {
        if (stopping.get() || !aiReady()) {
            return;
        }

        String botKey = bot.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();

        if (now < ignoredUntil.getOrDefault(botKey, 0L)) {
            return;
        }

        if (whisper) {
            long whisperCooldownMillis = Math.max(
                    0L,
                    plugin.getConfig().getLong(
                            "chat.whisper-cooldown-seconds",
                            2L
                    ) * 1000L
            );

            if (now - whisperCooldown.getOrDefault(botKey, 0L)
                    < whisperCooldownMillis) {
                return;
            }

            synchronized (this) {
                long minimumGap = Math.max(
                        0L,
                        plugin.getConfig().getLong(
                                "ai.min-whisper-request-interval-ms",
                                500L
                        )
                );

                if (now - lastWhisperRequest < minimumGap) {
                    return;
                }

                lastWhisperRequest = now;
            }

            whisperCooldown.put(botKey, now);

        } else {
            long cooldownMillis = Math.max(
                    0L,
                    plugin.getConfig().getLong(
                            "chat.per-bot-cooldown-seconds",
                            18L
                    ) * 1000L
            );

            if (now - cooldown.getOrDefault(botKey, 0L)
                    < cooldownMillis) {
                return;
            }

            synchronized (this) {
                long minimumGap = Math.max(
                        0L,
                        plugin.getConfig().getLong(
                                "ai.min-request-interval-ms",
                                1800L
                        )
                );

                if (now - lastRequest < minimumGap) {
                    return;
                }

                lastRequest = now;
            }

            cooldown.put(botKey, now);
        }

        try {
            String style = registry.getStyle(bot);

            if (style == null || style.isBlank()) {
                style = plugin.getConfig().getString(
                        "personality.default-style",
                        "chill"
                );
            }

            String persona = personality(style);
            String topic = topicHint(message);

            JsonObject payload = new JsonObject();

            payload.addProperty(
                    "model",
                    plugin.getConfig().getString(
                            "ai.model",
                            "gemini-3.5-flash-lite"
                    )
            );

            payload.addProperty(
                    "temperature",
                    plugin.getConfig().getDouble("ai.temperature", 0.9)
            );

            payload.addProperty(
                    "max_tokens",
                    plugin.getConfig().getInt("ai.max-tokens", 220)
            );

            JsonArray messages = new JsonArray();

            JsonObject system = new JsonObject();
            system.addProperty("role", "system");

            system.addProperty(
                    "content",
                    persona
                            + "\nYou are Minecraft player " + bot + "."
                            + "\nYou are an actual player in a Minecraft server, not an AI assistant."
                            + "\nTalk naturally like a real person typing in Minecraft chat."
                            + "\nKeep replies conversational, casual, and relevant to the exact message."
                            + "\nDo not write essays, explanations, formal paragraphs, or huge walls of text."
                            + "\nOften send 2 separate short messages, sometimes 3, and sometimes only 1."
                            + "\nPut each separate message on its own new line."
                            + "\nDo not combine multiple messages into one paragraph."
                            + "\nDo not use numbering, bullet points, labels, or quotation marks around messages."
                            + "\nFor trash talk, use short separate comebacks instead of long roast paragraphs."
                            + "\nDo not force multiple messages when one natural reply is enough."
                            + "\nNever use emojis, markdown, or assistant-style language."
                            + "\nNever use slurs, threats, sexual remarks, or identity-based insults."
                            + "\nDo not claim game events, coordinates, or inventory information you were not given."
                            + "\nYou can discuss Minecraft PvP, Minecraft mechanics, anime, shows, games, sports, and normal real-life topics."
                            + "\nUnderstand context, jokes, sarcasm, and follow-up messages."
                            + "\nDo not repeat the same phrases constantly."
                            + "\nDo not constantly say bro, gang, twin, or lil bro."
                            + "\nUse slang naturally when it fits your personality."
                            + "\n" + minecraftKnowledge()
                            + "\n" + topic
            );

            messages.add(system);

            Deque<String> botHistory = history.computeIfAbsent(
                    botKey,
                    key -> new ArrayDeque<>()
            );

            synchronized (botHistory) {
                for (String line : botHistory) {
                    int separator = line.indexOf(':');

                    if (separator < 0) {
                        continue;
                    }

                    String role = line.startsWith("BOT:")
                            ? "assistant"
                            : "user";

                    String content = line.substring(separator + 1).trim();

                    JsonObject previous = new JsonObject();
                    previous.addProperty("role", role);
                    previous.addProperty("content", content);

                    messages.add(previous);
                }
            }

            String source;

            if (whisper) {
                source = "Private whisper from ";
            } else if (botChat) {
                source = "Minecraft bot ";
            } else {
                source = "Player ";
            }

            JsonObject input = new JsonObject();
            input.addProperty("role", "user");

            input.addProperty(
                    "content",
                    source + speaker + ": " + message
                            + (addressed ? "\nYou were directly addressed." : "")
                            + (whisper
                            ? "\nThis is a private conversation. Reply privately to this player."
                            : "")
            );

            messages.add(input);
            payload.add("messages", messages);

            addHistory(botKey, "USER: " + speaker + ": " + message);

            String url = plugin.getConfig().getString("ai.api-url", "");

            if (url == null || url.isBlank() || !url.startsWith("https://")) {
                throw new IllegalStateException("Invalid AI API URL.");
            }

            String key = System.getenv().getOrDefault(
                    "TALK_BOT_API_KEY",
                    plugin.getConfig().getString("ai.api-key", "")
            );

            int timeout = Math.max(
                    5,
                    plugin.getConfig().getInt("ai.timeout-seconds", 30)
            );

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Content-Type", "application/json")
                    .POST(
                            HttpRequest.BodyPublishers.ofString(
                                    payload.toString(),
                                    StandardCharsets.UTF_8
                            )
                    );

            if (key != null && !key.isBlank() && !key.startsWith("YOUR_")) {
                builder.header("Authorization", "Bearer " + key);
            }

            http.sendAsync(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofString()
            ).thenApply(response -> {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException(
                            "HTTP " + response.statusCode()
                                    + ": " + response.body()
                    );
                }

                JsonObject json = JsonParser.parseString(
                        response.body()
                ).getAsJsonObject();

                JsonArray choices = json.getAsJsonArray("choices");

                if (choices == null || choices.isEmpty()) {
                    throw new IllegalStateException("AI returned no choices.");
                }

                JsonObject choice = choices.get(0).getAsJsonObject();
                JsonObject responseMessage = choice.getAsJsonObject("message");

                if (responseMessage == null) {
                    throw new IllegalStateException("AI response has no message.");
                }

                JsonElement content = responseMessage.get("content");

                if (content == null || content.isJsonNull()) {
                    throw new IllegalStateException("AI response has no content.");
                }

                return content.getAsString();

            }).thenAccept(reply -> {
                sendReply(bot, speaker, message, reply, whisper);

            }).exceptionally(error -> {
                if (whisper) {
                    whisperCooldown.remove(botKey, now);
                } else {
                    cooldown.remove(botKey, now);
                }

                plugin.getLogger().warning(
                        "AI request for " + bot + " failed: "
                                + error.getMessage()
                );

                return null;
            });

        } catch (Exception exception) {
            if (whisper) {
                whisperCooldown.remove(botKey, now);
            } else {
                cooldown.remove(botKey, now);
            }

            plugin.getLogger().warning(
                    "Could not start AI request for " + bot + ": "
                            + exception.getMessage()
            );
        }
    }

    // --------------------------------------------------
    // SEND AI REPLY
    // --------------------------------------------------

    private void sendReply(
            String botName,
            String speaker,
            String source,
            String reply,
            boolean whisper
    ) {
        if (stopping.get() || reply == null || reply.isBlank()) {
            return;
        }

        int maxLength = Math.max(
                1,
                plugin.getConfig().getInt(
                        "chat.max-message-length",
                        180
                )
        );

        // Split the AI response into separate chat messages.
        String[] rawLines = reply.split("\\R+");

        List<String> outputs = new ArrayList<>();

        for (String rawLine : rawLines) {
            String clean = cleanMessage(rawLine);

            if (clean.isBlank()) {
                continue;
            }

            if (clean.length() > maxLength) {
                clean = clean.substring(0, maxLength).trim();
            }

            if (!clean.isBlank()) {
                outputs.add(clean);
            }

            if (outputs.size() >= 3) {
                break;
            }
        }

        if (outputs.isEmpty()) {
            return;
        }

        long initialDelay = ThreadLocalRandom.current().nextLong(
                Math.max(0L, plugin.getConfig().getLong(
                        "chat.min-reply-delay-ticks",
                        5L
                )),
                Math.max(
                        Math.max(0L, plugin.getConfig().getLong(
                                "chat.min-reply-delay-ticks",
                                5L
                        )),
                        plugin.getConfig().getLong(
                                "chat.max-reply-delay-ticks",
                                15L
                        )
                ) + 1L
        );

        long currentDelay = initialDelay;

        for (String output : outputs) {
            final String line = output;
            final long scheduledDelay = currentDelay;

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (stopping.get()) {
                    return;
                }

                Player bot = Bukkit.getPlayerExact(botName);

                if (bot == null || !bot.isOnline() || !registry.isBot(botName)) {
                    return;
                }

                addHistory(
                        botName.toLowerCase(Locale.ROOT),
                        "BOT: " + line
                );

                if (whisper) {
                    String command = plugin.getConfig().getString(
                            "chat.whisper-reply-command",
                            "msg"
                    );

                    if (command == null || command.isBlank()) {
                        command = "msg";
                    }

                    command = command.replace("/", "").trim();

                    bot.performCommand(
                            command + " " + speaker + " " + line
                    );
                } else {
                    bot.chat(line);
                }

            }, scheduledDelay);

            // 15 characters = 2 seconds = 40 server ticks.
            // Each character therefore takes 8/3 ticks.
            long typingTicks = (long) Math.ceil(
                    line.length() * (40.0 / 15.0)
            );

            long pauseBetweenMessages = ThreadLocalRandom.current().nextLong(
                    4L,
                    9L
            );

            currentDelay += typingTicks + pauseBetweenMessages;
        }
    }

    private String cleanMessage(String input) {
        if (input == null) {
            return "";
        }

        return input
                .replaceAll("§[0-9A-FK-ORa-fk-or]", "")
                .replaceAll("^[\\s>*•\\-]+", "")
                .replaceAll("\\s+", " ")
                .replace("/", "")
                .trim();
    }

    private void addHistory(String botKey, String line) {
        Deque<String> botHistory = history.computeIfAbsent(
                botKey,
                key -> new ArrayDeque<>()
        );

        synchronized (botHistory) {
            botHistory.addLast(line);

            while (botHistory.size() > 20) {
                botHistory.removeFirst();
            }
        }
    }

    // --------------------------------------------------
    // MINECRAFT KNOWLEDGE
    // --------------------------------------------------

    private String minecraftKnowledge() {
        return """
                MINECRAFT KNOWLEDGE:
                You understand Minecraft Java Edition mechanics, survival, building,
                crafting, enchanting, villagers, trading, farming, redstone, mining,
                exploration, dimensions, mobs, and multiplayer servers.

                You understand Minecraft PvP concepts including:
                aim, tracking, strafing, sprint resetting, W-tapping, S-tapping,
                hit selecting, combos, spacing, movement, rod usage, block hitting,
                shield timing, critical hits, armor durability, potion effects,
                hotbar management, healing, clutches, and game sense.

                You understand common PvP modes such as:
                Crystal PvP, Sword, Axe, Mace, UHC, Pot PvP, NethPot, SMP PvP,
                Diamond SMP, Lifesteal, BedWars, SkyWars, and general survival PvP.

                You understand that different PvP modes have different mechanics
                and skill requirements. Do not mix their rules together.

                You understand MCTiers and tier-based PvP communities.
                You know that tiers and points are used to compare skill within
                particular modes, but never invent a player's current tier,
                points, ranking, or test results.

                You understand Mace PvP, including wind charges, fall damage,
                height advantage, timing, mace smash attacks, and movement.
                You understand Diamond SMP combat, armor durability, crystals,
                axes, swords, shields, and resource management.

                You understand Minecraft server behavior, plugins, commands,
                permissions, bots, latency, ping, TPS, and common PvP terminology.

                You can discuss Minecraft creators, SMP creators, PvP players,
                anime, sports, gaming, and normal everyday topics when relevant.
                Do not randomly list facts. Use knowledge naturally in conversation.

                Do not pretend you personally watched a video, met a creator,
                played a match, or witnessed an event unless the conversation
                explicitly gives you that information.

                Do not invent current rankings, accusations, statistics,
                tournament results, or player history.
                """;
    }

    // --------------------------------------------------
    // PLAYER REQUESTED ACTIONS
    // --------------------------------------------------

    private void handleActions(Player sender, String message) {
        String low = message.toLowerCase(Locale.ROOT);

        for (String botName : registry.getRegisteredBots()) {
            Player bot = Bukkit.getPlayerExact(botName);

            if (bot == null || !bot.isOnline()) {
                continue;
            }

            boolean mentioned = matchesBot(low, botName);

            boolean asksForTeleport =
                    low.contains("tpa to me")
                            || low.contains("teleport to me")
                            || low.contains("tp to me")
                            || low.contains("come to me");

            if (asksForTeleport) {
                double chance = plugin.getConfig().getDouble(
                        "chat.tpa-random-chance",
                        0.50
                );

                chance = Math.max(0.0, Math.min(1.0, chance));

                if (mentioned || ThreadLocalRandom.current().nextDouble() < chance) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (bot.isOnline() && registry.isBot(botName)) {
                            bot.performCommand("tpa " + sender.getName());
                        }
                    });
                }
            }

            boolean asksForSpawn =
                    low.contains("go to spawn")
                            || low.contains("come to spawn")
                            || low.contains("tp to spawn")
                            || low.contains("teleport to spawn");

            if (mentioned && asksForSpawn) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (bot.isOnline() && registry.isBot(botName)) {
                        bot.performCommand("spawn");
                    }
                });
            }

            if (plugin.getConfig().getBoolean(
                    "chat.resource-giving-enabled",
                    true
            )) {
                if (RESOURCE_REQUEST.matcher(low).find()) {
                    String resource = getRequestedResource(low);

                    if (resource != null) {
                        giveResource(bot, sender, resource);
                    }
                }
            }
        }
    }

    private String getRequestedResource(String message) {
        if (message.contains("cobble")) {
            return "cobblestone";
        }

        if (message.contains("iron")) {
            return "iron";
        }

        if (message.contains("wood")
                || message.contains("log")
                || message.contains("plank")) {
            return "wood";
        }

        if (message.contains("food") || message.contains("bread")) {
            return "food";
        }

        return null;
    }

    private void giveResource(Player bot, Player target, String kind) {
        if (bot == null || target == null
                || !bot.isOnline() || !target.isOnline()) {
            return;
        }

        if (!bot.getWorld().equals(target.getWorld())) {
            return;
        }

        if (bot.getLocation().distanceSquared(target.getLocation()) > 64.0) {
            return;
        }

        Material[] choices = switch (kind) {
            case "wood" -> new Material[]{
                    Material.OAK_LOG,
                    Material.SPRUCE_LOG,
                    Material.BIRCH_LOG,
                    Material.OAK_PLANKS,
                    Material.SPRUCE_PLANKS,
                    Material.BIRCH_PLANKS
            };

            case "cobblestone" -> new Material[]{
                    Material.COBBLESTONE
            };

            case "iron" -> new Material[]{
                    Material.IRON_INGOT
            };

            default -> new Material[]{
                    Material.BREAD,
                    Material.COOKED_BEEF,
                    Material.COOKED_PORKCHOP
            };
        };

        for (Material material : choices) {
            int slot = bot.getInventory().first(material);

            if (slot < 0) {
                continue;
            }

            ItemStack stack = bot.getInventory().getItem(slot);

            if (stack == null || stack.getAmount() <= 0) {
                continue;
            }

            int amount = Math.min(stack.getAmount(), 16);
            ItemStack given = new ItemStack(material, amount);

            if (stack.getAmount() == amount) {
                bot.getInventory().setItem(slot, null);
            } else {
                stack.setAmount(stack.getAmount() - amount);
            }

            Map<Integer, ItemStack> leftovers =
                    target.getInventory().addItem(given);

            leftovers.values().forEach(item ->
                    target.getWorld().dropItemNaturally(
                            target.getLocation(),
                            item
                    )
            );

            target.sendMessage(
                    ChatColor.GRAY + bot.getName()
                            + " gave you " + amount + " "
                            + material.name().toLowerCase(Locale.ROOT)
            );

            return;
        }
    }

    // --------------------------------------------------
    // MODERATION
    // --------------------------------------------------

    private void moderate(Player sender, String message) {
        if (!plugin.getConfig().getBoolean("moderation.enabled", true)) {
            return;
        }

        if (!plugin.getConfig().getBoolean(
                "moderation.warn-for-severe-language",
                true
        )) {
            return;
        }

        String normalized = normalize(message);

        if (!SEVERE_LANGUAGE.matcher(normalized).find()) {
            return;
        }

        String id = sender.getUniqueId().toString();
        long now = System.currentTimeMillis();

        long cooldownMillis = plugin.getConfig().getLong(
                "moderation.cooldown-seconds",
                12
        ) * 1000L;

        if (now - moderationCooldown.getOrDefault(id, 0L)
                < cooldownMillis) {
            return;
        }

        moderationCooldown.put(id, now);

        for (String name : registry.getRegisteredBots()) {
            String style = registry.getStyle(name);

            if (style == null || !style.startsWith("admin-")) {
                continue;
            }

            Player admin = Bukkit.getPlayerExact(name);

            if (admin == null || !admin.isOnline()) {
                continue;
            }

            String warning;

            if (style.equals("admin-yn")) {
                warning = sender.getName()
                        + " aye don't talk like that here gang";
            } else {
                warning = sender.getName()
                        + ", please keep the chat respectful. Continued behavior may result in moderation.";
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (admin.isOnline() && registry.isBot(name)) {
                    admin.chat(warning);
                }
            });

            break;
        }
    }

    private static String normalize(String input) {
        return input
                .toLowerCase(Locale.ROOT)
                .replace('0', 'o')
                .replace('1', 'i')
                .replace('3', 'e')
                .replace('4', 'a')
                .replace('5', 's')
                .replace('7', 't')
                .replaceAll("[^a-z ]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    // --------------------------------------------------
    // TOPIC DETECTION
    // --------------------------------------------------

    private String topicHint(String message) {
        String lower = message.toLowerCase(Locale.ROOT);

        if (plugin.getConfig().getConfigurationSection("topics") == null) {
            return "";
        }

        for (String topic : plugin.getConfig()
                .getConfigurationSection("topics")
                .getKeys(false)) {

            for (String keyword : plugin.getConfig().getStringList(
                    "topics." + topic + ".keywords"
            )) {
                if (keyword == null || keyword.isBlank()) {
                    continue;
                }

                if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                    return "Topic detected: " + topic
                            + ". Respond naturally using relevant knowledge. "
                            + "Do not force the topic into the conversation.";
                }
            }
        }

        return "";
    }

    // --------------------------------------------------
    // BOT NAME RECOGNITION
    // --------------------------------------------------

    private boolean matchesBot(String text, String name) {
        if (text == null || name == null || name.isBlank()) {
            return false;
        }

        String normalizedName = name.toLowerCase(Locale.ROOT);

        if (containsWholeName(text, normalizedName)) {
            return true;
        }

        String prefix = normalizedName.substring(
                0,
                Math.min(4, normalizedName.length())
        );

        if (prefix.length() < 3) {
            return false;
        }

        int matches = 0;

        for (String registered : registry.getRegisteredBots()) {
            if (registered.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                matches++;
            }
        }

        return matches == 1 && containsWholeName(text, prefix);
    }

    private boolean containsWholeName(String text, String name) {
        Pattern pattern = Pattern.compile(
                "(?<![a-z0-9_])"
                        + Pattern.quote(name)
                        + "(?![a-z0-9_])",
                Pattern.CASE_INSENSITIVE
        );

        return pattern.matcher(text).find();
    }

    // --------------------------------------------------
    // PERSONALITIES
    // --------------------------------------------------

    private String personality(String style) {
        if (style == null) {
            style = "chill";
        }

        return switch (style.toLowerCase(Locale.ROOT)) {

            case "toxic" ->
                    "You are a skilled Minecraft PvP rival. "
                            + "Your trash talk is effortless, clever, cold and sarcastic. "
                            + "Do not use corny long roast lines. "
                            + "You are straightforward and know how to hit people's nerves "
                            + "with clever comebacks. Usually reply in 2-8 words. "
                            + "NEVER exceed 12 words in one message. "
                            + "Never write paragraphs or explain your insults. "
                            + "Do not constantly say bro, gang, twin or lil bro. "
                            + "Do not force slang into every message. "
                            + "Use specific PvP references when relevant, such as aim, "
                            + "combos, strafing, armor, durability and clutches. "
                            + "Use dry sarcasm, dismissive replies, unexpected comebacks "
                            + "and subtle ragebait. Sometimes reply with just one or two words. "
                            + "Do not insult randomly. Never repeat the same comeback.";

            case "chill" ->
                    "You are a relaxed, casual Minecraft gamer. "
                            + "You are easygoing and friendly. "
                            + "Use slang like twin, gang, wassup, fr, brb and gtg naturally, "
                            + "but do not force it into every message.";

            case "funny" ->
                    "You are a genuinely funny Minecraft player. "
                            + "Make quick, witty, unexpected jokes. "
                            + "Avoid stale dad jokes and forced humor. "
                            + "Make fun of funny things that happen in-game, such as deaths.";

            case "silly" ->
                    "You are a goofy, playful and unpredictable Minecraft player. "
                            + "Say random funny things sometimes, but keep conversations natural. "
                            + "Do not spam bro every sentence.";

            case "cute" ->
                    "You are a sweet, warm, affectionate and supportive Minecraft player. "
                            + "You are friendly without sounding fake or overly dramatic.";

            case "girl" ->
                    "You are a casual feminine-presenting Minecraft player. "
                            + "You have your own personality. Be natural and avoid stereotypes.";

            case "boy" ->
                    "You are a casual masculine-presenting Minecraft player "
                            + "with your own personality. Be natural and avoid stereotypes.";

            case "tomboy" ->
                    "You have confident, sporty tomboy energy. "
                            + "You are casual, direct, playful and comfortable around gamers.";

            case "yn" ->
                    "You are a young casual gamer. Usually chill and relaxed, "
                            + "but you get heated during PvP. Use natural modern slang, "
                            + "including some Chicago and NYC-inspired slang when it fits, "
                            + "such as twin, aye, gng, gang, bum, idgaf, and stfu. "
                            + "Never use slurs or threats.";

            case "brainrot" ->
                    "You are an absurd meme-loving Minecraft player. "
                            + "Use occasional surreal internet humor and ridiculous comparisons. "
                            + "Do not write huge sentences or spam bro. "
                            + "Do not make every message brainrot.";

            case "weird" ->
                    "You are an awkward, strange and unpredictable Minecraft player. "
                            + "Use surreal, confusing and unexpected non-sexual humor. "
                            + "Do not harass people.";

            case "dry" ->
                    "You are extremely blunt and dry. "
                            + "Usually answer with a few words like yeah, nah, maybe or ok. "
                            + "Rarely elaborate. Do not force jokes.";

            case "admin-professional" ->
                    "You are a calm, professional server moderator. "
                            + "Be clear and respectful. Warn players about severe language. "
                            + "Never claim to have actual operator powers or invent punishments.";

            case "admin-yn" ->
                    "You are a casual slang-heavy server moderator. "
                            + "You can be friendly, but you take moderation seriously. "
                            + "Use casual slang naturally when it fits. "
                            + "Warn players about severe language. "
                            + "Never claim to have actual operator powers.";

            default -> {
                String configured = plugin.getConfig().getString(
                        "personality.default",
                        "You are an ordinary Minecraft player. "
                                + "Be natural, casual and concise."
                );

                yield configured == null || configured.isBlank()
                        ? "You are an ordinary Minecraft player. Be natural and concise."
                        : configured;
            }
        };
    }

    // --------------------------------------------------
    // AI CONFIGURATION
    // --------------------------------------------------

    private boolean aiReady() {
        if (!plugin.getConfig().getBoolean("ai.enabled", false)) {
            return false;
        }

        String url = plugin.getConfig().getString("ai.api-url", "");
        String model = plugin.getConfig().getString("ai.model", "");

        return url != null
                && url.startsWith("https://")
                && model != null
                && !model.isBlank();
    }

    // --------------------------------------------------
    // GAME REACTIONS
    // --------------------------------------------------

    public void onDamage(Player player, boolean visibleEntity) {
        if (player == null || !registry.isBot(player.getName())) {
            return;
        }

        if (!visibleEntity
                && "dry".equalsIgnoreCase(registry.getStyle(player.getName()))) {
            player.chat("why did i just take damage");
        }
    }

    public void onLowHealth(Player player) {
        if (player == null || !registry.isBot(player.getName())) {
            return;
        }

        String style = registry.getStyle(player.getName());

        if (Set.of("cute", "chill", "funny", "silly", "girl")
                .contains(style)) {

            player.chat("ggs that was close");

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()
                        && registry.isBot(player.getName())
                        && player.getHealth() <= 3.0) {
                    player.chat("omfg im actually one hit");
                }
            }, 12L);

        } else if ("toxic".equals(style)) {
            player.chat("dura diff nearly got me");

        } else if ("yn".equals(style)) {
            player.chat("NAH WHO HIT ME LIKE THAT");
        }
    }

    public void onDeath(Player player) {
        if (player == null || !registry.isBot(player.getName())) {
            return;
        }

        String style = registry.getStyle(player.getName());

        if ("toxic".equals(style)) {
            player.chat("L");

        } else if (Set.of("cute", "chill", "girl").contains(style)) {
            player.chat("ggs");

        } else {
            player.chat("nah im sick");
        }
    }

    public void onMole(Player bot, String block) {
        if (bot == null || !registry.isBot(bot.getName())) {
            return;
        }

        if (!plugin.getConfig().getBoolean(
                "chat.mole-detection-enabled",
                true
        )) {
            return;
        }

        String style = registry.getStyle(bot.getName());

        if ("toxic".equals(style)) {
            bot.chat("bro really building a mole setup");

        } else if ("dry".equals(style)) {
            bot.chat("subtle.");
        }
    }

    // --------------------------------------------------
    // SHUTDOWN
    // --------------------------------------------------

    public void shutdown() {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }

        if (cleanup != null) {
            cleanup.cancel();
            cleanup = null;
        }

        history.clear();
        cooldown.clear();
        whisperCooldown.clear();
        ignoredUntil.clear();
        moderationCooldown.clear();
    }
}
