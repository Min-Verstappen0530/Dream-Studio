package dev.physicalcamera.mixin;

import dev.physicalcamera.RemixItemCapture;
import java.util.Arrays;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.nvidia.rtxremix.minecraft.entity.RemixCapturingConsumer", remap = false)
abstract class RemixCameraVerticesMixin {
    // Includes the two largest lenses simultaneously held by one entity.
    @Unique private static final int MAX_CAMERA_VERTICES = 262_144;
    @Unique private int physicalCamera$expandedLimit;

    @Shadow private int vertexCount;
    @Shadow @Final @Mutable private float[] px;
    @Shadow @Final @Mutable private float[] py;
    @Shadow @Final @Mutable private float[] pz;
    @Shadow @Final @Mutable private float[] nx;
    @Shadow @Final @Mutable private float[] ny;
    @Shadow @Final @Mutable private float[] nz;
    @Shadow @Final @Mutable private float[] tu;
    @Shadow @Final @Mutable private float[] tv;
    @Shadow @Final @Mutable private int[] colors;
    @Shadow @Final @Mutable private int[] boneIndices;
    @Shadow @Final @Mutable private int[] texcoordBoundsMin;
    @Shadow @Final @Mutable private int[] texcoordBoundsMax;
    @Shadow @Final @Mutable private boolean[] generatedNormals;

    @Inject(method = "clear", at = @At("RETURN"))
    private void physicalCamera$restoreCaptureLimit(CallbackInfo ci) {
        // Consumers are reused by Remix for subsequent entities and frame items.
        physicalCamera$expandedLimit = 0;
    }

    @ModifyConstant(method = {"finalizeVertex()V", "acceptModelSpaceVertex(FFFIFFFFFIII)V"},
            constant = @Constant(intValue = 4096), require = 2)
    private int physicalCamera$captureCompleteEquipment(int defaultLimit) {
        int limit = physicalCamera$expandedLimit == 0 ? defaultLimit : physicalCamera$expandedLimit;
        if (vertexCount >= limit && limit < MAX_CAMERA_VERTICES && RemixItemCapture.active()) {
            int capacity = Math.max(px.length,Math.min(MAX_CAMERA_VERTICES,limit*2));
            if (capacity > px.length) physicalCamera$grow(capacity);
            physicalCamera$expandedLimit = capacity;
            return capacity;
        }
        return limit;
    }

    @Unique
    private void physicalCamera$grow(int capacity) {
        px = Arrays.copyOf(px,capacity);
        py = Arrays.copyOf(py,capacity);
        pz = Arrays.copyOf(pz,capacity);
        nx = Arrays.copyOf(nx,capacity);
        ny = Arrays.copyOf(ny,capacity);
        nz = Arrays.copyOf(nz,capacity);
        tu = Arrays.copyOf(tu,capacity);
        tv = Arrays.copyOf(tv,capacity);
        colors = Arrays.copyOf(colors,capacity);
        boneIndices = Arrays.copyOf(boneIndices,capacity);
        texcoordBoundsMin = Arrays.copyOf(texcoordBoundsMin,capacity);
        texcoordBoundsMax = Arrays.copyOf(texcoordBoundsMax,capacity);
        generatedNormals = Arrays.copyOf(generatedNormals,capacity);
    }
}
