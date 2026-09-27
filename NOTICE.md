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

Files changed from upstream:

| File | Change |
|---|---|
| `common/src/main/java/com/player2/playerengine/player2api/gateway/GatewayConfig.java` | new: reads the gateway config file, an optional key file and `PLAYERENGINE_GATEWAY_*` environment overrides |
| `common/src/main/java/com/player2/playerengine/player2api/gateway/GatewayRouter.java` | new: splits forwarded OpenAI endpoints from locally answered Player2 endpoints |
| `common/src/main/java/com/player2/playerengine/player2api/gateway/GatewaySelfTest.java` | new: self-test of the routing through the production HTTP path |
| `common/src/main/java/com/player2/playerengine/player2api/utils/HTTPUtils.java` | gateway takeover in `sendRequest` and `sendRequestElement` |
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
