package com.player2.playerengine.player2api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.player2.playerengine.player2api.gateway.GatewayConfig;
import com.player2.playerengine.player2api.gateway.GatewayRouter;
import com.player2.playerengine.program.ApiReference;

public class Prompts {

  public static final String reminderOnAIMsg = "Last message was from an AI. Think about whether or not to respond. You may respond but don't keep the conversation going forever if no meaningful content was said in the last few msgs, do not respond (return an empty say)";

  /**
   * Owned by peer-talk-restraint (masterplan/peer-talk-restraint-plan.md). The single per-turn reminder
   * slot for a peer CharacterMessage head event. Do NOT overwrite independently from another track —
   * extend this builder instead. Graduation thresholds (enrich at N>=1, stronger nudge at N>=3) are
   * prompt-only tuning values; retune here without re-reading the plan. The {@link #reminderOnAIMsg}
   * constant above is the N==0 baseline (no numeric cap baked into a frozen string — the count is the
   * argument).
   */
  public static String reminderOnAIMsg(int consecutivePeerReplies) {
    if (consecutivePeerReplies <= 0) {
      return reminderOnAIMsg;
    }
    return "Last message was from another AI. You have already replied to peer messages "
        + consecutivePeerReplies + " time(s) in a row with no human in between. "
        + "Sometimes the best response is no response. If no genuinely new information, question, or "
        + "task was raised, do NOT respond — return an empty say. "
        + (consecutivePeerReplies >= 3
            ? "This exchange is going in circles; strongly prefer silence unless a human spoke or "
              + "something genuinely new came up. "
            : "")
        + "Only reply if it clearly adds value.";
  }

  public static final String reminderOnOwnerMsg = "Last message was from your owner.";
  public static final String reminderOnOtherUSerMsg = "Last message was from a user that was not your owner.";
  public static final String generalConversationReminder = "Remember to output one valid JSON object with say, and program only when you act.";

  /** The system prompt's template, shared with the pack's replay checker, which fills it the same way. */
  public static final String SYSTEM_PROMPT_RESOURCE = "playerengine/program/system-prompt.txt";

  private static volatile String systemPromptTemplate;

  /**
   * The companion's system prompt: the template filled with the character, the owner and the full
   * {@code api.*} reference generated from the seam's signature table (§5.2). It is byte-stable while
   * those are unchanged, which keeps message 0 prefix-cacheable.
   */
  public static String getAINPCSystemPrompt(Character character, String ownerUsername) {
    return withOperatorInstructions(fill(template(), character.name(), character.description(), ownerUsername,
        ApiReference.published()));
  }

  /** Literal placeholder replacement: a description or reference may hold {@code $} or a backslash. */
  static String fill(String template, String name, String description, String owner, String apiReference) {
    return template
        .replace("{{characterName}}", name == null ? "" : name)
        .replace("{{characterDescription}}", description == null ? "" : description)
        .replace("{{ownerUsername}}", owner == null ? "" : owner)
        .replace("{{apiReference}}", apiReference);
  }

  static String template() {
    String t = systemPromptTemplate;
    if (t == null) {
      try (InputStream in = Prompts.class.getClassLoader().getResourceAsStream(SYSTEM_PROMPT_RESOURCE)) {
        if (in == null) {
          throw new IllegalStateException(SYSTEM_PROMPT_RESOURCE + " is not on the classpath");
        }
        t = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
      } catch (IOException e) {
        throw new IllegalStateException("cannot read " + SYSTEM_PROMPT_RESOURCE, e);
      }
      systemPromptTemplate = t;
    }
    return t;
  }

  /**
   * Appends the gateway characters file's {@code instructions} (operator persona policy, e.g. stay
   * in-world) after the template, so they read last and override its meta framing. Stable while the
   * file is unchanged, which keeps the system message byte-stable across turns.
   */
  public static String withOperatorInstructions(String prompt) {
    if (!GatewayConfig.isEnabled()) {
      return prompt;
    }
    String extra = GatewayRouter.companionInstructions();
    return extra.isEmpty() ? prompt : prompt + "Operator Instructions (these take precedence over the guidelines above):\n" + extra + "\n";
  }

  private final static String buildStructurePrompt = """
                              You are a code generator for a tiny construction DSL used by a Minecraft bot.
                              ## Objective:
                              Given a natural-language description of a structure, return only the DSL program as a single plain-text string (possibly multi-line). No explanations, no markdown, no code fences, no JSON, no Java wrappers.
                              ### DSL Summary (what you can output)
                              Declarations: let name = <int|string|boolean>;
                              Strings use double quotes; integers only; booleans true|false.
                              Arithmetic: + - * / % (integer math only).
                              Comparisons/logic: == != < <= > >= && || !
                              Control flow:
                              For loops: for (let i = 0; i < N; i = i + 1) { ... }
                              Conditionals: if (cond) { ... } else { ... }
                              Side effects:
                              setBlock(x, y, z, blockName); — place a single block.
                              Comments: // comment
                              Forbidden: user-defined functions, imports, while/foreach, floats, external calls (THIS INCLUDES Math.sin, etc. DO NOT USE Math.sin, or any other external inputs).
                              Place blocks via setBlock(baseX + dx, baseY + dy, baseZ + dz, <material>);
                              If materials are named in the description, use them (e.g., "oak_planks", "stone_bricks", "glass", "cobblestone", "spruce_log", "lantern", "torch", "water", "lava"). If unknown, fall back to "stone".
                              ## Structure Guidelines
                              - Make sure blocknames are correct minecraft blocknames.
                              - Make sure to comment your thoughts, and really think about this, this is very important that the design is not to simple.
                              - Translate the description into concrete geometry with loops/conditionals (floors, walls, roofs, pillars, arches, domes by integer radii, etc.).
                              - For buildings where it makes sense, make sure you also add beds, crafting_table, furnace, etc, be creative!! Maybe a building could have paintings in the hallway, maybe a fireplace, etc.
                              - For buildings when it makes sense, add rooms instead of having a big empty space. Make sure the rooms are different too, maybe a kitchen, bedroom, bathroom, etc. Try not to just make a rectangle/cube as well, maybe make the building an L shape, or add multiple sections, or something similar.
                              - Make sure any torches are attached to a block, and not floating in the air.
                              - A player is 2x1, so make sure structures are the appropriate size.
                              ##  Output Rules (critical)
                              Output only the final DSL program as plain text, each statement on its own line.
                              Every statement ends with ; (except }).
                              Do not wrap the program in quotes, Java, JSON, or markdown.
                              No extra commentary before or after. The first character of your output must be part of the DSL, and the last character must be ; or }.
                              Mini Example (illustrative only; do not echo this)
      // L-shaped villa with rooms, furniture, and thoughtful layout
      // Design thoughts: We'll build an L-shaped single-story villa (24x16 main hall + 12x12 wing).
      // Height = 8 (comfortable for 2-block-tall player). Interior walls create rooms: foyer/hall, kitchen, bedroom, study.
      // We'll add beds, crafting_table, furnace, bookshelves, tables, and well-placed torches on top of solid blocks (not floating).
      // Windows are spaced regularly; doors are 2 blocks tall. A stone-brick fireplace with a chimney and a campfire hearth adds flair.
      let baseX = 0;
      let baseY = 64;
      let baseZ = 0;
      let dir = "north";
      let block = "stone_bricks";
      // ====== FOUNDATION ======
      // Main rectangle: 24 x 16
      for (let x = 0; x < 24; x = x + 1) {
        for (let z = 0; z < 16; z = z + 1) {
          setBlock(baseX + x, baseY, baseZ + z, "stone");
        }
      }
      // Wing rectangle: 12 x 12, attached on the east side (from z=4..15)
      for (let x = 24; x < 36; x = x + 1) {
        for (let z = 4; z < 16; z = z + 1) {
          setBlock(baseX + x, baseY, baseZ + z, "stone");
        }
      }
      // ====== FLOORING ======
      // Main hall floor: oak_planks
      for (let x = 0; x < 24; x = x + 1) {
        for (let z = 0; z < 16; z = z + 1) {
          setBlock(baseX + x, baseY + 1, baseZ + z, "oak_planks");
        }
      }
      // Wing floor: spruce_planks for contrast
      for (let x = 24; x < 36; x = x + 1) {
        for (let z = 4; z < 16; z = z + 1) {
          setBlock(baseX + x, baseY + 1, baseZ + z, "spruce_planks");
        }
      }
      // ====== OUTER WALLS (HEIGHT 8) ======
      for (let y = 2; y <= 9; y = y + 1) {
        // Main rectangle perimeter
        for (let x = 0; x < 24; x = x + 1) {
          setBlock(baseX + x, baseY + y, baseZ + 0, "stone_bricks");
          setBlock(baseX + x, baseY + y, baseZ + 15, "stone_bricks");
        }
        for (let z = 0; z < 16; z = z + 1) {
          setBlock(baseX + 0, baseY + y, baseZ + z, "stone_bricks");
          setBlock(baseX + 23, baseY + y, baseZ + z, "stone_bricks");
        }
        // Wing perimeter
        for (let x = 24; x < 36; x = x + 1) {
          setBlock(baseX + x, baseY + y, baseZ + 4, "stone_bricks");
          setBlock(baseX + x, baseY + y, baseZ + 15, "stone_bricks");
        }
        for (let z = 4; z < 16; z = z + 1) {
          setBlock(baseX + 24, baseY + y, baseZ + z, "stone_bricks");
          setBlock(baseX + 35, baseY + y, baseZ + z, "stone_bricks");
        }
      }
      // ====== DOORWAYS ======
      // Main entrance centered on front (z=0) of main hall: width 3, height 3
      for (let dx = 10; dx <= 12; dx = dx + 1) {
        for (let dy = 2; dy <= 4; dy = dy + 1) {
          setBlock(baseX + dx, baseY + dy, baseZ + 0, "air");
        }
      }
      // Door from main hall to wing (opening on shared wall at x=23): 2x3
      for (let dz = 8; dz <= 9; dz = dz + 1) {
        for (let dy = 2; dy <= 4; dy = dy + 1) {
          setBlock(baseX + 23, baseY + dy, baseZ + dz, "air");
        }
      }
      // ====== WINDOWS ======
      // Evenly spaced windows (2x2) around exterior walls, leaving corners
      for (let y = 4; y <= 5; y = y + 1) {
        for (let x = 3; x <= 21; x = x + 6) {
          setBlock(baseX + x, baseY + y, baseZ + 0, "glass");
          setBlock(baseX + x + 1, baseY + y, baseZ + 0, "glass");
          setBlock(baseX + x, baseY + y, baseZ + 15, "glass");
          setBlock(baseX + x + 1, baseY + y, baseZ + 15, "glass");
        }
        for (let z = 3; z <= 13; z = z + 5) {
          setBlock(baseX + 0, baseY + y, baseZ + z, "glass");
          setBlock(baseX + 1, baseY + y, baseZ + z, "glass");
          setBlock(baseX + 23, baseY + y, baseZ + z, "glass");
          setBlock(baseX + 22, baseY + y, baseZ + z, "glass");
        }
        // Wing windows
        for (let x = 26; x <= 34; x = x + 8) {
          setBlock(baseX + x, baseY + y, baseZ + 4, "glass");
          setBlock(baseX + x + 1, baseY + y, baseZ + 4, "glass");
          setBlock(baseX + x, baseY + y, baseZ + 15, "glass");
          setBlock(baseX + x + 1, baseY + y, baseZ + 15, "glass");
        }
        for (let z = 6; z <= 14; z = z + 4) {
          setBlock(baseX + 24, baseY + y, baseZ + z, "glass");
          setBlock(baseX + 35, baseY + y, baseZ + z, "glass");
        }
      }
      // ====== ROOF (FLAT WITH BORDER) ======
      for (let x = 0; x < 24; x = x + 1) {
        for (let z = 0; z < 16; z = z + 1) {
          setBlock(baseX + x, baseY + 10, baseZ + z, "stone");
        }
      }
      for (let x = 24; x < 36; x = x + 1) {
        for (let z = 4; z < 16; z = z + 1) {
          setBlock(baseX + x, baseY + 10, baseZ + z, "stone");
        }
      }
      // Roof trim
      for (let x = 0; x < 24; x = x + 1) {
        setBlock(baseX + x, baseY + 10, baseZ + 0, "stone_bricks");
        setBlock(baseX + x, baseY + 10, baseZ + 15, "stone_bricks");
      }
      for (let z = 0; z < 16; z = z + 1) {
        setBlock(baseX + 0, baseY + 10, baseZ + z, "stone_bricks");
        setBlock(baseX + 23, baseY + 10, baseZ + z, "stone_bricks");
      }
      for (let x = 24; x < 36; x = x + 1) {
        setBlock(baseX + x, baseY + 10, baseZ + 4, "stone_bricks");
        setBlock(baseX + x, baseY + 10, baseZ + 15, "stone_bricks");
      }
      for (let z = 4; z < 16; z = z + 1) {
        setBlock(baseX + 24, baseY + 10, baseZ + z, "stone_bricks");
        setBlock(baseX + 35, baseY + 10, baseZ + z, "stone_bricks");
      }
      // ====== INTERIOR ROOMS ======
      // Partition main hall into foyer (front), corridor (middle), and living room (rear)
      for (let x = 2; x <= 21; x = x + 1) {
        for (let y = 2; y <= 7; y = y + 1) {
          // Wall between foyer and corridor at z=5
          setBlock(baseX + x, baseY + y, baseZ + 5, "stone_bricks");
          // Wall between corridor and living room at z=10
          setBlock(baseX + x, baseY + y, baseZ + 10, "stone_bricks");
        }
      }
      // Doorways (2x2) in those partitions
      for (let dy = 2; dy <= 3; dy = dy + 1) {
        setBlock(baseX + 12, baseY + dy, baseZ + 5, "air");
        setBlock(baseX + 12, baseY + dy, baseZ + 10, "air");
        setBlock(baseX + 13, baseY + dy, baseZ + 5, "air");
        setBlock(baseX + 13, baseY + dy, baseZ + 10, "air");
      }
      // Wing: split into kitchen (north) and bedroom (south)
      for (let x = 26; x <= 33; x = x + 1) {
        for (let y = 2; y <= 7; y = y + 1) {
          setBlock(baseX + x, baseY + y, baseZ + 10, "stone_bricks");
        }
      }
      // Wing doorways (2x2)
      for (let dy = 2; dy <= 3; dy = dy + 1) {
        setBlock(baseX + 30, baseY + dy, baseZ + 10, "air");
        setBlock(baseX + 31, baseY + dy, baseZ + 10, "air");
      }
      // ====== FIREPLACE & CHIMNEY (living room corner) ======
      // Hearth at (x=3..5, z=12..13)
      for (let x = 3; x <= 5; x = x + 1) {
        for (let z = 12; z <= 13; z = z + 1) {
          setBlock(baseX + x, baseY + 1, baseZ + z, "cobblestone");
        }
      }
      // Campfire for safe flame
      setBlock(baseX + 4, baseY + 2, baseZ + 12, "campfire");
      // Back wall cladding and chimney up
      for (let y = 2; y <= 10; y = y + 1) {
        setBlock(baseX + 4, baseY + y, baseZ + 14, "cobblestone");
        setBlock(baseX + 4, baseY + y, baseZ + 15, "cobblestone");
      }
      for (let y = 11; y <= 13; y = y + 1) {
        setBlock(baseX + 4, baseY + y, baseZ + 15, "cobblestone");
      }
      // ====== FURNITURE & UTILITIES ======
      // Corridor rug (carpet)
      for (let x = 9; x <= 14; x = x + 1) {
        for (let z = 6; z <= 9; z = z + 1) {
          setBlock(baseX + x, baseY + 2, baseZ + z, "red_carpet");
        }
      }
      // Living room: table (logs + slab top), bookshelves, torches on top of shelves
      // Table legs
      setBlock(baseX + 16, baseY + 2, baseZ + 12, "spruce_log");
      setBlock(baseX + 18, baseY + 2, baseZ + 12, "spruce_log");
      setBlock(baseX + 16, baseY + 2, baseZ + 14, "spruce_log");
      setBlock(baseX + 18, baseY + 2, baseZ + 14, "spruce_log");
      // Table top
      for (let x = 16; x <= 18; x = x + 1) {
        for (let z = 12; z <= 14; z = z + 1) {
          setBlock(baseX + x, baseY + 3, baseZ + z, "oak_slab");
        }
      }
      // Bookshelf wall
      for (let x = 19; x <= 21; x = x + 1) {
        for (let y = 2; y <= 4; y = y + 1) {
          setBlock(baseX + x, baseY + y, baseZ + 13, "bookshelf");
        }
      }
      // Torches on top of bookshelf (attached to solid block below)
      for (let x = 19; x <= 21; x = x + 1) {
        setBlock(baseX + x, baseY + 5, baseZ + 13, "torch");
      }
      // Kitchen (wing north): counters (stone), crafting_table, furnace, sink (water)
      for (let x = 26; x <= 33; x = x + 1) {
        setBlock(baseX + x, baseY + 2, baseZ + 6, "stone");
      }
      setBlock(baseX + 27, baseY + 2, baseZ + 7, "crafting_table");
      setBlock(baseX + 28, baseY + 2, baseZ + 7, "furnace");
      // Simple sink basin
      setBlock(baseX + 30, baseY + 2, baseZ + 7, "cauldron");
      setBlock(baseX + 30, baseY + 3, baseZ + 7, "water");
      // Bedroom (wing south): double bed, side tables (barrels), chest
      setBlock(baseX + 29, baseY + 2, baseZ + 12, "bed");
      setBlock(baseX + 30, baseY + 2, baseZ + 12, "bed");
      setBlock(baseX + 28, baseY + 2, baseZ + 12, "barrel");
      setBlock(baseX + 31, baseY + 2, baseZ + 12, "barrel");
      setBlock(baseX + 33, baseY + 2, baseZ + 13, "chest");
      // Study (rear main hall): desk, chair, bookshelves, torches on desk corners
      // Desk
      for (let x = 7; x <= 9; x = x + 1) {
        setBlock(baseX + x, baseY + 2, baseZ + 13, "oak_slab");
      }
      setBlock(baseX + 8, baseY + 2, baseZ + 12, "stair");
      setBlock(baseX + 7, baseY + 3, baseZ + 13, "torch");
      setBlock(baseX + 9, baseY + 3, baseZ + 13, "torch");
      // ====== INTERIOR LIGHTING (TORCHES ON TOP OF FLOOR BLOCKS) ======
      // Main hall grid, placed on floor tops (supported by floor below at y-1)
      for (let x = 3; x <= 21; x = x + 6) {
        for (let z = 3; z <= 13; z = z + 5) {
          setBlock(baseX + x, baseY + 2, baseZ + z, "torch");
        }
      }
      // Wing lighting
      for (let x = 26; x <= 34; x = x + 4) {
        setBlock(baseX + x, baseY + 2, baseZ + 6, "torch");
        setBlock(baseX + x, baseY + 2, baseZ + 13, "torch");
      }
      // ====== FRONT PATH & GARDEN TOUCH ======
      // Small path leading from entrance
      for (let z = -1; z >= -6; z = z - 1) {
        for (let x = 10; x <= 12; x = x + 1) {
          setBlock(baseX + x, baseY + 1, baseZ + z, "cobblestone");
        }
      }
      // Flower beds flanking the path
      for (let z = -1; z >= -6; z = z - 1) {
        setBlock(baseX + 9, baseY + 2, baseZ + z, "rose_bush");
        setBlock(baseX + 13, baseY + 2, baseZ + z, "peony");
      }
                  """;

  public static String getBuildStructurePrompt() {
    return buildStructurePrompt;
  }





 private static final String selectSchematicPrompt = """
        Given the following schematics, select the ID of the schematic that most clearly matches the query and has the highest quality.
        Your input is JSON, and you can use the name, description, and download count fields to determine the best match.
        Download count can be used to guess that something is of higher quality. Use this when there are a lot of similar results and avoid results with very low downloads.
        Your output MUST be one of the "id" fields, without quotes. Do not output quotes in the reply, it should ONLY contain alphanumeric and dash characters.
        FEEL FREE to pick a DIFFERENT ID from the one's we have below, the ones below are just examples.
        EXAMPLES:
        INPUT:
          {
            "query": "A large mansion made out of wood.",
            "options": [
              {
                "name": "empire state building",
                "description": "The empire state building in New York",
                "download_count": 1000,
                "id": "01913299-8e85-7a8b-8764-e496315e217b"
              },
              {
                "name": "brick apartment",
                "description": "A median sized apartment building made of bricks",
                "download_count": 400,
                "id": "01913299-c266-7e72-961c-b382af552cfb"
              },
              {
                "name": "ship",
                "description": "A large wooden ship",
                "download_count": 500,
                "id": "01913299-d777-75ab-b69c-1acdba677003"
              },
              {
                "name": "cozy house",
                "description": "A three story house with a spiral staircase, a big bed, and lots of goodies, fancier than the houses from buildSimpleHouse function but takes a lot longer to build",
                "download_count": 500,
                "id": "01913299-eb5f-7287-86c4-d0c1b3902e4b"
              },
              {
                "name": "fishing hut",
                "description": "A fishing hut, a small wooden house with a dock, bot need to build it next to water block, find water block first then build",
                "download_count": 500,
                "id": "01913786-ef38-756b-b731-5fe0c60e0526"
              }
            ]
          }
        OUTPUT:
          01913299-eb5f-7287-86c4-d0c1b3902e4b
        REASONING (NOT part of output, here so you understand why we picked this id):
          You reply with the ID of the cozy house because it most closely matches a large house made out of wood.
        INPUT:
          {
            "query": "A boat.",
            "options": [
              {
                "name": "small yacht",
                "description": "A modern small yacht",
                "id": "0191329a-a7e0-74df-9ab1-880217d10075"
              },
              {
                "name": "ship",
                "description": "A large wooden ship",
                "id": "01913299-d777-75ab-b69c-1acdba677003"
              },
            ]
          }
        OUTPUT:
          0191329a-a7e0-74df-9ab1-880217d10075
        REASONING (NOT part of output, here so you understand why we picked this id):
          The query was a boat. Both options are boats and equally fit the query, so just pick the one that is easier to make. A small yacht is probably easier to build than a large boat.
        INPUT:
          {
            "query": "A house.",
            "options": [
              {
                "name": "house",
                "description": "idk",
                "download_count": 3,
                "id": "48d4884d-0d04-4d34-9ab5-f3b13d6e8cfc"
              },
              {
                "name": "big house",
                "description": "a big house with everything in it",
                "download_count": 10,
                "id": "52e292d4-8c63-4f05-b34d-f1f079ca2c88"
              },
              {
                "name": "house for me",
                "description": "house",
                "download_count": 2,
                "id": "7f72b147-a464-4753-afcd-b71ef9e1f3db"
              },
              {
                "name": "ship",
                "description": "A large wooden ship",
                "download_count": 500,
                "id": "01913299-d777-75ab-b69c-1acdba677003"
              },
            ]
          }
        OUTPUT:
          52e292d4-8c63-4f05-b34d-f1f079ca2c88
        REASONING (NOT part of output, here so you understand why we picked this id):
          The big house has the highest number of downloads AND the description is higher quality than the other houses. It also matches the query, unlike the ship which does not.
      """;


  public static String getSelectSchematicPrompt() {
    return selectSchematicPrompt;
  } 

}
