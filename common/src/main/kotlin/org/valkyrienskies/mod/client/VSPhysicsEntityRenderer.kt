package org.valkyrienskies.mod.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.entity.EntityRendererProvider.Context
import net.minecraft.client.renderer.entity.MobRenderer
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState
import net.minecraft.resources.Identifier
import org.joml.Quaternionf
import org.valkyrienskies.core.internal.world.VsiClientShipWorld
import org.valkyrienskies.mod.client.VSPhysicsEntityModel.Companion.LAYER_LOCATION
import org.valkyrienskies.mod.common.IShipObjectWorldClientProvider
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.entity.VSPhysicsEntity

class VSPhysicsEntityRenderState : LivingEntityRenderState() {
    val shipRotation: Quaternionf = Quaternionf()
}

class VSPhysicsEntityRenderer<T : VSPhysicsEntity>(context: Context) :
    MobRenderer<T, VSPhysicsEntityRenderState, VSPhysicsEntityModel<VSPhysicsEntityRenderState>>(
        context,
        // Bake the layer
        VSPhysicsEntityModel(context.bakeLayer(LAYER_LOCATION)),
        // Shadow size
        0.0F
    ) {

    override fun createRenderState(): VSPhysicsEntityRenderState {
        return VSPhysicsEntityRenderState()
    }

    override fun extractRenderState(entity: T, state: VSPhysicsEntityRenderState, partialTick: Float) {
        super.extractRenderState(entity, state, partialTick)
        val renderTransform = entity.getRenderTransform(
            ((Minecraft.getInstance() as IShipObjectWorldClientProvider).shipObjectWorld as VsiClientShipWorld)
        )
        if (renderTransform != null) {
            state.shipRotation.set(renderTransform.shipToWorldRotation)
        } else {
            state.shipRotation.identity()
        }
    }

    override fun setupRotations(
        state: VSPhysicsEntityRenderState, poseStack: PoseStack, ageInTicks: Float, partialTick: Float
    ) {
        // Rotate with the ship
        poseStack.mulPose(state.shipRotation)

        // Offset model from hitbox
        poseStack.translate(0.0, -0.5, 0.0)
    }

    override fun getTextureLocation(state: VSPhysicsEntityRenderState): Identifier {
        return Identifier.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "textures/test_sphere.png")
    }
}