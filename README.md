# TalkBot 3.0

Paper 1.21.11 / Java 21 plugin adding chat personalities to existing PvpBot fake players. It does not spawn or control PvpBot entities.

## Build
GitHub Actions builds `target/TalkBot-3.0.0.jar` on push. Locally: `mvn clean package` with JDK 21 and Maven.

## Setup
1. Upload the JAR to the server's `plugins` folder and restart.
2. Set `ai.enabled: true` and configure the Gemini-compatible endpoint/model/key in `plugins/TalkBot/config.yml`. Prefer the `TALK_BOT_API_KEY` environment variable. Never commit a real key.
3. Register each existing PvpBot username: `/talkbot register Flowtives`.
4. Assign a personality: `/talkbot style Flowtives chill`.

Styles: toxic, chill, funny, silly, cute, girl, boy, tomboy, yn, brainrot, weird, dry, admin-professional, admin-yn.

## Important compatibility notes
- Whisper interception watches configured command aliases in `PlayerCommandPreprocessEvent`; set `chat.whisper-command`, `chat.whisper-aliases`, and `chat.whisper-reply-command` to match the server's actual private-message plugin. The outbound whisper command must accept `/whisper player message` syntax and the fake player must be permitted to use it.
- `/tpa` and `/spawn` are executed as the fake player via Bukkit `performCommand`; PvpBot must expose the bot as a Bukkit Player and the server must permit those commands.
- Resource transfers use the bot's Bukkit inventory and only work if PvpBot exposes a real inventory. The bot and recipient must be within 8 blocks.
- Combat damage/death/low-health reactions use Bukkit events. Nearby entity visibility is approximated using nearby Bukkit entities, not the bot's actual line-of-sight.
- Mole detection currently watches bot-placed configured blocks. It is not yet wired to PvpManager combat state or other players' block placements. That requires the exact PvpManager API/event contract.
- Moderation is warning-only. It uses a basic normalized keyword matcher and is not a complete slur/bypass classifier; review it against your server rules before enabling.
- AI requests are rate-limited, but provider quotas still apply. The configured model name may need changing if Gemini changes model availability for your API project.
- This code has not been compiled against your live BaoHost/PvpBot environment in this workspace. Let GitHub Actions compile it, then share the build log if anything fails.
