package com.petrolpark.destroy.recipe.serializer;

import java.util.HashSet;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.petrolpark.destroy.Destroy;
import com.petrolpark.destroy.MoveToPetrolparkLibrary;
import com.petrolpark.destroy.recipe.IBiomeSpecificProcessingRecipe;
import com.petrolpark.destroy.recipe.IBiomeSpecificProcessingRecipe.BiomeValue;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
import com.simibubi.create.content.processing.recipe.ProcessingRecipeBuilder.ProcessingRecipeFactory;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import com.simibubi.create.content.processing.recipe.ProcessingRecipeSerializer;

@MoveToPetrolparkLibrary
public class AdvancedProcessingRecipeSerializer<T extends ProcessingRecipe<?>> extends ProcessingRecipeSerializer<T> {

    public AdvancedProcessingRecipeSerializer(ProcessingRecipeFactory<T> factory) {
        super(factory);
    };

    @Override
    protected void writeToJson(JsonObject json, T recipe) {
        super.writeToJson(json, recipe);

        // Biome
        if (recipe instanceof IBiomeSpecificProcessingRecipe biomeRecipe) {
            Set<BiomeValue> biomes = biomeRecipe.getAllowedBiomes();
            if (!biomes.isEmpty()) {
                JsonArray jsonArray = new JsonArray(biomes.size());
                biomes.forEach(biome -> jsonArray.add(biome.serialize()));
                json.add("biomes", jsonArray);
            };
        };
    };

    @Override
    protected T readFromJson(ResourceLocation recipeId, JsonObject json) {
        T recipe = super.readFromJson(recipeId, json);

        // Biome
        if (json.has("biomes") && json.get("biomes").isJsonArray()) {
            if (recipe instanceof IBiomeSpecificProcessingRecipe biomeRecipe) {
                Set<BiomeValue> biomes = new HashSet<>();
                json.get("biomes").getAsJsonArray().forEach(e -> {
                    if (!e.isJsonPrimitive()) throw new JsonSyntaxException("Biome list must contain only names of biomes");
                    biomes.add(IBiomeSpecificProcessingRecipe.valueFromString(e.getAsString()));
                });
                biomeRecipe.setAllowedBiomes(biomes);
            } else {
                Destroy.LOGGER.warn("Recipe "+recipeId+" specifies a biome filter but that is not supported by this recipe type.");
            };
        };

        return recipe;
    };

    @Override
    protected void writeToBuffer(FriendlyByteBuf buffer, T recipe) {
        super.writeToBuffer(buffer, recipe);

        // Biome
        if (recipe instanceof IBiomeSpecificProcessingRecipe biomeRecipe) {
            Set<BiomeValue> biomes = biomeRecipe.getAllowedBiomes();
            buffer.writeVarInt(biomes.size());
            biomes.forEach(biome -> buffer.writeUtf(biome.serialize()));
        };
    };

    @Override
    protected T readFromBuffer(ResourceLocation recipeId, FriendlyByteBuf buffer) {
        T recipe = super.readFromBuffer(recipeId, buffer);

        // Biome
        if (recipe instanceof IBiomeSpecificProcessingRecipe biomeRecipe) {
            int biomeCount = buffer.readVarInt();
            Set<BiomeValue> biomes = new HashSet<>(biomeCount);
            for (int i = 0; i < biomeCount; i++) {
                biomes.add(IBiomeSpecificProcessingRecipe.valueFromString(buffer.readUtf()));
            };
            biomeRecipe.setAllowedBiomes(biomes);
        };

        return recipe;
    };
    
};
