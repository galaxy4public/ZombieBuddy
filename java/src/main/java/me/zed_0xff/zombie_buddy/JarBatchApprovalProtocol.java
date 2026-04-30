package me.zed_0xff.zombie_buddy;

import static me.zed_0xff.zombie_buddy.SteamWorkshop.SteamID64;
import static me.zed_0xff.zombie_buddy.SteamWorkshop.WorkshopItemID;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

/**
 * JSON file protocol between {@link Loader} (game process) and
 * {@link me.zed_0xff.zombie_buddy.frontend.SwingApprovalMain} (non-headless child JVM with Swing UI).
 */
public final class JarBatchApprovalProtocol {

    static final String HDR_REQ  = "ZB_BATCH_V8";
    static final String HDR_RESP = "ZB_BATCH_V8_OUT";
    private static final Gson JSON = ZBGson.PRETTY;

    public static final class Entry {
        /** Mod id from mod.info {@code id=}. */
        @SerializedName("modId")
        public final String modId;
        /** Nullable workshop item id for this row. */
        @SerializedName("workshopItemId")
        public final WorkshopItemID workshopItemId;
        @SerializedName("jarAbsolutePath")
        public final String jarAbsolutePath;
        @SerializedName("sha256")
        public final String sha256;
        /** Last modified time from the file system. */
        @SerializedName("date")
        public final Date date;
        /** Existing allow/deny decision for this exact JAR hash, if any. */
        @SerializedName("decision")
        public Boolean decision;
        /** Display name from mod.info {@code name=}; may be empty (UI falls back to {@link #modId}). */
        @SerializedName("modDisplayName")
        public final String modDisplayName;
        /** ZBS signature status and signer metadata. */
        @SerializedName("zbs")
        public final ZBSignature zbs;
        /** Nullable; null means no known Steam ban. */
        @SerializedName("steamBan")
        public final SteamBan steamBan;
        /** Whether this mod requests premain-time loading on next launch. */
        @SerializedName("bEarlyLoad")
        public final boolean bEarlyLoad;

        public Entry(
            String modId,
            WorkshopItemID workshopItemId,
            String jarAbsolutePath,
            String sha256,
            Date date,
            Boolean decision,
            String modDisplayName,
            ZBSignature zbs,
            SteamBan steamBan,
            boolean bEarlyLoad
        ) {
            this.modId = modId;
            this.workshopItemId = workshopItemId;
            this.jarAbsolutePath = jarAbsolutePath != null ? jarAbsolutePath : "";
            this.sha256 = sha256 != null ? sha256 : "";
            this.date = date;
            this.decision = decision;
            this.modDisplayName = modDisplayName != null ? modDisplayName : "";
            this.zbs = zbs != null ? zbs : ZBSignature.none();
            this.steamBan = steamBan;
            this.bEarlyLoad = bEarlyLoad;
        }

        public record SteamBan(String reason) {
            public SteamBan {
                reason = reason != null ? reason : "";
            }
        }

        public record ZBSignature(
            boolean valid,
            SteamID64 authorSteamId,
            String notice
        ) {
            public ZBSignature {
                notice = notice != null ? notice : "";
            }

            public static ZBSignature none() {
                return new ZBSignature(false, null, "");
            }

            public boolean invalid() {
                return !valid && !Utils.isBlank(notice);
            }

            public boolean unsigned() {
                return !valid && authorSteamId == null && Utils.isBlank(notice);
            }
        }
    }

    public static void writeRequest(Path path, List<Entry> entries) throws IOException {
        List<Entry> safe = entries == null ? Collections.emptyList() : entries;
        try (Writer w = Files.newBufferedWriter(path)) {
            JSON.toJson(new RequestEnvelope(HDR_REQ, safe), w);
        }
    }

    public static List<Entry> readRequest(Path path) throws IOException {
        try (Reader r = Files.newBufferedReader(path)) {
            RequestEnvelope env = JSON.fromJson(r, RequestEnvelope.class);
            if (env == null || !HDR_REQ.equals(env.header)) {
                throw new IOException("Bad request header: " + (env != null ? env.header : null));
            }
            return env.entries != null ? env.entries : Collections.emptyList();
        }
    }

    public static void writeResponse(Path path, List<Entry> entries) throws IOException {
        List<Entry> safe = entries == null ? Collections.emptyList() : entries;
        try (Writer w = Files.newBufferedWriter(path)) {
            JSON.toJson(new ResponseEnvelope(HDR_RESP, safe), w);
        }
    }

    public static List<Entry> readResponse(Path path) throws IOException {
        try (Reader r = Files.newBufferedReader(path)) {
            ResponseEnvelope env = JSON.fromJson(r, ResponseEnvelope.class);
            if (env == null || !HDR_RESP.equals(env.header)) {
                return null;
            }
            return env.entries != null ? env.entries : Collections.emptyList();
        }
    }

    private static final class RequestEnvelope {
        @SerializedName("header")
        public final String header;
        @SerializedName("entries")
        public final List<Entry> entries;

        RequestEnvelope(String header, List<Entry> entries) {
            this.header = header;
            this.entries = entries != null ? entries : new ArrayList<>();
        }
    }

    private static final class ResponseEnvelope {
        @SerializedName("header")
        public final String header;
        @SerializedName("entries")
        public final List<Entry> entries;

        ResponseEnvelope(String header, List<Entry> entries) {
            this.header = header;
            this.entries = entries != null ? entries : new ArrayList<>();
        }
    }
}
