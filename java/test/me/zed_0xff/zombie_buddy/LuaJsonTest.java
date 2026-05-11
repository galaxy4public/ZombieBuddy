package me.zed_0xff.zombie_buddy;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LuaJSONTest {

    private static JsonElement parse(String json) { return JsonParser.parseString(json); }
    private static boolean isValidJson(String s) { try { parse(s); return true; } catch (Exception e) { return false; } }

    // depth=10, no limit — exercises the Gson path
    private static LuaJSON deep() { return new LuaJSON(10); }

    // ---- primitives ----

    @Test void null_yields_null()              { assertEquals("null",  deep().toJson(null)); }
    @Test void string_is_quoted()              { assertEquals("\"hello\"", deep().toJson("hello")); }
    @Test void boolean_true()                  { assertEquals("true",  deep().toJson(Boolean.TRUE)); }
    @Test void boolean_false()                 { assertEquals("false", deep().toJson(Boolean.FALSE)); }
    @Test void double_integer_has_no_decimal() { assertEquals("42",    deep().toJson(42.0)); }
    @Test void float_integer_has_no_decimal()  { assertEquals("42",    deep().toJson(42.0f)); }
    @Test void float_fractional_has_decimal()  { String r = deep().toJson(3.14f); assertTrue(r.contains("."), "float should contain decimal: " + r); }

    @Test void double_float_has_decimal() {
        String r = deep().toJson(3.14);
        assertTrue(r.contains("."), "float should contain decimal point");
    }

    @Test void string_escapes_special_chars() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", deep().toJson("a\"b\\c\nd"));
    }

    // ---- collections ----

    @Test void map_serialized() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", 1.0);
        m.put("y", "hello");
        JsonElement el = parse(deep().toJson(m));
        assertTrue(el.isJsonObject());
        assertEquals(1, el.getAsJsonObject().get("x").getAsInt());
        assertEquals("hello", el.getAsJsonObject().get("y").getAsString());
    }

    @Test void list_serialized() {
        JsonElement el = parse(deep().toJson(List.of(1.0, "two", Boolean.TRUE)));
        assertTrue(el.isJsonArray());
        assertEquals(3, el.getAsJsonArray().size());
    }

    @Test void nested_map_beyond_depth_shows_sentinel() {
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("inner", Map.of("k", "v"));
        JsonElement el = parse(new LuaJSON(1).toJson(outer)); // depth 1: outer expands, inner doesn't
        assertEquals("[object]", el.getAsJsonObject().get("inner").getAsString());
    }

    @Test void nested_list_beyond_depth_shows_sentinel() {
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("list", List.of(1.0, 2.0));
        JsonElement el = parse(new LuaJSON(1).toJson(outer));
        assertEquals("[list]", el.getAsJsonObject().get("list").getAsString());
    }

    // ---- max_len enforcement (streaming path) ----

    @Test void fits_within_limit_returns_full_output() {
        assertEquals("\"hello\"", new LuaJSON(10, 1000).toJson("hello"));
    }

    @Test void zero_limit_returns_full_output() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) m.put("k" + i, "v" + i);
        String r = new LuaJSON(10, 0).toJson(m); // 0 = no limit
        assertTrue(isValidJson(r));
        assertEquals(20, parse(r).getAsJsonObject().entrySet().size());
    }

    @Test void truncated_output_ends_with_ellipsis() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < 50; i++) m.put("key" + i, "value" + i);
        String r = new LuaJSON(10, 80).toJson(m);
        assertTrue(r.endsWith("..."), "truncated result should end with ...: " + r);
        assertEquals(83, r.length(), "length should be maxLen(80) + 3: " + r);
    }

    @Test void truncated_list_ends_with_ellipsis() {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < 100; i++) list.add("item" + i);
        String r = new LuaJSON(10, 50).toJson(list);
        assertTrue(r.endsWith("..."), "truncated result should end with ...: " + r);
        assertEquals(53, r.length());
    }

    @Test void large_map_length_close_to_limit() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < 100; i++) m.put("k" + i, "v" + i);
        int limit = 60;
        String r = new LuaJSON(10, limit).toJson(m);
        assertEquals(limit + 3, r.length(), "length should be exactly limit+3: " + r);
    }

    @Test void tiny_limit_yields_ellipsis() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", "value");
        String r = new LuaJSON(10, 3).toJson(m);
        assertEquals("{\"k...", r);
    }

    // ---- canSerialize ----

    @Test void canSerialize_null()    { assertTrue(LuaJSON.canSerialize(null)); }
    @Test void canSerialize_string()  { assertTrue(LuaJSON.canSerialize("hello")); }
    @Test void canSerialize_double()  { assertTrue(LuaJSON.canSerialize(1.0)); }
    @Test void canSerialize_boolean() { assertTrue(LuaJSON.canSerialize(Boolean.TRUE)); }
    @Test void canSerialize_map()     { assertTrue(LuaJSON.canSerialize(Map.of("k", "v"))); }
    @Test void canSerialize_list()    { assertTrue(LuaJSON.canSerialize(List.of(1.0))); }
    @Test void canSerialize_integer() { assertTrue(LuaJSON.canSerialize(42)); }
    @Test void canSerialize_long()    { assertTrue(LuaJSON.canSerialize(42L)); }
    @Test void canSerialize_unknown() { assertFalse(LuaJSON.canSerialize(new Object())); }

    @Test void truncated_result_is_shorter_than_full() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) m.put("k" + i, i * 1.0);
        String full  = new LuaJSON(10, 0).toJson(m);
        String trunc = new LuaJSON(10, full.length() / 2).toJson(m);
        assertTrue(trunc.length() < full.length(), "truncated should be shorter than full");
        assertTrue(trunc.endsWith("..."));
    }

    // ---- STRIP_QUOTES ----

    private static LuaJSON sq() { return new LuaJSON(10, 1000, LuaJSON.Flags.STRIP_QUOTES); }

    @Test void strip_quotes_simple_string()         { assertEquals("hello",   sq().toJson("hello")); }
    @Test void strip_quotes_string_with_space()     { assertEquals("\"hello world\"", sq().toJson("hello world")); }
    @Test void strip_quotes_string_with_quote()     { assertEquals("\"a\\\"b\"", sq().toJson("a\"b")); }
    @Test void strip_quotes_string_with_backslash() { assertEquals("\"a\\\\b\"", sq().toJson("a\\b")); }
    @Test void strip_quotes_empty_string()          { assertEquals("\"\"",     sq().toJson("")); }
    @Test void strip_quotes_map_keys_and_values() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "title");
        m.put("name", "hello world");
        assertEquals("{type:title,name:\"hello world\"}", sq().toJson(m));
    }
}
