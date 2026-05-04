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

Events.OnMainMenuEnter.Add(function()
    if ZombieBuddy and ZombieBuddy.Watermark and ZombieBuddy.Watermark.setAlpha then
        ZombieBuddy.Watermark.setAlpha(watermarkOpacity:getValue())
    end
end)
