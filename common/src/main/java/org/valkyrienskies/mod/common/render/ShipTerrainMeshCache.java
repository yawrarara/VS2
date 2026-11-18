package org.valkyrienskies.mod.common.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import org.joml.FrustumIntersection;
import org.joml.Matrix3f;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.mod.common.VSClientGameUtils;
import org.valkyrienskies.mod.common.config.VSGameConfig;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.VectorConversionsMCKt;
import org.valkyrienskies.mod.compat.SodiumCompat;
import org.valkyrienskies.mod.compat.voxy.VoxyPerPixel;
import org.valkyrienskies.mod.mixin.accessors.client.render.FrustumInvoker;
import org.valkyrienskies.mod.mixin.accessors.client.render.RenderTypeAccessor;

/**
 * Caches ship terrain geometry so it is baked once and cheaply redrawn each frame, instead of
 * re-baking every block model (with ambient occlusion + light sampling) every frame -- the latter
 * dropped large ships to single-digit FPS.
 * <p>
 * Each shipyard section is baked once into the vanilla {@code BLOCK} vertex layout (in SECTION-LOCAL
 * [0,16] coordinates) and, by default, re-emitted each frame into the frame's
 * {@link MultiBufferSource.BufferSource} -- the IMMEDIATE path. That re-emit goes through the
 * moving-block render types (the same path the per-block ship renderer uses), which Iris's gbuffers
 * shade (so shaders work and the see-through holes can't return; it never touches Sodium's terrain
 * render-lists), and vanilla flushes it at the correct point in the frame.
 * <p>
 * The GPU path (always on; it self-disables only on a GPU error) instead uploads solid/cutout/tripwire
 * geometry once into a persistent {@link GpuBuffer} and redraws it each frame with only a per-section
 * model-view uniform changing -- no per-vertex CPU work. Under a shaderpack it draws through Iris's
 * gbuffer terrain program (geometry repacked into Iris's TERRAIN vertex format and drawn via
 * {@link ShipTerrainIrisPipeline}'s assigned pipelines), which eliminates the per-frame CPU re-emit that
 * otherwise costs ~7 ms/frame with a hull in view; without shaders it draws through the vanilla
 * moving-block pipeline. The flush runs from MixinFeatureRenderDispatcher: at renderAllFeatures HEAD
 * with no shaderpack (before the shared-buffer flush draws ship translucent mid-features), at TAIL under
 * a shaderpack (after Iris binds its gbuffer target). Translucent (glass,
 * water) always uses the immediate re-emit path, since it needs vanilla's per-frame back-to-front sort
 * to blend correctly. With no shaderpack that re-emit is DEFERRED until after the world's translucent
 * pass (see {@link #flushDeferredTranslucent}): flushed mid-features it lands before world water and
 * writes depth, so the sea behind a ship window was depth-rejected and a glass hull over deep water
 * showed the dark sea floor with no water in front of it. Iris reorders those buffers after terrain
 * water itself, which is why the xray never appeared under a shaderpack. (Verified 2026-09-07: the
 * unfixed build, and July's, drew the sea floor through a glass raft without shaders; with the deferral
 * the sea is back.)
 * <p>
 * The GPU path self-disables to the immediate path on any GPU error, and re-bakes automatically if the
 * vertex format changes (a shaderpack toggle). Sections outside the camera frustum are skipped entirely
 * (and not even baked until first seen), so cost scales with what is on screen, not the whole ship.
 * <p>
 * We deliberately decode/upload the bytes out at bake time and never retain a {@link MeshData}: its
 * backing {@code ByteBufferBuilder.Result} is invalidated after its first upload, so a held mesh throws
 * "Buffer is no longer valid" on the second frame.
 * <p>
 * The cache is invalidated per-section on block changes via {@code LevelRenderer.setSectionDirty}, and
 * stale sections are evicted over time (closing their GPU buffers). Client-only: referenced only from
 * client mixins, on the render thread.
 */
public final class ShipTerrainMeshCache {

    public static final ShipTerrainMeshCache INSTANCE = new ShipTerrainMeshCache();

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int INITIAL_BUFFER_BYTES = 1 << 12;
    // Off-screen ships are skipped wholesale, so a section can go untouched for a while without the
    // ship being gone; keep cached sections alive long enough to survive normal look-aways.
    private static final long EVICT_AFTER_FRAMES = 3600L;
    // Per-section cooldown between LIGHT-ONLY in-place re-bakes, so a light-flickering source can't
    // re-bake the same section every frame. The stale-lit mesh keeps drawing meanwhile.
    private static final long LIGHT_REBAKE_COOLDOWN_FRAMES = 20L;

    // Auto-recovery for the cache + GPU path: after a render/GPU error we skip the failed path for this
    // many frames (~1.7s at 60fps) and then RETRY, instead of disabling it permanently until relog. Repeated
    // failures back the retry off exponentially (capped) so a persistently-broken state doesn't thrash.
    private static final int RETRY_BASE_FRAMES = 100;
    private static final int RETRY_BACKOFF_MAX = 64;

    // Constant writeTransform args (match vanilla RenderType.draw): no colour modulation, no model
    // offset, identity texture matrix. Never mutated -- writeTransform only reads them.
    private static final Vector4f WHITE = new Vector4f(1.0f, 1.0f, 1.0f, 1.0f);
    private static final Vector3f NO_MODEL_OFFSET = new Vector3f();
    private static final Matrix4f IDENTITY_TEX = new Matrix4f();
    private static final Supplier<String> GPU_BUFFER_LABEL = () -> "vs-ship-section";
    private static final Supplier<String> GPU_PASS_LABEL = () -> "vs_ship_terrain_gpu";
    private static final Supplier<String> GPU_TRANSLUCENT_PASS_LABEL = () -> "vs_ship_terrain_gpu_translucent";

    // The persistent-GPU-buffer path: bake each section's solid/cutout geometry once into a GPU buffer
    // and redraw it with only a per-section transform changing, instead of re-emitting every vertex on
    // the CPU every frame. Always on; a GPU error skips it for a cooldown (gpuPathCooldown) and then
    // retries, gracefully degrading to the immediate re-emit path meanwhile.
    //   * Under a shaderpack it draws through Iris's gbuffer terrain program -- sections are repacked
    //     into Iris's TERRAIN vertex format and drawn via ShipTerrainIrisPipeline's assigned pipelines,
    //     so the hull is shaded exactly like surrounding chunk terrain. This is the large FPS win with a
    //     ship in view (it eliminates the ~7ms/frame CPU re-emit). See frameIrisGpu.
    //   * Without a shaderpack it draws through the vanilla moving-block pipeline.
    //   * The flush runs from MixinFeatureRenderDispatcher: renderAllFeatures HEAD with no shaderpack
    //     (the hull must precede the mid-features draw of ship translucent), TAIL under Iris.
    // Translucent geometry (glass/water) stays on the immediate re-emit path without a shaderpack -- it gets
    // vanilla's per-frame back-to-front sort there. Under a shaderpack it takes the GPU route too, drawn
    // through the pack's water program after the world's water (see frameIrisTranslucent).

    // Keyed by SectionPos.asLong-packed shipyard section position (disjoint per ship, so globally
    // unique). Primitive keys: the per-frame walk looks one up per non-air section, and a record
    // key meant one allocation + boxed hashing per lookup.
    private final Long2ObjectOpenHashMap<CachedSection> sections = new Long2ObjectOpenHashMap<>();

    private long frame;
    // Post-failure retry cooldowns (frames) + exponential backoff multipliers, render-thread only. 0 = the
    // path is active. cacheCooldown gates the whole cache (-> per-block immediate fallback); gpuPathCooldown
    // gates only the persistent-GPU path (-> immediate re-emit of cached meshes).
    private int cacheCooldown;
    private int cacheBackoff = 1;
    private int gpuPathCooldown;
    private int gpuBackoff = 1;
    private ClientLevel boundLevel;

    // Reusable scratch (render thread only -- never shared) to avoid per-section/-frame allocation.
    private final Vector3d cullMin = new Vector3d();
    private final Vector3d cullMax = new Vector3d();
    private final PoseStack scratchPose = new PoseStack();
    // Scratch for the allocation-free transformRenderWithShip overload (per visible section per frame).
    private final Matrix4d scratchModel = new Matrix4d();
    private final Matrix4d scratchPoseMat = new Matrix4d();
    private final Matrix3f scratchNormal = new Matrix3f();

    // Escape hatch (JVM -D flag): disable the whole cached path to compare against the per-block
    // immediate fallback, or to work around a rendering regression without reverting the build.
    private static final boolean CACHE_DISABLED = Boolean.getBoolean("vs.disableShipMeshCache");

    // Per-frame queue of GPU draws, collected during the section walk and flushed in one render pass
    // afterwards (so buffer uploads during lazy baking never happen inside an open pass).
    private final List<GpuDrawItem> gpuDrawQueue = new ArrayList<>();
    private boolean gpuFormatMismatch;

    // This frame's camera model-view (copied at renderAll); composed onto each queued ship pose at flush.
    private final Matrix4f frameCamModelView = new Matrix4f();
    // Per-pass flush guards so the queue is drawn at most once per pass (the main gbuffer pass + Iris's shadow
    // pass, which also fires the flush hook), reset each renderAll. The queue is RETAINED until the next
    // renderAll (not cleared after the first flush) so both passes can draw it -- the shadow pass runs before
    // renderAll, so it draws the previous frame's queue (a 1-frame shadow lag).
    private boolean flushedMain;
    private boolean flushedShadow;

    // Ship translucent (glass, water) held back this frame for flushDeferredTranslucent -- the pose each
    // section was walked with plus its immediate-path meshes. Filled by renderAll with no shaderpack, drawn
    // from MixinChunkSectionsToRender once the world's translucent group is done, emptied either way.
    private final List<DeferredTranslucent> deferredTranslucent = new ArrayList<>();
    private boolean frameDeferTranslucent;
    // The deferred draw failed once: fall back to the mid-features flush for good (logged once).
    private boolean deferTranslucentBroken;
    // Own buffer source for the deferred draw; vanilla's has already been flushed by then.
    private MultiBufferSource.BufferSource deferredBuffers;

    private record DeferredTranslucent(PoseStack.Pose pose, List<Built> built) {
    }

    // Whether this frame uses the persistent-GPU-buffer path at all (vs the immediate re-emit). True when
    // the GPU path is active (not in a post-error cooldown) and either no shaderpack is active (vanilla
    // pipeline) or the Iris pipelines are registered (frameIrisGpu). Recomputed at the top of each frame; a
    // flip (shaders toggled, or the GPU path recovering after an error) re-bakes every section in the new mode.
    private boolean frameGpuEffective;
    private boolean lastGpuEffective;    // detect flips to re-bake in the new mode
    // This frame the GPU buffers draw through Iris's gbuffer program (sections baked into Iris's TERRAIN
    // format and drawn via ShipTerrainIrisPipeline). False = vanilla pipeline (no shaderpack active).
    private boolean frameIrisGpu;
    private boolean lastIrisGpu;         // detect a shaderpack on/off flip to re-bake in the new format
    // This frame the bake should write each block's shaderpack id into mc_Entity (emissive/material). Gated on
    // the Iris path AND the renderShipBlockIds toggle; the ids are baked into the GPU buffer, so a toggle flip
    // must re-bake -- tracked like the other bake-mode flips.
    private boolean frameBlockIds;
    private boolean lastBlockIds;
    // The emissive half of frameBlockIds: whether unhooked blocks get their pack id (the toggle) or the neutral -1.
    private boolean frameEmissiveIds;
    private boolean lastEmissiveIds;
    // This frame the ShipRenderHooks overrides are asked for AND reach the screen (translucent route live, the
    // pack maps minecraft:water). Mirrored to ShipRenderHooks.isActive() for the hooks' owners.
    private boolean frameHooksActive;
    // Under a shaderpack the ship's translucent meshes (glass, water) take the GPU route: baked into Iris's
    // TERRAIN format with material ids like the hull, and drawn after the world's translucent group through the
    // pack's WATER program (ShipTerrainIrisPipeline.translucentPipeline). Without this route they go out with
    // the features as vanilla flushes them, drawn by the pack's BLOCK program with no material and before Iris's
    // pre-translucent depth copy -- a window then reads as an opaque wall to the pack. Tracked like the other
    // bake-mode flips: the meshes move between the immediate and the GPU lists.
    private boolean frameIrisTranslucent;
    private boolean lastIrisTranslucent;
    // The after-water draw stopped reaching us (watchdog in renderAll): immediate path for the session.
    private boolean irisTranslucentBroken;
    private int translucentMissedFrames;
    private boolean loggedTranslucentRoute;
    // Per-frame queue of translucent GPU draws (main pass only, camera-visible sections), drawn from
    // flushDeferredTranslucentGpu; the flag says it was.
    private final List<GpuDrawItem> gpuTranslucentQueue = new ArrayList<>();
    // The hooks' overlay sheets, drawn after the translucent queue in the same flush, only while their owner
    // shows them (ShipRenderHooks.overlaysVisible()).
    private final List<GpuDrawItem> gpuOverlayQueue = new ArrayList<>();
    private boolean flushedTranslucent;
    // The pack's block-id map last seen; a new object means a pack swap/reload -> the baked ids are stale.
    private Object lastBlockIdMap;

    // Cached reflective handle to IrisApi.getInstance().isShaderPackInUse() -- resolved once, no hard dep.
    private static boolean irisResolved;
    private static Object irisApiInstance;
    private static Method irisIsShaderPackInUse;

    // Per-frame bake budget counters (reset each frame): first-time/block-edit bakes and
    // light-only in-place re-bakes are budgeted separately (see the config entries).
    private int lastBaked;

    // Bumped whenever a SHIP section's cached mesh is evicted for a real block change (never for
    // light-only dirties, and never for world-terrain edits -- those miss the section map). A cheap
    // "some ship's blocks changed" signal: MixinLevelRenderer's block-entity chunk cache rescans on a
    // stamp change instead of walking every ship chunk every frame.
    private static long blockEditStamp;
    private int lightRebaked;

    // TEMP DIAG: last shipyard position broken to air, logged when it is baked / invalidated.
    public static volatile net.minecraft.core.BlockPos DEBUG_POS;

    private ShipTerrainMeshCache() {
    }

    /** One render type's worth of a baked section, decoded into flat primitive arrays (immediate path). */
    private static final class Built {
        final RenderType type;
        final int vertexCount;
        final float[] pos;     // 3 per vertex: x, y, z
        final float[] uv;      // 2 per vertex: u, v
        final byte[] color;    // 4 per vertex: r, g, b, a
        final int[] light;     // 1 per vertex: (uv2.u & 0xFFFF) | (uv2.v << 16)
        final float[] normal;  // 3 per vertex: nx, ny, nz (already /127 at decode -- emit is per frame)

        Built(final RenderType type, final int vertexCount, final float[] pos, final float[] uv,
            final byte[] color, final int[] light, final float[] normal) {
            this.type = type;
            this.vertexCount = vertexCount;
            this.pos = pos;
            this.uv = uv;
            this.color = color;
            this.light = light;
            this.normal = normal;
        }
    }

    /**
     * One render type's worth of a baked section living in a persistent GPU vertex buffer (GPU path).
     * Drawn each frame through the render type's pipeline -- which Iris shades like any moving-block
     * terrain -- with only a per-section transform uniform changing.
     */
    private static final class GpuMesh {
        final RenderType type;
        final GpuBuffer vertexBuffer;
        final int indexCount;
        // Bake-time vertex stride. If the pipeline's current format size diverges (shaderpack toggled),
        // the baked bytes no longer match the program and the section must be re-baked.
        final int vertexSize;

        GpuMesh(final RenderType type, final GpuBuffer vertexBuffer, final int indexCount,
            final int vertexSize) {
            this.type = type;
            this.vertexBuffer = vertexBuffer;
            this.indexCount = indexCount;
            this.vertexSize = vertexSize;
        }

        void close() {
            vertexBuffer.close();
        }
    }

    /**
     * A queued GPU draw: one section's persistent meshes plus its RAW ship pose (camera-relative). The
     * camera (main pass) or shadow (shadow pass) model-view is composed onto it at flush time, so the same
     * queue can be drawn into both passes. Each pass draws only the items visible to IT
     * ({@code cameraVisible} / {@code shadowVisible}) -- previously both passes drew the whole queue, so
     * the main pass paid for every behind-camera (shadow-only) section.
     */
    private record GpuDrawItem(Matrix4f shipPose, List<GpuMesh> meshes,
        boolean cameraVisible, boolean shadowVisible, long shipId) {
    }

    private static final TextureAtlasSprite[] NO_SPRITES = new TextureAtlasSprite[0];

    private static final class CachedSection {
        // Translucent geometry: re-emitted immediately each frame (keeps per-frame depth sorting).
        final List<Built> built = new ArrayList<>(1);
        // Solid/cutout/tripwire geometry: persistent GPU buffers, redrawn with a transform each frame.
        final List<GpuMesh> gpuMeshes = new ArrayList<>(2);
        // Translucent geometry on the shaderpack route (frameIrisTranslucent): its own list, drawn after the
        // world's water through the pack's water program, never into the shadow map.
        final List<GpuMesh> gpuTranslucent = new ArrayList<>(1);
        // ShipRenderHooks overlays (a submarine's window sheets): translucent, on the shaderpack route, drawn
        // right after gpuTranslucent and only while ShipRenderHooks.overlaysVisible().
        final List<GpuMesh> gpuOverlay = new ArrayList<>(1);
        // A ShipRenderHooks hook answered for a block of this section at bake (overlay or not): this section
        // re-bakes when the hook's owner asks for a relight.
        boolean hooked;
        // Animated sprites this section's blocks use, collected at bake time. Sodium only advances a
        // sprite's animation if something marked it active that tick, and it never sees our geometry --
        // so every frame this section is drawn we have to report these by hand or they freeze.
        // Empty for the overwhelming majority of sections, which makes the per-frame cost nothing.
        TextureAtlasSprite[] animatedSprites = NO_SPRITES;
        long lastUsedFrame;
        // Cached shadow-frustum verdict for camera-culled sections, recomputed every few frames
        // (staggered by section key). The shadow frustum is a large, slow-moving box, so brief
        // staleness is invisible -- and the cache removes a per-frame AABB allocation + Iris
        // isVisible call for every off-camera section while ship shadows are on.
        long shadowCullFrame = -1000L;
        boolean shadowCullResult;
        // Light-only staleness: a relight arrived but the geometry is unchanged. The mesh stays valid
        // and keeps drawing; renderAll re-bakes it IN PLACE (budgeted per frame + per-section cooldown)
        // instead of evicting -- eviction on every relight blinked sections during light storms.
        boolean lightDirty;
        long lastLightRebakeFrame = -1000L;

        boolean isEmpty() {
            return built.isEmpty() && gpuMeshes.isEmpty() && gpuTranslucent.isEmpty() && gpuOverlay.isEmpty();
        }

        void close() {
            for (final GpuMesh m : gpuMeshes) {
                m.close();
            }
            gpuMeshes.clear();
            for (final GpuMesh m : gpuTranslucent) {
                m.close();
            }
            gpuTranslucent.clear();
            for (final GpuMesh m : gpuOverlay) {
                m.close();
            }
            gpuOverlay.clear();
        }
    }

    public boolean isDisabled() {
        return cacheCooldown > 0;
    }

    /**
     * Whether the renderer should take the cached path (vs the immediate per-block fallback). Also TICKS the
     * post-failure retry cooldown down -- the renderer calls this exactly once per frame to choose its path,
     * so when the cache previously failed we spend [cacheCooldown] frames on the fallback and then retry.
     */
    public boolean canUseCache() {
        if (CACHE_DISABLED) {
            ShipRenderHooks.setActive(false);
            return false;
        }
        if (cacheCooldown > 0) {
            cacheCooldown--;
            // renderAll (the only writer of the hooks' active flag) is skipped this frame: say so.
            ShipRenderHooks.setActive(false);
            return false;
        }
        return true;
    }

    /**
     * True when a shaderpack is active (Iris). Resolved reflectively against the stable Iris v0 API so
     * there is no compile/runtime dependency on Iris; if Iris is absent or anything fails we report
     * "no shaders" and keep the GPU path available.
     *
     * <p>Public because the Voxy compat layer ({@code VoxyPerPixel} / {@code VoxyOcclusion}) gates on
     * it too: both mechanisms exist solely for the Iris case (Voxy withholds LOD depth from the gbuffer
     * under a shaderpack) and must stand down in vanilla, where Voxy composites LOD depth itself.
     */
    public static boolean shadersActive() {
        if (!irisResolved) {
            irisResolved = true;
            try {
                final Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisApiInstance = api.getMethod("getInstance").invoke(null);
                irisIsShaderPackInUse = api.getMethod("isShaderPackInUse");
            } catch (final Throwable ignored) {
                irisApiInstance = null;
                irisIsShaderPackInUse = null;
            }
        }
        if (irisApiInstance == null || irisIsShaderPackInUse == null) {
            return false;
        }
        try {
            return (Boolean) irisIsShaderPackInUse.invoke(irisApiInstance);
        } catch (final Throwable ignored) {
            return false;
        }
    }

    /**
     * Draw every loaded ship's visible cached terrain, baking missing sections on demand. Translucent
     * geometry is re-emitted into {@code bufferSource} (vanilla flushes it); solid/cutout geometry is
     * drawn from persistent GPU buffers in one render pass at the end. Call at the gbuffer-bound submit
     * point in the main pass (where the immediate path draws). Catches its own failures and disables
     * the cache (caller falls back to immediate) rather than propagating.
     *
     * @param frustum the main camera frustum (world space); sections outside it are skipped. May be
     *                null, in which case nothing is culled.
     */
    public void renderAll(final ClientLevel level,
        final RandomSource random, final MultiBufferSource.BufferSource bufferSource,
        final Frustum frustum, final LongSet occludedShipIds,
        final double camX, final double camY, final double camZ) {

        // No disabled-guard here: the renderer only calls renderAll when canUseCache() returned true (cooldown
        // already 0), and canUseCache ticks the cooldown. renderAll runs => the cache is active this frame.
        try {
            if (boundLevel != level) {
                clear();
                boundLevel = level;
            }
            frame++;
            lastBaked = 0;
            lightRebaked = 0;
            gpuDrawQueue.clear();
            flushedMain = false;
            flushedShadow = false;

            // Bake budgets (config, clamped): at least 1 first-time bake per frame or ships could
            // never appear; light re-bakes may be 0 (= relight only on block edits).
            final int maxBakes = Math.max(1, VSGameConfig.CLIENT.getShipMaxSectionBakesPerFrame());
            final int maxLightRebakes = Math.max(0, VSGameConfig.CLIENT.getShipLightRebakesPerFrame());

            // Tick the GPU-path retry cooldown (a GPU draw error parks the persistent-GPU path here); when it
            // reaches 0 the path re-activates and the flip below re-bakes into GPU buffers.
            if (gpuPathCooldown > 0) {
                gpuPathCooldown--;
            }
            final boolean gpuActive = gpuPathCooldown == 0;

            // Recompute the render mode each frame; when it flips -- shaders toggled, or the GPU path
            // recovering after an error -- re-bake everything so each section is stored in the correct mode
            // (persistent GPU buffers vs immediate Built meshes).
            final boolean shadersOn = shadersActive();
            // Under a shaderpack the GPU buffers draw through Iris's gbuffer terrain program via
            // ShipTerrainIrisPipeline (sections are repacked into Iris's TERRAIN vertex format); without
            // shaders they draw through the vanilla moving-block pipeline. frameIrisGpu selects the former.
            frameIrisGpu = gpuActive && shadersOn && ShipTerrainIrisPipeline.ready();
            // The GPU path runs in BOTH modes; only the flush POINT differs (see MixinFeatureRenderDispatcher):
            // shaders-off draws at renderAllFeatures HEAD, under a shaderpack at TAIL where Iris's gbuffer
            // target is bound. The old shaders-off xray (hull invisible behind ship water/glass/redstone) was
            // pass ORDERING, not GL state: the moving-block types are not fixed buffers, so ship translucent is
            // actually drawn by the shared-buffer type switch INSIDE renderAllFeatures -- a TAIL-drawn hull
            // landed after it and lost the depth test everywhere a translucent surface covered it. Drawing the
            // hull at HEAD restores the order the immediate path always had (2026-08-26).
            frameGpuEffective = frameIrisGpu || (gpuActive && !shadersOn);
            // Without a shaderpack the ship's translucent meshes are held back and drawn after the world's
            // water (MixinChunkSectionsToRender). Off for the session once the deferred draw failed.
            frameDeferTranslucent = !shadersOn && !deferTranslucentBroken;
            deferredTranslucent.clear();
            // Under a shaderpack the translucent meshes take the GPU route instead (frameIrisTranslucent), drawn
            // from the same after-the-world's-water hook. Watchdog first: a queue left undrawn for three frames
            // running means that hook is not reaching us (another mod cancelling the group, say), and the meshes
            // go back to the immediate path for the session rather than stay invisible.
            if (!gpuTranslucentQueue.isEmpty() && !flushedTranslucent) {
                if (++translucentMissedFrames >= 3 && !irisTranslucentBroken) {
                    irisTranslucentBroken = true;
                    LOGGER.warn("VS ship terrain: the after-water translucent draw never ran for {} frames; ship "
                        + "glass and water go back to the feature pass under shaders for this session",
                        translucentMissedFrames);
                }
            } else {
                translucentMissedFrames = 0;
            }
            gpuTranslucentQueue.clear();
            gpuOverlayQueue.clear();
            flushedTranslucent = false;
            frameIrisTranslucent = frameIrisGpu && !irisTranslucentBroken
                && ShipTerrainIrisPipeline.translucentReady()
                && VSGameConfig.CLIENT.getRenderShipTranslucentViaShaderpack();
            // Whether to bake shaderpack block ids into mc_Entity (emissive/material) -- Iris path + toggle. The
            // per-face material hooks need the id stream too, whatever the emissive toggle says; with it off the
            // unhooked blocks still get the neutral id.
            frameEmissiveIds = frameIrisGpu && VSGameConfig.CLIENT.getRenderShipBlockIds();
            final boolean hooksPossible = frameIrisTranslucent && ShipRenderHooks.any();
            frameBlockIds = frameEmissiveIds || hooksPossible;
            // Re-bake when the bake mode flips. frameGpuEffective catches GPU <-> immediate; frameIrisGpu
            // catches a shaderpack toggle, which changes the bake FORMAT (vanilla BLOCK 32B <-> Iris
            // TERRAIN 52B) while frameGpuEffective can stay true; the id flags catch the emissive toggle,
            // whose ids are baked into the buffer; frameIrisTranslucent moves translucent meshes between the
            // immediate and GPU lists -- so all of them must be tracked, or a section baked before a flip keeps
            // a stale layout / stale (or missing) mc_Entity ids / the wrong list.
            if (frameGpuEffective != lastGpuEffective || frameIrisGpu != lastIrisGpu
                || frameBlockIds != lastBlockIds || frameEmissiveIds != lastEmissiveIds
                || frameIrisTranslucent != lastIrisTranslucent) {
                lastGpuEffective = frameGpuEffective;
                lastIrisGpu = frameIrisGpu;
                lastBlockIds = frameBlockIds;
                lastEmissiveIds = frameEmissiveIds;
                lastIrisTranslucent = frameIrisTranslucent;
                clear();
            }
            // A shaderpack swapped or reloaded with shaders left on rebuilds its block-id map, and the ids baked
            // into the buffers are then the old pack's: start over. Identity is the right test -- Iris only
            // replaces the map when its contents changed.
            final Object blockIdMap = frameBlockIds ? ShipTerrainIrisPipeline.blockIdMap() : null;
            if (blockIdMap != lastBlockIdMap) {
                lastBlockIdMap = blockIdMap;
                if (!sections.isEmpty()) {
                    LOGGER.info("VS ship terrain: shaderpack block-id map changed; re-baking ship sections");
                    clear();
                }
            }
            // Whether material overrides reach the screen this frame: the translucent route is live and the pack
            // maps minecraft:water, the material every hook so far asks for. ONE predicate for both the bake
            // (whether hooks are asked at all) and the hooks' owners (who fall back to something else without
            // it), so the two presentations can never be on at once.
            frameHooksActive = hooksPossible && ShipTerrainIrisPipeline.waterMaterialMapped(blockIdMap);
            ShipRenderHooks.setActive(frameHooksActive);

            // Camera rotation for this frame. Section-local GPU buffers are drawn with
            // (cameraModelView x shipPose) so they land exactly where the immediate path's
            // camera-relative-baked vertices do (vanilla draws immediate meshes with this matrix).
            // Constant for the whole pass; copy it because we multiply per section.
            final Matrix4f camModelView = new Matrix4f(RenderSystem.getModelViewMatrix());
            // Remember it for the deferred flush(es) -- composed onto each section's raw ship pose per pass.
            frameCamModelView.set(camModelView);

            final int minSectionY = level.getMinSectionY();

            // When ship shadows are on, also keep sections/ships the SUN's shadow frustum can see -- not just
            // the camera's. Otherwise a caster behind the camera is culled and its shadow vanishes as you turn.
            // The shadow frustum is ~1 frame stale here (it's set during the shadow pass, which ran before this
            // renderAll), but it's a large box around the player so that lag is harmless.
            final Frustum shadowFrustum =
                (ShipTerrainIrisPipeline.shadowReady() && VSGameConfig.CLIENT.getRenderShipShadows())
                    ? ShipTerrainIrisPipeline.shadowFrustum() : null;
            // 0.0 = uncapped downstream; the toggle simply forces that regardless of the stored distance.
            final double shadowDistCap = VSGameConfig.CLIENT.getShipShadowDistanceCap()
                ? VSGameConfig.CLIENT.getShipShadowDistance() : 0.0;

            for (final ClientShip ship : VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
                // Skip the whole ship (and all its per-chunk/-section work) when neither the camera nor (for
                // shadows) the sun can see it. The camera test runs allocation-free on the render AABB's
                // doubles; the MC-AABB shadow test (Iris only overrides isVisible(AABB)) is built lazily,
                // only for camera-culled ships while shadows are on.
                final var renderAabb = ship.getRenderAABB();
                final boolean shipCamVisible = isWorldBoxVisible(frustum,
                    renderAabb.minX(), renderAabb.minY(), renderAabb.minZ(),
                    renderAabb.maxX(), renderAabb.maxY(), renderAabb.maxZ());
                // Per-ship shadow frustum: null when shadows are off, or the ship's center is beyond the
                // configured shadow-distance cap (its sections then neither bake for nor draw into the
                // shadow map). 0 = uncapped.
                Frustum shipShadow = shadowFrustum;
                if (shipShadow != null && shadowDistCap > 0.0) {
                    final double dx = (renderAabb.minX() + renderAabb.maxX()) * 0.5 - camX;
                    final double dy = (renderAabb.minY() + renderAabb.maxY()) * 0.5 - camY;
                    final double dz = (renderAabb.minZ() + renderAabb.maxZ()) * 0.5 - camZ;
                    if (dx * dx + dy * dy + dz * dz > shadowDistCap * shadowDistCap) {
                        shipShadow = null;
                    }
                }
                final Frustum shipShadowFrustum = shipShadow;
                final boolean shipVisible = shipCamVisible
                    || (shipShadowFrustum != null
                        && shipShadowFrustum.isVisible(VectorConversionsMCKt.toMinecraft(renderAabb)));
                if (!shipVisible) {
                    continue;
                }
                if (shipCamVisible) {
                    // Voxy per-pixel LOD merge only has to run on frames that draw hull into the main
                    // gbuffer. Marked before the occlusion test below, so the merge is never skipped on
                    // the frame a ship re-emerges from behind LOD terrain.
                    VoxyPerPixel.markShipVisible();
                }
                // VS-VOXY-OCCLUSION: ship is fully behind Voxy LOD terrain (sampled from Voxy's own
                // depth buffer in MixinLevelRenderer). Keep its baked sections warm so re-emerging is
                // instant, but suppress the draw below -- this is also what saves the per-frame
                // immediate re-emit under shaders for hidden ships.
                final boolean shipOccluded = occludedShipIds != null && occludedShipIds.contains(ship.getId());
                final ShipTransform renderTransform = ship.getRenderTransform();
                final Matrix4dc shipToWorld = renderTransform.getShipToWorld();
                ship.getActiveChunksSet().forEach((chunkX, chunkZ) -> {
                    final LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                    final LevelChunkSection[] arr = chunk.getSections();
                    for (int sectionIndex = 0; sectionIndex < arr.length; sectionIndex++) {
                        final LevelChunkSection section = arr[sectionIndex];
                        if (section == null || section.hasOnlyAir()) {
                            continue;
                        }
                        final int sectionY = minSectionY + sectionIndex;
                        final long key = SectionPos.asLong(chunkX, sectionY, chunkZ);

                        CachedSection cached = sections.get(key);

                        // Camera and shadow visibility are tracked SEPARATELY so each render pass draws
                        // only its own sections: the main pass skips shadow-only (behind-camera) casters
                        // -- previously it drew every queued section -- while the shadow pass still gets
                        // every caster the sun can see. Camera-visible implies shadow-visible (matches the
                        // old combined queue, so no caster the main pass draws ever misses the shadow map).
                        // The sun test uses the public isVisible(AABB) (not the allocation-free
                        // cubeInFrustum invoker) so Iris's shadow frustum override actually runs; it only
                        // fires for off-camera sections while shadows are on, and its verdict is cached
                        // for a few frames per section (see CachedSection.shadowCullFrame).
                        final boolean camVisible =
                            isShipSectionVisible(frustum, shipToWorld, chunkX, sectionY, chunkZ);
                        final boolean shadowVisible;
                        if (shipShadowFrustum == null) {
                            shadowVisible = false;
                        } else if (camVisible) {
                            shadowVisible = true;
                        } else if (cached != null) {
                            if (frame - cached.shadowCullFrame >= 3 + (key & 3)) {
                                cached.shadowCullFrame = frame;
                                cached.shadowCullResult = isShipSectionVisibleShadow(
                                    shipShadowFrustum, shipToWorld, chunkX, sectionY, chunkZ);
                            }
                            shadowVisible = cached.shadowCullResult;
                        } else {
                            shadowVisible = isShipSectionVisibleShadow(
                                shipShadowFrustum, shipToWorld, chunkX, sectionY, chunkZ);
                        }
                        final boolean visible = camVisible || shadowVisible;

                        if (cached == null) {
                            // Defer the (expensive) bake until the section is first actually visible,
                            // and cap bakes per frame so a ship appearing all at once doesn't hitch.
                            if (!visible || lastBaked >= maxBakes) {
                                continue;
                            }
                            cached = bake(level, chunkX, sectionY, chunkZ, random, shipToWorld);
                            // Seed the shadow-cull cache with the verdict just computed above.
                            cached.shadowCullFrame = frame;
                            cached.shadowCullResult = shadowVisible;
                            sections.put(key, cached);
                            lastBaked++;
                        }
                        // Keep loaded-ship sections alive even while culled, so turning back toward the
                        // ship doesn't trigger a re-bake stutter.
                        cached.lastUsedFrame = frame;
                        if (!visible) {
                            continue;
                        }
                        if (cached.isEmpty()) {
                            continue;
                        }
                        // Ship fully behind LOD terrain: sections stay baked + kept-alive (above), but
                        // emit nothing this frame. (Cheaper than drawing, and the only thing that
                        // actually hides the hull under shaders -- the immediate re-emit path.)
                        if (shipOccluded) {
                            continue;
                        }
                        // Light-only staleness: re-bake IN PLACE (budgeted + per-section cooldown) while
                        // the stale-lit mesh keeps drawing -- no eviction, so nothing blinks. Camera-visible
                        // only: the shadow map is depth-only, so stale lighting is invisible there. Closing
                        // the old GPU buffers here is safe: this frame's queue was cleared at renderAll
                        // start and is rebuilt below with the fresh meshes; last frame's queue was already
                        // consumed by the shadow pass (which runs before renderAll).
                        if (cached.lightDirty && camVisible
                            && lightRebaked < maxLightRebakes
                            && frame - cached.lastLightRebakeFrame >= LIGHT_REBAKE_COOLDOWN_FRAMES) {
                            final CachedSection fresh =
                                bake(level, chunkX, sectionY, chunkZ, random, shipToWorld);
                            fresh.shadowCullFrame = cached.shadowCullFrame;
                            fresh.shadowCullResult = cached.shadowCullResult;
                            fresh.lastLightRebakeFrame = frame;
                            // A section a hook once answered for stays eligible for relights even when this
                            // bake's answer was "nothing right now" (a window above the waterline).
                            fresh.hooked |= cached.hooked;
                            cached.close();
                            sections.put(key, fresh);
                            cached = fresh;
                            lightRebaked++;
                        }
                        // Anything to do this frame? Translucents re-emit only when the CAMERA sees the
                        // section -- they only ever land in the main pass's bufferSource (the shadow pass
                        // never consumes them), so shadow-only sections skip the per-vertex CPU cost.
                        final boolean emitTranslucent = camVisible && !cached.built.isEmpty();
                        final boolean queueGpu = !cached.gpuMeshes.isEmpty();
                        final boolean queueTranslucentGpu = camVisible && !cached.gpuTranslucent.isEmpty();
                        final boolean queueOverlayGpu = camVisible && !cached.gpuOverlay.isEmpty();
                        if (!emitTranslucent && !queueGpu && !queueTranslucentGpu && !queueOverlayGpu) {
                            continue;
                        }

                        // Tell Sodium these sprites are on screen, or it will not animate them: it only
                        // advances sprites something marked active this tick, and our geometry never
                        // reaches its render lists. Camera-visible only -- a section that exists purely to
                        // cast a shadow is not "visible", and the shadow map is depth-only anyway.
                        if (camVisible && cached.animatedSprites.length != 0) {
                            SodiumCompat.markSpritesActive(cached.animatedSprites);
                        }

                        // One ship pose per section; section-local [0,16] vertices map to render space
                        // exactly as the per-block immediate path does (offset by the section origin).
                        // Scratch pose stack + matrices (render-thread only): zero per-section allocation.
                        scratchPose.setIdentity();
                        VSClientGameUtils.transformRenderWithShip(renderTransform, scratchPose,
                            chunkX * 16.0, sectionY * 16.0, chunkZ * 16.0, camX, camY, camZ,
                            scratchModel, scratchPoseMat, scratchNormal);
                        final PoseStack.Pose pose = scratchPose.last();

                        if (emitTranslucent) {
                            if (frameDeferTranslucent) {
                                deferredTranslucent.add(new DeferredTranslucent(pose.copy(), cached.built));
                            } else {
                                for (final Built b : cached.built) {
                                    emit(bufferSource.getBuffer(b.type), pose, b);
                                }
                            }
                        }
                        // Solid/cutout: queue a GPU draw with this section's RAW ship pose. Copy it (scratchPose
                        // is mutated next section); the camera/shadow model-view is composed per pass at flush.
                        if (queueGpu) {
                            gpuDrawQueue.add(new GpuDrawItem(new Matrix4f(pose.pose()), cached.gpuMeshes,
                                camVisible, shadowVisible, ship.getId()));
                        }
                        // Translucent on the shaderpack route: main pass only (camera-visible), drawn after the
                        // world's water from flushDeferredTranslucentGpu.
                        if (queueTranslucentGpu) {
                            gpuTranslucentQueue.add(new GpuDrawItem(new Matrix4f(pose.pose()),
                                cached.gpuTranslucent, true, false, ship.getId()));
                        }
                        if (queueOverlayGpu) {
                            gpuOverlayQueue.add(new GpuDrawItem(new Matrix4f(pose.pose()),
                                cached.gpuOverlay, true, false, ship.getId()));
                        }
                    }
                });
            }

            // The GPU draw is intentionally NOT flushed here. submitBlockEntities runs before Iris has
            // bound its gbuffer target, so a draw at this point lands on the wrong framebuffer under a
            // shaderpack. The queue is instead flushed from MixinFeatureRenderDispatcher: at
            // renderAllFeatures HEAD with no shaderpack (flushDeferredGpuDrawsEarly -- the hull must land
            // before the shared-buffer type switch draws ship translucent mid-features), at TAIL under
            // Iris, where its target override IS live.
            // (The per-section transforms are already baked into gpuDrawQueue above, so deferring only
            // the draw is correct -- the camera model-view doesn't change between here and that point.)

            evictStale();
            cacheBackoff = 1; // a clean frame clears the backoff so the next failure retries quickly
        } catch (final Throwable t) {
            cacheCooldown = RETRY_BASE_FRAMES * cacheBackoff;
            cacheBackoff = Math.min(cacheBackoff * 2, RETRY_BACKOFF_MAX);
            LOGGER.error("Ship terrain mesh cache failed; using immediate per-block fallback for {} frames before retrying",
                cacheCooldown, t);
            clear();
            // The per-block fallback bakes no ids and no overrides: the hooks' owners must not believe otherwise.
            frameHooksActive = false;
            ShipRenderHooks.setActive(false);
        }
    }

    /**
     * Draw the ship translucent geometry renderAll held back this frame (no shaderpack only). Called from
     * MixinChunkSectionsToRender as the world's TRIPWIRE group starts, i.e. once the TRANSLUCENT group has
     * been drawn and returned -- Sodium's chunk draw included. Pass ordering is the whole point: vanilla flushes the
     * moving-block buffers mid-features, before the world's water, and translucent writes depth, so a
     * ship window in front of the sea rejected every water fragment behind it and left the dark sea floor
     * showing through the hull. Drawn here, the window blends over water that is already there. Ship glass
     * seen THROUGH world water (a submerged hull from above the surface) now fails the depth test instead,
     * which is the lesser artifact: a missing pane edge under water rather than a missing sea.
     * <p>The meshes are emitted with the same camera-relative poses as the immediate path and drawn through
     * the same render types, so RenderSystem's level-pass model-view and projection apply unchanged, and
     * the batch is depth-sorted on upload exactly as vanilla's flush sorts it.
     */
    public void flushDeferredTranslucent() {
        if (deferredTranslucent.isEmpty()) {
            return;
        }
        try {
            if (deferredBuffers == null) {
                deferredBuffers = MultiBufferSource.immediate(new ByteBufferBuilder(INITIAL_BUFFER_BYTES));
            }
            for (final DeferredTranslucent item : deferredTranslucent) {
                for (final Built b : item.built()) {
                    emit(deferredBuffers.getBuffer(b.type), item.pose(), b);
                }
            }
            deferredBuffers.endBatch();
        } catch (final Throwable t) {
            deferTranslucentBroken = true;
            LOGGER.error("Deferred ship translucent draw failed; ship glass and water draw with the features again "
                + "(sea behind a ship window may not show without a shaderpack)", t);
        } finally {
            deferredTranslucent.clear();
        }
    }

    /**
     * Early flush for the NO-shaderpack GPU path, from renderAllFeatures HEAD. Ordering is the whole
     * point: renderAllFeatures draws this frame's recorded features and flushes the immediate
     * moving-block buffers -- including ship translucent (water, stained glass), which BLENDS against
     * whatever the framebuffer holds and WRITES DEPTH. The TAIL flush draws ship solid/cutout AFTER
     * that, so translucent ship geometry blends against the WORLD (the hull is not there yet) and its
     * depth then rejects every hull fragment behind it: the hull xrays wherever water/glass touches it,
     * keeping the world pixels forever. Drawing at HEAD puts hull solid/cutout in the framebuffer
     * before anything blends or depth-tests against it; solid-vs-solid needs no ordering, so being
     * before the world's remaining passes is harmless.
     * <p>Under a shaderpack this must NOT run -- Iris binds its gbuffer target between HEAD and the
     * feature draws, so a HEAD draw lands on the wrong framebuffer and is composited away (the original
     * reason the flush was deferred to TAIL at all). The Iris path keeps the TAIL flush unchanged.
     */
    public void flushDeferredGpuDrawsEarly() {
        if (frameIrisGpu || !frameGpuEffective) {
            return; // Iris path flushes at TAIL; immediate mode has nothing queued.
        }
        flushDeferredGpuDraws();
    }

    /**
     * Flush this frame's queued ship-terrain GPU draws. Called from MixinFeatureRenderDispatcher at
     * {@code renderAllFeatures} TAIL (and, with no shaderpack, from {@link #flushDeferredGpuDrawsEarly}
     * at HEAD). Drawing earlier (at submitBlockEntities time) lands on the wrong target under shaders. The hook fires in BOTH the main gbuffer pass and Iris's shadow pass; per-pass
     * flags draw the queue at most once per pass, and the queue is retained until the next renderAll so both
     * passes can use it. In the shadow pass the queue is only drawn when Ship Shadows is enabled + registered.
     */
    public void flushDeferredGpuDraws() {
        if (gpuDrawQueue.isEmpty()) {
            return;
        }
        final boolean shadow = ShipTerrainIrisPipeline.isShadowPass();
        if (shadow) {
            // Only draw into the shadow map when shadows are enabled AND our shadow pipeline registered;
            // otherwise skip entirely (drawing with the main program/transform would corrupt the shadow map).
            if (!ShipTerrainIrisPipeline.shadowReady() || !VSGameConfig.CLIENT.getRenderShipShadows()
                || flushedShadow) {
                return;
            }
        } else if (flushedMain) {
            return;
        }
        try {
            flushGpuDraws(shadow);
            gpuBackoff = 1; // a clean flush clears the backoff
            if (shadow) {
                flushedShadow = true;
            } else {
                flushedMain = true;
            }
        } catch (final Throwable t) {
            // A GPU failure parks the persistent-GPU path for a cooldown, degrading to the immediate re-emit
            // (still correct), then retries -- instead of disabling it for the rest of the session.
            gpuPathCooldown = RETRY_BASE_FRAMES * gpuBackoff;
            gpuBackoff = Math.min(gpuBackoff * 2, RETRY_BACKOFF_MAX);
            LOGGER.error("VS ship GPU draw failed; using immediate re-emit for {} frames before retrying",
                gpuPathCooldown, t);
            clear();
            gpuDrawQueue.clear(); // the queue's mesh refs are now invalid; the flags reset next renderAll
        }
        if (gpuFormatMismatch) {
            // Shaderpack toggled: baked bytes no longer match the program -- re-bake fresh.
            gpuFormatMismatch = false;
            clear();
            gpuDrawQueue.clear();
        }
    }

    /** Draw the queued solid/cutout sections for one pass (main or shadow); see {@link #drawItems}. */
    private void flushGpuDraws(final boolean shadow) {
        // The base model-view composed onto each section's raw ship pose: the camera (main pass) or the sun
        // POV (shadow pass). For shadows Iris's GL encoder also redirects this draw onto the shadow framebuffer.
        final Matrix4f baseModelView = shadow ? ShipTerrainIrisPipeline.shadowModelView() : frameCamModelView;
        if (baseModelView == null) {
            return; // shadow pass but the shadow model-view wasn't available this frame
        }
        // Each pass draws ONLY its own items: the main pass skips shadow-only (behind-camera) sections
        // -- previously both passes drew the whole queue -- and the shadow pass skips camera-only ones.
        final List<GpuDrawItem> passItems = new ArrayList<>(gpuDrawQueue.size());
        for (final GpuDrawItem item : gpuDrawQueue) {
            if (shadow ? item.shadowVisible() : item.cameraVisible()) {
                passItems.add(item);
            }
        }
        drawItems(passItems, baseModelView, shadow, GPU_PASS_LABEL);
    }

    /**
     * Shaderpack route only: draw the ship's translucent meshes (glass, water) from their persistent buffers
     * through the pack's water program. Called from MixinChunkSectionsToRender as the world's TRIPWIRE group
     * starts, i.e. after the world's translucent terrain -- the slot the no-shaderpack deferral uses, for the
     * same reason (a window drawn before the sea rejects every water fragment behind it) and for one more:
     * Iris copies its "no translucents" depth just before the world's translucent group, so anything drawn
     * earlier is an opaque surface to the pack's water shading. Only here is a ship window glass to it.
     *
     * <p>Unsorted: sections draw back to front by their centre, quads within a section in bake order. Two
     * ship translucents on one ray inside a section may blend in the wrong order (a window seen through a
     * window, ship water behind ship glass); the immediate path sorted those, and this route accepts the
     * loss for the material ids and the draw slot. The config toggle restores the old path.
     */
    public void flushDeferredTranslucentGpu() {
        if (!frameIrisTranslucent || flushedTranslucent
            || (gpuTranslucentQueue.isEmpty() && gpuOverlayQueue.isEmpty())) {
            return;
        }
        try {
            final List<GpuDrawItem> items = new ArrayList<>(gpuTranslucentQueue);
            items.sort((a, b) -> Float.compare(sectionDistanceSq(b), sectionDistanceSq(a)));
            drawItems(items, frameCamModelView, false, GPU_TRANSLUCENT_PASS_LABEL);
            // The hooks' sheets over the glass, when their owner shows them (camera inside the hull).
            if (ShipRenderHooks.overlaysVisible() && !gpuOverlayQueue.isEmpty()) {
                final List<GpuDrawItem> sheets = new ArrayList<>(gpuOverlayQueue);
                sheets.sort((a, b) -> Float.compare(sectionDistanceSq(b), sectionDistanceSq(a)));
                drawItems(sheets, frameCamModelView, false, GPU_TRANSLUCENT_PASS_LABEL);
            }
            flushedTranslucent = true;
            gpuBackoff = 1;
            if (!loggedTranslucentRoute) {
                loggedTranslucentRoute = true;
                LOGGER.info("VS ship terrain: ship glass and water draw after the world's water through the "
                    + "shaderpack's water program ({} sections this frame)", items.size());
            }
        } catch (final Throwable t) {
            gpuPathCooldown = RETRY_BASE_FRAMES * gpuBackoff;
            gpuBackoff = Math.min(gpuBackoff * 2, RETRY_BACKOFF_MAX);
            LOGGER.error("VS ship translucent GPU draw failed; using immediate re-emit for {} frames before retrying",
                gpuPathCooldown, t);
            clear();
            gpuDrawQueue.clear();
            gpuTranslucentQueue.clear();
            gpuOverlayQueue.clear();
        }
        if (gpuFormatMismatch) {
            gpuFormatMismatch = false;
            clear();
            gpuDrawQueue.clear();
            gpuTranslucentQueue.clear();
            gpuOverlayQueue.clear();
        }
    }

    /** Squared camera distance of a queued section's centre: its pose is camera-relative, its extent 16. */
    private static float sectionDistanceSq(final GpuDrawItem item) {
        return item.shipPose().transformPosition(new Vector3f(8.0f, 8.0f, 8.0f)).lengthSquared();
    }

    /** The rotation (and any scale) of a section's pose without its translation -- what a normal matrix needs. */
    private static Matrix4f rotationOf(final Matrix4f pose) {
        return new Matrix4f(pose).setTranslation(0.0f, 0.0f, 0.0f);
    }

    /**
     * Draw a list of sections' persistent GPU meshes, mirroring vanilla {@code RenderType.draw}: same
     * output-target/override selection, pipeline, default uniforms, textures and index buffer. The per-section
     * model-view is supplied through the DynamicTransforms uniform; pipeline + textures are (re)bound only when
     * the pipeline changes.
     *
     * <p>One render pass per run of sections of the same ship. Under a shaderpack the pass is what carries the
     * ship's ROTATION to the pack's normal matrix: Iris derives that matrix from RenderSystem's model-view when
     * a pass is set up (the positions come through DynamicTransforms, which already hold camera x ship pose),
     * so one pass for everything left every ship's normals unrotated -- tolerable on a hull, wrong on a
     * normal-mapped window. The model-view stack gets the ship's rotation pushed for the run and popped after.
     */
    private void drawItems(final List<GpuDrawItem> items, final Matrix4f baseModelView, final boolean shadow,
        final Supplier<String> label) {
        final int n = items.size();
        if (n == 0) {
            return;
        }
        final DynamicUniforms uniforms = RenderSystem.getDynamicUniforms();

        // PHASE 1 -- everything that MAPS a GPU buffer (the transforms + sizing the sequential index
        // buffer) MUST happen before a render pass is opened; mapping inside an open pass throws
        // "Close the existing render pass before performing additional commands".
        //
        // Build every section's transform and write them in ONE batched call: writeTransforms pre-sizes
        // the dynamic-uniform UBO for all of them and hands back slices that are valid together. N
        // separate writeTransform calls do NOT work here -- when the UBO hits its capacity mid-batch it
        // reallocates, so any slice handed out before that point references a freed buffer, which
        // transformed whole sections off-screen and made the ship vanish.
        final DynamicUniforms.Transform[] transforms = new DynamicUniforms.Transform[n];
        int maxIndexCount = 0;
        for (int i = 0; i < n; i++) {
            final GpuDrawItem item = items.get(i);
            final Matrix4f modelView = new Matrix4f(baseModelView).mul(item.shipPose());
            transforms[i] = new DynamicUniforms.Transform(modelView, WHITE, NO_MODEL_OFFSET, IDENTITY_TEX);
            for (final GpuMesh gm : item.meshes()) {
                if (gm.indexCount > maxIndexCount) {
                    maxIndexCount = gm.indexCount;
                }
            }
        }
        if (maxIndexCount == 0) {
            return;
        }
        final GpuBufferSlice[] slices = uniforms.writeTransforms(transforms);
        // Ship terrain is always QUADS (guaranteed at bake), so one shared sequential index buffer,
        // sized to the largest mesh, serves every draw. getBuffer() may grow/map it -- hence pre-pass.
        final RenderSystem.AutoStorageIndexBuffer seq =
            RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        final GpuBuffer indexBuffer = seq.getBuffer(maxIndexCount);
        final VertexFormat.IndexType indexType = seq.type();

        // PHASE 2 -- the render passes do only pipeline/uniform/buffer binds + draws (no mapping).
        final RenderTarget target = OutputTarget.MAIN_TARGET.getRenderTarget();
        // Match vanilla exactly: honour Iris's (or anyone's) target overrides, else the main target.
        final GpuTextureView color = RenderSystem.outputColorTextureOverride != null
            ? RenderSystem.outputColorTextureOverride
            : target.getColorTextureView();
        final GpuTextureView depth = !target.useDepth ? null
            : (RenderSystem.outputDepthTextureOverride != null
                ? RenderSystem.outputDepthTextureOverride
                : target.getDepthTextureView());
        final CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        final int terrainStride = ShipTerrainIrisPipeline.terrainStride();
        // Main pass under a shaderpack only: the shadow pass is depth-only and its model-view is Iris's.
        final boolean rotateNormals = frameIrisGpu && !shadow;
        final Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();

        int start = 0;
        while (start < n) {
            final long shipId = items.get(start).shipId();
            int end = start + 1;
            while (end < n && items.get(end).shipId() == shipId) {
                end++;
            }
            if (rotateNormals) {
                modelViewStack.pushMatrix();
                modelViewStack.mul(rotationOf(items.get(start).shipPose()));
            }
            try (RenderPass pass = encoder.createRenderPass(label, color, OptionalInt.empty(),
                depth, OptionalDouble.empty())) {
                RenderPipeline boundPipeline = null;
                for (int i = start; i < end; i++) {
                    final GpuBufferSlice transform = slices[i];
                    for (final GpuMesh gm : items.get(i).meshes()) {
                        // Iris-format meshes (TERRAIN stride) draw through the Iris-assigned pipelines so the
                        // shaderpack's gbuffer programs shade them. Vanilla-format meshes (no shaderpack) use
                        // the render type's own pipeline.
                        final RenderPipeline pipeline = pipelineFor(gm, terrainStride);
                        if (gm.vertexSize != pipeline.getVertexFormat().getVertexSize()) {
                            // Shaderpack toggled since bake: the program now wants a different layout.
                            gpuFormatMismatch = true;
                            continue;
                        }
                        if (pipeline != boundPipeline) {
                            pass.setPipeline(pipeline);
                            RenderSystem.bindDefaultUniforms(pass);
                            bindRenderTypeTextures(pass, gm.type);
                            boundPipeline = pipeline;
                        }
                        pass.setUniform("DynamicTransforms", transform);
                        pass.setVertexBuffer(0, gm.vertexBuffer);
                        pass.setIndexBuffer(indexBuffer, indexType);
                        pass.drawIndexed(0, 0, gm.indexCount, 1);
                    }
                }
            } finally {
                if (rotateNormals) {
                    modelViewStack.popMatrix();
                }
            }
            start = end;
        }
    }

    /**
     * Pick the draw pipeline for a baked mesh: the Iris-assigned solid/cutout TERRAIN pipeline when the
     * mesh was baked in Iris format and that pipeline is registered, else the render type's own vanilla
     * pipeline (no shaderpack active, or Iris registration unavailable).
     */
    private static RenderPipeline pipelineFor(final GpuMesh gm, final int terrainStride) {
        if (terrainStride > 0 && gm.vertexSize == terrainStride) {
            final RenderPipeline p = gm.type == RenderTypes.translucentMovingBlock()
                ? ShipTerrainIrisPipeline.translucentPipeline() : ShipTerrainIrisPipeline.terrainPipeline();
            if (p != null) {
                return p;
            }
        }
        return gm.type.pipeline();
    }

    /** Bind a render type's textures (block atlas + lightmap) onto the pass, exactly as vanilla draw. */
    private static void bindRenderTypeTextures(final RenderPass pass, final RenderType type) {
        final RenderSetup setup = ((RenderTypeAccessor) type).valkyrienskies$getState();
        for (final Map.Entry<String, RenderSetup.TextureAndSampler> e : setup.getTextures().entrySet()) {
            pass.bindTexture(e.getKey(), e.getValue().textureView(), e.getValue().sampler());
        }
    }

    /**
     * Is the shipyard section [sx,sy,sz] visible? Transforms the section's [0,16]^3 box from shipyard
     * space into rendered world space with {@code shipToWorld}, then frustum-tests the enclosing AABB.
     * Public so the block-entity renderer can cull ship BEs by the same section visibility.
     */
    public boolean isShipSectionVisible(final Frustum frustum, final Matrix4dc shipToWorld,
        final int sx, final int sy, final int sz) {
        final double x0 = sx * 16.0;
        final double y0 = sy * 16.0;
        final double z0 = sz * 16.0;
        return isShipBoxVisible(frustum, shipToWorld, x0, y0, z0, x0 + 16.0, y0 + 16.0, z0 + 16.0);
    }

    /**
     * Section visibility against an Iris SHADOW frustum. Unlike {@link #isShipSectionVisible}, this calls the
     * public {@link Frustum#isVisible} so the shadow frustum's overridden cull runs: Iris's shadow frustums
     * override only {@code isVisible(AABB)} and build the base {@code Frustum} from identity matrices, so the
     * allocation-free {@code cubeInFrustum} invoker would test an identity clip cube and wrongly cull
     * everything. Allocates one AABB; only called (via {@code ||} short-circuit) for sections the camera can't
     * see, while ship shadows are enabled, so the cost is bounded by the shadow distance.
     */
    private boolean isShipSectionVisibleShadow(final Frustum shadowFrustum, final Matrix4dc shipToWorld,
        final int sx, final int sy, final int sz) {
        final double x0 = sx * 16.0;
        final double y0 = sy * 16.0;
        final double z0 = sz * 16.0;
        shipToWorld.transformAab(x0, y0, z0, x0 + 16.0, y0 + 16.0, z0 + 16.0, cullMin, cullMax);
        return shadowFrustum.isVisible(new net.minecraft.world.phys.AABB(
            cullMin.x, cullMin.y, cullMin.z, cullMax.x, cullMax.y, cullMax.z));
    }

    /**
     * Frustum-test an already-world-space box (e.g. a ship's render AABB), allocation-free. Null
     * frustum = visible. Same INSIDE/INTERSECT semantics as vanilla {@code isVisible(AABB)}; only for
     * the CAMERA frustum -- Iris shadow frustums override {@code isVisible(AABB)} and need that call.
     * Public so the block-entity pass can cull whole ships by the same test.
     */
    public static boolean isWorldBoxVisible(final Frustum frustum,
        final double minX, final double minY, final double minZ,
        final double maxX, final double maxY, final double maxZ) {
        if (frustum == null) {
            return true;
        }
        final int result = ((FrustumInvoker) frustum).valkyrienskies$cubeInFrustum(
            minX, minY, minZ, maxX, maxY, maxZ);
        return result == FrustumIntersection.INSIDE || result == FrustumIntersection.INTERSECT;
    }

    /** Frustum-test a shipyard-space box transformed into rendered world space. Null frustum = visible. */
    private boolean isShipBoxVisible(final Frustum frustum, final Matrix4dc shipToWorld,
        final double minX, final double minY, final double minZ,
        final double maxX, final double maxY, final double maxZ) {
        if (frustum == null) {
            return true;
        }
        shipToWorld.transformAab(minX, minY, minZ, maxX, maxY, maxZ, cullMin, cullMax);
        // Direct cubeInFrustum call -- this runs once per non-air ship section per frame, so skip
        // the per-test AABB allocation. Same INSIDE/INTERSECT check as vanilla isVisible(AABB).
        final int result = ((FrustumInvoker) frustum).valkyrienskies$cubeInFrustum(
            cullMin.x, cullMin.y, cullMin.z, cullMax.x, cullMax.y, cullMax.z);
        return result == FrustumIntersection.INSIDE || result == FrustumIntersection.INTERSECT;
    }

    private static void emit(final VertexConsumer consumer, final PoseStack.Pose pose, final Built mesh) {
        final int count = mesh.vertexCount;
        final float[] pos = mesh.pos;
        final float[] uv = mesh.uv;
        final byte[] color = mesh.color;
        final int[] light = mesh.light;
        final float[] normal = mesh.normal;

        for (int i = 0; i < count; i++) {
            final int p3 = i * 3;
            final int p2 = i * 2;
            final int c4 = i * 4;
            final int l = light[i];

            consumer.addVertex(pose, pos[p3], pos[p3 + 1], pos[p3 + 2])
                .setColor(color[c4] & 0xFF, color[c4 + 1] & 0xFF, color[c4 + 2] & 0xFF, color[c4 + 3] & 0xFF)
                .setUv(uv[p2], uv[p2 + 1])
                .setUv2(l & 0xFFFF, l >>> 16)
                .setNormal(pose, normal[p3], normal[p3 + 1], normal[p3 + 2]);
        }
    }

    /** Bake one shipyard section into per-RenderType meshes (section-local coords). */
    private CachedSection bake(final ClientLevel level, final int sectionX, final int sectionY,
        final int sectionZ, final RandomSource random,
        final Matrix4dc shipToWorld) {

        final CachedSection result = new CachedSection();
        result.lastUsedFrame = frame;

        final LevelChunk chunk = level.getChunk(sectionX, sectionZ);
        final int sectionIndex = sectionY - level.getMinSectionY();
        final LevelChunkSection[] arr = chunk.getSections();
        if (sectionIndex < 0 || sectionIndex >= arr.length) {
            return result;
        }
        final LevelChunkSection section = arr[sectionIndex];
        if (section == null || section.hasOnlyAir()) {
            return result;
        }

        final int baseX = sectionX << 4;
        final int baseY = sectionY << 4;
        final int baseZ = sectionZ << 4;

        final Map<RenderType, BufferBuilder> builders = new HashMap<>(4);
        final Map<RenderType, ByteBufferBuilder> backings = new HashMap<>(4);
        final PoseStack pose = new PoseStack();

        // Under shaders, capture the shaderpack block id per vertex so repack can fill mc_Entity (emissive/
        // material). currentBlockId is set once per block below; builderFor wraps each builder in a counter that
        // tags every vertex it emits with it. Off the Iris path (counters == null) builderFor returns the raw
        // builder unchanged -- no overhead and identical behaviour to before.
        final boolean captureBlockIds = frameBlockIds;
        final Map<RenderType, CountingVertexConsumer> counters = captureBlockIds ? new HashMap<>(4) : null;
        final BlockCtx ctx = new BlockCtx();
        // Material/light overrides from other mods (a submarine's windows) -- only where they can reach the
        // screen (the same predicate their owners see, so neither side acts alone).
        final boolean askHooks = captureBlockIds && frameHooksActive;

        // Animated-sprite tracking for Sodium (see CachedSection.animatedSprites).
        //
        // Which sprites a block draws with is a per-POSITION question, not a per-state one: a weighted
        // model picks its variant from state.getSeed(pos), and fire is exactly that -- fire_0 and fire_1
        // are separate animated sprites chosen per block. Sampling one variant per state (which this did)
        // left whichever half of the flames drew the other sprite frozen, and it looked intermittent
        // because marking is atlas-global: any one block using fire_1 anywhere unfreezes every fire_1.
        //
        // So the walk below is exact, seeded the same way the renderer seeds itself. The per-state map is
        // only a filter for whether that walk is worth doing at all -- the answer is no for virtually
        // every block, and that is what keeps this off the hot path. Being per-bake, it also cannot carry
        // a sprite from a previous atlas across a resource reload.
        final boolean trackSprites = SodiumCompat.tracksSpriteAnimation();
        final Map<BlockState, Boolean> animatingStates = trackSprites ? new HashMap<>() : null;
        final List<TextureAtlasSprite> sectionSprites = trackSprites ? new ArrayList<>(0) : null;
        // Never the caller's RandomSource: reseeding that to match a block would perturb whatever the
        // renderer is using it for.
        final RandomSource spriteRandom = trackSprites ? RandomSource.create() : null;
        // Resolve the shaderpack block-id map ONCE per bake (instead of a reflective lookup per block); the
        // per-block id is then a plain map.getInt. Null when not capturing / no pack -> every id stays -1.
        final Object blockIdMap = captureBlockIds ? ShipTerrainIrisPipeline.blockIdMap() : null;

        // Quads are routed per chunk-section layer into the matching moving-block capture buffer by a
        // BlockQuadOutput: vanilla's ModelBlockRenderer resolves face culling, ambient occlusion, light
        // and tint, then hands us each quad ready to copy into the consumer for its material layer.
        // The pose (section translation + per-block translation) is applied on the stack at emit time.
        final BlockQuadOutput quadOutput = (x, y, z, quad, instance) ->
            builderFor(builders, backings, counters, ctx, movingBlockRenderType(quad.materialInfo().layer()))
                .putBakedQuad(pose.last(), quad, instance);
        // The hooks' overlay sheets go into their own translucent buffer (drawn after the block's own quads,
        // and only while their owner shows them), created on the first sheet.
        BufferBuilder overlayBuilder = null;
        ByteBufferBuilder overlayBacking = null;
        CountingVertexConsumer overlayCounter = null;

        try {
            for (int lx = 0; lx < 16; lx++) {
                for (int ly = 0; ly < 16; ly++) {
                    for (int lz = 0; lz < 16; lz++) {
                        final BlockState state = section.getBlockState(lx, ly, lz);
                        if (DEBUG_POS != null && DEBUG_POS.equals(new BlockPos(baseX + lx, baseY + ly, baseZ + lz))) {
                            LOGGER.debug("[VSPHANTOM] bake pos={} state={} clientSide={} chunk={}",
                                DEBUG_POS, state, level.isClientSide, chunk.getClass().getName());
                        }
                        if (state.isAir()) {
                            continue;
                        }
                        // Tag this block's vertices (both the fluid and model emit below) with its shaderpack
                        // block id; -1 off the Iris path / no pack / emissive off / unmapped state (neutral).
                        ctx.id = frameEmissiveIds ? ShipTerrainIrisPipeline.shaderBlockId(blockIdMap, state)
                            : (short) -1;
                        final BlockPos posWorld = new BlockPos(baseX + lx, baseY + ly, baseZ + lz);
                        // A hook's answer marks the section as a relight candidate whether or not it asks for
                        // any overlay; the sheets themselves are laid after the block's own quads, below.
                        ShipRenderHooks.FaceOverrides overrides = null;
                        if (askHooks) {
                            overrides = ShipRenderHooks.query(level, posWorld, state, shipToWorld);
                            if (overrides != null) {
                                result.hooked = true;
                            }
                        }

                        final FluidState fluidState = state.getFluidState();

                        if (trackSprites) {
                            Boolean animates = animatingStates.get(state);
                            if (animates == null) {
                                animates = couldAnimate(level, posWorld, state, fluidState,
                                    spriteRandom);
                                animatingStates.put(state, animates);
                            }
                            if (animates) {
                                collectAnimatedAt(level, posWorld, state, fluidState,
                                    spriteRandom, sectionSprites);
                            }
                        }
                        if (!fluidState.isEmpty()) {
                            // LiquidBlockRenderer emits section-local [0,16] coords (no PoseStack), which
                            // is exactly our cache space -- feed it straight in.
                            fluidRenderer().tesselate(level, posWorld,
                                layer -> builderFor(builders, backings, counters, ctx,
                                    movingBlockRenderType(layer)), state, fluidState);
                        }

                        if (state.getRenderShape() == RenderShape.MODEL) {
                            final BlockStateModel model = blockModels().get(state);
                            pose.pushPose();
                            pose.translate(lx, ly, lz);
                            // Section-local position from the pose; posWorld (shipyard coords) gives the
                            // renderer the neighbours it needs for face culling and ambient occlusion, which
                            // all resolve against the ship's own blocks.
                            modelRenderer().tesselateBlock(quadOutput, 0.0f, 0.0f, 0.0f, level, posWorld,
                                state, model, state.getSeed(posWorld));
                            pose.popPose();
                        }

                        // The hooks' overlay sheets: one quad a hair outside each asked-for face, with the
                        // OTHER state's material id, and the hook's light and tint. Emitted after the block's
                        // own quads so a sheet blends over its window, never under it.
                        if (overrides != null && overrides.any()) {
                            if (overlayBuilder == null) {
                                overlayBacking = new ByteBufferBuilder(INITIAL_BUFFER_BYTES);
                                overlayBuilder = new BufferBuilder(overlayBacking, VertexFormat.Mode.QUADS,
                                    DefaultVertexFormat.BLOCK);
                                overlayCounter = new CountingVertexConsumer(overlayBuilder, ctx);
                            }
                            final short blockId = ctx.id;
                            for (final Direction d : Direction.values()) {
                                final BlockState material = overrides.material[d.ordinal()];
                                if (material == null) {
                                    continue;
                                }
                                final short id = ShipTerrainIrisPipeline.shaderBlockId(blockIdMap, material);
                                if (id < 0) {
                                    continue; // the pack has no material for it: a sheet would say nothing
                                }
                                // Lit by the cell it faces (a torch inside the hull reaches it), with the
                                // hook's SKY half in place of the cell's when the hook gave one.
                                final int cellLight = LevelRenderer.getLightCoords(level, posWorld.relative(d));
                                final int hookLight = overrides.lightmap[d.ordinal()];
                                final int light = hookLight == ShipRenderHooks.KEEP_LIGHT ? cellLight
                                    : ((cellLight & 0xFFFF) | (hookLight & 0xFFFF0000));
                                ctx.id = id;
                                emitOverlayQuad(overlayCounter, lx, ly, lz, d,
                                    blockModels().get(material).particleMaterial().sprite(),
                                    overrides.color[d.ordinal()], light);
                            }
                            ctx.id = blockId;
                        }
                    }
                }
            }

            if (overlayBuilder != null) {
                try (MeshData mesh = overlayBuilder.build()) {
                    if (mesh != null && frameGpuEffective) {
                        final short[] ids = overlayCounter.ids();
                        final GpuMesh gm = uploadGpuMesh(RenderTypes.translucentMovingBlock(), mesh,
                            ids.length == mesh.drawState().vertexCount() ? ids : null);
                        if (gm != null) {
                            result.gpuOverlay.add(gm);
                        }
                    }
                }
            }
            for (final Map.Entry<RenderType, BufferBuilder> entry : builders.entrySet()) {
                try (MeshData mesh = entry.getValue().build()) {
                    if (mesh == null) {
                        continue;
                    }
                    final RenderType type = entry.getKey();
                    final boolean translucent = type == RenderTypes.translucentMovingBlock();
                    // Translucent needs per-frame depth sorting -> immediate path, EXCEPT on the shaderpack
                    // route, where it is baked like the hull (material ids and all) into its own list and drawn
                    // through the pack's water program; that route is unsorted (see
                    // flushDeferredTranslucentGpu). Everything else (solid/cutout/tripwire, and opaque fluids
                    // like lava) -> persistent GPU buffer, provided it draws QUADS (it always does) so the one
                    // shared sequential index buffer applies to every mesh; anything else falls back to the
                    // immediate path.
                    if (frameGpuEffective && (!translucent || frameIrisTranslucent)
                        && mesh.drawState().mode() == VertexFormat.Mode.QUADS) {
                        final short[] ids = blockIdsFor(counters, type, mesh.drawState().vertexCount());
                        final GpuMesh gm = uploadGpuMesh(type, mesh, ids);
                        if (gm != null) {
                            (translucent ? result.gpuTranslucent : result.gpuMeshes).add(gm);
                        }
                    } else {
                        final Built b = decode(type, mesh);
                        if (b != null) {
                            result.built.add(b);
                        }
                    }
                }
            }
        } finally {
            for (final ByteBufferBuilder backing : backings.values()) {
                backing.close();
            }
            if (overlayBacking != null) {
                overlayBacking.close();
            }
        }
        if (trackSprites && !sectionSprites.isEmpty()) {
            result.animatedSprites = sectionSprites.toArray(new TextureAtlasSprite[0]);
        }
        return result;
    }

    /** How many variants to try before deciding a state has no animated sprite to offer. */
    private static final int VARIANT_PROBES = 8;

    /**
     * Add the animated sprites this block draws with <em>at this position</em>.
     *
     * <p>Seeded exactly as the renderer seeds itself, so a weighted model resolves to the same variant we
     * are about to emit geometry for. Both halves of what {@link #bake} emits are covered: the fluid,
     * whose sprites come from Fabric's fluid render handler -- the same source {@code renderLiquid} uses,
     * so a modded fluid is handled like a vanilla one -- and the block model's own quads.
     */
    private static void collectAnimatedAt(final ClientLevel level, final BlockPos pos,
        final BlockState state, final FluidState fluidState,
        final RandomSource random, final List<TextureAtlasSprite> out) {

        if (!fluidState.isEmpty()) {
            final FluidModel fluidModel = fluidModels().get(fluidState);
            addAnimated(fluidModel.stillMaterial().sprite(), out);
            addAnimated(fluidModel.flowingMaterial().sprite(), out);
        }

        if (state.getRenderShape() == RenderShape.MODEL) {
            random.setSeed(state.getSeed(pos));
            addAnimatedParts(blockModels().get(state), random, out);
        }
    }

    /**
     * Whether this state is worth walking per-position at all.
     *
     * <p>Only a filter, so sampling is fine here where it would not be above: a handful of variants is
     * enough to notice that a block animates, and the exact walk then decides which sprite it actually
     * uses. Wrong only for a block whose variants disagree about whether they animate at all, which costs
     * that block the frozen texture it already had.
     */
    private static boolean couldAnimate(final ClientLevel level, final BlockPos pos,
        final BlockState state, final FluidState fluidState,
        final RandomSource random) {

        final List<TextureAtlasSprite> probe = new ArrayList<>(0);

        if (!fluidState.isEmpty()) {
            final FluidModel fluidModel = fluidModels().get(fluidState);
            addAnimated(fluidModel.stillMaterial().sprite(), probe);
            addAnimated(fluidModel.flowingMaterial().sprite(), probe);
            if (!probe.isEmpty()) {
                return true;
            }
        }

        if (state.getRenderShape() == RenderShape.MODEL) {
            final BlockStateModel model = blockModels().get(state);
            for (int variant = 0; variant < VARIANT_PROBES; variant++) {
                random.setSeed(variant * 0x9E3779B97F4A7C15L);
                addAnimatedParts(model, random, probe);
                if (!probe.isEmpty()) {
                    return true;
                }
            }
        }

        return false;
    }

    private static void addAnimatedParts(final BlockStateModel model, final RandomSource random,
        final List<TextureAtlasSprite> out) {
        final List<BlockStateModelPart> parts = new ArrayList<>(0);
        model.collectParts(random, parts);
        for (final BlockStateModelPart part : parts) {
            addAnimatedQuads(part.getQuads(null), out);
            for (final Direction dir : Direction.values()) {
                addAnimatedQuads(part.getQuads(dir), out);
            }
        }
    }

    private static void addAnimatedQuads(final List<BakedQuad> quads,
        final List<TextureAtlasSprite> out) {
        for (final BakedQuad quad : quads) {
            addAnimated(quad.materialInfo().sprite(), out);
        }
    }

    private static void addAnimated(final TextureAtlasSprite[] sprites,
        final List<TextureAtlasSprite> out) {
        for (final TextureAtlasSprite sprite : sprites) {
            addAnimated(sprite, out);
        }
    }

    private static void addAnimated(final TextureAtlasSprite sprite,
        final List<TextureAtlasSprite> out) {
        // Lists, not sets: these hold a couple of entries at most, so a linear scan beats hashing.
        if (sprite != null && !out.contains(sprite) && SodiumCompat.hasAnimation(sprite)) {
            out.add(sprite);
        }
    }

    /**
     * Copy a freshly-built mesh's vertex bytes into a persistent GPU buffer. The MeshData (and its
     * backing Result) is still valid here inside bake, so this synchronous upload is safe -- we never
     * retain the MeshData past this method. Only reached when no shaderpack is active (the bake routing
     * gates on frameGpuEffective), so the bytes are always the vanilla moving-block layout.
     */
    private GpuMesh uploadGpuMesh(final RenderType type, final MeshData mesh, final short[] blockIds) {
        final MeshData.DrawState ds = mesh.drawState();
        if (ds.indexCount() <= 0) {
            return null;
        }
        // Under a shaderpack, repack BLOCK -> Iris TERRAIN (computing the shader extras + mc_Entity block id)
        // so the Iris-assigned pipeline shades the hull; otherwise keep the vanilla moving-block layout.
        final ByteBuffer verts;
        final int vertexSize;
        if (frameIrisGpu) {
            verts = ShipTerrainIrisPipeline.repackBlockToTerrain(
                mesh.vertexBuffer(), ds.vertexCount(), ds.format(), blockIds);
            vertexSize = ShipTerrainIrisPipeline.terrainStride();
        } else {
            verts = mesh.vertexBuffer();
            vertexSize = ds.format().getVertexSize();
        }
        final GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
            GPU_BUFFER_LABEL, GpuBuffer.USAGE_VERTEX, verts);
        return new GpuMesh(type, buffer, ds.indexCount(), vertexSize);
    }

    /**
     * Decode a freshly-built mesh into flat primitive arrays (immediate path, translucent). Iris extends
     * the bake buffer to its TERRAIN layout during the level pass, so read the vanilla fields by their
     * actual offsets (and stride) -- correct whether the format is vanilla BLOCK (32B) or an extended
     * layout (52B+). The appended shader extras are ignored: re-emitting through bufferSource regenerates
     * them.
     */
    private static Built decode(final RenderType type, final MeshData mesh) {
        final MeshData.DrawState ds = mesh.drawState();
        final VertexFormat fmt = ds.format();
        final int stride = fmt.getVertexSize();
        final int offPos = fmt.getOffset(VertexFormatElement.POSITION);
        final int offColor = fmt.getOffset(VertexFormatElement.COLOR);
        final int offUv0 = fmt.getOffset(VertexFormatElement.UV0);
        final int offUv2 = fmt.getOffset(VertexFormatElement.UV2);
        final int offNormal = fmt.getOffset(VertexFormatElement.NORMAL);
        if (offPos < 0 || offColor < 0 || offUv0 < 0 || offUv2 < 0 || offNormal < 0) {
            throw new IllegalStateException("ship bake format missing a required element: " + fmt);
        }

        final int count = ds.vertexCount();
        // GPU vertex bytes are native (little-endian) order; ByteBuffer.duplicate() resets to
        // BIG_ENDIAN, which would silently byte-swap the float/short reads. Read with native order.
        final ByteBuffer src = mesh.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());

        final float[] pos = new float[count * 3];
        final float[] uv = new float[count * 2];
        final byte[] color = new byte[count * 4];
        final int[] light = new int[count];
        final float[] normal = new float[count * 3];

        for (int i = 0; i < count; i++) {
            final int base = i * stride;
            final int pp = base + offPos;
            final int cc = base + offColor;
            final int tt = base + offUv0;
            final int ll = base + offUv2;
            final int nn = base + offNormal;

            final int p3 = i * 3;
            pos[p3] = src.getFloat(pp);
            pos[p3 + 1] = src.getFloat(pp + 4);
            pos[p3 + 2] = src.getFloat(pp + 8);

            final int c4 = i * 4;
            color[c4] = src.get(cc);
            color[c4 + 1] = src.get(cc + 1);
            color[c4 + 2] = src.get(cc + 2);
            color[c4 + 3] = src.get(cc + 3);

            final int p2 = i * 2;
            uv[p2] = src.getFloat(tt);
            uv[p2 + 1] = src.getFloat(tt + 4);

            final int u2 = src.getShort(ll) & 0xFFFF;
            final int v2 = src.getShort(ll + 2) & 0xFFFF;
            light[i] = u2 | (v2 << 16);

            // Pre-divide at bake: emit() replays these every frame for translucents.
            normal[p3] = src.get(nn) / 127.0f;
            normal[p3 + 1] = src.get(nn + 1) / 127.0f;
            normal[p3 + 2] = src.get(nn + 2) / 127.0f;
        }

        return new Built(type, count, pos, uv, color, light, normal);
    }

    private static VertexConsumer builderFor(final Map<RenderType, BufferBuilder> builders,
        final Map<RenderType, ByteBufferBuilder> backings,
        final Map<RenderType, CountingVertexConsumer> counters, final BlockCtx ctx,
        final RenderType rt) {

        BufferBuilder builder = builders.get(rt);
        if (builder == null) {
            final ByteBufferBuilder backing = new ByteBufferBuilder(INITIAL_BUFFER_BYTES);
            // Force the vanilla BLOCK layout so the cached bytes have the fixed offsets we decode.
            builder = new BufferBuilder(backing, rt.mode(), DefaultVertexFormat.BLOCK);
            builders.put(rt, builder);
            backings.put(rt, backing);
        }
        if (counters == null) {
            return builder;
        }
        // Iris path: hand out (and reuse) one counter per render type so every vertex of every block that
        // uses this type is tagged with the current block id, in emission order.
        CountingVertexConsumer counter = counters.get(rt);
        if (counter == null) {
            counter = new CountingVertexConsumer(builder, ctx);
            counters.put(rt, counter);
        }
        return counter;
    }

    /** The block being emitted, for the counters: its shaderpack id (or an overlay sheet's, while one is laid). */
    private static final class BlockCtx {
        short id = -1;
    }

    /** Vertex offset of an overlay sheet from its face: enough to win the depth test, too little to see. */
    private static final float OVERLAY_INSET = 1.0f / 64.0f;

    /**
     * One overlay sheet (see {@link ShipRenderHooks}): a unit quad a hair outside face {@code d} of the block at
     * section-local ({@code lx}, {@code ly}, {@code lz}), wound counter-clockwise seen from the side it faces,
     * carrying the sprite, tint and packed light given. The id it is counted with is whatever {@code ctx.id}
     * holds when it is emitted.
     */
    private static void emitOverlayQuad(final VertexConsumer out, final int lx, final int ly, final int lz,
        final Direction d, final TextureAtlasSprite sprite, final int argb, final int light) {
        // Two tangents a, b with a x b = the face normal, so p0..p3 = c -a-b, c +a-b, c +a+b, c -a+b is
        // counter-clockwise from the +normal side (what the pipeline's back-face cull keeps).
        final float ax;
        final float ay;
        final float az;
        final float bx;
        final float by;
        final float bz;
        switch (d) {
            case UP -> { ax = 0; ay = 0; az = 1; bx = 1; by = 0; bz = 0; }
            case DOWN -> { ax = 1; ay = 0; az = 0; bx = 0; by = 0; bz = 1; }
            case EAST -> { ax = 0; ay = 1; az = 0; bx = 0; by = 0; bz = 1; }
            case WEST -> { ax = 0; ay = 0; az = 1; bx = 0; by = 1; bz = 0; }
            case SOUTH -> { ax = 1; ay = 0; az = 0; bx = 0; by = 1; bz = 0; }
            default -> { ax = 0; ay = 1; az = 0; bx = 1; by = 0; bz = 0; } // NORTH
        }
        final float nx = d.getStepX();
        final float ny = d.getStepY();
        final float nz = d.getStepZ();
        final float cx = lx + 0.5f + nx * (0.5f + OVERLAY_INSET);
        final float cy = ly + 0.5f + ny * (0.5f + OVERLAY_INSET);
        final float cz = lz + 0.5f + nz * (0.5f + OVERLAY_INSET);
        final float u0 = sprite.getU0();
        final float u1 = sprite.getU1();
        final float v0 = sprite.getV0();
        final float v1 = sprite.getV1();
        final int color = argb == 0 ? 0xFFFFFFFF : argb;
        final int overlay = OverlayTexture.NO_OVERLAY;
        out.addVertex(cx - 0.5f * (ax + bx), cy - 0.5f * (ay + by), cz - 0.5f * (az + bz),
            color, u0, v0, overlay, light, nx, ny, nz);
        out.addVertex(cx + 0.5f * (ax - bx), cy + 0.5f * (ay - by), cz + 0.5f * (az - bz),
            color, u1, v0, overlay, light, nx, ny, nz);
        out.addVertex(cx + 0.5f * (ax + bx), cy + 0.5f * (ay + by), cz + 0.5f * (az + bz),
            color, u1, v1, overlay, light, nx, ny, nz);
        out.addVertex(cx - 0.5f * (ax - bx), cy - 0.5f * (ay - by), cz - 0.5f * (az - bz),
            color, u0, v1, overlay, light, nx, ny, nz);
    }

    /** The per-vertex block-id stream captured for {@code type}, or null when unavailable or length-mismatched. */
    private static short[] blockIdsFor(final Map<RenderType, CountingVertexConsumer> counters,
        final RenderType type, final int vertexCount) {
        if (counters == null) {
            return null;
        }
        final CountingVertexConsumer counter = counters.get(type);
        if (counter == null) {
            return null;
        }
        final short[] ids = counter.ids();
        if (ids.length != vertexCount) {
            // A vertex bypassed the counter (e.g. a future FRAPI change): drop to neutral rather than mislabel.
            if (!warnedBlockIdMismatch) {
                warnedBlockIdMismatch = true;
                LOGGER.warn("VS ship terrain: captured block-id count {} != mesh vertex count {} for {}; "
                    + "mc_Entity left neutral (no emissive id) for affected meshes", ids.length, vertexCount, type);
            }
            return null;
        }
        return ids;
    }

    /** One-shot guard so a block-id / vertex-count desync warns once per session, not per section. */
    private static boolean warnedBlockIdMismatch;

    /**
     * Wraps a bake BufferBuilder to record every vertex's shaderpack block id (read from a shared holder set once
     * per block) so repack can write mc_Entity. Counting hooks ONLY the two vertex-starting addVertex overloads --
     * the 3-arg chained form (Sodium's FRAPI renderer, the liquid renderer, and every other chained caller) and
     * the 11-arg form (Indigo / vanilla bulk data) -- so each vertex is recorded exactly once, in emission
     * order, which equals the order repack walks the built mesh. Every other VertexConsumer method delegates
     * unchanged, so the vertex DATA is identical; we only observe the id stream.
     */
    private static final class CountingVertexConsumer implements VertexConsumer {
        private final VertexConsumer delegate;
        private final BlockCtx ctx;
        private final ShortArrayList ids = new ShortArrayList();

        CountingVertexConsumer(final VertexConsumer delegate, final BlockCtx ctx) {
            this.delegate = delegate;
            this.ctx = ctx;
        }

        short[] ids() {
            return ids.toShortArray();
        }

        @Override
        public VertexConsumer addVertex(final float x, final float y, final float z) {
            ids.add(ctx.id);
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public void addVertex(final float x, final float y, final float z, final int color, final float u,
            final float v, final int overlay, final int light, final float nx, final float ny, final float nz) {
            ids.add(ctx.id);
            delegate.addVertex(x, y, z, color, u, v, overlay, light, nx, ny, nz);
        }

        @Override
        public VertexConsumer setColor(final int red, final int green, final int blue, final int alpha) {
            delegate.setColor(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer setColor(final int argb) {
            delegate.setColor(argb);
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(final float width) {
            delegate.setLineWidth(width);
            return this;
        }

        @Override
        public VertexConsumer setUv(final float u, final float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(final int u, final int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(final int u, final int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(final float x, final float y, final float z) {
            delegate.setNormal(x, y, z);
            return this;
        }
    }

    /** Map a chunk-section render layer to the matching MOVING-block render type used by ship terrain. */
    public static RenderType movingBlockRenderType(final ChunkSectionLayer layer) {
        return switch (layer) {
            case CUTOUT -> RenderTypes.cutoutMovingBlock();
            case TRANSLUCENT -> RenderTypes.translucentMovingBlock();
            default -> RenderTypes.solidMovingBlock();
        };
    }

    private static BlockStateModelSet blockModels() {
        return Minecraft.getInstance().getModelManager().getBlockStateModelSet();
    }

    private static FluidStateModelSet fluidModels() {
        return Minecraft.getInstance().getModelManager().getFluidStateModelSet();
    }

    /** Lazily built shared renderers (they hold no per-frame state). */
    private static ModelBlockRenderer MODEL_RENDERER;
    private static FluidRenderer FLUID_RENDERER;

    private static ModelBlockRenderer modelRenderer() {
        if (MODEL_RENDERER == null) {
            MODEL_RENDERER = new ModelBlockRenderer(true, true, Minecraft.getInstance().getBlockColors());
        }
        return MODEL_RENDERER;
    }

    private static FluidRenderer fluidRenderer() {
        if (FLUID_RENDERER == null) {
            FLUID_RENDERER = new FluidRenderer(fluidModels());
        }
        return FLUID_RENDERER;
    }

    /**
     * Immediate (uncached) counterpart of {@link #bake}'s model emit, for the renderer's fallback path:
     * bakes one block's quads straight into {@code buffers}, routed per material layer, with {@code pose}
     * already carrying the ship transform and the block's translation.
     */
    public static void renderBlockImmediate(final BlockState state, final BlockPos pos,
        final ClientLevel level, final PoseStack pose,
        final MultiBufferSource.BufferSource buffers, final RandomSource random) {
        final BlockStateModel model = blockModels().get(state);
        random.setSeed(state.getSeed(pos));
        final BlockQuadOutput output = (x, y, z, quad, instance) ->
            buffers.getBuffer(movingBlockRenderType(quad.materialInfo().layer()))
                .putBakedQuad(pose.last(), quad, instance);
        modelRenderer().tesselateBlock(output, 0.0f, 0.0f, 0.0f, level, pos, state, model,
            random.nextLong());
    }

    /** Immediate counterpart of {@link #bake}'s fluid emit, with per-layer consumer routing. */
    public static void renderFluidImmediate(final BlockState state, final FluidState fluidState,
        final BlockPos pos, final ClientLevel level,
        final java.util.function.Function<ChunkSectionLayer, VertexConsumer> consumers) {
        fluidRenderer().tesselate(level, pos, consumers::apply, state, fluidState);
    }

    /**
     * Drop the cached mesh for a section (called when its blocks change) so it re-bakes next render,
     * closing any GPU buffers it held. Cheap no-op when nothing is cached (the common, no-ships case).
     */
    public void invalidateSection(final int sectionX, final int sectionY, final int sectionZ) {
        invalidateSection(sectionX, sectionY, sectionZ, false);
    }

    /**
     * As {@link #invalidateSection(int, int, int)}, but a LIGHT-ONLY dirty ({@code lightOnly=true},
     * classified by MixinLevelRenderer's public-overload context) does NOT evict: the geometry is
     * unchanged and the mesh keeps drawing; it is only flagged for a budgeted in-place lighting
     * re-bake in {@code renderAll}. Evicting on every relight re-baked whole sections through the
     * bake budget and blinked them during post-assembly light storms and block-break relights.
     */
    public void invalidateSection(final int sectionX, final int sectionY, final int sectionZ,
        final boolean lightOnly) {
        if (sections.isEmpty()) {
            return;
        }
        if (lightOnly) {
            final CachedSection cached = sections.get(SectionPos.asLong(sectionX, sectionY, sectionZ));
            if (cached != null) {
                cached.lightDirty = true;
            }
            return;
        }
        final CachedSection removed = sections.remove(SectionPos.asLong(sectionX, sectionY, sectionZ));
        if (DEBUG_POS != null && DEBUG_POS.getX() >> 4 == sectionX && DEBUG_POS.getY() >> 4 == sectionY
            && DEBUG_POS.getZ() >> 4 == sectionZ) {
            LOGGER.debug("[VSPHANTOM] invalidate section present={} cachedSections={} lightOnly={}",
                removed != null, sections.size(), lightOnly);
        }
        if (removed != null) {
            removed.close();
            // The pending GPU draw queue (retained across frames for the shadow pass) may reference this
            // just-closed section; drop ONLY its item so a deferred flush can't draw the freed buffer, while
            // leaving the rest of the ship's queued sections intact. (Clearing the WHOLE queue here made the
            // shadow pass -- which draws the PREVIOUS frame's queue -- go empty for a frame on every light
            // update, blinking the whole ship's shadow. Per-item removal keeps the freed-buffer guarantee with
            // no whole-ship blink; only the one changed section's shadow drops, for the existing ~1-frame lag.)
            // Identity match is exact: the queue stores cached.gpuMeshes by reference (see the renderAll enqueue).
            gpuDrawQueue.removeIf(item -> item.meshes() == removed.gpuMeshes);
            gpuTranslucentQueue.removeIf(item -> item.meshes() == removed.gpuTranslucent);
            gpuOverlayQueue.removeIf(item -> item.meshes() == removed.gpuOverlay);
            blockEditStamp++;
        }
    }

    /**
     * Flag every cached section of a ship whose bake applied a {@link ShipRenderHooks} override for a
     * light-only re-bake: in place, on the light budget, the mesh drawing meanwhile. For a hook whose answer
     * depends on the world around the ship (the sea's light at a window's depth). Block coordinates,
     * shipyard space, inclusive. Returns how many sections were flagged.
     */
    public int relightHookedSections(final int minX, final int minY, final int minZ,
        final int maxX, final int maxY, final int maxZ) {
        if (sections.isEmpty()) {
            return 0;
        }
        final int sx0 = minX >> 4;
        final int sy0 = minY >> 4;
        final int sz0 = minZ >> 4;
        final int sx1 = maxX >> 4;
        final int sy1 = maxY >> 4;
        final int sz1 = maxZ >> 4;
        int n = 0;
        for (final it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<CachedSection> e
            : sections.long2ObjectEntrySet()) {
            final CachedSection cs = e.getValue();
            if (!cs.hooked || cs.lightDirty) {
                continue;
            }
            final long key = e.getLongKey();
            final int sx = SectionPos.x(key);
            final int sy = SectionPos.y(key);
            final int sz = SectionPos.z(key);
            if (sx < sx0 || sx > sx1 || sy < sy0 || sy > sy1 || sz < sz0 || sz > sz1) {
                continue;
            }
            cs.lightDirty = true;
            n++;
        }
        return n;
    }

    /** See {@link #blockEditStamp} -- changes when any ship section is evicted for a block edit. */
    public static long blockEditStamp() {
        return blockEditStamp;
    }

    private void evictStale() {
        if ((frame & 0xFF) != 0L) {
            return;
        }
        final long cutoff = frame - EVICT_AFTER_FRAMES;
        final Iterator<CachedSection> it = sections.values().iterator();
        while (it.hasNext()) {
            final CachedSection cs = it.next();
            if (cs.lastUsedFrame < cutoff) {
                cs.close();
                it.remove();
            }
        }
    }

    public void clear() {
        LOGGER.debug("[VSPHANTOM] cache clear ({} sections)", sections.size());
        for (final CachedSection cs : sections.values()) {
            cs.close();
        }
        sections.clear();
    }
}
