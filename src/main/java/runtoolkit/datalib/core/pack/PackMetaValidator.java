package runtoolkit.datalib.core.pack;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.resource.ResourcePackProfile;
import net.minecraft.server.MinecraftServer;
import runtoolkit.datalib.core.DataLibCore;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validates pack.mcmeta for all enabled datapacks.
 *
 * Excludes packs from DataLib systems if:
 *  - pack_format != EXPECTED_PACK_FORMAT  → ERROR log
 *  - environment doesn't match runtime    → WARN log
 */
public final class PackMetaValidator {

    private static final Gson GSON = new Gson();

    /** pack_format for Minecraft 1.21.1 */
    private static final int EXPECTED_PACK_FORMAT = 48;

    private static final Map<String, PackMeta> VALID_PACKS    = new LinkedHashMap<>();
    private static final Map<String, PackMeta> EXCLUDED_PACKS = new LinkedHashMap<>();

    private PackMetaValidator() {}

    // -------------------------------------------------------------------------

    public static void validate(MinecraftServer server) {
        VALID_PACKS.clear();
        EXCLUDED_PACKS.clear();

        PackMeta.Environment currentEnv = PackMeta.Environment.detect(server);

        var profiles = server.getDataPackManager().getEnabledProfiles();

        if (profiles.isEmpty()) {
            DataLibCore.LOGGER.info("[DataLib] No datapacks enabled — nothing to validate.");
            return;
        }

        DataLibCore.LOGGER.info("[DataLib] Validating {} datapack(s) (expected pack_format={})...",
            profiles.size(), EXPECTED_PACK_FORMAT);

        int valid    = 0;
        int excluded = 0;

        for (ResourcePackProfile profile : profiles) {
            String packName = profile.getName();
            PackMeta meta   = readPackMeta(profile, packName);

            if (meta == null) {
                DataLibCore.LOGGER.warn(
                    "[DataLib] [{}] Could not read pack.mcmeta — skipping DataLib validation.", packName);
                continue;
            }

            // 1. pack_format check
            if (meta.packFormat() != EXPECTED_PACK_FORMAT) {
                DataLibCore.LOGGER.error(
                    "[DataLib] [{}] EXCLUDED — pack_format mismatch: expected {}, got {}. " +
                    "DataLib features will not apply to this pack.",
                    meta.effectiveId(), EXPECTED_PACK_FORMAT, meta.packFormat());
                EXCLUDED_PACKS.put(meta.effectiveId(), meta);
                excluded++;
                continue;
            }

            // 2. environment check
            if (!meta.isActiveIn(currentEnv)) {
                DataLibCore.LOGGER.warn(
                    "[DataLib] [{}] EXCLUDED — environment '{}' does not match current runtime ({}).",
                    meta.effectiveId(),
                    meta.environment(),
                    currentEnv.name().toLowerCase());
                EXCLUDED_PACKS.put(meta.effectiveId(), meta);
                excluded++;
                continue;
            }

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

    private static PackMeta readPackMeta(ResourcePackProfile profile, String packName) {
        try (var pack = profile.createResourcePack()) {
            // openRoot returns InputSupplier<InputStream> — must call .get()
            var supplier = pack.openRoot("pack.mcmeta");
            if (supplier == null) return null;

            try (InputStream is = supplier.get();
                 var reader   = new InputStreamReader(is, StandardCharsets.UTF_8)) {

                JsonObject root = GSON.fromJson(reader, JsonObject.class);
                if (root == null || !root.has("pack")) return null;

                JsonObject packObj = root.getAsJsonObject("pack");

                int    format      = packObj.has("pack_format")  ? packObj.get("pack_format").getAsInt()     : -1;
                String description = packObj.has("description")  ? packObj.get("description").getAsString()  : "";
                String id          = packObj.has("id")           ? packObj.get("id").getAsString()           : null;
                String environment = packObj.has("environment")  ? packObj.get("environment").getAsString()  : null;

                return new PackMeta(packName, format, description, id, environment);
            }
        } catch (Exception e) {
            DataLibCore.LOGGER.debug("[DataLib] Could not read pack.mcmeta for '{}': {}", packName, e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------

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
