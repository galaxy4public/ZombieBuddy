package me.zed_0xff.zombie_buddy.frontend;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import me.zed_0xff.zombie_buddy.Agent;
import me.zed_0xff.zombie_buddy.JarApprovalOutcome;
import me.zed_0xff.zombie_buddy.JarBatchApprovalProtocol;
import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.ModApprovalsStore;
import me.zed_0xff.zombie_buddy.SteamWorkshop;
import me.zed_0xff.zombie_buddy.Utils;

import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiMouseCursor;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiTableColumnFlags;
import imgui.flag.ImGuiTableBgTarget;
import imgui.flag.ImGuiTableFlags;
import imgui.flag.ImGuiTableRowFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;

import zombie.network.DesktopBrowser;

final class ImguiApprovalDialog {
    private static final String PRIOR_YES = "yes";

    private static final int ROW_OK                     = ImColor.rgba(60, 110, 60, 80);
    private static final int ROW_BAD                    = ImColor.rgba(130, 55, 55, 95);
    private static final int STEAM_BAN_UNKNOWN          = ImColor.rgb(184, 134, 11);
    private static final int STEAM_BAN_NO               = ImColor.rgb(0, 170, 70);
    private static final int LINK                       = ImColor.rgb(80, 150, 255);
    private static final int ALLOW_CHECK_MARK           = ImColor.rgb(0, 255, 0);
    private static final int DENY_CROSS_MARK            = ImColor.rgb(255, 0, 0);

    private static final float BASE_FRAME_HEIGHT        = 19.0f; // vanilla UIFont.Small + 6
    private static final float DIALOG_W                 = 1024.0f;
    private static final float DIALOG_H                 = 600.0f;
    private static final float TABLE_SCROLL_H           = 420.0f;
    private static final float TABLE_ROW_MIN_HEIGHT     = 32.0f;

    private static final String COL_MOD                 = "Mod";
    private static final String COL_AUTHOR              = "Author";
    private static final String COL_UPDATED             = "Updated";
    private static final String COL_STEAM_BAN           = "Steam ban status";
    private static final String COL_ALLOW               = "Allow";
    private static final String COL_TRUST_AUTHOR        = "Trust author";
    private static final String TRUST_AUTHOR_TOOLTIP    = "Signed mods by that author can be auto-allowed while the signature remains valid and the mod is not banned.";
    private static final String WATERMARK_ICON_RESOURCE = "zb_icon.png";

    private static IconTexture watermarkIcon;
    private static boolean watermarkIconLoadAttempted;

    private final List<JarBatchApprovalProtocol.Entry> entries;
    private final ImBoolean[] allow;
    private final ImBoolean[] trustAuthor;
    private final boolean[] initialAllow;
    private final boolean[] forceDeny;
    private final String[] authorGroupKey;
    private final ImBoolean persist = new ImBoolean(false);
    private final ImBoolean open    = new ImBoolean(true);
    private final AtomicReference<List<JarBatchApprovalProtocol.OutLine>> result;
    private final boolean showTrustColumn;
    private static float tableRowStartY;

    ImguiApprovalDialog(
            List<JarBatchApprovalProtocol.Entry> entries,
            AtomicReference<List<JarBatchApprovalProtocol.OutLine>> result) {
        this.entries = entries;
        this.result = result;
        this.allow = new ImBoolean[entries.size()];
        this.trustAuthor = new ImBoolean[entries.size()];
        this.initialAllow = new boolean[entries.size()];
        this.forceDeny = new boolean[entries.size()];
        this.authorGroupKey = new String[entries.size()];
        this.showTrustColumn = entries.stream().anyMatch(e -> "yes".equals(e.zbsValid));
        for (int i = 0; i < entries.size(); i++) {
            JarBatchApprovalProtocol.Entry e = entries.get(i);
            this.initialAllow[i] = initialAllow(e);
            this.forceDeny[i] = "no".equals(e.zbsValid) || "yes".equals(e.steamBanStatus);
            this.authorGroupKey[i] = e.zbsSteamId != null ? e.zbsSteamId.toString() : "";
            this.allow[i] = new ImBoolean(this.initialAllow[i]);
            this.trustAuthor[i] = new ImBoolean(false);
        }
    }

    boolean isOpen() {
        return open.get();
    }

    void close() {
        open.set(false);
    }

    void draw() {
        ImGui.getIO().setConfigWindowsMoveFromTitleBarOnly(true);
        ImGui.setNextWindowSize(dialogWidth(), dialogHeight(), ImGuiCond.Appearing);
        ImGui.setNextWindowPos(
                ImGui.getIO().getDisplaySizeX() * 0.5f,
                ImGui.getIO().getDisplaySizeY() * 0.5f,
                ImGuiCond.Appearing,
                0.5f,
                0.5f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowTitleAlign, 0.5f, 0.5f);
        boolean visible = ImGui.begin("ZombieBuddy Java Mod Approval", open, ImGuiWindowFlags.NoCollapse);
        ImGui.popStyleVar();
        if (!visible) {
            ImGui.end();
            return;
        }
        drawWindowIconOverlay();
        centeredText("Review each Java mod before allowing it to load.");
        ImGui.separator();

        if (ImGui.beginChild("##zb-imgui-approval-scroll", 0.0f, scaled(TABLE_SCROLL_H), true)) {
            int columns = showTrustColumn ? 6 : 5;
            int tableFlags = ImGuiTableFlags.Borders
                    | ImGuiTableFlags.RowBg
                    | ImGuiTableFlags.Resizable
                    | ImGuiTableFlags.SizingStretchProp;
            if (ImGui.beginTable("##zb-imgui-approval-table", columns, tableFlags)) {
                setupTableColumns();
                drawHeaderRow();
                for (int i = 0; i < entries.size(); i++) {
                    drawRow(i, entries.get(i));
                }
                ImGui.endTable();
            }
        }
        ImGui.endChild();

        ImGui.spacing();
        ImGui.spacing();
        drawBottomActions();
        ImGui.end();
    }

    private void drawBottomActions() {
        String persistLabel = "Save decisions to disk (persist across game launches)";
        String persistTooltip = "Saved to " + approvalsFilePath();
        String cancelLabel = "Cancel";
        String okLabel = "OK";

        float spacing       = ImGui.getStyle().getItemSpacingX();
        float paddingX      = ImGui.getStyle().getFramePaddingX();
        float checkboxW     = ImGui.getFrameHeight();
        float buttonH       = ImGui.getFrameHeight() * 1.35f;
        float persistLabelW = ImGui.calcTextSize(persistLabel).x;
        float cancelW       = ImGui.calcTextSize(cancelLabel).x + paddingX * 4.0f;
        float okW           = Math.max(80.0f, ImGui.calcTextSize(okLabel).x + paddingX * 4.0f);
        float persistRowW   = persistLabelW + spacing + checkboxW;
        float buttonRowW    = cancelW + spacing + okW;
        float rightPad      = ImGui.getWindowWidth() - ImGui.getWindowContentRegionMaxX();

        ImGui.setCursorPosX(Math.max(ImGui.getCursorPosX(), ImGui.getWindowWidth() - rightPad - persistRowW));
        ImGui.text(persistLabel);
        showTooltipIfHovered(persistTooltip);
        ImGui.sameLine();
        clickableCheckbox("##persist-decisions", persist);
        showTooltipIfHovered(persistTooltip);
        ImGui.spacing();

        ImGui.setCursorPosX(Math.max(ImGui.getCursorPosX(), ImGui.getWindowWidth() - rightPad - buttonRowW));
        boolean cancelClicked = clickableButton(cancelLabel, cancelW, buttonH);
        showTooltipIfHovered("deny all for this game session");
        if (cancelClicked) {
            result.compareAndSet(null, denyAll(entries));
            close();
        }
        ImGui.sameLine();
        if (clickableButton(okLabel, okW, buttonH)) {
            result.compareAndSet(null, buildLines());
            close();
        }
    }

    private static void drawWindowIconOverlay() {
        IconTexture icon = loadWatermarkIcon();
        if (icon == null) {
            return;
        }
        float size = ImGui.getFrameHeight() * 2;
        float pad = ImGui.getStyle().getFramePaddingX();
        float x = ImGui.getWindowPosX() + pad;
        float y = ImGui.getWindowPosY() + pad * 0.5f;

        ImDrawList drawList = ImGui.getWindowDrawList();
        drawList.pushClipRectFullScreen();
        try {
            drawList.addImage(icon.textureId, x, y, x + size, y + size);
        } finally {
            drawList.popClipRect();
        }
    }

    private void drawHeaderRow() {
        ImGui.tableNextRow(ImGuiTableRowFlags.Headers, scaled(TABLE_ROW_MIN_HEIGHT));
        tableRowStartY = ImGui.getCursorPosY();
        ImGui.tableSetColumnIndex(0); cellCenteredText(COL_MOD);
        ImGui.tableSetColumnIndex(1); cellCenteredText(COL_AUTHOR);
        ImGui.tableSetColumnIndex(2); cellCenteredText(COL_UPDATED);
        ImGui.tableSetColumnIndex(3); cellCenteredText(COL_STEAM_BAN);
        ImGui.tableSetColumnIndex(4); cellCenteredText(COL_ALLOW);
        if (showTrustColumn) {
            ImGui.tableSetColumnIndex(5); cellCenteredText(COL_TRUST_AUTHOR);
        }
    }

    private static void centeredText(String text) {
        centerNextItem(ImGui.calcTextSize(text).x);
        ImGui.text(text);
    }

    private static void centeredDisabledText(String text) {
        centerNextItem(ImGui.calcTextSize(text).x);
        ImGui.textDisabled(text);
    }

    private static void centeredTextColored(int color, String text) {
        centerNextItem(ImGui.calcTextSize(text).x);
        ImGui.textColored(color, text);
    }

    private static void centeredTextColored(float r, float g, float b, float a, String text) {
        centerNextItem(ImGui.calcTextSize(text).x);
        ImGui.textColored(r, g, b, a, text);
    }

    private static void cellTextWrapped(String text) {
        centerNextItemVertically(ImGui.getTextLineHeight());
        ImGui.textWrapped(text);
    }

    private static void cellCenteredText(String text) {
        centerNextItemVertically(ImGui.getTextLineHeight());
        centeredText(text);
    }

    private static void cellCenteredDisabledText(String text) {
        centerNextItemVertically(ImGui.getTextLineHeight());
        centeredDisabledText(text);
    }

    private static void cellCenteredTextColored(int color, String text) {
        centerNextItemVertically(ImGui.getTextLineHeight());
        centeredTextColored(color, text);
    }

    private static void cellCenteredTextColored(float r, float g, float b, float a, String text) {
        centerNextItemVertically(ImGui.getTextLineHeight());
        centeredTextColored(r, g, b, a, text);
    }

    private static void centerNextItem(float itemW) {
        float cellW = ImGui.getContentRegionAvailX();
        ImGui.setCursorPosX(ImGui.getCursorPosX() + Math.max(0.0f, (cellW - itemW) * 0.5f));
    }

    private static void centerNextItemVertically(float itemH) {
        ImGui.setCursorPosY(tableRowStartY + Math.max(0.0f, (scaled(TABLE_ROW_MIN_HEIGHT) - itemH) * 0.5f));
    }

    private static float contentColumnWidth(float contentW) {
        return contentW + ImGui.getStyle().getCellPaddingX() * 2.0f + ImGui.getStyle().getItemSpacingX();
    }

    private static float scaled(float value) {
        return value * (ImGui.getFrameHeight() / BASE_FRAME_HEIGHT);
    }

    private static float dialogWidth() {
        return Math.min(scaled(DIALOG_W), Math.max(320.0f, ImGui.getIO().getDisplaySizeX() - ImGui.getFrameHeight() * 2.0f));
    }

    private static float dialogHeight() {
        return Math.min(scaled(DIALOG_H), Math.max(240.0f, ImGui.getIO().getDisplaySizeY() - ImGui.getFrameHeight() * 2.0f));
    }

    private void setupTableColumns() {
        ImGui.tableSetupColumn(COL_MOD, ImGuiTableColumnFlags.WidthStretch, 1.0f);
        setupFixedColumn(COL_AUTHOR, columnContentWidth(COL_AUTHOR, e -> authorText(e)));
        setupFixedColumn(COL_UPDATED, columnContentWidth(COL_UPDATED, e -> updatedText(e)));
        setupFixedColumn(COL_STEAM_BAN, steamBanColumnContentWidth());
        setupFixedColumn(COL_ALLOW, allowColumnContentWidth());
        if (showTrustColumn) {
            setupFixedColumn(COL_TRUST_AUTHOR, Math.max(ImGui.calcTextSize(COL_TRUST_AUTHOR).x, ImGui.getFrameHeight()));
        }
    }

    private static void setupFixedColumn(String label, float contentW) {
        ImGui.tableSetupColumn(label, ImGuiTableColumnFlags.WidthFixed, contentColumnWidth(contentW));
    }

    private float columnContentWidth(String header, EntryText text) {
        float w = ImGui.calcTextSize(header).x;
        for (JarBatchApprovalProtocol.Entry e : entries) {
            w = Math.max(w, ImGui.calcTextSize(text.apply(e)).x);
        }
        return w;
    }

    private interface EntryText {
        String apply(JarBatchApprovalProtocol.Entry e);
    }

    private static String authorText(JarBatchApprovalProtocol.Entry e) {
        if ("yes".equals(e.zbsValid) && e.zbsSteamId != null) {
            return !Utils.isBlank(e.zbsNotice) ? e.zbsNotice : e.zbsSteamId.toString();
        }
        if ("no".equals(e.zbsValid)) {
            return "No";
        }
        if ("unsigned".equals(e.zbsValid)) {
            return "(unsigned)";
        }
        return "?";
    }

    private static String updatedText(JarBatchApprovalProtocol.Entry e) {
        return !Utils.isBlank(e.modifiedHuman) ? e.modifiedHuman : "-";
    }

    private static float steamBanColumnContentWidth() {
        return Math.max(
                ImGui.calcTextSize(COL_STEAM_BAN).x,
                ImGui.calcTextSize("Unknown").x);
    }

    private float allowColumnContentWidth() {
        float w = ImGui.calcTextSize(COL_ALLOW).x;
        w = Math.max(w, ImGui.getFrameHeight() * 2.0f + ImGui.getStyle().getItemSpacingX());
        return w;
    }

    static List<JarBatchApprovalProtocol.OutLine> denyAll(List<JarBatchApprovalProtocol.Entry> pending) {
        ArrayList<JarBatchApprovalProtocol.OutLine> out = new ArrayList<>(pending.size());
        for (JarBatchApprovalProtocol.Entry e : pending) {
            out.add(new JarBatchApprovalProtocol.OutLine(
                    e.modKey,
                    e.workshopItemId,
                    e.sha256,
                    JarApprovalOutcome.DENY_SESSION.toBatchToken(),
                    null));
        }
        return out;
    }

    private void drawRow(int index, JarBatchApprovalProtocol.Entry e) {
        boolean zbsYes = "yes".equals(e.zbsValid);
        boolean zbsNo = "no".equals(e.zbsValid);
        boolean steamBanYes = "yes".equals(e.steamBanStatus);
        int rowColor = steamBanYes || zbsNo ? ROW_BAD : (zbsYes ? ROW_OK : 0);

        ImGui.tableNextRow(0, scaled(TABLE_ROW_MIN_HEIGHT));
        tableRowStartY = ImGui.getCursorPosY();
        if (rowColor != 0) {
            ImGui.tableSetBgColor(ImGuiTableBgTarget.RowBg0, rowColor);
        }

        ImGui.tableSetColumnIndex(0);
        drawMod(e);
        // if (!Utils.isBlank(e.jarAbsolutePath)) {
        //     ImGui.textWrapped(e.jarAbsolutePath);
        // }

        ImGui.tableSetColumnIndex(1);
        drawAuthor(e);

        ImGui.tableSetColumnIndex(2);
        cellCenteredText(updatedText(e));

        ImGui.tableSetColumnIndex(3);
        drawSteamBan(e);

        ImGui.tableSetColumnIndex(4);
        drawAllow(index);

        if (showTrustColumn) {
            ImGui.tableSetColumnIndex(5);
            boolean canTrust = zbsYes && !steamBanYes && e.zbsSteamId != null;
            if (canTrust) {
                centerNextItemVertically(ImGui.getFrameHeight());
                centerNextItem(ImGui.getFrameHeight());
                boolean before = trustAuthor[index].get();
                if (clickableCheckbox("##trust-" + index, trustAuthor[index]) && before != trustAuthor[index].get()) {
                    applyTrustAuthor(index, trustAuthor[index].get());
                }
                showTooltipIfHovered(TRUST_AUTHOR_TOOLTIP);
            }
        }
    }

    private void drawMod(JarBatchApprovalProtocol.Entry e) {
        String name = displayName(e);
        String tooltip = modTooltip(e);
        if (e.workshopItemId == null) {
            cellTextWrapped(name);
            showTooltipIfHovered(tooltip);
            return;
        }
        String url = SteamWorkshop.workshopItemUrl(e.workshopItemId);
        linkTextWrapped(name, url, "url: " + url + "\n" + tooltip);
    }

    private void drawAuthor(JarBatchApprovalProtocol.Entry e) {
        if ("yes".equals(e.zbsValid) && e.zbsSteamId != null) {
            centerNextItemVertically(ImGui.getTextLineHeight());
            centeredLinkText(authorText(e), SteamWorkshop.authorWorkshopUrl(e.zbsSteamId));
            return;
        }
        if ("no".equals(e.zbsValid)) {
            cellCenteredTextColored(1.0f, 0.25f, 0.25f, 1.0f, "Invalid signature");
            if (!Utils.isBlank(e.zbsNotice)) {
                showTooltipIfHovered(e.zbsNotice); // should be called after text draw
            }
            return;
        }
        if ("unsigned".equals(e.zbsValid)) {
            cellCenteredDisabledText("(unsigned)");
            return;
        }
        cellCenteredText("?");
    }

    private void drawSteamBan(JarBatchApprovalProtocol.Entry e) {
        String status = Utils.isBlank(e.steamBanStatus) ? "unknown" : e.steamBanStatus;
        if ("unknown".equals(status)) {
            cellCenteredTextColored(STEAM_BAN_UNKNOWN, "Unknown");
        } else if ("yes".equals(status)) {
            cellCenteredTextColored(1.0f, 0.25f, 0.25f, 1.0f, "Yes");
        } else {
            cellCenteredTextColored(STEAM_BAN_NO, "No");
        }
        if (!Utils.isBlank(e.steamBanReason)) {
            showTooltipIfHovered(e.steamBanReason);
        }
    }

    private void drawAllow(int index) {
        boolean interactive = true;
        if (forceDeny[index]) {
            allow[index].set(false);
            interactive = false;
        } else if (trustAuthor[index].get()) {
            allow[index].set(true);
            interactive = false;
        }
        drawAllowCheckboxes(index, interactive);
    }

    private void drawAllowCheckboxes(int index, boolean interactive) {
        float checkboxW = ImGui.getFrameHeight();
        float rowW = checkboxW * 2.0f + ImGui.getStyle().getItemSpacingX();
        centerNextItemVertically(ImGui.getFrameHeight());
        centerNextItem(rowW);
        ImGui.beginDisabled(!interactive);
        try {
            if (clickableCheckboxValue("##allow-yes-" + index, allow[index].get(), "Yes", ALLOW_CHECK_MARK)) {
                allow[index].set(true);
            }
            ImGui.sameLine();
            if (clickableCrossCheckbox("##allow-no-" + index, !allow[index].get(), "No")) {
                allow[index].set(false);
            }
        } finally {
            ImGui.endDisabled();
        }
    }

    private static boolean clickableButton(String label, float w, float h) {
        boolean clicked = ImGui.button(label, w, h);
        handCursorIfHovered();
        return clicked;
    }

    private static boolean clickableCheckbox(String label, ImBoolean value) {
        boolean clicked = ImGui.checkbox(label, value);
        handCursorIfHovered();
        return clicked;
    }

    private static boolean clickableCheckboxValue(String label, boolean checked, String tooltip) {
        return clickableCheckboxValue(label, checked, tooltip, 0);
    }

    private static boolean clickableCheckboxValue(String label, boolean checked, String tooltip, int checkMarkColor) {
        if (checkMarkColor != 0) {
            ImGui.pushStyleColor(ImGuiCol.CheckMark, checkMarkColor);
        }
        try {
            boolean clicked = ImGui.checkbox(label, new ImBoolean(checked));
            handCursorIfHovered();
            showTooltipIfHovered(tooltip);
            return clicked;
        } finally {
            if (checkMarkColor != 0) {
                ImGui.popStyleColor();
            }
        }
    }

    private static boolean clickableCrossCheckbox(String label, boolean checked, String tooltip) {
        if (!checked) {
            return clickableCheckboxValue(label, false, tooltip);
        }

        float size = ImGui.getFrameHeight();
        boolean clicked = ImGui.invisibleButton(label, size, size);
        boolean hovered = ImGui.isItemHovered();
        boolean active = ImGui.isItemActive();
        float x0 = ImGui.getItemRectMinX();
        float y0 = ImGui.getItemRectMinY();
        float x1 = ImGui.getItemRectMaxX();
        float y1 = ImGui.getItemRectMaxY();
        float rounding = ImGui.getStyle().getFrameRounding();
        int bgColor = ImGui.getColorU32(active
                ? ImGuiCol.FrameBgActive
                : (hovered ? ImGuiCol.FrameBgHovered : ImGuiCol.FrameBg));

        ImDrawList drawList = ImGui.getWindowDrawList();
        drawList.addRectFilled(x0, y0, x1, y1, bgColor, rounding);

        float pad = Math.max(1.0f, (float) (int) (size / 6.0f));
        renderCrossMark(drawList, x0 + pad, y0 + pad, DENY_CROSS_MARK, size - pad * 2.0f);

        if (hovered) {
            ImGui.setMouseCursor(ImGuiMouseCursor.Hand);
            ImGui.setTooltip(tooltip);
        }
        return clicked;
    }

    private static void renderCrossMark(ImDrawList drawList, float x, float y, int color, float size) {
        float thickness = Math.max(size / 5.0f, 1.0f);
        size -= thickness * 0.5f;
        x += thickness * 0.25f;
        y += thickness * 0.25f;

        drawList.pathClear();
        drawList.pathLineTo(x, y);
        drawList.pathLineTo(x + size, y + size);
        drawList.pathStroke(color, 0, thickness);

        drawList.pathClear();
        drawList.pathLineTo(x + size, y);
        drawList.pathLineTo(x, y + size);
        drawList.pathStroke(color, 0, thickness);
    }

    private static IconTexture loadWatermarkIcon() {
        if (watermarkIconLoadAttempted) {
            return watermarkIcon;
        }
        watermarkIconLoadAttempted = true;
        try {
            watermarkIcon = loadIconTexture(WATERMARK_ICON_RESOURCE);
        } catch (Exception e) {
            Logger.warn("Could not load ImGui watermark icon: " + e.getMessage());
            watermarkIcon = null;
        }
        return watermarkIcon;
    }

    private static IconTexture loadIconTexture(String resourceName) throws Exception {
        try (InputStream in = openResource(resourceName)) {
            if (in == null) {
                throw new IllegalStateException("resource not found: " + resourceName);
            }
            BufferedImage image = ImageIO.read(in);
            if (image == null) {
                throw new IllegalStateException("invalid image: " + resourceName);
            }

            int width = image.getWidth();
            int height = image.getHeight();
            ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int argb = image.getRGB(x, y);
                    pixels.put((byte) ((argb >> 16) & 0xff));
                    pixels.put((byte) ((argb >> 8) & 0xff));
                    pixels.put((byte) (argb & 0xff));
                    pixels.put((byte) ((argb >> 24) & 0xff));
                }
            }
            pixels.flip();

            int textureId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glTexImage2D(
                    GL11.GL_TEXTURE_2D,
                    0,
                    GL11.GL_RGBA,
                    width,
                    height,
                    0,
                    GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE,
                    pixels);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            return new IconTexture(textureId, width, height);
        }
    }

    private static InputStream openResource(String resourceName) {
        ClassLoader cl = ImguiApprovalDialog.class.getClassLoader();
        InputStream in = cl.getResourceAsStream(resourceName);
        if (in != null) {
            return in;
        }
        return cl.getResourceAsStream("resources/" + resourceName);
    }

    private static final class IconTexture {
        final int textureId;
        final int width;
        final int height;

        IconTexture(int textureId, int width, int height) {
            this.textureId = textureId;
            this.width = width;
            this.height = height;
        }
    }

    private static void handCursorIfHovered() {
        if (ImGui.isItemHovered()) {
            ImGui.setMouseCursor(ImGuiMouseCursor.Hand);
        }
    }

    private static void linkTextWrapped(String text, String url) {
        linkTextWrapped(text, url, url);
    }

    private static void linkTextWrapped(String text, String url, String tooltip) {
        centerNextItemVertically(ImGui.getTextLineHeight());
        ImGui.pushStyleColor(ImGuiCol.Text, LINK);
        try {
            ImGui.textWrapped(text);
        } finally {
            ImGui.popStyleColor();
        }
        handleLinkInteraction(url, tooltip);
    }

    private static void centeredLinkText(String text, String url) {
        centerNextItem(ImGui.calcTextSize(text).x);
        ImGui.textColored(LINK, text);
        handleLinkInteraction(url);
    }

    private static void handleLinkInteraction(String url) {
        handleLinkInteraction(url, url);
    }

    private static void handleLinkInteraction(String url, String tooltip) {
        if (!ImGui.isItemHovered()) {
            return;
        }
        ImGui.setMouseCursor(ImGuiMouseCursor.Hand);
        ImGui.setTooltip(tooltip);
        if (ImGui.isItemClicked()) {
            DesktopBrowser.openURL(url);
        }
    }

    private static void showTooltipIfHovered(String text) {
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(text);
        }
    }

    private static String modTooltip(JarBatchApprovalProtocol.Entry e) {
        StringBuilder sb = new StringBuilder();
        if (!Utils.isBlank(e.modId)) {
            sb.append("id:  ").append(e.modId);
        }
        if (!Utils.isBlank(e.jarAbsolutePath)) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append("jar: ").append(e.jarAbsolutePath);
        }
        return sb.toString();
    }

    private static String approvalsFilePath() {
        return Agent.configDir().resolve(ModApprovalsStore.JSON_FILE_NAME).toString();
    }

    private void applyTrustAuthor(int sourceIndex, boolean selected) {
        String key = authorGroupKey[sourceIndex];
        if (Utils.isBlank(key)) {
            setAllowForTrust(sourceIndex, selected);
            return;
        }
        for (int i = 0; i < authorGroupKey.length; i++) {
            if (key.equals(authorGroupKey[i])) {
                trustAuthor[i].set(selected);
                setAllowForTrust(i, selected);
            }
        }
    }

    private void setAllowForTrust(int index, boolean selected) {
        if (selected) {
            allow[index].set(true);
            return;
        }
        allow[index].set(forceDeny[index] ? false : initialAllow[index]);
    }


    private List<JarBatchApprovalProtocol.OutLine> buildLines() {
        ArrayList<JarBatchApprovalProtocol.OutLine> out = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            JarBatchApprovalProtocol.Entry e = entries.get(i);
            boolean rowAllow = allow[i].get();
            JarApprovalOutcome outcome = rowAllow
                    ? (persist.get() ? JarApprovalOutcome.ALLOW_PERSIST : JarApprovalOutcome.ALLOW_SESSION)
                    : (persist.get() ? JarApprovalOutcome.DENY_PERSIST : JarApprovalOutcome.DENY_SESSION);
            if ("no".equals(e.zbsValid) || "yes".equals(e.steamBanStatus)) {
                outcome = persist.get() ? JarApprovalOutcome.DENY_PERSIST : JarApprovalOutcome.DENY_SESSION;
            }
            boolean trust = persist.get() && trustAuthor[i].get() && "yes".equals(e.zbsValid) && e.zbsSteamId != null;
            out.add(new JarBatchApprovalProtocol.OutLine(
                    e.modKey,
                    e.workshopItemId,
                    e.sha256,
                    outcome.toBatchToken(),
                    trust ? e.zbsSteamId : null));
        }
        return out;
    }

    private static boolean initialAllow(JarBatchApprovalProtocol.Entry e) {
        if ("no".equals(e.zbsValid) || "yes".equals(e.steamBanStatus)) {
            return false;
        }
        return PRIOR_YES.equals(e.priorHint);
    }

    private static String displayName(JarBatchApprovalProtocol.Entry e) {
        return e.modDisplayName;
    }

}
