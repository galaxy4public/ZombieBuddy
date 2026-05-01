package me.zed_0xff.zombie_buddy;

import static me.zed_0xff.zombie_buddy.SteamWorkshop.SteamID64;
import static me.zed_0xff.zombie_buddy.SteamWorkshop.WorkshopItemID;
import static me.zed_0xff.zombie_buddy.ModFlags.*;

import java.io.File;
import java.lang.ClassLoader;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.lwjgl.glfw.GLFW;
import org.lwjglx.opengl.Display;
import zombie.GameWindow;
import zombie.gameStates.ChooseGameInfo;

import me.zed_0xff.zombie_buddy.frontend.ModApprovalFrontend;
import me.zed_0xff.zombie_buddy.frontend.ModApprovalFrontends;

public class Loader {
    // Package-private: only ZombieBuddy's own classes may touch the instrumentation handle.
    // External mods (different package) cannot access this field directly, which prevents
    // an approved mod from gaining unrestricted bytecode manipulation of the entire JVM.
    static Instrumentation g_instrumentation;
    static int g_verbosity = 0;
    static boolean g_forceApprovalDialog = false;

    // called by Patch_GameWindow.Patch_DoLoadingText
    public static boolean g_hasDoLoadingText = false;

    /**
     * When {@code true} (default), a missing {@code .zbs} next to the JAR is allowed as &quot;unsigned&quot;
     * (unless {@link #g_jarPolicy} is {@code allow-all}, which skips ZBS checks entirely).
     * When {@code false}, missing {@code .zbs} is treated like a failed signature for prompt UI and load gating.
     * Invalid signatures (present {@code .zbs} but verification fails) are always blocked when ZBS is enforced.
     */
    static boolean g_allowUnsignedMods = true;

    private static final String POLICY_PROMPT       = "prompt";
    private static final String POLICY_DENY_NEW     = "deny-new";
    private static final String POLICY_ALLOW_ALL    = "allow-all";
    private static final Set<String> VALID_POLICIES = Set.of(POLICY_PROMPT, POLICY_DENY_NEW, POLICY_ALLOW_ALL);

    // Write-once policy: must be set during Agent.premain, before any other Java
    // mod is on the classpath. Subsequent set attempts are logged and ignored,
    // so a later-loading Java mod cannot call Loader.setPolicy("allow-all").
    // (Reflection/bytecode-redefinition can still bypass this — same threat
    // model as the approvals file; see ~/.zombie_buddy/ comment.)
    private static volatile String g_jarPolicy = POLICY_PROMPT;
    private static volatile boolean g_jarPolicyLocked = false;

    public static synchronized void setPolicy(String value) {
        if (g_jarPolicyLocked) {
            Logger.warn("Ignoring attempt to change policy (already locked at '" + g_jarPolicy + "')");
            return;
        }
        String v = value == null ? "" : value.trim().toLowerCase();
        if (!VALID_POLICIES.contains(v)) {
            Logger.warn("Invalid policy '" + value + "', keeping '" + g_jarPolicy + "'. Valid: " + VALID_POLICIES);
            return;
        }
        g_jarPolicy = v;
        g_jarPolicyLocked = true;
        Logger.info("Policy set to '" + g_jarPolicy + "' (locked)");
    }

    public static String getPolicy() {
        return g_jarPolicy;
    }

    /** ZBS verification applies for every policy except {@code allow-all}. */
    private static boolean zbsSignatureChecksEnabled() {
        return !POLICY_ALLOW_ALL.equals(g_jarPolicy);
    }

    private record ZBSContext(
        Map<WorkshopItemID, SteamWorkshop.ItemDetails> workshopDetailsById,
        Map<SteamID64, KnownAuthors.AuthorEntry> knownAuthorsBySteamId
    ) {}

    private static ZBSContext loadZBSContext(Iterable<WorkshopItemID> workshopIds) {
        Set<WorkshopItemID> ids = new HashSet<>();
        for (WorkshopItemID id : workshopIds) {
            if (id != null) ids.add(id);
        }
        return new ZBSContext(SteamWorkshop.fetchItemDetails(ids), KnownAuthors.loadAuthors());
    }

    private static ZBSVerifier.CheckResult zbsCheck(
        File jarFile, String hash, WorkshopItemID workshopItemId, ZBSContext ctx
    ) {
        if (!zbsSignatureChecksEnabled() || jarFile == null || hash == null) {
            return ZBSVerifier.CheckResult.DISABLED;
        }
        return ZBSVerifier.check(jarFile, hash, workshopItemId, workshopItemId != null,
            g_allowUnsignedMods, ctx.workshopDetailsById(), ctx.knownAuthorsBySteamId());
    }

    private static boolean zbsBlocked(ZBSVerifier.CheckResult r) {
        return r != ZBSVerifier.CheckResult.DISABLED && !r.flags().has(MF_VALID);
    }

    static Set<String> g_known_classes = new HashSet<>();
    static Set<File> g_known_jars = new HashSet<>();

    // Persisted entries loaded from disk - the source of truth for saving
    private static List<ModApprovalsStore.ModEntry> g_storedEntries = new ArrayList<>();
    private static boolean g_modApprovalsDirty;
    private static final JarDecisionTable g_sessionJarDecisions = new JarDecisionTable();
    private static Config g_config = new Config();
    private static boolean g_configDirty;

    private static final Object g_approvalFrontendLock = new Object();
    private static volatile ModApprovalFrontend g_approvalFrontend;
    /** Set in {@link #configureApprovalFrontend}; resolved lazily — do not touch LWJGL during agent {@code premain}. */
    private static String g_approvalFrontendConfig = ModApprovalFrontends.ARG_AUTO;

    /** Called from {@link Agent#premain} with {@code frontend=...} (default {@code auto}). */
    public static void configureApprovalFrontend(String value) {
        String v = value == null ? "" : value.trim();
        synchronized (g_approvalFrontendLock) {
            g_approvalFrontendConfig = v.isEmpty() ? ModApprovalFrontends.ARG_AUTO : v;
            g_approvalFrontend = null;
        }
        Logger.info("Java mod approval frontend: " + g_approvalFrontendConfig);
    }

    private static ModApprovalFrontend approvalFrontend() {
        ModApprovalFrontend f = g_approvalFrontend;
        if (f != null) {
            return f;
        }
        synchronized (g_approvalFrontendLock) {
            f = g_approvalFrontend;
            if (f != null) {
                return f;
            }
            g_approvalFrontend = ModApprovalFrontends.resolve(g_approvalFrontendConfig);
            return g_approvalFrontend;
        }
    }

    private static Boolean lookupJarDecision(JarDecisionTable disk, String sha256) {
        if (sha256 == null) return null;
        return disk.get(sha256);
    }

    private static void applySessionDecisions(JarDecisionTable approvals) {
        for (String hash : g_sessionJarDecisions.hashes()) {
            approvals.put(hash, g_sessionJarDecisions.get(hash));
        }
    }

    /**
     * Per-modId load/decision snapshot captured at loadMods() time so Lua
     * (and other callers) can display "loaded / blocked" next to each mod in
     * the UI. Keyed by modId; for multi-dir B42 mods the version-dir entry
     * (which typically carries the JAR) overwrites the common-dir one.
     */
    static final class JavaModLoadState {
        final ModFlags flags;
        final String reason;      // "loaded" or a short skip reason
        final String sha256;      // JAR sha256, null if no JAR
        final Boolean decision;   // true = allow, false = deny, null = undecided

        JavaModLoadState(ModFlags flags, String reason, String sha256, Boolean decision) {
            this.flags = flags;
            this.reason = reason;
            this.sha256 = sha256;
            this.decision = decision;
        }
    }

    private static final Map<String, JavaModLoadState> g_jarLoadStatus = new ConcurrentHashMap<>();

    static JavaModLoadState getJarLoadState(String modId) {
        return modId == null ? null : g_jarLoadStatus.get(modId);
    }

    static ArrayList<String> getActiveJavaMods() {
        ArrayList<String> out = new ArrayList<>();
        for (Map.Entry<String, JavaModLoadState> entry : g_jarLoadStatus.entrySet()) {
            JavaModLoadState state = entry.getValue();
            if (state != null && state.flags.has(MF_ACTIVE)) {
                out.add(entry.getKey());
            }
        }
        return out;
    }

    private static JarDecisionTable buildJarDecisionTable(List<ModApprovalsStore.ModEntry> mods) {
        JarDecisionTable table = new JarDecisionTable();
        for (ModApprovalsStore.ModEntry entry : mods) {
            if (entry.jarHash != null) {
                table.put(entry.jarHash, entry.decision);
            }
        }
        return table;
    }

    private static boolean isDecisionStored(String jarHash, Boolean decision) {
        if (jarHash == null || decision == null) return false;
        for (ModApprovalsStore.ModEntry entry : g_storedEntries) {
            if (jarHash.equals(entry.jarHash) && entry.decision == decision) {
                return true;
            }
        }
        return false;
    }

    private static List<ModApprovalsStore.ModEntry> copyStoredEntries(List<ModApprovalsStore.ModEntry> entries) {
        List<ModApprovalsStore.ModEntry> copy = new ArrayList<>();
        if (entries == null) return copy;
        for (ModApprovalsStore.ModEntry entry : entries) {
            if (entry == null) continue;
            copy.add(new ModApprovalsStore.ModEntry(
                entry.id,
                entry.workshopId,
                entry.jarHash,
                entry.decision,
                entry.time,
                entry.authorId
            ));
        }
        return copy;
    }

    /**
     * Add or update a decision in g_storedEntries (for persistence).
     * Updates existing entry with matching jarHash, or adds a new one.
     */
    private static void storeDecision(String jarHash, boolean allow, String modId, 
            WorkshopItemID workshopId, SteamID64 authorId) {
        if (jarHash == null) return;
        // Update existing entry if found
        for (ModApprovalsStore.ModEntry e : g_storedEntries) {
            if (jarHash.equals(e.jarHash)) {
                e.decision = allow;
                if (!Utils.isBlank(modId)) e.id = modId;
                if (workshopId != null) e.workshopId = workshopId;
                if (authorId != null) e.authorId = authorId;
                g_modApprovalsDirty = true;
                return;
            }
        }
        // Add new entry
        g_storedEntries.add(new ModApprovalsStore.ModEntry(
            modId != null ? modId : "",
            workshopId,
            jarHash,
            allow,
            null,
            authorId
        ));
        g_modApprovalsDirty = true;
    }

    private static boolean isJarAllowedByPolicy(String modId, JavaModInfo jModInfo, JarDecisionTable disk, String hash) {
        File jarFile = jModInfo != null ? jModInfo.getJarFileAsFile() : null;
        if (hash == null) return false;

        String policy = g_jarPolicy;
        Boolean decision = lookupJarDecision(disk, hash);
        if (Boolean.TRUE.equals(decision)) return true;
        if (Boolean.FALSE.equals(decision)) {
            Logger.warn("Blocking Java mod by stored denial: " + modId + " (" + jarFile + ")");
            return false;
        }

        if (POLICY_ALLOW_ALL.equals(policy)) {
            disk.put(hash, Boolean.TRUE);
            storeDecision(hash, true, modId, jModInfo != null ? jModInfo.getWorkshopItemID() : null, null);
            return true;
        }
        if (POLICY_DENY_NEW.equals(policy)) {
            Logger.warn("Blocking Java mod by policy=deny-new: " + modId + " (" + jarFile + ")");
            return false;
        }

        // policy=prompt: decisions come from approvePendingMods() in loadMods(); should not reach here.
        Logger.warn("No approval decision for " + modId + " (hash " + hash + ") — denying.");
        return false;
    }

    public static void applyBatchApprovalLines(List<JarBatchApprovalProtocol.Entry> entries, JarDecisionTable disk) {
        if (entries == null) return;
        for (JarBatchApprovalProtocol.Entry entry : entries) {
            if (entry == null || entry.decision == null || Utils.isBlank(entry.sha256)) continue;
            boolean allow = entry.decision;
            disk.put(entry.sha256, allow);
            g_sessionJarDecisions.put(entry.sha256, allow);
            if (entry.flags.has(MF_PERSIST)) {
                storeDecision(entry.sha256, allow, entry.modId, entry.workshopItemId,
                    entry.zbs.valid() ? entry.zbs.authorSteamId() : null);
            }
            if (entry.flags.has(MF_PERSIST) && entry.zbs.valid() && entry.zbs.authorSteamId() != null) {
                if (entry.flags.has(MF_TRUST_AUTHOR)) {
                    storeTrustedAuthor(entry.zbs.authorSteamId());
                } else if (entry.steamBan == null && isAuthorTrusted(entry.zbs.authorSteamId())) {
                    removeTrustedAuthor(entry.zbs.authorSteamId());
                }
            }
            if (entry.flags.has(MF_PRELOAD) && !Utils.isBlank(entry.javaPkgName) && !Utils.isBlank(entry.jarAbsolutePath)) {
                if (allow) {
                    storePreloadMod(entry.javaPkgName, entry.jarAbsolutePath);
                } else {
                    removePreloadMod(entry.javaPkgName);
                }
            }
        }
    }

    private static boolean isAuthorTrusted(SteamID64 sid) {
        return g_config.trustsAuthor(sid);
    }

    private static void storeTrustedAuthor(SteamID64 sid) {
        if (sid == null) {
            return;
        }
        Config nextConfig = g_config.withTrustedAuthor(sid);
        if (!nextConfig.equals(g_config)) {
            g_config = nextConfig;
            g_configDirty = true;
        }
    }

    private static void removeTrustedAuthor(SteamID64 sid) {
        if (sid == null) {
            return;
        }
        Config nextConfig = g_config.withoutTrustedAuthor(sid);
        if (!nextConfig.equals(g_config)) {
            g_config = nextConfig;
            g_configDirty = true;
        }
    }

    private static void storePreloadMod(String javaPkgName, String jarPath) {
        Config nextConfig = g_config.withPreloadMod(javaPkgName, jarPath);
        if (!nextConfig.equals(g_config)) {
            g_config = nextConfig;
            g_configDirty = true;
        }
    }

    private static void removePreloadMod(String javaPkgName) {
        Config nextConfig = g_config.withoutPreloadMod(javaPkgName);
        if (!nextConfig.equals(g_config)) {
            g_config = nextConfig;
            g_configDirty = true;
        }
    }

    static void preloadMods() {
        Config config = Config.load();
        if (config.preloadMods().isEmpty()) {
            return;
        }

        // First pass: validate and collect entries
        record PreloadEntry(String javaPkgName, String jarPath, File jarFile, String hash, WorkshopItemID workshopItemId) {}
        List<PreloadEntry> entries = new ArrayList<>();
        for (Map.Entry<String, String> e : config.preloadMods().entrySet()) {
            String javaPkgName = e.getKey();
            String jarPath = e.getValue();
            File jarFile = new File(jarPath);
            if (!jarFile.isFile()) {
                Logger.warn("Preload pkg '" + javaPkgName + "': JAR not found at " + jarPath);
                continue;
            }
            if (g_known_jars.contains(jarFile)) {
                Logger.info("Preload pkg '" + javaPkgName + "' already on classpath, skipping.");
                continue;
            }
            if (!validatePackageInJar(jarFile, javaPkgName)) {
                Logger.warn("Preload pkg '" + javaPkgName + "': package not found in JAR " + jarPath + "; skipping.");
                continue;
            }
            entries.add(new PreloadEntry(javaPkgName, jarPath, jarFile, Utils.sha256Hex(jarFile),
                JavaModInfo.workshopItemIdFromPath(jarPath)));
        }
        if (entries.isEmpty()) return;

        ZBSContext zbsCtx = loadZBSContext(entries.stream().map(entry -> entry.workshopItemId()).toList());

        // Second pass: ZBS check and load
        for (PreloadEntry entry : entries) {
            ZBSVerifier.CheckResult zbsResult = zbsCheck(entry.jarFile(), entry.hash(), entry.workshopItemId(), zbsCtx);
            if (zbsBlocked(zbsResult)) {
                Logger.warn("Preload pkg '" + entry.javaPkgName() + "': ZBS check failed (" + zbsResult.blockReason() + "); skipping.");
                continue;
            }
            loadPatchesFromJar(entry.jarPath(), entry.javaPkgName());
        }
    }

    /**
     * Adds {@code jarFile} to the system classpath if not already present.
     * Validates that {@code packageName} exists in the JAR. If {@code approvedHash}
     * is non-null, re-hashes as a TOCTOU guard against file swaps after approval.
     *
     * @return true if the JAR was added; false on any failure or already-loaded.
     */
    private static boolean addJarToClasspath(File jarFile, String packageName, String approvedHash) {
        if (g_known_jars.contains(jarFile)) {
            Logger.info("" + jarFile + " already on classpath, skipping.");
            return false;
        }
        if (!validatePackageInJar(jarFile, packageName)) {
            Logger.error("JAR does not contain package " + packageName + ": " + jarFile);
            return false;
        }
        if (approvedHash != null) {
            String currentHash = Utils.sha256Hex(jarFile);
            if (!approvedHash.equals(currentHash)) {
                Logger.error("SECURITY: JAR hash changed between approval and load for "
                    + jarFile + " — expected " + approvedHash + ", got " + currentHash
                    + ". Aborting load.");
                return false;
            }
        }
        try {
            g_instrumentation.appendToSystemClassLoaderSearch(new JarFile(jarFile));
            g_known_jars.add(jarFile);
            Logger.info("added to classpath: " + jarFile);
            return true;
        } catch (Exception e) {
            Logger.error("Error adding JAR to classpath " + jarFile + ": " + e);
            return false;
        }
    }

    private static void untrustAuthorForBannedMod(
        String modId,
        File jarFile,
        String hash,
        WorkshopItemID workshopItemId,
        boolean steamBanned,
        Map<WorkshopItemID, SteamWorkshop.ItemDetails> workshopDetailsById
    ) {
        if (!steamBanned || jarFile == null || hash == null) {
            return;
        }
        SteamID64 uploaderID = SteamWorkshop.getUploaderID(workshopItemId, workshopDetailsById);
        if (uploaderID != null && isAuthorTrusted(uploaderID)) {
            removeTrustedAuthor(uploaderID);
            Logger.warn("Removed trusted-author flag for " + uploaderID + " because banned mod was detected: " + modId);
        }
    }

    private static String authorDisplayName(
        SteamID64 sid,
        Map<SteamID64, KnownAuthors.AuthorEntry> knownAuthors
    ) {
        if (sid == null) {
            return "";
        }
        KnownAuthors.AuthorEntry known = knownAuthors != null ? knownAuthors.get(sid) : null;
        return known != null && !Utils.isBlank(known.name) ? known.name : sid.toString();
    }

    public static void loadMods(ArrayList<String> mods) {
        ArrayList<JavaModInfo> jModInfos = new ArrayList<>();
        ArrayList<String> jModIds = new ArrayList<>();

        for (String mod_id : mods) {
            var mod = ChooseGameInfo.getAvailableModDetails(mod_id);
            if (mod == null) continue;

            if (Accessor.hasPublicMethod(mod, "getVersionDir") && Accessor.hasPublicMethod(mod, "getCommonDir")) {
                // B42+
                // follow lua engine logic, load common dir first, then version dir
                // so version dir could override common dir
                JavaModInfo jModInfoCommon = JavaModInfo.parse(mod.getCommonDir());
                JavaModInfo jModInfoVersion = JavaModInfo.parse(mod.getVersionDir());

                if (jModInfoCommon != null) {
                    jModInfos.add(jModInfoCommon);
                    jModIds.add(mod_id);
                    if (jModInfoVersion == null) {
                        // when mod.info is in common dir, but JAR is in version dir
                        jModInfoVersion = JavaModInfo.parseMerged(mod.getCommonDir(), mod.getVersionDir());
                    }
                }
                if (jModInfoVersion != null) {
                    jModInfos.add(jModInfoVersion);
                    jModIds.add(mod_id);
                }
            } else {
                // B41
                JavaModInfo jModInfo = JavaModInfo.parse(mod.getDir());
                if (jModInfo != null) {
                    jModInfos.add(jModInfo);
                    jModIds.add(mod_id);
                }
            }
        }

        Logger.info("java mod list to load:");
        printModList(jModInfos);

        // Find the last occurrence index for each package name
        Map<String, Integer> lastPkgNameIndex = new HashMap<>();
        for (int i = 0; i < jModInfos.size(); i++) {
            JavaModInfo jModInfo = jModInfos.get(i);
            lastPkgNameIndex.put(jModInfo.javaPkgName(), i);
        }

        // Process the list to determine which mods should be skipped
        String myPackageName = Loader.class.getPackage().getName();
        ArrayList<Boolean> shouldSkipList = new ArrayList<>();
        ArrayList<String> skipReasons = new ArrayList<>();
        ModApprovalsStore.FileData fileData = ModApprovalsStore.load();
        g_config = Config.load();
        g_configDirty = false;
        // Store entries for persistence and build lookup table
        g_storedEntries = new ArrayList<>(fileData.mods);
        g_modApprovalsDirty = false;
        List<ModApprovalsStore.ModEntry> storedEntriesBefore = copyStoredEntries(g_storedEntries);
        JarDecisionTable approvals = buildJarDecisionTable(g_storedEntries);
        if (!g_forceApprovalDialog) {
            checkShiftKey();
        }
        if (!g_forceApprovalDialog) {
            applySessionDecisions(approvals);
        }
        if (g_forceApprovalDialog) {
            Logger.info("Shift held during game load; forcing Java mod approval dialog.");
        }
        // Structural-only skip flags (must match the policy loop below) — used to batch all PROMPT dialogs.
        ArrayList<Boolean> structuralOnlySkip = new ArrayList<>();
        for (int i = 0; i < jModInfos.size(); i++) {
            JavaModInfo jModInfo = jModInfos.get(i);
            boolean stSkip = false;
            if (jModInfo.javaPkgName().equals(myPackageName)) {
                stSkip = true;
            }
            Integer lastIdx = lastPkgNameIndex.get(jModInfo.javaPkgName());
            if (lastIdx != null && lastIdx > i) {
                stSkip = true;
            }
            structuralOnlySkip.add(stSkip);
        }
        ZBSContext zbsCtx = loadZBSContext(jModInfos.stream().map(JavaModInfo::getWorkshopItemID).toList());
        Map<WorkshopItemID, SteamWorkshop.ItemDetails> workshopDetailsById = zbsCtx.workshopDetailsById();
        Map<SteamID64, KnownAuthors.AuthorEntry> knownAuthorsBySteamId = zbsCtx.knownAuthorsBySteamId();

        // Pre-compute per-mod context to avoid duplicate work in prompt and load loops
        record ModCtx(String modId, File jarFile, String hash, WorkshopItemID workshopItemId,
                      SteamWorkshop.ItemDetails workshopDetails, SteamWorkshop.BanInfo banInfo, boolean steamBanned,
                      ZBSVerifier.CheckResult zbsResult) {}
        List<ModCtx> modContexts = new ArrayList<>();
        for (int i = 0; i < jModInfos.size(); i++) {
            JavaModInfo jModInfo = jModInfos.get(i);
            String modId = i < jModIds.size() ? jModIds.get(i) : jModInfo.javaPkgName();
            File jarFile = jModInfo.getJarFileAsFile();
            String hash = Utils.sha256Hex(jarFile);
            WorkshopItemID workshopItemId = jModInfo.getWorkshopItemID();
            SteamWorkshop.ItemDetails workshopDetails = workshopItemId != null ? workshopDetailsById.get(workshopItemId) : null;
            SteamWorkshop.BanInfo banInfo = workshopItemId == null
                ? new SteamWorkshop.BanInfo(null, "Workshop id not found in mod path.")
                : (workshopDetails != null ? workshopDetails.ban() : null);
            boolean steamBanned = banInfo != null && Boolean.TRUE.equals(banInfo.status());
            modContexts.add(new ModCtx(modId, jarFile, hash, workshopItemId, workshopDetails, banInfo, steamBanned,
                zbsCheck(jarFile, hash, workshopItemId, zbsCtx)));
        }
        for (ModCtx ctx : modContexts) {
            untrustAuthorForBannedMod(
                ctx.modId,
                ctx.jarFile,
                ctx.hash,
                ctx.workshopItemId,
                ctx.steamBanned,
                workshopDetailsById
            );
        }

        if (POLICY_PROMPT.equals(g_jarPolicy)) {
            // Late Shift re-check: on first boot the display window may not exist when
            // checkShiftKey() runs above (before workshop/ZBS work). By the time we reach
            // here the window is more likely to be ready, so try once more.
            if (!g_forceApprovalDialog) {
                checkShiftKey();
                if (g_forceApprovalDialog) {
                    Logger.info("Shift held during game load (late detection); forcing Java mod approval dialog.");
                }
            }
            ArrayList<JarBatchApprovalProtocol.Entry> batchEntries = new ArrayList<>();
            for (int i = 0; i < jModInfos.size(); i++) {
                if (structuralOnlySkip.get(i)) continue;
                ModCtx ctx = modContexts.get(i);
                if (ctx.hash == null) continue;

                JavaModInfo jModInfo = jModInfos.get(i);
                Date date = (ctx.jarFile != null && ctx.jarFile.exists())
                    ? new Date(ctx.jarFile.lastModified())
                    : null;
                String modDisplay = jModInfo.displayName();
                if (modDisplay == null || modDisplay.trim().isEmpty()) {
                    modDisplay = ctx.modId != null ? ctx.modId : "";
                }
                JarBatchApprovalProtocol.Entry.SteamBan steamBan = ctx.steamBanned
                    ? new JarBatchApprovalProtocol.Entry.SteamBan(ctx.banInfo != null ? ctx.banInfo.reason() : "")
                    : null;
                SteamID64 authorID = ctx.zbsResult.sid();
                ModFlags flags = ctx.zbsResult.flags();
                JarBatchApprovalProtocol.Entry.ZBSignature zbs = new JarBatchApprovalProtocol.Entry.ZBSignature(
                    flags.hasAll(MF_SIGNED, MF_VALID),
                    authorID,
                    flags.hasAll(MF_SIGNED, MF_VALID) ? authorDisplayName(authorID, knownAuthorsBySteamId) : ctx.zbsResult.notice()
                );
                Boolean decision = lookupJarDecision(approvals, ctx.hash);
                if (decision == null) {
                    // When force-dialog skips applySessionDecisions, still surface session decisions
                    // so the user sees their earlier choices pre-filled in the dialog.
                    decision = g_sessionJarDecisions.get(ctx.hash);
                }
                if (!g_forceApprovalDialog && !ctx.steamBanned && zbs.valid() && isAuthorTrusted(authorID)) {
                    // Auto-approve for this session only — trusted-author approvals are not persisted
                    // because the trust comes from the author record, not the specific JAR hash.
                    approvals.put(ctx.hash, Boolean.TRUE);
                    continue;
                }
                if (!g_forceApprovalDialog && !ctx.steamBanned) {
                    if (decision != null) continue;
                }
                ModFlags entryFlags = new ModFlags(isDecisionStored(ctx.hash, decision) ? MF_PERSIST : MF_NONE);
                if (zbs.valid() && authorID != null && isAuthorTrusted(authorID) && !ctx.steamBanned) {
                    entryFlags = entryFlags.with(MF_TRUST_AUTHOR);
                }
                if (jModInfo.javaPreload() && zbs.valid() && JavaModInfo.hasManifestPreload(ctx.jarFile)) {
                    entryFlags = entryFlags.with(MF_PRELOAD);
                }
                batchEntries.add(new JarBatchApprovalProtocol.Entry(
                    ctx.modId,
                    ctx.workshopItemId,
                    ctx.jarFile.getAbsolutePath(),
                    ctx.hash,
                    date,
                    decision,
                    entryFlags,
                    modDisplay,
                    zbs,
                    steamBan,
                    jModInfo.javaPkgName()
                ));
            }
            if (!batchEntries.isEmpty()) {
                if (g_hasDoLoadingText) {
                    GameWindow.DoLoadingText("Waiting for Java mods approval…");
                }
                List<JarBatchApprovalProtocol.Entry> decided = batchEntries;
                try {
                    decided = approvalFrontend().approvePendingMods(batchEntries);
                } finally {
                    if (g_hasDoLoadingText) {
                        GameWindow.DoLoadingText("Loading Mods");
                    }
                }
                applyBatchApprovalLines(decided, approvals);
                g_forceApprovalDialog = false; // reset after one batch, can be re-triggered by holding Shift on next load
            }
        }

        for (int i = 0; i < jModInfos.size(); i++) {
            JavaModInfo jModInfo = jModInfos.get(i);
            ModCtx ctx = modContexts.get(i);
            boolean shouldSkip = false;
            String skipReason = "";
            
            // Skip ZombieBuddy itself - it's loaded as a Java agent, not through normal mod loading
            if (jModInfo.javaPkgName().equals(myPackageName)) {
                shouldSkip = true;
                skipReason = " (loaded as Java agent, skipping normal mod loading)" + SelfUpdater.getExclusionReasonSuffix(ctx.jarFile);
            }
            
            // Check if this mod's package name appears in a later mod
            Integer lastIndex = lastPkgNameIndex.get(jModInfo.javaPkgName());
            if (lastIndex != null && lastIndex > i) {
                shouldSkip = true;
                skipReason = " (package " + jModInfo.javaPkgName() + " is overridden by later mod)";
            }

            if (!shouldSkip && ctx.steamBanned) {
                shouldSkip = true;
                skipReason = " (Steam Workshop mod is banned" + (!Utils.isBlank(ctx.banInfo.reason()) ? ": " + ctx.banInfo.reason() : "") + ", modId=" + ctx.modId + ")";
            }

            ModFlags flags = ctx.zbsResult == ZBSVerifier.CheckResult.DISABLED ? ModFlags.EMPTY : ctx.zbsResult.flags();
            if (!shouldSkip && zbsBlocked(ctx.zbsResult)) {
                shouldSkip = true;
                skipReason = " (" + ctx.zbsResult.blockReason() + ", modId=" + ctx.modId + ")";
            }

            // Enforce Java JAR policy for new/changed binaries.
            if (!shouldSkip && !isJarAllowedByPolicy(ctx.modId, jModInfo, approvals, ctx.hash)) {
                shouldSkip = true;
                skipReason = " (blocked by policy=" + g_jarPolicy + ", modId=" + ctx.modId + ")";
            }
            
            shouldSkipList.add(shouldSkip);
            skipReasons.add(skipReason);

            // Snapshot for ZombieBuddy.getJavaModStatus(). Skipped when no JAR
            // (e.g. metadata-only mod.info entries in B42 common dirs).
            if (ctx.jarFile != null) {
                Boolean decision = null;
                if (ctx.hash != null) {
                    decision = approvals.get(ctx.hash);
                    if (isDecisionStored(ctx.hash, decision)) {
                        flags = flags.with(MF_PERSIST);
                    }
                }
                if (!shouldSkip) {
                    flags = flags.with(MF_ACTIVE);
                } else {
                    // A loaded JAR cannot be unloaded — preserve MF_ACTIVE if it was previously set.
                    JavaModLoadState prev = g_jarLoadStatus.get(ctx.modId);
                    if (prev != null && prev.flags.has(MF_ACTIVE)) {
                        flags = flags.with(MF_ACTIVE);
                    }
                }
                g_jarLoadStatus.put(ctx.modId, new JavaModLoadState(
                    flags,
                    shouldSkip ? skipReason.trim() : "loaded",
                    ctx.hash,
                    decision
                ));
            }
        }

        // Save if anything changed
        if (g_modApprovalsDirty
            && (!storedEntriesBefore.equals(g_storedEntries)
                || g_storedEntries.size() != storedEntriesBefore.size())) {
            ModApprovalsStore.FileData dataToSave = new ModApprovalsStore.FileData();
            dataToSave.mods = new ArrayList<>(g_storedEntries);
            ModApprovalsStore.save(dataToSave);
        }
        if (g_configDirty) {
            Config.save(g_config);
        }
        
        if (!shouldSkipList.isEmpty()) {
            // Print excluded mods first
            for (int i = 0; i < jModInfos.size(); i++) {
                if (shouldSkipList.get(i)) {
                    JavaModInfo jModInfo = jModInfos.get(i);
                    String reason = i < skipReasons.size() ? skipReasons.get(i) : "";
                    Logger.info("Excluded: " + jModInfo.modDir().getAbsolutePath() + reason);
                }
            }
            
            // Build list of mods that will be loaded
            ArrayList<JavaModInfo> modsToLoad = new ArrayList<>();
            for (int i = 0; i < jModInfos.size(); i++) {
                if (!shouldSkipList.get(i)) {
                    modsToLoad.add(jModInfos.get(i));
                }
            }
            Logger.info("java mod list after processing:");
            printModList(modsToLoad);
        }

        // Load only the mods that should be loaded
        for (int i = 0; i < jModInfos.size(); i++) {
            if (!shouldSkipList.get(i)) {
                loadJavaMod(jModInfos.get(i), modContexts.get(i).hash);
            }
        }
    }

    private static void checkShiftKey() {
        try {
            long window = Display.getWindow();
            if (window == 0) {
                return;
            }
            if(GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS) {
                g_forceApprovalDialog = true;
            }
        } catch (Throwable t) {
        }
    }

    static {
        Callbacks.onDisplayCreate.register(Loader::checkShiftKey);
    }

    public static void printModList(ArrayList<JavaModInfo> jModInfos) {
        int longestPathLength = 0;
        for (JavaModInfo jModInfo : jModInfos) {
            if (jModInfo.modDir().getAbsolutePath().length() > longestPathLength) {
                longestPathLength = jModInfo.modDir().getAbsolutePath().length();
            }
        }

        String formatString = "    %-" + longestPathLength + "s %s";
        for (JavaModInfo jModInfo : jModInfos) {
            Logger.info(String.format(formatString, jModInfo.modDir().getAbsolutePath(), jModInfo.javaPkgName()));
        }
    }

    public static void ApplyPatchesFromPackage(String packageName, ClassLoader modLoader) {
        // Load and invoke optional Main class
        String mainClassName = packageName + ".Main";
        if (g_known_classes.contains(mainClassName)) {
            Logger.info("Java class " + mainClassName + " already loaded, skipping.");
        } else {
            g_known_classes.add(mainClassName);

            Logger.info("trying to load " + mainClassName);
            Class<?> cls = Accessor.findClass(mainClassName);
            if (cls != null) {
                try_call_main(cls);
            }
        }

        PatchEngine.applyPatches(packageName, modLoader);
    }

    public static List<Class<?>> CollectPatches(String packageName, ClassLoader modLoader) {
        return PatchEngine.collectPatches(packageName, modLoader);
    }

    // Package-private: approval checks live in loadMods(); keeping this non-public
    // prevents an external (approved) mod from calling it directly with an arbitrary JAR,
    // bypassing ZBS verification and the decision table entirely.
    static void loadJavaMod(JavaModInfo modInfo, String approvedHash) {
        final String separator = "-------------------------------------------";
        Logger.info(separator + " loading Java mod: " + modInfo.modDir());

        try {
            File jarFile = modInfo.getJarFileAsFile();
            if (jarFile == null) {
                Logger.error("Error! No JAR file specified for mod: " + modInfo.modDir());
                return;
            }
            if (!jarFile.exists()) {
                Logger.error("classpath not found: " + jarFile);
                return;
            }
            if (!addJarToClasspath(jarFile, modInfo.javaPkgName(), approvedHash)) {
                return;
            }

            ApplyPatchesFromPackage(modInfo.javaPkgName(), null);
        } finally {
            // called for all return paths to ensure consistent log formatting
            Logger.info(separator);
        }
    }

    static void loadPatchesFromJar(String jarPath, String packageName) {
        File jarFile = new File(jarPath);
        if (!jarFile.exists()) {
            Logger.error("patches_jar file not found: " + jarPath);
            return;
        }
        if (!addJarToClasspath(jarFile, packageName, null)) {
            return;
        }
        ApplyPatchesFromPackage(packageName, null);
    }

    static void try_call_main(Class<?> cls) {
        Method main = null;
        try {
            main = cls.getMethod("main", String[].class);
        } catch (java.lang.NoSuchMethodException e) {
            return;
        } catch (Exception e) {
            Logger.error("" + cls + ": error getting main(): " + e);
            return;
        }

        // main cannot be null here if getMethod() succeeded
        try {
            String[] args = {}; // no arguments for now
            main.invoke(null, (Object) args);
            Logger.info("" + cls + ": main() invoked successfully");
        } catch (Exception e) {
            Logger.error("" + cls + ": error invoking main(): " + e);
        }
    }

    private static boolean validatePackageInJar(File jarFile, String packageName) {
        if (jarFile == null || Utils.isBlank(packageName)) {
            return false;
        }
        try {
            String packagePath = packageName.replace('.', '/');
            try (JarFile jf = new JarFile(jarFile)) {
                var entries = jf.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    String entryName = entry.getName();
                    if (entryName.startsWith(packagePath + "/") || 
                        entryName.equals(packagePath) || 
                        entryName.equals(packagePath + "/")) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            Logger.error("Error validating package in JAR " + jarFile + ": " + e);
            return false;
        }
    }
}
