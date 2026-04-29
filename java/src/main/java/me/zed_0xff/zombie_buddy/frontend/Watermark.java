package me.zed_0xff.zombie_buddy.frontend;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;

import me.zed_0xff.zombie_buddy.*;

import zombie.GameTime;
import zombie.GameWindow;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.textures.Texture;
import zombie.ui.TextManager;
import zombie.ui.UIFont;
import zombie.ui.UIManager;

@Exposer.LuaClass(name = "ZombieBuddy.Watermark")
public final class Watermark {
    private static final String ICON_RESOURCE = "zb_icon.png";
    private static final float GREEN_R = 0.5f;
    private static final float GREEN_G = 1.0f;
    private static final float GREEN_B = 0.5f;
    private static final float GRAY    = 0.9f;

    private static final float DEFAULT_ALPHA = 0.4f;
    private static final int DEFAULT_TTL   = 200;
    private static final int FADE_DURATION = 10;

    private static Texture icon;
    private static boolean iconLoadAttempted;

    private static boolean _in_init = true;
    private static int _ttl         = DEFAULT_TTL;
    private static float _alpha     = DEFAULT_ALPHA;

    private Watermark() {}

    static {
        Exposer.exposeClass(Watermark.class);
        Callbacks.onGameInitComplete.register(() -> {
            _in_init = false;
        });
    }

    public static void setAlpha(float alpha) {
        _alpha = alpha;
    }

    public static void maybeDraw() {
        if (isEnabled()) {
            draw();
        } else if (_ttl > 0) {
            if (_ttl > FADE_DURATION && GameWindow.isIngameState()) {
                // fade immediately on game start
                _ttl = FADE_DURATION;
            }
            _ttl--;
            draw();
        }
    }

    private static float getCurrentAlpha() {
        float alpha = _alpha;
        if (!isEnabled() && _ttl < FADE_DURATION) {
            alpha *= _ttl / (float)FADE_DURATION;
        }
        return alpha;
    }

    private static boolean isEnabled() {
        return _in_init
                || !GameWindow.isIngameState()
                || GameTime.isGamePaused() && !UIManager.isShowPausedMessage();
    }

    private static void draw() {
        String watermark = ZombieBuddy.getFullVersionString() + " loaded";
        String newVersion = SelfUpdater.getNewVersion();
        if (newVersion != null) {
            watermark += " (New version " + newVersion + " installed. Please restart the game)";
        }
        var font     = UIFont.Small;
        var textMgr  = TextManager.instance;
        var textH    = textMgr.MeasureStringY(font, watermark);
        var iconTex  = loadIcon();
        var iconSize = Utils.isHiRes() ? 128 : 64;
        var textX    = iconSize + 4;
        var textY    = 0;

        if (Utils.isMac()) {
            textY += 16; // camera brow
        }

        if (iconTex != null) {
            float alpha = getCurrentAlpha();
            SpriteRenderer.instance.renderi(iconTex, 0, 0, iconSize, iconSize, 1.0f, 1.0f, 1.0f, alpha, null);
        }
        drawGreen(font, textMgr, textX, textY, watermark);
        textY += textH;
        drawActiveJavaMods(font, textMgr, textX, textY, textH);
    }

    private static void drawActiveJavaMods(UIFont font, TextManager textMgr, int textX, int textY, int lineH) {
        var mods = activeJavaMods();
        String prefix = (mods.size() == 0 ? "No" : mods.size()) + " active JAVA mods";
        if (mods.isEmpty()) {
            drawGreen(font, textMgr, textX, textY, prefix);
            return;
        }

        int maxW = Math.max(80, Core.getInstance().getScreenWidth() - textX - 4);
        ArrayList<TextSegment> line = new ArrayList<>();
        String lineText = prefix + ": ";
        boolean lineHasMod = false;
        line.add(TextSegment.green(lineText));
        for (ActiveJavaMod mod : mods) {
            String text = (lineHasMod ? ", " : "") + mod.name;
            String candidate = lineText + text;
            if (lineHasMod && textMgr.MeasureStringX(font, candidate) > maxW) {
                drawSegments(font, textMgr, textX, textY, line);
                textY += lineH;
                line.clear();
                lineText = "";
                lineHasMod = false;
                text = mod.name;
            }
            line.add(new TextSegment(text, mod.signed ? GREEN_R : GRAY, mod.signed ? GREEN_G : GRAY, mod.signed ? GREEN_B : GRAY));
            lineText += text;
            lineHasMod = true;
        }
        drawSegments(font, textMgr, textX, textY, line);
    }

    private static ArrayList<ActiveJavaMod> activeJavaMods() {
        var mods = new ArrayList<ActiveJavaMod>();
        for (String name : ZombieBuddy.getActiveJavaMods()) {
            mods.add(new ActiveJavaMod(name, ZombieBuddy.isJavaModSigned(name)));
        }
        Collections.sort(mods, (a, b) -> a.name.compareTo(b.name));
        return mods;
    }

    private static void drawSegments(UIFont font, TextManager textMgr, int x, int y, ArrayList<TextSegment> segments) {
        int cursorX = x;
        float alpha = getCurrentAlpha();
        for (TextSegment segment : segments) {
            textMgr.DrawString(font, cursorX, y, segment.text, segment.r, segment.g, segment.b, alpha);
            cursorX += textMgr.MeasureStringX(font, segment.text);
        }
    }

    private static void drawGreen(UIFont font, TextManager textMgr, int x, int y, String text) {
        textMgr.DrawString(font, x, y, text, GREEN_R, GREEN_G, GREEN_B, getCurrentAlpha());
    }

    private static Texture loadIcon() {
        if (iconLoadAttempted) {
            return icon;
        }
        iconLoadAttempted = true;
        try (InputStream in = Watermark.class.getClassLoader().getResourceAsStream(ICON_RESOURCE)) {
            if (in == null) {
                Logger.warn("Could not load watermark icon: resource not found: " + ICON_RESOURCE);
                return null;
            }
            icon = new Texture(ICON_RESOURCE, new BufferedInputStream(in), false);
        } catch (Exception e) {
            Logger.warn("Could not load watermark icon: " + e.getMessage());
            icon = null;
        }
        return icon;
    }

    private static final class ActiveJavaMod {
        final String name;
        final boolean signed;

        ActiveJavaMod(String name, boolean signed) {
            this.name = name;
            this.signed = signed;
        }
    }

    private static final class TextSegment {
        final String text;
        final float r;
        final float g;
        final float b;

        TextSegment(String text, float r, float g, float b) {
            this.text = text;
            this.r = r;
            this.g = g;
            this.b = b;
        }

        static TextSegment green(String text) {
            return new TextSegment(text, GREEN_R, GREEN_G, GREEN_B);
        }
    }
}
