package dev.stonerestore.mixin;

import dev.stonerestore.StoneRestoreClient;
import net.minecraft.resource.ReloadableResourceManagerImpl;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourceReload;
import net.minecraft.resource.ResourceType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * The single hook of the mod. Every resource reload passes its pack list through here, which is the one
 * place where "which packs are loaded, in which order" is final - so wrapping a pack here cannot be
 * overridden by pack priority.
 */
@Mixin(ReloadableResourceManagerImpl.class)
public abstract class ReloadableResourceManagerMixin {

    @Shadow
    @Final
    private ResourceType type;

    @ModifyVariable(method = "reload", at = @At("HEAD"), argsOnly = true)
    private List<ResourcePack> stonerestore$wrapPacks(List<ResourcePack> packs) {
        if (this.type != ResourceType.CLIENT_RESOURCES) return packs;
        try {
            return StoneRestoreClient.engine().beginReload(packs);
        } catch (Throwable t) {
            StoneRestoreClient.LOG.error("[StoneRestore] Could not prepare restoration layer; loading packs unmodified", t);
            return packs;
        }
    }

    @Inject(method = "reload", at = @At("RETURN"))
    private void stonerestore$afterReload(CallbackInfoReturnable<ResourceReload> cir) {
        if (this.type != ResourceType.CLIENT_RESOURCES) return;
        try {
            StoneRestoreClient.engine().afterReload((ResourceManager) (Object) this, cir.getReturnValue());
        } catch (Throwable t) {
            StoneRestoreClient.LOG.error("[StoneRestore] Could not attach verification", t);
        }
    }
}
