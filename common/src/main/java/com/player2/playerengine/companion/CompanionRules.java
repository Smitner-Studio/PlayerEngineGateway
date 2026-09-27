package com.player2.playerengine.companion;

import com.player2.playerengine.automaton.utils.DirUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Operator rules for how the companion plays, read once from
 * {@code config/playerengine-companion.properties}. A missing file or key takes the default.
 *
 * <ul>
 *   <li>{@code survivalParity} (default {@code true}): the companion mines at a survival player's
 *       pace and has hunger on unless its settings file says otherwise. {@code false} restores the
 *       upstream behaviour.</li>
 *   <li>{@code progressChat} (default {@code milestones}): which task-progress lines reach the
 *       owner's chat. {@code all} also sends step chatter ("breaking iron ore"), {@code milestones}
 *       only outcomes and failures, {@code off} none. Conversational replies are never affected.</li>
 * </ul>
 */
public final class CompanionRules {
    private static final Logger LOGGER = LogManager.getLogger();

    public static final String FILE_NAME = "playerengine-companion.properties";

    public enum ProgressChat {
        ALL, MILESTONES, OFF;

        /** Whether a progress line of the given kind reaches chat. */
        public boolean shows(boolean milestone) {
            return this == ALL || (this == MILESTONES && milestone);
        }
    }

    private static volatile CompanionRules instance;

    private final boolean survivalParity;
    private final ProgressChat progressChat;

    CompanionRules(Properties p) {
        this.survivalParity = parseBoolean(p.getProperty("survivalParity"), true);
        this.progressChat = parseProgressChat(p.getProperty("progressChat"));
    }

    public static CompanionRules get() {
        CompanionRules local = instance;
        if (local == null) {
            synchronized (CompanionRules.class) {
                local = instance;
                if (local == null) {
                    local = load();
                    instance = local;
                }
            }
        }
        return local;
    }

    public static boolean survivalParityEnabled() {
        return get().survivalParity();
    }

    private static CompanionRules load() {
        Properties p = new Properties();
        Path path;
        try {
            path = DirUtil.getConfigDir().resolve(FILE_NAME);
        } catch (RuntimeException | LinkageError e) {
            // Headless self-tests have no platform config folder.
            LOGGER.warn("Companion rules: no config folder ({}); using defaults", e.toString());
            return new CompanionRules(p);
        }
        if (Files.exists(path)) {
            try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                LOGGER.error("Companion rules {} unreadable; using defaults: {}", path, e.getMessage());
                p = new Properties();
            }
        }
        CompanionRules rules = new CompanionRules(p);
        LOGGER.info("Companion rules: survivalParity={} progressChat={}",
                rules.survivalParity, rules.progressChat.name().toLowerCase(Locale.ROOT));
        return rules;
    }

    /** Replaces the loaded rules; lets self-tests run without a game directory. */
    static void install(CompanionRules rules) {
        synchronized (CompanionRules.class) {
            instance = rules;
        }
    }

    private static boolean parseBoolean(String raw, boolean fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String v = raw.trim();
        if (v.equalsIgnoreCase("true")) {
            return true;
        }
        if (v.equalsIgnoreCase("false")) {
            return false;
        }
        LOGGER.warn("Companion rules: '{}' is not true/false; using {}", v, fallback);
        return fallback;
    }

    private static ProgressChat parseProgressChat(String raw) {
        if (raw == null || raw.isBlank()) {
            return ProgressChat.MILESTONES;
        }
        try {
            return ProgressChat.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Companion rules: progressChat '{}' is not all/milestones/off; using milestones", raw.trim());
            return ProgressChat.MILESTONES;
        }
    }

    public boolean survivalParity() { return survivalParity; }
    public ProgressChat progressChat() { return progressChat; }
}
