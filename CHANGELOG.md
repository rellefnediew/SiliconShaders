# Changelog

## 0.3.0-alpha.5

- Noir joins Standard as a bundled, protected profile, retaining all 312 settings from the saved Noir profile.
  Saving edits creates and selects a Noir copy; selecting unchanged Noir does not create a personal copy.

- Rain and thunder drop brightness/opacity are applied at the final vertex consumer, preventing Lunar's weather color
  from overwriting the sliders. Rain wind can be off without bypassing drop color; snow keeps its vanilla rendering.

- Bundled profiles are protected. Saving a changed Standard profile creates and activates a personal copy automatically;
  leaving an unchanged Standard profile selected creates no copy. Settings, the new profile and its active selection
  are saved in one transaction. Saving while editing establishes the saved settings as the new cancel baseline.

- Quality levels now follow the revised Low/Medium/Standard/High/Ultra settings. SSAO is off in every level; colored
  block light is on only in Ultra; Low disables water reflections and the other levels restore their Standard strength.
  Resolution and MetalFX are kept separate from quality selection. The Standard profile also has colored block light off.

- The Standard quality button now uses the quality values from the shipped Standard profile, so that profile is correctly
  recognized as Standard and the button restores its quality after choosing another level.

- Updated Standard to the supplied 312-setting profile, with the later requested effect defaults. Standard and Noir are the bundled profiles;
  Natural, Cinema, Maximum FPS and the three End presets are removed. Saved user profiles remain available.

- Weather lighting now has separate Sun & daytime and Moon & nighttime sections. Existing rain controls are visible
  directly; nighttime sky darkening and the nighttime thunder light factor have their own sliders. The new night
  defaults use 25% rain darkening and retain 80% of the light in a thunderstorm, with smooth day/night transitions.

- Optional Ko-fi support button in the SiliconShaders settings menu only; opens the browser without closing or saving
  the settings draft. Compact menus show a heart with a descriptive tooltip and accessible label.

- New start-up notice when Minecraft's settings differ from what the Standard profile is tuned for: a 1920-pixel-wide
  picture (1920x1080 or 1920x1200), exclusive fullscreen, anisotropic 8x texture filtering, 4 mipmap levels and improved
  transparency off. Apply selects and activates a supported Full HD mode, including on Retina and 4K displays.
  If the display has no Full HD mode, it recommends a MetalFX render resolution of about 1920 pixels wide; the mod never
  switches MetalFX on by itself. Clouds are switched off at every start, even when the notice is hidden or a world opens directly.
  Buttons: Apply, Later and Don't show again.
- New **Standard profile**: a fresh installation starts with it, and it is the "Standard" preset in the graphics menu
  (the supplied 312 settings, including quality). Existing settings and settings imported from MCOpt
  are never replaced.
- Faster entity shadows in the distance: while the far shadow map is reused from its cache, entities (including your
  own first-person body) are drawn into a small image covering only their rectangle instead of copying and redrawing
  the whole far map every frame. The result is identical texel for texel.
- Lunar Client: your own body casts a shadow in first person again. The mod now poses and meshes the shadow body
  itself instead of submitting an extra player, so no visible copy can appear. Under Lunar the body shadow leaves out
  armor, held items and cosmetics.
- New camera effect: **film grain** (Camera → Film grain). Fine, moving grain that is strongest in midtones and
  changes 24 times a second like real film. Settings: strength and grain size. Off by default.
  It is the last step of the existing camera pass and costs about 0.05 ms per frame at 1080p on an M1 Pro when another
  camera effect is on (about 0.15 ms at 3024x1890).

## 0.3.0-alpha.4

- Announced: a ray tracing update for Macs with M3 or newer is in the works.
- Fixed: with Lunar Client, a lingering copy of your own player (including cosmetics) could appear in the world.
  The first-person body shadow is now off under Lunar Client; -Dsiliconshaders.sun.selfShadow=true turns it back on.
- Shadow casters are only collected from Minecraft's own world rendering, not from extra render calls of other mods.

## 0.3.0-alpha.3

First public release.

- Native Metal rendering with shaders in the Overworld, the Nether and the End
- Sun and moon shadows, coloured block light, hand light and ambient occlusion
- Water, rain, wet surfaces, auroras, night nebula, rainbows and a procedural End sky
- Camera effects: light rays, bloom, depth of field, motion blur, lens flares and more
- MetalFX upscaling and quality settings tuned per Apple Silicon generation
- Graphics menu with profiles, shareable profile codes and a built-in benchmark
- Settings, profiles and profile codes from MCOpt are imported automatically
- Requires an Apple Silicon Mac with macOS 15 or later
