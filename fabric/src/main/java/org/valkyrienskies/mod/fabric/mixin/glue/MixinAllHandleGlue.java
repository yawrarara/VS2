package org.valkyrienskies.mod.fabric.mixin.glue;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/**
 * Create's super glue packets are validated against the player's position in
 * {@code AllHandle.onSuperGlueSelection}/{@code onSuperGlueRemoval}. On a ship the glue
 * box and the target block live in the ship's shipyard, millions of blocks away from
 * where the player actually stands, so every request was rejected as out of reach.
 *
 * <p>Re-resolve those distance checks through
 * {@link VSGameUtilsKt#squaredDistanceBetweenInclShips}, which maps BOTH points into
 * world space (and is a no-op for ordinary, non-ship positions), keeping normal Create
 * glue behaviour untouched.
 *
 * <p>Only the 6-double overload is used. The {@code Vec3, Vec3, Operation} overload is a
 * helper for wrapping {@code Vec3.distanceToSqr(Vec3)}: it calls the original operation
 * with {@code (inWorldV1, inWorldV2)}, which is the wrong shape for
 * {@code ServerPlayer.distanceToSqr(Vec3)} (that operation takes the player as its
 * receiver) and throws a ClassCastException inside the packet handler - silently dropping
 * every glue placement and removal.
 *
 * <p>Targeted by name so the mixin class itself carries no Create reference; the fabric
 * mixin plugin only applies it when Create Fly is loaded.
 */
@Mixin(targets = "com.zurrtum.create.AllHandle")
public class MixinAllHandleGlue {

    @WrapOperation(
        method = "onSuperGlueSelection", require = 1,
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;distanceToSqr(Lnet/minecraft/world/phys/Vec3;)D"
        )
    )
    private static double vs$glueSelectionReach(final ServerPlayer player, final Vec3 target,
        final Operation<Double> original) {
        return vs$reach(player, target);
    }

    @WrapOperation(
        method = "onSuperGlueRemoval", require = 1,
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;distanceToSqr(Lnet/minecraft/world/phys/Vec3;)D"
        )
    )
    private static double vs$glueRemovalReach(final ServerPlayer player, final Vec3 target,
        final Operation<Double> original) {
        return vs$reach(player, target);
    }

    /** Mirrors {@code ServerPlayer.distanceToSqr(Vec3)} but treats shipyard points as world points. */
    private static double vs$reach(final ServerPlayer player, final Vec3 target) {
        return VSGameUtilsKt.squaredDistanceBetweenInclShips(
            player.level(),
            player.getX(), player.getY(), player.getZ(),
            target.x, target.y, target.z
        );
    }
}
