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

record Config(
    List<SteamID64> trusted_authors,
    Map<String, Config.PreloadMod> preload_mods
) {
    record PreloadMod(
        Path infPath,
        Path jarPath
    ) {}

    static final String JSON_FILE_NAME = "config.json";

    Config {
        trusted_authors = normalizeTrustedAuthors(trusted_authors);
        preload_mods = normalizePreloadMods(preload_mods);
    }

    Config() {
        this(new ArrayList<>(), new LinkedHashMap<>());
    }

    static Path jsonPath() {
        return Agent.configDir().resolve(JSON_FILE_NAME);
    }

    static Config load() {
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

    static void save(Config config) {
        try {
            Path path = jsonPath();
            Files.createDirectories(path.getParent());
            Utils.writeFileAtomic(path, ZBGson.PRETTY.toJson(config != null ? config : new Config()), StandardCharsets.UTF_8);
            Logger.info("ZombieBuddy config written to " + path);
        } catch (Exception e) {
            Logger.error("Could not save config: " + e);
        }
    }

    boolean trustsAuthor(SteamID64 authorId) {
        return authorId != null && trusted_authors.contains(authorId);
    }

    Config withTrustedAuthor(SteamID64 authorId) {
        if (authorId == null || trusted_authors.contains(authorId)) {
            return this;
        }
        List<SteamID64> out = new ArrayList<>(trusted_authors);
        out.add(authorId);
        return new Config(out, preload_mods);
    }

    Config withoutTrustedAuthor(SteamID64 authorId) {
        if (authorId == null || !trusted_authors.contains(authorId)) {
            return this;
        }
        List<SteamID64> out = new ArrayList<>(trusted_authors);
        out.remove(authorId);
        return new Config(out, preload_mods);
    }

    Config withPreloadMod(String id, PreloadMod mod) {
        if (Utils.isBlank(id)
                || mod == null
                || Utils.isBlank(mod.infPath())
                || Utils.isBlank(mod.jarPath())) {
            return this;
        }
        Map<String, PreloadMod> out = new LinkedHashMap<>(preload_mods);
        out.put(id, mod);
        return new Config(trusted_authors, out);
    }

    Config withoutPreloadMod(String id) {
        if (Utils.isBlank(id) || !preload_mods.containsKey(id)) {
            return this;
        }
        Map<String, PreloadMod> out = new LinkedHashMap<>(preload_mods);
        out.remove(id);
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

    private static Map<String, PreloadMod> normalizePreloadMods(Map<String, PreloadMod> input) {
        LinkedHashMap<String, PreloadMod> out = new LinkedHashMap<>();
        if (input != null) {
            for (Map.Entry<String, PreloadMod> entry : input.entrySet()) {
                PreloadMod mod = entry.getValue();
                if (!Utils.isBlank(entry.getKey())
                        && mod != null
                        && !Utils.isBlank(mod.infPath())
                        && !Utils.isBlank(mod.jarPath())) {
                    out.put(entry.getKey(), mod);
                }
            }
        }
        return out;
    }
}
