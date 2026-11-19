package dev.physicalcamera;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import java.util.Optional;
import java.util.OptionalDouble;
import org.lwjgl.system.MemoryStack;

/** Scales the live camera crop on the GPU before readback. */
final class ViewfinderTarget implements AutoCloseable {
    private static final RenderPipeline COLOUR = pipeline(false);
    private static final RenderPipeline COLOUR_AND_DEPTH = pipeline(true);
    private RenderTarget target;
    private boolean unavailable;

    RenderTarget copy(RenderTarget source, Z8ReadRegion region, int width, int height, boolean depth,
                      Z8Depth.Viewport depthView) {
        if (unavailable || source.getColorTextureView() == null) return null;
        boolean hasDepth = depth && source.getDepthTextureView() != null
                && source.getDepthTexture().getFormat() == GpuFormat.D32_FLOAT;
        if (region.width() == width && source.getColorTexture().getFormat() == GpuFormat.RGBA8_UNORM
                && (!hasDepth || !depthView.remapped())) return null;
        try {
            if (target == null || target.hasDepth() != hasDepth) {
                close();
                target = new TextureTarget("Dream Studio viewfinder",width,height,GpuFormat.RGBA8_UNORM,
                        hasDepth ? GpuFormat.D32_FLOAT : null);
            } else if (target.width != width || target.height != height) {
                target.resize(width,height);
            }
            var pipeline = RenderSystem.getCompiledPipeline(hasDepth ? COLOUR_AND_DEPTH : COLOUR);
            var device = RenderSystem.getDevice();
            var encoder = device.createCommandEncoder();
            try (var stack = MemoryStack.stackPush()) {
                var values = stack.malloc(8*Float.BYTES);
                values.putFloat(region.x()/(float)source.width)
                        .putFloat(region.bottom(source.height)/(float)source.height)
                        .putFloat(region.width()/(float)source.width)
                        .putFloat(region.height()/(float)source.height)
                        .putFloat(depthView.scaleX()).putFloat(depthView.scaleY())
                        .putFloat(depthView.offsetU()).putFloat(depthView.offsetV()).flip();
                var crop = encoder.transientMemory().uploadGpu(values,
                        device.getDeviceInfo().limits().minUniformOffsetAlignment(),GpuBuffer.USAGE_UNIFORM);
                var samplers = RenderSystem.getSamplerCache();
                try (var pass = encoder.createRenderPass(() -> "Dream Studio viewfinder crop",
                        target.getColorTextureView(),Optional.empty(),target.getDepthTextureView(),OptionalDouble.empty())) {
                    pass.setPipeline(pipeline);
                    pass.setUniform("ViewfinderCrop",crop);
                    pass.setUniform("ViewColour",source.getColorTextureView(),samplers.getClampToEdge(FilterMode.LINEAR));
                    if (hasDepth) {
                        pass.setUniform("ViewDepth",source.getDepthTextureView(),samplers.getClampToEdge(FilterMode.NEAREST));
                    }
                    pass.draw(3,1,0,0);
                }
            }
            return target;
        } catch (RuntimeException failure) {
            unavailable = true;
            close();
            org.slf4j.LoggerFactory.getLogger("physical_camera").warn("Viewfinder crop pass unavailable",failure);
            return null;
        }
    }

    private static RenderPipeline pipeline(boolean depth) {
        var layout = BindGroupLayout.builder().withUniform("ViewfinderCrop",UniformType.UNIFORM_BUFFER)
                .withUniform("ViewColour",UniformType.COMBINED_IMAGE_SAMPLER);
        var builder = RenderPipeline.builder()
                .withLocation(CameraEquipment.id("pipeline/viewfinder_" + (depth ? "depth" : "colour")))
                .withVertexShader("core/screenquad")
                .withFragmentShader(CameraEquipment.id("core/viewfinder_copy"))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withColorTargetState(ColorTargetState.DEFAULT)
                .withCull(false);
        if (depth) {
            layout.withUniform("ViewDepth",UniformType.COMBINED_IMAGE_SAMPLER);
            builder.withShaderDefine("VIEW_DEPTH")
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS,true));
        }
        return builder.withBindGroupLayout(layout.build()).build();
    }

    @Override public void close() {
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
    }
}
