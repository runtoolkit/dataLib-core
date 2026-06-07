package runtoolkit.datalib.core.pack;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.SharedConstants;
import runtoolkit.datalib.core.DataLibCore;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validates pack.mcmeta files for all enabled datapacks on server start / reload.
 *
 * For each pack:
 *  - Parses standard pack_format + description
 *  - Parses DataLib extension fields: "id", "environment"
 *  - Logs ERROR and excludes from DataLib systems if pack_format is wrong
 *  - Logs WARNING if "environment" excludes the pack from the current runtime
 *
 * Results are stored in a static map keyed by effectiveId and consumed by
 * CommandAliasLoader and other DataLib subsystems.
 */
public final class PackMetaValidator {

    private static final Gson GSON = new Gson();

    /** Current expected pack_format for 1.21.1 */
    private static final int EXPECTED_PACK_FORMAT = 48;

    /** effectiveId → PackMeta for all packs that passed validation */
    private static final Map<String, PackMeta> VALID_PACKS = new LinkedHashMap<>();

    /** effectiveId → PackMeta for packs excluded by DataLib (wrong format or wrong env) */
    private static final Map<String, PackMeta> EXCLUDED_PACKS = new LinkedHashMap<>();

    private PackMetaValidator() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public static void validate(MinecraftServer server) {
        VALID_PACKS.clear();
        EXCLUDED_PACKS.clear();

        PackMeta.Environment currentEnv = PackMeta.Environment.detect(server);
        var resourceManager = server.getResourceManager();

        // Find every pack.mcmeta across all enabled datapacks
        var resources = resourceManager.findResources(
            "../pack.mcmeta",   // relative path from the data root
            id -> id.getPath().endsWith("pack.mcmeta")
        );

        // Fallback: Fabric exposes pack list via DataPackManager
        var enabledPacks = server.getDataPackManager().getEnabledNames();

        if (enabledPacks.isEmpty()) {
            DataLibCore.LOGGER.info("[DataLib] No datapacks enabled — nothing to validate.");
            return;
        }

        DataLibCore.LOGGER.info("[DataLib] Validating {} datapack(s) (expected pack_format={})...",
            enabledPacks.size(), EXPECTED_PACK_FORMAT);

        int valid = 0;
        int excluded = 0;

        for (String packName : enabledPacks) {
            PackMeta meta = readPackMeta(server, packName);

            if (meta == null) {
                // Could not read pack.mcmeta — treat as unknown, don't block
                DataLibCore.LOGGER.warn("[DataLib] [{}] Could not read pack.mcmeta — skipping DataLib validation.", packName);
                continue;
            }

            // 1. pack_format check
            if (meta.packFormat() != EXPECTED_PACK_FORMAT) {
                DataLibCore.LOGGER.error(
                    "[DataLib] [{}] EXCLUDED — pack_format mismatch: expected {}, got {}. " +
                    "DataLib features will not apply to this pack.",
                    meta.effectiveId(), EXPECTED_PACK_FORMAT, meta.packFormat()
                );
                EXCLUDED_PACKS.put(meta.effectiveId(), meta);
                excluded++;
                continue;
            }

            // 2. environment check
            if (!meta.isActiveIn(currentEnv)) {
                DataLibCore.LOGGER.warn(
                    "[DataLib] [{}] EXCLUDED — environment '{}' does not match current runtime ({}).",
                    meta.effectiveId(), meta.environment(), currentEnv.name().toLowerCase()
                );
                EXCLUDED_PACKS.put(meta.effectiveId(), meta);
                excluded++;
                continue;
            }

            // Passed all checks
            if (meta.id() != null && !meta.id().isBlank()) {
                DataLibCore.LOGGER.info("[DataLib] [{}] OK (id='{}', env='{}')",
                    packName, meta.effectiveId(),
                    meta.environment() != null ? meta.environment() : "all");
            } else {
                DataLibCore.LOGGER.info("[DataLib] [{}] OK", packName);
            }

            VALID_PACKS.put(meta.effectiveId(), meta);
            valid++;
        }

        DataLibCore.LOGGER.info("[DataLib] Pack validation complete: {} valid, {} excluded.", valid, excluded);
    }

    // -------------------------------------------------------------------------
    // pack.mcmeta reader
    // -------------------------------------------------------------------------

    private static PackMeta readPackMeta(MinecraftServer server, String packName) {
        // Try to find pack.mcmeta via resource manager
        // Fabric exposes packs under their directory names
        var resourceManager = server.getResourceManager();

        // pack.mcmeta sits at the root of each pack — try common lookup paths
        String[] candidates = {
            "pack.mcmeta",
        };

        for (String candidate : candidates) {
            try {
                var optResource = resourceManager.getResource(
                    net.minecraft.util.Identifier.of("minecraft", candidate)
                );
                // Resource manager merges packs; we need per-pack access.
                // Use DataPackManager to get the pack's own resource directly.
                break;
            } catch (Exception ignored) {}
        }

        // Primary path: read directly from the pack via DataPackManager
        try {
            var packManager = server.getDataPackManager();
            var pack = packManager.getProfile(packName);

            if (pack == null) return null;

            try (var packResources = pack.createResourcePack();
                 var metaStream = packResources.openRoot("pack.mcmeta")) {

                if (metaStream == null) return null;

                try (var reader = new InputStreamReader(metaStream, StandardCharsets.UTF_8)) {
                    JsonObject root = GSON.fromJson(reader, JsonObject.class);

                    if (root == null || !root.has("pack")) return null;

                    JsonObject packObj = root.getAsJsonObject("pack");

                    int format = packObj.has("pack_format")
                        ? packObj.get("pack_format").getAsInt()
                        : -1;

                    String description = packObj.has("description")
                        ? packObj.get("description").getAsString()
                        : "";

                    // DataLib extension fields
                    String id = packObj.has("id")
                        ? packObj.get("id").getAsString()
                        : null;

                    String environment = packObj.has("environment")
                        ? packObj.get("environment").getAsString()
                        : null;

                    return new PackMeta(packName, format, description, id, environment);
                }
            }
        } catch (Exception e) {
            DataLibCore.LOGGER.debug("[DataLib] Could not read pack.mcmeta for '{}': {}", packName, e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Accessors used by other DataLib subsystems
    // -------------------------------------------------------------------------

    /** Returns true if the given effectiveId passed validation. */
    public static boolean isValid(String effectiveId) {
        return VALID_PACKS.containsKey(effectiveId);
    }

    public static Map<String, PackMeta> getValidPacks() {
        return Collections.unmodifiableMap(VALID_PACKS);
    }

    public static Map<String, PackMeta> getExcludedPacks() {
        return Collections.unmodifiableMap(EXCLUDED_PACKS);
    }

    public static int getExpectedPackFormat() {
        return EXPECTED_PACK_FORMAT;
    }
}
