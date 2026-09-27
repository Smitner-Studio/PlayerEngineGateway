package com.player2.playerengine.retrieval;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Phase B2 — complete hand-authored {@link ToolDocument} records for every command
 * registered in {@code PlayerEngineCommands.init()}.
 *
 * <p>B1 authored 15 representative entries as a spike. B2 fills out all 30 registered
 * commands and patches keyword gaps identified in the B1 spike report:
 * <ul>
 *   <li>Singular/plural forms added (e.g. "logs", "trees", "mobs").
 *   <li>Individual mob-type keywords added to {@code attack}.
 *   <li>{@code explore} {@code whenToUse} tightened to reduce false-positive recall.
 * </ul>
 *
 * <p>Commands intentionally excluded (not registered in {@code PlayerEngineCommands}):
 * {@code inventory}, {@code list}, {@code pause}, {@code unpause}, {@code status},
 * and dev-only stubs ({@code CycleTestCommand}, {@code DummyTaskCommand}).
 *
 * <p>The {@code keywords} field on each entry is the primary mechanism for
 * paraphrase/synonym coverage at retrieval time.
 *
 * <p>Command IDs match the strings passed to {@code super(name, ...)} in each Command
 * subclass.
 */
public final class SeedToolMetadata {

    private SeedToolMetadata() {}

    /** Returns all seed documents as an immutable list. */
    public static Collection<ToolDocument> all() {
        return List.of(

            // ------------------------------------------------------------------
            // B1 entries — revised with B2 gap-fills
            // ------------------------------------------------------------------

            doc("get",
                "get",
                "Gather resources or craft any item. Handles mining, chopping, mob drops, and crafting automatically. Any item with a vanilla or modded crafting recipe is supported — not just the catalogued vanilla items.",
                "Use when the NPC needs a specific item or resource in its inventory. Works for raw materials (logs, stone, ores) and crafted items (tools, armor, food). Any item with a crafting recipe is supported, not just catalogued vanilla items. Provide item ID and optional quantity. For MODDED items use the full registry id namespace:path (e.g. iceandfire:podium_oak) — never a bare modded name and never the translation/lang key shown in inventory (block.iceandfire.podium_oak). Bare names work only for vanilla items. If you only know the display/lang key, look up the real registry id with `inspect ITEM <id>` or an advanced tooltip (F3+H). For wooden items use the GENERIC name (sign, planks, log), not a wood species like oak_sign — the best available wood nearby is chosen automatically. Items that require a furnace or smithing table (stone, glass, netherite gear) still work via their catalogue entries.",
                list("get log 20", "get sign 1", "get diamond_chestplate 1", "get iron_ingot 64", "get wooden_pickaxe 1", "get minecraft:iron_pickaxe 1", "get iceandfire:podium_oak 1"),
                list("gather", "collect", "acquire", "obtain", "fetch", "mine", "chop", "harvest",
                     "craft", "make", "create", "build item", "resource", "material", "wood", "stone",
                     "ore", "log", "logs", "trees", "tree", "lumber", "cut trees", "chop trees",
                     "cut wood", "item", "pickaxe", "sword", "tool", "ingredient",
                     "coal", "iron ore", "gravel", "sand", "dirt", "cobblestone", "planks", "sticks",
                     "sign", "signs", "modded item", "registry id", "any item"),
                list("inventory", "crafting")),

            doc("smelt",
                "smelt",
                "Smelt, blast, or smoke an item using a nearby furnace, blast furnace, or smoker as a deferred background job. The NPC walks to the furnace, loads the input plus enough fuel, waits while it cooks (reading the real furnace progress, not a timer), then collects the result.",
                "Use when the NPC needs to cook or smelt raw materials into a processed output — e.g. raw iron/gold/copper ore into ingots, raw food into cooked food, sand into glass, cobblestone into stone. Provide the INPUT item name and an optional count (defaults to 1). The output and cook time are resolved from the recipe registry, so any item with a furnace/blast/smoker recipe works — no need to specify the furnace type. Fuel is chosen and sized automatically (charcoal/coal/planks preferred; lava is not used). The NPC must have the input items in its inventory and a furnace, blast furnace, or smoker must be reachable nearby. Returning to the furnace to collect is a normal success, not a failure. Use 'get' first if the NPC does not yet have the raw material.",
                list("smelt iron 16", "smelt raw_iron 32", "smelt raw_gold 8", "smelt potato 8", "smelt cobblestone 64", "smelt sand 16"),
                list("smelt", "smelting", "cook", "cooking", "blast", "blasting", "smoke", "smoking",
                     "furnace", "blast furnace", "smoker", "iron", "ingot", "ingots", "ore", "raw iron",
                     "raw gold", "raw copper", "food", "cooked food", "glass", "stone", "charcoal",
                     "process", "refine", "melt", "bake", "roast"),
                list("crafting", "resources")),

            doc("smith",
                "smith",
                "Upgrade an item at a smithing table. The NPC resolves the required template, base, and addition from the recipe registry, gathers all three ingredients, walks to a smithing table, and performs the upgrade. Works for any registered SmithingTransformRecipe — vanilla netherite upgrades and modded upgrades alike. Provide the desired OUTPUT item name (e.g. netherite_pickaxe) and an optional count.",
                "Use when the owner wants to upgrade gear to netherite or perform any other smithing-table upgrade — e.g. diamond to netherite, apply a modded upgrade. Provide the OUTPUT item (what you want to end up with). The NPC figures out the template, base, and addition automatically from the recipe registry. Examples: `smith netherite_pickaxe`, `smith netherite_chestplate 1`. Also use for `smith_items` in agentic plans where the goal is a smithing-table upgrade. This command does NOT smelt (use 'smelt' for furnace work) and does NOT craft (use 'get' for crafting-table recipes).",
                list("smith netherite_pickaxe", "smith netherite_chestplate 1", "smith netherite_sword",
                     "smith netherite_helmet", "smith netherite_leggings", "smith netherite_boots",
                     "smith netherite_axe", "smith netherite_shovel", "smith netherite_hoe"),
                list("smith", "smithing", "smithing table", "upgrade", "upgrade armor", "upgrade tool",
                     "upgrade gear", "upgrade equipment", "netherite", "netherite upgrade",
                     "apply netherite", "netherite my", "make netherite", "diamond to netherite",
                     "upgrade diamond gear", "upgrade diamond armor", "upgrade diamond tools",
                     "upgrade diamond pickaxe", "upgrade diamond sword", "upgrade diamond helmet",
                     "upgrade diamond chestplate", "upgrade diamond leggings", "upgrade diamond boots",
                     "upgrade diamond axe", "upgrade diamond shovel",
                     "netherite pickaxe", "netherite sword", "netherite helmet", "netherite chestplate",
                     "netherite leggings", "netherite boots", "netherite axe", "netherite shovel",
                     "netherite ingot", "netherite upgrade smithing template", "smithing template",
                     "armor upgrade", "tool upgrade", "gear upgrade", "weapon upgrade",
                     "make my pickaxe netherite", "make my sword netherite", "make my armor netherite",
                     "turn diamond into netherite", "convert diamond to netherite",
                     "modded upgrade", "smithing transform", "transform recipe",
                     "armor trim redirect", "smithing station"),
                list("crafting", "resources", "inventory")),

            doc("goto",
                "goto",
                "Travel to a specific location by coordinates or dimension name.",
                "Use when the NPC needs to move to a specific position in the world. Accepts full XYZ coordinates, XZ only, Y only, or just a dimension name.",
                list("goto 100 64 200", "goto 0 nether", "goto 100 200 overworld", "goto 50 200"),
                list("go", "move", "travel", "walk", "navigate", "head to", "coordinates", "position",
                     "location", "teleport", "nether", "end", "overworld", "dimension", "x y z", "xz",
                     "go here", "come here", "head over", "walk over", "teleport to"),
                list("movement", "navigation")),

            doc("deposit",
                "deposit",
                "Directly store items into the nearest container in one shot, creating one if simple. Best for a quick put-away when a chest is, or can easily be, nearby.",
                "Use for a one-shot direct put-away of items from the NPC's inventory into the nearest chest, barrel, or shulker box. Pass no arguments to deposit all non-tool/armor/weapon items, or specify items and quantities. Unlike 'stash', deposit auto-finds the nearest container without a coordinate range. If it cannot finish (no reachable or creatable container, container full, unreachable, or it times out), it reports the failure and recommends retrying via 'agentic', which can gather materials, craft or place a chest, and search farther. For a precise amount into a specific chest position, use 'deposit_to_storage' instead.",
                list("deposit", "deposit diamond 5", "deposit iron_ingot 32", "deposit log 20"),
                list("store", "put", "chest", "container", "save items", "drop off",
                     "storage", "barrel", "shulker", "put away", "store items", "inventory transfer",
                     "put in chest", "put everything away", "drop off items", "offload", "unload",
                     "direct store", "quick store", "one shot store"),
                list("inventory", "storage")),

            doc("attack",
                "attack",
                "Attack a nearby player or mob by name or type.",
                "Use when the NPC should engage in combat with a specific named target. Provide the player username or mob type. Optionally specify a kill count. Use 'hero' instead to clear ALL nearby hostile mobs at once.",
                list("attack zombie", "attack Creeper", "attack Steve", "attack skeleton 5"),
                list("fight", "kill", "hit", "engage", "combat", "slay", "defeat", "damage",
                     "mob", "mobs", "hostile", "hostiles", "enemy", "enemies", "monster", "monsters",
                     "player", "aggressive", "PvP",
                     "zombie", "zombies", "creeper", "creepers", "skeleton", "skeletons",
                     "spider", "spiders", "enderman", "endermen", "blaze", "blazes",
                     "witch", "pillager", "pillagers", "drowned", "phantom", "phantoms"),
                list("combat", "attack")),

            doc("follow",
                "follow",
                "Follow the owner or a specified player continuously.",
                "Use when the NPC should shadow or accompany a player. The NPC continuously paths to stay close. Provide a username or leave blank to follow the owner.",
                list("follow", "follow Steve", "follow PlayerName"),
                list("accompany", "shadow", "escort", "trail", "stick with", "walk with", "come with",
                     "stay close", "partner", "tag along", "pursue", "owner", "player", "nearby",
                     "come with me", "walk behind me", "don't leave", "bodyguard", "protect me"),
                list("movement", "social")),

            doc("scan",
                "scan",
                "Scan the nearby area and locate the nearest instance of a specified block type.",
                "Use to give the NPC situational awareness or to find a specific block. Returns the position of the nearest matching block. Container-content questions (\"what's in the chest\", \"check the chest\") belong to 'scan_storage', not 'scan'. Finding chests/containers belongs to 'locate_storage'.",
                list("scan CHEST", "scan DIRT", "scan OAK_LOG"),
                list("look", "find", "search", "locate", "detect", "observe", "check nearby",
                     "nearest", "closest", "block", "blocks", "area", "surroundings", "environment",
                     "survey", "where is", "find block", "look for block", "search for block"),
                list("observation", "scanning")),

            doc("eat_food",
                "eat_food",
                "Eat a food item from the NPC's inventory to restore hunger.",
                "Use when the NPC is hungry (hunger bar below 20) and needs to restore saturation. The NPC picks the best available food from its inventory. Use 'food' to first collect food and 'eat_food' to consume it.",
                list("eat_food"),
                list("eat", "consume", "hungry", "hunger", "food", "health", "saturation",
                     "restore hunger", "feed", "meal", "snack", "nourish"),
                list("inventory", "utility")),

            doc("equip",
                "equip",
                "Equip an item from the NPC's inventory into its armor slots or active hand.",
                "Use to put on armor, hold a weapon, or wield a tool. Provide the item ID.",
                list("equip iron_sword", "equip diamond_helmet", "equip bow", "equip iron_chestplate"),
                list("wear", "hold", "arm", "armor", "weapon", "tool", "gear", "wield",
                     "put on", "slot", "hand", "sword", "helmet", "chestplate", "boots", "shield",
                     "leggings", "pickaxe"),
                list("inventory", "combat")),

            doc("give",
                "give",
                "Give or drop items the NPC is carrying to a player: the NPC walks to the player and drops the items on the ground for them to pick up.",
                "Use when the owner wants the NPC to drop or hand over items it is carrying so a player can pick them up. Items are dropped on the ground next to the player (not forced into their inventory).",
                list("give iron_ingot 10 Steve", "give log 5 PlayerName", "give diamond 1 Owner"),
                list("drop", "drop off", "drop on ground", "drop the items", "drop for me", "drop here",
                     "throw down", "put on ground", "hand over", "give to me", "transfer", "share",
                     "offer", "provide", "deliver", "trade", "toss", "player", "owner", "pass", "inventory"),
                list("inventory", "social")),

            doc("fish",
                "fish",
                "Go fishing at a nearby body of water.",
                "Use when the NPC should find water, cast a fishing rod, and collect fish or treasure. Requires a fishing rod in inventory.",
                list("fish"),
                list("fishing", "catch", "rod", "water", "lake", "river", "pond", "angling",
                     "lure", "cast", "seafood", "ocean"),
                list("utility")),

            doc("setup_farm",
                "setup_farm",
                "Create or resume one fixed 9x9 irrigated farm plot, prepare and till it, and record its farm waypoint. Planting crops is a separate plant_farm operation.",
                "Use when the owner asks to build, prepare, create, or resume a crop farm. Among compatible unfinished EllieGPS farms in range, coordinate-less setup_farm resumes the nearest whose complete repair envelope is loaded. If such farms are known but none has a fully loaded repair envelope, setup stops instead of creating a duplicate. Automatic safe-surface selection, prioritizing grass, is used only when no compatible unfinished farm exists in range. For ordinary conversational requests such as 'make a farm here' without a precise block, use coordinate-less setup_farm; use an exact anchor only when the owner explicitly requests exact feet or coordinates. Exact x y z coordinates select exactly that fixed 9x9 center. Direct bot-command aliases (not JSON step args): setup_farm bot/setup_farm here selects the exact block beneath the NPC; setup_farm owner/setup_farm player selects the exact block beneath the owner. These aliases never use player or NPC gaze, and every exact anchor retains the hard safety gates. If an exact center is rejected, do not retry it unchanged or claim clearing or tilling happened; choose another dry, clear 9x9 center or use coordinate-less setup_farm. Setup obtains real materials, places center water, and tills the 80 surrounding cells, but it does not plant crops; use plant_farm separately.",
                list("setup_farm", "setup_farm bot", "setup_farm owner", "setup_farm 120 64 -32"),
                list("create farm", "build farm", "prepare farmland", "irrigated plot", "till soil",
                     "farm setup", "agriculture", "water source", "hoe", "crops"),
                list("utility", "construction", "farming")),

            doc("harvest_farm",
                "harvest_farm",
                "Harvest only fully grown crops from one recorded farm in a finite pass.",
                "Use when the owner asks to harvest, reap, or gather ready crops. With no coordinates the NPC selects the nearest current-dimension farm; exact x y z coordinates select a recorded farm center. Immature and unsupported crops are left untouched, and this operation does not replant.",
                list("harvest_farm", "harvest_farm 120 64 -32"),
                list("harvest farm", "harvest crops", "gather mature crops", "reap", "ready crops",
                     "fully grown", "farm harvest", "crop pickup", "agriculture"),
                list("utility", "farming", "gathering")),

            doc("plant_farm",
                "plant_farm",
                "Plant ordered crop groups on one recorded 9x9 farm and refresh its exact capacity.",
                "Use when the owner asks to plant or sow a recorded farm. Requests name planting items and counts; the minecraft namespace is optional for vanilla items, so wheat_seeds=20 and minecraft:wheat_seeds=20 are equivalent. Roots such as carrots and potatoes work alongside seed items. The NPC completes ordered crop groups one at a time, selects an exact farm when coordinates are given or the nearest compatible farm with enough open slots otherwise, and can preserve, clear, or set an exact farm's future bot-planting restriction. If a request is rejected, do not retry the unchanged command. Melon, pumpkin, and other horizontal stem crops are excluded. Planting items are sought in inventory, EllieGPS chests, then bounded world sources; village-planted crops are used before village chests.",
                list("plant_farm minecraft:carrot=23,minecraft:wheat_seeds=10",
                     "plant_farm minecraft:carrot=23 120 64 -32",
                     "plant_farm minecraft:carrot=23 120 64 -32 single:minecraft:carrot"),
                list("plant farm", "plant crops", "sow seeds", "carrots", "potatoes", "wheat seeds",
                     "farm capacity", "open slots", "mixed crops", "single crop farm", "agriculture"),
                list("utility", "farming", "gathering")),

            doc("explore",
                "explore",
                "Explore the surrounding area without a specific destination.",
                "Use when the NPC should wander to discover new terrain, structures, or resources nearby. This is undirected wandering — use 'goto' for a specific position, 'scan' to find a specific block, or 'locate_structure' for world-generated structures.",
                list("explore"),
                list("wander", "roam", "adventure", "scout", "discover",
                     "look around", "find new area", "patrol", "investigate"),
                list("movement", "navigation", "observation")),

            doc("pickup_drops",
                "pickup_drops",
                "Pick up item drops on the ground within a bounded nearby radius.",
                "Use for a simple direct pickup of nearby floor items. Prefer agentic for broader multi-step gather goals.",
                list("pickup_drops"),
                list("collect drops", "loot", "grab items", "ground items", "item drops",
                     "pick up", "looting", "nearby drops", "floor items", "dropped items"),
                list("inventory")),

            doc("agentic",
                "agentic",
                "Plan and run multi-step storage goals: gather loose drops, find OR craft/place a chest even when none is nearby, deposit, and optionally label the chest with a sign.",
                "Use for multi-step gather-and-store goals, or when a direct 'deposit' could not finish. It can collect nearby drops first, find or craft/place one chest (even when none exists nearby), deposit the selected (or all non-tool) items, and best-effort craft (if needed) and place a labeled sign. This is the recommended escalation when a one-shot 'deposit' reports it could not complete. Prefer pickup_drops for simple floor pickup only. NOTE: agentic does NOT mine or fetch raw resources — 'gather' here means picking up loose drops already on the ground, not mining blocks or sourcing items. To deposit an item the NPC does not yet hold, use 'get <item> <count>' to obtain it FIRST, then deposit.",
                list("agentic store my cobblestone", "agentic pick up nearby drops",
                     "agentic find a chest nearby",
                     "agentic collect the drops and prepare a chest",
                     "agentic deposit my items and label the chest"),
                list("task", "plan", "multi step", "gather and store", "prepare storage", "find chest",
                     "place chest", "craft chest", "collect nearby drops", "gather drops", "organize",
                     "label chest", "sign the chest", "store farther", "make a chest"),
                list("inventory", "planning")),

            doc("place_sign",
                "place_sign",
                "Place a sign with specified text at a given location.",
                "Use to label a chest, room, or area with a sign. Provide the sign text and the target block coordinates.",
                list("place_sign \"Chest 1\" 100 64 200", "place_sign \"Storage Room\" 50 65 50"),
                list("sign", "label", "tag", "write", "mark", "note", "text on block",
                     "annotate", "name location", "identify", "signpost"),
                list("building", "placement", "utility")),

            doc("read_signs",
                "read_signs",
                "Read the text on all nearby signs and report their contents.",
                "Use to gather information from signs in the world — useful for finding labeled storage locations, reading instructions, or understanding area context.",
                list("read_signs"),
                list("read sign", "sign text", "information", "label", "note", "nearby signs",
                     "what does sign say", "sign content", "observe signs", "check signs"),
                list("observation", "scanning", "utility")),

            // ------------------------------------------------------------------
            // B2 new entries — all remaining registered commands
            // ------------------------------------------------------------------

            doc("idle",
                "idle",
                "Remain idle when no real user task is active. Idle never cancels active work.",
                "Send idle by itself, never in a semicolon command chain. Use it when no further action is needed. Idle does not shut down the task runner and does not cancel active user work; use stop to cancel.",
                list("idle"),
                list("stand still", "wait", "do nothing", "stay", "hold position", "halt",
                     "stop moving", "be still", "pause", "rest", "freeze", "stand by"),
                list("utility")),

            doc("stop",
                "stop",
                "Stop all running automation and the task runner entirely. Also stops the idle task until a new task is started.",
                "Use when the owner wants to fully halt the NPC and clear its current task. Unlike 'idle', stop shuts down the task runner so the NPC will not resume any activity until explicitly given a new command.",
                list("stop"),
                list("cancel", "abort", "halt", "end task", "stop everything", "quit",
                     "cease", "terminate", "kill task", "shut down", "do not continue",
                     "stop now", "drop what you are doing"),
                list("utility")),

            doc("hero",
                "hero",
                "Actively hunt and kill hostile mobs within a short distance (about 16 blocks) of the companion's current position.",
                "Use when the owner wants the NPC to clear hostiles in the immediate area around the companion, without specifying a single target. It will move to and fight nearby mobs. Unlike 'attack' which targets one named mob or player, 'hero' clears ALL nearby hostiles at once. For a passive 'just wait here, do nothing' stance use 'idle'; for 'stay near me and fight off hostiles that come' use 'set_follow_mode DEFENDER'.",
                list("hero"),
                list("clear mobs", "kill all mobs", "clear hostiles",
                     "kill everything nearby", "mob clear", "sweep area", "hostile clear",
                     "exterminate", "purge mobs", "kill all", "clean up mobs",
                     "mob sweep", "area clear"),
                list("combat", "attack")),

            doc("locate_structure",
                "locate_structure",
                "Locate and travel to a world-generated structure. Supports stronghold and desert temple.",
                "Use when the owner asks the NPC to find or travel to a specific vanilla world structure such as a stronghold (End portal room) or a desert temple. The NPC will navigate to the nearest matching structure.",
                list("locate_structure stronghold", "locate_structure desert_temple"),
                list("find structure", "stronghold", "end portal", "portal", "eye of ender",
                     "desert temple", "temple", "go to stronghold",
                     "navigate to stronghold", "find desert temple", "structure",
                     "biome structure", "vanilla structure"),
                list("movement", "navigation", "observation")),

            // DISABLED for release: "build_structure" schematic builder is broken (registration cut in
            // PlayerEngineCommands). Tool doc removed so the model is never told the command exists and no
            // "metadata but no registered Command" warning fires. Re-enable alongside the registration.
            // doc("build_structure",
            //     "build_structure",
            //     "Build a structure at specified coordinates using a schematic search query and description. The NPC does not need to collect materials beforehand.",
            //     "Use when the owner asks the NPC to build or construct something at a specific location. Provide XYZ coordinates for the build center-bottom, a short schematic search query, and a longer description. If no coordinates are specified, use the owner's or NPC's current position.",
            //     list(
            //         "build_structure -305 406 72 \"gray modern house\" \"a gray modern house with a rose garden\"",
            //         "build_structure 100 64 200 \"small barn\" \"a wooden barn for storing hay\""
            //     ),
            //     list("build", "construct", "create structure", "make building", "place building",
            //          "erect", "architect", "schematic", "house", "base", "shelter", "cabin",
            //          "tower", "wall", "build house", "build base", "build something",
            //          "put up", "place structure"),
            //     list("building", "placement")),

            // Bodylang doc. Two grounding points exist for gestures now: this doc AND the inline-marker
            // syntax in the message field (see Prompts.java). Gestures are TTS-timed via inline markers
            // [bl:<action>] placed inside the spoken message at the point the gesture should fire; the
            // marker is silent (never spoken/shown). The whole-message `bodylang <action>` command path
            // remains as a backward-compatible fallback. Keywords cover ONLY synonyms of the four REAL
            // actions (greeting/nod_head/shake_head/victory) so retrieval never primes a marker that
            // deterministically resolves to invalid (every invalid marker fires the noisy truthful-failure
            // path). The stale "wave" keyword was removed for that reason (wave is not one of the four).
            //
            // FUTURE per-action seam (documented, NOT implemented here): per-action emotion docs (e.g.
            // id "bodylang_nod_head") are blocked today because RagPromptBuilder.appendToolBlock skips any
            // doc whose executor.get(toolId) is null, and only one Command named "bodylang" is registered.
            // The recommended future path is an OVERRIDE-DOC render: let appendToolBlock render a
            // whitelisted set of metadata-only sub-doc ids using the parent command's usage line, instead
            // of gating on Command presence — zero command-name proliferation. Do NOT add new docs or
            // change appendToolBlock in this phase; this comment + the plan establish the seam only.
            doc("bodylang",
                "bodylang",
                "Perform a body-language / emote animation. Valid actions: greeting, nod_head, shake_head, victory. Gestures can be TTS-timed by embedding a silent inline marker [bl:<action>] inside the spoken message at the exact point the gesture should fire (e.g. \"Hi! [bl:greeting] Nice to meet you.\"); markers are never spoken or shown. The whole-message command form `bodylang <action>` also works.",
                "Use when the social context calls for a non-verbal physical reaction — greeting an arrival, nodding agreement, shaking head in disagreement, or celebrating a victory. Prefer the inline [bl:<action>] marker inside the message so the gesture syncs with speech; use the command form for a single whole-message gesture. Do NOT use for movement or task commands. Only the four listed actions are valid.",
                list("bodylang greeting", "bodylang nod_head", "bodylang shake_head", "bodylang victory"),
                list("emote", "animation", "gesture", "non-verbal", "body motion", "react",
                     "bow", "greet", "hello", "welcome",
                     "nod", "agree", "affirmative", "acknowledge", "yes",
                     "shake", "disagree", "negative", "no",
                     "celebrate", "cheer", "triumph", "victory dance"),
                list("social", "utility")),

            doc("food",
                "food",
                "Collect a target number of food units from any available source — farming, hunting, or crafting.",
                "Use when the owner wants the NPC to stock up on food of any type. Provide the desired food unit count. The NPC accounts for food already in its inventory. Use 'meat' if the owner specifically wants meat-based food, or 'eat_food' to consume food already in inventory.",
                list("food 10", "food 20"),
                list("get food", "collect food", "gather food", "stock food", "food supply",
                     "provisioning", "hunger", "feed", "prepare food", "rations",
                     "supplies", "cook", "bread", "wheat", "berries", "vegetables", "sustenance"),
                list("inventory", "utility")),

            doc("meat",
                "meat",
                "Collect a target number of food units specifically from meat sources by hunting animals.",
                "Use when the owner explicitly wants meat-based food such as beef, pork, chicken, or mutton. Unlike 'food', this command hunts animals specifically rather than accepting any food source.",
                list("meat 10", "meat 20"),
                list("hunt", "kill animals", "get meat", "collect meat", "beef", "pork",
                     "chicken", "mutton", "animal food", "hunting", "butcher",
                     "raw beef", "cooked meat", "steak", "porkchop", "protein"),
                list("inventory", "combat", "utility")),

            // DISABLED for release: "beat the game" (gamer) is broken. Seed entry cut so RAG cannot
            // retrieve it (version token changes -> index rebuilds automatically); the command
            // registration in PlayerEngineCommands is cut alongside. Re-enable both once fixed.
            // doc("gamer",
            //     "gamer",
            //     "Execute the full sequence to beat Minecraft — gather resources, find and activate the End portal, and defeat the Ender Dragon.",
            //     "Use only when the owner explicitly wants the NPC to attempt to beat the game autonomously from start to finish. This is a very long-running, multi-stage task that controls the NPC for an extended period.",
            //     list("gamer"),
            //     list("beat the game", "speedrun", "finish game", "complete minecraft",
            //          "ender dragon", "kill dragon", "end game", "beat minecraft",
            //          "win", "credits", "beat it", "complete the game", "finish",
            //          "stronghold run", "end portal run"),
            //     list("utility", "movement", "combat")),

            doc("leaveboat",
                "leaveboat",
                "Dismount from a boat or any other vehicle the NPC is currently riding.",
                "Use when the NPC is a passenger in a boat, minecart, or other vehicle and needs to get out. Safe to call even if not currently riding — it completes immediately with no effect.",
                list("leaveboat"),
                list("get off boat", "exit boat", "dismount", "get out of boat", "disembark",
                     "stop riding", "leave vehicle", "exit vehicle", "exit minecart",
                     "get off", "unboard", "off boat"),
                list("movement", "utility")),

            doc("reload_settings",
                "reload_settings",
                "Reload the bot's configuration files and the butler allow/block lists without restarting.",
                "Use when configuration files have been updated on disk and need to take effect immediately. This is a management command — only use when the owner explicitly requests a settings reload. The agent should NOT call this autonomously.",
                list("reload_settings"),
                list("reload config", "refresh settings", "update settings", "reload whitelist",
                     "reload blacklist", "apply config", "load config", "config reload",
                     "butler list reload", "settings update"),
                list("utility")),

            doc("resetmemory",
                "resetmemory",
                "Clear the NPC's conversation history and memory without stopping the current task.",
                "Use when the owner wants to wipe the NPC's chat context for a fresh conversation without halting active automation. The agent must NOT call this on itself — this is a user-initiated command only.",
                list("resetmemory"),
                list("clear memory", "forget", "wipe memory", "reset context", "fresh start",
                     "clear chat", "clear history", "reset conversation", "amnesia",
                     "start over", "clear conversation", "forget everything"),
                list("utility")),

            doc("chatclef",
                "chatclef",
                "Enable or disable the AI chat bridge (chatclef). When disabled, the NPC does not intercept or respond to player messages.",
                "Use only when the owner explicitly toggles the AI bridge on or off. The agent must NOT call this autonomously — it is a user-managed control toggle.",
                list("chatclef ON", "chatclef OFF"),
                list("ai bridge", "enable ai", "disable ai", "toggle chat", "turn off ai",
                     "turn on ai", "mute npc", "unmute npc", "silence", "chat toggle",
                     "ai on", "ai off", "chat intercept", "disable chat"),
                list("utility")),

            doc("set_attack_hostiles",
                "set_attack_hostiles",
                "Enable or disable automatic hostile mob attack behavior. When off, the NPC will not attack hostile mobs automatically.",
                "Use when the owner explicitly wants to change whether the NPC automatically engages nearby hostile mobs. Turning this OFF is appropriate in contexts where combat is unwanted. The agent should only call this when the owner explicitly asks.",
                list("set_attack_hostiles ON", "set_attack_hostiles OFF"),
                list("auto attack", "attack toggle", "stop attacking", "disable attack",
                     "enable attack", "hostile attack", "peaceful mode", "passive mode",
                     "combat off", "combat on", "don't attack", "no attacking",
                     "aggressive mode", "attack mobs automatically"),
                list("combat", "utility")),

            doc("set_follow_mode",
                "set_follow_mode",
                "Set how this companion behaves while following: NORMAL (default combat), COWARD (avoids monsters, won't fight hostile mobs, flees danger while staying close), or DEFENDER (fights off hostile mobs to protect you and itself while staying near you).",
                "Use when the owner wants to change the companion's follow-time stance toward danger. COWARD when they want it to stay safe and not pick fights with monsters; DEFENDER when they want it to actively protect them; NORMAL to return to default behavior. The agent should only call this when the owner explicitly asks.",
                list("set_follow_mode COWARD", "set_follow_mode DEFENDER", "set_follow_mode NORMAL"),
                list("be careful", "protect me", "defend me", "don't fight", "coward",
                     "run away", "stay safe", "guard me", "act normal"),
                list("combat", "utility")),

            doc("stash",
                "stash",
                "Store items in a chest or container within a specified coordinate bounding box. Deposits all non-equipped items if no item list is given.",
                "Use when the owner wants items deposited into a specific coordinate-bounded storage region rather than the nearest container. Unlike 'deposit' which automatically finds the nearest chest, 'stash' targets a precise XYZ range. Provide start XYZ and end XYZ coordinates, then optionally a list of specific items and quantities.",
                list(
                    "stash 100 64 200 110 66 210",
                    "stash 100 64 200 110 66 210 iron_ingot 32 diamond 5"
                ),
                list("store in range", "deposit range", "put in storage", "coordinate chest",
                     "specific chest", "targeted storage", "region storage", "stash items",
                     "store here", "drop off at", "storage region", "bounded chest"),
                list("inventory", "storage")),

            // ------------------------------------------------------------------
            // C4.5 entries — storage content scan + precise access
            // ------------------------------------------------------------------

            doc("scan_storage",
                "scan_storage",
                "Fresh-read the live contents of a storage container (chest, double chest, barrel, shulker box) at given block coordinates. Read-only — moves nothing. Modes: light, deep, targeted.",
                "Use light mode to learn what a chest holds cheaply; targeted when you need specific items/counts; deep only when you need exact slot positions (for slot-precise withdraw/deposit). Output of deep is large — prefer light first. Targeted items are registry ids separated by \",\", each with an optional wanted count. This reads the CONTENTS of a container at a known position; use 'scan' to find/locate a block type in the area. If you don't know the container's coordinates, run locate_storage first.",
                list("scan_storage 120 64 -33 light",
                     "scan_storage 120 64 -33 deep",
                     "scan_storage 120 64 -33 targeted iron_ingot 60, oak_planks"),
                list("check chest", "look inside", "what's in the chest", "whats in the chest",
                     "chest contents", "container contents", "inspect chest", "see inside",
                     "what is stored", "check storage", "check the barrel", "look in the barrel",
                     "shulker contents", "count items in chest", "how many in chest",
                     "slot positions", "read chest", "examine chest"),
                list("inventory", "storage", "scanning")),

            doc("withdraw_from_storage",
                "withdraw_from_storage",
                "Take specific items (with optional exact counts) from the container at given block coordinates into the NPC's inventory.",
                "Use when the NPC should take items OUT of a known chest, barrel, or shulker at specific coordinates. Entries are separated by \",\", each \"<item>\" or \"<item> <count>\"; omit the count to take all available. Explicit counts are atomic: if any cannot be fully satisfied, nothing moves and the error names the live amount. If unsure what the container holds, run a light or targeted scan_storage first. Use withdraw_storage_slot only when an exact slot index from a deep scan matters.",
                list("withdraw_from_storage 120 64 -33 iron_ingot 40, coal",
                     "withdraw_from_storage 120 64 -33 cobblestone"),
                list("take from chest", "grab", "retrieve", "withdraw", "take out",
                     "get from storage", "remove from chest", "take items", "fetch from chest",
                     "grab from barrel", "empty the chest", "collect from storage",
                     "take out of the chest", "get items from chest"),
                list("inventory", "storage")),

            doc("deposit_to_storage",
                "deposit_to_storage",
                "Put a precise selection of the NPC's items (with optional exact counts) into the container at given block coordinates.",
                "Use deposit_to_storage for a precise amount into a specific chest position; use deposit for bulk store-everything behavior. Entries are separated by \",\", each \"<item>\" or \"<item> <count>\"; omit the count to deposit all the NPC carries of that item. Explicit counts are atomic: if any cannot fit, nothing moves and the error names the live room. Unlike deposit, no keep-gear rules apply — exactly what is asked moves.",
                list("deposit_to_storage 120 64 -33 dirt 64, oak_planks",
                     "deposit_to_storage 120 64 -33 iron_ingot 32"),
                list("put into", "put in chest at", "store at", "precise deposit",
                     "exact amount", "specific chest", "put items in", "place items into chest",
                     "store in barrel", "transfer to chest", "put this in the chest",
                     "deposit exactly", "store exactly"),
                list("inventory", "storage")),

            doc("withdraw_storage_slot",
                "withdraw_storage_slot",
                "Take an exact count from ONE specific container slot (deep scan_storage indexing; a double chest is one 0-53 space), optionally into a specific bot inventory slot (0-35).",
                "Use only for slot-precision: when a deep scan_storage showed the exact slot index and the NPC must take from that slot specifically. For ordinary \"take N of item X\" requests prefer withdraw_from_storage — it needs no slot index and no deep scan. count is required; use the slot's full count to take everything in it. Omit botSlot to auto-place.",
                list("withdraw_storage_slot 120 64 -33 2 40 7",
                     "withdraw_storage_slot 120 64 -33 26 1"),
                list("take from slot", "withdraw slot", "specific slot", "slot number",
                     "grab from slot", "take slot", "exact slot", "container slot",
                     "pull from slot", "slot index"),
                list("inventory", "storage")),

            doc("deposit_storage_slot",
                "deposit_storage_slot",
                "Put an exact count from ONE bot inventory slot (0-35) into the container at given block coordinates, optionally into a specific container slot (deep scan_storage indexing; 0-53 for double chests).",
                "Use only for slot-precision: placing items into a particular container slot (e.g. keeping a sorted chest layout) or moving from a specific bot inventory slot. For ordinary \"store N of item X\" requests prefer deposit_to_storage; for bulk store-everything use deposit. Omit containerSlot to auto-place into the first stackable-or-empty slot.",
                list("deposit_storage_slot 120 64 -33 4 32 9",
                     "deposit_storage_slot 120 64 -33 0 16"),
                list("store in slot", "put in slot", "deposit slot", "specific slot",
                     "slot number", "place in slot", "insert into slot", "sorted chest",
                     "exact slot", "slot index"),
                list("inventory", "storage")),

            doc("locate_storage",
                "locate_storage",
                "Instantly report the coordinates of nearby storage containers (chest, trapped chest, barrel, shulker box): the container the prompting user is looking at, the one nearest the user, and the one nearest the NPC. Pure lookup — takes no arguments and never moves the NPC.",
                "Use when the user or the NPC refers to a chest/container WITHOUT coordinates (\"the chest near you\", \"what's in the chest\", \"put it in my chest\") — run locate_storage first, then feed the returned coordinates to scan_storage, withdraw_from_storage, or deposit_to_storage. Reported positions are canonical (a double chest reports one fixed half) and can be passed verbatim to those commands.",
                list("locate_storage"),
                list("find chest", "locate chest", "which chest", "nearest chest", "closest chest",
                     "container location", "where is the chest", "chest coordinates",
                     "chest position", "find barrel", "find shulker", "find container",
                     "the chest near you", "chest near me", "chest nearby",
                     "looking at chest", "this chest", "that chest"),
                list("inventory", "storage", "scanning")),

            // ------------------------------------------------------------------
            // C5 entries — EllieGPS inventory waypoints
            // ------------------------------------------------------------------

            doc("create_waypoint",
                "create_waypoint",
                "Register (or refresh) a durable EllieGPS storage waypoint for the container at given block coordinates. Navigates there, scans the contents, and remembers position, category keywords, and a description across restarts.",
                "Use when the owner wants the NPC to REMEMBER a chest/barrel/shulker for later (\"remember this chest\", \"this is the iron chest\"). Re-running on the same position refreshes the stored snapshot. Worldgen loot chests are refused to keep storage memory clean. If you don't know the coordinates, run locate_storage first. Use locate_waypoints later to find remembered storage.",
                list("create_waypoint 120 64 -33"),
                list("remember this chest", "remember chest", "save chest location", "mark this chest",
                     "register waypoint", "create waypoint", "storage memory", "bookmark chest",
                     "remember storage", "save this spot", "remember where", "note this chest",
                     "track this chest", "remember the barrel", "memorize chest"),
                list("storage", "waypoints")),

            doc("delete_waypoint",
                "delete_waypoint",
                "Remove the EllieGPS waypoint recorded at given block coordinates in the current dimension. Pure record removal — no navigation, and it works even when the container block is gone.",
                "Use to forget a remembered storage location: the chest was moved/destroyed, the waypoint is stale, or the owner says to forget it. Either half of a remembered double chest matches. This deletes only the memory record, never the container or its items. Stale records reported by audit_waypoint or shown [stale] by locate_waypoints are removed this way.",
                list("delete_waypoint 120 64 -33"),
                list("forget chest", "forget this chest", "delete waypoint", "remove waypoint",
                     "forget storage", "unregister chest", "remove storage memory", "forget location",
                     "stale waypoint", "clean up waypoints", "stop tracking chest", "erase waypoint"),
                list("storage", "waypoints")),

            doc("audit_waypoint",
                "audit_waypoint",
                "Re-scan the container behind an existing EllieGPS waypoint at given block coordinates and overwrite its stored snapshot, keywords, and description with the live contents.",
                "Use to bring a remembered chest's record up to date — after compare_waypoint reported drift, after items were added/removed, or when a waypoint's description looks outdated. Requires an existing waypoint at the position (use create_waypoint for new ones). If the container is missing the record is marked stale instead of deleted; use delete_waypoint to remove it.",
                list("audit_waypoint 120 64 -33"),
                list("update waypoint", "refresh waypoint", "rescan chest", "re-scan the chest",
                     "audit storage", "update chest record", "refresh storage memory",
                     "resync waypoint", "update the record", "bring waypoint up to date",
                     "refresh remembered chest"),
                list("storage", "waypoints")),

            doc("compare_waypoint",
                "compare_waypoint",
                "Scan the container behind an existing EllieGPS waypoint at given block coordinates and report drift against the stored snapshot: added items, removed items, count changes, and empty-slot delta. Read-only — never changes the record.",
                "Use to answer \"what changed in the chest\" / \"did anyone take something\" for a remembered container. Requires an existing waypoint with a stored snapshot (keyword-only records say so and suggest audit_waypoint). When drift is found, follow up with audit_waypoint to refresh the record. If the container is missing the record is marked stale.",
                list("compare_waypoint 120 64 -33"),
                list("what changed in the chest", "chest drift", "compare contents", "did anything change",
                     "check for changes", "items missing from chest", "who took items",
                     "anything missing", "changed since last scan", "compare chest", "chest differences",
                     "verify chest contents"),
                list("storage", "waypoints")),

            doc("locate_waypoints",
                "locate_waypoints",
                "Search the EllieGPS waypoint memory by keywords (item names, categories like 'tools' or 'ores', or description words) and report up to 3 matching storage locations in the current dimension with position, kind, and description.",
                "Use when the owner refers to remembered storage without coordinates (\"the iron chest\", \"where did I store the food\") — run locate_waypoints first, then feed the returned coordinates to scan_storage, withdraw_from_storage, deposit_to_storage, or the other waypoint commands. Only finds containers previously registered via create_waypoint or auto-registered after deposits; for arbitrary nearby containers use locate_storage.",
                list("locate_waypoints iron ingot",
                     "locate_waypoints food",
                     "locate_waypoints tools mining"),
                list("find storage with ingots", "which chest has", "where did I store",
                     "where is the iron chest", "find my storage", "search waypoints",
                     "storage search", "remembered chests", "find chest with", "which chest holds",
                     "where do I keep", "my waypoints", "list waypoints"),
                list("storage", "waypoints")),

            doc("mine_block",
                "mine_block",
                "Mine the nearest matching block of a named type within a radius. The NPC deterministically figures out which pickaxe tier the block needs (wood/stone/iron/diamond), acquires a sufficient tool if it lacks one (checking its inventory, then EllieGPS-marked chests, then nearby chests, then crafting one up the material chain), breaks the block(s), and collects the drops into its inventory. Breaking a block with too weak a tool yields no drops (vanilla behavior) and is reported honestly. Mined materials stay in the inventory — this step never stores or deposits them.",
                "Use as an agentic plan step when the goal is to break and collect a specific kind of block in the world (ore, stone, deepslate, obsidian, etc.) rather than gather loose drops already on the ground. Provide the block id (e.g. minecraft:iron_ore, cobblestone) and optional radius/maxBlocks. v1 targets ONLY the nearest matching block within the radius (no coordinate, area, or vein mining). The NPC resolves and acquires the required pickaxe automatically — do not pre-craft a tool. Mining does NOT auto-store the result; the materials remain in inventory for a later craft step (or an explicit store step) to use. Pair with gather_loose_items to also sweep up drops auto-pickup missed.",
                list("mine_block minecraft:iron_ore", "mine_block cobblestone 8", "mine_block minecraft:deepslate", "mine_block obsidian 1"),
                list("mine", "mining", "dig", "digging", "break", "break block", "break blocks",
                     "destroy block", "ore", "ores", "iron ore", "gold ore", "diamond ore", "coal ore",
                     "copper ore", "stone", "cobblestone", "deepslate", "obsidian", "rock", "boulder",
                     "pickaxe", "tool tier", "wooden pickaxe", "stone pickaxe", "iron pickaxe",
                     "diamond pickaxe", "mineable", "extract", "quarry", "harvest block",
                     "mine the ore", "mine some stone", "dig out", "break the rock"),
                list("resources", "mining", "tools")),

            doc("mine",
                "mine",
                "Standalone mine command — break and collect up to N of the nearest matching block of a named type. The NPC deterministically resolves and acquires the right pickaxe tier if it lacks one, breaks the block(s), and collects the drops into its inventory; it reports no-drops (too-weak tool) honestly and NEVER auto-stores the result.",
                "Use when the user asks the companion to mine/dig/break a specific block or ore by name, optionally with a count. Prefer this over `get` for items obtained by MINING rather than crafting (ores, stone, cobblestone, deepslate, obsidian, gravel). The companion picks the pickaxe tier automatically — do not pre-craft a tool. Count is a number of BLOCKS to break (a block may drop multiple items, or none with too weak a tool) and defaults to 1.",
                list("mine iron_ore 8", "mine cobblestone 32", "mine minecraft:deepslate", "mine diamond_ore 3"),
                list("mine", "mining", "dig", "digging", "dig up", "dig out", "break block",
                     "break blocks", "destroy block", "ore", "ores", "iron ore", "gold ore", "coal ore",
                     "diamond ore", "copper ore", "redstone ore", "lapis ore", "emerald ore",
                     "cobblestone", "stone", "deepslate", "obsidian", "gravel", "harvest block",
                     "extract ore", "mine ore", "mine some", "mine a block", "mine the", "pickaxe",
                     "quarry"),
                list("resources", "mining", "tools")),

            doc("excavate",
                "excavate",
                "Area dig: clears every block in a box to air in survival (real tools, real dig time), leaving blocks people placed and containers standing. Sized relative to an anchor (here, owner, last area, or x,y,z) or by two corners. Refuses next to water or lava, near the world border or spawn, and anything over about 20 minutes of digging; reports the finished area so it can be extended.",
                "Use when the owner wants a space dug out rather than a named block collected: a room, underground room, cellar, basement, bunker, storage room, tunnel section, hole, pit, clearing, or making an existing room bigger. For a big job use several excavate steps in a plan (for example one half, then the other). 'Keep it 1:1' or square means equal dx and dz. To extend a room, dig the new part next to the last area using its corners.",
                list("excavate 9 4 9", "excavate 7 3 7 anchor=owner", "excavate 5 3 9 anchor=last facing=east", "excavate 100 60 -20 108 63 -12"),
                list("room", "underground room", "dig a room", "dig out", "hollow out", "cellar", "basement",
                     "bunker", "cave out", "clear area", "clear space", "excavate", "expand the room", "make it bigger",
                     "widen", "extend", "enlarge", "tunnel", "hole", "pit", "space for us", "square room", "1:1"),
                list("building", "mining", "area")),

            doc("fill",
                "fill",
                "Area place: puts one block type from the inventory into every empty cell of a box, for floors, walls, ceilings and plugs. Fails with the exact shortfall when the inventory holds too few.",
                "Use when the owner wants a floor laid, a wall or ceiling built, a hole plugged, or an area paved with one block. A floor is dy 1; a wall has one side 1. Get the blocks first (get or mine) when the inventory is short.",
                list("fill cobblestone 9 1 9 anchor=last", "fill oak_planks 100 64 -20 108 64 -12", "fill stone_bricks 1 3 7"),
                list("floor", "lay a floor", "pave", "wall", "build a wall", "ceiling", "fill in", "plug the hole",
                     "cover", "patch", "platform", "fill"),
                list("building", "area"))

        );
    }

    /**
     * Returns the baseline {@link ToolDocument} for {@code id}, or {@code null} if the id
     * is not in the seed set.
     *
     * <p>Used by the {@code /playerengine rag inspect} command to diff overlay-added keywords
     * and examples from the hand-authored baseline without tagging documents at construction time.
     */
    public static ToolDocument byId(String id) {
        if (id == null) return null;
        for (ToolDocument doc : all()) {
            if (doc.id().equals(id)) return doc;
        }
        return null;
    }

    private static ToolDocument doc(String id, String name, String description, String whenToUse,
                                    List<String> examples, List<String> keywords,
                                    List<String> categoryTags) {
        return new ToolDocument(id, name, description, whenToUse, examples, keywords, categoryTags);
    }

    private static List<String> list(String... items) {
        return Arrays.asList(items);
    }
}
