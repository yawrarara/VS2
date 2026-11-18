package org.valkyrienskies.mod.mixin.client.multiplayer;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.IShipObjectWorldClientCreator;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.ValkyrienSkiesMod;

@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener {

    @Shadow
    private ClientLevel level;

    @Inject(
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
            shift = Shift.AFTER
        ),
        method = "handleLogin"
    )
    private void beforeHandleLogin(final ClientboundLoginPacket packet, final CallbackInfo ci) {
        ((IShipObjectWorldClientCreator) Minecraft.getInstance()).createShipObjectWorldClient();
    }

    /**
     * Spawn [ShipMountingEntity] on client side
     */
    @Inject(method = "handleAddEntity",
        at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V"),
        cancellable = true)
    private void handleShipMountingEntity(final ClientboundAddEntityPacket packet, final CallbackInfo ci) {
        if (packet.getType().equals(ValkyrienSkiesMod.SHIP_MOUNTING_ENTITY_TYPE)) {
            ci.cancel();
            final double d = packet.getX();
            final double e = packet.getY();
            final double f = packet.getZ();
            final Entity entity = ValkyrienSkiesMod.SHIP_MOUNTING_ENTITY_TYPE.create(level,
                net.minecraft.world.entity.EntitySpawnReason.LOAD);
            final int i = packet.getId();
            entity.syncPacketPositionCodec(d, e, f);
            entity.snapTo(d, e, f, entity.getYRot(), entity.getXRot());
            entity.setXRot((float) (packet.getXRot() * 360) / 256.0f);
            entity.setYRot((float) (packet.getYRot() * 360) / 256.0f);
            entity.setId(i);
            entity.setUUID(packet.getUUID());
            this.level.addEntity(entity);
        }
    }

    /**
     * When mc receives a tp packet it interpolates the entity towards the target, which is bad for ships: the entity
     * gets stuck in an unloaded chunk clientside and stays there until rejoining the server. 26.1 applies the packet
     * through {@code setValuesFromPositionPacket}, whose interpolation call is {@code moveOrInterpolateTo}; ships skip
     * it and snap instead.
     */
    @WrapOperation(method = "setValuesFromPositionPacket", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/Entity;moveOrInterpolateTo(Lnet/minecraft/world/phys/Vec3;FF)V"))
    private static void teleportingWithNoStep(final Entity instance, final net.minecraft.world.phys.Vec3 pos,
        final float yRot, final float xRot, final Operation<Void> moveOrInterpolateTo) {
        if (VSGameUtilsKt.getShipObjectManagingPos(instance.level(), instance.getX(), instance.getY(), instance.getZ()) !=
            null) {
            instance.setPos(pos);
            instance.setYRot(yRot);
            instance.setXRot(xRot);
        } else {
            moveOrInterpolateTo.call(instance, pos, yRot, xRot);
        }
    }
}
