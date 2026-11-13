# Bedrock shader reference

Source: [Microsoft's Vibrant Visuals creator documentation](https://github.com/MicrosoftDocs/minecraft-creator/tree/main/creator/Documents/VibrantVisuals), consulted on 2026-11-13.

## Values applied

- [Lighting](https://github.com/MicrosoftDocs/minecraft-creator/blob/main/creator/Documents/VibrantVisuals/LightingCustomization.md): torch and candles `#EFE39D`, redstone torch `#FF0000`, end rod and sea pickle `#FFFFFF`, lantern `#CE8133`, soul torch and lantern `#00FFFF`, copper torch and lantern `#B8EF8D`. These values replace guessed RGB colors when Colored Light is enabled. The mod's RGB propagation and Java's existing lightmap still prevent exact radiometric parity.
- [Fog](https://github.com/MicrosoftDocs/minecraft-creator/blob/main/creator/Documents/FogInResourcePacks.md#henyey-greenstein-g): Henyey–Greenstein air anisotropy `g = 0.75`. Water's documented `g = 0.6` is not applied because this pass does not implement underwater volume integration. The phase function is normalized, then compressed into the existing LDR fog-color blend.
- [Shadows](https://github.com/MicrosoftDocs/minecraft-creator/blob/main/creator/Documents/VibrantVisuals/ShadowsCustomization.md): the blocky shadow texel size defaults to **16**, already used by the mod. The documentation says **soft** shadows are the fallback when no style is supplied; the mod intentionally retains its existing blocky style.

## Values deliberately not copied

- Fog density `0.01`, density heights `50 / 150`, and medium coefficients in the volumetric fog article belong to an **example configuration**, not declared universal defaults. They cannot directly replace this mod's exponential extinction coefficients.
- Sun illuminance in lux requires HDR lighting and auto-exposure; copying it into the existing LDR post pass would not reproduce Bedrock. Moon strength remains unchanged as requested.
- The color-grading examples (`contrast = 1.3`, `saturation = 1.05`, Generic/Reinhard Luminance operators) are examples, not universal defaults. Neutral saturation/gain are `1`, offset is `0`, standard gamma is `2.2`, and default temperature is `6500 K`. Applying gamma again to Java's already rendered image would risk double correction. Existing artistic grading is retained.
- The docs list ACES among supported operators but do not provide Mojang's exact implementation or Generic curve coefficients. The Narkowicz approximation remains.
- Water caustic scale is inverse in size, power controls brightness, and the built-in texture has 64 frames. The article does not give universal scale, power or frame-duration defaults. This mod uses a procedural pattern, not that sprite sheet; its tile sizes, speeds and intensity remain artistic approximations (including the user's brighter caustics adjustment).
- Bloom intensity/threshold, shadow bias, map resolution, solar stepping, shadow elevation clamp and cloud-shadow opacity are not specified by these articles. Existing implementation-specific values remain unchanged.

This is a parameter alignment where the rendering models allow it, not a claim of pixel-identical Bedrock rendering.
