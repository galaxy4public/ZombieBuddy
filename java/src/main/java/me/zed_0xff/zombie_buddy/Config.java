package me.zed_0xff.zombie_buddy;

import static me.zed_0xff.zombie_buddy.SteamWorkshop.SteamID64;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.google.gson.annotations.SerializedName;

public record Config(
    @SerializedName("trusted_authors") List<SteamID64> trustedAuthors,
    @SerializedName("preload_mods") Map<String, String> preloadMods
) {
    public static final String JSON_FILE_NAME = "config.json";

    public Config {
        trustedAuthors = normalizeTrustedAuthors(trustedAuthors);
        preloadMods = normalizePreloadMods(preloadMods);
    }

    public Config() {
        this(new ArrayList<>(), new LinkedHashMap<>());
    }

    static Path jsonPath() {
        return Agent.configDir().resolve(JSON_FILE_NAME);
    }

    public static Config load() {
        Path path = jsonPath();
        try {
            if (!Files.exists(path)) {
                return new Config();
            }
            String json = Files.readString(path, StandardCharsets.UTF_8);
            if (Utils.isBlank(json)) {
                return new Config();
            }
            Config config = ZBGson.PRETTY.fromJson(json, Config.class);
            return config != null ? config : new Config();
        } catch (Exception e) {
            Logger.error("Could not load config: " + e);
            return new Config();
        }
    }

    public static void save(Config config) {
        try {
            Path path = jsonPath();
            Files.createDirectories(path.getParent());
            Utils.writeFileAtomic(path, ZBGson.PRETTY.toJson(config != null ? config : new Config()), StandardCharsets.UTF_8);
            Logger.info("ZombieBuddy config written to " + path);
        } catch (Exception e) {
            Logger.error("Could not save config: " + e);
        }
    }

    public boolean trustsAuthor(SteamID64 authorId) {
        return authorId != null && trustedAuthors.contains(authorId);
    }

    public Config withTrustedAuthor(SteamID64 authorId) {
        if (authorId == null || trustedAuthors.contains(authorId)) {
            return this;
        }
        List<SteamID64> out = new ArrayList<>(trustedAuthors);
        out.add(authorId);
        return new Config(out, preloadMods);
    }

    public Config withoutTrustedAuthor(SteamID64 authorId) {
        if (authorId == null || !trustedAuthors.contains(authorId)) {
            return this;
        }
        List<SteamID64> out = new ArrayList<>(trustedAuthors);
        out.remove(authorId);
        return new Config(out, preloadMods);
    }

    private static List<SteamID64> normalizeTrustedAuthors(List<SteamID64> input) {
        LinkedHashSet<SteamID64> out = new LinkedHashSet<>();
        if (input != null) {
            for (SteamID64 id : input) {
                if (id != null) {
                    out.add(id);
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static Map<String, String> normalizePreloadMods(Map<String, String> input) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (input != null) {
            for (Map.Entry<String, String> entry : input.entrySet()) {
                if (!Utils.isBlank(entry.getKey()) && !Utils.isBlank(entry.getValue())) {
                    out.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return out;
    }
}
