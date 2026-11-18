package org.valkyrienskies.mod.mixin.feature.mass_tooltip;

import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.21.11: BlockItem no longer overrides appendHoverText, so the mass tooltip injects into
 * Item.appendHoverText with a BlockItem guard. The map below provides matching hover-lines for
 * the valkyrienskies tab items so the tab reads as a toolkit, not a debug page.
 */
@Mixin(Item.class)
public class MixinItemTooltips {
    private static final Map<String, String> TOOLTIPS = Map.ofEntries(
        Map.entry("ship_creator",          "Right-click a structure to turn it into a ship."),
        Map.entry("ship_creator_smaller",  "Right-click to turn a small structure into a 1/2-scale ship."),
        Map.entry("ship_remover",          "Right-click a ship to delete it and drop its blocks."),
        Map.entry("ship_assembler",        "Right-click to shipify the block you are looking at."),
        Map.entry("area_assembler",        "Right-click and drag to shipify a large area."),
        Map.entry("connection_checker",    "Right-click any block to find out which ship (if any) it belongs to."),
        Map.entry("physics_entity_creator","Spawn a debug physics entity. Left-click: picker. Right-click: owns.")
    );

    @Inject(method = "appendHoverText", at = @At("TAIL"), require = 1)
    private void ValkyrienSkies$addVsItemTooltip(ItemStack itemStack, TooltipContext tooltipContext,
        TooltipDisplay tooltipDisplay, Consumer<Component> consumer, TooltipFlag tooltipFlag, CallbackInfo ci) {
        final Registry<Item> registry = BuiltInRegistries.ITEM;
        final net.minecraft.resources.Identifier key = registry.getKey(itemStack.getItem());
        if (key == null) return;
        if (!"valkyrienskies".equals(key.getNamespace())) return;
        final String hint = TOOLTIPS.get(key.getPath());
        if (hint == null) return;
        consumer.accept(Component.literal(hint).withStyle(ChatFormatting.GRAY));
    }
}
