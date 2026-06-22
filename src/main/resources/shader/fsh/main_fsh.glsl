#version 430

#define MAX_LIGHTS 16
#define LIGHT_DIRECTIONAL 0
#define LIGHT_POINT 1
#define LIGHT_SPOT 2

in vec2 a_uv;
in vec3 fragWorldPos;
in vec3 fragWorldNormal;

out vec4 fragColor;

uniform sampler2D textureSampler;
uniform sampler2DArray shadowMapArray;
uniform sampler2DArray textureArray;
uniform int textureLayer;

uniform vec3 ambientColor;
uniform float shadowMapSize;

uniform int lightCount;
uniform vec4 lightColor[MAX_LIGHTS];
uniform vec4 lightDir[MAX_LIGHTS];
uniform vec4 lightPos[MAX_LIGHTS];
uniform vec4 lightParams[MAX_LIGHTS];
uniform mat4 lightSpaceMatrices[MAX_LIGHTS];

void main() {
    vec3 N = normalize(fragWorldNormal);
    vec3 colorSum = vec3(0.0);

    for (int i = 0; i < lightCount && i < MAX_LIGHTS; i++) {
        int lightType = int(lightParams[i].x);
        vec3 lColor = lightColor[i].rgb;
        float intensity = lightColor[i].a;
        vec3 L;
        float attenuation = intensity;

        if (lightType == LIGHT_DIRECTIONAL) {
            L = normalize(-lightDir[i].xyz);
        } else if (lightType == LIGHT_POINT) {
            vec3 toLight = lightPos[i].xyz - fragWorldPos;
            float dist = length(toLight);
            L = toLight / dist;
            attenuation = intensity / (1.0 + 0.05 * dist + 0.005 * dist * dist);
        } else if (lightType == LIGHT_SPOT) {
            vec3 toLight = lightPos[i].xyz - fragWorldPos;
            float dist = length(toLight);
            L = toLight / dist;
            float theta = dot(-L, normalize(lightDir[i].xyz));
            float innerCutOff = lightPos[i].w;
            float outerCutOff = lightParams[i].y;
            float spot;
            if (innerCutOff <= outerCutOff) {
                spot = smoothstep(outerCutOff, innerCutOff, theta);
            } else {
                spot = step(outerCutOff, theta);
            }
            attenuation = intensity * spot / (1.0 + 0.05 * dist + 0.005 * dist * dist);
        }

        float diff = max(dot(N, L), 0.0);

        float shadow = 1.0;
        bool hasShadow = lightParams[i].z > 0.5;
        if (hasShadow) {
            vec4 lsPos = lightSpaceMatrices[i] * vec4(fragWorldPos, 1.0);
            vec3 proj = lsPos.xyz / lsPos.w;
            vec2 shadowUV = proj.xy * 0.5 + 0.5;
            if (shadowUV.x >= 0.0 && shadowUV.x <= 1.0 && shadowUV.y >= 0.0 && shadowUV.y <= 1.0) {
                float layer = lightParams[i].w;
                float texelSize = 1.0 / shadowMapSize;
                float bias = 0.002;
                shadow = 0.0;
                //PCF sampling
                for (int x = -2; x <= 2; ++x) {
                    for (int y = -2; y <= 2; ++y) {
                        float pcfClosest = texture(shadowMapArray, vec3(shadowUV + vec2(x, y) * texelSize, layer)).r;
                        if (proj.z >= pcfClosest - bias) {
                            shadow += 1.0;
                        }
                    }
                }
                shadow /= 9.0;
            }
        }

        colorSum += lColor * diff * attenuation * shadow;
    }

    colorSum += ambientColor;
    vec4 texColor;
    if (textureLayer >= 0) {
        texColor = texture(textureArray, vec3(a_uv, float(textureLayer)));
    } else {
        texColor = texture(textureSampler, a_uv);
    }
    fragColor = texColor * vec4(colorSum, 1.0);
    //fragColor = vec4(1.0,0.0,0.0,1.0);
    //fragColor = texColor;
    //fragColor = vec4(ambientColor, 1.0);
    //fragColor = vec4(colorSum, 1.0);
}
