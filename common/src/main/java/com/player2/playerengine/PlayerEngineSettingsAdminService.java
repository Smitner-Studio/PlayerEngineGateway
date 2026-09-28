package com.player2.playerengine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.player2.playerengine.control.KillAura;
import com.player2.playerengine.tasks.movement.DefaultGoToDimensionTask;
import com.player2.playerengine.util.helpers.ConfigHelper;
import com.player2.playerengine.util.serialization.gson.BlockPosTypeAdapter;
import com.player2.playerengine.util.serialization.gson.ItemListTypeAdapter;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Typed, validated persistence boundary for the PlayerEngine settings exposed by the mod UI. */
public final class PlayerEngineSettingsAdminService {

    private static final String CONFIG_FILE = PlayerEngine.MOD_ID + "_settings.json";
    private static final int MAX_ITEM_LIST_ENTRIES = 64;
    private static final int MAX_ITEM_LIST_TEXT_LENGTH =
            PlayerEngineSettings.ADMIN_ITEM_LIST_MAX_SERIALIZED_LENGTH;

    private static final Gson READER_GSON = new GsonBuilder()
            .registerTypeAdapter(BlockPos.class, new BlockPosTypeAdapter())
            .registerTypeAdapter(new TypeToken<List<Item>>() {}.getType(), new ItemListTypeAdapter())
            .create();

    private static final Set<String> BOOLEAN_KEYS = Set.of(
            "avoidSearchingDungeonChests",
            "avoidOceanBlocks",
            "collectPickaxeFirst",
            "replantCrops",
            "mobDefense",
            "dodgeProjectiles",
            "killOrAvoidAnnoyingHostiles",
            "avoidDrowning",
            "extinguishSelfWithWater",
            "autoEat",
            "hungerEnabled",
            "autoMLGBucket",
            "fastCraftMacrosEnabled",
            "preferLocalCraftingTable",
            "enableAgenticPlanner",
            "enableLookAtOwnerIdle",
            "agenticPlannerFallbackGather",
            "agenticStorageAllowPlacement",
            "agenticStoragePreferExisting",
            "agenticStorageAvoidLootChests",
            "agenticDepositKeepTools",
            "agenticEnableLabelChest",
            "agenticLabelUseModelText",
            "enableDeferredSmelt",
            "enableSmithing",
            "ellieGpsEnabled",
            "ellieGpsUseModelDescription",
            "dontThrowAwayCustomNameItems",
            "dontThrowAwayEnchantedItems",
            "throwAwayUnusedItems",
            "limitFuelsToSupportedFuels"
    );

    private static final Set<String> ITEM_LIST_KEYS = Set.of(
            "throwawayItems", "importantItems", "supportedFuels");

    private PlayerEngineSettingsAdminService() {}

    public enum UpdateResult {
        OK,
        INVALID_VALUE,
        UNSUPPORTED_SETTING,
        SAVE_FAILED,
        SAVED_LIVE_PENDING,
        SAVED_LIVE_FAILED
    }

    public record SnapshotResult(Map<String, String> values, boolean loadFailed,
                                 PlayerEngineController.SettingsLiveState liveState) {
    }

    public static Map<String, String> snapshot() {
        return snapshotResult().values();
    }

    public static SnapshotResult snapshotResult() {
        PlayerEngineSettings settings;
        boolean loadFailed = false;
        try {
            settings = readFreshCandidate();
        } catch (IOException ignored) {
            settings = null;
            loadFailed = true;
        }
        if (settings == null) {
            loadFailed = true;
            settings = liveFallback();
        }
        settings.normalizeAdminSupportedValues();
        return new SnapshotResult(snapshotOf(settings), loadFailed,
                PlayerEngineController.currentSettingsLiveState());
    }

    public static synchronized UpdateResult update(String key, String value, BlockPos currentPosition) {
        if (!isSupportedKey(key)) {
            return UpdateResult.UNSUPPORTED_SETTING;
        }

        CandidateMutation mutation = parseMutation(key, value, currentPosition);
        if (mutation == null) {
            return UpdateResult.INVALID_VALUE;
        }

        PlayerEngineSettings candidate;
        try {
            candidate = readFreshCandidate();
        } catch (IOException e) {
            return UpdateResult.SAVE_FAILED;
        }
        if (candidate == null) {
            return UpdateResult.INVALID_VALUE;
        }

        candidate.normalizeAdminSupportedValues();
        mutation.apply(candidate);
        // A single UI edit must never repair the coupled food fields by silently changing the
        // other control. Full config loads normalize the pair; interactive edits are rejected.
        if (!candidate.hasValidFoodSafety()) {
            return UpdateResult.INVALID_VALUE;
        }
        candidate.normalizeAdminSupportedValues();

        try {
            JsonElement canonicalJson = READER_GSON.toJsonTree(candidate, PlayerEngineSettings.class);
            if (!ConfigHelper.saveConfigChecked(CONFIG_FILE, canonicalJson)) {
                return UpdateResult.SAVE_FAILED;
            }
        } catch (RuntimeException e) {
            return UpdateResult.SAVE_FAILED;
        }
        PlayerEngineController.SettingsPublicationOutcome publication =
                PlayerEngineController.publishSettingsFromAdmin(candidate);
        if (publication.hasFailures()) {
            return UpdateResult.SAVED_LIVE_FAILED;
        }
        return publication.fullyApplied() ? UpdateResult.OK : UpdateResult.SAVED_LIVE_PENDING;
    }

    static PlayerEngineController.SettingsPublicationOutcome reloadFromDiskAndPublish() {
        PlayerEngineSettings candidate;
        try {
            candidate = readFreshCandidate();
        } catch (IOException e) {
            candidate = null;
        }
        if (candidate == null) {
            return new PlayerEngineController.SettingsPublicationOutcome(1, 0, 0, 1);
        }
        candidate.normalizeAdminSupportedValues();
        return PlayerEngineController.publishSettingsFromAdmin(candidate);
    }

    private static PlayerEngineSettings readFreshCandidate() throws IOException {
        Path path = PlayerEnginePaths.userFile(CONFIG_FILE);
        if (Files.notExists(path)) {
            return new PlayerEngineSettings();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!hasValidStoredItemLists(root)) {
                return null;
            }
            return READER_GSON.fromJson(root, PlayerEngineSettings.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean hasValidStoredItemLists(JsonElement root) {
        if (root == null || !root.isJsonObject()) {
            return false;
        }
        for (String key : ITEM_LIST_KEYS) {
            if (!root.getAsJsonObject().has(key)) {
                continue;
            }
            JsonElement list = root.getAsJsonObject().get(key);
            if (list == null || !list.isJsonArray()) {
                return false;
            }
            int entryCount = 0;
            for (JsonElement entry : list.getAsJsonArray()) {
                if (++entryCount > MAX_ITEM_LIST_ENTRIES) {
                    return false;
                }
                if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) {
                    return false;
                }
                Item item = ItemListTypeAdapter.resolveItem(entry.getAsString());
                if (item == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private static PlayerEngineSettings liveFallback() {
        for (PlayerEngineController controller : PlayerEngineController.staticControllers.values()) {
            if (controller != null) {
                PlayerEngineSettings live = controller.getModSettings();
                if (live != null) {
                    return live.defensiveCopy();
                }
            }
        }
        return new PlayerEngineSettings();
    }

    private static Map<String, String> snapshotOf(PlayerEngineSettings settings) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("resourcePickupDropRange", whole(settings.getResourcePickupRange()));
        values.put("minimumFoodAllowed", String.valueOf(settings.getMinimumFoodAllowed()));
        values.put("foodUnitsToCollect", String.valueOf(settings.getFoodUnitsToCollect()));
        values.put("resourceChestLocateRange", whole(settings.getResourceChestLocateRange()));
        values.put("resourceMineRange", whole(settings.getResourceMineRange()));
        values.put("avoidSearchingDungeonChests", String.valueOf(settings.shouldAvoidSearchingForDungeonChests()));
        values.put("avoidOceanBlocks", String.valueOf(settings.shouldAvoidOcean()));
        values.put("entityReachRange", whole(settings.getEntityReachRange()));
        values.put("collectPickaxeFirst", String.valueOf(settings.shouldCollectPickaxeFirst()));
        values.put("replantCrops", String.valueOf(settings.shouldReplantCrops()));
        values.put("mobDefense", String.valueOf(settings.isMobDefense()));
        values.put("forceFieldStrategy", settings.getForceFieldStrategy().name());
        values.put("dodgeProjectiles", String.valueOf(settings.isDodgeProjectiles()));
        values.put("killOrAvoidAnnoyingHostiles", String.valueOf(settings.shouldDealWithAnnoyingHostiles()));
        values.put("avoidDrowning", String.valueOf(settings.shouldAvoidDrowning()));
        values.put("extinguishSelfWithWater", String.valueOf(settings.shouldExtinguishSelfWithWater()));
        values.put("autoEat", String.valueOf(settings.isAutoEat()));
        values.put("hungerEnabled", String.valueOf(settings.isHungerEnabled()));
        values.put("autoMLGBucket", String.valueOf(settings.shouldAutoMLGBucket()));
        values.put("overworldToNetherBehaviour", settings.getOverworldToNetherBehaviour().name());
        values.put("netherFastTravelWalkingRange", String.valueOf(settings.getNetherFastTravelWalkingRange()));
        values.put("idleCommand", settings.getIdleCommand());
        values.put("fastCraftMacrosEnabled", String.valueOf(settings.isFastCraftMacrosEnabled()));
        values.put("craftDelaySeconds", milliseconds(settings.getCraftDelaySeconds()));
        values.put("craftTableLookHoldSeconds", milliseconds(settings.getCraftTableLookHoldSeconds()));
        values.put("preferLocalCraftingTable", String.valueOf(settings.isPreferLocalCraftingTable()));
        values.put("craftingTableReuseRadius", whole(settings.getCraftingTableReuseRadius()));
        values.put("enableAgenticPlanner", String.valueOf(settings.isEnableAgenticPlanner()));
        values.put("enableLookAtOwnerIdle", String.valueOf(settings.isEnableLookAtOwnerIdle()));
        values.put("agenticPlannerRagTopK", String.valueOf(settings.getAgenticPlannerRagTopK()));
        values.put("agenticPlannerMaxSteps", String.valueOf(settings.getAgenticPlannerMaxSteps()));
        values.put("agenticPlannerFallbackGather", String.valueOf(settings.isAgenticPlannerFallbackGather()));
        values.put("gatherLooseItemsRadius", whole(settings.getGatherLooseItemsRadius()));
        values.put("gatherLooseItemsMaxItems", String.valueOf(settings.getGatherLooseItemsMaxItems()));
        values.put("gatherLooseItemsTimeoutSeconds", whole(settings.getGatherLooseItemsTimeoutSeconds()));
        values.put("gatherLooseItemsSettleSeconds", milliseconds(settings.getGatherLooseItemsSettleSeconds()));
        values.put("agenticStorageSearchRadius", whole(settings.getAgenticStorageSearchRadius()));
        values.put("agenticStoragePlacementRadius", whole(settings.getAgenticStoragePlacementRadius()));
        values.put("agenticMaxTravelRadius", whole(settings.getAgenticMaxTravelRadius()));
        values.put("agenticStorageAllowPlacement", String.valueOf(settings.isAgenticStorageAllowPlacement()));
        values.put("agenticStoragePreferExisting", String.valueOf(settings.isAgenticStoragePreferExisting()));
        values.put("agenticStorageAvoidLootChests", String.valueOf(settings.isAgenticStorageAvoidLootChests()));
        values.put("agenticStorageResolveTimeoutSeconds", whole(settings.getAgenticStorageResolveTimeoutSeconds()));
        values.put("agenticDepositTimeoutSeconds", whole(settings.getAgenticDepositTimeoutSeconds()));
        values.put("agenticDepositKeepTools", String.valueOf(settings.isAgenticDepositKeepTools()));
        values.put("agenticEnableLabelChest", String.valueOf(settings.isAgenticEnableLabelChest()));
        values.put("agenticLabelTimeoutSeconds", whole(settings.getAgenticLabelTimeoutSeconds()));
        values.put("agenticLabelUseModelText", String.valueOf(settings.isAgenticLabelUseModelText()));
        values.put("enableDeferredSmelt", String.valueOf(settings.isEnableDeferredSmelt()));
        values.put("deferredSmeltMaxBatch", String.valueOf(settings.getDeferredSmeltMaxBatch()));
        values.put("enableSmithing", String.valueOf(settings.isEnableSmithing()));
        values.put("smithMaxBatch", String.valueOf(settings.getSmithMaxBatch()));
        values.put("deferredSmeltStallPolls", String.valueOf(settings.getDeferredSmeltStallPolls()));
        values.put("deferredSmeltTimeoutSeconds", String.valueOf(settings.getDeferredSmeltTimeoutSeconds()));
        values.put("aggregateCountDropRadius", whole(settings.getAggregateCountDropRadius()));
        values.put("aggregateLocalSourceBlockRadius", whole(settings.getAggregateLocalSourceBlockRadius()));
        values.put("mineCollectSettleSeconds", milliseconds(settings.getMineCollectSettleSeconds()));
        values.put("wanderBoundDefaultSeconds", String.valueOf(settings.getWanderBoundDefaultSeconds()));
        values.put("wanderNoImprovementSeconds", String.valueOf(settings.getWanderNoImprovementSeconds()));
        values.put("ellieGpsEnabled", String.valueOf(settings.getEllieGpsEnabled()));
        values.put("ellieGpsSnapshotSlotThreshold", String.valueOf(settings.getEllieGpsSnapshotSlotThreshold()));
        values.put("ellieGpsUseModelDescription", String.valueOf(settings.getEllieGpsUseModelDescription()));
        values.put("throwawayItems", formatItems(settings.adminThrowawayItems()));
        values.put("dontThrowAwayCustomNameItems", String.valueOf(settings.getDontThrowAwayCustomNameItems()));
        values.put("dontThrowAwayEnchantedItems", String.valueOf(settings.getDontThrowAwayEnchantedItems()));
        values.put("throwAwayUnusedItems", String.valueOf(settings.shouldThrowawayUnusedItems()));
        values.put("importantItems", formatItems(settings.adminImportantItems()));
        values.put("limitFuelsToSupportedFuels", String.valueOf(settings.shouldLimitFuelsToSupportedFuels()));
        values.put("supportedFuels", formatItems(settings.adminSupportedFuels()));
        BlockPos home = settings.getHomeBasePosition();
        values.put("homeBasePosition", home.getX() + "," + home.getY() + "," + home.getZ());
        return Collections.unmodifiableMap(values);
    }

    private static CandidateMutation parseMutation(String key, String value, BlockPos currentPosition) {
        if (BOOLEAN_KEYS.contains(key)) {
            Boolean parsed = parseBoolean(value);
            return parsed == null ? null : settings -> settings.setAdminBoolean(key, parsed);
        }

        IntRange range = integerRange(key);
        if (range != null) {
            Integer parsed = parseInteger(value);
            return parsed == null || !range.contains(parsed)
                    ? null : settings -> settings.setAdminInt(key, parsed);
        }

        range = wholeNumberRange(key);
        if (range != null) {
            Integer parsed = parseInteger(value);
            return parsed == null || !range.contains(parsed)
                    ? null : settings -> settings.setAdminWholeNumber(key, parsed);
        }

        range = millisecondRange(key);
        if (range != null) {
            Integer parsed = parseInteger(value);
            return parsed == null || !range.contains(parsed)
                    ? null : settings -> settings.setAdminMilliseconds(key, parsed);
        }

        if (ITEM_LIST_KEYS.contains(key)) {
            List<Item> parsed = parseItems(value);
            return parsed == null ? null : settings -> settings.setAdminItems(key, parsed);
        }

        return switch (key) {
            case "idleCommand" -> isValidIdleCommand(value)
                    ? settings -> settings.setAdminString(key, value) : null;
            case "forceFieldStrategy" -> forceFieldMutation(value);
            case "overworldToNetherBehaviour" -> dimensionMutation(value);
            case "homeBasePosition" -> currentPosition == null ? null
                    : settings -> settings.setAdminHomeBasePosition(currentPosition);
            default -> null;
        };
    }

    private static CandidateMutation forceFieldMutation(String value) {
        try {
            KillAura.Strategy strategy = KillAura.Strategy.valueOf(value);
            return settings -> settings.setAdminForceFieldStrategy(strategy);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    private static CandidateMutation dimensionMutation(String value) {
        try {
            DefaultGoToDimensionTask.OVERWORLD_TO_NETHER_BEHAVIOUR behavior =
                    DefaultGoToDimensionTask.OVERWORLD_TO_NETHER_BEHAVIOUR.valueOf(value);
            return settings -> settings.setAdminOverworldToNetherBehaviour(behavior);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    private static boolean isSupportedKey(String key) {
        return key != null && (BOOLEAN_KEYS.contains(key)
                || ITEM_LIST_KEYS.contains(key)
                || integerRange(key) != null
                || wholeNumberRange(key) != null
                || millisecondRange(key) != null
                || switch (key) {
                    case "idleCommand", "forceFieldStrategy",
                            "overworldToNetherBehaviour", "homeBasePosition" -> true;
                    default -> false;
                });
    }

    private static IntRange integerRange(String key) {
        return switch (key) {
            case "minimumFoodAllowed", "foodUnitsToCollect" -> new IntRange(0, 64);
            case "netherFastTravelWalkingRange" -> new IntRange(0, 100000);
            case "agenticPlannerRagTopK" -> new IntRange(1, 20);
            case "agenticPlannerMaxSteps" -> new IntRange(1, 4);
            case "gatherLooseItemsMaxItems" -> new IntRange(1, 1024);
            case "deferredSmeltMaxBatch", "smithMaxBatch" -> new IntRange(1, 512);
            case "deferredSmeltStallPolls" -> new IntRange(20, 2000);
            case "deferredSmeltTimeoutSeconds" -> new IntRange(60, 3600);
            case "wanderBoundDefaultSeconds" -> new IntRange(0, 600);
            case "wanderNoImprovementSeconds" -> new IntRange(0, 300);
            case "ellieGpsSnapshotSlotThreshold" -> new IntRange(1, 54);
            default -> null;
        };
    }

    private static IntRange wholeNumberRange(String key) {
        return switch (key) {
            case "resourcePickupDropRange" -> new IntRange(0, 128);
            case "resourceChestLocateRange", "resourceMineRange" -> new IntRange(0, 4096);
            case "entityReachRange" -> new IntRange(1, 6);
            case "craftingTableReuseRadius" -> new IntRange(4, 128);
            case "gatherLooseItemsRadius" -> new IntRange(2, 64);
            case "gatherLooseItemsTimeoutSeconds" -> new IntRange(5, 300);
            case "agenticStorageSearchRadius" -> new IntRange(4, 64);
            case "agenticStoragePlacementRadius" -> new IntRange(2, 8);
            case "agenticMaxTravelRadius" -> new IntRange(16, 160);
            case "agenticStorageResolveTimeoutSeconds", "agenticDepositTimeoutSeconds" -> new IntRange(10, 300);
            case "agenticLabelTimeoutSeconds" -> new IntRange(10, 180);
            case "aggregateCountDropRadius" -> new IntRange(0, 64);
            case "aggregateLocalSourceBlockRadius" -> new IntRange(0, 128);
            default -> null;
        };
    }

    private static IntRange millisecondRange(String key) {
        return switch (key) {
            case "craftDelaySeconds" -> new IntRange(0, 5000);
            case "craftTableLookHoldSeconds" -> new IntRange(0, 3000);
            case "gatherLooseItemsSettleSeconds" -> new IntRange(500, 20000);
            case "mineCollectSettleSeconds" -> new IntRange(0, 5000);
            default -> null;
        };
    }

    private static Boolean parseBoolean(String value) {
        if ("true".equals(value)) {
            return Boolean.TRUE;
        }
        if ("false".equals(value)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static Integer parseInteger(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character < '0' || character > '9') {
                return null;
            }
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isValidIdleCommand(String value) {
        return value != null && value.codePointCount(0, value.length()) <= 128
                && value.codePoints().noneMatch(java.lang.Character::isISOControl);
    }

    private static List<Item> parseItems(String value) {
        if (value == null || value.length() > MAX_ITEM_LIST_TEXT_LENGTH) {
            return null;
        }
        if (value.isBlank()) {
            return List.of();
        }

        LinkedHashSet<Item> items = new LinkedHashSet<>();
        for (String part : value.split(",", -1)) {
            String token = part.trim();
            if (token.isEmpty()) {
                return null;
            }
            ResourceLocation id = ResourceLocation.tryParse(token);
            if (id == null || !id.toString().equals(token) || !BuiltInRegistries.ITEM.containsKey(id)) {
                return null;
            }
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item == null || item == Items.AIR) {
                return null;
            }
            items.add(item);
            if (items.size() > MAX_ITEM_LIST_ENTRIES) {
                return null;
            }
        }
        return List.copyOf(items);
    }

    private static String formatItems(List<Item> items) {
        return items.stream()
                .filter(item -> item != null && item != Items.AIR)
                .map(item -> BuiltInRegistries.ITEM.getResourceKey(item)
                        .map(key -> key.location())
                        .orElse(null))
                .filter(id -> id != null)
                .map(ResourceLocation::toString)
                .distinct()
                .limit(MAX_ITEM_LIST_ENTRIES)
                .collect(Collectors.joining(","));
    }

    private static String whole(double value) {
        return String.valueOf(Math.round(value));
    }

    private static String milliseconds(double seconds) {
        return String.valueOf(Math.round(seconds * 1000.0));
    }

    @FunctionalInterface
    private interface CandidateMutation {
        void apply(PlayerEngineSettings settings);
    }

    private record IntRange(int min, int max) {
        boolean contains(int value) {
            return value >= min && value <= max;
        }
    }

}
