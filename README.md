# Vibrants Visuals

Fabric mod for **Minecraft Java 26.3** that recreates the **Vibrant Visuals** graphics mode of Minecraft: Bedrock Edition as closely as possible.

> No dependency required.

## Features

| Effect | Option | Where |
|---|---|---|
| Pixelated directional shadows (sun and moon) | Shadows | Vibrants Visuals |
| Colored shadows (slime, honey, ice, stained glass and panes) | part of Shadows | — |
| Shadows of entities, special blocks and the player (first person included) | part of Shadows | — |
| Underwater caustics | Caustics | Vibrants Visuals |
| Volumetric fog | Volumetric Fog | Vibrants Visuals |
| Light shafts | Light Shafts | Vibrants Visuals |
| Bloom | Bloom | Vibrants Visuals |
| ACES tone mapping | Tone Mapping | Vibrants Visuals |
| Cloud shadows | Cloud Shadows | Preferences |
| Light from held light sources | Held Light | Preferences |
| Shadows from nearby light sources (lanterns, torches…) | Dynamic Light | Preferences |
| Colored light, tinted by stained glass | Colored Light | Preferences |

- The **"Vibrants Visuals"** graphics preset sits between *Fabulous* and *Custom*. It applies the *Fabulous* settings, turns on every effect and switches rendering to **Vulkan**. That switch requires a restart.
- Changing an effect by hand sets the preset to *Custom*. The *Fast*, *Fancy* and *Fabulous* presets turn every effect off.
- The Preferences options are never changed by presets. Held Light, Dynamic Light and Colored Light are off by default, for parity with Bedrock.
- Settings are saved in `config/vibrants-visuals.properties`.

### Datapack: held light sources

The items that give off light when held are listed in the item tag `vibrants-visuals:held_light_sources`. A datapack can add items with a file at `data/vibrants-visuals/tags/item/held_light_sources.json`:

```json
{
	"replace": false,
	"values": ["minecraft:magma_block"]
}
```

With `"replace": true`, the datapack replaces the whole list. On a server without the mod, the tag is not sent; in that case any light-emitting block item gives off light.

## How it works

### Overview

The game draws the world as usual. The mod adds the following steps:

1. **Before the main pass** (`LevelRenderer.addMainPass`), it draws the scene as seen from the sun into **shadow maps**. It also draws the opaque terrain from the camera to store exact surface normals.
2. **After the world is drawn, before the hand** (`GameRenderer.renderLevel`), it runs a **full-screen pass**. This pass reads the image and its depth, rebuilds each pixel's world position, then applies every effect (`shaders/post/vibrant_visuals.fsh`).

Everything goes through the game's rendering API (`com.mojang.renderpearl`), so the mod works on both OpenGL and Vulkan.

> In this version, depth is **reversed**: it is cleared to 0, and closer objects have greater values. All of the mod's matrices and comparisons account for this.

### Shadows

**Shadow map** (`render/ShadowRenderer.java`)
- A 4096×4096 depth texture covering 96 blocks around the player. It is seen from the sun with an orthographic projection.
- **Terrain**: the mod reuses the terrain draws the game has already prepared for the frame (`ChunkSectionsToRender.renderLayers`). It draws them with its own pipelines, which only write depth and cull no faces.
- **Entities and block entities** (chests, heads, golems…): the mod replays the opaque and translucent models prepared by the game, through `FeatureRenderDispatcher.PreparedFrame`, with the sun's projection.
- **Translucent models**: they normally don't write depth, and the player's skin is one of them. They are swapped on the fly for a pipeline that does write depth.

**First-person player shadow** (`render/FirstPersonShadow.java`)
- In this view, the game doesn't prepare the player model. The mod prepares it in a separate list that only the shadow pass draws.

**Bedrock-style pixelated look**
- Before the shadow test, each pixel's position is snapped to the face's **1/16 block grid**, so shadow edges follow the texture pixels.
- The shadow map origin is snapped to its own texel grid, and the sun direction moves in **0.5° steps**. Together, these stop shadows from shimmering or drifting.
- The shadow projection clamps light elevation to **10°** for stability; this is a mod-specific choice, not a documented Bedrock default.

**Avoiding shadow acne**
- Several offsets are combined:
  - an offset along the normal, which grows at grazing light angles;
  - a depth bias computed in the shader;
  - a hardware bias in the shadow pipelines.
- **Exact normals** (`render/NormalRenderer.java`): the opaque terrain is drawn a second time from the camera, and each face writes its real orientation. Where it is unavailable (entities, for example), the normal is rebuilt from depth, always using the neighbor that lies on the same surface.
- Shaded areas are darkened and slightly **blue**, since only the sky lights them, as on Bedrock.

**Colored shadows**
- A second color map, 2048×2048, receives selected translucent blocks: slime, honey, ice, stained glass and panes, and water.
- The shader recognizes these blocks by the position of their texture in the atlas.
- Only the surface closest to the sun is kept, so the slime's inner cube doesn't darken the shadow a second time.
- Water doesn't tint the light: it only marks underwater areas for caustics.

**Cloud shadows**
- The shader casts a ray toward the sun up to cloud height, then reads the vanilla `clouds.png` texture.
- It uses the exact same scrolling as the game: 12 blocks per texture pixel and 0.03 blocks per tick. It follows the sun smoothly, without the 0.5° steps.

### Caustics
- They only appear on surfaces that are **underwater** and **lit** by the sun, which includes sand behind a waterfall.
- The pattern is projected from the water surface along the light direction. It fades with depth, like the light, which also turns bluer.
- Bedrock's `caustics.png` is not freely licensed, so the pattern is **generated in the shader**. Two layers of different sizes are blended to hide the repetition.

### Lights (`render/DynamicLights.java`, `render/ColoredLightVolume.java`)
- **Held Light**: entities holding a light source within 32 blocks become a point light that adds light to their surroundings. The vanilla lightmap doesn't know about this light.
- **Dynamic Light**: light-emitting blocks within 12 blocks (up to 8 lights) cast shadows. They are computed in screen space: the shader marches from each surface toward the light through the depth buffer. The march stops before the light's own block, so it doesn't shadow itself. In first person, the player's body is approximated by a capsule.
- **Colored Light**: within a 32-block cube around the camera, colored light spreads like vanilla light, with one level per red, green and blue channel. Stained glass multiplies the light that passes through it. The result is stored in a texture and blended smoothly in the shader. Light colors are guessed from block names (redstone → red, soul → blue, copper → green…), so modded blocks follow the same rules.

### Volumetric fog, light shafts, bloom, tone mapping
- **Fog**: its density depends on altitude and increases with rain. Scattering uses the Henyey–Greenstein phase function with the documented air default, **g = 0.75**. Its conversion into an LDR color blend remains an approximation.
- **Light shafts**: a radial blur starting from the sun's position on screen, keeping only sky pixels.
- **Bloom**: very bright areas spill slightly onto their neighbors.
- **Tone mapping**: a Narkowicz ACES approximation gives a filmic look. Bedrock supports several operators, including its own Generic curve; the documentation does not establish this approximation as its default.
- Debug lines, such as chunk borders, no longer write depth when fog is on. Otherwise the fog would treat them as walls.

## Structure

```
src/client/java/com/lucachinou/client/
├── VibrantsVisualsOptions.java        options and saving
├── render/
│   ├── VibrantsVisualsRenderer.java   full-screen pass and shader parameters
│   ├── ShadowRenderer.java            shadow maps (terrain, entities, colored shadows)
│   ├── NormalRenderer.java            exact terrain normals
│   ├── FirstPersonShadow.java         first-person player shadow
│   ├── DynamicLights.java             held and nearby point lights
│   └── ColoredLightVolume.java        colored light propagation
└── mixin/                             hooks into the game
src/client/resources/assets/vibrants-visuals/shaders/
├── core/shadow.fsh                    shadow map rendering
├── core/normals.vsh, normals.fsh      normal pass
└── post/vibrant_visuals.fsh           every effect
src/main/resources/data/vibrants-visuals/tags/item/
└── held_light_sources.json            held light sources (datapack-editable)
```

## Known limitations
- No PBR textures (relief, reflections, materials).
- No water reflections or Snell's window.
- Only what is loaded around the camera casts sun shadows, within 96 blocks.
- Cloud shadows are flat: they don't reproduce the volume of "Fancy" clouds.
- Dynamic light shadows only see what is on screen. Colored light only reaches 16 blocks around the player.

## Building

```bash
./gradlew build       # the .jar is in build/libs/
./gradlew runClient   # launches the test game
```
