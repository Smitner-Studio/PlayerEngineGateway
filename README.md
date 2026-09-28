# PlayerEngineGateway

A fork of [PlayerEngine](https://github.com/shakey2/PlayerEngine) by **Goodbird**, the server-side
framework that gives Minecraft mobs a player's abilities. This fork runs PlayerEngine's AI companions
against any OpenAI-compatible gateway instead of the Player2 API, and adds long jobs (area commands,
plans). It is built for the Smitner Industrial modpack and licensed LGPL-3.0, like upstream.

For what PlayerEngine is, how it works, and its acknowledgements, read the
[upstream README](https://github.com/shakey2/PlayerEngine#readme). This page covers only the fork.

**This is a modified PlayerEngine: the OpenAI-gateway fork (LGPL-3.0).** With
`config/playerengine-gateway.properties` enabled, it talks only to the OpenAI-compatible
gateway you configure, instead of the Player2 API and desktop app. See [NOTICE.md](NOTICE.md)
for what changed and why.

Configuration (`config/playerengine-gateway.properties`; each key can be overridden by an
environment variable):

| Key | Environment variable | Meaning |
|---|---|---|
| `enabled` | `PLAYERENGINE_GATEWAY_ENABLED` | `true` routes everything to the gateway |
| `baseUrl` | `PLAYERENGINE_GATEWAY_URL` | gateway root, e.g. `http://host:4001/v1` |
| `apiKey` | `PLAYERENGINE_GATEWAY_KEY` | sent as `Authorization: Bearer`; prefer the variable or the key file |
| `apiKeyFile` | `PLAYERENGINE_GATEWAY_KEY_FILE` | used when no key is set: first line of this file in `config/` (default `playerengine-gateway.key`) |
| `model` | `PLAYERENGINE_GATEWAY_MODEL` | model for chat completions |
| `embeddingModel` | `PLAYERENGINE_GATEWAY_EMBEDDING_MODEL` | empty disables embeddings (memory falls back to lexical) |
| `patronTier` | `PLAYERENGINE_GATEWAY_PATRON_TIER` | non-empty unlocks patron-only features (more LLM calls) |
| `charactersFile` | `PLAYERENGINE_GATEWAY_CHARACTERS` | companion list in `config/`, same shape as Player2's `/v1/selected_characters` |
| `defaultEndpoint` | — | name of the endpoint profile the keys above form (default `default`) |

**Endpoint profiles.** A companion can use its own endpoint: give its entry in the characters
file `"endpoint": "<name>"` and declare the profile with `endpoint.<name>.*` keys. Its chat
completions then go only there, with only its key. Calls from other companions, and calls with no
companion (memory extraction, mod-intelligence enrichment, embeddings), use the default profile.
Companions on different profiles think in parallel: each (player, profile) pair has its own
dispatch lane with one call in flight, so one companion's calls stay in order.

| Key | Meaning |
|---|---|
| `endpoint.<name>.baseUrl` | endpoint root, e.g. `https://api.openai.com/v1` (extra profiles only) |
| `endpoint.<name>.model` | model id (extra profiles only) |
| `endpoint.<name>.apiKeyEnv` | environment variable that holds the key (extra profiles only) |
| `endpoint.<name>.apiKeyFile` | used when that variable is unset: first line of this file in `config/` (default `playerengine-gateway-<name>.key`) |
| `endpoint.<name>.tokenParam` | `max_tokens` (default) or `max_completion_tokens`, the field the output limit is sent in |
| `endpoint.<name>.maxOutputTokens` | replaces the mod's output limit (reasoning models spend reasoning tokens from it); 0 keeps it |
| `endpoint.<name>.jsonMode` | `false` strips `response_format` (default `true`) |
| `endpoint.<name>.dropParams` | comma list of body fields to remove, e.g. `temperature,top_p` |
| `endpoint.<name>.param.<field>` | extra body field, e.g. `param.reasoning_effort=max`; `true`/`false`/integers are sent as JSON values, a value starting with `{` or `[` as that JSON structure (invalid JSON disables the profile) |
| `endpoint.<name>.callsPerHour` | chat completions per player per rolling hour on this profile; 0 = no cap here |
| `endpoint.<name>.maxRequestChars` | content characters one chat request may carry (4096 to 262144); 0 keeps the mod's 24576, sized for small-context models |

A companion's decision turn asks for `response_format: {"type":"json_object"}`, so a JSON-mode
profile served by vLLM or OpenAI always answers with one JSON object. A request over its budget
loses its oldest history first; a status turn that still does not fit has its largest fields
(the command list first) shortened, never the player's words or the format reminder, and stays
valid JSON.

The tuning keys (`tokenParam` to `maxRequestChars`) also apply to the default profile under
`endpoint.<defaultEndpoint>.*`; its URL, model and key stay the top-level keys. An extra profile
without a URL, model or key is disabled: its companions' calls fail before any network I/O, with
one error line at startup, and the other profiles keep working. In dedicated client-proxy mode a
companion on a non-default profile is refused, because the client's own config would serve it.

**Operator instructions.** A top-level `"instructions"` in the characters file (a string or an
array of lines) is appended to every companion's system prompt, after the built-in guidelines.
Use it for persona policy such as staying in-world. Unlike a character's `description`, which is
saved with a summoned companion, it is read from the file, so an edit reaches companions already
in the world at their next prompt rebuild.

**Thinking off for a local model.** A Qwen-style model behind vLLM thinks before answering
unless its chat template is told not to; `reasoning_effort` does not switch that off. Set it on
that model's profile only, since other providers reject the field:
`endpoint.gx10.param.chat_template_kwargs={"enable_thinking":false}`. Whatever reasoning text a
model still returns in `content` (`<think>…</think>`, or text before a lone `</think>`) is
removed before the reply is parsed.

Build with `task build` (jar in `neoforge/build/libs/`); check the routing with `task test`.
Both need a JDK 21 (`JDK=<path>`, default the Temurin 21 install).

Companion rules (`config/playerengine-companion.properties`, see [NOTICE.md](NOTICE.md)):

| Key | Default | Meaning |
|---|---|---|
| `survivalParity` | `true` | mine and fight with a survival player's numbers; with hunger off, regenerate like a full food bar |
| `hunger` | unset | `true`/`false` overrides `hungerEnabled` in `playerengine/playerengine_settings.json` |
| `progressChat` | `off` | task progress in chat: `all`, `milestones` (outcomes and failures) or `off` |
| `peerReplies` | `1` | answers a companion may give other companions, only when named, before a human speaks again; `0` = never |
| `captureDecisions` | `false` | append each decision turn (messages, reply, dispatched command) to `playerengine/data/decisions.jsonl` for replay; the lines carry players' chat |

**Who hears, who commands.** Any player may give any companion any command or plan. Every companion
has a unique name, its owner's name and its own (`Arran's Ada`). Say the unique name to reach it
from anywhere in your dimension; its owner reaches it with the bare name (`Ada`) the same way.
Anyone else's bare name reaches a companion only when it is the only one of that name within 64
blocks; otherwise you are told the unique names to use. Unnamed chat reaches companions within 64
blocks. **stop Ada** (or `Arran's Ada, stop`) stops that one companion at once, from anyone, by the
same naming rules. Each player gets 60 model turns an hour across all companions and each companion
120; past that the companion says it is worn out. Companions never attack players, whoever asks.

**Long jobs (plans and area commands).** Ask your companion in chat; it turns the request into
commands itself. Any player may give it a plan or an area job.

| You say | What the companion runs |
|---|---|
| "dig out a 7 by 3 by 7 room here" | `excavate 7 3 7 anchor=owner` (7 wide, 3 high, 7 deep, starting one block in front of you) |
| "make the room 5 longer to the north" | `excavate 7 3 5 anchor=last facing=north` (extends the last area) |
| "clear from 100 60 -20 to 108 63 -12" | `excavate 100 60 -20 108 63 -12` (two corners, inclusive) |
| "put a cobblestone floor in it" | `fill cobblestone <x1> <y1> <z1> <x2> <y2> <z2>` (corners from the last area) |
| "dig a room, floor it and pick up the drops" | a plan of 2 or 3 steps: `excavate …`, `fill …`, `pickup_drops` |

One `excavate` is at most 32 wide, 8 high and about 20 minutes of digging; bigger jobs become
several plan steps. It leaves blocks people placed and containers standing, never breaks your
blocks next to the box, and refuses a box beside water or lava. `fill` needs the blocks in the
companion's inventory and says how many are missing. Drops left in a dug room despawn after 5
minutes unless the plan ends with `pickup_drops`.

If a plan is interrupted (another command replaces a step, or the server restarts), it pauses. Say
**continue** (or "keep going", "carry on", "resume") to pick it up where it stopped. Say
**stop** to end the current job and drop the plan.

## Upstream and credits

PlayerEngine is by **Goodbird**, made for the Player2 AI Game Jam, and builds on
[Automatone](https://github.com/Ladysnake/Automatone) and
[ChatClef](https://github.com/elefant-ai/chatclef). The full story, feature list and
acknowledgements are in the [upstream repository](https://github.com/shakey2/PlayerEngine).
