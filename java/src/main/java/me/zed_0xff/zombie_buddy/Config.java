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

public record Config(
    List<SteamID64> trusted_authors,
    Map<String, String> preload_mods
) {
    public static final String JSON_FILE_NAME = "config.json";

    public Config {
        trusted_authors = normalizeTrustedAuthors(trusted_authors);
        preload_mods = normalizePreloadMods(preload_mods);
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
        return authorId != null && trusted_authors.contains(authorId);
    }

    public Config withTrustedAuthor(SteamID64 authorId) {
        if (authorId == null || trusted_authors.contains(authorId)) {
            return this;
        }
        List<SteamID64> out = new ArrayList<>(trusted_authors);
        out.add(authorId);
        return new Config(out, preload_mods);
    }

    public Config withoutTrustedAuthor(SteamID64 authorId) {
        if (authorId == null || !trusted_authors.contains(authorId)) {
            return this;
        }
        List<SteamID64> out = new ArrayList<>(trusted_authors);
        out.remove(authorId);
        return new Config(out, preload_mods);
    }

    public Config withPreloadMod(String javaPkgName, String jarPath) {
        if (Utils.isBlank(javaPkgName) || Utils.isBlank(jarPath)) {
            return this;
        }
        Map<String, String> out = new LinkedHashMap<>(preload_mods);
        out.put(javaPkgName, jarPath);
        return new Config(trusted_authors, out);
    }

    public Config withoutPreloadMod(String javaPkgName) {
        if (Utils.isBlank(javaPkgName) || !preload_mods.containsKey(javaPkgName)) {
            return this;
        }
        Map<String, String> out = new LinkedHashMap<>(preload_mods);
        out.remove(javaPkgName);
        return new Config(trusted_authors, out);
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
