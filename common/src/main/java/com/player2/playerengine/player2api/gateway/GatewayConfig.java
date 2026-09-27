package com.player2.playerengine.player2api.gateway;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.player2.playerengine.automaton.utils.DirUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

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
 *
 * <p>Endpoint profiles: the keys above form the default profile, named by {@code defaultEndpoint}
 * (default {@code default}). Further profiles are declared as {@code endpoint.<name>.baseUrl},
 * {@code .model}, {@code .apiKeyEnv} (name of the environment variable holding its key) and
 * {@code .apiKeyFile} (default {@code playerengine-gateway-<name>.key} beside this file). Any profile,
 * the default included, takes the tuning keys {@code .tokenParam}, {@code .maxOutputTokens},
 * {@code .jsonMode}, {@code .dropParams}, {@code .callsPerHour} and {@code .param.<field>}; a
 * {@code param} value written as a JSON object or array is sent as that structure, so a LAN model's
 * {@code param.chat_template_kwargs={"enable_thinking":false}} stays on that profile alone. A character
 * in the characters file selects a profile with {@code "endpoint": "<name>"}. An extra profile never
 * falls back to the default profile's URL or key: missing either disables it.
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

    private static final String PROFILE_PREFIX = "endpoint.";
    private static final Pattern PROFILE_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
    private static final Pattern INTEGER = Pattern.compile("-?\\d{1,9}");
    private static final Set<String> CORE_KEYS = Set.of("baseUrl", "model", "apiKeyEnv", "apiKeyFile");
    private static final Set<String> TUNING_KEYS = Set.of("tokenParam", "maxOutputTokens", "jsonMode", "dropParams",
            "callsPerHour");
    /** Body fields a {@code param.*} entry may not set, because a dedicated key or the caller owns them. */
    private static final Set<String> RESERVED_PARAMS = Set.of("model", "messages", EndpointProfile.MAX_TOKENS,
            EndpointProfile.MAX_COMPLETION_TOKENS);

    private static volatile GatewayConfig instance;

    private final UnaryOperator<String> env;
    private final boolean enabled;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String embeddingModel;
    private final String patronTier;
    private final String charactersFile;
    private final Path configDir;
    private final String defaultEndpoint;
    private final Map<String, EndpointProfile> profiles;

    GatewayConfig(Properties p, Path configDir) {
        this(p, configDir, System::getenv);
    }

    /** {@code env} stands in for {@link System#getenv(String)} so self-tests can supply variables. */
    GatewayConfig(Properties p, Path configDir, UnaryOperator<String> env) {
        this.env = env;
        this.configDir = configDir;
        this.enabled = Boolean.parseBoolean(value(p, "enabled", "PLAYERENGINE_GATEWAY_ENABLED", "false"));
        this.baseUrl = stripTrailingSlashAndV1(value(p, "baseUrl", "PLAYERENGINE_GATEWAY_URL", ""));
        this.apiKey = resolveApiKey(p, configDir);
        this.model = value(p, "model", "PLAYERENGINE_GATEWAY_MODEL", "");
        this.embeddingModel = value(p, "embeddingModel", "PLAYERENGINE_GATEWAY_EMBEDDING_MODEL", "");
        this.patronTier = value(p, "patronTier", "PLAYERENGINE_GATEWAY_PATRON_TIER", "");
        this.charactersFile = value(p, "charactersFile", "PLAYERENGINE_GATEWAY_CHARACTERS", "playerengine-gateway-characters.json");
        this.defaultEndpoint = p.getProperty("defaultEndpoint", "default").trim();
        this.profiles = Collections.unmodifiableMap(buildProfiles(p));
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
        if (cfg.enabled) {
            for (EndpointProfile profile : cfg.profiles.values()) {
                if (profile.usable()) {
                    LOGGER.info("Gateway {}", profile.describe());
                } else {
                    LOGGER.error("Gateway {}. Companions bound to it will not answer; other endpoints are unaffected.",
                            profile.describe());
                }
            }
        }
        return cfg;
    }

    /** Replaces the loaded config; lets self-tests run without a game directory. */
    static void install(GatewayConfig cfg) {
        synchronized (GatewayConfig.class) {
            instance = cfg;
        }
    }

    private String resolveApiKey(Properties p, Path configDir) {
        String direct = value(p, "apiKey", "PLAYERENGINE_GATEWAY_KEY", "");
        if (!direct.isEmpty() || configDir == null) {
            return direct;
        }
        return readKeyFile(configDir.resolve(value(p, "apiKeyFile", "PLAYERENGINE_GATEWAY_KEY_FILE", "playerengine-gateway.key")));
    }

    /** First non-blank line; a UTF-8 byte-order mark (Windows editors add one) is not part of the key. */
    private static String readKeyFile(Path keyFile) {
        if (!Files.isRegularFile(keyFile)) {
            return "";
        }
        try {
            return Files.readAllLines(keyFile, StandardCharsets.UTF_8).stream()
                    .map(line -> line.replace("﻿", "").trim())
                    .filter(line -> !line.isEmpty()).findFirst().orElse("");
        } catch (IOException e) {
            LOGGER.error("Gateway key file {} unreadable: {}", keyFile, e.getMessage());
            return "";
        }
    }

    private Map<String, EndpointProfile> buildProfiles(Properties p) {
        Set<String> names = new TreeSet<>();
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith(PROFILE_PREFIX)) {
                int dot = key.indexOf('.', PROFILE_PREFIX.length());
                names.add(dot < 0 ? key.substring(PROFILE_PREFIX.length()) : key.substring(PROFILE_PREFIX.length(), dot));
            }
        }
        Map<String, EndpointProfile> out = new LinkedHashMap<>();
        out.put(defaultEndpoint, profile(p, defaultEndpoint, true));
        for (String name : names) {
            if (name.equals(defaultEndpoint)) {
                continue;
            }
            if (!PROFILE_NAME.matcher(name).matches()) {
                LOGGER.error("Gateway endpoint name '{}' is invalid (lower-case letters, digits, '-', '_'); ignored", name);
                continue;
            }
            out.put(name, profile(p, name, false));
        }
        return out;
    }

    private EndpointProfile profile(Properties p, String name, boolean isDefault) {
        String prefix = PROFILE_PREFIX + name + ".";
        for (String key : p.stringPropertyNames()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            String field = key.substring(prefix.length());
            if (!TUNING_KEYS.contains(field) && !field.startsWith("param.") && !CORE_KEYS.contains(field)) {
                LOGGER.warn("Gateway property {} is not recognised; ignored", key);
            } else if (isDefault && CORE_KEYS.contains(field)) {
                LOGGER.warn("Gateway property {} ignored: the default endpoint takes its URL, model and key from the top-level keys", key);
            }
        }

        String url;
        String mdl;
        String key;
        String keySource;
        if (isDefault) {
            url = baseUrl;
            mdl = model;
            key = apiKey;
            keySource = "env PLAYERENGINE_GATEWAY_KEY, apiKey, apiKeyFile";
        } else {
            url = stripTrailingSlashAndV1(p.getProperty(prefix + "baseUrl", "").trim());
            mdl = p.getProperty(prefix + "model", "").trim();
            String envName = p.getProperty(prefix + "apiKeyEnv", "").trim();
            String fileName = p.getProperty(prefix + "apiKeyFile", "playerengine-gateway-" + name + ".key").trim();
            String fromEnv = envName.isEmpty() ? null : env.apply(envName);
            if (fromEnv != null && !fromEnv.isBlank()) {
                key = fromEnv.trim();
            } else {
                key = configDir == null ? "" : readKeyFile(configDir.resolve(fileName));
            }
            keySource = (envName.isEmpty() ? "" : "env " + envName + ", ") + "file " + fileName;
        }

        String problem = null;
        String tokenParam = p.getProperty(prefix + "tokenParam", EndpointProfile.MAX_TOKENS).trim();
        if (!tokenParam.equals(EndpointProfile.MAX_TOKENS) && !tokenParam.equals(EndpointProfile.MAX_COMPLETION_TOKENS)) {
            problem = "tokenParam must be max_tokens or max_completion_tokens";
            tokenParam = EndpointProfile.MAX_TOKENS;
        }
        int maxOutputTokens = intProperty(p, prefix + "maxOutputTokens");
        int callsPerHour = intProperty(p, prefix + "callsPerHour");
        boolean jsonMode = Boolean.parseBoolean(p.getProperty(prefix + "jsonMode", "true").trim());
        Set<String> drop = new LinkedHashSet<>();
        Arrays.stream(p.getProperty(prefix + "dropParams", "").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).forEach(drop::add);
        JsonObject params = new JsonObject();
        for (String k : new TreeSet<>(p.stringPropertyNames())) {
            if (k.startsWith(prefix + "param.")) {
                String field = k.substring((prefix + "param.").length());
                if (RESERVED_PARAMS.contains(field)) {
                    problem = k + " is not allowed; the caller or a dedicated key sets " + field;
                } else {
                    try {
                        params.add(field, literal(p.getProperty(k).trim()));
                    } catch (JsonParseException e) {
                        problem = k + " is not valid JSON";
                    }
                }
            }
        }

        if (!isDefault) {
            if (url.isEmpty()) {
                problem = "no baseUrl";
            } else if (mdl.isEmpty()) {
                problem = "no model";
            } else if (key.isEmpty()) {
                problem = "no API key (" + keySource + ")";
            }
        }
        return new EndpointProfile(name, url, mdl, key, keySource, tokenParam, maxOutputTokens, jsonMode,
                Collections.unmodifiableSet(drop), params, callsPerHour, problem);
    }

    private static int intProperty(Properties p, String key) {
        String raw = p.getProperty(key, "0").trim();
        try {
            return Math.max(0, Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            LOGGER.error("Gateway property {}={} is not an integer; using 0", key, raw);
            return 0;
        }
    }

    /**
     * {@code param.*} values are sent as JSON: true/false as booleans, integers as numbers, a value
     * starting with a brace or bracket as that object or array, else strings.
     *
     * @throws JsonParseException when a brace- or bracket-led value is not one complete JSON value
     */
    private static JsonElement literal(String raw) {
        if (raw.startsWith("{") || raw.startsWith("[")) {
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject() && !parsed.isJsonArray()) {
                throw new JsonParseException("not an object or array");
            }
            return parsed;
        }
        if (raw.equals("true") || raw.equals("false")) {
            return new JsonPrimitive(Boolean.parseBoolean(raw));
        }
        if (INTEGER.matcher(raw).matches()) {
            return new JsonPrimitive(Integer.parseInt(raw));
        }
        return new JsonPrimitive(raw);
    }

    private String value(Properties p, String key, String envName, String fallback) {
        String fromEnv = env.apply(envName);
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
    public String defaultEndpoint() { return defaultEndpoint; }
    public Map<String, EndpointProfile> profiles() { return profiles; }

    public EndpointProfile defaultProfile() {
        return profiles.get(defaultEndpoint);
    }

    /** The named profile, or an unusable stand-in when no such profile is declared. */
    public EndpointProfile profile(String name) {
        EndpointProfile profile = profiles.get(name);
        return profile != null ? profile : EndpointProfile.unusable(name, "no endpoint profile named '" + name + "'");
    }
}
