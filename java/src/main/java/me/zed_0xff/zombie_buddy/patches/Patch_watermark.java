package me.zed_0xff.zombie_buddy.patches;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;

import me.zed_0xff.zombie_buddy.*;

import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.textures.Texture;
import zombie.ui.TextManager;
import zombie.ui.UIFont;

public class Patch_watermark {
    public static final int MAX_WATERMARK_TTL = 500;
    public static boolean _draw_watermark = true;
    public static int _watermark_ttl = MAX_WATERMARK_TTL;
    private static final String ICON_RESOURCE = "zb_icon.png";
    private static Texture icon;
    private static boolean iconLoadAttempted;

    @Patch(className = "zombie.GameWindow", methodName = "init")
    class Patch_GameWindow_init {
        @Patch.OnEnter
        static void enter() {
            _draw_watermark = true;
        }

        @Patch.OnExit
        static void exit() {
            _draw_watermark = false;
            Callbacks.onGameInitComplete.run();
        }
    }

    public static void draw_watermark() {
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
            SpriteRenderer.instance.renderi(iconTex, 0, 0, iconSize, iconSize, 1.0f, 1.0f, 1.0f, 0.4f, null);
        }
        textMgr.DrawString(font, textX, textY, watermark, 0.5f, 1.0f, 0.5f, 0.4f);
        textY += textH;
        drawActiveJavaMods(font, textMgr, textX, textY, textH);
    }

    private static void drawActiveJavaMods(UIFont font, TextManager textMgr, int textX, int textY, int lineH) {
        var mods = activeJavaMods();
        String prefix = (mods.size() == 0 ? "No" : mods.size()) + " active JAVA mods";
        if (mods.isEmpty()) {
            textMgr.DrawString(font, textX, textY, prefix, 0.5f, 1.0f, 0.5f, 0.4f);
            return;
        }

        int maxW = Math.max(80, Core.getInstance().getScreenWidth() - textX - 4);
        String line = prefix + ": ";
        for (String mod : mods) {
            String candidate = line + (line.endsWith(": ") || line.isEmpty() ? "" : ", ") + mod;
            if (!line.endsWith(": ") && textMgr.MeasureStringX(font, candidate) > maxW) {
                textMgr.DrawString(font, textX, textY, line, 0.5f, 1.0f, 0.5f, 0.4f);
                textY += lineH;
                line = mod;
            } else {
                line = candidate;
            }
        }
        textMgr.DrawString(font, textX, textY, line, 0.5f, 1.0f, 0.5f, 0.4f);
    }

    private static ArrayList<String> activeJavaMods() {
        var mods = new ArrayList<>(ZombieBuddy.getActiveJavaMods());
        Collections.sort(mods);
        return mods;
    }

    private static Texture loadIcon() {
        if (iconLoadAttempted) {
            return icon;
        }
        iconLoadAttempted = true;
        try (InputStream in = Patch_watermark.class.getClassLoader().getResourceAsStream(ICON_RESOURCE)) {
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

    @Patch(className = "zombie.core.Core", methodName = "EndFrameUI")
    class Patch_Core_EndFrameUI {
        @Patch.OnEnter
        static void enter() {
            if (_draw_watermark) {
                draw_watermark();
            } else if (_watermark_ttl > 0) {
                draw_watermark();
                _watermark_ttl--;
            }
        }
    }

    @Patch(className = "zombie.gameStates.IngameState", methodName = "renderBackground")
    class Patch_IngameState_renderBackground {
        @Patch.OnExit
        static void exit() {
            draw_watermark();
        }
    }

    @Patch(className = "zombie.gameStates.LoadingQueueState", methodName = "renderBackground")
    class Patch_LoadingQueueState_renderBackground {
        @Patch.OnExit
        static void exit() {
            draw_watermark();
        }
    }
    
    @Patch(className = "zombie.gameStates.MainScreenState", methodName = "renderBackground")
    class Patch_MainScreenState_renderBackground {
        @Patch.OnExit
        static void exit() {
            draw_watermark();
        }
    }

    @Patch(className = "zombie.gameStates.GameLoadingState", methodName = "renderBackground")
    class Patch_GameLoadingState_renderBackground {
        @Patch.OnExit
        static void exit() {
            draw_watermark();
        }
    }

    @Patch(className = "zombie.gameStates.TISLogoState", methodName = "renderBackground")
    class Patch_TISLogoState_renderBackground {
        @Patch.OnExit
        static void exit() {
            draw_watermark();
        }
    }
}
