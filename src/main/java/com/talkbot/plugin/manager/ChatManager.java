
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
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    private final Map<String, Deque<String>> history = new ConcurrentHashMap<>();

    private final Map<String, Long> cooldown = new ConcurrentHashMap<>();
    private final Map<String, Long> ignoredUntil = new ConcurrentHashMap<>();
    private final Map<String, Long> moderationCooldown = new ConcurrentHashMap<>();

    private final AtomicBoolean stopping = new AtomicBoolean();

    private volatile long lastRequest;
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
        ignoredUntil.clear();
        moderationCooldown.clear();

        synchronized (this) {
            lastRequest = 0L;
        }
    }

    private void cleanup() {
        long now = System.currentTimeMillis();

        cooldown.entrySet().removeIf(
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
        if (stopping.get() || message == null || message.isBlank()) {
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
        if (stopping.get() || sender == null || target == null || message == null) {
            return;
        }

        Player bot = Bukkit.getPlayerExact(target);

        if (bot == null || !registry.isBot(target)) {
            return;
        }

        if (aiReady()) {
            request(
                    target,
                    sender.getName(),
                    message,
                    true,
                    false,
                    true
            );
        }
    }

    // --------------------------------------------------
    // BOT-TO-BOT CHAT
    // --------------------------------------------------

    public void handleBotMessage(Player bot, String message) {
        if (stopping.get() || bot == null || message == null || !aiReady()) {
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

        long cooldownMillis = plugin.getConfig().getLong(
                "chat.per-bot-cooldown-seconds",
                18
        ) * 1000L;

        if (now - cooldown.getOrDefault(botKey, 0L) < cooldownMillis) {
            return;
        }

        synchronized (this) {
            long minimumGap = plugin.getConfig().getLong(
                    "ai.min-request-interval-ms",
                    1800L
            );

            if (now - lastRequest < minimumGap) {
                return;
            }

            lastRequest = now;
        }

        cooldown.put(botKey, now);

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
                    plugin.getConfig().getInt("ai.max-tokens", 110)
            );

            JsonArray messages = new JsonArray();

            JsonObject system = new JsonObject();
            system.addProperty("role", "system");

            system.addProperty(
                    "content",
                    persona
                            + "\nYou are Minecraft player " + bot + "."
                            + "\nRespond like a real player in Minecraft chat."
                            + "\nUsually use one short message, not a paragraph."
                            + "\nNever use emojis, markdown, or assistant-style language."
                            + "\nNever use slurs, threats, sexual remarks, or identity-based insults."
                            + "\nDo not claim game events or inventory information you were not given."
                            + "\nYou can discuss anime, shows, games, sports, and normal real-life topics."
                            + "\nUnderstand context and respond to what the player actually means."
                            + "\nDo not repeat the same phrases constantly."
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
            );

            messages.add(input);

            payload.add("messages", messages);

            // Store the player's message in conversation memory.
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
                cooldown.remove(botKey, now);

                plugin.getLogger().warning(
                        "AI request for " + bot + " failed: "
                                + error.getMessage()
                );

                return null;
            });

        } catch (Exception exception) {
            cooldown.remove(botKey, now);

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
        if (stopping.get() || reply == null) {
            return;
        }

        String clean = reply
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("§[0-9A-FK-ORa-fk-or]", "")
                .replaceAll("\\s+", " ")
                .trim();

        // Prevent an AI reply from executing as a command.
        clean = clean.replace("/", "");

        int maxLength = Math.max(
                1,
                plugin.getConfig().getInt(
                        "chat.max-message-length",
                        180
                )
        );

        if (clean.length() > maxLength) {
            clean = clean.substring(0, maxLength).trim();
        }

        if (clean.isBlank()) {
            return;
        }

        final String output = clean;

        long minimumDelay = Math.max(
                0L,
                plugin.getConfig().getLong(
                        "chat.min-reply-delay-ticks",
                        10L
                )
        );

        long maximumDelay = Math.max(
                minimumDelay,
                plugin.getConfig().getLong(
                        "chat.max-reply-delay-ticks",
                        35L
                )
        );

        long delay = ThreadLocalRandom.current().nextLong(
                minimumDelay,
                maximumDelay + 1L
        );

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (stopping.get()) {
                return;
            }

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
                        "BOT: " + output
                );

                if (whisper) {
                    String command = plugin.getConfig().getString(
                            "chat.whisper-reply-command",
                            "whisper"
                    );

                    if (command == null || command.isBlank()) {
                        command = "whisper";
                    }

                    bot.performCommand(
                            command + " " + speaker + " " + output
                    );
                } else {
                    bot.chat(output);
                }

            }, delay);
        });
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
        if (bot == null || target == null || !bot.isOnline() || !target.isOnline()) {
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

        if (now - moderationCooldown.getOrDefault(id, 0L) < cooldownMillis) {
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

        // Do not use a prefix if another registered bot shares it.
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
                    + "You are straightforward and know how to hit people's nerves "
                    + "with clever comebacks. Usually reply in 2-8 words. "
                    + "NEVER exceed 12 words. Never write paragraphs. "
                    + "Never explain your insults. "
                    + "Do not constantly say bro, gang, twin or lil bro. "
                    + "Do not force slang into every message. "
                    + "Use specific PvP references when relevant, such as aim, "
                    + "combos, strafing, armor, durability and clutches. "
                    + "Use dry sarcasm, dismissive replies, unexpected comebacks "
                    + "and subtle ragebait. Sometimes reply with just one or two words. "
                    + "Do not insult randomly. Never repeat the same comeback. "
                    + "No slurs, threats, identity-based insults or sexual remarks.";

            case "chill" ->
                    "You are a relaxed, casual Minecraft gamer. "
                    + "You are easygoing and friendly. "
                    + "Use slang like twin, gang, wassup, fr, brb and gtg naturally, "
                    + "but do not force it into every message.";

            case "funny" ->
                    "You are a genuinely funny Minecraft player. "
                    + "Make quick, witty, unexpected jokes. "
                    + "Avoid stale dad jokes and forced humor.";

            case "silly" ->
                    "You are a goofy, playful and unpredictable Minecraft player. "
                    + "Say random funny things sometimes, but keep conversations natural.";

            case "cute" ->
                    "You are a sweet, warm, affectionate and supportive Minecraft player. "
                    + "You are friendly without sounding fake or overly dramatic.";

            case "girl" ->
                    "You are a casual feminine-presenting Minecraft player "
                    + "with your own personality. Be natural and avoid stereotypes.";

            case "boy" ->
                    "You are a casual masculine-presenting Minecraft player "
                    + "with your own personality. Be natural and avoid stereotypes.";

            case "tomboy" ->
                    "You have confident, sporty tomboy energy. "
                    + "You are casual, direct, playful and comfortable around gamers.";

            case "yn" ->
                    "You are a young casual gamer. Usually chill and relaxed, "
                    + "but you get heated during PvP. Use natural modern slang. "
                    + "Never use slurs or threats.";

            case "brainrot" ->
                    "You are an absurd meme-loving Minecraft player. "
                    + "Use occasional surreal internet humor and ridiculous comparisons. "
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
        if (!registry.isBot(player.getName())) {
            return;
        }

        if (!visibleEntity
                && "dry".equalsIgnoreCase(registry.getStyle(player.getName()))) {
            player.chat("why did i just take damage");
        }
    }

    public void onLowHealth(Player player) {
        if (!registry.isBot(player.getName())) {
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
        if (!registry.isBot(player.getName())) {
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
        if (!registry.isBot(bot.getName())) {
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
        ignoredUntil.clear();
        moderationCooldown.clear();
    }
}
