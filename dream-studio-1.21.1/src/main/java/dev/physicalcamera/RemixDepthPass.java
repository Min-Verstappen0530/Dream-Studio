package dev.physicalcamera;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL33;

final class RemixDepthPass implements AutoCloseable {
    private static final String VERTEX = """
            #version 150
            void main() {
                vec2 position = vec2(float((gl_VertexID << 1) & 2),float(gl_VertexID & 2));
                gl_Position = vec4(position * 2.0 - 1.0,0.0,1.0);
            }
            """;
    private static final String FRAGMENT = """
            #version 150
            uniform sampler2D RemixDepth;
            uniform vec2 FrameSize;
            uniform int HaveDepth;
            void main() {
                if (HaveDepth == 0) {
                    gl_FragDepth = 1.0;
                } else {
                    ivec2 size = textureSize(RemixDepth,0);
                    vec2 uv = gl_FragCoord.xy / FrameSize;
                    // Remix's interop image starts at the top of the frame.
                    uv.y = 1.0 - uv.y;
                    ivec2 texel = clamp(ivec2(uv * vec2(size)),ivec2(0),size - 1);
                    gl_FragDepth = texelFetch(RemixDepth,texel,0).r;
                }
            }
            """;

    private RenderTarget frame;
    private int program, array, uniformSize, uniformHaveDepth;

    RenderTarget copy(RenderTarget source, int depthTexture) {
        int readFramebuffer = GL33.glGetInteger(GL33.GL_READ_FRAMEBUFFER_BINDING);
        int drawFramebuffer = GL33.glGetInteger(GL33.GL_DRAW_FRAMEBUFFER_BINDING);
        int oldProgram = GL33.glGetInteger(GL33.GL_CURRENT_PROGRAM);
        int oldArray = GL33.glGetInteger(GL33.GL_VERTEX_ARRAY_BINDING);
        int textureUnit = GL33.glGetInteger(GL33.GL_ACTIVE_TEXTURE);
        int depthFunction = GL33.glGetInteger(GL33.GL_DEPTH_FUNC);
        boolean test = GL33.glIsEnabled(GL33.GL_DEPTH_TEST);
        boolean writeDepth = GL33.glGetBoolean(GL33.GL_DEPTH_WRITEMASK);
        boolean scissor = GL33.glIsEnabled(GL33.GL_SCISSOR_TEST);
        boolean cull = GL33.glIsEnabled(GL33.GL_CULL_FACE);

        // Target allocation can touch the viewport and texture bindings.
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var oldViewport = stack.mallocInt(4);
            GL33.glGetIntegerv(GL33.GL_VIEWPORT,oldViewport);
            if (frame == null) frame = new TextureTarget(source.width,source.height,true,Minecraft.ON_OSX);
            else if (frame.width != source.width || frame.height != source.height) {
                frame.resize(source.width,source.height,Minecraft.ON_OSX);
            }
            if (program == 0) createProgram();

            GL33.glActiveTexture(GL33.GL_TEXTURE0);
            int texture = GL33.glGetInteger(GL33.GL_TEXTURE_BINDING_2D);
            try {
                GL33.glDisable(GL33.GL_SCISSOR_TEST);
                GL33.glBindFramebuffer(GL33.GL_READ_FRAMEBUFFER,source.frameBufferId);
                GL33.glBindFramebuffer(GL33.GL_DRAW_FRAMEBUFFER,frame.frameBufferId);
                GL33.glBlitFramebuffer(0,0,source.width,source.height,0,0,frame.width,frame.height,
                        GL33.GL_COLOR_BUFFER_BIT,GL33.GL_NEAREST);
                GL33.glDrawBuffer(GL33.GL_NONE);
                GL33.glViewport(0,0,frame.width,frame.height);
                GL33.glEnable(GL33.GL_DEPTH_TEST);
                GL33.glDisable(GL33.GL_CULL_FACE);
                GL33.glDepthFunc(GL33.GL_ALWAYS);
                GL33.glDepthMask(true);
                GL33.glBindTexture(GL33.GL_TEXTURE_2D,depthTexture);
                GL33.glUseProgram(program);
                GL33.glUniform2f(uniformSize,frame.width,frame.height);
                GL33.glUniform1i(uniformHaveDepth,depthTexture > 0 ? 1 : 0);
                GL33.glBindVertexArray(array);
                GL33.glDrawArrays(GL33.GL_TRIANGLES,0,3);
            } finally {
                GL33.glDrawBuffer(GL33.GL_COLOR_ATTACHMENT0);
                GL33.glBindTexture(GL33.GL_TEXTURE_2D,texture);
                GL33.glActiveTexture(textureUnit);
                GL33.glBindVertexArray(oldArray);
                GL33.glUseProgram(oldProgram);
                GL33.glViewport(oldViewport.get(0),oldViewport.get(1),oldViewport.get(2),oldViewport.get(3));
                GL33.glDepthMask(writeDepth);
                GL33.glDepthFunc(depthFunction);
                if (!test) GL33.glDisable(GL33.GL_DEPTH_TEST);
                if (scissor) GL33.glEnable(GL33.GL_SCISSOR_TEST);
                if (cull) GL33.glEnable(GL33.GL_CULL_FACE);
            }
        } finally {
            GL33.glBindFramebuffer(GL33.GL_READ_FRAMEBUFFER,readFramebuffer);
            GL33.glBindFramebuffer(GL33.GL_DRAW_FRAMEBUFFER,drawFramebuffer);
        }
        return frame;
    }

    private void createProgram() {
        int vertex = compile(GL33.GL_VERTEX_SHADER,VERTEX);
        int fragment = 0;
        int candidate = GL33.glCreateProgram();
        try {
            fragment = compile(GL33.GL_FRAGMENT_SHADER,FRAGMENT);
            GL33.glAttachShader(candidate,vertex);
            GL33.glAttachShader(candidate,fragment);
            GL33.glLinkProgram(candidate);
            if (GL33.glGetProgrami(candidate,GL33.GL_LINK_STATUS) == GL33.GL_FALSE) {
                throw new IllegalStateException(GL33.glGetProgramInfoLog(candidate));
            }
            uniformSize = GL33.glGetUniformLocation(candidate,"FrameSize");
            uniformHaveDepth = GL33.glGetUniformLocation(candidate,"HaveDepth");
            array = GL33.glGenVertexArrays();
            program = candidate;
        } finally {
            if (program == 0) GL33.glDeleteProgram(candidate);
            if (fragment != 0) GL33.glDeleteShader(fragment);
            GL33.glDeleteShader(vertex);
        }
    }

    private static int compile(int type, String source) {
        int shader = GL33.glCreateShader(type);
        GL33.glShaderSource(shader,source);
        GL33.glCompileShader(shader);
        if (GL33.glGetShaderi(shader,GL33.GL_COMPILE_STATUS) != GL33.GL_FALSE) return shader;
        String error = GL33.glGetShaderInfoLog(shader);
        GL33.glDeleteShader(shader);
        throw new IllegalStateException(error);
    }

    @Override public void close() {
        if (frame != null) {
            frame.destroyBuffers();
            frame = null;
        }
        if (array != 0) GL33.glDeleteVertexArrays(array);
        if (program != 0) GL33.glDeleteProgram(program);
        array = program = 0;
    }
}
