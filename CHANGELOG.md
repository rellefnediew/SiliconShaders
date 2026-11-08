# Changelog

## 0.3.0-alpha.5

- New start-up notice when Minecraft's settings differ from what the Standard profile is tuned for: a 1920-pixel-wide
  picture (1920x1080 or 1920x1200), anisotropic 8x texture filtering and 4 mipmap levels. On wider screens it recommends
  the MetalFX render resolution that renders about 1920 pixels wide; the mod never switches MetalFX on by itself.
  Buttons: Apply, Later and Don't show again.
- New **Standard profile**: a fresh installation starts with it, and it is the "Standard" preset in the graphics menu
  (shadows 4096, bloom, light rays, film grain and a tuned picture). Existing settings and settings imported from MCOpt
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
