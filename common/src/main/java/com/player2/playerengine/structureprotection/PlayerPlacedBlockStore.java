package com.player2.playerengine.structureprotection;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.player2.playerengine.PlayerEngine;
import com.player2.playerengine.automaton.api.BaritoneAPI;
import com.player2.playerengine.player2api.Player2NpcPersistencePaths;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Per-world, per-dimension store of positions where a <em>real</em> player placed a block.
 *
 * <p>This is the single source of truth consulted by
 * {@code CalculationContext.isProtected(...)} on the pathfinder's off-thread hot path: a block
 * position is "protected" iff it is present here. Only confirmed real-player placements are ever
 * recorded (the place-event hook owns that gate); bot/dispenser/piston placements are never added.
 *
 * <h3>Threading contract (S4 — copy-on-write per chunk bucket)</h3>
 * <ul>
 *   <li>{@link #add}, {@link #remove}, {@link #flushDirty()} eviction, and {@link #clear()} run on
 *       the <strong>server thread</strong>.</li>
 *   <li>{@link #contains} is the off-thread, lock-free, allocation-free read.</li>
 * </ul>
 * Each chunk's {@link LongOpenHashSet} is treated as <strong>immutable once published</strong>:
 * mutators build a fresh set from the old one and atomically swap it into the per-dimension
 * {@link ConcurrentHashMap}. A concurrent {@code contains} therefore always sees a fully-built set
 * and can never observe a half-mutated one or throw. There is <strong>no lock on the read path</strong>.
 *
 * <h3>Persistence</h3>
 * One JSON file per dimension under
 * {@code <worldRoot>/player2npc/persistentdata/playerplaced/<dimension-sanitized>/blocks.json},
 * written via atomic tmp-rename with corrupt-file quarantine, modelled on {@code EllieGPSStore}.
 * Writes are debounced (dirty flag flushed on a timer + a final flush on {@link #clear()}) so a
 * busy server placing many blocks never fsyncs per block.
 *
 * <h3>Cap + eviction (S5)</h3>
 * Bounded by {@code respectStructuresStoreMaxBlocks}; when an add would exceed the cap the
 * least-recently-added whole chunk bucket is evicted via an explicit per-dimension insertion-order
 * deque (a bare {@link ConcurrentHashMap} has no insertion order). Best-effort, recent builds favored.
 *
 * <h3>Egress</h3>
 * Internal only. Store contents, positions, and counts must never reach a Player2 prompt/RAG/
 * model-facing surface (DESIGN.md §3).
 *
 * <h3>Singleton</h3>
 * {@link #INSTANCE} is set by {@link #loadForServer(MinecraftServer)} at {@code SERVER_STARTING} and
 * cleared by {@link #clear()} at {@code SERVER_STOPPING}.
 */
public final class PlayerPlacedBlockStore {

    // -------------------------------------------------------------------------
    // Singleton holder
    // -------------------------------------------------------------------------

    /** The store for the currently loaded world. Null when no world is loaded. */
    private static volatile PlayerPlacedBlockStore INSTANCE;

    /** Returns the store for the currently loaded world, or {@code null} when no world is loaded. */
    public static PlayerPlacedBlockStore get() {
        return INSTANCE;
    }

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private static final int SCHEMA_VERSION = 1;
    private static final String BLOCKS_FILE_NAME = "blocks.json";
    private static final Gson GSON = new GsonBuilder().create();

    /** Cap clamp bounds — mirror {@code respectStructuresStoreMaxBlocks} default/clamp in Settings (parity). */
    private static final int DEFAULT_MAX_BLOCKS = 200000;
    private static final int MIN_MAX_BLOCKS = 1000;
    private static final int MAX_MAX_BLOCKS = 2000000;

    /** Debounce interval for the background flush of dirty dimensions. */
    private static final long FLUSH_INTERVAL_SECONDS = 5L;

    /** Throttle window for the eviction warning so a sustained build does not spam the log. */
    private static final long EVICTION_WARN_INTERVAL_MS = 30000L;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    /** The world-root path this store was loaded for (for path builders). */
    private final Path worldRoot;

    /** dimensionId (e.g. {@code minecraft:overworld}) -> per-dimension chunk buckets. */
    private final ConcurrentHashMap<String, DimBuckets> byDimension = new ConcurrentHashMap<>();

    /**
     * Global insertion-ordered chunk keys for oldest-first eviction <em>across all dimensions</em> (S5).
     * Server-thread only. Holding this at the store level (not per-dimension) means a multi-dimension server
     * evicts the genuinely-oldest chunk, never just the oldest chunk of whichever dimension was last written.
     */
    private final ArrayDeque<ChunkRef> insertionOrder = new ArrayDeque<>();

    /** Total stored positions across all dimensions; O(1) {@link #size()} + cap checks. Server-thread mutation. */
    private final AtomicInteger totalBlocks = new AtomicInteger(0);

    /** Background debounced flusher. Single daemon thread; null until {@link #loadForServer} runs. */
    private ScheduledExecutorService flusher;

    /** Last time an eviction warning was logged (server-thread only). */
    private long lastEvictionWarnMs = 0L;

    private PlayerPlacedBlockStore(Path worldRoot) {
        this.worldRoot = worldRoot;
    }

    /**
     * Per-dimension chunk buckets. The {@code chunks} map is read off-thread by {@link #contains}; the global
     * insertion order + total count live on the store (server-thread only).
     */
    private static final class DimBuckets {
        /** chunkKey ({@link ChunkPos#asLong}) -> immutable published set of packed BlockPos longs. */
        final ConcurrentHashMap<Long, LongOpenHashSet> chunks = new ConcurrentHashMap<>();
        /** Set on any mutation; cleared by the debounced flush. */
        volatile boolean dirty = false;
    }

    /** A (dimension, chunkKey) reference for the global insertion-order deque. Server-thread only. */
    private static final class ChunkRef {
        final String dimensionId;
        final long chunkKey;

        ChunkRef(String dimensionId, long chunkKey) {
            this.dimensionId = dimensionId;
            this.chunkKey = chunkKey;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ChunkRef other)) {
                return false;
            }
            return this.chunkKey == other.chunkKey && this.dimensionId.equals(other.dimensionId);
        }

        @Override
        public int hashCode() {
            return 31 * this.dimensionId.hashCode() + Long.hashCode(this.chunkKey);
        }
    }

    /**
     * The protection rule every caller shares: a position is protected iff the feature is on, a
     * store is loaded, and a real player placed a block there. The store keeps no placer, so the
     * rule cannot tell the companion's owner from anyone else: excavate, fill and mine all leave
     * every player-placed block standing.
     */
    public static boolean protects(boolean enabled, PlayerPlacedBlockStore store, String dimensionId, BlockPos pos) {
        return enabled && store != null && store.contains(dimensionId, pos);
    }

    /** An empty, unpersisted store that is not the singleton, for the self-test. */
    static PlayerPlacedBlockStore forSelfTest() {
        return new PlayerPlacedBlockStore(null);
    }

    // -------------------------------------------------------------------------
    // Load / clear lifecycle
    // -------------------------------------------------------------------------

    /**
     * Loads the store for the given server's world and sets {@link #INSTANCE}. Called from
     * {@code SERVER_STARTING}; never throws — per-dimension failures quarantine + start that
     * dimension empty.
     */
    public static PlayerPlacedBlockStore loadForServer(MinecraftServer server) {
        Path worldRoot = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        PlayerPlacedBlockStore store = new PlayerPlacedBlockStore(worldRoot);
        store.load();
        store.flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PlayerPlacedBlockStore-flush");
            t.setDaemon(true);
            return t;
        });
        store.flusher.scheduleWithFixedDelay(
                store::flushDirty, FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);
        INSTANCE = store;
        return store;
    }

    /**
     * Stops the flusher, writes any remaining dirty dimensions, and nulls the singleton. Called
     * from {@code SERVER_STOPPING}. After this {@link #contains} degrades to {@code false}.
     */
    public void clear() {
        if (flusher != null) {
            flusher.shutdown();
            flusher = null;
        }
        flushDirty();
        byDimension.clear();
        insertionOrder.clear();
        totalBlocks.set(0);
        INSTANCE = null;
        PlayerEngine.LOGGER.info("PlayerPlacedBlockStore: store cleared.");
    }

    // -------------------------------------------------------------------------
    // Public API (mutators server-thread; contains off-thread hot read)
    // -------------------------------------------------------------------------

    /**
     * Records a real-player placement (caller-gated). Enforces the cap with oldest-chunk eviction
     * and marks the dimension dirty for the debounced flush. No-op if already present.
     */
    public void add(String dimensionId, BlockPos pos) {
        DimBuckets b = byDimension.computeIfAbsent(dimensionId, k -> new DimBuckets());
        long chunkKey = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        long posKey = pos.asLong();
        LongOpenHashSet old = b.chunks.get(chunkKey);
        if (old != null && old.contains(posKey)) {
            return; // already tracked
        }
        LongOpenHashSet copy = (old == null) ? new LongOpenHashSet() : new LongOpenHashSet(old);
        copy.add(posKey);
        b.chunks.put(chunkKey, copy); // atomic swap of an immutable, fully-built set
        if (old == null) {
            insertionOrder.addLast(new ChunkRef(dimensionId, chunkKey));
        }
        totalBlocks.incrementAndGet();
        b.dirty = true;
        enforceCap();
    }

    /**
     * Purges a position (called from the break/removal hook so the store never accumulates stale
     * entries). Marks the dimension dirty when something was removed.
     *
     * @return true if the position was present and removed
     */
    public boolean remove(String dimensionId, BlockPos pos) {
        DimBuckets b = byDimension.get(dimensionId);
        if (b == null) {
            return false;
        }
        long chunkKey = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        long posKey = pos.asLong();
        LongOpenHashSet old = b.chunks.get(chunkKey);
        if (old == null || !old.contains(posKey)) {
            return false;
        }
        if (old.size() == 1) {
            b.chunks.remove(chunkKey);
            insertionOrder.remove(new ChunkRef(dimensionId, chunkKey)); // no duplicate chunk refs
        } else {
            LongOpenHashSet copy = new LongOpenHashSet(old);
            copy.remove(posKey);
            b.chunks.put(chunkKey, copy); // atomic swap
        }
        totalBlocks.decrementAndGet();
        b.dirty = true;
        return true;
    }

    /**
     * Off-thread, lock-free, allocation-free hot read used by {@code CalculationContext.isProtected}.
     * Reads only immutable published sets, so it can never observe a half-mutated set or throw.
     */
    public boolean contains(String dimensionId, BlockPos pos) {
        DimBuckets b = byDimension.get(dimensionId);
        if (b == null) {
            return false;
        }
        LongOpenHashSet set = b.chunks.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        return set != null && set.contains(pos.asLong());
    }

    /** Total tracked positions across all dimensions (diagnostic / cap checks). O(1). */
    public int size() {
        return totalBlocks.get();
    }

    // -------------------------------------------------------------------------
    // Internal: cap + eviction (S5)
    // -------------------------------------------------------------------------

    /** Reads {@code respectStructuresStoreMaxBlocks} from global settings, clamped to safe bounds. */
    private static int maxBlocks() {
        try {
            int v = BaritoneAPI.getGlobalSettings().respectStructuresStoreMaxBlocks.get();
            if (v < MIN_MAX_BLOCKS) return MIN_MAX_BLOCKS;
            if (v > MAX_MAX_BLOCKS) return MAX_MAX_BLOCKS;
            return v;
        } catch (Throwable t) {
            return DEFAULT_MAX_BLOCKS;
        }
    }

    /**
     * Evicts the globally least-recently-added whole chunk buckets (oldest first, across <em>all</em>
     * dimensions) until the total store size is back under the cap. Logs a single throttled warning when
     * eviction occurs. Server-thread only.
     */
    private void enforceCap() {
        int cap = maxBlocks();
        boolean evicted = false;
        while (totalBlocks.get() > cap && !insertionOrder.isEmpty()) {
            ChunkRef oldest = insertionOrder.pollFirst();
            if (oldest == null) {
                break;
            }
            DimBuckets db = byDimension.get(oldest.dimensionId);
            if (db == null) {
                continue; // dimension already gone; ref is stale, drop it
            }
            LongOpenHashSet removed = db.chunks.remove(oldest.chunkKey);
            if (removed != null) {
                totalBlocks.addAndGet(-removed.size());
                db.dirty = true;
                evicted = true;
            }
        }
        if (evicted) {
            long now = System.currentTimeMillis();
            if (now - lastEvictionWarnMs > EVICTION_WARN_INTERVAL_MS) {
                lastEvictionWarnMs = now;
                PlayerEngine.LOGGER.warn(
                        "PlayerPlacedBlockStore: cap {} reached — evicting oldest chunk(s); recent builds favored.",
                        cap);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal: load and persist
    // -------------------------------------------------------------------------

    /** Scans the playerplaced root for per-dimension files and loads each (quarantine-on-corrupt). */
    private void load() {
        Path root = Player2NpcPersistencePaths.playerPlacedRoot(worldRoot);
        if (!Files.isDirectory(root)) {
            PlayerEngine.LOGGER.info("PlayerPlacedBlockStore: no playerplaced data found — starting empty.");
            return;
        }
        try (Stream<Path> dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                Path file = dir.resolve(BLOCKS_FILE_NAME);
                if (Files.exists(file)) {
                    loadDimensionFile(file);
                }
            });
        } catch (IOException e) {
            PlayerEngine.LOGGER.warn("PlayerPlacedBlockStore: could not scan playerplaced root: {}", e.getMessage());
        }
        PlayerEngine.LOGGER.info(
                "PlayerPlacedBlockStore: loaded {} position(s) across {} dimension(s).",
                size(), byDimension.size());
    }

    private void loadDimensionFile(Path file) {
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null) {
                quarantine(file, "root is null");
                return;
            }
            int version = root.has("schemaVersion") ? root.get("schemaVersion").getAsInt() : 0;
            if (version != SCHEMA_VERSION) {
                quarantine(file, "schemaVersion " + version + " != supported " + SCHEMA_VERSION);
                return;
            }
            if (!root.has("dimension") || !root.has("chunks") || !root.get("chunks").isJsonArray()) {
                quarantine(file, "missing dimension or chunks array");
                return;
            }
            String dimensionId = root.get("dimension").getAsString();
            DimBuckets b = byDimension.computeIfAbsent(dimensionId, k -> new DimBuckets());
            for (JsonElement chunkElem : root.get("chunks").getAsJsonArray()) {
                JsonObject chunkObj = chunkElem.getAsJsonObject();
                long chunkKey = chunkObj.get("c").getAsLong();
                JsonArray positions = chunkObj.get("p").getAsJsonArray();
                LongOpenHashSet set = new LongOpenHashSet(positions.size());
                for (JsonElement p : positions) {
                    set.add(p.getAsLong());
                }
                if (set.isEmpty()) {
                    continue;
                }
                b.chunks.put(chunkKey, set);
                insertionOrder.addLast(new ChunkRef(dimensionId, chunkKey));
                totalBlocks.addAndGet(set.size());
            }
        } catch (Exception e) {
            quarantine(file, e.getMessage());
        }
    }

    /** Renames a corrupt/unsupported file to {@code blocks.json.corrupt-<epoch>} and skips it (no crash). */
    private void quarantine(Path file, String reason) {
        String quarantineName = BLOCKS_FILE_NAME + ".corrupt-" + System.currentTimeMillis();
        try {
            Files.move(file, file.resolveSibling(quarantineName), StandardCopyOption.REPLACE_EXISTING);
            PlayerEngine.LOGGER.warn(
                    "PlayerPlacedBlockStore: quarantined corrupt {} as {} (reason: {}); skipping.",
                    file.getFileName(), quarantineName, reason);
        } catch (IOException ex) {
            PlayerEngine.LOGGER.warn(
                    "PlayerPlacedBlockStore: could not quarantine {} (reason: {}; rename error: {}); skipping.",
                    file.getFileName(), reason, ex.getMessage());
        }
    }

    /** Writes every dirty dimension to disk via atomic tmp-rename. Server-thread + flusher-thread safe. */
    private void flushDirty() {
        for (var entry : byDimension.entrySet()) {
            DimBuckets b = entry.getValue();
            if (!b.dirty) {
                continue;
            }
            b.dirty = false; // clear before writing; a concurrent mutation re-sets it for the next pass
            try {
                persistDimension(entry.getKey(), b);
            } catch (Exception e) {
                b.dirty = true; // retry next flush
                PlayerEngine.LOGGER.warn(
                        "PlayerPlacedBlockStore: failed to persist a dimension file: {}", e.getMessage());
            }
        }
    }

    private void persistDimension(String dimensionId, DimBuckets b) throws IOException {
        Path file = Player2NpcPersistencePaths.playerPlacedFile(worldRoot, dimensionId);
        Files.createDirectories(file.getParent());
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("dimension", dimensionId);
        JsonArray chunks = new JsonArray();
        // Iterate the ConcurrentHashMap of immutable published sets (weakly consistent, safe off-thread).
        for (var chunkEntry : b.chunks.entrySet()) {
            LongOpenHashSet set = chunkEntry.getValue();
            if (set == null || set.isEmpty()) {
                continue;
            }
            JsonObject chunkObj = new JsonObject();
            chunkObj.addProperty("c", chunkEntry.getKey());
            JsonArray positions = new JsonArray();
            for (long p : set.toLongArray()) {
                positions.add(p);
            }
            chunkObj.add("p", positions);
            chunks.add(chunkObj);
        }
        root.add("chunks", chunks);
        String json = GSON.toJson(root);
        Path tmp = file.resolveSibling(BLOCKS_FILE_NAME + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        atomicReplace(tmp, file);
    }

    private static void atomicReplace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
