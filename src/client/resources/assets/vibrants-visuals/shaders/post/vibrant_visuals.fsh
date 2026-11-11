#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:globals.glsl>

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;
uniform sampler2D ShadowSampler;
uniform sampler2D TintSampler;
uniform sampler2D TintDepthSampler;
uniform sampler2D CloudSampler;
uniform sampler2D NormalSampler;
uniform sampler2D NormalDepthSampler;
uniform sampler2D ColorLightSampler;

layout(std140) uniform VibrantsConfig {
    mat4 InvViewProj;
    mat4 ShadowMat;
    ivec4 ShadowInfo;   // x: shadow map available
    vec4 ShadowParams;  // x: covered radius in blocks, y: shadow map texel size in blocks
    vec4 LightDir;      // xyz: direction to the sun (or moon), w: strength
    vec4 LightScreen;   // xy: light position in UV, z: light in front of camera, w: is day
    vec4 FogTint;       // rgb: vanilla fog color
    vec4 Params;        // x: camera Y, y: rain, z: zZeroToOne, w: far plane
    ivec4 Toggles;      // x: volumetric fog, y: light shafts, z: bloom, w: tone mapping
    vec4 CloudParams;   // xy: camera position in the scrolling cloud texture (blocks), z: cloud height, w: enabled
    vec4 CausticsParams; // x: time in seconds, y: enabled, zw: camera X/Z wrapped to 256 blocks
    vec4 CloudLightDir;  // xyz: unstepped light direction, the stepped one makes cloud shadows jump, w: exact terrain normals available
    mat4 ViewProj;
    vec4 PointLights[16]; // pairs: (camera-relative position, radius) then (color, 1 = held light / 0 = block light)
    vec4 PointLightInfo;  // x: light count, y: dynamic shadows enabled, z: first person capsule, w: eye height
    vec4 GlassTints[48];  // per light: stained glass color met towards +X, -X, +Y, -Y, +Z, -Z
    vec4 ColoredVolume;   // xyz: colored light volume corner relative to the camera, w: enabled
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const int SHAFT_SAMPLES = 32;

vec3 worldPos(vec2 uv, float depth) {
    float ndcZ = Params.z > 0.5 ? depth : depth * 2.0 - 1.0;
    vec4 pos = InvViewProj * vec4(uv * 2.0 - 1.0, ndcZ, 1.0);
    return pos.xyz / pos.w;
}

float luminance(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// Bedrock's Vibrant Visuals shadows are aligned on the 16x16 texel grid of each block face
const float PIXELS_PER_BLOCK = 16.0;

// Bedrock water defaults: caustics scale 0.5 and power 1. One tile spans 4 blocks, which divides the 256 block wrap.
const float CAUSTICS_TILE = 4.0;
const float TAU = 6.28318530718;

// Tileable water caustic pattern (after Dave Hoskins), bright thin lines on a dark background
float causticPattern(vec2 uv, float time) {
    vec2 p = mod(uv * TAU, TAU) - 250.0;
    vec2 i = p;
    float c = 1.0;
    const float inten = 0.005;
    for (int n = 0; n < 5; n++) {
        float t = time * (1.0 - (3.5 / float(n + 1)));
        i = p + vec2(cos(t - i.x) + sin(t + i.y), sin(t - i.y) + cos(t + i.x));
        c += 1.0 / length(vec2(p.x / (sin(i.x + t) / inten), p.y / (cos(i.y + t) / inten)));
    }
    c /= 5.0;
    c = 1.17 - pow(c, 1.4);
    return clamp(pow(abs(c), 8.0), 0.0, 1.0);
}

// The pattern is projected from the water surface along the light, so it stays still on the ground when the sun moves slowly
float caustics(vec3 relPos, float waterDepth) {
    vec3 lightDir = normalize(LightDir.xyz);
    vec3 world = vec3(relPos.x + CausticsParams.z, Params.x + relPos.y, relPos.z + CausticsParams.w);
    vec2 projected = world.xz - lightDir.xz / max(lightDir.y, 0.1) * world.y;
    float a = causticPattern(projected / CAUSTICS_TILE, CausticsParams.x * 0.6);
    float b = causticPattern(projected.yx / 6.4 + 0.37, CausticsParams.x * 0.45 + 17.0);
    return clamp((a + b) * 0.75, 0.0, 1.0) * exp(-waterDepth * 0.08);
}

// Clouds block the light where the ray towards the sun crosses the cloud layer
float cloudShadow(vec3 pos) {
    if (CloudParams.w < 0.5) {
        return 1.0;
    }
    vec3 lightDir = normalize(CloudLightDir.xyz);
    float worldY = Params.x + pos.y;
    float t = (CloudParams.z - worldY) / max(lightDir.y, 0.05);
    if (t < 0.0) {
        return 1.0;
    }
    vec2 cloudPos = CloudParams.xy + pos.xz + lightDir.xz * t;
    float cloud = texture(CloudSampler, cloudPos / (12.0 * vec2(textureSize(CloudSampler, 0)))).a;
    return mix(1.0, 0.55, step(0.5, cloud));
}

vec3 shadowFactor(vec3 pos, vec3 normal, float nDotL, bool axisAligned) {
    float clouds = cloudShadow(pos);
    // pos is relative to the camera; CameraOffset gives the camera position inside its block,
    // so pos - CameraOffset is relative to an integer block corner and can be snapped directly
    vec3 blockRel = pos - CameraOffset;
    vec3 inside = blockRel - normal * (0.5 / PIXELS_PER_BLOCK);
    vec3 snapped = axisAligned ? (floor(inside * PIXELS_PER_BLOCK) + 0.5) / PIXELS_PER_BLOCK : blockRel;
    // Pixel-snapped position, so caustics follow the 16x16 texel grid like the shadows
    vec3 pixelPos = snapped + CameraOffset;
    // Move the sample back on the surface, then outwards along the normal (normal offset) to avoid self-shadowing.
    // The offset grows with the light angle since grazing light spreads one shadow texel over a longer distance.
    float tanTheta = clamp(sqrt(max(1.0 - nDotL * nDotL, 0.0)) / max(nDotL, 0.05), 0.0, 8.0);
    float normalOffset = 0.04 + ShadowParams.y * 1.5 * tanTheta;
    snapped += normal * (dot(normal, blockRel - snapped) + normalOffset);
    vec3 samplePos = snapped + CameraOffset;

    vec4 clip = ShadowMat * vec4(samplePos, 1.0);
    vec3 ndc = clip.xyz / clip.w;
    vec2 uv = ndc.xy * 0.5 + 0.5;
    if (uv.x <= 0.0 || uv.x >= 1.0 || uv.y <= 0.0 || uv.y >= 1.0) {
        return vec3(clouds);
    }

    float depth = Params.z > 0.5 ? ndc.z : ndc.z * 0.5 + 0.5;
    // Slope-scaled depth bias, expressed in blocks then converted to depth units (ShadowParams.z = depth range)
    float bias = (0.03 + ShadowParams.y * 2.0 * tanTheta) / ShadowParams.z;
    // Reversed depth: a greater stored value means an occluder closer to the light
    float lit = texture(ShadowSampler, uv).r > depth + bias ? 0.0 : 1.0;

    vec3 light = vec3(lit);
    // Slime, honey and ice let tinted light through instead of fully blocking it
    float tintDepth = texture(TintDepthSampler, uv).r;
    if (lit > 0.5 && tintDepth > depth + bias) {
        vec4 tint = texture(TintSampler, uv);
        if (tint.a < 0.5) {
            // Under water: light focused by the surface waves
            if (CausticsParams.y > 0.5) {
                float waterDepth = (tintDepth - depth) * ShadowParams.z;
                // Sunlight loses strength and red first through the water column
                light *= mix(vec3(0.30, 0.55, 0.70), vec3(1.0), exp(-waterDepth * 0.12));
                light *= 1.0 + 3.0 * caustics(pixelPos, waterDepth);
            }
        } else {
            light = tint.rgb;
        }
    }

    float edgeFade = smoothstep(0.8, 1.0, length(pos.xz) / ShadowParams.x);
    return mix(light, vec3(1.0), edgeFade) * clouds;
}

// ACES fitted curve (Narkowicz), close to the filmic look of Bedrock's Vibrant Visuals
vec3 aces(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

vec3 sceneLight() {
    float day = LightScreen.w;
    vec3 sun = mix(vec3(1.0, 0.62, 0.35), vec3(1.0, 0.94, 0.82), clamp(LightDir.y * 4.0, 0.0, 1.0));
    vec3 moon = vec3(0.55, 0.65, 1.0);
    return mix(moon, sun, day) * LightDir.w;
}

vec3 bloom(vec2 uv) {
    vec2 texel = 1.0 / ScreenSize;
    vec3 sum = vec3(0.0);
    float weights = 0.0;
    for (int x = -3; x <= 3; x++) {
        for (int y = -3; y <= 3; y++) {
            vec2 offset = vec2(x, y) * texel * 3.0;
            vec3 c = texture(InSampler, uv + offset).rgb;
            float w = exp(-float(x * x + y * y) / 8.0);
            sum += max(c - 0.7, 0.0) * w;
            weights += w;
        }
    }
    return sum / weights;
}

vec3 surfaceNormal(vec3 pos, vec3 viewDir, out bool axisAligned) {
    // Derivatives across a depth edge (leaf holes, block silhouettes) give a wrong normal and a dark outline,
    // so each axis uses the neighbour lying on the same surface
    vec2 texel = 1.0 / vec2(textureSize(DepthSampler, 0));
    vec3 normal;
    // Exact normal written by the terrain, valid only where the terrain is the visible surface
    float depth = texture(DepthSampler, texCoord).r;
    float normalDepth = texture(NormalDepthSampler, texCoord).r;
    vec4 stored = texture(NormalSampler, texCoord);
    if (CloudLightDir.w > 0.5 && stored.a > 0.5 && abs(normalDepth - depth) <= depth * 1.0e-4) {
        normal = normalize(stored.xyz * 2.0 - 1.0);
    } else {
    vec3 px = worldPos(texCoord + vec2(texel.x, 0.0), texture(DepthSampler, texCoord + vec2(texel.x, 0.0)).r);
    vec3 nx = worldPos(texCoord - vec2(texel.x, 0.0), texture(DepthSampler, texCoord - vec2(texel.x, 0.0)).r);
    vec3 py = worldPos(texCoord + vec2(0.0, texel.y), texture(DepthSampler, texCoord + vec2(0.0, texel.y)).r);
    vec3 ny = worldPos(texCoord - vec2(0.0, texel.y), texture(DepthSampler, texCoord - vec2(0.0, texel.y)).r);
    vec3 dx = length(px - pos) < length(pos - nx) ? px - pos : pos - nx;
    vec3 dy = length(py - pos) < length(pos - ny) ? py - pos : pos - ny;
    normal = normalize(cross(dx, dy));
    if (dot(normal, viewDir) > 0.0) {
        normal = -normal;
    }
    }
    // Block faces are axis-aligned: snapping the noisy reconstructed normal to its dominant axis makes it exact,
    // which removes most of the shadow acne caused by imprecise depth derivatives
    // Tilted surfaces (lever handles, chains, entities) keep their real normal
    vec3 absNormal = abs(normal);
    axisAligned = max(absNormal.x, max(absNormal.y, absNormal.z)) > 0.95;
    if (!axisAligned) {
    } else if (absNormal.x > absNormal.y && absNormal.x > absNormal.z) {
        normal = vec3(sign(normal.x), 0.0, 0.0);
    } else if (absNormal.y > absNormal.z) {
        normal = vec3(0.0, sign(normal.y), 0.0);
    } else {
        normal = vec3(0.0, 0.0, sign(normal.z));
    }
    return normal;
}

// Screen-space shadow: march towards the light through the depth buffer (cheap, no extra shadow maps)
// Shortest distance between segments [a0,a1] and [b0,b1]
float segmentDistance(vec3 a0, vec3 a1, vec3 b0, vec3 b1) {
    vec3 u = a1 - a0, v = b1 - b0, w = a0 - b0;
    float a = dot(u, u), b = dot(u, v), c = dot(v, v), d = dot(u, w), e = dot(v, w);
    float den = max(a * c - b * b, 1.0e-6);
    float s = clamp((b * e - c * d) / den, 0.0, 1.0);
    float t = clamp((b * s + e) / c, 0.0, 1.0);
    s = clamp((b * t - d) / a, 0.0, 1.0);
    return length(w + u * s - v * t);
}

float pointShadow(vec3 pos, vec3 toLight) {
    // First person player body, approximated by a capsule below the camera
    if (PointLightInfo.z > 0.5) {
        vec3 feet = vec3(0.0, -PointLightInfo.w + 0.1, 0.0), head = vec3(0.0, -0.15, 0.0);
        vec3 lightPos = pos + toLight;
        bool lightOutside = segmentDistance(lightPos, lightPos, feet, head) > 0.4;
        if (lightOutside && segmentDistance(pos, lightPos, feet, head) < 0.3) {
            return 0.0;
        }
    }
    // Stop before the light's own block, so its model doesn't shadow its own light
    float reach = max(1.0 - 0.75 / length(toLight), 0.0);
    const int STEPS = 16;
    for (int i = 1; i < STEPS; i++) {
        vec3 p = pos + toLight * (reach * float(i) / float(STEPS));
        vec4 clip = ViewProj * vec4(p, 1.0);
        if (clip.w <= 0.0) {
            return 1.0;
        }
        vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
        if (uv.x <= 0.0 || uv.x >= 1.0 || uv.y <= 0.0 || uv.y >= 1.0) {
            return 1.0;
        }
        float sceneDepth = texture(DepthSampler, uv).r;
        float gap = length(p) - length(worldPos(uv, max(sceneDepth, 1.0e-6)));
        if (gap > 0.08 && gap < 1.5) {
            return 0.0;
        }
    }
    return 1.0;
}

vec3 colorLightCell(ivec3 c) {
    if (any(lessThan(c, ivec3(0))) || any(greaterThanEqual(c, ivec3(32)))) {
        return vec3(0.0);
    }
    return texelFetch(ColorLightSampler, ivec2(c.x + c.y * 32, c.z), 0).rgb;
}

// Smoothly interpolated colored light, read in the air just in front of the surface
vec3 coloredLight(vec3 color, vec3 pos, vec3 normal) {
    vec3 p = pos + normal * 0.5 - ColoredVolume.xyz - 0.5;
    ivec3 base = ivec3(floor(p));
    vec3 f = fract(p);
    vec3 light = vec3(0.0);
    for (int i = 0; i < 8; i++) {
        ivec3 o = ivec3(i & 1, (i >> 1) & 1, (i >> 2) & 1);
        vec3 w = mix(1.0 - f, f, vec3(o));
        light += colorLightCell(base + o) * (w.x * w.y * w.z);
    }
    float level = max(light.r, max(light.g, light.b));
    if (level < 0.02) {
        return color;
    }
    // The lightmap already holds a warm torch color, so only the hue relative to it is applied
    vec3 rel = light / vec3(1.0, 0.78, 0.52);
    rel /= max(rel.r, max(rel.g, rel.b));
    float saturation = 1.0 - min(rel.r, min(rel.g, rel.b));
    return color * mix(vec3(1.0), rel * (1.0 + 0.5 * saturation), clamp(level * 1.2, 0.0, 1.0) * 0.8);
}

vec3 pointLights(vec3 color, vec3 pos, vec3 normal) {
    vec3 result = color;
    for (int i = 0; i < int(PointLightInfo.x); i++) {
        vec4 lightPos = PointLights[i * 2];
        vec4 lightColor = PointLights[i * 2 + 1];
        vec3 toLight = lightPos.xyz - pos;
        float d = length(toLight);
        float atten = clamp(1.0 - d / lightPos.w, 0.0, 1.0);
        if (atten <= 0.0) {
            continue;
        }
        atten *= atten;
        float nDotL = max(dot(normal, toLight / d), 0.0);
        float shadow = PointLightInfo.y > 0.5 && d > 0.6 ? pointShadow(pos + normal * 0.02, toLight) : 1.0;
        // Stained glass between the light and this surface, picked from the main direction
        vec3 dir = -toLight / d;
        vec3 w = dir * dir;
        int g = i * 6;
        vec3 glass = w.x * GlassTints[g + (dir.x > 0.0 ? 0 : 1)].rgb
                   + w.y * GlassTints[g + (dir.y > 0.0 ? 2 : 3)].rgb
                   + w.z * GlassTints[g + (dir.z > 0.0 ? 4 : 5)].rgb;
        vec3 tint = lightColor.rgb * glass;
        if (lightColor.w > 0.5 && lightColor.w < 1.5) {
            // Held light: the vanilla lightmap doesn't know about it, so it adds light
            result += color * tint * (1.6 * atten * nDotL * shadow);
        } else {
            if (lightColor.w > 1.5) {
                // Colored light: the lightmap is already warm, so recolor relative to the warm torch color
                vec3 rel = tint / vec3(1.0, 0.78, 0.52);
                rel /= max(max(rel.r, max(rel.g, rel.b)), 1.0e-3);
                float k = atten * max(nDotL, 0.35) * mix(0.3, 1.0, shadow) * 0.8;
                result *= mix(vec3(1.0), rel * 1.35, k);
            }
            // Placed light: already in the lightmap, only the occluded or turned-away side is darkened
            result *= PointLightInfo.y < 0.5 ? 1.0 : mix(1.0, 0.55, atten * (1.0 - min(nDotL * 1.5, 1.0) * shadow));
        }
    }
    return result;
}

void main() {
    vec3 color = texture(InSampler, texCoord).rgb;
    float depth = texture(DepthSampler, texCoord).r;
    bool isSky = depth <= 0.0;

    vec3 pos = worldPos(texCoord, isSky ? 1.0e-6 : depth);
    float dist = isSky ? Params.w : length(pos);
    vec3 viewDir = normalize(pos);
    vec3 light = sceneLight();

    bool axisAligned = false;
    vec3 normal = isSky ? vec3(0.0, 1.0, 0.0) : surfaceNormal(pos, viewDir, axisAligned);

    // Directional shadows: occlusion from the shadow map plus faces turned away from the light
    if (ShadowInfo.x == 1 && !isSky) {
        float nDotL = dot(normal, normalize(LightDir.xyz));
        vec3 lit = nDotL <= 0.0 ? vec3(0.0) : shadowFactor(pos, normal, nDotL, axisAligned);
        // Bedrock's shaded areas are lit by the sky only, so they turn darker and slightly blue instead of plain grey
        vec3 shadowColor = color * mix(vec3(1.0), vec3(0.50, 0.56, 0.70), LightDir.w);
        color = mix(shadowColor, color, lit);
    }

    if (PointLightInfo.x > 0.5 && !isSky) {
        color = pointLights(color, pos, normal);
    }
    if (ColoredVolume.w > 0.5 && !isSky) {
        color = coloredLight(color, pos, normal);
    }

    // Bedrock-like volumetric fog: height-based density, scattering towards the light source
    if (Toggles.x == 1) {
        float density = mix(0.0016, 0.006, Params.y);
        float heightFalloff = 0.045;
        float camHeight = Params.x - 62.0;
        float dy = viewDir.y * dist;
        float heightTerm = abs(dy) > 0.01
            ? (exp(-heightFalloff * camHeight) - exp(-heightFalloff * (camHeight + dy))) / (heightFalloff * dy)
            : exp(-heightFalloff * camHeight);
        float opticalDepth = density * dist * clamp(heightTerm, 0.0, 4.0);
        float transmittance = exp(-opticalDepth);
        float cosTheta = dot(viewDir, normalize(LightDir.xyz));
        float phase = 0.4 + 0.6 * pow(max(cosTheta, 0.0), 6.0);
        vec3 fogColor = mix(FogTint.rgb, FogTint.rgb * 0.6 + light * 0.4, phase);
        if (!isSky) {
            color = mix(fogColor, color, transmittance);
        } else {
            color = mix(color, fogColor, (1.0 - transmittance) * 0.35);
        }
    }

    // Light shafts (god rays): radial occlusion march towards the sun/moon in screen space
    if (Toggles.y == 1 && LightScreen.z > 0.5) {
        vec2 lightUv = LightScreen.xy;
        vec2 delta = (lightUv - texCoord) / float(SHAFT_SAMPLES);
        vec2 uv = texCoord + delta * hash(texCoord * ScreenSize + GameTime * 1000.0);
        float lit = 0.0;
        float decay = 1.0;
        for (int i = 0; i < SHAFT_SAMPLES; i++) {
            uv += delta;
            if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
                break;
            }
            lit += (texture(DepthSampler, uv).r <= 0.0 ? 1.0 : 0.0) * decay;
            decay *= 0.96;
        }
        lit /= float(SHAFT_SAMPLES);
        float facing = max(dot(viewDir, normalize(LightDir.xyz)), 0.0);
        float edge = 1.0 - smoothstep(0.6, 1.4, length((texCoord - lightUv) * vec2(ScreenSize.x / ScreenSize.y, 1.0)));
        color += light * lit * pow(facing, 3.0) * edge * 0.55;
    }

    if (Toggles.z == 1) {
        color += bloom(texCoord) * 0.8;
    }

    if (Toggles.w == 1) {
        color = aces(color * 1.15);
        float l = luminance(color);
        color = max(mix(vec3(l), color, 1.12), 0.0);
    }

    fragColor = vec4(color, 1.0);
}
