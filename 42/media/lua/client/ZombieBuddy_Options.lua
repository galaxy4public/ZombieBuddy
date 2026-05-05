if not PZAPI or not PZAPI.ModOptions then return end

local zbOptions = PZAPI.ModOptions:create("ZombieBuddy", "ZombieBuddy")

local watermarkOpacity = zbOptions:addSlider(
    "watermarkOpacity",
    "UI_ZB_WatermarkOpacity",
    0.0, 1.0, 0.05, 0.4
)

local function onChangeWatermarkOpacity(self, value)
    if ZombieBuddy and ZombieBuddy.Watermark and ZombieBuddy.Watermark.setAlpha then
        ZombieBuddy.Watermark.setAlpha(value)
    end
end

watermarkOpacity.onChange      = onChangeWatermarkOpacity
watermarkOpacity.onChangeApply = onChangeWatermarkOpacity

-- ---------------------------------------------------------------------------
-- Suppress sandbox options logging (GameLoadingState.exit)
-- ---------------------------------------------------------------------------

local suppressSandboxLog = zbOptions:addTickBox(
    "suppressSandboxLog",
    "UI_ZB_SuppressSandboxLog",
    false
)

local function onChangeSuppressSandboxLog(self, value)
    if ZombieBuddy and ZombieBuddy.Patches and ZombieBuddy.Patches.GameLoadingState then
        ZombieBuddy.Patches.GameLoadingState.setSuppress(value)
    end
end

suppressSandboxLog.onChange      = onChangeSuppressSandboxLog
suppressSandboxLog.onChangeApply = onChangeSuppressSandboxLog

-- apply settings

Events.OnMainMenuEnter.Add(function()
    if not ZombieBuddy then return end

    if ZombieBuddy.Watermark and ZombieBuddy.Watermark.setAlpha then
        ZombieBuddy.Watermark.setAlpha(watermarkOpacity:getValue())
    end
    if ZombieBuddy.Patches and ZombieBuddy.Patches.GameLoadingState and ZombieBuddy.Patches.GameLoadingState.setSuppress then
        ZombieBuddy.Patches.GameLoadingState.setSuppress(suppressSandboxLog:getValue())
    end
end)
