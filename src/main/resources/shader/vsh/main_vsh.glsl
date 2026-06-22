#version 430


layout(location = 0) in vec3 position;
layout(location = 1) in vec2 uv;
layout(location = 2) in vec3 normal;
layout(location = 3) in mat4 instanceModel;

out vec2 a_uv;
out vec3 fragWorldPos;
out vec3 fragWorldNormal;

uniform mat4 vp;

void main() {
    vec4 worldPos = instanceModel * vec4(position, 1.0);
    gl_Position = vp * worldPos;
    fragWorldPos = worldPos.xyz;
    fragWorldNormal = normalize(mat3(instanceModel) * normal);
    a_uv = uv;
}
