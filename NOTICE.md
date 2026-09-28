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
A profile's `param.<field>` written as a JSON object or array is sent as that structure, which is
how a LAN model's thinking is switched off on its profile alone
(`param.chat_template_kwargs={"enable_thinking":false}`). A `<think>` block, or reasoning text
closed by a lone `</think>`, is removed from a chat completion's content before it is parsed.

Companion LLM calls are dispatched on lanes keyed by billing key and endpoint profile, where
upstream had one lane per billing key. Each lane still carries one request at a time, so one
companion's calls stay in order, while two companions of the same player on different profiles
(a LAN model and OpenAI, say) think at the same time instead of queueing behind each other. A
companion's conversation summary runs inside its own lane. Hourly caps and the per-player budget
are unchanged. Idle lanes are dropped after ten minutes and rebuilt on the next call.

A companion's decision turn asks for `response_format: {"type":"json_object"}` (upstream sent it
only for memory extraction and enrichment); a profile with `jsonMode=false` still strips it. A
profile's `maxRequestChars` replaces the mod-wide 24576-character request budget for its
companions. A status turn over the budget has its largest fields shortened, never `userMessage` or
`reminders`, and stays one JSON object, where upstream cut the serialized object at the budget and
left an unterminated string. A reply that is not JSON is retried with feedback to the model and no
longer announced in chat ("... had trouble understanding that"); after the retries the turn ends
quietly and the running task continues.

Files changed from upstream:

| File | Change |
|---|---|
| `common/src/main/java/com/player2/playerengine/player2api/gateway/GatewayConfig.java` | new: reads the gateway config file, an optional key file, `PLAYERENGINE_GATEWAY_*` environment overrides and endpoint profiles |
| `common/src/main/java/com/player2/playerengine/player2api/gateway/EndpointProfile.java` | new: one endpoint profile and its request-body rewrite |
| `common/src/main/java/com/player2/playerengine/player2api/gateway/GatewayCallContext.java` | new: the calling companion's character and billing key for the current thread |
| `common/src/main/java/com/player2/playerengine/player2api/gateway/GatewayRouter.java` | new: splits forwarded OpenAI endpoints from locally answered Player2 endpoints; picks the endpoint profile, enforces its hourly cap and strips reasoning text from chat content |
| `common/src/main/java/com/player2/playerengine/player2api/gateway/GatewaySelfTest.java` | new: self-test of the routing through the production HTTP path |
| `common/src/main/java/com/player2/playerengine/player2api/gateway/GatewayProfilesSelfTest.java` | new: self-test of per-character endpoint profiles against two loopback gateways |
| `common/src/main/java/com/player2/playerengine/player2api/gateway/GatewayLanesSelfTest.java` | new: self-test of parallel dispatch lanes against two slow loopback gateways |
| `common/src/main/java/com/player2/playerengine/player2api/gateway/GatewayJsonSelfTest.java` | new: self-test of the decision turn's JSON request, request budget and parse-failure handling |
| `common/src/main/java/com/player2/playerengine/player2api/DecisionTurnProbe.java` | new: self-test access to the decision turn through the gateway |
| `common/src/main/java/com/player2/playerengine/player2api/LogEgressGuard.java` | per-request budget; status turns trimmed by field instead of cut |
| `common/src/main/java/com/player2/playerengine/player2api/manager/LlmLanes.java` | new: LLM dispatch lanes per billing key and endpoint profile |
| `common/src/main/java/com/player2/playerengine/player2api/manager/ConversationManager.java` | dispatches companions through `LlmLanes` |
| `common/src/main/java/com/player2/playerengine/player2api/utils/HTTPUtils.java` | gateway takeover in `sendRequest` and `sendRequestElement`; gateway chat responses lose reasoning text |
| `common/src/main/java/com/player2/playerengine/player2api/Player2APIService.java` | companion calls carry their character and billing key to the gateway router; decision turns request a JSON object within the profile's request budget |
| `common/src/main/java/com/player2/playerengine/player2api/Player2ApiDispatcher.java` | client-proxy relay refused for a companion on a non-default endpoint profile |
| `common/src/main/java/com/player2/playerengine/player2api/Prompts.java` | appends the characters file's operator `instructions` to the companion system prompt when the gateway is enabled |
| `common/src/main/java/com/player2/playerengine/MCCommands.java` | loads the gateway config and companion rules at server start, so their log lines appear then |
| `common/src/main/java/com/player2/playerengine/player2api/auth/AuthenticationManager.java` | no Player2 login when the gateway is enabled |
| `common/src/main/java/com/player2/playerengine/player2api/auth/TokenStorage.java` | placeholder token instead of stored Player2 tokens when the gateway is enabled |
| `common/src/main/java/com/player2/playerengine/player2api/Player2PayerResolution.java` | server-wide work is billable with no player online when the gateway is enabled |
| `common/src/main/java/com/player2/playerengine/player2api/utils/AudioUtils.java` | text-to-speech skipped when the gateway is enabled |
| `common/build.gradle` | `gatewaySelfTest` task |
| `gradle.properties` | version `1.21.1-1.4.0-gateway.7` |
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
- `peerReplies=1` (default): a line another companion speaks wakes this companion's model only when
  it names this companion, and at most this many times until a human speaks to it again. Other peer
  lines are kept as context for its next turn. `0` means companions never answer each other.
  Upstream woke every companion within 64 blocks on every line another companion spoke.

Under `survivalParity`, a digging task's time budget (`mine`) is 2.5 times upstream's, the worst
slowdown of an unenchanted pickaxe short of gold.

Independently of these rules, taking items from a container no longer inserts one extra item per
take (the room check used to insert a probe item), and a loot take that only partly fits leaves
the rest in the container instead of deleting it.

Deposits no longer create items either: the simulated insert grew the container's matching stack,
so merging into a part stack added the items twice. A deposit counts what it has moved rather than
what the container holds, so a container that already has some of the item takes everything asked
for. Container tasks walk to any spot within 3 blocks of the container instead of into its block,
which pathing never reaches (it does not break chests or player-placed blocks), and the direct
`deposit` budget grows with the distance to the container. A body-language gesture no longer
replaces a running task that cannot resume after it; the gesture is skipped. The bottom-slab
correction of the companion's feet position reads the chunk it stands in (it read a chunk sixteen
times further out, so the correction never ran and traverses off a slab stalled).

`mine` documents that it takes the block as it stands in the world (stone, not the cobblestone it
drops; its upstream example was `mine cobblestone 32`), and a search for cobblestone or cobbled
deepslate that finds none tells the model which block to mine instead. `deposit` names the verbs
players use for it (put, drop off, store, dump), so command retrieval finds it.

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
| `common/src/main/java/com/player2/playerengine/player2api/PeerTalkPolicy.java` | new: when another companion's line wakes this one |
| `common/src/main/java/com/player2/playerengine/player2api/AgentConversationData.java` | peer lines gated by `PeerTalkPolicy`; the rest kept as context; unparseable replies retried without a chat line |
| `common/src/main/java/com/player2/playerengine/commands/MineCommand.java` | block-not-item guidance and drop-source hint |
| `common/src/main/java/com/player2/playerengine/commands/DepositCommand.java` | description names deposit's synonyms |
| `common/src/main/java/com/player2/playerengine/tasks/container/ContainerDeposit.java` | new: item-conserving move into a container |
| `common/src/main/java/com/player2/playerengine/tasks/container/ContainerApproach.java` | new: walk to within reach of a container |
| `common/src/main/java/com/player2/playerengine/tasks/container/StoreInContainerTask.java` | moves the owed count through `ContainerDeposit` |
| `common/src/main/java/com/player2/playerengine/tasks/container/StoreInAnyContainerTask.java` | deposit budget grows with the distance |
| `common/src/main/java/com/player2/playerengine/tasks/container/BoundedContainerDepositTask.java` | comments only |
| `common/src/main/java/com/player2/playerengine/tasks/container/{BoundedContainerTransfer,SlotPreciseTransaction,ScanContainer,LootContainer,PickupFromContainer,UpgradeInSmithingTable}Task.java`, `tasks/agentic/ResolveStorageChestTask.java` | approach through `ContainerApproach` |
| `common/src/main/java/com/player2/playerengine/tasks/agentic/MineBlockTask.java` | time budget scaled by `digTimeScale` |
| `common/src/main/java/com/player2/playerengine/chains/UserTaskChain.java` | skips a gesture that would drop a non-resumable task |
| `common/src/main/java/com/player2/playerengine/automaton/utils/player/EntityContext.java` | slab check reads the feet's own chunk |
| `common/src/main/java/com/player2/playerengine/{tasks/container/ContainerDeposit,player2api/PeerTalkPolicy,chains/GestureGuard,automaton/utils/player/FeetChunk}SelfTest.java` | new: self-tests run by `companionSelfTest` |

## Companion plans and area commands (gateway.6)

A companion's decision reply may carry a `plan`: a goal and up to 8 steps, one command line per
step (a step containing `;` is refused). The first step starts at once and each later step is
dispatched on the server tick after the previous one finishes. A step that another command
replaced reports Finished upstream; here a dispatch counter (`commandDispatchSeq`, moved by every
command line except a line of gestures only and the task chain's own idle fallback) marks it
superseded, and the plan pauses instead of advancing. A failed step goes back to the model for a
revised plan. Plans are budgeted per owner (24 model calls in a rolling hour across all their
companions, 8 per plan), expire after 60 idle minutes, and cancel a step still running after 25
minutes. A plan is saved to `plan.json` beside the companion's conversation files, survives a
server stop, and loads paused; it resumes only when the owner says "continue" (or "keep going",
"carry on", "resume" and a few similar whole-message phrases). `stop` drops the plan.

Two area commands, `excavate` and `fill`, clear a box to air or fill it from the inventory through
the embedded builder. A box is at most 32 per axis, 8 high, 2048 cells (512 for `fill`) and an
estimated 20 minutes of survival digging, within 48 blocks of the companion, in the overworld,
the nether or the end, inside the world border and outside spawn protection. A box with water
(waterlogged blocks included) or lava in it or its shell is refused, as is a box under a column
of more than 6 falling blocks. Success is judged from the world (every target cell cleared or
filled), not from the builder going idle.

**Owner only.** Creating, resuming or repairing a plan, and `excavate` or `fill` (sent directly,
as a plan step, or in any `;` part of a line), are accepted only on a turn started by the
companion's owner, decided by the authenticated sender UUID of the chat or voice packet, never by
a name in the text. Anyone else is declined. Other commands keep upstream's rules.

**Protection.** During an area job the companion does not break player-placed blocks inside the
box or around it. Inside the box, player-placed blocks and block entities are left out of the
targets and stay standing (more than 8 such cells refuse the box unless the owner repeats it with
`confirm=yes`). In the box's shell (1 block on the sides and below, 2 above) player-placed blocks
are a hard no-break for the builder (`BuilderProcess.setHardNoBreak`). Beyond the shell,
upstream's finite break-cost penalty for player-placed blocks is unchanged.

**Sable gap.** `AreaVetoes` is the hook where a "not on a Sable ship sub-level" refusal belongs,
but this fork has no Sable dependency and no Sable API to ask, so the hook ships empty: an area
command does not know whether a box lies on a Sable sub-level (`claude/planner-design.md` §6,
"Where a box may be").

**Drops.** `excavate` has no pickup sweep of its own. The companion collects what it walks
through; anything left on the floor despawns after the vanilla 5 minutes unless a `pickup_drops`
step follows the dig in the plan.

**Smoke harness.** `/playerengine smoke <scenario>` (op-only; a fake owner and stranger drive a
summoned companion against a mock model) exists only when the server JVM runs with
`-Dplayerengine.smoke=true`. Without that property the command is not registered.

Fixes in the same release: a chat or error line for an owner who is offline is logged and skipped
instead of throwing on the server tick; a working companion holds its own chunk and all eight
neighbours (upstream forced a floor/ceil 2x2 set that could miss the chunk it stepped into,
freezing it there).

| File | Change |
|---|---|
| `common/src/main/java/com/player2/playerengine/player2api/plan/PlanParser.java` | new: validates the `plan` field; one command per step; resume phrases |
| `common/src/main/java/com/player2/playerengine/player2api/plan/CompanionPlan.java` | new: one companion's plan state |
| `common/src/main/java/com/player2/playerengine/player2api/plan/PlanCoordinator.java` | new: plan lifecycle, supersession, clocks and repair under one monitor |
| `common/src/main/java/com/player2/playerengine/player2api/plan/PlanBudget.java` | new: per-owner model-call budget for plans |
| `common/src/main/java/com/player2/playerengine/player2api/plan/PlanStore.java` | new: `plan.json` load and save |
| `common/src/main/java/com/player2/playerengine/player2api/plan/OwnerGate.java` | new: owner-only decision by authenticated UUID, across every `;` part |
| `common/src/main/java/com/player2/playerengine/player2api/plan/PlanSelfTest.java` | new: planner and area-bound self-test with a mock model and host |
| `common/src/main/java/com/player2/playerengine/tasks/construction/area/AreaSpec.java` | new: box grammar and bounds |
| `common/src/main/java/com/player2/playerengine/tasks/construction/area/AreaScan.java` | new: pre-scan, time cap and verdict |
| `common/src/main/java/com/player2/playerengine/tasks/construction/area/AreaBuildTask.java` | new: masked builder run, watchdog, settings save and restore |
| `common/src/main/java/com/player2/playerengine/tasks/construction/area/AreaVetoes.java` | new: box veto hook, empty (Sable gap above) |
| `common/src/main/java/com/player2/playerengine/tasks/construction/area/AreaSelfTest.java` | new: area self-test, run by `planSelfTest` |
| `common/src/main/java/com/player2/playerengine/commands/AreaCommand.java` | new: shared `excavate`/`fill` command; world-border, spawn and dimension checks |
| `common/src/main/java/com/player2/playerengine/commands/ExcavateCommand.java`, `commands/FillCommand.java` | new: the two area commands |
| `common/src/main/java/com/player2/playerengine/PlayerEngineCommands.java` | registers `excavate` and `fill` |
| `common/src/main/java/com/player2/playerengine/commands/base/Command.java` | `isIdempotent()`, default false |
| `common/src/main/java/com/player2/playerengine/commands/GotoCommand.java` | idempotent, so "continue" re-dispatches it directly |
| `common/src/main/java/com/player2/playerengine/commands/base/CommandExecutor.java` | moves the dispatch seq for each command line, not for gestures only or the idle fallback |
| `common/src/main/java/com/player2/playerengine/PlayerEngineController.java` | dispatch seq, last area, plan status line, last owner message time |
| `common/src/main/java/com/player2/playerengine/chains/UserTaskChain.java` | exposes whether the idle fallback is being installed |
| `common/src/main/java/com/player2/playerengine/automaton/api/process/IBuilderProcess.java`, `automaton/process/BuilderProcess.java` | per-run hard no-break set for the shell |
| `common/src/main/java/com/player2/playerengine/player2api/AgentConversationData.java` | `plan` field, owner gate, tick-deferred step dispatch, stop clears the plan, `plan.json` |
| `common/src/main/java/com/player2/playerengine/player2api/AgentSideEffects.java` | reports the accepted dispatch seq; skips chat to an offline owner |
| `common/src/main/java/com/player2/playerengine/player2api/OfflineOwnerChatSelfTest.java` | new: offline-owner chat self-test |
| `common/src/main/java/com/player2/playerengine/player2api/AIPersistantData.java` | path of `plan.json` |
| `common/src/main/java/com/player2/playerengine/player2api/manager/ConversationManager.java` | ticks plans; a server stop keeps saved plans |
| `common/src/main/java/com/player2/playerengine/MCCommands.java` | keeps plans across a stop; registers the smoke command only under `-Dplayerengine.smoke=true` |
| `common/src/main/java/com/player2/playerengine/player2api/Prompts.java` | `plan` field and long-jobs guidance in the system prompt |
| `common/src/main/java/com/player2/playerengine/player2api/status/AgentStatus.java` | `activePlan` and `lastArea` in the model's status |
| `common/src/main/java/com/player2/playerengine/retrieval/SeedToolMetadata.java` | retrieval documents for `excavate` and `fill`; removed from `mine` keywords |
| `common/src/main/java/com/player2/playerengine/trackers/ChunkLoadingTracker.java` | holds the companion's chunk and its eight neighbours |
| `common/src/main/java/com/player2/playerengine/trackers/ChunkHoldSelfTest.java` | new: chunk-hold self-test |
| `common/src/main/java/com/player2/playerengine/companion/CompanionRulesSelfTest.java` | runs the offline-owner and chunk-hold self-tests |
| `common/src/main/java/com/player2/playerengine/smoke/*` | new: op-only `/playerengine smoke <scenario>` live test harness (fake owner and stranger, mock-model scenarios) |
| `common/src/main/resources/assets/playerengine/lang/en_us.json` | help text for the smoke command |
| `common/build.gradle`, `Taskfile.yml` | `planSelfTest` and `smokeGateSelfTest` tasks, run by `task test` |
| `gradle.properties` | version `1.21.1-1.4.0-gateway.6` |

## Greeting and chunk fixes (gateway.7)

**The greeting no longer eats "continue".** Upstream forced the first reply of every fresh
conversation to `bodylang greeting`, and that turn skipped the plan and owner rules. After a
restart or re-attach the owner's first "continue" or plan was dropped. A turn is now forced to the
greeting only when a greeting was actually queued (a first meeting), and never when its batch
carries a message from the owner: that turn runs normally and greets through the `[bl:greeting]`
marker. A resume phrase now applies only to the turn it arrived in.

| File | Change |
|---|---|
| `common/src/main/java/com/player2/playerengine/player2api/AgentConversationData.java` | greeting armed when queued, decided per batch, never over an owner message |
| `common/src/main/java/com/player2/playerengine/player2api/AIPersistantData.java` | `returnGreets()`: whether the return event is a greeting |
| `common/src/main/java/com/player2/playerengine/player2api/plan/PlanCoordinator.java` | `beginTurn()` clears a resume request left by an earlier turn |
| `common/src/main/java/com/player2/playerengine/player2api/plan/PlanSelfTest.java` | a continue applies only to its own turn |
| `common/src/main/java/com/player2/playerengine/smoke/SmokeHarness.java` | `resume` scenario: one continue after re-attach resumes the plan |
| `common/src/main/resources/assets/playerengine/lang/en_us.json` | smoke scenario list |

**Companions release only the chunks they forced.** Upstream un-forced any chunk a companion left,
including chunks an operator's `/forceload` or another mod had forced, keyed holds by chunk
coordinates without the dimension, and never released a hold when a companion was removed. A
companion now never claims a chunk that is already forced, keys holds by dimension and chunk,
releases its whole 3x3 hold when Player2NPC removes it, and every hold is released at server stop
so none is saved into the world. Still open until the ticket-based fix: a chunk forced by someone
else after a companion claimed it is released when the companion leaves.

| File | Change |
|---|---|
| `common/src/main/java/com/player2/playerengine/util/ChunkController.java` | holds by dimension and chunk; never claims a forced chunk; release-all per companion and at stop |
| `common/src/main/java/com/player2/playerengine/util/ChunkControllerSelfTest.java` | new: chunk ownership self-test, run by `companionSelfTest` |
| `common/src/main/java/com/player2/playerengine/trackers/ChunkLoadingTracker.java` | re-asserts the hold each second; releases it on a dimension change and on reset |
| `common/src/main/java/com/player2/playerengine/PlayerEngineController.java` | removal and stale-controller pruning release the companion's chunks |
| `common/src/main/java/com/player2/playerengine/MCCommands.java` | releases every companion-forced chunk at server stop |
| `common/src/main/java/com/player2/playerengine/companion/CompanionRulesSelfTest.java` | runs the chunk ownership self-test |
| `common/src/main/java/com/player2/playerengine/smoke/SmokeHarness.java` | `chunks` and `despawn` scenarios |
| `gradle.properties` | version `1.21.1-1.4.0-gateway.7` |

## Live fixes (gateway.8)

**Companions perceive only what a player could.** Upstream targeted and reported every block the
scanner read from the world's data, buried ore included, and listed every hostile within 32 blocks
with its coordinates, through walls. Block targets from the scanner (the collect and mine path) and
the status tail's nearby blocks now count only exposed blocks: at least one face touching air,
fluid, or a block that is not a full opaque cube. The status tail's hostiles are only those in line
of sight from the companion's eyes, given as kind, a distance rounded to 5 blocks and a compass
direction, without coordinates.

| File | Change |
|---|---|
| `common/src/main/java/com/player2/playerengine/util/Perception.java` | new: exposure test, nearest exposed candidate, hostiles in sight |
| `common/src/main/java/com/player2/playerengine/util/PerceptionSelfTest.java` | new: buried ore never a candidate nor reported; occluded hostiles not listed |
| `common/src/main/java/com/player2/playerengine/commands/BlockScanner.java` | nearest-block and any-found queries consider exposed blocks only |
| `common/src/main/java/com/player2/playerengine/player2api/status/StatusUtils.java` | nearby blocks exposed only; hostiles in line of sight, no coordinates |
| `common/src/main/java/com/player2/playerengine/companion/CompanionRulesSelfTest.java` | runs the perception self-test |

**Companion chunk holds are owner-tagged, persistent tickets (NeoForge).** The 3x3 hold is now a
set of entity tickets from a registered `TicketController` (`playerengine:companion_holds`), one per
(dimension, chunk, companion). A companion adds and removes only its own tickets, so an operator's
`/forceload`, another mod's ticket or another companion's hold is never touched, and a chunk forced
by someone else after the companion arrived now stays forced when it leaves. Despawn and dismissal
release all 9. Holds survive a restart: nothing is released while the server stops, NeoForge saves
the tickets with the level and reinstates them at load, and the companion claims its hold when it
next ticks. Liveness cannot be judged when NeoForge's load callback runs (entities are not loaded
yet), so a sweep after start releases a loaded hold that records no owner, and one whose owner has
been online for three sweeps (about 30 s) without the companion returning. A hold whose owner stays
offline is kept. The companion-to-owner record is `data/playerengine_companion_holds.dat` in the
overworld. The sweep never touches vanilla forced chunks, including any a gateway.7 crash left
behind. On Fabric, which has no ticket API here, holds fall back to gateway.7's vanilla forced
flags: in memory, and released at server stop.

| File | Change |
|---|---|
| `common/src/main/java/com/player2/playerengine/util/ChunkHolds.java` | new: where holds live; NeoForge installs tickets, Fabric keeps the vanilla fallback |
| `common/src/main/java/com/player2/playerengine/util/TicketBook.java` | new: ticket bookkeeping keyed by dimension, chunk and companion; stop keeps holds; stale sweep |
| `common/src/main/java/com/player2/playerengine/util/TicketBookSelfTest.java` | new: all 9 released, dimension in the key, others' tickets kept, holds kept through stop, stale sweep |
| `common/src/main/java/com/player2/playerengine/util/TicketChunkHolds.java` | new: tickets through a platform seam, the owner record, sweep on start and every 200 ticks |
| `common/src/main/java/com/player2/playerengine/util/VanillaChunkHolds.java` | new: the Fabric fallback over `ChunkController` |
| `neoforge/src/main/java/com/player2/playerengine/forge/NeoForgeChunkTickets.java` | new: the `TicketController`, its load callback, and the saved tickets read back for the smoke gate |
| `neoforge/src/main/java/com/player2/playerengine/forge/PlayerEngineForge.java` | registers the ticket controller on the mod bus |
| `common/src/main/java/com/player2/playerengine/trackers/ChunkLoadingTracker.java` | holds exactly the companion's 3x3 through `ChunkHolds`, once a second |
| `common/src/main/java/com/player2/playerengine/PlayerEngineController.java` | removal, pruning and the 200-tick sweep go through `ChunkHolds` |
| `common/src/main/java/com/player2/playerengine/MCCommands.java` | start sweep; at stop, tickets are kept and the Fabric fallback releases |
| `common/src/main/java/com/player2/playerengine/companion/CompanionRulesSelfTest.java` | runs the ticket self-test |
| `common/src/main/java/com/player2/playerengine/smoke/SmokeHarness.java` | `despawn` reads the ticket store; `chunk-hold` and `chunk-hold-restart` scenarios |
| `common/src/main/resources/assets/playerengine/lang/en_us.json` | smoke scenario list |

**`reason` is one sentence.** The system prompt asked for step-by-step reasoning in `reason`, which
nothing reads after parsing and which runs to hundreds of tokens per turn. It now asks for one short
sentence of at most 200 characters. The field stays (it is the model's room to pick a command until
programs replace commands); there is no response-side trim, because an output cap shorter than the
reason truncates the reply before `command` and fails the parse.

| File | Change |
|---|---|
| `common/src/main/java/com/player2/playerengine/player2api/Prompts.java` | `reason`: one sentence, at most 200 characters |

**Any player may command any companion (R1).** gateway.6 let only the authenticated owner run
`excavate` and `fill` or give, resume or cancel a plan. Both gates are gone: the command check
(`OwnerGate.OWNER_ONLY_COMMAND_IDS`) and the plan check (`PlanCoordinator`, which authorised only
owner turns). A turn now belongs to its authenticated sender, and a command-feedback turn to the
player whose chat started the chain; any such turn may plan, resume or cancel, and each player's
plans draw on that player's own hourly allowance. A line with no authenticated sender still cannot
plan. Another companion's chat still cannot start `excavate` or `fill`. The owner-only stop phrase
is unchanged here.

| File | Change |
|---|---|
| `common/src/main/java/com/player2/playerengine/player2api/plan/OwnerGate.java` | a turn belongs to its authenticated sender or the chain's; owner-only set removed; peer-refused area commands |
| `common/src/main/java/com/player2/playerengine/player2api/plan/PlanCoordinator.java` | any player's turn may plan, resume and cancel; `Turn` carries the initiator only |
| `common/src/main/java/com/player2/playerengine/player2api/plan/PlanSelfTest.java` | a stranger's plan starts, resumes and cancels; no player, no plan |
| `common/src/main/java/com/player2/playerengine/player2api/AgentConversationData.java` | owner-only refusal removed; the chain remembers its initiating player |
| `common/src/main/java/com/player2/playerengine/player2api/Prompts.java` | any player may give a command or a plan |
| `common/src/main/java/com/player2/playerengine/smoke/SmokeHarness.java` | `stranger`: a second player's excavate command and plan each clear a box |

**Companions never attack players (R2).** Upstream's `attack` took a player name, and a
companion hit twice by a player retaliated until it forgot or killed them. Every companion hit goes
through `PlayerExtraController.attack`, which now refuses any player target; `attack` with a player
name (or the word "player") fails with a stated reason before a task starts, and its target filter
skips players. The player-retaliation chain is removed. Hero and hostile defence already target
monsters only.

| File | Change |
|---|---|
| `common/src/main/java/com/player2/playerengine/companion/NoPvp.java` | new: the fixed rule and the player-name check |
| `common/src/main/java/com/player2/playerengine/control/PlayerExtraController.java` | never hits a player |
| `common/src/main/java/com/player2/playerengine/commands/AttackPlayerOrMobCommand.java` | refuses a player target; the filter skips players |
| `common/src/main/java/com/player2/playerengine/chains/PlayerDefenseChain.java` | removed: retaliation against players |
| `common/src/main/java/com/player2/playerengine/tasks/entity/KillPlayerTask.java` | removed: only the retaliation chain used it |
| `common/src/main/java/com/player2/playerengine/PlayerEngineController.java` | no player-defence chain |
| `common/src/main/java/com/player2/playerengine/chains/FoodChain.java`, `commands/SetFollowModeCommand.java` | comments no longer name the removed chain |
| `common/src/main/java/com/player2/playerengine/smoke/SmokeHarness.java` | `attack` scenario |
| `common/src/main/resources/assets/playerengine/lang/en_us.json` | smoke scenario list |

**Decision capture for replay.** With `captureDecisions=true` in
`config/playerengine-companion.properties` (default `false`), every decision turn appends one JSON
line to `playerengine/data/decisions.jsonl`: the messages the model saw, its reply, and the command
the turn dispatched. A command's outcome is in the next line's messages, as the model's feedback.
The lines carry players' chat.

| File | Change |
|---|---|
| `common/src/main/java/com/player2/playerengine/player2api/DecisionCapture.java` | new: the capture line and the append |
| `common/src/main/java/com/player2/playerengine/player2api/DecisionCaptureSelfTest.java` | new: line shape and appending |
| `common/src/main/java/com/player2/playerengine/companion/CompanionRules.java` | `captureDecisions` key |
| `common/src/main/java/com/player2/playerengine/player2api/AgentConversationData.java` | records each decision turn after the plan rules |
| `common/src/main/java/com/player2/playerengine/companion/CompanionRulesSelfTest.java` | capture default and parsing; runs the capture self-test |

**Unique names, hearing by name, and a stop anyone can give (R8 with R1).** Every companion answers
to a unique name, its owner's name and its own: `Arran's Ada` (Player2NPC keeps one companion per
character per owner, and the call-by-name parser already reads this possessive form). A unique name
reaches that companion from anyone, at any distance in the speaker's dimension. A bare name from the
companion's owner reaches the owner's own companion the same way. A bare name from anyone else
reaches a companion only when it is the only one of that name within 64 blocks; otherwise nothing is
delivered and the speaker is told the unique names to use. A unique name in another dimension gets
a "too far" reply. Unnamed chat, and chat with call-by-name off, still reach only companions within
64 blocks. A name never reaches two companions. Upstream sent another player's bare name to the
nearest match at any range, and a qualified mention could reach a second companion through the bare
name inside it.

The model-bypassing stop lane (`stop <name>`, `<name> stop`) is open to every authenticated player
with no proximity check, under the same naming rules: a unique name stops that companion, a bare
name stops the speaker's own companion or the only one of that name within 64 blocks, and anything
else stops none and asks which. The speaker, not the owner, gets the acknowledgement.

| File | Change |
|---|---|
| `common/src/main/java/com/player2/playerengine/player2api/CompanionAddress.java` | new: which companion a unique or bare name reaches |
| `common/src/main/java/com/player2/playerengine/player2api/CompanionAddressSelfTest.java` | new: reach, too far, which-one, no fan-out, strict stop lines, a bare stop stops at most one |
| `common/src/main/java/com/player2/playerengine/player2api/CallByNameMentionRouter.java` | resolves mentions through `CompanionAddress` over every companion; reports too-far and which-one |
| `common/src/main/java/com/player2/playerengine/player2api/StopIntent.java` | new, replaces `OwnerStopIntent`: stop lines with a bare or unique name |
| `common/src/main/java/com/player2/playerengine/player2api/OwnerStopIntent.java`, `OwnerStopTargetResolution.java` | removed |
| `common/src/main/java/com/player2/playerengine/player2api/manager/ConversationManager.java` | routes chat and stops by name across the dimension; tells the speaker too far or which one |
| `common/src/main/java/com/player2/playerengine/player2api/ConversationControlSelfTest.java` | stop-line parsing through `StopIntent` |
| `common/src/main/java/com/player2/playerengine/companion/CompanionRulesSelfTest.java` | runs the address self-test |
| `common/src/main/java/com/player2/playerengine/smoke/SmokeHarness.java` | `far-owner` scenario |
| `common/src/main/resources/assets/playerengine/lang/en_us.json` | too-far and which-one replies; smoke scenario list |

**Model-turn caps.** Each speaking player gets 60 model turns an hour across all companions, and
each companion 120 in total, over a rolling hour, whatever endpoint the companion uses (gx10 had no
cap). A turn belongs to the player whose line is in it, or to the player whose chat started the
chain for a command-feedback turn; another companion's chat counts only against the companion. Over
either cap the companion says it is worn out and makes no model call.

| File | Change |
|---|---|
| `common/src/main/java/com/player2/playerengine/player2api/TurnCaps.java` | new: rolling-hour caps per player and per companion |
| `common/src/main/java/com/player2/playerengine/player2api/TurnCapsSelfTest.java` | new: turn 61 from one player and turn 121 on one companion make no call |
| `common/src/main/java/com/player2/playerengine/player2api/AgentConversationData.java` | charges each turn before the model call; the tired line; request count |
| `common/src/main/java/com/player2/playerengine/companion/CompanionRulesSelfTest.java` | runs the caps self-test |
| `common/src/main/java/com/player2/playerengine/smoke/SmokeHarness.java` | `caps` scenario |
| `common/src/main/resources/assets/playerengine/lang/en_us.json` | smoke scenario list |

**Perception smoke.** The `xray` scenario seals a block in dirt three blocks from the companion:
`mine` leaves it alone, and mines it once the dirt above it is gone.

| File | Change |
|---|---|
| `common/src/main/java/com/player2/playerengine/smoke/SmokeHarness.java` | `xray` scenario |
| `common/src/main/resources/assets/playerengine/lang/en_us.json` | smoke scenario list |

**Baritone mines exposed ore only by default.** `allowOnlyExposedOres` now defaults to `true`, so
Baritone's `MineProcess` (a separate path from the scanner) also skips enclosed ore, and a pack
needs no `playerengine/settings.txt` for it. `settings.txt` still overrides it.

| File | Change |
|---|---|
| `common/src/main/java/com/player2/playerengine/automaton/api/Settings.java` | `allowOnlyExposedOres` defaults to `true` |
| `common/src/main/java/com/player2/playerengine/util/PerceptionSelfTest.java` | checks the default |

**A one-time clear of vanilla forced chunks.** gateway.7 and earlier held companion chunks as
vanilla forced flags with no owner, and a crash could leave them forced for good; nothing can tell
them from an operator's `/forceload`. On the first gateway.8 start of a world, every vanilla forced
chunk in every dimension is un-forced once, each logged, and the world records the clear in
`data/playerengine_forced_chunk_clear.dat`. It never runs again, so a chunk forced afterwards stays.
Operators re-issue any `/forceload` they still want after that first start.

| File | Change |
|---|---|
| `common/src/main/java/com/player2/playerengine/util/ForcedChunkClear.java` | new: the one-time clear and its world record |
| `common/src/main/java/com/player2/playerengine/util/ForcedChunkClearSelfTest.java` | new: clears all once; a later start clears nothing |
| `common/src/main/java/com/player2/playerengine/MCCommands.java` | runs the clear at server start, before the ticket sweep |
| `common/src/main/java/com/player2/playerengine/companion/CompanionRulesSelfTest.java` | runs the clear self-test |
| `common/src/main/java/com/player2/playerengine/smoke/SmokeHarness.java` | `spawn` reports the clear; `chunk-hold-restart` checks it was skipped and the first boot's forced chunks kept |

**Two-Ada smoke.** The `two-ada` scenario summons a second player's Ada beside the owner's. A third
player's bare "stop Ada" stops neither and is asked which; the second player's bare "stop Ada" stops
their own only.

| File | Change |
|---|---|
| `common/src/main/java/com/player2/playerengine/smoke/SmokeHarness.java` | `two-ada` scenario |
| `common/src/main/resources/assets/playerengine/lang/en_us.json` | smoke scenario list |
