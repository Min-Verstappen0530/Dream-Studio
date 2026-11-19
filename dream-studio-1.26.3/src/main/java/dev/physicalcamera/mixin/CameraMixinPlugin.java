package dev.physicalcamera.mixin;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class CameraMixinPlugin implements IMixinConfigPlugin {
    @Override public boolean shouldApplyMixin(String targetClass, String mixinClass) {
        return !targetClass.startsWith("net.irisshaders.iris.") || FabricLoader.getInstance().isModLoaded("iris");
    }

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> targets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClass, ClassNode target, String mixinClass, IMixinInfo info) {}
    @Override public void postApply(String targetClass, ClassNode target, String mixinClass, IMixinInfo info) {}
}
