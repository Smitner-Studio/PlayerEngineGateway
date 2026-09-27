package com.player2.playerengine.player2api.plan;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** {@code plan.json} beside a companion's {@code conversation.jsonl}. Never throws. */
public final class PlanStore {
    private static final Logger LOGGER = LogManager.getLogger();

    private PlanStore() {
    }

    public static void save(Path file, JsonObject planOrNull) {
        if (file == null) {
            return;
        }
        try {
            if (planOrNull == null) {
                Files.deleteIfExists(file);
                return;
            }
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, planOrNull.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[Plan] could not save {}: {}", file, e.getMessage());
        }
    }

    /** The saved plan as PAUSED, or null. A file that does not parse is moved aside, not retried. */
    public static CompanionPlan load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            CompanionPlan plan = CompanionPlan.fromJson(JsonParser.parseString(text).getAsJsonObject());
            if (plan != null) {
                return plan;
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[Plan] unreadable {}: {}", file, e.getMessage());
        }
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.warn("[Plan] could not quarantine {}: {}", file, e.getMessage());
        }
        return null;
    }
}
