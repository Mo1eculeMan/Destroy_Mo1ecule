package com.petrolpark.destroy.compat.jei.tooltip;

import com.petrolpark.destroy.chemistry.legacy.IItemReactant;
import com.petrolpark.destroy.chemistry.legacy.LegacySpecies;
import com.petrolpark.destroy.chemistry.legacy.LegacyReaction;
import com.petrolpark.destroy.chemistry.legacy.reactionresult.PrecipitateReactionResult;
import com.petrolpark.destroy.config.DestroyAllConfigs;
import com.petrolpark.destroy.util.DestroyLang;
import com.simibubi.create.foundation.item.TooltipHelper;
import com.simibubi.create.foundation.item.TooltipHelper.Palette;

import mezz.jei.api.gui.ingredient.IRecipeSlotRichTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;

public class ReactionTooltipHelper {

    public static IRecipeSlotRichTooltipCallback reactantTooltip(LegacyReaction reaction, LegacySpecies reactant) {
        boolean nerdMode = DestroyAllConfigs.CLIENT.chemistry.nerdMode.get();
        int ratio = reaction.getReactantMolarRatio(reactant);
        return (view, tooltip) -> {
            tooltip.add(Component.literal(" "));
            if (ratio == 1) {
                tooltip.addAll(TooltipHelper.cutTextComponent(DestroyLang.translate("tooltip.reaction.reactant_ratio.single").component(), Palette.GRAY_AND_WHITE));
            } else {
                tooltip.addAll(TooltipHelper.cutTextComponent(DestroyLang.translate("tooltip.reaction.reactant_ratio.plural", ratio).component(), Palette.GRAY_AND_WHITE));
            };
            if (nerdMode) tooltip.addAll(TooltipHelper.cutTextComponent(DestroyLang.translate("tooltip.reaction.order", reaction.getOrders().get(reactant)).component(), Palette.GRAY_AND_WHITE));
        };
    };

    public static IRecipeSlotRichTooltipCallback productTooltip(LegacyReaction reaction, LegacySpecies product) {
        int ratio = reaction.getProductMolarRatio(product);
        return (view, tooltip) -> {
            tooltip.add(Component.literal(" "));
            if (ratio == 1) {
                tooltip.addAll(TooltipHelper.cutTextComponent(DestroyLang.translate("tooltip.reaction.product_ratio.single").component(), Palette.GRAY_AND_WHITE));
            } else {
                tooltip.addAll(TooltipHelper.cutTextComponent(DestroyLang.translate("tooltip.reaction.product_ratio.plural", ratio).component(), Palette.GRAY_AND_WHITE));
            };
            if(DestroyAllConfigs.CLIENT.chemistry.nerdMode.get() && reaction.getReverseReactionForDisplay().isPresent()) {
            	LegacyReaction reverseReaction = reaction.getReverseReactionForDisplay().get();
            	tooltip.addAll(TooltipHelper.cutTextComponent(DestroyLang.translate("tooltip.reaction.order", reverseReaction.getOrders().get(product)).component(), Palette.GRAY_AND_WHITE));
            }
        };
    };

    public static IRecipeSlotRichTooltipCallback catalystTooltip(LegacyReaction reaction, LegacySpecies catalyst) {
        boolean nerdMode = DestroyAllConfigs.CLIENT.chemistry.nerdMode.get();
        return (view, tooltip) -> {
            if (nerdMode) {
                tooltip.add(Component.literal(" "));
                tooltip.addAll(TooltipHelper.cutTextComponent(DestroyLang.translate("tooltip.reaction.order", reaction.getOrders().get(catalyst)).component(), Palette.GRAY_AND_WHITE));
            };
        };
    };

    public static IRecipeSlotRichTooltipCallback itemReactantTooltip(LegacyReaction reaction, IItemReactant itemReactant) {
        return (view, tooltip) -> {
            if (!itemReactant.isCatalyst()) {
                tooltip.add(Component.literal(" "));
                tooltip.addAll(TooltipHelper.cutTextComponent(DestroyLang.translate("tooltip.reaction.item_reactant", reaction.getMolesPerItem()).component(), Palette.GRAY_AND_WHITE));
            };
        };
    };

    public static IRecipeSlotRichTooltipCallback precipitateTooltip(LegacyReaction reaction, PrecipitateReactionResult precipitate) {
        return (view, tooltip) -> {
            tooltip.add(Component.literal(" "));
            tooltip.addAll(TooltipHelper.cutTextComponent(DestroyLang.translate("tooltip.reaction.precipitate", precipitate.getRequiredMoles()).component(), Palette.GRAY_AND_WHITE));
        };
    };

    public static IRecipeSlotRichTooltipCallback nerdModeTooltip(LegacyReaction reaction) {
        return (view, tooltip) -> {
            boolean reversible = reaction.getReverseReactionForDisplay().isPresent();
            if (reaction.isHalfReaction()) tooltip.add(DestroyLang.translate("tooltip.reaction.standard_half_cell_potential", reaction.getStandardHalfCellPotential()).style(ChatFormatting.GRAY).component());
            if (reversible) tooltip.add(DestroyLang.translate("tooltip.reaction.forward").component());
            tooltip.add(Component.literal(reversible ? "  " : "").append(DestroyLang.translate("tooltip.reaction.activation_energy", reaction.getActivationEnergy()).style(ChatFormatting.GRAY).component()));
            tooltip.add(Component.literal(reversible ? "  " : "").append(DestroyLang.translate("tooltip.reaction.enthalpy_change", reaction.getEnthalpyChange()).style(ChatFormatting.GRAY).component()));
            tooltip.add(Component.literal(reversible ? "  " : "").append(DestroyLang.preexponentialFactor(reaction)).withStyle(ChatFormatting.GRAY));
            if (reversible) {
                tooltip.add(DestroyLang.translate("tooltip.reaction.reverse").component());
                LegacyReaction reverseReaction = reaction.getReverseReactionForDisplay().get();
                tooltip.add(Component.literal("  ").append(DestroyLang.translate("tooltip.reaction.activation_energy", reverseReaction.getActivationEnergy()).style(ChatFormatting.GRAY).component()));
                tooltip.add(Component.literal("  ").append(DestroyLang.translate("tooltip.reaction.enthalpy_change", reverseReaction.getEnthalpyChange()).style(ChatFormatting.GRAY).component()));
                tooltip.add(Component.literal("  ").append(DestroyLang.preexponentialFactor(reverseReaction)).withStyle(ChatFormatting.GRAY));
            };
        };
    }

	public static IRecipeSlotRichTooltipCallback itemProductTooltip(LegacyReaction reaction, Item item, double count) {
        return (view, tooltip) -> {
            tooltip.add(Component.literal(" "));
            if (count == 1) {
                tooltip.addAll(TooltipHelper.cutTextComponent(DestroyLang.translate("tooltip.reaction.item_product.single").component(), Palette.GRAY_AND_WHITE));
            } else {
                tooltip.addAll(TooltipHelper.cutTextComponent(DestroyLang.translate("tooltip.reaction.item_product.plural", count).component(), Palette.GRAY_AND_WHITE));
            };
        };
	};
    
};
