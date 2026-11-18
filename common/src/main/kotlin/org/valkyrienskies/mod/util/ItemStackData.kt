package org.valkyrienskies.mod.util

import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData

/**
 * 26.1 port shim: `ItemStack.tag` was removed with the data-component rewrite.
 * VS2's items only ever read/write plain custom NBT, so map it onto CUSTOM_DATA.
 */
val ItemStack.tag: CompoundTag?
    get() = get(DataComponents.CUSTOM_DATA)?.copyTag()

fun ItemStack.editTag(block: (CompoundTag) -> Unit) {
    val tag = get(DataComponents.CUSTOM_DATA)?.copyTag() ?: CompoundTag()
    block(tag)
    if (tag.isEmpty) {
        remove(DataComponents.CUSTOM_DATA)
    } else {
        set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
    }
}
