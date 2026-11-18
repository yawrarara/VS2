package org.valkyrienskies.mod.fabric.mixin.seat;

import com.zurrtum.create.content.contraptions.actors.seat.SeatEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/**
 * Create seats work through the {@code SeatEntity}. On a ship, vanilla dismount teleports the
 * rider into the shipyard (huge negative coordinates) and traps them there. Return them to the
 * ship's worldspace instead.
 */
@Mixin(SeatEntity.class)
public abstract class MixinSeatEntity extends Entity {
    public MixinSeatEntity(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }

    @Overwrite
    public Vec3 getDismountLocationForPassenger(LivingEntity livingEntity) {
        if (VSGameUtilsKt.isBlockInShipyard(level(), position())
            && VSGameUtilsKt.getShipManagingPos(level(), position()) == null) {
            // Can't find the ship — at minimum don't teleport the rider to negative-billion coordinates.
            return livingEntity.position();
        }
        return super.getDismountLocationForPassenger(livingEntity).add(0, 0.5, 0);
    }
}
