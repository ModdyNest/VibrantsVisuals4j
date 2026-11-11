#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:terrainglobals.glsl>
#ifndef MULTIDRAW_TERRAIN
    #include <minecraft:chunksection.glsl>
#endif

uniform sampler2D Sampler0;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;
layout(location = 4) in float chunkVisibility;

#ifdef TRANSLUCENT_CASTERS
// Must match ShadowRenderer.CASTER_TEXTURES: 2 water sprites, slime, honey (x3), ice, 16 stained glass + 16 pane edges
const int CASTER_COUNT = 39;
const int WATER_CASTERS = 2;

layout(std140) uniform VibrantsShadowCasters {
    vec4 CasterRects[CASTER_COUNT];
};

layout(location = 0) out vec4 fragColor;
#endif

void main() {
    vec4 tex = texture(Sampler0, texCoord0);
#ifdef TRANSLUCENT_CASTERS
    int caster = -1;
    for (int i = 0; i < CASTER_COUNT; i++) {
        vec4 rect = CasterRects[i];
        if (all(greaterThanEqual(texCoord0, rect.xy)) && all(lessThanEqual(texCoord0, rect.zw))) {
            caster = i;
        }
    }
    if (caster < 0) {
        discard;
    }
    // Water lets the light through untinted, alpha 0 flags it for caustics
    if (caster < WATER_CASTERS) {
        fragColor = vec4(1.0, 1.0, 1.0, 0.0);
        return;
    }
    if (tex.a < 0.05) {
        discard;
    }
    // Light passing through keeps the block's color, more strongly the more opaque the texture is
    fragColor = vec4(mix(vec3(1.0), tex.rgb, tex.a), 1.0);
#else
    if (tex.a < 0.5) {
        discard;
    }
#endif
}
