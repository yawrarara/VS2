package org.valkyrienskies.mod.mixin.client.renderer;

import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.render.ShipTerrainMeshCache;

/**
 * Draw ship translucent terrain (glass, water) AFTER the world's translucent group, not before it.
 *
 * <p>Vanilla flushes the moving-block buffers in the middle of the features, which is before the world's
 * water is drawn; translucent writes depth, so a ship window in front of the sea rejected every water
 * fragment behind it and a glass hull over deep water showed the dark sea floor with no water in front
 * of it. With no shaderpack {@link ShipTerrainMeshCache} now holds that geometry back, and this hook
 * draws it once the TRANSLUCENT group is done. Under Iris the same geometry comes from persistent GPU
 * buffers instead, drawn here through the pack's water program (the second flush): Iris does not reorder
 * the immediate buffers -- they went out with the features, shaded as opaque blocks -- so this is also
 * where a ship window first becomes glass to a shaderpack.
 *
 * <p>The hook sits at the HEAD of the TRIPWIRE group, which the main pass always draws immediately after
 * the translucent one, rather than at the translucent group's RETURN: Sodium takes {@code renderGroup}
 * over with a cancelling HEAD inject, and Mixin resolves every injection point against the ORIGINAL
 * method before any mixin's code goes in, so a RETURN inject only ever attaches to vanilla's own return
 * -- which Sodium's early return never reaches (verified: that hook never fired). At HEAD both mixins
 * attach to the same first instruction and run in application order, so priority 900 puts this callback
 * ahead of Sodium's; the TRANSLUCENT group has already been drawn and returned by then either way.
 */
@Mixin(value = ChunkSectionsToRender.class, priority = 900)
public abstract class MixinChunkSectionsToRender {

    @Inject(method = "renderGroup", at = @At("HEAD"), require = 1)
    private void valkyrienskies$drawShipTranslucentAfterWorld(final ChunkSectionLayerGroup group,
        final GpuSampler sampler, final CallbackInfo ci) {
        if (group == ChunkSectionLayerGroup.TRANSLUCENT) {
            ShipTerrainMeshCache.INSTANCE.flushDeferredTranslucent();
            ShipTerrainMeshCache.INSTANCE.flushDeferredTranslucentGpu();
        }
    }
}
