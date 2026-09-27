# Notice: modified PlayerEngine

This repository is a **modified version of PlayerEngine**, forked on 2026-09-27 from
<https://github.com/shakey2/PlayerEngine> at commit `40732ba6615f4cd6acdd6218a1a842e539712d3d`
(branch `1.21.1-arch-persistentdata`, release 1.21.1-1.4.0). The unmodified history is kept in
this repository and the original source stays reachable through the `upstream` remote.

PlayerEngine is licensed under the GNU Lesser General Public License v3.0 (see `LICENSE`) and
contains code derived from Baritone and Automatone, which are also LGPL-3.0. This modified
version is distributed under the same licence. All original copyright notices and licence
headers are retained. Upstream authors: Goodbird, Itsuka, Loris, the Altoclef creators, the
Automatone creators and the Baritone creators.

"Player2" is a name of Player2 (player2.game). This fork is not affiliated with or endorsed by
Player2, and its display name does not use that name. The mod id stays `playerengine` so that
mods depending on PlayerEngine (Player2NPC) keep loading.

## What changed

When `config/playerengine-gateway.properties` sets `enabled=true`, the mod contacts **only** the
OpenAI-compatible gateway named by `baseUrl`. It sends no request to `api.player2.game` or to a
local Player2 desktop app. Chat completions (and embeddings, when `embeddingModel` is set) go to the
gateway with `Authorization: Bearer <apiKey>` and the configured `model`. Player2-platform
endpoints (login, heartbeat, credits, AI profiles, characters, speech-to-text, cloud storage,
schematic search) are answered inside the process. Text-to-speech is disabled. When the file is
absent or `enabled=false`, behaviour is identical to upstream.

With endpoint profiles (`endpoint.<name>.*`), a companion whose character names `"endpoint"`
sends its chat completions to that profile's URL with that profile's key and model instead; every
other call keeps the default endpoint. A profile missing its key is refused before any request.

Files changed from upstream:

| File | Change |
|---|---|
| `common/src/main/java/com/player2/playerengine/player2api/gateway/GatewayConfig.java` | new: reads the gateway config file, an optional key file, `PLAYERENGINE_GATEWAY_*` environment overrides and endpoint profiles |
| `common/src/main/java/com/player2/playerengine/player2api/gateway/EndpointProfile.java` | new: one endpoint profile and its request-body rewrite |
| `common/src/main/java/com/player2/playerengine/player2api/gateway/GatewayCallContext.java` | new: the calling companion's character and billing key for the current thread |
| `common/src/main/java/com/player2/playerengine/player2api/gateway/GatewayRouter.java` | new: splits forwarded OpenAI endpoints from locally answered Player2 endpoints; picks the endpoint profile and enforces its hourly cap |
| `common/src/main/java/com/player2/playerengine/player2api/gateway/GatewaySelfTest.java` | new: self-test of the routing through the production HTTP path |
| `common/src/main/java/com/player2/playerengine/player2api/gateway/GatewayProfilesSelfTest.java` | new: self-test of per-character endpoint profiles against two loopback gateways |
| `common/src/main/java/com/player2/playerengine/player2api/utils/HTTPUtils.java` | gateway takeover in `sendRequest` and `sendRequestElement` |
| `common/src/main/java/com/player2/playerengine/player2api/Player2APIService.java` | companion calls carry their character and billing key to the gateway router |
| `common/src/main/java/com/player2/playerengine/player2api/Player2ApiDispatcher.java` | client-proxy relay refused for a companion on a non-default endpoint profile |
| `common/src/main/java/com/player2/playerengine/player2api/Prompts.java` | appends the characters file's operator `instructions` to the companion system prompt when the gateway is enabled |
| `common/src/main/java/com/player2/playerengine/player2api/auth/AuthenticationManager.java` | no Player2 login when the gateway is enabled |
| `common/src/main/java/com/player2/playerengine/player2api/auth/TokenStorage.java` | placeholder token instead of stored Player2 tokens when the gateway is enabled |
| `common/src/main/java/com/player2/playerengine/player2api/Player2PayerResolution.java` | server-wide work is billable with no player online when the gateway is enabled |
| `common/src/main/java/com/player2/playerengine/player2api/utils/AudioUtils.java` | text-to-speech skipped when the gateway is enabled |
| `common/build.gradle` | `gatewaySelfTest` task |
| `gradle.properties` | version `1.21.1-1.4.0-gateway.1` |
| `neoforge/src/main/resources/META-INF/neoforge.mods.toml` | display name "PlayerEngine (OpenAI-gateway fork)" |
| `README.md`, `NOTICE.md`, `Taskfile.yml`, `.gitignore` | fork documentation and build entries |

The exact changes are the commits on branch `gateway` after `40732ba`:
`git log --stat 40732ba..gateway`.

## Companion rules

`config/playerengine-companion.properties` sets how the companion plays. A missing file or key takes
the default.

- `survivalParity=true` (default): the companion mines at a survival player's pace. Break speed
  follows `Player.getDigSpeed` (tool, Efficiency, Haste, Mining Fatigue, `block_break_speed`, the
  underwater penalty that only Aqua Affinity lifts, the airborne penalty), and after each block that
  took more than one tick it waits 5 ticks before starting the next, as `MultiPlayerGameMode` does.
  Combat uses a player's numbers: base attack damage 1 and armour 0 instead of the zombie values
  Player2NPC registers, and each hit is scaled by the attack-cooldown curve of `Player.attack`
  (weapon attack speed included) and resets the charge. With hunger off, the companion acts as a
  player whose food bar stays full: natural regeneration heals 1 every 80 ticks instead of being
  frozen. `false` restores the upstream mining, combat and hunger behaviour.
- `hunger` (default unset): `true` or `false` overrides `hungerEnabled` in
  `playerengine/playerengine_settings.json`, which a singleplayer instance keeps across pack
  updates. Unset leaves that file in charge (upstream default off).
- `progressChat=off` (default): no task progress in the owner's chat. `milestones` shows outcomes
  and failures, `all` also step chatter such as "breaking iron ore" (upstream). Conversational
  replies are unaffected.

Independently of these rules, taking items from a container no longer inserts one extra item per
take (the room check used to insert a probe item), and a loot take that only partly fits leaves
the rest in the container instead of deleting it.

| File | Change |
|---|---|
| `common/src/main/java/com/player2/playerengine/companion/CompanionRules.java` | new: reads the companion rules file |
| `common/src/main/java/com/player2/playerengine/companion/SurvivalDigSpeed.java` | new: vanilla dig-speed arithmetic |
| `common/src/main/java/com/player2/playerengine/companion/SurvivalCombat.java` | new: player base attributes and attack-cooldown arithmetic |
| `common/src/main/java/com/player2/playerengine/companion/CompanionRulesSelfTest.java` | new: break times, cooldown and regeneration against vanilla, rules parsing |
| `common/src/main/java/com/player2/playerengine/automaton/api/entity/LivingEntityInteractionManager.java` | dig speed and progress under `survivalParity`; pins player base attributes each tick |
| `common/src/main/java/com/player2/playerengine/automaton/api/entity/LivingEntityHungerManager.java` | full-food-bar regeneration when hunger is off under `survivalParity` |
| `common/src/main/java/com/player2/playerengine/control/PlayerExtraController.java` | cooldown-scaled hits under `survivalParity` |
| `common/src/main/java/com/player2/playerengine/control/KillAura.java`, `tasks/entity/AbstractKillEntityTask.java` | wait for the weapon's real cooldown under `survivalParity` |
| `common/src/main/java/com/player2/playerengine/mixins/LivingEntityMixin.java` | setter for `attackStrengthTicker` |
| `common/src/main/java/com/player2/playerengine/automaton/utils/player/EntityInteractionController.java` | post-break delay under `survivalParity` |
| `common/src/main/java/com/player2/playerengine/PlayerEngineSettings.java` | `hunger` rule overrides `hungerEnabled` |
| `common/src/main/java/com/player2/playerengine/PlayerEngineController.java` | progress lines filtered by `progressChat` |
| `common/src/main/java/com/player2/playerengine/executor/TaskStepExecutorAdapter.java` | step-failure line hidden by `progressChat=off` |
| `common/src/main/java/com/player2/playerengine/tasks/container/PickupFromContainerTask.java` | room check without inserting |
| `common/src/main/java/com/player2/playerengine/tasks/container/LootContainerTask.java` | room check without inserting; partial takes keep the rest |
| `common/build.gradle`, `Taskfile.yml` | `companionSelfTest` task, run by `task test` |
