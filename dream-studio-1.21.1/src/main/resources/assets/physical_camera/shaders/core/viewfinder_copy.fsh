#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D ViewColour;

#ifdef VIEW_DEPTH
uniform sampler2D ViewDepth;
#endif

layout(std140) uniform ViewfinderCrop {
    vec4 Region;
    vec4 DepthViewport;
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColour;

void main() {
    vec2 uv = Region.xy + texCoord * Region.zw;
    fragColour = texture(ViewColour, uv);
#ifdef VIEW_DEPTH
    vec2 halfTexel = vec2(0.5) / vec2(textureSize(ViewDepth, 0));
    vec2 depthUv = clamp(uv * DepthViewport.xy + DepthViewport.zw,
                         halfTexel, DepthViewport.xy - halfTexel);
    gl_FragDepth = texture(ViewDepth, depthUv).r;
#endif
}
