#version 150

uniform sampler2D DiffuseSampler;

in vec2 texCoord;
out vec4 fragColor;

vec4 sulfur(vec4 c) {
    float l = dot(c.rgb, vec3(0.299, 0.587, 0.114));
    return vec4(mix(c.rgb, mix(vec3(0.10, 0.07, 0.0), vec3(0.98, 0.88, 0.35), l), 0.6), 1.0);
}

void main() {
    vec4 c = texture(DiffuseSampler, texCoord);
    fragColor = vec4(c.rgb, 1.0);
}
