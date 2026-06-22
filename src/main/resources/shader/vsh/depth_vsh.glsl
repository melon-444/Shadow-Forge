#version 430

layout(location = 0) in vec3 position;
layout(location = 3) in mat4 instanceModel;

uniform mat4 vp;

void main() {
    gl_Position = vp * instanceModel * vec4(position, 1.0);
}
