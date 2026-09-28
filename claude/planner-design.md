# Companion planner and area commands: design (gateway.6)

## TLDR: Plan memory in the main chat loop, plus bounded, verified area commands (excavate, fill) on the embedded Baritone builder

Status: reviewed (Fable, 2026-09-27); implementation WIP, see section 7. Section 6 records the resolutions and is binding: where it
differs from sections 3-5, section 6 wins. Implementation is on `feat/planner-area-commands`,
branched from `gateway` at gateway.5 (d669af0).

Paths are under `common/src/main/java/com/player2/playerengine/` unless they start with the repo root.

## 1. Problem

Requests like "make a large underground room for us, keep it 1:1", "expand this room" and
"continue" fail. The server log shows that no command matches, and then the model either breaks
JSON or stalls. There are two causes:

1. **No area verb exists.** The registered commands (`PlayerEngineCommands.java`) mine N blocks of
   a named type, get items, go to a place, deposit and farm. Nothing clears or fills a region.
   `build_structure` is unregistered as broken, and it places blocks with `world.setBlock`, which
   is not survival.
2. **The chain is not a plan.** It is one command per LLM turn. After each command,
   `onCommandFinish` queues "finished running. What shall we do next?". The model has to
   reconstruct the goal from 16 history lines (`ConversationHistory.MAX_HISTORY`) plus a
   500-character summary. Nothing records "step 3 of 6", so "continue" means nothing to it.

## 2. Research findings

Tags: **V** = VERIFIED (source read this session), **I** = INFERRED.

### 2.1 The NPC loop

- **V** `AgentConversationData.process()` (:385-565) runs one turn. It drains the event queue into
  history, runs RAG, and wraps the latest user turn with `worldStatus`, `agentStatus`,
  `gameDebugMessages`, `reminders`, `validCommands`, `memory` and `currentMood`
  (`AIPersistantData.getConversationHistoryWrappedWithStatus`). It then submits one completion on
  the companion's lane (`LlmLanes`, keyed by billing key and endpoint profile).
- **V** The reply is JSON: `{reason, command, message, mood?}` (`Prompts.java:71-77`).
  `handleLlmResponse` (:1078-1241) reads it, runs the deep-search and post-decision-retry
  follow-ups, strips gesture markers, and dispatches `Event.CharacterMessage(message, command)`.
- **V** `AgentSideEffects.onCommandListGenerated` (:130-267) executes the command line on the
  server thread through `CommandExecutor.execute`. It reports back through
  `onCommandFinish(CommandExecutionStopReason)`, where the reason is `Finished(cmd, note)`,
  `Error(cmd, errMsg)` or `Cancelled(cmd)`.
- **V** `onCommandFinish` (:1699-1746) handles each reason:
  - `Finished` queues the "finished running … What shall we do next?" InfoMessage, which costs one
    LLM call per step (deduplicated by `commandAwaitingFinishAck`).
  - `Error` queues "FAILED. The error was …", with `RepeatedCommandFailureGuard` stopping a
    command that fails twice with the same error.
  - `Cancelled` queues nothing.
- **V** Commands report through `Command.finish()`, `finishWithNote(note)` (success with a
  degradation clause), `finishWithInfo(payload)` (a result) and `finishWithError(msg)`
  (`commands/base/Command.java:66-133`).
- **V** RAG: when `ragLiveEnabled`, `maybeUpdateRagSystemPrompt` retrieves the top-k tools for the
  latest user message. It always includes `idle`, `stop` and `bodylang`
  (`RagPromptBuilder.ALWAYS_INCLUDE_IDS`). Short goals such as "continue" skip retrieval and reuse
  the cached block. The model is told that only the listed ids may be selected. RAG documents live
  in `retrieval/SeedToolMetadata.java` as `doc(id, name, description, whenToUse, examples, keywords)`.
- **V** Memory: history is capped at 16 messages. The overflow is summarised by one LLM call into
  at most 500 characters, and history persists as `conversation.jsonl` per character per world.
  `mood.json` and the additional-prompt file sit beside it (`AIPersistantData.java:28-63`).
  Nothing structured about the current job persists.
- **V** Parse failures: an unparseable reply queues "resend valid JSON" up to `MAX_PARSE_RETRY`
  times. The json-stalls lane (uncommitted in `../PlayerEngineGateway-json`) silences the
  player-facing line and forces JSON mode. The planner field must be optional and tolerant, so a
  plan that is malformed never costs the whole reply.

### 2.2 The existing `agentic` planner, and why it is not the base

- **V** `commands/AgenticCommand` → `AgenticPlannerService.planAsync` → `AgenticPlanExecutor`
  (upstream persistentdata-line code, unmodified by the fork). Its LLM half is off by default
  (`enableAgenticPlanner=false`, `PlayerEngineSettings.java:74`), which leaves regex fallbacks.
- **V** It is a closed world:
  - at most 4 steps;
  - 10 whitelisted step kinds (gather, storage, deposit, label, smelt, smith, mine_block, farm ×3);
  - a plan's kind sequence must exactly match one of 15 allowed sequences
    (`AgenticPlanValidator.java:166-181`). Smelt and smith are whitelisted but absent from that
    list, so they are always rejected;
  - no repair: the first failed step ends the run;
  - no persistence;
  - a single global `agentic-planner` thread that bypasses `LlmLanes`.
- **I** Extending it to open-ended requests would mean rewriting the validator, the executor and
  the prompt, while keeping a second LLM conversation with none of the chat context.

Decision: **leave `agentic` untouched as a callable command**, and put plan memory in the main
loop, where the goal, the chat and the persona already are.

### 2.3 Baritone and the tasks

- **V** `BuilderProcess` exists per entity (`Baritone.java:87`, `IBuilderProcess.java:29-46`):
  - `build(name, ISchematic, origin)`;
  - `clearArea(p1, p2)`, which is a `FillSchematic` of air;
  - `pause`, `resume`, `isPaused`.

  Schematic types are Fill, Walls, Shell, Replace, Composite and **Mask** (`automaton/api/schematic/`).
- **V** It is survival-honest:
  - it places only from inventory slots 0-35 and moves blocks to the hotbar under `allowInventory`;
  - it breaks with `switchToBestToolFor`;
  - under `survivalParity`, dig speed follows `Player.getDigSpeed`. A wrong-tool break drops
    nothing (`LivingEntityInteractionManager` ~239-341) and is followed by a 5-tick post-break delay.
- **V, and a trap:**
  - `isActive()` is `schematic != null`.
  - On success the builder calls `onLostControl()`, which clears it.
  - On failure (nothing placeable or breakable, missing materials) it logs "Unable to do it.
    Pausing…" and sets `paused=true`. **`isActive()` stays true.**

  `DestroyBlockTask` polls only `isActive()` and so can hang forever. A caller must check
  `isPaused()`, run its own watchdog, and **verify the result against the world**. The builder
  has no result object; its only failure signal is a log line.
- **V, and a trap:** positions that a schematic wants to be air are exempt from player-placed
  protection (`BuilderProcess.java:811-818`). Other player-placed blocks get a large but finite
  cost (`CalculationContext.isProtected`, `PlayerPlacedBlockStore`). This is deliberate, "so a
  builder bot never freezes". So a plain `clearArea` **would dig out a player's own blocks inside
  the box.** `blocksToAvoidBreaking` (chests, furnaces …) only slows the tool estimate, and the
  position predicates of `AdditionalBaritoneSettings.shouldAvoidBreaking` are ignored by the builder.
- **V** The builder does not collect drops (`mineScanDroppedItems` is off). Drops are picked up by
  contact as the bot walks (`PlayerCollidesWithEntityMixin`), and there is
  `PickupDroppedItemTask` / `pickup_drops`.
- **V** No altoclef task clears a region or places torches. The ones that exist:
  - `PlaceItemBlockAtPosTask(pos, block)`: a direct place with typed failures `no_support`,
    `occupied`, `missing_item` and `place_click_exhausted`;
  - `PlaceBlockTask`, which uses the builder and never fails;
  - `StoreInAnyContainerTask`, with reasons `no_container_available`, `container_full`,
    `unreachable` and `timeout`;
  - `DepositItemsTask`.
- **V** Settings are per entity (`Baritone(LivingEntity)` creates its own `Settings`), so an area
  task can tune them for its own run and restore them afterwards.
- **V** `CompanionRules.digTimeScale` is 2.5 under parity (`CompanionRules.java:51,171`), and
  `SurvivalDigSpeed` has the vanilla break-time arithmetic that the time budget can reuse.

### 2.4 Test harness

- **V** Self-tests are plain `main()` classes run by Gradle `JavaExec` tasks in `common/build.gradle`
  (`gatewaySelfTest`, `companionSelfTest`, `farmingSelfTest`). `task test` runs the first two.
- **V** There is no LLM mock above HTTP. The gateway tests use a loopback `HttpServer` that
  returns canned chat content (`GatewayProfilesSelfTest.Mock`).
- **I** A full-loop test needs a `PlayerEngineController`, which means a live world. The design
  therefore keeps the planner in a class with no Minecraft types, behind two small interfaces.
  That class is tested with a mock model (canned JSON replies parsed by the real parser) and a
  mock dispatcher.

## 3. Design

### 3.1 The plan in the reply

Add one optional field to the response format:

```json
{"reason": "...", "message": "On it. Digging out a 9 by 9 room here.",
 "command": "",
 "plan": {"goal": "9x4x9 underground room under the owner",
          "steps": ["excavate 9 4 9 anchor=owner", "light_area", "pickup_drops"]}}
```

- `plan.steps` is a list of **command lines** in the existing command grammar. There is no new
  step vocabulary, so every registered command is a step, including `agentic`.
- A step may also be `{"command": "...", "note": "..."}`. The parser keeps only `command`.
- `plan` may also be the string `"resume"` (continue the paused plan) or `"cancel"` (drop it).
- The field is **optional and tolerated when broken.** If `plan` fails to parse or validate, the
  rest of the reply still applies, and the model gets one InfoMessage explaining why its plan was
  refused. A bad plan never turns into a JSON-stall.

System-prompt addition (a stable string, so the prefix cache keeps working):

> For a job that needs more than one command, or where no single command fits, include `plan`:
> a short `goal` and at most 8 `steps`, each a command line. The first step starts immediately;
> leave `command` empty. Later steps run on their own. You will hear back only when the plan
> finishes, a step fails, or the owner speaks. To change course, send a new `plan`; to stop,
> use `stop`.

### 3.2 Validation (`PlanParser`, pure)

- 1 to 8 steps, each at most 160 characters, and a goal of at most 120 characters (truncated,
  never rejected).
- Each step's first token must resolve to a **registered** command id (`CommandExecutor`
  resolution, including aliases), and must not be `idle`, `bodylang`, `stop` or `rag_deepsearch`.
- Steps are **not** limited to this turn's RAG subset. The subset exists to keep the prompt small;
  it does not grant permission. A registered command is a valid step. Unknown ids are rejected
  with a "did you mean" drawn from the executor.
- The step's own arguments are not parsed at plan time. They are parsed when the step runs, and a
  parse error there takes the ordinary step-failure path (§3.4). This keeps one grammar owner.

### 3.3 State (`CompanionPlan`, pure, one per companion)

```
goal, steps[], cursor, status {RUNNING, PAUSED, DONE, FAILED, CANCELLED},
results[] (per step: ok | failed:<reason, ≤120 chars> | skipped),
repairsUsed, modelCallsUsed, createdAtMillis, initiator
```

Transitions are pure methods that return a `PlanAction`: `Dispatch(stepLine)`,
`AskModel(infoText)`, `Report(infoText)` or `None`. A thin `PlanCoordinator` in
`AgentConversationData` turns those actions into `AgentSideEffects.onCommandListGenerated(...)`
or `addEventToQueue(InfoMessage)`.

### 3.4 Loop integration (the only edits to the loop)

1. **The reply carries `plan`** (final decision only, never a deep-search or retry follow-up).
   Parse it and replace any existing plan. If `command` is non-empty and differs from step 1,
   ignore `command` and log it, because the plan owns dispatch. Then dispatch step 1.
2. **`onCommandFinish(Finished)` for the plan's current step.** Record `ok` (with the note if
   there is one) and advance.
   - If steps remain, **dispatch the next step directly, with no LLM call**, and suppress the
     "what next?" prompt.
   - After the last step, queue one InfoMessage: "Plan '<goal>' finished: <compact results>.
     Tell the owner in one short line if they are waiting." This is the only completion chatter.
3. **`onCommandFinish(Error)` for the plan step.** Record the failure.
   - If `repairsUsed < 2`, queue a repair InfoMessage: "Plan step 3/5 `excavate 9 4 9` failed:
     <reason>. Done so far: … Remaining: …. Reply with a revised `plan` for the remaining work,
     or `plan: \"cancel\"`." The reply's plan replaces the remainder; completed results are kept.
   - Otherwise set status FAILED and queue one InfoMessage for an in-character "need you" line.
   - `RepeatedCommandFailureGuard` still applies. When it halts, the plan fails with no repair.
4. **`onCommandFinish(Cancelled)` or supersession.** The owner spoke, and the model answered with
   a command outside the plan, or a new user task replaced the step. Set status PAUSED and keep
   the cursor on the interrupted step. A plan is never silently dropped by an unrelated command.
5. **`stop` (a model command or the operator stop path)** sets status CANCELLED and clears the plan.
   So does `plan: "cancel"`. `cancelPendingModelActionsForOperatorStop` clears it too.
6. **Resume.** A reply with `plan: "resume"`, or a user message that is exactly a resume phrase
   ("continue", "keep going", "carry on", "go on", "resume", "finish it"; whole message,
   case-insensitive, trailing punctuation allowed) while a plan is PAUSED, re-dispatches the
   cursor step.
   - The phrase path still runs the normal LLM turn so the companion can answer.
   - If that reply has neither `plan` nor `command`, the coordinator resumes by itself.
   - This deterministic fallback is what makes "continue" work even when the model forgets the field.
7. **Status block.** While a plan exists, the per-turn wrapper gains
   `"activePlan": "goal | step 3/5 running: excavate 9 4 9 anchor=owner | done: 2 | next: light_area"`.
   It is capped at 400 characters and placed in the per-turn tail, never in the system prompt,
   so the prefix cache holds.

### 3.5 Guardrails

| Guard | Value | Where enforced |
|---|---|---|
| steps per plan | 8 | parser |
| repairs per plan | 2 | `CompanionPlan` |
| model calls charged to a plan (creation, repairs, completion) | 5, then FAILED | `CompanionPlan` |
| plan wall-clock | 60 min from creation, then FAILED("expired") when the next step would dispatch | `CompanionPlan` |
| per-step time | the step command's own budget; area commands use §3.7 | commands |
| who can start, resume or cancel | the same people who can command the companion today (initiator recorded) | unchanged policy |

Step dispatch costs no LLM call, so a 6-step plan costs about 2 calls (create and complete) where
the current chain costs 7. That matters for Rivet (`callsPerHour=60`).

### 3.6 Persistence (chosen: yes, resumed only on request)

- The plan is saved as `plan.json` beside `conversation.jsonl` and `mood.json`, through the same
  path resolution, with atomic tmp-rename writes and a tolerant load that quarantines a corrupt
  file and loads nothing.
- On load, a RUNNING plan becomes **PAUSED**. It never auto-resumes after a restart, because the
  owner may be elsewhere and the world may have changed. The model sees it in `activePlan`, and
  "continue" resumes it.
- A DONE, FAILED or CANCELLED plan is deleted.
- This is safe because the area commands are **idempotent over world state** (§3.7). A resumed
  `excavate` recomputes the remaining cells from the world instead of from progress counters.

### 3.7 Area commands

There is one pure `AreaSpec` for box resolution and bounds, and one builder-backed `AreaBuildTask`
for execution. Both commands register `SeedToolMetadata` documents with keywords such as room,
dig out, hollow, underground, basement, cellar, clear, excavate, expand, widen, floor, wall, fill
and pave, so RAG finds them for natural requests.

**Box grammar, shared by both commands:**

- `excavate <dx> <dy> <dz> [anchor=here|owner|last|<x> <y> <z>] [facing=north|south|east|west]`
- `excavate <x1> <y1> <z1> <x2> <y2> <z2>` (corners, inclusive)

Relative boxes are laid out as follows:

- The floor is at the anchor's feet Y, and the box goes up `dy` from there.
- Horizontally, the box starts one block in front of the anchor in `facing` (default: the
  anchor's facing, snapped to a cardinal direction) and is centred across it.
- `anchor=last` means the last area this companion worked on.

Every finished area command records `lastArea` (corners). `agentStatus` shows it as
`lastArea: x1 y1 z1 .. x2 y2 z2`, so "expand this room east by 5" becomes a corner-form
`excavate` the model can compute. "Keep it 1:1" becomes `dx == dz`, which is the model's job.

**`excavate`**: clears the box to air in survival.

1. **Bounds** (`AreaSpec`, pure; each rejection gives a plain reason the model can use in a repair):
   - volume ≤ 2048 cells (for example 16×8×16);
   - each axis ≤ 32;
   - dy ≤ 8;
   - the box centre within 48 blocks of the companion;
   - the box floor ≥ `minBuildHeight + 6`;
   - `dx`, `dy`, `dz` ≥ 1.
2. **Pre-scan** of the box plus a one-block shell, on the server thread, before anything is
   touched:
   - **Refuse if there is lava or water in the box or its shell** ("there's lava behind the west
     wall"). A room that floods or burns is the worst outcome, and refusal is the minimal robust
     rule. Handling liquids with buckets is out of scope.
   - **Refuse if there is void or an unloaded chunk** in the box or its shell.
   - Build the target set: non-air cells in the box, **excluding**
     - player-placed blocks (`PlayerPlacedBlockStore`),
     - block entities (chests, furnaces, spawners and so on),
     - `blocksToAvoidBreaking`,
     - blocks with hardness < 0 (bedrock and similar).

     The count of excluded cells goes into the finish note ("left 3 of your blocks standing").
   - **Tool check:** if any target needs a tool for drops (`requiresCorrectToolForDrops`) and the
     companion has no tool that is correct for it, fail fast with "needs a pickaxe". The plan
     repair then adds `get stone_pickaxe`. The failure is deterministic and has one clear fix.
3. **Execute.** Call `builder.build("excavate", MaskSchematic(FillSchematic(air), targetSet), min)`.
   Only target cells are schematic-air, so the builder's protection exemption covers exactly the
   cells we chose, and player-placed cells in the box keep their normal protection. Outside the box
   the existing finite-cost policy is unchanged (see §5, question 1). Per-entity settings for the
   run are `allowBreak=true`, `allowPlace=true` (scaffold from throwaways), `buildInLayers=true`
   top-down (keeps footing and avoids undermining itself), and `allowParkour=false`. The previous
   values are restored in `onStop`.
4. **Watchdog.** Every 20 ticks, re-count the remaining target cells from the world.
   - Success: the count reaches 0 (the world is the witness, not `isActive`).
   - Failure:
     - `builder.isPaused()`, giving "stuck: can't reach part of it";
     - no decrease within `stallSeconds = 45 × digTimeScale`;
     - the total budget is exceeded. The budget is the sum of vanilla break time per remaining
       cell with the best held tool (`SurvivalDigSpeed`) × `digTimeScale` × 1.5, plus 60 s.

     Every failure reports cleared/total ("cleared 140 of 324").
5. **Drops.** After success, run `PickupDroppedItemTask` for item entities inside the box, bounded
   at 30 s. If free slots fall below 2 mid-run and the agentic run memory or `locate_storage`
   knows a chest, pause the builder, run `StoreInAnyContainerTask` for the throwaway-class blocks
   (cobblestone, dirt, deepslate, tuff, gravel …, tools kept), and resume. With no chest known,
   keep digging and leave the overflow on the floor, saying so in the finish note ("left the spare
   stone on the floor").
6. **Idempotent.** A second `excavate` of the same box recomputes targets and finishes at once when
   the box is already clear. That is why a resumed plan cannot double-dig.

**`fill <block> <box…>`**: places blocks from inventory. It covers floors (dy=1), walls (one axis=1)
and plugs.

- Same box grammar and bounds, with volume ≤ 512 for placement.
- Pre-scan: count the cells that need the block (air, or replaceable such as grass or snow),
  excluding player-placed and block-entity cells.
- If inventory has fewer blocks than needed, **fail fast with the exact shortfall** ("need 23 more
  cobblestone"). The repair adds `get cobblestone 23` or `mine stone 23`.
- Execute with `builder.build("fill", MaskSchematic(FillSchematic(block), cells), min)`, using the
  same watchdog with the placed count as the witness.

**`light_area [spacing=5]`**: optional, stage 4. It places torches on a grid on the floor of
`lastArea`. The floor must be solid and the cell dark (block light < 8) and empty. Each torch uses
`PlaceItemBlockAtPosTask`, and a shortfall fails fast like `fill`. This is included because an
underground room without light fills with mobs. It is cut if stage 3 runs long.

### 3.8 Where the code goes

| New | Role |
|---|---|
| `player2api/plan/CompanionPlan.java` | state and transitions (pure) |
| `player2api/plan/PlanParser.java` | reply field → plan, validated against a `Predicate<String>` of known command ids (pure) |
| `player2api/plan/PlanCoordinator.java` | bridges `PlanAction` to dispatch, InfoMessage and store |
| `player2api/plan/PlanStore.java` | `plan.json` persistence |
| `player2api/plan/PlanSelfTest.java` | self-tests (below) |
| `tasks/construction/area/AreaSpec.java` | box grammar and bounds (pure) |
| `tasks/construction/area/AreaBuildTask.java` | pre-scan, masked builder run, watchdog, verification |
| `commands/ExcavateCommand.java`, `commands/FillCommand.java` (`LightAreaCommand.java`) | commands |
| `tasks/construction/area/AreaSelfTest.java` | bounds and grammar self-tests |

| Edited | Change |
|---|---|
| `player2api/AgentConversationData.java` | the five hooks in §3.4 |
| `player2api/Prompts.java` | the `plan` field and its paragraph |
| `player2api/AIPersistantData.java` | `activePlan` in the per-turn wrapper, plan file path |
| `player2api/status/AgentStatus.java` | `lastArea` |
| `retrieval/SeedToolMetadata.java` | documents for the new commands |
| `PlayerEngineCommands.java` | register the commands |
| `common/build.gradle`, `Taskfile.yml` | `planSelfTest`, run by `task test` |

### 3.9 Tests: self-test harness, mock model

The mock model is a queue of canned reply JSON strings fed through the real `PlanParser`. The mock
dispatcher records dispatched lines and lets a test complete each one as Finished, Error or
Cancelled.

| Criterion | Test | Red witness (the property removed, the test fails) |
|---|---|---|
| a plan's steps run in order with no model call between them | plan of 3 → dispatch 1, finish, 2, finish, 3, finish; model calls = 1 create + 1 complete | dispatch next step through the model instead → call count 4 fails |
| a failure asks for repair, and the repaired remainder runs | step 2 errors → AskModel with the remaining steps; canned repair plan → its steps dispatched; step 1 result kept | drop the repair branch → the test sees FAILED |
| repairs are bounded | three consecutive failures → FAILED after 2 repairs, no 3rd AskModel | raise the bound → an extra AskModel is observed |
| "continue" resumes a paused plan without the field | pause via Cancelled; user "continue" and a reply with no plan or command → cursor step re-dispatched | remove the phrase fallback → nothing dispatched |
| stop and cancel clear the plan | `stop` → CANCELLED, no dispatch on a later finish | skip the clear → a later finish dispatches |
| a malformed plan does not break the reply | reply with `plan: {"steps": 7}` → message and command still applied, one refusal InfoMessage | parse the plan strictly (throw) → the reply is lost |
| an unknown step id is refused | `plan` with `dig_room 9 9` → refused with a suggestion | skip id validation → dispatched |
| volume, axis and dy bounds hold | `AreaSpec` 17×8×16, 16×9×16, dy=0 rejected; 16×8×16 accepted | loosen a bound → the rejection case passes wrongly |
| relative-box layout | anchor, facing north, 9×4×9 → exact corners | swap an axis → corner mismatch |
| persistence pauses | save RUNNING, load → PAUSED, cursor preserved | load as RUNNING → the assertion fails |

`AreaBuildTask` needs a world, so its pre-scan (target-set filter, liquid refusal, shortfall
count) is written over a `BlockLookup` interface and tested with a map-backed fake. The builder
run itself is verified in game (stage 5 smoke). This is a stated limit, not a claimed test.

## 4. Stages

1. **Planner core:** `CompanionPlan`, `PlanParser`, `PlanSelfTest` (pure). Gate: `task test`.
2. **Loop integration:** hooks, prompt, `activePlan`, `PlanStore`. Gate: `task test` and a build.
3. **`excavate` and `fill`:** `AreaSpec`, `AreaBuildTask`, commands, RAG documents, `AreaSelfTest`.
   Gate: `task test` and a build.
4. **`light_area`** (optional).
5. **Ship:** version `gateway.6`, NOTICE/README, merge into `gateway`, then in the pack run
   `task companion-build` and `task check`, and update CHANGELOG and README (companion section:
   long jobs, examples).

## 5. Open questions for the review / owner

1. **Protection outside the box.** Player-placed blocks outside the box keep today's large but
   finite cost, which is the existing anti-freeze choice, so pathing can still break one as a last
   resort. Is that acceptable, or should an area run make protection a hard exclusion (freeze risk
   in tight spots)? Proposed: keep it finite, which is the existing policy, and name it in NOTICE.
2. **Liquids.** Excavation refuses outright when liquid is in or next to the box. Is that
   acceptable for v1? Proposed: yes. The model tells the owner, who can deal with it.
3. **Who may start a plan.** Proposed: unchanged, anyone who can command the companion today.

Answers, all settled in section 6: (1) a hard exclusion in the box's shell, with the finite cost
kept farther away; (2) yes, and the scan uses fluid state, so waterlogged blocks count as water;
(3) owner-only, by authenticated UUID.

## 6. Review resolutions (binding)

Fable's adversarial read raised three 🔴 findings and a set of 🟡 findings. The foreman relayed
them, and I re-checked each 🔴 against the source before settling it.

### 🔴1 A superseded step reports Finished, not Cancelled

**Finding.** VERIFIED: `UserTaskChain.java:472-560`. When a new genuine task replaces an active one
(ACTIVE-REPLACE-FINISH), the replaced task's `onFinish` fires, and the command reports Finished.
`Cancelled` only happens on `@stop`. Under §3.4, the plan would treat that as success, advance,
and override the owner's new command.

**Resolution: dispatch generations.**

- `PlayerEngineController` holds a `commandDispatchSeq` (an AtomicLong). `CommandExecutor.execute`
  increments it for every command line, whatever the source: the model, a plan step, or a player's
  own `@` command.
- A plan step records the value right after its own dispatch.
- A finish for that step counts only while `commandDispatchSeq` still equals the recorded value.
  Otherwise the step is marked `superseded` and the plan goes PAUSED with the cursor unchanged.
- The replacing dispatch always increments the counter before the replaced terminal fires, because
  that terminal fires inside `runUserTask`, after the incoming task is installed. So the check is
  ordered correctly.
- A nested `execute` inside a command would also increment the counter. It fails safe: the plan
  pauses, and nothing advances by mistake.
- Area steps succeed only when the world shows `remaining == 0` (§3.7 step 4), so a superseded area
  step cannot report success even without the generation check.

### 🔴2 A `;` in a step runs several commands

**Finding.** VERIFIED: `CommandExecutor.java:150-164` splits the line on `;` and runs every part.

**Resolution.** `PlanParser` rejects any step containing `;`, with the reason "one command per
step". A self-test covers `;` injection.

### 🔴3 Who may plan

**Finding.** Today any non-blacklisted player within 64 blocks can command a companion. The pack
runs on a multiplayer server.

**Resolution: owner-only for long work.**

- These are OWNER-ONLY: creating a plan, resuming one, repairing one, and the commands `excavate`,
  `fill` and `light_area`, whether sent directly or as plan steps.
- Ownership is decided by the authenticated UUID only, never by names in the message text:
  `Event.UserMessage.authenticatedUserUuid` must equal the owner's UUID. Ingress sets that UUID
  from the chat or voice packet sender (`ConversationManager.java:64`, `PlayerEngine.java:181`).
- `AgentConversationData` records whether the latest batch's last user message was an
  authenticated owner message (`chainInitiatorIsOwner`). Command-feedback turns inherit the value
  from the chain.
- The plan stores its initiator UUID.
- A `plan` field, or an owner-only command, on a turn that a non-owner started is refused with an
  InfoMessage.
- Everything else in non-owner commanding is unchanged.

### 🟡 findings adopted

**Watchdog and cleanup**

- `!builder.isActive() && remaining > 0` fails the step immediately ("gave up with N left").
- On every exit (success, failure, stop, supersession) the builder is cancelled
  (`onLostControl`), and the per-entity Baritone settings changed for the run are restored.

**Restart**

- On load, the cursor step's result is `unknown`.
- `Command.isIdempotent()` defaults to false and is true for `excavate`, `fill`, `light_area` and
  `goto`. "continue" re-dispatches an idempotent cursor step directly. For any other step, resuming
  goes through the model with an InfoMessage saying that step N was interrupted by a restart and
  may or may not have finished, and asking for a `plan` covering what is left.

**Concurrency**

- All `CompanionPlan` mutation happens under one monitor, the `PlanCoordinator` instance.
- Each plan carries a generation id, and every callback carries (plan generation, dispatch seq). A
  finish from an older plan or an older dispatch is dropped and logged.

**Size is derived from time**

- The per-step time cap is 20 minutes at survival parity.
- Estimate per target block: `ticks = ceil(1 / (toolSpeed / hardness / (canHarvest ? 30 : 100))) + 5`
  (the post-break delay). Then `seconds = Σ ticks / 20 × 2`, where the final ×2 accounts for walking
  and repositioning. The tool is the best one in the companion's inventory.
- Worked numbers for stone (hardness 1.5), per block, including the ×2:

  | Pickaxe | Speed | Ticks (break + delay) | Seconds per block | Blocks in 20 min |
  |---|---|---|---|---|
  | wooden | 2 | 23 + 5 | 2.8 | ≈ 428 |
  | stone | 4 | 12 + 5 | 1.7 | ≈ 705 |
  | iron | 6 | 8 + 5 | 1.3 | ≈ 923 |

  Deepslate (hardness 3) with a stone pickaxe takes 23 + 5 ticks, so 2.8 s per block.
- A box whose estimate exceeds 1200 s is refused, and the reply tells the model the block count that
  would fit, so it can split the job into steps.
- The fixed ceilings stay: 2048 cells, 32 per axis, dy ≤ 8.

**Clocks**

- The plan clock is an **idle** timeout. A plan fails as "expired" after 60 minutes without a
  dispatch, a finish, or an owner turn. The last-activity time is persisted as wall-clock
  milliseconds.
- `ConversationManager.injectOnTick` calls `AgentConversationData.tickPlan(now)`.
- Any plan step still running 25 minutes after dispatch is cancelled (`mod.cancelUserTask()`) and
  recorded as failed "took too long". This runs through the ordinary repair path.

**Liquids, ceilings and falling blocks**

- The liquid scan uses `FluidState`, so waterlogged blocks count as water.
- The shell is 1 block on the sides and floor and **2 blocks above the ceiling**. Any liquid in the
  shell refuses the box.
- Falling blocks (`FallingBlock`: sand, gravel, concrete powder …) directly above the ceiling are
  added to the target set, up to 6 per column. A longer column refuses the box ("loose gravel
  overhead"). Those cells count toward the time estimate.

**Where a box may be**

- Refuse any box outside the world border (`level.getWorldBorder().isWithinBounds` on both corners,
  with a 2-block margin).
- Refuse any box inside spawn protection when `server.getSpawnProtectionRadius() > 0`: the square of
  that radius around the overworld's shared spawn.
- Only the overworld, the nether and the end are allowed.
- There is a `AreaVetoes` registry of `(level, box) → Optional<reason>`, empty by default. It is
  where a Sable sub-level ("not on a ship") check belongs. **Gap:** I found no Sable API among this
  fork's dependencies, and the fork does not depend on Sable. The hook ships empty and NOTICE names
  the gap.

**Protection**

- Player-placed cells in the box's shell (1 block, and 2 above the ceiling) are a **hard** exclusion
  for the run. `BuilderProcess` gets a per-run `hardNoBreak` position set, and its inner context's
  `breakCostMultiplierAt` returns the impossible cost for those cells before the finite-penalty
  branch.
- Farther away, the finite cost is unchanged. NOTICE names this.
- Player-placed and block-entity cells inside the box are left standing.
- If there are **more than 8** such cells, the box is refused unless the command carries
  `confirm=yes`. `confirm=yes` counts only if an authenticated owner message arrived after that
  box's refusal.

**Inventory**

- There are no mid-run store trips in v1.
- `excavate` fails at the start if fewer than 4 inventory slots are free.
- It fails during the run if no slot is free, reporting progress, so the repair can add `deposit`.
- Drops left on the floor despawn after 5 minutes. NOTICE says so.

**Scaffold**

- For the run, `acceptableThrowawayItems` is limited to dirt, cobblestone, cobbled deepslate and
  netherrack, and the previous value is restored afterwards.
- Success requires every non-excluded cell in the box to be air, so scaffold left inside the room
  fails verification.
- Shell cells that were air before the run and are solid afterwards are counted and reported as bot
  scaffold.

**Budget**

- Model calls charged to plans are counted per owner UUID across all their companions: 24 in any
  rolling hour.
- A single plan may be charged at most 8 calls. Creation, repairs, completion and every owner turn
  while the plan exists (including "continue") are charged.
- Hitting either cap fails the plan as "budget", with one InfoMessage.

**Parsing and facing**

- `"plan": null`, `{}`, `[]` or `""` means the field is absent.
- Facing defaults:
  - `anchor=owner` uses the owner's horizontal facing;
  - `anchor=here` uses the companion's own facing;
  - `anchor=last` keeps the last area's facing;
  - coordinate anchors centre the box on the anchor horizontally and ignore `facing`.

### Test rows added to §3.9

| Criterion | Test | Red witness |
|---|---|---|
| a superseded step (reported as Finished) pauses the plan | dispatch step 1; a foreign dispatch increments the seq; step 1's Finished arrives → PAUSED, cursor 0, no dispatch | ignore the seq check → step 2 is dispatched |
| `;` injection is refused | step `goto 1 2 3; excavate 9 9 9` → plan refused | drop the `;` rule → accepted |
| non-owner cannot plan | a plan on a non-owner turn → refused with an InfoMessage, no dispatch | drop the owner check → dispatched |
| a stale generation finish is dropped | plan A replaced by plan B; A's step finish arrives → B unchanged | drop the generation check → B advances |
| an area step that gave up with blocks remaining is a failure | `AreaOutcome.judge(active=false, remaining=5)` → FAIL | treat inactive as done → SUCCESS |
| a step running too long is cancelled | tick 25 min after dispatch → the step is cancelled and fails | remove the per-step timeout → no cancel |
| idle expiry | tick at +61 min with no activity → FAILED("expired") | remove the idle check → still RUNNING |
| budget per owner | two plans for the same owner exceed 24 calls in an hour → the next charge fails | key by companion → passes |
| the time-derived size cap | 9×4×9 stone with a stone pickaxe accepted; 16×8×16 with a wooden pickaxe refused, with a suggested size | drop the time cap → accepted |
| fluid and falling-block pre-scan | map-backed fake: waterlogged cell in the shell → refused; a 3-high gravel column → folded in; a 7-high column → refused | skip the fluid-state check → accepted |

### Red-witness runs (2026-09-28)

Each row removes or breaks one property in the source, runs `:common:planSelfTest`, and restores
the bytes. The quoted text is the assertion that failed. "Commit" is the code the mutation was
applied to: 3c1ca2d for tests that existed at the WIP commit, otherwise the commit that added the
test or fix. With every mutation restored, `task test` is green at 8ccf34e (gateway 27, companion 598,
plan 116 planner + 67 area checks), and so is `task build`.

| Criterion | Production-path test | Red witness (mutation → failing assertion) | Commit | Verdict |
|---|---|---|---|---|
| a superseded step (reported Finished) pauses the plan | `supersededStepReportedAsFinishedPausesThePlan` | seq check in `onStepStopped` → `if (false)`: "superseded step pauses the plan" | 3c1ca2d | Proven |
| a line with a gesture and a command moves the seq (**bug, fixed**) | `everyLineThatRunsACommandMovesTheSeq` via `CommandExecutor.countsAsDispatch` | restore the shipped `startsWith("bodylang")` rule: "a gesture in front of a command still replaces the running step" | 5089771 | Proven |
| `CommandExecutor.execute` bumps the seq | none | call site → `if (false)`: suite stays green | 5089771 | **Unproven**: needs a live controller; in-game smoke |
| `;` in a plan step is refused | `parserShapes` | drop the `;` rule in `PlanParser`: "';' injection refused" | 3c1ca2d | Proven |
| a non-owner turn cannot plan, resume or cancel | `nonOwnerCannotPlanOrResume` | `authorised = true`: "refusal told" | 3c1ca2d | Proven |
| ownership is the authenticated UUID, never the name | `ownershipIsTheAuthenticatedUuid` via `OwnerGate.isOwner` / `OwnerGate.turn` | accept a message with no UUID: "the owner's name without an authenticated UUID is not the owner" | 6b679ff | Proven |
| an owner-only command in any `;` part is refused (**bug, fixed**) | `ownerOnlyCommandsAreFoundInEverySemicolonPart` via `OwnerGate.ownerOnlyCommandIn` | check the first part only, as shipped: "an owner-only command after ';' is found" | 6b679ff | Proven |
| a plan sent on the owner chain's feedback turn is charged to the owner (**bug, fixed**) | `feedbackTurnInOwnerChainCanStartAPlan` | feedback-turn initiator `null`, as shipped: "a feedback turn in the owner's chain is the owner's" | 6b679ff | Proven |
| the budget is keyed per owner | `budgetIsPerOwnerAcrossCompanions` | one key for everyone in `PlanBudget`: "another owner's allowance is separate" | 3c1ca2d | Proven |
| companions share the server-wide budget (the loop's wiring) | `companionsShareTheServerBudget` via `PlanCoordinator(Host)` | a fresh `PlanBudget` per coordinator: "a third companion cannot extend the owner's hour" | 8ccf34e | Proven |
| a step running past 25 min is cancelled and repaired | `stepThatRunsTooLongIsStoppedAndRepaired` | timeout branch in `tick` → `if (false)`: "timed-out step stopped" | 3c1ca2d | Proven |
| a dig estimated over 20 min is refused with a size that fits | `timeDerivedSizeCap` | drop the `MAX_STEP_SECONDS` refusal: "16x8x16 of stone with a wooden pickaxe is refused…: null" | 3c1ca2d | Proven |
| liquid in the box or shell refuses the scan | `liquidsRefuseIncludingWaterlogged` | liquid check in `AreaScan.scan` → `if (false)`: NPE on the null refusal at the waterlogged-shell check (`AreaSelfTest.java:175`) | 3c1ca2d | Proven |
| the fluid state marks waterlogged blocks as water | `fluidStateMarksWaterloggedBlocksAsWater` via `AreaCommand.liquidName` on real block states | decide by `LiquidBlock` type: "a waterlogged slab is water" | 8ccf34e | Proven |
| an error or chat line for an offline owner is skipped, not thrown on the tick (**bug, fixed**; found on a boot-gate server) | `OfflineOwnerChatSelfTest` in `:common:companionSelfTest`, calling `AgentSideEffects.onError` / `broadcastChatToPlayer` with a null player | the shipped unguarded helper: NPE "because \"player\" is null" → "an error with no online owner is logged, not thrown"; green after, companion 598 → 601 | 4b7ff21 | Proven |

Before 8ccf34e, the shared-budget wiring and the fluid-state mapping could not go red: the budget test built
its own shared `PlanBudget`, and the map-backed scan test was handed `liquid="water"`, so it never
exercised the adapter. The self-test has no datapack tags bound, so it asserts only that lava is a
liquid, not that it is labelled "lava".

Not witnessed (production wiring that has no test double; the in-game smoke covers it):

- `AgentConversationData` calling `OwnerGate` (the gate's logic is witnessed; the call sites are not);
- the tick-deferred dispatch's own seq check in `PlanHost.dispatch`;
- the seq bump at the `CommandExecutor.execute` call site (row above).

### Decisions made while implementing

- The box grammar's coordinate anchor is written `anchor=x,y,z`, so it cannot be confused with the
  six-number corner form. `anchor=last` places the new box against the last area's edge in `facing`,
  so "make the room 5 longer to the north" is `excavate 9 4 5 anchor=last facing=north`.
- A plan step is dispatched on the next server tick, from `AgentConversationData.tickPlan`, never
  inside a task-chain finish callback. If the dispatch seq has moved between scheduling and that
  tick, the step reports CANCELLED and the plan pauses.
- There is no drop-pickup sweep inside `excavate`. The companion picks up most drops by walking
  through the room, and a plan can add `pickup_drops` as a step. The RAG examples and the prompt say so.
- `light_area` is deferred, as allowed for stage 4. It is not registered, and the prompt does not
  mention it.
- `excavate` and `fill` are removed from the `mine` / `mine_block` RAG keyword lists (collision) and
  get their own documents.

**Not tested without a world** (stated limits; covered by the in-game smoke only):

- the builder actually clearing a masked schematic;
- `hardNoBreak` stopping a break in the shell;
- the scaffold throwaway restriction;
- world-border and spawn-protection lookups on a real level;
- the ACTIVE-REPLACE ordering on a live `UserTaskChain`. The unit test drives the seq directly; the
  ordering claim rests on reading `UserTaskChain.java:472-560`.

## 7. Status and next steps (2026-09-27, end of session)

Branch `feat/planner-area-commands`, in worktree `../PlayerEngineGateway-planner`, cut from
`gateway` at d669af0 (gateway.5). It is committed as WIP and is **not** merged into `gateway`. The
pack is untouched.

**Built**

- The planner core (`player2api/plan/`): `PlanParser`, `CompanionPlan`, `PlanCoordinator`,
  `PlanBudget` and `PlanStore`.
- Loop integration (`AgentConversationData`):
  - the `plan` field;
  - owner-only rules by authenticated UUID;
  - stop and cancel clear the plan;
  - the tick-deferred dispatch;
  - `tickPlan` from `ConversationManager.injectOnTick`;
  - `plan.json` load and save;
  - `activePlan` and `lastArea` in `AgentStatus`;
  - the system-prompt paragraph.
- The dispatch seq: `PlayerEngineController.commandDispatchSeq`, incremented in
  `CommandExecutor.execute` (not for a line of gestures only), and an accepted-seq hook in
  `AgentSideEffects.onCommandListGenerated`.
- `Command.isIdempotent()`, true for `goto`, `excavate` and `fill`.
- The area commands:
  - `AreaSpec` (grammar and bounds) and `AreaScan` (pre-scan, time cap and verdict);
  - `AreaBuildTask` (masked builder run, watchdog, settings save and restore);
  - `AreaVetoes` (empty hook);
  - `AreaCommand` with `ExcavateCommand` and `FillCommand`, registered, with RAG documents.
- `BuilderProcess.setHardNoBreak` (the shell hard-exclusion).
- `planSelfTest` (Gradle) runs as part of `task test`.

**Gates at the WIP commit**

- `task test` is green: gateway 27, companion 598, plan 73 planner + 60 area checks.
- `task build`: see the commit body.

**Next**

1. **Red-witness runs: done (2026-09-28).** See "Red-witness runs" in section 6. They found three
   bugs, each fixed with a witness: an owner-only command after `;` in a reply (6b679ff); a plan on
   a feedback turn refused for want of an initiator (6b679ff); and a `bodylang …; <command>` line
   that did not move the seq (5089771). Tests were added for the fluid-state mapping and the budget
   wiring (8ccf34e). Gates at 8ccf34e: `task test` green (gateway 27, companion 598, plan 116
   planner + 67 area checks), and `task build` green.
2. **In-game smoke on a local instance, not the NAS server.**
   - Ask Ada for "a 7 by 3 by 7 room here" and a two-step plan.
   - Say "continue" after an interruption.
   - Stop mid-dig and check the settings are restored.
   - Place a block beside the box and check it is never broken.
   - Waterlogged refusal.
   - Restart with a paused plan.
   - While a plan step runs, give Ada a direct `@goto` of your own; the plan must pause, not
     advance (the seq bump at the `CommandExecutor.execute` call site has no unit witness).
   - Have a second player ask Ada to dig; she must decline (the `OwnerGate` call sites).
3. **Review points still open:**
   - the loop does not yet charge "continue" turns that the resume phrase handles when no plan
     exists (harmless);
   - `AreaBuildTask` relies on `onStart` being re-run after a chain interruption. Verify that in game.
4. **Ship:** version `gateway.6`, NOTICE (files table, protection policy, Sable gap, drop despawn)
   and README (plan field, area commands), merge into `gateway`, then in the pack
   `task companion-build` and `task check`, CHANGELOG, and a README companion section with examples.
