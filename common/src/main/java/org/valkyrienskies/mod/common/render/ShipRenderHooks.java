package org.valkyrienskies.mod.common.render;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4dc;

/**
 * Lets another mod lay a sheet of some other material over a ship block's faces, for a shaderpack's benefit.
 *
 * <p>Under Iris the ship's terrain is baked with each block's shaderpack material id ({@code mc_Entity}) and
 * its lightmap, exactly as world terrain is. A hook registered here is asked, once per block per bake, for a
 * {@link FaceOverrides}: per face it may ask for an OVERLAY -- one extra quad, a hair off that face on the
 * outside of it, carrying the material of ANOTHER block state (the id the pack assigned that state in its
 * block.properties), its own lightmap and tint. The block's own quads are untouched, so its texture stays
 * exactly as it was; the overlay is what the pack's material logic sees first along a ray.
 *
 * <p>What it was built for: a submarine's windows. The mod that fills a hull with dry air lays a
 * {@code minecraft:water} sheet just inside every window that borders that air, lit as the sea outside is at
 * that depth. To the pack the ray from the cabin meets a water surface with the seafloor behind it, and the
 * cabin in front of it is air -- which is precisely the physical situation. The glass keeps its frame. The
 * sheets are drawn only while their owner says so ({@link #setOverlaysVisible}: with the camera inside the
 * hull), so from the sea a window is glass and nothing else. Packs without a water material never get a
 * sheet at all (see {@link #isActive()}).
 *
 * <p>Only consulted on the Iris GPU path with the translucent route available; the vanilla path never
 * calls a hook and bakes byte-for-byte as before. Hooks run on the render thread during a bake and must be
 * cheap: they are called for every non-air block of every section baked.
 */
public final class ShipRenderHooks {

    private ShipRenderHooks() {
    }

    /** Leave this face's lightmap to VS2 (the light of the block the overlay faces). */
    public static final int KEEP_LIGHT = -1;

    /** One block's per-face answers, indexed by {@link net.minecraft.core.Direction#ordinal()}. */
    public static final class FaceOverrides {
        /** The block state whose shaderpack material an overlay on this face carries; null = no overlay. */
        public final BlockState[] material = new BlockState[6];
        /**
         * The overlay's packed lightmap (see {@code LightTexture.pack}): only its SKY half is used, the block
         * light is always that of the cell the face points at (a torch inside the hull lights the sheet).
         * {@link #KEEP_LIGHT} = that cell's light entirely.
         */
        public final int[] lightmap = new int[6];
        /** The overlay's vertex tint, ARGB; 0 = white. */
        public final int[] color = new int[6];

        public FaceOverrides() {
            Arrays.fill(lightmap, KEEP_LIGHT);
        }

        /** Whether any face asks for an overlay. */
        public boolean any() {
            for (final BlockState s : material) {
                if (s != null) {
                    return true;
                }
            }
            return false;
        }
    }

    @FunctionalInterface
    public interface BlockHook {
        /**
         * @param level       the client level (the shipyard chunks live in it too)
         * @param shipPos     the block's shipyard position
         * @param state       its state
         * @param shipToWorld the ship's render transform this bake, for reading the WORLD around the block
         * @return overrides for this block, or null to leave it alone. An answer with no overlay at all is
         *         still an answer: the section remembers it was a candidate and re-bakes on a relight.
         */
        FaceOverrides forBlock(ClientLevel level, BlockPos shipPos, BlockState state, Matrix4dc shipToWorld);
    }

    private static final List<BlockHook> HOOKS = new CopyOnWriteArrayList<>();
    private static volatile boolean active;
    private static volatile boolean overlaysVisible;

    public static void register(final BlockHook hook) {
        HOOKS.add(hook);
    }

    public static boolean any() {
        return !HOOKS.isEmpty();
    }

    /** The first hook with an answer wins; null when none has one. */
    static FaceOverrides query(final ClientLevel level, final BlockPos shipPos, final BlockState state,
        final Matrix4dc shipToWorld) {
        for (final BlockHook hook : HOOKS) {
            final FaceOverrides o = hook.forBlock(level, shipPos, state, shipToWorld);
            if (o != null) {
                return o;
            }
        }
        return null;
    }

    /**
     * Whether overlays reach the screen right now: a shaderpack is on, the ship's translucent geometry is
     * drawn through the pack's water program, the pack maps {@code minecraft:water} to a material at all, and
     * the mesh cache is not sitting out a failure. Decided once per frame by the mesh cache. A hook's owner
     * can use this to fall back to something else (the submarine mod keeps the pack believing the eye is
     * under water instead).
     */
    public static boolean isActive() {
        return active;
    }

    static void setActive(final boolean value) {
        active = value;
    }

    /**
     * Whether the overlays are drawn this frame. The sheets are baked with the hull, so this is the
     * per-frame switch their owner flips: the submarine mod shows them only while the camera is inside a
     * hull, so that from the sea a window is plain glass.
     */
    public static void setOverlaysVisible(final boolean value) {
        overlaysVisible = value;
    }

    public static boolean overlaysVisible() {
        return overlaysVisible;
    }

    /**
     * Ask for a light-only re-bake of every cached section of a ship that a hook answered for (candidate or
     * overlaid). For a hook whose answer depends on the WORLD around the ship (the sea's light at the window's
     * depth, or whether the window is under water at all): call it when that answer would change. Cheap -- the
     * sections keep drawing and re-bake in place on the light budget. Returns how many sections were flagged.
     *
     * @param minX .. maxZ the ship's block AABB in shipyard coordinates (inclusive)
     */
    public static int relightHooked(final int minX, final int minY, final int minZ,
        final int maxX, final int maxY, final int maxZ) {
        return ShipTerrainMeshCache.INSTANCE.relightHookedSections(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Throw every baked ship section away so the hooks are asked again (after a toggle changed their answers). */
    public static void rebakeAll() {
        ShipTerrainMeshCache.INSTANCE.clear();
    }
}
