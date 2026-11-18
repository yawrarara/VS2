package org.valkyrienskies.mod.mixin.client.renderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.render.ShipTerrainMeshCache;
import org.valkyrienskies.mod.compat.voxy.VoxyPerPixel;

/**
 * Flush the ship-terrain GPU-buffer draw queue at the correct point in the frame.
 *
 * <p>The GPU render path bakes each ship section once into a persistent vertex buffer and redraws it
 * every frame with a per-section model-view, instead of re-emitting every block's vertices. The queue
 * is BUILT at submitBlockEntities TAIL (see MixinLevelRenderer) and must be flushed from inside the
 * main gbuffer pass, where the camera model-view (and, under a shaderpack, Iris's target override)
 * are live.
 *
 * <p>26.1 changed the call structure: the main pass ({@code LevelRenderer.lambda$addMainPass$0}) no
 * longer calls {@code renderAllFeatures}; it calls {@code renderSolidFeatures()} and later
 * {@code renderTranslucentFeatures()} directly. {@code renderAllFeatures} now only runs AFTER the
 * world (item-in-hand and the tail of {@code GameRenderer.renderLevel}), by which time the
 * LevelRenderer's model-view push has been popped -- flushing there drew the hull with a non-view
 * matrix, which is what made ships look like a camera-locked flat sprite. Flushing from the two
 * per-pass methods puts the draw back inside the pass with the correct matrix.
 *
 * <p>With no shaderpack the queue is flushed at {@code renderSolidFeatures} HEAD: the moving-block
 * types are not fixed buffers, so the shared-buffer type switch draws ship translucent mid-features,
 * and the hull must already be in the framebuffer or it loses the depth test behind every translucent
 * surface (the old shaders-off xray). Solid-vs-solid needs no ordering, so drawing before the features
 * is harmless. Under a shaderpack it is flushed at {@code renderTranslucentFeatures} TAIL, i.e. once
 * the pack's gbuffer programs have the world depth, matching the previous renderAllFeatures-TAIL slot.
 *
 * <p>These methods are invoked more than once per frame; per-pass flags draw the queue at most once
 * per pass. No-op entirely when the GPU path is off.
 */
@Mixin(FeatureRenderDispatcher.class)
public abstract class MixinFeatureRenderDispatcher {

    // Per-pixel LOD occlusion: merge Voxy's LOD depth into the gbuffer BEFORE the hull (which draws
    // from inside the main pass), so the hull depth-tests against LOD. No-op on the shadow pass or when
    // per-pixel can't run (then the dilation cull in VoxyOcclusion handles it instead).
    @Inject(method = "renderSolidFeatures", at = @At("HEAD"), require = 1)
    private void valkyrienskies$mergeLodDepthBeforeHull(final CallbackInfo ci) {
        VoxyPerPixel.beforeHull(Minecraft.getInstance().levelRenderer);
        // NO-shaderpack GPU path: draw the hull BEFORE the feature draws + immediate moving-block flush,
        // so ship translucent (water, stained glass) blends against the hull instead of xraying through to
        // the world. Iris frames no-op here and keep the late flush (their gbuffer program wants world
        // depth first).
        ShipTerrainMeshCache.INSTANCE.flushDeferredGpuDrawsEarly();
    }

    @Inject(method = "renderTranslucentFeatures", at = @At("TAIL"), require = 1)
    private void valkyrienskies$flushShipGpuDraws(final CallbackInfo ci) {
        ShipTerrainMeshCache.INSTANCE.flushDeferredGpuDraws();
        // Per-pixel LOD occlusion: selectively restore the gbuffer depth (remove the LOD primer, keep
        // hull + entities) so the shaderpack's shadow/SSR passes never see LOD -> no shadow glitch.
        VoxyPerPixel.afterHull(Minecraft.getInstance().levelRenderer);
    }
}
