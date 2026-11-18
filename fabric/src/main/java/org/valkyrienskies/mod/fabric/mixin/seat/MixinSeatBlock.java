package org.valkyrienskies.mod.fabric.mixin.seat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.zurrtum.create.content.contraptions.actors.seat.SeatBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.IShipObjectWorldProvider;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.entity.ShipMountingEntity;
import org.valkyrienskies.mod.common.util.EntityDraggingInformation;
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider;

/**
 * 26.1: {@code SeatBlock.updateEntityMovementAfterFallOn} looks up {@code entity.blockPosition()}
 * to find the seat under a falling player. On a ship that returns a shipyard position, where
 * getBlockState() doesn't hold the seat block. Translate the falling entity into ship-local
 * coordinates so sitDown() actually fires.
 */
@Mixin(SeatBlock.class)
public abstract class MixinSeatBlock extends Block {

    public MixinSeatBlock(final Properties properties) {
        super(properties);
    }

    /**
     * @author Tri0de (ported for fabric 26.1)
     * @reason Fix entities not sitting in seats on ships
     */
    @WrapOperation(
        method = "updateEntityMovementAfterFallOn",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;blockPosition()Lnet/minecraft/core/BlockPos;")
    )
    private BlockPos trackwork$wrapBlockPosInUpdateEntityMovementAfterFallOn(
        final Entity entity,
        final Operation<BlockPos> getBlockPosition
    ) {
        final EntityDraggingInformation draggingInformation =
            ((IEntityDraggingInformationProvider) entity).getDraggingInformation();
        if (draggingInformation != null && draggingInformation.isEntityBeingDraggedByAShip()) {
            final Long shipStandingOnId = draggingInformation.getLastShipStoodOn();
            if (shipStandingOnId != null) {
                final var shipWorld = ((IShipObjectWorldProvider) entity.level()).getShipObjectWorld();
                if (shipWorld != null) {
                    final Ship ship = shipWorld.getLoadedShips().getById(shipStandingOnId);
                    if (ship != null) {
                        final Vector3dc posInShip = ship.getTransform().getWorldToShip()
                            .transformPosition(entity.getX(), entity.getY(), entity.getZ(), new Vector3d());
                        return BlockPos.containing(posInShip.x(), posInShip.y(), posInShip.z());
                    }
                }
            }
        }
        return getBlockPosition.call(entity);
    }

    /**
     * Create spawns a {@code SeatEntity} for every seat click, and on a ship that entity lives
     * in the shipyard, where entity ticks never run - so {@code ServerPlayer.rideTick} never sees
     * the sneak input (the client never even learns about the vehicle: "Received passengers for
     * unknown entity"). The rider stays mounted forever, even after the block is destroyed.
     * The VS2 passenger-seat pattern already solves this: spawn a world-space {@code ShipMountingEntity}
     * (fake invisible car, ticks fine, client renders sitting pose from the spawn), then startRiding it
     * like any other entity - sneak dismounting is then plain vanilla behaviour on the server.
     * sitDown() is the single mount entry point for fall-mount, right-click, and mob mounting alike.
     */
    @Inject(method = "sitDown", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private static void trackwork$sitDownOnShipInstead(
        final Level level, final BlockPos pos, final Entity entity, final CallbackInfo ci
    ) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        final Ship ship = VSGameUtilsKt.getShipManagingPos(level,
            new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5));
        if (ship == null) {
            return; // not on a ship - stock create seat behaviour
        }
        // Ship-local == shipyard block coordinates in VS2's model: rel must stay anchored to the ship, so it
        // is just the block position (transforming it again via worldToShip would double-apply the offset
        // and fling the rider to the far side of nowhere). world = shipToWorld(rel) gives the seat's
        // current world-space position; the seat re-derives it every tick from the same anchor.
        // rel.y is set mid-block (block coords are integers) - the mount sits at saddle-height on the
        // block rather than on the block's bottom edge, which is where a rider should sit.
        final Vector3dc rel = new Vector3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        final Vector3dc world = ship.getTransform().getShipToWorld()
            .transformPosition(rel.x(), rel.y(), rel.z(), new Vector3d());
        final ShipMountingEntity seat = ShipMountingEntity.spawnPassengerSeat(
            serverLevel,
            world.x(), world.y(), world.z(),
            entity.getYRot(), entity.getXRot(),
            ship.getId(),
            rel.x(), rel.y(), rel.z(),
            false
        );
        if (seat == null) {
            return; // fall back to vanilla seat creation
        }
        entity.startRiding(seat, true, true);
        ci.cancel();
    }
}
