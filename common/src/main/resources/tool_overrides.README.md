# PlayerEngine — Tool Keyword Overlay (`tool_overrides.json`)

This file documents the `tool_overrides.json` overlay system introduced in Phase B1.5.
It is copied automatically next to the global overlay file at
`playerengine/tool_overrides.json` on first launch.

---

## What this system does

The retrieval index ships with a seed set of commands, each with hand-authored keywords.
When the NPC can't find the right command for a phrase (e.g. "cut trees" when the keyword
"cut" isn't in the dictionary), you can add the missing keyword via an overlay file.

Overlays are **additive-only**: they add keywords and usage examples to existing tools.
They cannot rename, delete, or replace any existing field.

---

## File locations

| File | Who controls it | When active |
|------|----------------|-------------|
| `playerengine/tool_overrides.json` | Server operator / world host | Always (global) |
| `world/player2npc/persistentdata/owners/<uuid>/tool_overrides.json` | Per-player (B5 learning loop) | That player only |

**Merge order:** global is loaded first, then per-owner is layered on top for that player.
Owner A's overlay never affects Owner B's retrieval results.

---

## Schema (schemaVersion: 1)

```json
{
  "schemaVersion": 1,
  "tools": {
    "<toolId>": {
      "addKeywords": ["keyword1", "keyword2"],
      "addExamples": ["example command 1", "example command 2"]
    }
  }
}
```

- `schemaVersion` must be `1`. Any other value rejects the entire file (logged, not a crash).
- `tools` keys must match the command IDs registered in `CommandExecutor.allCommands()`.
  Unknown IDs are skipped with a warning.
- Both `addKeywords` and `addExamples` are optional. Missing arrays are treated as empty.

---

## Merge rules and caps

| Rule | Limit |
|------|-------|
| Max keywords per tool (baseline + all overlays combined) | 200 |
| Max new examples added per tool per overlay file | 5 |
| Keyword characters | `[a-z0-9 \-_']` only; uppercase is lowercased automatically |
| Duplicate keywords | Silently deduplicated against baseline and earlier overlays |
| Excess additions | Dropped with a log warning; never a crash |

---

## Example: adding "cut" as a synonym for the "get" command

```json
{
  "schemaVersion": 1,
  "tools": {
    "get": {
      "addKeywords": ["cut", "fell", "chop down"],
      "addExamples": ["get log 20 (chop trees to collect logs)"]
    }
  }
}
```

After editing the file, run `/playerengine rag reload` in-game (OP permission required)
to apply the changes without restarting Minecraft.

---

## Available tool IDs (Phase B1 seed set)

| ID | What it does |
|----|-------------|
| `get` | Gather resources or craft items |
| `goto` | Travel to coordinates or a dimension |
| `deposit` | Store items in a container |
| `attack` | Attack a player or mob |
| `follow` | Follow the owner or another player |
| `scan` | Scan for the nearest block of a type |
| `eat_food` | Eat food from inventory |
| `equip` | Equip armor or weapons |
| `give` | Give items to a player |
| `fish` | Go fishing |
| `explore` | Wander and discover terrain |
| `pickup_drops` | Collect item drops from the ground |
| `place_sign` | Place a sign with text |
| `read_signs` | Read nearby sign text |

Use `/playerengine rag inspect <toolId>` to see the current merged keyword list for a
tool, with `[+]` marking overlay-added entries.
