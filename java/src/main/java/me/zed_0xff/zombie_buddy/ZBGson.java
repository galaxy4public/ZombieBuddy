package me.zed_0xff.zombie_buddy;

import static me.zed_0xff.zombie_buddy.SteamWorkshop.SteamID64;
import static me.zed_0xff.zombie_buddy.SteamWorkshop.WorkshopItemID;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.LongFunction;
import java.util.function.ToLongFunction;

/** Shared Gson configuration for on-disk and IPC JSON. */
public final class ZBGson {

    private ZBGson() {}

    private static final TypeAdapter<Path> PATH_ADAPTER = new TypeAdapter<>() {
        @Override
        public void write(JsonWriter out, Path value) throws IOException {
            if (value == null) out.nullValue();
            else out.value(value.toString());
        }

        @Override
        public Path read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) { in.nextNull(); return null; }
            String s = in.nextString();
            return Utils.isBlank(s) ? null : Path.of(s);
        }
    };

    private static final TypeAdapter<ModFlags> MOD_FLAGS_ADAPTER = new TypeAdapter<>() {
        @Override
        public void write(JsonWriter out, ModFlags value) throws IOException {
            if (value == null) {
                out.value(0);
            } else {
                out.value(value.value());
            }
        }

        @Override
        public ModFlags read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return ModFlags.EMPTY;
            }
            return new ModFlags(in.nextInt());
        }
    };

    private static <T> TypeAdapter<T> longAdapter(LongFunction<T> ctor, ToLongFunction<T> getter) {
        return new TypeAdapter<>() {
            @Override public void write(JsonWriter out, T v) throws IOException {
                if (v == null) out.nullValue(); else out.value(getter.applyAsLong(v));
            }
            @Override public T read(JsonReader in) throws IOException {
                if (in.peek() == JsonToken.NULL) { in.nextNull(); return null; }
                return ctor.apply(in.nextLong());
            }
        };
    }

    private static final TypeAdapter<WorkshopItemID> WORKSHOP_ID_ADAPTER = longAdapter(WorkshopItemID::new, WorkshopItemID::value);
    private static final TypeAdapter<SteamID64>      STEAM_ID64_ADAPTER  = longAdapter(SteamID64::new,      SteamID64::value);

    /** Pretty-printed output ({@link JarBatchApprovalProtocol}, {@link ModApprovalsStore}). */
    public static final Gson PRETTY = new GsonBuilder()
        .setPrettyPrinting()
        .setDateFormat("yyyy-MM-dd")
        .disableHtmlEscaping()
        .registerTypeAdapter(ModFlags.class, MOD_FLAGS_ADAPTER)
        .registerTypeAdapter(WorkshopItemID.class, WORKSHOP_ID_ADAPTER)
        .registerTypeAdapter(SteamID64.class, STEAM_ID64_ADAPTER)
        .registerTypeHierarchyAdapter(Path.class, PATH_ADAPTER)
        .create();
}
