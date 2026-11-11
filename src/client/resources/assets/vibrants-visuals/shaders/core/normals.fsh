#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:terrainglobals.glsl>
#ifndef MULTIDRAW_TERRAIN
    #include <minecraft:chunksection.glsl>
#endif

uniform sampler2D Sampler0;

layout(location = 3) in vec2 texCoord0;
layout(location = 5) in vec3 viewPos;

layout(location = 0) out vec4 fragColor;

void main() {
    if (texture(Sampler0, texCoord0).a < 0.5) {
        discard;
    }
    // Inside one triangle the derivatives are exact, unlike the ones rebuilt from the depth buffer
    vec3 normal = normalize(cross(dFdx(viewPos), dFdy(viewPos)));
    if (dot(normal, viewPos) > 0.0) {
        normal = -normal;
    }
    fragColor = vec4(normal * 0.5 + 0.5, 1.0);
}
