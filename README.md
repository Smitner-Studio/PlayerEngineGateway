# PlayerEngine: The AI Embodiment Framework for Minecraft

> **This is a modified PlayerEngine: the OpenAI-gateway fork (LGPL-3.0).** With
> `config/playerengine-gateway.properties` enabled, it talks only to the OpenAI-compatible
> gateway you configure, instead of the Player2 API and desktop app. See [NOTICE.md](NOTICE.md)
> for what changed and why; the upstream description follows unchanged.
>
> Configuration (`config/playerengine-gateway.properties`; each key can be overridden by an
> environment variable):
>
> | Key | Environment variable | Meaning |
> |---|---|---|
> | `enabled` | `PLAYERENGINE_GATEWAY_ENABLED` | `true` routes everything to the gateway |
> | `baseUrl` | `PLAYERENGINE_GATEWAY_URL` | gateway root, e.g. `http://host:4001/v1` |
> | `apiKey` | `PLAYERENGINE_GATEWAY_KEY` | sent as `Authorization: Bearer`; prefer the variable or the key file |
> | `apiKeyFile` | `PLAYERENGINE_GATEWAY_KEY_FILE` | used when no key is set: first line of this file in `config/` (default `playerengine-gateway.key`) |
> | `model` | `PLAYERENGINE_GATEWAY_MODEL` | model for chat completions |
> | `embeddingModel` | `PLAYERENGINE_GATEWAY_EMBEDDING_MODEL` | empty disables embeddings (memory falls back to lexical) |
> | `patronTier` | `PLAYERENGINE_GATEWAY_PATRON_TIER` | non-empty unlocks patron-only features (more LLM calls) |
> | `charactersFile` | `PLAYERENGINE_GATEWAY_CHARACTERS` | companion list in `config/`, same shape as Player2's `/v1/selected_characters` |
> | `defaultEndpoint` | — | name of the endpoint profile the keys above form (default `default`) |
>
> **Endpoint profiles.** A companion can use its own endpoint: give its entry in the characters
> file `"endpoint": "<name>"` and declare the profile with `endpoint.<name>.*` keys. Its chat
> completions then go only there, with only its key. Calls from other companions, and calls with no
> companion (memory extraction, mod-intelligence enrichment, embeddings), use the default profile.
>
> | Key | Meaning |
> |---|---|
> | `endpoint.<name>.baseUrl` | endpoint root, e.g. `https://api.openai.com/v1` (extra profiles only) |
> | `endpoint.<name>.model` | model id (extra profiles only) |
> | `endpoint.<name>.apiKeyEnv` | environment variable that holds the key (extra profiles only) |
> | `endpoint.<name>.apiKeyFile` | used when that variable is unset: first line of this file in `config/` (default `playerengine-gateway-<name>.key`) |
> | `endpoint.<name>.tokenParam` | `max_tokens` (default) or `max_completion_tokens`, the field the output limit is sent in |
> | `endpoint.<name>.maxOutputTokens` | replaces the mod's output limit (reasoning models spend reasoning tokens from it); 0 keeps it |
> | `endpoint.<name>.jsonMode` | `false` strips `response_format` (default `true`) |
> | `endpoint.<name>.dropParams` | comma list of body fields to remove, e.g. `temperature,top_p` |
> | `endpoint.<name>.param.<field>` | extra body field, e.g. `param.reasoning_effort=max`; `true`/`false`/integers are sent as JSON values, a value starting with `{` or `[` as that JSON structure (invalid JSON disables the profile) |
> | `endpoint.<name>.callsPerHour` | chat completions per player per rolling hour on this profile; 0 = no cap here |
>
> The tuning keys (`tokenParam` to `callsPerHour`) also apply to the default profile under
> `endpoint.<defaultEndpoint>.*`; its URL, model and key stay the top-level keys. An extra profile
> without a URL, model or key is disabled: its companions' calls fail before any network I/O, with
> one error line at startup, and the other profiles keep working. In dedicated client-proxy mode a
> companion on a non-default profile is refused, because the client's own config would serve it.
>
> **Operator instructions.** A top-level `"instructions"` in the characters file (a string or an
> array of lines) is appended to every companion's system prompt, after the built-in guidelines.
> Use it for persona policy such as staying in-world. Unlike a character's `description`, which is
> saved with a summoned companion, it is read from the file, so an edit reaches companions already
> in the world at their next prompt rebuild.
>
> **Thinking off for a local model.** A Qwen-style model behind vLLM thinks before answering
> unless its chat template is told not to; `reasoning_effort` does not switch that off. Set it on
> that model's profile only, since other providers reject the field:
> `endpoint.gx10.param.chat_template_kwargs={"enable_thinking":false}`. Whatever reasoning text a
> model still returns in `content` (`<think>…</think>`, or text before a lone `</think>`) is
> removed before the reply is parsed.
>
> Build with `task build` (jar in `neoforge/build/libs/`); check the routing with `task test`.
> Both need a JDK 21 (`JDK=<path>`, default the Temurin 21 install).
>
> Companion rules (`config/playerengine-companion.properties`, see [NOTICE.md](NOTICE.md)):
>
> | Key | Default | Meaning |
> |---|---|---|
> | `survivalParity` | `true` | mine and fight with a survival player's numbers; with hunger off, regenerate like a full food bar |
> | `hunger` | unset | `true`/`false` overrides `hungerEnabled` in `playerengine/playerengine_settings.json` |
> | `progressChat` | `off` | task progress in chat: `all`, `milestones` (outcomes and failures) or `off` |

[![Player2 AI Game Jam](https://img.shields.io/badge/Player2-AI_Game_Jam-blueviolet)](https://itch.io/jam/ai-npc-jam)
[![Powered by Automatone](https://img.shields.io/badge/Powered%20by-Automatone-orange)](https://github.com/Ladysnake/Automatone/tree/1.20)
[![Based on ChatClef](https://img.shields.io/badge/Based%20on-ChatClef-9cf)](https://github.com/elefant-ai/chatclef/tree/main)

**PlayerEngine** is a server-side framework designed to fundamentally change how AI NPCs exist in Minecraft. Developed by **Goodbird**, this project moves beyond the limitations of client-side mods, offering a powerful toolkit to give **your own custom mobs** the full capabilities of a player.

This project was born from the desire to transcend the gimmick of chatbot NPCs and create truly embodied agents for the **Player2 AI Game Jam**. It's not about making vanilla pigs talk; it's about empowering developers to create entities that can mine, fight, manage an inventory, and interact with the world on a player's level.

## The Story: From Client-Side Hacks to a True Framework

The inspiration for PlayerEngine came from **ChatClef**, an innovative mod by Player2 that connected an AI to the player's client. While groundbreaking, it had a significant limitation: to have AI companions, one had to run multiple instances of the Minecraft client. This was cumbersome and not scalable.

PlayerEngine solves this problem by moving the logic to the server and, most importantly, decoupling player-like abilities from the `PlayerEntity` class itself. The result is a true framework that allows any modder to grant their custom `LivingEntity` the soul of a player.

## The Core Concept: The "Player" as an Interface

At its heart, PlayerEngine treats "being a player" not as a specific entity type, but as a set of capabilities that can be attached to any mob. By implementing a few simple interfaces, your custom mob gains access to:

*   A persistent, player-like inventory (`LivingEntityInventory`).
*   The ability to interact with the world, breaking blocks and using items (`LivingEntityInteractionManager`).
*   Advanced pathfinding and task execution via the powerful **Automatone** engine.

This makes PlayerEngine the ultimate "actuator" layer for an AI "brain" like the one provided by the **Player2 API**. Your LLM can decide *what* to do, and PlayerEngine gives your NPC the body to *do it*.

## Key Features for Developers

*   **🤖 Empower Your Mobs:** Designed for modders. Easily transform your own custom entities into player-like agents. Don't just reskin a vanilla mob—give your unique creations true agency.
*   **⛏️ True World Interaction:** NPCs can mine blocks, use tools, and interact with objects. *(Note: complex building is not yet supported).*
*   **🎒 Player-Like Inventories:** Each agent manages its own persistent inventory, allowing for complex resource gathering, crafting, and tool management.
*   **🧠 Seamless Player2 Integration:** PlayerEngine is the perfect physical counterpart to the Player2 API. Send high-level commands like `@get diamond 5` and watch your agent execute a complex chain of tasks to achieve the goal.
*   **🛠️ Built on a Solid Foundation:**
    *   **Navigation:** Powered by **Automatone**, a fork of the legendary Baritone pathfinding engine.
    *   **Task System:** Adapts the robust task and command system from Player2's **ChatClef**.
    *   **Modularity:** Uses **Cardinal Components** to cleanly attach capabilities, ensuring high compatibility and easy integration.

## Why PlayerEngine is "Beyond an AI Gimmick"

*   **Integration:** It's a framework for deep systemic integration. NPCs are no longer just quest-givers; they are active participants in the game's economy, ecology, and emergent stories.
*   **Guardrails:** PlayerEngine *is* the guardrail. It provides a deterministic, game-logic-based action layer that reliably executes the high-level goals from an LLM, complete with fallbacks and a robust understanding of the game world.
*   **Creativity:** It empowers *other creators*. We're not just showing one cool NPC; we're giving the entire community a tool to build their own intelligent companions, adversaries, and dynamic storytellers.
*   **Stability:** Built on the shoulders of giants—Baritone and Cardinal Components—PlayerEngine is a stable and performant foundation for ambitious AI projects.

## Acknowledgements

This project stands on the work and support of many.

### Player2
This framework was created for the **Player2 AI Game Jam** and is designed to integrate seamlessly with the Player2 API, realizing their vision for intelligent, interactive agents.
> *We are a team of researchers and engineers that are passionate about advancing the state of the art in AI. Our team members have worked at some of the world's leading tech companies and research institutions, and we are united by our shared vision of building intelligent agents that can interact with the world in a meaningful way.*

### Foundation & Inspiration
*   **Automatone / Baritone:** The powerful navigation of PlayerEngine is provided by Automatone, a fork of the legendary Baritone pathfinding engine.
*   **ChatClef:** The robust task and command system is adapted from the original ChatClef mod by Player2, which proved the potential of AI agents in Minecraft.

### Special Thanks
*   **Itsuka:** For his invaluable guidance with the Player2 API, brainstorming sessions, and rigorous testing that helped shape PlayerEngine into what it is today.

### Author
PlayerEngine is a solo project developed by **Goodbird**.