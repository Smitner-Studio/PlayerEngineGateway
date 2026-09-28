package com.player2.playerengine.program;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * {@code job.json} beside a companion's {@code conversation.jsonl}, written as {@code PlanStore}
 * writes {@code plan.json}: to a sibling {@code .tmp}, then an atomic move over the file, so a crash
 * mid-write leaves the previous file whole. Never throws.
 */
public final class JobStore {
    private static final Logger LOGGER = LogManager.getLogger();

    private JobStore() {
    }

    /** Writes {@code board}, or deletes the file when it is null. */
    public static boolean save(Path file, JsonObject board) {
        if (file == null) {
            return false;
        }
        try {
            if (board == null) {
                Files.deleteIfExists(file);
                return true;
            }
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, board.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[Job] could not save {}: {}", file, e.getMessage());
            return false;
        }
    }

    /** The saved board, or null. A file that does not parse is moved aside, not retried. */
    public static JsonObject load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[Job] unreadable {}: {}", file, e.getMessage());
        }
        quarantine(file);
        return null;
    }

    static void quarantine(Path file) {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.warn("[Job] could not quarantine {}: {}", file, e.getMessage());
        }
    }
}
