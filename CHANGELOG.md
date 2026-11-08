# Changelog

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
