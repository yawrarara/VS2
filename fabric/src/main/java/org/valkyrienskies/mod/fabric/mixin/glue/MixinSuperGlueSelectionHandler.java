package org.valkyrienskies.mod.fabric.mixin.glue;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.zurrtum.create.client.catnip.outliner.Outline;
import com.zurrtum.create.client.catnip.outliner.Outliner;
import com.zurrtum.create.client.content.contraptions.glue.SuperGlueSelectionHandler;
import com.zurrtum.create.content.contraptions.glue.SuperGlueEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/**
 * Makes Create's super glue work on Valkyrien Skies ships.
 *
 * <p>Create does not render glue boxes with an entity renderer: {@code SuperGlueRenderer}
 * is an intentional stub returning {@code shouldRender = false} in upstream Create, and
 * this port keeps it. The only thing that ever draws a glue box is
 * {@code SuperGlueSelectionHandler.tick}, which finds nearby {@code SuperGlueEntity}s and
 * hands their boxes to the {@code Outliner}. That is the "glue script", and it is left
 * entirely in charge here - boxes are visible exactly when Create shows them (glue in
 * hand) and disappear when it hides them.
 *
 * <p>What breaks on a ship is only the coordinate space. A glue box on a ship is stored
 * in that ship's shipyard (which is what lets Create glue the shipyard blocks together
 * when a contraption is assembled), while the player, their look ray and the Outliner all
 * live in world space:
 * <ul>
 *     <li>the {@code getEntitiesOfClass} search around the player never finds a ship's
 *     glue box, so nothing is drawn at all,</li>
 *     <li>the left-click removal test clips the player's world-space look ray against the
 *     shipyard box, so a glue box could never be selected or removed,</li>
 *     <li>any outline handed to the {@code Outliner} is drawn millions of blocks away.</li>
 * </ul>
 *
 * <p>So each of those three points is mapped through the ship transform, and nothing else
 * about Create's glue behaviour is changed.
 */
@Mixin(SuperGlueSelectionHandler.class)
public abstract class MixinSuperGlueSelectionHandler {

    /**
     * The removal hit test (and the "glue under the crosshair" distance) compares this box
     * against world-space eye/trace vectors, so hand it out in world space. This is the
     * only caller inside {@code tick} that needs the box in world space.
     */
    @WrapOperation(
        method = "tick", require = 1,
        at = @At(
            value = "INVOKE",
            target = "Lcom/zurrtum/create/content/contraptions/glue/SuperGlueEntity;"
                + "getBoundingBox()Lnet/minecraft/world/phys/AABB;"
        )
    )
    private static AABB vs$worldSpaceGlueBox(final SuperGlueEntity glue, final Operation<AABB> original) {
        final AABB box = original.call(glue);
        final ClientLevel level = Minecraft.getInstance().level;
        return level == null ? box : vs$toWorld(level, box);
    }

    /**
     * Create's glue search is a plain world-space AABB around the player, so a ship's glue
     * boxes - which sit in the shipyard - are never in the result. Add the same search
     * mapped into every shipyard the box touches.
     */
    @WrapOperation(
        method = "tick", require = 1,
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getEntitiesOfClass"
                + "(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"
        )
    )
    private static List<SuperGlueEntity> vs$includeShipGlue(final ClientLevel level,
        final Class<SuperGlueEntity> type, final AABB box,
        final Operation<List<SuperGlueEntity>> original) {
        final List<SuperGlueEntity> found = new ArrayList<>(original.call(level, type, box));

        VSGameUtilsKt.transformFromWorldToNearbyShips(level, box, (AABB shipBox) -> {
            for (final SuperGlueEntity glue : level.getEntitiesOfClass(type, shipBox)) {
                if (!found.contains(glue)) {
                    found.add(glue);
                }
            }
        });
        return found;
    }

    /** Existing glue box outlines, drawn by Create, need the box in world space. */
    @WrapOperation(
        method = "tick", require = 1,
        at = @At(
            value = "INVOKE",
            target = "Lcom/zurrtum/create/client/catnip/outliner/Outliner;showAABB"
                + "(Ljava/lang/Object;Lnet/minecraft/world/phys/AABB;)"
                + "Lcom/zurrtum/create/client/catnip/outliner/Outline$OutlineParams;"
        )
    )
    private static Outline.OutlineParams vs$worldSpaceBox(final Outliner outliner, final Object slot,
        final AABB box, final Operation<Outline.OutlineParams> original) {
        final ClientLevel level = Minecraft.getInstance().level;
        return original.call(outliner, slot, level == null ? box : vs$toWorld(level, box));
    }

    /**
     * The pending glue cluster is a set of shipyard block positions, so move every block
     * into world space before Create outlines it.
     */
    @WrapOperation(
        method = "tick", require = 1,
        at = @At(
            value = "INVOKE",
            target = "Lcom/zurrtum/create/client/catnip/outliner/Outliner;showCluster"
                + "(Ljava/lang/Object;Ljava/lang/Iterable;)"
                + "Lcom/zurrtum/create/client/catnip/outliner/Outline$OutlineParams;"
        )
    )
    private static Outline.OutlineParams vs$worldSpaceCluster(final Outliner outliner, final Object slot,
        final Iterable<BlockPos> blocks, final Operation<Outline.OutlineParams> original) {
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return original.call(outliner, slot, blocks);
        }

        final List<BlockPos> worldBlocks = new ArrayList<>();
        for (final BlockPos pos : blocks) {
            worldBlocks.add(BlockPos.containing(VSGameUtilsKt.toWorldCoordinates(level, pos)));
        }
        return original.call(outliner, slot, worldBlocks);
    }

    /** Shipyard box -> world box, through the ship that manages its centre. */
    @Unique
    private static AABB vs$toWorld(final ClientLevel level, final AABB box) {
        return VSGameUtilsKt.transformRenderAABBToWorld(level, box.getCenter(), box);
    }
}
