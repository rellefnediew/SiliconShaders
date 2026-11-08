# Metal shaderpack compiler experiment

## Whole-pack default translation experiment

`tools/scene-probe PACK.zip OUTPUT_DIR` performs an offline census for the pack's Overworld, Nether and End programs,
using source defaults and macOS/Minecraft 26.3 macros. It does not read a saved `.zip.txt` or select a quality profile.
It respects default `program.*.enabled=false` switches. `dh_` and `clrwl_` programs are recorded separately as optional
Distant Horizons and Colorwheel integrations, which are absent from this test environment.

The separate scene compiler supports legacy geometry inputs and matrices, fog state, shadow comparisons and multiple
fragment outputs. It preserves these as explicit runtime dependencies, with reflected buffer layouts and stage interfaces.
Scene vertex output converts OpenGL clip depth to Metal's range and inverts Y. The existing final-image preview keeps its
own restricted adapter and input checks. Compute translation has a fixture check; Complementary's default macOS settings
disable its colored-lighting compute stages.

The native checker uses the original siliconshaders `mc_library_new` entry point to compile every generated Metal program,
then links matching vertex/fragment pairs using reflected attributes, RGBA16F color attachments and a Depth32F attachment.
These are compiler/interface checks, not scene rendering, validation of pack attachment formats, resource population,
image comparisons or FPS measurements. The generated directory is **not an installable shader pack**.

The remaining sections document the earlier, narrower final-pass experiment.

This module is the standalone compiler experiment for shaderpack support in siliconshaders. The `metal` module now also shares its compiler and archive reader with an opt-in in-game post-processing preview. Running this standalone tool does not register game hooks, load Iris or Sodium, change the active game renderer, or write to a Minecraft installation.

## What is implemented

- Read a shader ZIP without extracting or modifying it.
- Expand quoted/root-relative GLSL includes, preserve a numeric source map, and apply matching numeric `#define` settings from a supplied settings file.
- Compile `shaders/world0/final.vsh` and `final.fsh` with shaderc to SPIR-V, then generate Metal Shading Language using SPIRV-Cross.
- Reflect uniform-buffer member offsets and texture bindings for the Metal program.
- Compile the generated source with Apple's Metal compiler at runtime, construct a graphics pipeline, and draw a 64 × 64 image on the GPU.
- Check every output pixel against a CPU reference for Complementary Reimagined r5.9.3's default final-pass sharpening, vignette, and dithering. Two constant images and a checkerboard exercise different values and neighboring-pixel sampling.
- Verify that the original ZIP still has the same SHA-256 after compilation.

The input image is synthetic. In a complete renderer it would be the result of the earlier shaderpack passes. This probe does not create a scene, shadows, water, atmosphere, or the full Complementary look.

## Run

Requires an Apple Silicon Mac, Java **JDK 25** (a JRE cannot compile the project), and Apple's Command Line Tools. Gradle resolves LWJGL 3.4.3 and Gson 2.14.0 from Maven Central. No OpenGL or Vulkan context is created by the probe.

From the repository root, with `JAVA_HOME` pointing to a JDK 25:

```sh
tools/pack-probe \
  "$HOME/.lunarclient/profiles/minecraft/shaderpacks/ComplementaryReimagined_r5.9.3.zip" \
  "$PWD/run/pack-probe" \
  "$HOME/.lunarclient/profiles/minecraft/shaderpacks/ComplementaryReimagined_r5.9.3.zip.txt"
```

The output directory contains translated source, SPIR-V, Metal source, a binding manifest, GPU check results, and three PPM test images. They are local compiler artifacts, not a converted pack to install or redistribute. The source ZIP remains the authoritative input.

Run archive/adapter fixture checks separately with `./gradlew :packprobe:check`. They cover nested and commented includes, numeric options, include cycles, root escape, invalid option values, archive integrity, and rejection of unsupported final outputs.

## Scope of this experiment

- Only the overworld final pass was adapted and tested. Files named `final` in arbitrary packs are not automatically compatible.
- Legacy fullscreen positions and UVs map to explicit Metal inputs with identity texture matrices. An active legacy lightmap vertex input is rejected.
- Non-opaque GLSL uniforms become a std140 buffer; the runner supplies dimensions and zero-initializes the remaining state. Removed GLSL uniform initializer defaults are not a general runtime implementation.
- Numeric `#define` settings are applied. Boolean toggles, profile inheritance, shaderpack custom-uniform expressions, and `const` options such as `shadowDistance` are not implemented.
- Includes are expanded before conditional preprocessing. This works for the tested pack's final-pass source tree; macro includes and guarded recursive include graphs need a proper include callback for broader compatibility.
- The runner provides only `colortex3` and a constant `noisetex`. Other active sampled textures are rejected.
- The pixel reference is specific to this pack version's tested camera defaults. Different sharpening/vignette settings require a matching reference before a GPU pass can be claimed.
- No disk cache or shader reload lifecycle is implemented yet.

## Next integration milestone

The local `0.3.0-alpha.2-shaders.1` build implements the first final-image hook, pack menu, settings persistence, background compilation, compiler cache, and resizing lifecycle. It uses the pack's noise PNG and applies the preview before the 2D HUD. The original broader integration checklist follows; the terrain/shadow/composite work remains outstanding.

1. Put the compiler and pack selection behind an explicit opt-in runtime setting, with bounded errors and shader cache keys that include pack contents, options, compiler version, and adapter version.
2. Add a final-image hook before HUD rendering. Use a separate sampled input texture so a pass never samples the render target it writes. Keep siliconshaders's own terrain renderer enabled.
3. Carry actual dimensions and camera state into the reflected buffers. Manage resize, world changes, shader reload, and GPU resource lifetimes on the render thread.
4. Use a diagnostic final shader in-game first. Applying Complementary's final pass to vanilla/siliconshaders color alone does not provide the complete pack's lighting.
5. Integrate the pack's terrain, shadow, deferred, and composite passes in dependency order. Adapt siliconshaders's packed geometry/material data and recheck GPU culling for vertex displacement such as waving foliage.
6. Compare image correctness with an Iris reference, then measure frame times on the same M1 Pro scene. Test Lunar integration after the Fabric development profile works.

Upstream already contains `MetalHooks`, `PassDelegate`, and `MetalBridge`, but these seams alone do not route the custom `OwnTerrain` native draws through arbitrary pack programs. Keeping its optimizations requires explicit work on that path.

## Source provenance

Base: [mcopt](https://github.com/noahdunnagan/mcopt) by Noah Dunnagan, commit `7b1d0fb49b4aa03f0a8ebd31a42f93c5fa1cbc6c` (renderer version 0.3.0-alpha.2). Original mcopt license and notices are retained. This experiment's sources are added under the repository's Apache-2.0 license. Shader packs are read locally and are not part of this source package.
