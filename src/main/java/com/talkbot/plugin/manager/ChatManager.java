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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChatManager {

    private static final double PEER_ACTION_MAX_DISTANCE = 10.0;
    private static final double PEER_ACTION_MAX_DIST_SQ =
            PEER_ACTION_MAX_DISTANCE * PEER_ACTION_MAX_DISTANCE;
    private static final long FLOWSTATE_WINDOW_MS = 55_000L;

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

    private static final List<String> SATIRE_SLANG_POOL = List.of(
            "fire", "hard", "aura", "aura farming", "bussin", "drip",
            "ate", "ate and left no crumbs", "slay", "goated", "valid",
            "mid", "chopped", "cooked", "trash", "L",
            "crine", "im dead", "im weak",
            "tweaking", "crashing out", "unhinged",
            "larping", "cap", "no cap", "performative", "cheugy",
            "locked in", "on lock",
            "cortisol spike", "high cortisol", "low cortisol",
            "sigma", "chill", "unc", "boomer",
            "rizz", "rizzler", "mogging", "glow up",
            "yapping", "yapper",
            "W", "weird flex but ok", "whip", "whole meal", "wig snatched",
            "wya", "wyd", "uhh", "understood the assignment",
            "v", "vanilla", "vibe",
            "sus", "swerve", "swole", "swoop",
            "take a seat", "tea", "tf", "tfw", "thirsty",
            "totes", "touch grass", "twin", "twizzy",
            "smh", "smol", "snack", "snatched", "so",
            "stan", "standing on business", "stoked",
            "salty", "savage", "say less", "sending me",
            "shade", "ship", "shook", "shorty", "sick", "simp",
            "slap", "omg", "oml", "omw", "ong", "on fleek", "on point",
            "only in ohio", "ops", "otp", "periodt", "plug",
            "poggers", "preppy", "pressed", "pulling", "put on blast",
            "rad", "ratchet", "read", "real", "receipts", "rent free", "rn", "rpg",
            "left on read", "let them cook", "lets get this bread", "lewk", "lfg", "lit",
            "lmao", "lol", "looksmaxxing", "low taper fade", "mad",
            "menty b", "mewing", "ngl", "gassing", "gg", "ghost",
            "girl math", "girl dinner", "gigachad", "giving me life", "glazing",
            "goat", "guap", "gucci", "heated", "heem", "hella skrilla",
            "here for this", "high key", "hits different", "hollywood", "hop off",
            "hot take", "hype", "ick", "icymi", "irl", "iso", "iykyk",
            "kda", "keep it 100", "maxxing", "quiet on the creek",
            "i can't do nathan", "scuba", "clanker", "chud", "npc",
            "situationship", "zesty", "pookie", "lock in", "pick me",
            "brain rot", "fanum tax", "fambushing", "bet", "biblically accurate",
            "big yikes", "blueprint", "boo'd up", "boujee", "brat",
            "breadcrumbing", "buttah", "cash", "catch feels", "caught in 4k",
            "chad", "chat", "clapback", "clock", "core", "cray cray",
            "cringe", "curve", "dank", "dap", "dayroom", "delulu",
            "dip", "dl", "dope", "drag", "dub", "extra", "facts",
            "fan service", "ffa", "finna", "finsta", "fit", "flex",
            "fml", "frfr", "ftw", "fugly", "fw", "fyp", "g"
    );

    private static final Pattern RESOURCE_REQUEST = Pattern.compile(
            "\\b(give|got|have|need|spare|drop)\\b.*\\b(wood|planks|logs|cobblestone|cobble|iron|food|bread)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern SEVERE_LANGUAGE = Pattern.compile(
            "\\b(rape|raped|nigger|nigga)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern LOCALIZED_ACTION_PATTERN = Pattern.compile(
            "\\b(place|put|set|drop|throw|move|stand|walk|go|come|build|mine|break|open|close|fill|clear|cover|hide)\\b.*\\b(here|there|over there|right here|by me|next to me|wool|chest|sign|block|torch|door|bed|furnace)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern OPEN_BROADCAST_PATTERN = Pattern.compile(
            "\\b(yo who|who has|who got|who plays|who watches|who knows|anyone got|anyone have|anyone know|anyone else|guys can|guys do|yall|chat|in chat|server)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern ROAST_OR_L_TRIGGER = Pattern.compile(
            "\\b(you are ass|ur ass|youre ass|stfu|trash|L+|cooked|clowned|washed|get bodied)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern LAUGH_TRIGGER = Pattern.compile(
            "\\b(lol+|lmao+|lmfao+|crine|im dead|i'm dead|im weak|i'm weak|funny)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private final TalkBotPlugin plugin;
    private final BotRegistry registry;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final Map<String, Deque<String>> history = new ConcurrentHashMap<>();
    private final Map<String, Long> cooldown = new ConcurrentHashMap<>();
    private final Map<String, Long> whisperCooldown = new ConcurrentHashMap<>();
    private final Map<String, Long> ignoredUntil = new ConcurrentHashMap<>();
    private final Map<String, Long> moderationCooldown = new ConcurrentHashMap<>();
    private final Map<String, Long> flowstateUntil = new ConcurrentHashMap<>();

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
        flowstateUntil.clear();

        synchronized (this) {
            lastRequest = 0L;
            lastWhisperRequest = 0L;
        }
    }

    private void cleanup() {
        long now = System.currentTimeMillis();

        cooldown.entrySet().removeIf(entry -> now - entry.getValue() > 3_600_000L);
        whisperCooldown.entrySet().removeIf(entry -> now - entry.getValue() > 3_600_000L);
        moderationCooldown.entrySet().removeIf(entry -> now - entry.getValue() > 3_600_000L);
        ignoredUntil.entrySet().removeIf(entry -> now > entry.getValue());
        flowstateUntil.entrySet().removeIf(entry -> now > entry.getValue());
    }

    // --------------------------------------------------
    // ADMIN /talkbot tell COMMAND
    // --------------------------------------------------

    public void tellBot(String botName, String instruction) {
        if (stopping.get() || botName == null || instruction == null || instruction.isBlank()) {
            return;
        }

        if (!aiReady()) {
            return;
        }

        String prompt = "Admin order/directive for you: " + instruction
                + "\nAct/respond naturally in character according to this directive.";

        requestInternal(
                botName,
                "Admin",
                prompt,
                true,
                false,
                false,
                true,
                true
        );
    }

    // --------------------------------------------------
    // VOICE CHAT ACTIVITY HOOK
    // --------------------------------------------------

    public void handleVoiceActivity(Player speaker, boolean whispering) {
        if (stopping.get() || speaker == null || !speaker.isOnline() || !aiReady()) {
            return;
        }

        if (registry.isBot(speaker.getName())) {
            return;
        }

        double triggerRadius = whispering
                ? plugin.getConfig().getDouble("voicechat.whisper-react-distance", 12.0)
                : plugin.getConfig().getDouble("voicechat.normal-react-distance", 10.0);

        double maxDistSq = triggerRadius * triggerRadius;
        double chance = plugin.getConfig().getDouble("voicechat.react-chance", 0.18);

        for (String botName : registry.getRegisteredBots()) {
            if (botName.equalsIgnoreCase(speaker.getName())) {
                continue;
            }

            Player bot = Bukkit.getPlayerExact(botName);
            if (bot == null || !bot.isOnline()) {
                continue;
            }

            if (!bot.getWorld().equals(speaker.getWorld())) {
                continue;
            }

            if (bot.getLocation().distanceSquared(speaker.getLocation()) > maxDistSq) {
                continue;
            }

            if (ThreadLocalRandom.current().nextDouble() < chance) {
                String vcContext = whispering
                        ? "[Heard " + speaker.getName() + " whispering in voice chat nearby]"
                        : "[Heard " + speaker.getName() + " speaking in voice chat nearby]";

                requestInternal(
                        botName,
                        speaker.getName(),
                        vcContext,
                        false,
                        false,
                        false,
                        false,
                        false
                );
            }
        }
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
        long now = System.currentTimeMillis();

        for (String botName : registry.getRegisteredBots()) {
            if (botName.equalsIgnoreCase(sender.getName())) {
                continue;
            }

            Player bot = Bukkit.getPlayerExact(botName);
            if (bot == null || !bot.isOnline()) {
                continue;
            }

            boolean mentioned = matchesBot(low, botName);
            boolean localizedPeerChat = isLocalizedPeerChat(sender, bot, low, botName);
            boolean openBroadcast = OPEN_BROADCAST_PATTERN.matcher(low).find();

            String flowKey = botName.toLowerCase(Locale.ROOT) + ":" + sender.getUniqueId();
            boolean inFlowstate = now < flowstateUntil.getOrDefault(flowKey, 0L);

            // If someone is doing localized action/peer chatter (like "yo friend place the wool here"
            // or "yo bro stand there"), suppress bots farther than 10 blocks unless directly addressed
            // to THIS bot within 10 blocks.
            if (localizedPeerChat && !isWithinDistance(sender, bot, PEER_ACTION_MAX_DIST_SQ)) {
                continue;
            }

            if (mentioned || inFlowstate) {
                flowstateUntil.put(flowKey, now + FLOWSTATE_WINDOW_MS);
            }

            double baseChance = plugin.getConfig().getDouble("chat.random-reply-chance", 0.25);
            baseChance = Math.max(0.0, Math.min(1.0, baseChance));

            double effectiveChance;
            if (mentioned) {
                effectiveChance = 1.0;
            } else if (inFlowstate) {
                effectiveChance = Math.min(0.90, Math.max(baseChance + 0.45, 0.70));
            } else if (openBroadcast) {
                effectiveChance = Math.min(0.85, Math.max(baseChance + 0.25, 0.45));
            } else {
                effectiveChance = baseChance;
            }

            if (ThreadLocalRandom.current().nextDouble() < effectiveChance) {
                requestInternal(
                        botName,
                        sender.getName(),
                        message,
                        mentioned,
                        false,
                        false,
                        inFlowstate,
                        false
                );
            }
        }
    }

    private boolean isLocalizedPeerChat(Player sender, Player bot, String low, String botName) {
        boolean actionPhrasing = LOCALIZED_ACTION_PATTERN.matcher(low).find();
        boolean otherPlayerAddressed = addressesAnotherPlayer(low, botName);
        return actionPhrasing || otherPlayerAddressed;
    }

    private boolean addressesAnotherPlayer(String low, String botName) {
        if (low.startsWith("yo ") || low.contains(" yo ")) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                String name = online.getName().toLowerCase(Locale.ROOT);
                if (name.equalsIgnoreCase(botName)) {
                    continue;
                }
                if (containsWholeName(low, name)) {
                    return true;
                }
            }
        }

        // Also treat explicit peer cues like "yo friend", "friend place", "bro stand"
        // as localized peer chat when the bot's own name isn't in the message.
        if (!matchesBot(low, botName)
                && (low.contains("friend") || low.contains("place ") || low.contains("put ") || low.contains("stand there"))) {
            return true;
        }

        return false;
    }

    private boolean isWithinDistance(Player a, Player b, double maxDistSq) {
        if (a == null || b == null || !a.isOnline() || !b.isOnline()) {
            return false;
        }
        if (!a.getWorld().equals(b.getWorld())) {
            return false;
        }
        return a.getLocation().distanceSquared(b.getLocation()) <= maxDistSq;
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

        requestInternal(
                target,
                sender.getName(),
                message,
                true,
                false,
                true,
                true,
                false
        );
    }

    // --------------------------------------------------
    // BOT-TO-BOT CHAT
    // --------------------------------------------------

    public void handleBotMessage(Player bot, String message) {
        if (stopping.get() || bot == null || message == null || !aiReady()) {
            return;
        }

        double chance = plugin.getConfig().getDouble("chat.bot-to-bot-chance", 0.10);
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

            requestInternal(
                    name,
                    bot.getName(),
                    message,
                    true,
                    true,
                    false,
                    false,
                    false
            );
            break;
        }
    }

    // --------------------------------------------------
    // AI REQUEST
    // --------------------------------------------------

    private void requestInternal(
            String bot,
            String speaker,
            String message,
            boolean addressed,
            boolean botChat,
            boolean whisper,
            boolean flowstate,
            boolean bypassCooldown
    ) {
        if (stopping.get() || !aiReady()) {
            return;
        }

        String botKey = bot.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();

        if (!bypassCooldown && now < ignoredUntil.getOrDefault(botKey, 0L)) {
            return;
        }

        if (!bypassCooldown) {
            if (whisper) {
                long whisperCooldownMillis = Math.max(
                        0L,
                        plugin.getConfig().getLong("chat.whisper-cooldown-seconds", 2L) * 1000L
                );

                if (now - whisperCooldown.getOrDefault(botKey, 0L) < whisperCooldownMillis) {
                    return;
                }

                synchronized (this) {
                    long minimumGap = Math.max(
                            0L,
                            plugin.getConfig().getLong("ai.min-whisper-request-interval-ms", 500L)
                    );
                    if (now - lastWhisperRequest < minimumGap) {
                        return;
                    }
                    lastWhisperRequest = now;
                }

                whisperCooldown.put(botKey, now);
            } else {
                long baseSeconds = plugin.getConfig().getLong("chat.per-bot-cooldown-seconds", 18L);
                long effectiveSeconds = flowstate ? Math.max(2L, baseSeconds / 3L) : baseSeconds;
                long cooldownMillis = Math.max(0L, effectiveSeconds * 1000L);

                if (now - cooldown.getOrDefault(botKey, 0L) < cooldownMillis) {
                    return;
                }

                synchronized (this) {
                    long minimumGap = Math.max(
                            0L,
                            plugin.getConfig().getLong("ai.min-request-interval-ms", 1800L)
                    );
                    long effectiveGap = flowstate ? Math.min(minimumGap, 700L) : minimumGap;
                    if (now - lastRequest < effectiveGap) {
                        return;
                    }
                    lastRequest = now;
                }

                cooldown.put(botKey, now);
            }
        }

        try {
            String style = registry.getStyle(bot);
            if (style == null || style.isBlank()) {
                style = plugin.getConfig().getString("personality.default-style", "chill");
            }

            String persona = personality(style);
            String topic = topicHint(message);
            String memoriesBlock = formatMemoriesForPrompt(bot);
            String sampledSlang = sampleSatireSlang(style);

            JsonObject payload = new JsonObject();
            payload.addProperty("model", plugin.getConfig().getString("ai.model", "gemini-3.5-flash-lite"));
            payload.addProperty("temperature", plugin.getConfig().getDouble("ai.temperature", 0.9));
            payload.addProperty("max_tokens", plugin.getConfig().getInt("ai.max-tokens", 220));

            JsonArray messages = new JsonArray();

            JsonObject system = new JsonObject();
            system.addProperty("role", "system");
            system.addProperty(
                    "content",
                    persona
                            + "\nYou are Minecraft player " + bot + "."
                            + "\nYou are a real human-like player in a Minecraft server, not an AI assistant."
                            + "\nTalk naturally like actual players in casual chat (short lowercase lines, 'yo', 'sup', 'we waited years for that shi', 'same lol', 'ong', etc.)."
                            + "\nCRITICAL: Do NOT spam 'fr' at the end of sentences. Rarely use 'fr' at all."
                            + "\nCRITICAL: Do NOT obsess over chests, double chests, or mining iron unless the conversation or your memories specifically ask about it."
                            + "\nHave a believable everyday life outside just mining: age, hobbies, classmates/friends, food you ate, and random events in your day."
                            + "\nKeep replies casual, conversational, and tied to what was actually said."
                            + "\nOften send 1 to 3 short separate messages on separate lines when natural."
                            + "\nDo not use emojis, markdown, numbering, bullets, or assistant phrasing."
                            + "\nNever use slurs, threats, sexual remarks, or identity-based insults."
                            + "\nOccasional satirical slang is allowed (max 1 term per reply, never forced): " + sampledSlang
                            + "\n" + memoriesBlock
                            + "\n" + minecraftKnowledge()
                            + "\n" + topic
            );
            messages.add(system);

            Deque<String> botHistory = history.computeIfAbsent(botKey, key -> new ArrayDeque<>());
            synchronized (botHistory) {
                for (String line : botHistory) {
                    int separator = line.indexOf(':');
                    if (separator < 0) {
                        continue;
                    }
                    String role = line.startsWith("BOT:") ? "assistant" : "user";
                    String content = line.substring(separator + 1).trim();

                    JsonObject previous = new JsonObject();
                    previous.addProperty("role", role);
                    previous.addProperty("content", content);
                    messages.add(previous);
                }
            }

            String source = whisper
                    ? "Private whisper from "
                    : (botChat ? "Minecraft bot " : "Player ");

            JsonObject input = new JsonObject();
            input.addProperty("role", "user");
            input.addProperty(
                    "content",
                    source + speaker + ": " + message
                            + (addressed ? "\nYou were directly addressed." : "")
                            + (flowstate ? "\nYou are in an active flowstate conversation; keep momentum natural." : "")
                            + (whisper ? "\nThis is a private conversation. Reply privately to this player." : "")
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

            int timeout = Math.max(5, plugin.getConfig().getInt("ai.timeout-seconds", 30));

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8));

            if (key != null && !key.isBlank() && !key.startsWith("YOUR_")) {
                builder.header("Authorization", "Bearer " + key);
            }

            http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() < 200 || response.statusCode() >= 300) {
                            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
                        }

                        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
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
                    })
                    .thenAccept(reply -> sendReply(bot, speaker, message, reply, whisper))
                    .exceptionally(error -> {
                        if (whisper) {
                            whisperCooldown.remove(botKey, now);
                        } else {
                            cooldown.remove(botKey, now);
                        }
                        plugin.getLogger().warning("AI request for " + bot + " failed: " + error.getMessage());
                        return null;
                    });

        } catch (Exception exception) {
            if (whisper) {
                whisperCooldown.remove(botKey, now);
            } else {
                cooldown.remove(botKey, now);
            }
            plugin.getLogger().warning("Could not start AI request for " + bot + ": " + exception.getMessage());
        }
    }

    private String formatMemoriesForPrompt(String botName) {
        List<BotRegistry.MemoryEntry> memories = registry.getMemories(botName);
        if (memories.isEmpty()) {
            return "MEMORIES & LIFE: None explicitly saved yet; keep your personal age, hobbies, classmates, and daily life consistent.";
        }

        StringBuilder sb = new StringBuilder("SAVED MEMORIES & LIFE FACTS (use naturally when relevant):\n");
        for (int i = 0; i < memories.size(); i++) {
            sb.append("- ").append(memories.get(i).memory()).append('\n');
        }
        return sb.toString().trim();
    }

    private String sampleSatireSlang(String style) {
        int count = switch (style.toLowerCase(Locale.ROOT)) {
            case "brainrot", "yn", "silly", "funny" -> 6;
            case "dry", "admin-professional" -> 2;
            default -> 4;
        };

        List<String> picked = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String term = SATIRE_SLANG_POOL.get(ThreadLocalRandom.current().nextInt(SATIRE_SLANG_POOL.size()));
            if (!picked.contains(term)) {
                picked.add(term);
            }
        }
        return String.join(", ", picked);
    }

    // --------------------------------------------------
    // SEND AI REPLY (WITH ANTI-"fr", LAUGHS & "L" BURSTS)
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

        String style = registry.getStyle(botName);
        String lowerSource = source == null ? "" : source.toLowerCase(Locale.ROOT);

        // Optional style-driven "L" burst when someone gets clowned / takes an L
        if (!whisper
                && ROAST_OR_L_TRIGGER.matcher(lowerSource).find()
                && Set.of("toxic", "yn", "brainrot").contains(style)
                && ThreadLocalRandom.current().nextDouble() < 0.35) {
            dispatchLines(botName, speaker, List.of("L", "L", "L", "L"), false);
            return;
        }

        // Optional style-driven laugh lines when funny/silly/yn reacts to laughter/jokes
        if (!whisper
                && LAUGH_TRIGGER.matcher(lowerSource).find()
                && Set.of("funny", "silly", "yn", "brainrot").contains(style)
                && ThreadLocalRandom.current().nextDouble() < 0.25) {
            String laugh = switch (style) {
                case "brainrot" -> ThreadLocalRandom.current().nextBoolean() ? "LMFAOOO" : "im dead";
                case "yn" -> ThreadLocalRandom.current().nextBoolean() ? "LMAOO" : "im weak";
                default -> ThreadLocalRandom.current().nextBoolean() ? "LOLLL" : "lmao";
            };
            dispatchLines(botName, speaker, List.of(laugh), false);
            return;
        }

        int maxLength = Math.max(1, plugin.getConfig().getInt("chat.max-message-length", 180));
        String[] rawLines = reply.split("\\R+");
        List<String> outputs = new ArrayList<>();

        for (String rawLine : rawLines) {
            String clean = sanitizeCasualLine(cleanMessage(rawLine));
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

        dispatchLines(botName, speaker, outputs, whisper);
    }

    private String sanitizeCasualLine(String line) {
        if (line == null || line.isBlank()) {
            return "";
        }

        // Collapse repeated "fr" spam like "fr fr fr fr"
        String cleaned = line.replaceAll("(?i)\\b(fr)(\\s+\\1){1,}\\b", "$1");

        // Strip trailing "fr" ~80% of the time so it doesn't get tacked onto every sentence
        if (ThreadLocalRandom.current().nextDouble() < 0.80) {
            cleaned = cleaned.replaceAll("(?i)(\\s+\\bfr\\b)+[.!?]*$", "");
        }

        return cleaned.replaceAll("\\s+", " ").trim();
    }

    private void dispatchLines(String botName, String speaker, List<String> outputs, boolean whisper) {
        long minDelay = Math.max(0L, plugin.getConfig().getLong("chat.min-reply-delay-ticks", 5L));
        long maxDelay = Math.max(minDelay, plugin.getConfig().getLong("chat.max-reply-delay-ticks", 15L));
        long currentDelay = ThreadLocalRandom.current().nextLong(minDelay, maxDelay + 1L);

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

                addHistory(botName.toLowerCase(Locale.ROOT), "BOT: " + line);

                if (whisper) {
                    String command = plugin.getConfig().getString("chat.whisper-reply-command", "msg");
                    if (command == null || command.isBlank()) {
                        command = "msg";
                    }
                    command = command.replace("/", "").trim();
                    bot.performCommand(command + " " + speaker + " " + line);
                } else {
                    bot.chat(line);
                }
            }, scheduledDelay);

            long typingTicks = (long) Math.ceil(line.length() * (40.0 / 15.0));
            long pauseBetweenMessages = ThreadLocalRandom.current().nextLong(4L, 9L);
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
        Deque<String> botHistory = history.computeIfAbsent(botKey, key -> new ArrayDeque<>());
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

                Do not claim game events, coordinates, or inventory information you were not given.
                Do not invent current rankings, accusations, statistics, or player history.
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

            boolean asksForTeleport = low.contains("tpa to me")
                    || low.contains("teleport to me")
                    || low.contains("tp to me")
                    || low.contains("come to me");

            if (asksForTeleport) {
                double chance = plugin.getConfig().getDouble("chat.tpa-random-chance", 0.50);
                chance = Math.max(0.0, Math.min(1.0, chance));

                if (mentioned || ThreadLocalRandom.current().nextDouble() < chance) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (bot.isOnline() && registry.isBot(botName)) {
                            bot.performCommand("tpa " + sender.getName());
                        }
                    });
                }
            }

            boolean asksForSpawn = low.contains("go to spawn")
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

            if (plugin.getConfig().getBoolean("chat.resource-giving-enabled", true)) {
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
        if (message.contains("wood") || message.contains("log") || message.contains("plank")) {
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
                    Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG,
                    Material.OAK_PLANKS, Material.SPRUCE_PLANKS, Material.BIRCH_PLANKS
            };
            case "cobblestone" -> new Material[]{Material.COBBLESTONE};
            case "iron" -> new Material[]{Material.IRON_INGOT};
            default -> new Material[]{Material.BREAD, Material.COOKED_BEEF, Material.COOKED_PORKCHOP};
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

            Map<Integer, ItemStack> leftovers = target.getInventory().addItem(given);
            leftovers.values().forEach(item -> target.getWorld().dropItemNaturally(target.getLocation(), item));

            target.sendMessage(ChatColor.GRAY + bot.getName() + " gave you " + amount + " " + material.name().toLowerCase(Locale.ROOT));
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
        if (!plugin.getConfig().getBoolean("moderation.warn-for-severe-language", true)) {
            return;
        }

        String normalized = normalize(message);
        if (!SEVERE_LANGUAGE.matcher(normalized).find()) {
            return;
        }

        String id = sender.getUniqueId().toString();
        long now = System.currentTimeMillis();
        long cooldownMillis = plugin.getConfig().getLong("moderation.cooldown-seconds", 12) * 1000L;

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

            String warning = style.equals("admin-yn")
                    ? sender.getName() + " aye don't talk like that here gang"
                    : sender.getName() + ", please keep the chat respectful. Continued behavior may result in moderation.";

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

        for (String topic : plugin.getConfig().getConfigurationSection("topics").getKeys(false)) {
            for (String keyword : plugin.getConfig().getStringList("topics." + topic + ".keywords")) {
                if (keyword == null || keyword.isBlank()) {
                    continue;
                }
                if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                    return "Topic detected: " + topic + ". Respond naturally using relevant knowledge.";
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

        String prefix = normalizedName.substring(0, Math.min(4, normalizedName.length()));
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
        Matcher matcher = Pattern.compile(
                "(?<![a-z0-9_])" + Pattern.quote(name) + "(?![a-z0-9_])",
                Pattern.CASE_INSENSITIVE
        ).matcher(text);
        return matcher.find();
    }

    // --------------------------------------------------
    // PERSONALITIES
    // --------------------------------------------------

    private String personality(String style) {
        if (style == null) {
            style = "chill";
        }

        return switch (style.toLowerCase(Locale.ROOT)) {
            case "toxic" -> "You are a skilled Minecraft PvP rival. Trash talk is effortless, cold, short, and sarcastic. Usually 2-8 words, never paragraphs.";
            case "chill" -> "You are a relaxed, casual Minecraft gamer. Easygoing and natural.";
            case "funny" -> "You are a witty Minecraft player with dry/casual jokes and real reactions.";
            case "silly" -> "You are goofy, playful, and unpredictable in casual chat.";
            case "cute" -> "You are warm, friendly, and supportive without sounding fake.";
            case "girl" -> "You are a casual feminine-presenting Minecraft player with your own personality.";
            case "boy" -> "You are a casual masculine-presenting Minecraft player with your own personality.";
            case "tomboy" -> "You have confident, sporty tomboy energy and talk casually.";
            case "yn" -> "You are a young casual gamer who gets heated in PvP and uses modern/regional slang naturally without slurs.";
            case "brainrot" -> "You use occasional surreal internet/meme humor without overdoing it.";
            case "weird" -> "You are awkward, strange, and unpredictable in a harmless way.";
            case "dry" -> "You are blunt and dry. Short answers like yeah, nah, ok, maybe.";
            case "admin-professional" -> "You are a calm, level-headed server moderator.";
            case "admin-yn" -> "You are a casual slang-friendly server moderator.";
            default -> {
                String configured = plugin.getConfig().getString("personality.default", "You are an ordinary Minecraft player.");
                yield (configured == null || configured.isBlank())
                        ? "You are an ordinary Minecraft player."
                        : configured;
            }
        };
    }

    private boolean aiReady() {
        if (!plugin.getConfig().getBoolean("ai.enabled", false)) {
            return false;
        }
        String url = plugin.getConfig().getString("ai.api-url", "");
        String model = plugin.getConfig().getString("ai.model", "");
        return url != null && url.startsWith("https://") && model != null && !model.isBlank();
    }

    // --------------------------------------------------
    // GAME REACTIONS
    // --------------------------------------------------

    public void onDamage(Player player, boolean visibleEntity) {
        if (player == null || !registry.isBot(player.getName())) {
            return;
        }
        if (!visibleEntity && "dry".equalsIgnoreCase(registry.getStyle(player.getName()))) {
            player.chat("CHILL");
        }
    }

    public void onLowHealth(Player player) {
        if (player == null || !registry.isBot(player.getName())) {
            return;
        }

        String style = registry.getStyle(player.getName());
        if (Set.of("cute", "chill", "funny", "silly", "girl").contains(style)) {
            player.chat("ggs that was close");
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && registry.isBot(player.getName()) && player.getHealth() <= 3.0) {
                    player.chat("omfg im actually one hit");
                }
            }, 12L);
        } else if ("toxic".equals(style)) {
            player.chat("dura diff nearly got me");
        } else if ("yn".equals(style)) {
            player.chat("oh shi");
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
            player.chat("nYO");
        }
    }

    public void onMole(Player bot, String block) {
        if (bot == null || !registry.isBot(bot.getName())) {
            return;
        }
        if (!plugin.getConfig().getBoolean("chat.mole-detection-enabled", true)) {
            return;
        }

        String style = registry.getStyle(bot.getName());
        if ("toxic".equals(style)) {
            bot.chat("gay ass moling");
        } else if ("dry".equals(style)) {
            bot.chat("subtle.");
        }
    }

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
        flowstateUntil.clear();
    }
}
