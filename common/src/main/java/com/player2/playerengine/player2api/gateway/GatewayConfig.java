package com.player2.playerengine.player2api.gateway;

import com.player2.playerengine.automaton.utils.DirUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Operator-supplied OpenAI-compatible gateway that replaces the Player2 cloud and desktop app.
 *
 * <p>Read once from {@code config/playerengine-gateway.properties}; each key can be overridden by an
 * environment variable. The API key is resolved from {@code PLAYERENGINE_GATEWAY_KEY}, then the
 * {@code apiKey} property, then the first line of {@code apiKeyFile} (default
 * {@code playerengine-gateway.key} beside the properties file). The key file lets a modpack ship the
 * properties file while each install keeps its own unshipped key. When {@link #enabled()} is false
 * every Player2 code path behaves exactly as upstream.
 *
 * <p>The API key is only ever attached to requests addressed to {@link #baseUrl()}; it is never
 * persisted to the Player2 token store and never sent to a Player2 host.
 */
public final class GatewayConfig {
    private static final Logger LOGGER = LogManager.getLogger();

    public static final String FILE_NAME = "playerengine-gateway.properties";

    /**
     * Stand-in for a Player2 token. Upstream gates many features on "token present"; this value
     * satisfies those gates without being a credential.
     */
    public static final String TOKEN_PLACEHOLDER = "gateway";

    /** Synthetic billing identity used when no player is online to "pay" for a call. */
    public static final String SYNTHETIC_USER = "__gateway__";

    private static volatile GatewayConfig instance;

    private final boolean enabled;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String embeddingModel;
    private final String patronTier;
    private final String charactersFile;
    private final Path configDir;

    GatewayConfig(Properties p, Path configDir) {
        this.configDir = configDir;
        this.enabled = Boolean.parseBoolean(value(p, "enabled", "PLAYERENGINE_GATEWAY_ENABLED", "false"));
        this.baseUrl = stripTrailingSlashAndV1(value(p, "baseUrl", "PLAYERENGINE_GATEWAY_URL", ""));
        this.apiKey = resolveApiKey(p, configDir);
        this.model = value(p, "model", "PLAYERENGINE_GATEWAY_MODEL", "");
        this.embeddingModel = value(p, "embeddingModel", "PLAYERENGINE_GATEWAY_EMBEDDING_MODEL", "");
        this.patronTier = value(p, "patronTier", "PLAYERENGINE_GATEWAY_PATRON_TIER", "");
        this.charactersFile = value(p, "charactersFile", "PLAYERENGINE_GATEWAY_CHARACTERS", "playerengine-gateway-characters.json");
    }

    public static GatewayConfig get() {
        GatewayConfig local = instance;
        if (local == null) {
            synchronized (GatewayConfig.class) {
                local = instance;
                if (local == null) {
                    local = load();
                    instance = local;
                }
            }
        }
        return local;
    }

    public static boolean isEnabled() {
        return get().enabled();
    }

    private static GatewayConfig load() {
        Properties p = new Properties();
        Path path = DirUtil.getConfigDir().resolve(FILE_NAME);
        if (Files.exists(path)) {
            try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                LOGGER.error("Gateway config {} unreadable; gateway disabled: {}", path, e.getMessage());
                return new GatewayConfig(new Properties(), path.getParent());
            }
        }
        GatewayConfig cfg = new GatewayConfig(p, path.getParent());
        if (cfg.enabled && cfg.baseUrl.isEmpty()) {
            LOGGER.error("Gateway enabled but baseUrl is empty in {}; Player2 calls will fail", path);
        }
        LOGGER.info("Gateway config: enabled={} baseUrl={} model={} embeddingModel={} apiKeySet={}",
                cfg.enabled, cfg.baseUrl, cfg.model, cfg.embeddingModel, !cfg.apiKey.isEmpty());
        return cfg;
    }

    /** Replaces the loaded config; lets self-tests run without a game directory. */
    static void install(GatewayConfig cfg) {
        synchronized (GatewayConfig.class) {
            instance = cfg;
        }
    }

    private static String resolveApiKey(Properties p, Path configDir) {
        String direct = value(p, "apiKey", "PLAYERENGINE_GATEWAY_KEY", "");
        if (!direct.isEmpty() || configDir == null) {
            return direct;
        }
        Path keyFile = configDir.resolve(value(p, "apiKeyFile", "PLAYERENGINE_GATEWAY_KEY_FILE", "playerengine-gateway.key"));
        if (!Files.isRegularFile(keyFile)) {
            return "";
        }
        try {
            return Files.readAllLines(keyFile, StandardCharsets.UTF_8).stream()
                    .map(String::trim).filter(line -> !line.isEmpty()).findFirst().orElse("");
        } catch (IOException e) {
            LOGGER.error("Gateway key file {} unreadable: {}", keyFile, e.getMessage());
            return "";
        }
    }

    private static String value(Properties p, String key, String env, String fallback) {
        String fromEnv = System.getenv(env);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        String fromFile = p.getProperty(key);
        return fromFile == null ? fallback : fromFile.trim();
    }

    /**
     * Callers pass Player2 endpoints that already start with {@code /v1}, so a base URL written in
     * the usual OpenAI form ({@code http://host:4001/v1}) must lose its suffix.
     */
    private static String stripTrailingSlashAndV1(String url) {
        String u = url;
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        if (u.endsWith("/v1")) {
            u = u.substring(0, u.length() - 3);
        }
        return u;
    }

    public boolean enabled() { return enabled; }
    public String baseUrl() { return baseUrl; }
    public String apiKey() { return apiKey; }
    public String model() { return model; }
    public String embeddingModel() { return embeddingModel; }
    public String patronTier() { return patronTier; }
    public String charactersFile() { return charactersFile; }
    public Path configDir() { return configDir; }
}
