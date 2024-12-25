package com.petrolpark.destroy.chemistry.legacy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import java.util.Set;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.petrolpark.destroy.Destroy;
import com.petrolpark.destroy.block.entity.VatControllerBlockEntity;
import com.petrolpark.destroy.chemistry.api.error.ChemistryException;
import com.petrolpark.destroy.chemistry.api.util.Constants;
import com.petrolpark.destroy.chemistry.legacy.genericreaction.DoubleGroupGenericReaction;
import com.petrolpark.destroy.chemistry.legacy.genericreaction.GenericReactant;
import com.petrolpark.destroy.chemistry.legacy.genericreaction.GenericReaction;
import com.petrolpark.destroy.chemistry.legacy.genericreaction.SingleGroupGenericReaction;
import com.petrolpark.destroy.chemistry.legacy.index.DestroyMolecules;
import com.petrolpark.destroy.chemistry.legacy.reactionresult.NovelCompoundSynthesizedReactionResult;
import com.petrolpark.destroy.recipe.ReactionInBasinRecipe.ReactionInBasinResult;
import com.simibubi.create.foundation.utility.NBTHelper;
import com.simibubi.create.foundation.utility.Pair;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.registries.ForgeRegistries;


public class LegacyMixture extends ReadOnlyMixture {

    protected static final int TICKS_PER_SECOND = 20;

    /**
     * A Map of all {@link ReactionResult Results} of {@link LegacyReaction Reactions} generated in this Mixture, mapped
     * to their 'concentrations' (moles of the Reaction which have occured per Bucket of this Mixture, since the last
     * instance of that Reaction Result was dealt with).
     */
    protected Map<ReactionResult, Double> reactionResults;
    /**
     * {@link LegacySpecies Molecules} which do not have a name space or ID.
     */
    protected List<LegacySpecies> novelMolecules;

    /**
     * All {@link LegacyReaction Reactions} with specific Reactants and specified {@link GenericReaction Generic Reactions}
     * which are possible given the {@link LegacySpecies Molecules} in this Mixture.
     */
    protected List<LegacyReaction> possibleReactions;

    /**`
     * Every {@link LegacySpecies} in this Mixture that has a {@link LegacyFunctionalGroup functional Group}, indexed by the {@link LegacyFunctionalGroup#getType Type} of that Group.
     * Molecules are stored as {@link com.petrolpark.destroy.chemistry.genericReaction.GenericReactant Generic Reactants}.
     * Molecules which have multiple of the same Group are indexed for each occurence of the Group.
     */
    protected Map<LegacyFunctionalGroupType<?>, List<GenericReactant<?>>> groupIDsAndMolecules;

    /**
     * Whether this Mixture has reached equilibrium. This means either:
     * <ul>
     * <li>No more {@link LegacyReaction Reactions} will occur.</li>
     * <li>Any further Reactions that occur will balance each other out and there will be no change in concentration of any {@link LegacySpecies}.</li>
     * </ul>
     */
    protected boolean equilibrium;

    /**
     * The Molecule which has the boiling point which is closest to the current temperature of the Mixture, but higher.
     * Either value may be {@code null}.
     */
    Pair<Float, LegacySpecies> nextHigherBoilingPoint;
    
    /**
     * The Molecule which has the boiling point which is closest to the current temperature of the Mixture, but lower.
     * Either value may be {@code null}.
     */
    Pair<Float, LegacySpecies> nextLowerBoilingPoint;

    /**
     * Some Molecules which get removed get swiftly added back in the next tick. To prevent refreshing the Reactions every time, we keep hold of them for a while until we know they're definitely gone.
     * This maps Molecules which have 0 concentration to the ticks they have left to live.
     */
    Map<LegacySpecies, Integer> moleculesToRemove;
    
    //for debugging purposes, in M
    public Map<LegacyReaction, Double> lastTickReactions;
    
    public LegacyMixture() {
        super();
        reactionResults = new HashMap<>();
        novelMolecules = new ArrayList<>();
        possibleReactions = new ArrayList<>();
        groupIDsAndMolecules = new HashMap<>();
        nextHigherBoilingPoint = Pair.of(Float.MAX_VALUE, null);
        nextLowerBoilingPoint = Pair.of(0f, null);
        moleculesToRemove = new HashMap<>();
        equilibrium = false;
        lastTickReactions = new HashMap<>();
    };

    /**
     * Get a Mixture containing only the given Molecule, unless it is charged, in which case get a Mixture
     * containing the sodium salt or chloride of the ion.
     * @param molecule
     */
    public static LegacyMixture pure(LegacySpecies molecule) {
        LegacyMixture mixture = new LegacyMixture();
        if (molecule.getCharge() == 0) {
            mixture.addMolecule(molecule, molecule.getPureConcentration());
            return mixture;
        };
        LegacySpecies otherIon = molecule.getCharge() < 0 ? DestroyMolecules.SODIUM_ION : DestroyMolecules.CHLORIDE;
        int chargeMagnitude = Math.abs(molecule.getCharge());
        mixture.addMolecule(molecule, 1f);
        mixture.addMolecule(otherIon, chargeMagnitude);
        mixture.recalculateVolume(1000);
        return mixture;
    };

    public static LegacyMixture readNBT(CompoundTag compound) {
        LegacyMixture mixture = new LegacyMixture();
        
        if (compound == null) {
            Destroy.LOGGER.warn("Null Mixture loaded");
            return mixture;
        };

        mixture.translationKey = compound.getString("TranslationKey"); // Set to "" if the key is not present

        if (compound.contains("Temperature")) mixture.temperature = compound.getFloat("Temperature");

        ListTag contents = compound.getList("Contents", Tag.TAG_COMPOUND);
        contents.forEach(tag -> {
            CompoundTag moleculeTag = (CompoundTag) tag;
            LegacySpecies molecule = LegacySpecies.getMolecule(moleculeTag.getString("Molecule"));
            if (molecule == null) return;
            mixture.internalAddMolecule(molecule, moleculeTag.getFloat("Concentration"), false);
            if (moleculeTag.contains("Gaseous",Tag.TAG_FLOAT)) {
                double state = moleculeTag.getFloat("Gaseous");
                mixture.states.put(molecule, state);
                if (state != 0f && state != 1f) mixture.boiling = true;
            } else { // If we're not told the state, guess it
                mixture.states.put(molecule, molecule.getBoilingPoint() < mixture.temperature ? 1d : 0d);
            };
        });

        mixture.equilibrium = compound.getBoolean("AtEquilibrium");

        if (compound.contains("Results", Tag.TAG_LIST)) {
            ListTag results = compound.getList("Results", Tag.TAG_COMPOUND);
            results.forEach(tag -> {
                CompoundTag resultTag = (CompoundTag) tag;
                LegacyReaction reaction = LegacyReaction.get(resultTag.getString("Result"));
                if (reaction == null) return;
                ReactionResult result = reaction.getResult();
                if (result == null) return;
                mixture.reactionResults.put(result, resultTag.getDouble("MolesPerBucket"));
            });
        };
        
        if(compound.contains("LastTickReactions", Tag.TAG_LIST)) {
        	ListTag reactions = compound.getList("LastTickReactions", Tag.TAG_COMPOUND);
        	reactions.forEach(tag -> {
        		LegacyReaction reaction = LegacyReaction.get(((CompoundTag) tag).getString("Reaction"));
        		if(reaction == null) return;
        		mixture.lastTickReactions.put(reaction, (((CompoundTag) tag)).getDouble("MolesPerLiter"));
        	});
        }
        
        mixture.updateName();
        mixture.updateColor();
        mixture.refreshPossibleReactions();
        mixture.updateNextBoilingPoints();

        return mixture;
    };

    @Override
    public CompoundTag writeNBT() {
        CompoundTag tag = super.writeNBT();
        tag.putBoolean("AtEquilibrium", equilibrium);

        if (!reactionResults.isEmpty()) {
            tag.put("Results", NBTHelper.writeCompoundList(reactionResults.entrySet().stream().filter(entry -> entry.getKey().getReaction().isPresent()).toList(), entry -> {
                CompoundTag resultTag = new CompoundTag();
                resultTag.putString("Result", entry.getKey().getReaction().get().getFullId());
                resultTag.putDouble("MolesPerBucket", entry.getValue());
                return resultTag;
            }));
        };
        
        if(!lastTickReactions.isEmpty()) {
        	tag.put("LastTickReactions", NBTHelper.writeCompoundList(lastTickReactions.entrySet().stream().toList(), entry -> {
        		CompoundTag reactionTag = new CompoundTag();
        		reactionTag.putString("Reaction", entry.getKey().getNameSpace() + ":" + entry.getKey().getId());
        		reactionTag.putDouble("MolesPerLiter", entry.getValue());
        		return reactionTag;
        	}));
        }
        return tag;
    };

    /**
     * Set the temperature (in kelvins) of this Mixture. This is mutative.
     * @param temperature in Kelvins
     * @return This Mixture
     */
    public LegacyMixture setTemperature(float temperature) {
        this.temperature = temperature;
        // Ensure everything has the right state
        for (LegacySpecies molecule : contents.keySet()) {
            if (molecule.getBoilingPoint() < temperature) {
                states.put(molecule, 1d);
            } else {
                states.put(molecule, 0d);
            };
        };
        return this; 
    };

    /**
     * Set the state of a {@link com.petrolpark.destroy.chemistry.legacy.LegacySpecies Molecule}.
     * @param molecule The Molecule to set the state of. If not present in this Mixture, nothing happens
     * @param state A number from {@code 0} (entirely liquid) to {@code 1} (entirely gaseous). If out of this range, an exception will be thrown
     */
    public void setState(LegacySpecies molecule, double state) {
        if (state < 0f || state > 1f) throw new IllegalStateException("Molecules can range from entirely liquid (state = 0) to entirely gas (state = 1)");
        if (getConcentrationOf(molecule) > 0f) states.put(molecule, state);
    };

    /**
     * Adds a {@link LegacySpecies} to this Mixture.
     * If the Molecule is already in the Mixture, its concentration is increased by the given amount.
     * @param molecule The Molecule to add
     * @param concentration The initial concentration of this Molecule, or the amount to add
     */
    @Override
    public LegacyMixture addMolecule(LegacySpecies molecule, double concentration) {

        if (getConcentrationOf(molecule) > 0f) { // If we already have this Molecule
            changeConcentrationOf(molecule, concentration, true);
            updateName();
            updateColor();
            return this;
        };

        // If we're not adding a pre-existing Molecule
        internalAddMolecule(molecule, concentration, true);
        equilibrium = false;
        return this;
    };

    public List<LegacySpecies> getContents(boolean excludeNovel) {
        return contents.keySet().stream().filter(molecule -> getConcentrationOf(molecule) > 0f && (!molecule.isNovel() || !excludeNovel)).toList();
    };

    /**
     * Creates a new Mixture by mixing together existing ones. This does not give the volume of the new Mixture.
     * @param mixtures A Map of all Mixtures to their volumes (in any consistent units)
     * @return A new Mixture instance
     */
    public static LegacyMixture mix(Map<LegacyMixture, Double> mixtures) {
        if (mixtures.size() == 0) return new LegacyMixture();
        if (mixtures.size() == 1) return mixtures.keySet().iterator().next();
        LegacyMixture resultMixture = new LegacyMixture();
        Map<LegacySpecies, Double> moleculesAndMoles = new HashMap<>(); // A Map of all Molecules to their quantity in moles (not their concentration)
        Map<ReactionResult, Double> reactionResultsAndMoles = new HashMap<>(); // A Map of all Reaction Results to their quantity in moles
        Map<Item, Double> items = new HashMap<>();
        double totalAmount = 0d;
        float totalEnergy = 0f;

        for (Entry<LegacyMixture, Double> mixtureAndAmount : mixtures.entrySet()) {
            LegacyMixture mixture = mixtureAndAmount.getKey();
            double amount = mixtureAndAmount.getValue();
            totalAmount += amount;

            for (Entry<LegacySpecies, Double> entry : mixture.contents.entrySet()) {
                LegacySpecies molecule = entry.getKey();
                double concentration = entry.getValue();
                moleculesAndMoles.merge(molecule, concentration * amount, (m1, m2) -> m1 + m2); // Add the Molecule to the map if it's a new one, or increase the existing molar quantity otherwise
                totalEnergy += molecule.getMolarHeatCapacity() * concentration * mixture.temperature * amount; // Add all the energy that would be required to raise this Molecule from 0K to its current temperature
                totalEnergy += molecule.getLatentHeat() * concentration * mixture.states.get(molecule) * amount; // Add all the energy that would be required to vaporise this Molecule, if necessary
            };

            for (Entry<ReactionResult, Double> entry : mixture.reactionResults.entrySet()) {
                reactionResultsAndMoles.merge(entry.getKey(), entry.getValue() * amount, (r1, r2) -> r1 + r2); // Same for Reaction Results
            };
            
            for(Entry<Item, Double> entry : mixture.partiallyDissolvedItems.entrySet()) {
            	items.merge(entry.getKey(), entry.getValue() * amount, (r1,r2)-> r1+r2);
            }
        };

        for (Entry<LegacySpecies, Double> moleculeAndMoles : moleculesAndMoles.entrySet()) {
            LegacySpecies molecule = moleculeAndMoles.getKey();
            resultMixture.internalAddMolecule(molecule, (float)(moleculeAndMoles.getValue() / totalAmount), false); // Add all these Molecules to the new Mixture
            resultMixture.states.put(molecule, 0d); // Set it to entirely liquid as we will soon be reheating the Mixture from 0K
        };

        for (Entry<ReactionResult, Double> reactionResultAndMoles : reactionResultsAndMoles.entrySet()) {
            if (reactionResultAndMoles.getKey().getReaction().isPresent())
                resultMixture.incrementReactionResults(reactionResultAndMoles.getKey().getReaction().get(), (float)(reactionResultAndMoles.getValue() / totalAmount)); // Add all Reaction Results to the new Mixture
        };

        for(Entry<Item, Double> item : items.entrySet()) {
        	resultMixture.partiallyDissolvedItems.put(item.getKey(), item.getValue() / totalAmount);
        }
        
        resultMixture.temperature = 0f; // Initially set the temperature of the new Mixture to 0K
        resultMixture.updateNextBoilingPoints();
        resultMixture.heat(totalEnergy / (float)totalAmount); // Now heat it up with the total internal energy of all component Mixtures

        resultMixture.refreshPossibleReactions();
        resultMixture.updateName();
        resultMixture.updateColor();
        resultMixture.updateNextBoilingPoints();

        resultMixture.equilibrium = false; //obviously
        
        return resultMixture;
    };

    @Override
    public double getConcentrationOf(LegacySpecies molecule) {
        return super.getConcentrationOf(molecule);
    };

    /**
     * Whether this Mixture will {@link LegacyMixture#equilibrium react any further}.
     */
    public boolean isAtEquilibrium() {
        return equilibrium;
    };

    /**
     * Let this Mixture know it should no longer be at {@link LegacyMixture#equilibrium equilibrium}.
     */
    public void disturbEquilibrium() {
        equilibrium = false;
    };

    /**
     * Reacts the contents of this Mixture for one tick, if it is not already at {@link LegacyMixture#equilibrium equilibrium}.
     * @param context
     * @param cycles Number of times each tick the reactions should be enacted
     */
    public void reactForTick(ReactionContext context, int cycles) {

        boolean shouldUpdateDisplay = true;
        
        lastTickReactions.clear();

        for (int cycle = 0; cycle < cycles; cycle++) {

            if (equilibrium) { // If we have already reached equilibrium, nothing more is going to happen, so don't bother reacting
                shouldUpdateDisplay = false;
                break; 
            };

            equilibrium = true; // Start by assuming we have reached equilibrium
            boolean shouldRefreshPossibleReactions = false; // Rather than refreshing the possible Reactions every time a new Molecule is added or removed, start by assuming we won't need to, and flag for refreshing if we ever do

            Map<LegacySpecies, Double> oldContents = new HashMap<>(contents); // Copy all the old concentrations of everything

            Map<LegacyReaction, Double> reactionRates = new HashMap<>(); // Rates of all Reactions
            List<LegacyReaction> orderedReactions = new ArrayList<>(); // A list of Reactions in the order of their current rate, fastest first
            Set<LegacyReaction> reactionsThatUsePDItems = new HashSet<>(); //Set of all Reactions that use partially-dissolved items. item-consuming reactions that occur and are not in this list will not check for stoichiometry

            orderEachReaction: for (LegacyReaction possibleReaction : possibleReactions) {

                for (IItemReactant itemReactant : possibleReaction.getItemReactants()) { // Check all Reactions have the necessary Item catalysts
                    boolean validStackFound = false; // Start by assuming we won't have the required Item Stack...
                    boolean validPDFound = false;
                    for(Entry<Item, Double> pdItem : partiallyDissolvedItems.entrySet()) {
                    	if(itemReactant.isItemValid(pdItem.getKey()) && pdItem.getValue() > 0d) {
                    		validPDFound = true;
                    		if(!itemReactant.isCatalyst()) reactionsThatUsePDItems.add(possibleReaction);
                    		break;
                    	}
                    }
                    if(!validPDFound) {
                    	for (ItemStack stack : context.availableItemStacks) {
                    		if (itemReactant.isItemValid(stack)) {
                    			validStackFound = true; // ...If we do, correct this assumption
                    			if(!itemReactant.isCatalyst()) partiallyDissolvedItems.put(stack.getItem(), 0d);
                    			break;
                    		};
                    	};
                    }
                    if (!validStackFound && !validPDFound) continue orderEachReaction; // If we don't have the requesite Item Stacks, don't do this Reaction
                };

                reactionRates.put(possibleReaction, calculateReactionRate(possibleReaction, context) / cycles); // Calculate the Reaction data for this sub-tick
                orderedReactions.add(possibleReaction); // Add the Reaction to the rate-ordered list, which is currently not sorted
            };

            Collections.sort(orderedReactions, (r1, r2) -> reactionRates.get(r1).compareTo(reactionRates.get(r2))); // Sort the Reactions by rate

            doEachReaction: for (LegacyReaction reaction : orderedReactions) { // Go through each Reaction, fastest first

                double molesOfReactionPerLiter = reactionRates.get(reaction); // We are reacting over one tick, so moles of Reaction that take place in this time = rate of Reaction in M per sub-tick

                for (LegacySpecies reactant : reaction.getReactants()) {
                    int reactantMolarRatio = reaction.getReactantMolarRatio(reactant);
                    double reactantConcentration = getConcentrationOf(reactant);
                    if (reactantConcentration < reactantMolarRatio * molesOfReactionPerLiter) { // Determine the limiting reagent, if there is one
                        molesOfReactionPerLiter = reactantConcentration / (float) reactantMolarRatio; // If there is a new limiting reagent, alter the moles of reaction which will take place
                    };
                };
                //Do the same for item reagents too
                if(reactionsThatUsePDItems.contains(reaction)) {
                	for (IItemReactant reactant : reaction.getItemReactants()) {
                		if(reactant.isCatalyst()) continue;
                		float totalMatchingConcentration = 0;
                		for(Entry<Item, Double> pdItem : partiallyDissolvedItems.entrySet()) {
                			if(reactant.isItemValid(pdItem.getKey())) totalMatchingConcentration += pdItem.getValue();
                		}
                		if(totalMatchingConcentration < Constants.MILLIBUCKETS_PER_LITER * 1000 * molesOfReactionPerLiter / reaction.getMolesPerItem()) {
                			molesOfReactionPerLiter = totalMatchingConcentration * reaction.getMolesPerItem() / Constants.MILLIBUCKETS_PER_LITER / 1000;
                		}
                	}
                }
                if (molesOfReactionPerLiter <= 0f) continue doEachReaction; // Don't bother going any further if this Reaction won't happen

                lastTickReactions.merge(reaction, molesOfReactionPerLiter, (f1,f2)-> f1+f2);
                
                shouldRefreshPossibleReactions |= doReaction(reaction, molesOfReactionPerLiter); // Increment the amount of this Reaction which has occured, add all products and remove all reactants
            };

            // Check now if we have actually reached equilibrium or if that was a false assumption at the start
            for (LegacySpecies molecule : oldContents.keySet()) {
                if (!areVeryClose(oldContents.get(molecule), getConcentrationOf(molecule))) { // If there's something that has changed concentration noticeably in this tick...
                    equilibrium = false; // ...we cannot have reached equilibrium
                };
            };

            if (shouldRefreshPossibleReactions) { // If we added a new Molecule at any point
                refreshPossibleReactions();
            };

        };

        // Purge removed Molecules
        boolean shouldUpdateReactions = false;
        Iterator<Entry<LegacySpecies, Integer>> iterator = moleculesToRemove.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry<LegacySpecies, Integer> entry = iterator.next();
            entry.setValue(entry.getValue() - 1);
            if (entry.getValue() <= 0) {
                removeMolecule(entry.getKey()); // Completely remove the Molecule from the Mixture if its TTL has expired
                iterator.remove();  
                shouldUpdateReactions = true; // Now we know the Molecule isn't coming back, we can update the reactions to reflect its removal
            };
        };
        if (shouldUpdateReactions) refreshPossibleReactions();
        
        if (shouldUpdateDisplay) {
            updateName();
            updateColor();
        };
    };

    /**
     * Add or take heat from this Mixture. This will boil/condense Molecules and change the temperature.
     * @param energy In joules per bucket
     */
    public void heat(double energyDensity) {
        double volumetricHeatCapacity = getVolumetricHeatCapacity();
        if (volumetricHeatCapacity == 0f) return;

        double temperatureChange = energyDensity / volumetricHeatCapacity; // The theoretical temperature change if no boiling or condensation occurs

        if (temperatureChange == 0f) {
            return;
        } else if (temperatureChange > 0f) { // If the temperature would be increasing
            if (nextHigherBoilingPoint.getSecond() != null && temperature + temperatureChange >= nextHigherBoilingPoint.getFirst()) { // If a Molecule needs to boil before the temperature can change
                
                temperatureChange = nextHigherBoilingPoint.getFirst() - temperature; // Only increase the temperature by enough to get to the next BP
                temperature += temperatureChange; // Raise the Mixture to the boiling point
                energyDensity -= temperatureChange * getVolumetricHeatCapacity(); // Energy leftover once the Mixture has been raised to the boiling point

                LegacySpecies molecule = nextHigherBoilingPoint.getSecond();
                double liquidConcentration = getConcentrationOf(molecule) * (1f - states.get(molecule)); // The moles per bucket of liquid Molecules
                double energyRequiredToFullyBoil = liquidConcentration * molecule.getLatentHeat(); // The energy density required to boil all remaining liquid

                if (energyDensity > energyRequiredToFullyBoil) { // If there is leftover energy once the Molecule has been boiled
                    states.put(molecule, 1d); // Convert the Molecule fully to gas
                    //temperature += 0.01f; // Increase the temperature slightly so the new next higher Molecule isn't the one we just finished boiling
                    updateNextBoilingPoints(true);
                    boiling = false; // If we're just increasing the temperature, then all Molecule are either fully gaseous or liquid
                    heat(energyDensity - energyRequiredToFullyBoil); // Continue heating
                } else { // If there is no leftover energy and the Molecule is still boiling
                    double boiled = energyDensity / (molecule.getLatentHeat() * getConcentrationOf(molecule)); // The proportion of all of the Molecule which is additionally boiled
                    states.merge(molecule, boiled, (f1, f2) -> f1 + f2);
                    boiling = true; // Set the fact that there is a Molecule which will be not fully gaseous or liquid
                };

                equilibrium = false; // Equilibrium is broken when a Molecule boils

            } else {
                temperature += temperatureChange;
            };
        } else { // If the temperature would be decreasing
            if (nextLowerBoilingPoint.getSecond() != null && temperature + temperatureChange < nextLowerBoilingPoint.getFirst()) { // If a Molecule needs to condense before the temperature can change

                temperatureChange = nextLowerBoilingPoint.getFirst() - temperature; // Only decrease the temperature by enough to get to the next condensation point
                temperature += temperatureChange; // Decrease the Mixture to the boiling point
                energyDensity -= temperatureChange * getVolumetricHeatCapacity(); // Additional energy once the Mixture has been lowered to the condensation point

                LegacySpecies molecule = nextLowerBoilingPoint.getSecond();
                double gasConcentration = getConcentrationOf(molecule) * states.get(molecule);
                double energyReleasedWhenFullyCondensed = gasConcentration * molecule.getLatentHeat(); // The energy density which could be released when all remaining gas is condensed

                if (energyDensity < -energyReleasedWhenFullyCondensed) { // If there is more energy that needs to be released than the condensation can supply
                    states.put(molecule, 0d); // Convert the Molecule fully to liquid
                    //temperature -= 0.01f; // Decrease the temperature slightly so the new next lower Molecule isn't the one we just finished condensing
                    updateNextBoilingPoints(true);
                    boiling = false; // If we're just increasing the temperature, then all Molecule are either fully gaseous or liquid
                    heat(energyDensity + energyReleasedWhenFullyCondensed); // Continue cooling
                } else {
                    double condensed = -energyDensity / (molecule.getLatentHeat() * getConcentrationOf(molecule));
                    states.merge(molecule, 1f - condensed, (f1, f2) -> f1 + f2 - 1f);
                    boiling = true; // Set the fact that a Molecule is currently not fully gaseous or liquid
                };

                equilibrium = false; // Equilibrium is broken when a Molecule condenses

            } else {
                temperature += temperatureChange;
            };
        };

        temperature = Math.max(temperature, 0.0001f);
    };

    /**
     * Corrects the volume of this Mixture, to prevent things like Mixtures containing
     * 1M water and nothing else (which is physically impossible). This is mutative.
     * @param initialVolume The initial volume (in mB)
     * @return The new volume (in mB) of this Mixture
     * @deprecated This doesn't account for the space occupied by gases
     */
    @Deprecated
    public int recalculateVolume(int initialVolume) {
        if (contents.isEmpty()) return 0;
        double initialVolumeInLiters = (double)initialVolume / Constants.MILLIBUCKETS_PER_LITER;
        double newVolumeInLiters = 0d;

        // Molecules
        Map<LegacySpecies, Double> molesOfMolecules = new HashMap<>();
        for (Entry<LegacySpecies, Double> entry : contents.entrySet()) {
            LegacySpecies molecule = entry.getKey();
            double molesOfMolecule = entry.getValue() * initialVolumeInLiters;
            molesOfMolecules.put(molecule, molesOfMolecule);
            newVolumeInLiters += molesOfMolecule / molecule.getPureConcentration();
        };
        for (Entry<LegacySpecies, Double> entry : molesOfMolecules.entrySet()) {
            contents.replace(entry.getKey(), entry.getValue() / newVolumeInLiters);
        };

        // Results
        Map<ReactionResult, Double> resultsCopy = new HashMap<>(reactionResults);
        for (Entry<ReactionResult, Double> entry : resultsCopy.entrySet()) {
            reactionResults.replace(entry.getKey(), entry.getValue() * initialVolumeInLiters / newVolumeInLiters);
        };

        //Items
        final double newVolume = newVolumeInLiters;
        partiallyDissolvedItems.replaceAll((item, molesPerBucket) -> molesPerBucket * initialVolumeInLiters / newVolume);
        
        return (int)((newVolumeInLiters * Constants.MILLIBUCKETS_PER_LITER));
    };

    /**
     * Adjust the concentrations of this Mixture so the number of moles is conserved if the volume is changed. The change is isothermic.
     * @param volumeIncreaseFactor The multiplicative factor which has been applied to the volume.
     */
    public void scale(float volumeIncreaseFactor) {
        contents.replaceAll((molecule, concentration) -> concentration / volumeIncreaseFactor);
        reactionResults.replaceAll((reactionResult, molesPerBucket) -> molesPerBucket / volumeIncreaseFactor);
        partiallyDissolvedItems.replaceAll((item, molesPerBucket) -> molesPerBucket / volumeIncreaseFactor);
    };

    public static record Phases(LegacyMixture gasMixture, Double gasVolume, LegacyMixture liquidMixture, Double liquidVolume) {};

    /**
     * Get two new Mixtures from one - one containing all gas, one containing all liquid.
     * This doesn't mutate this Mixture.
     * @param initialVolume The initial volume of this Mixture from which to scale, ideally in buckets.
     * @return A {@link Phases} record containing:<ul>
     * <li>{@code gasMixture} A new Mixture containing all gases</li>
     * <li>{@code gasVolume} This is always {@code 1d}, allowing it to be rescaled later.</li>
     * <li>{@code liquidMixture} A new Mixture containing all liquids.</li>
     * <li>{@code liquidVolume} A volume of liquid in the same units as {@code initialVolume}.</li></ul>
     */
    public Phases separatePhases(double initialVolume) {
        Map<LegacySpecies, Double> liquidMoles = new HashMap<>();
        Map<LegacySpecies, Double> gasMoles = new HashMap<>();

        double newLiquidVolume = 0d;
        double newGasVolume = 1d;

        LegacyMixture liquidMixture = new LegacyMixture();
        LegacyMixture gasMixture = new LegacyMixture();

        for (Entry<LegacySpecies, Double> entry : contents.entrySet()) {
            LegacySpecies molecule = entry.getKey();
            double concentration = entry.getValue();
            double proportionGaseous = states.get(molecule);

            // Liquid
            double molesOfLiquidMolecule = concentration * (1f - proportionGaseous) * initialVolume;
            liquidMoles.put(molecule, molesOfLiquidMolecule);
            newLiquidVolume += molesOfLiquidMolecule / molecule.getPureConcentration();

            // Gas
            gasMoles.put(molecule, concentration * proportionGaseous * initialVolume);
        };

        // Put Molecules in new Mixtures
        for (Entry<LegacySpecies, Double> entry : liquidMoles.entrySet()) {
            double moles = entry.getValue();
            if (moles == 0d) continue;
            liquidMixture.internalAddMolecule(entry.getKey(), (float)(moles / newLiquidVolume), false);
            liquidMixture.states.put(entry.getKey(), 0d);
        };
        for (Entry<LegacySpecies, Double> entry : gasMoles.entrySet()) {
            double moles = entry.getValue();
            if (moles == 0d) continue;
            gasMixture.internalAddMolecule(entry.getKey(), (float)(moles / newGasVolume), false);
            gasMixture.states.put(entry.getKey(), 1d);
        };

        // Add Reaction Results to new Mixtures
        for (Entry<ReactionResult, Double> entry : reactionResults.entrySet()) {
            double resultMoles = entry.getValue() * initialVolume;
            double newTotalVolume = newLiquidVolume + newGasVolume;
            liquidMixture.reactionResults.put(entry.getKey(), resultMoles / newTotalVolume); // A cancelled-out expression for (resultMoles / liquidVolume)  * (liquidVolume / (liquidVolume + gasVolume)). Essentially we just divvy out the results based on the volumes of the two phases
            gasMixture.reactionResults.put(entry.getKey(), resultMoles / newTotalVolume);
        };
        
        for(Entry<Item, Double> entry : partiallyDissolvedItems.entrySet()) {
        	liquidMixture.partiallyDissolvedItems.put(entry.getKey(), entry.getValue() * initialVolume / newLiquidVolume);
        }
        
        liquidMixture.temperature = temperature;
        gasMixture.temperature = temperature;
        liquidMixture.refreshPossibleReactions();
        gasMixture.refreshPossibleReactions();
        liquidMixture.equilibrium = equilibrium;
        gasMixture.equilibrium = equilibrium;
        
        return new Phases(gasMixture, newGasVolume, liquidMixture, newLiquidVolume);
    };

    /**
     * Increase the number of moles of Reaction which have occured, add all products, and remove all reactants.
     * @param reaction
     * @param molesPerLiter Moles (per liter) of Reaction
     * @return Whether the possible Reactions for this Mixture should be updated
     */
    protected boolean doReaction(LegacyReaction reaction, double molesPerLiter) {

        boolean shouldRefreshPossibleReactions = false;

        for (LegacySpecies reactant : reaction.getReactants()) {
            changeConcentrationOf(reactant, - (molesPerLiter * reaction.getReactantMolarRatio(reactant)), false); // Use up the right amount of all the reagents
        };
        for(IItemReactant itemReactant : reaction.getItemReactants()) {
        	if(itemReactant.isCatalyst()) continue;
        	double requiredReduction = Constants.MILLIBUCKETS_PER_LITER * 1000 * molesPerLiter / reaction.getMolesPerItem();
        	List<Item> usedItems = new ArrayList<>();
        	double totalUsedItemConcentration = 0f;
        	for(Entry<Item, Double> pdItem : partiallyDissolvedItems.entrySet()) {
        		if(itemReactant.isItemValid(pdItem.getKey())) {
        			usedItems.add(pdItem.getKey());
        			totalUsedItemConcentration += pdItem.getValue();
        		}
        	}
        	if(usedItems.isEmpty()) {
        		Destroy.LOGGER.warn("Called doReaction() for a reaction with no valid items in context or mixture!");
        		return false;
        	}
        	//if no real PDStacks exist, that means that there must exist a concentration-0 PDStack corresponding to a real stack in the reaction context
        	if(totalUsedItemConcentration == 0) {
        		partiallyDissolvedItems.put(usedItems.get(0), -requiredReduction);
        	} else //otherwise, stoichiometry is already checked for us in reactForTick()
        	for(Item i : usedItems) {
        		double concentration = partiallyDissolvedItems.get(i);
        		partiallyDissolvedItems.put(i, concentration - requiredReduction * concentration / totalUsedItemConcentration);
        	}
        }
        
        addEachProduct: for (LegacySpecies product : reaction.getProducts()) {
            if (product.isNovel() && getConcentrationOf(product) == 0d) { // If we have a novel Molecule that we don't think currently exists in the Mixture...
                if (internalAddMolecule(product, molesPerLiter * reaction.getProductMolarRatio(product), false)) { // ...add it with this method, as this automatically checks for pre-existing novel Molecules, and if it was actually a brand new Molecule...
                    shouldRefreshPossibleReactions = true; // ...flag this
                }; 
                continue addEachProduct;
            };

            if (!contents.containsKey(product)) { // If we are adding a new product, the possible Reactions will change
                shouldRefreshPossibleReactions = true;
            };
            changeConcentrationOf(product, molesPerLiter * reaction.getProductMolarRatio(product), false); // Increase the concentration of the product
        };

        heat(-reaction.getEnthalpyChange() * 1000 * molesPerLiter);
        incrementReactionResults(reaction, molesPerLiter);
        for(Pair<Supplier<Item>, Double> entry : reaction.getItemProducts()) {
        	partiallyDissolvedItems.merge(entry.getFirst().get(), entry.getSecond() * molesPerLiter, (f1, f2) -> f1+f2);
        }

        return shouldRefreshPossibleReactions;
    };

    /**
     * Increase the number of moles of this Reaction which have occured in this Mixture.
     * @param reaction
     * @param molesPerBucket Moles (per Bucket) of this Reaction
     */
    protected void incrementReactionResults(LegacyReaction reaction, double molesPerBucket) {
        if (!reaction.hasResult()) return;
        ReactionResult result = reaction.getResult();
        reactionResults.merge(result, molesPerBucket, (f1, f2) -> f1 + f2);
    };

    /**
     * {@link LegacyMixture#reactForTick React} this Mixture until it reaches {@link LegacyMixture#equilibrium equilibrium}. This is mutative.
     * @return A {@link com.petrolpark.destroy.recipe.ReactionInBasinRecipe.ReactionInBasinResult ReactionInBasinResult} containing
     * the number of ticks it took to reach equilibrium, the {@link ReactionResult Reaction Results} and the new volume of Mixture.
     * @param volume (in liters) of this Reaction
     * @param availableStacks Item Stacks available for reacting. This List and its contents will be modified.
     * @param heatingPower The power being supplied to this Basin by the {@link com.petrolpark.destroy.util.vat.IVatHeaterBlock heater} below it.
     * @param outsideTemperature The {@link com.petrolpark.destroy.capability.Pollution#getLocalTemperature temperature} outside the Basin.
     */
    public ReactionInBasinResult reactInBasin(int volume, List<ItemStack> availableStacks, float heatingPower, float outsideTemperature) {
        float volumeInLiters = (float)volume / Constants.MILLIBUCKETS_PER_LITER;
        int ticks = 0;

        while (!equilibrium && ticks < 600) { // React the Mixture
            float energyChange = heatingPower / TICKS_PER_SECOND;
            energyChange += (outsideTemperature - temperature) * 100f / TICKS_PER_SECOND; // Fourier's Law (sort of), the Basin has a fixed conductance of 100 andthe divide by 20 is for 20 ticks per second
            if (Math.abs(energyChange) > 0.0001f) {
                heat(1000 * energyChange / volume); // 1000 converts getFluidAmount() in mB to Buckets
            };
            
            ReactionContext context = new ReactionContext(availableStacks, 0f, false); //TODO uv in basins? maybe?
            
            //dissolve new items if necessary
            Iterator<ItemStack> iter = availableStacks.iterator();
            while(iter.hasNext()) {
            	ItemStack stack = iter.next();
            	Double current = partiallyDissolvedItems.get(stack.getItem());
            	if(current != null && current < 0) {
            		if(stack.getCount() <= 1) {
            			iter.remove();
            		} else {
            			stack.shrink(1);
            		}
            		partiallyDissolvedItems.put(stack.getItem(), current + 1000d/volume);
            	}
            }
            
            reactForTick(context, VatControllerBlockEntity.getSimulationLevel());
            
            //precipitate
            for(Entry<Item, Double> solid : partiallyDissolvedItems.entrySet()) {
            	if(solid.getValue() > 1000d/volume) {
            		boolean addedToStack = false;
            		for(ItemStack stack : availableStacks) {
            			if(ItemHandlerHelper.canItemStacksStackRelaxed(stack, new ItemStack(solid.getKey(), 1))) {
            				addedToStack = true;
            				stack.grow(1);
            			}
            		}
            		if(!addedToStack) {
            			availableStacks.add(new ItemStack(solid.getKey(), 1));
            		}
            		solid.setValue(solid.getValue() - 1000d/volume);
            	}
            }
            
            ticks++;
        };

        if (ticks == 0) return new ReactionInBasinResult(0, Map.of(), volume); // If no reactions occured (because we were already at equilibrium), cancel early

        int amount = recalculateVolume(volume);

        return new ReactionInBasinResult(ticks, getCompletedResults(amount), amount);
    };

    /**
     * If any {@link LegacyMixture#reactionResults results} have had enough moles to have occured, remove them from the Mixture and return them here.
     * This is mutative.
     * @param volumeInLiters The amount of Mixture
     * @return A Set of Reaction Results mapped to the number of times that Reaction result occurs
     */
    public Map<ReactionResult, Integer> getCompletedResults(double volumeInLiters) {
        Map<ReactionResult, Integer> results = new HashMap<>();
        if (reactionResults.isEmpty()) return results;
        for (ReactionResult result : reactionResults.keySet()) {

            if (result.isOneOff()) {
                results.put(result, 1);
                continue;
            };

            double molesPerLiterOfReaction = reactionResults.get(result);
            int numberOfResult = (int) (volumeInLiters * molesPerLiterOfReaction / result.getRequiredMoles());
            if (numberOfResult == 0) continue;

            // Decrease the amount of Reaction that has happened
            reactionResults.replace(result, molesPerLiterOfReaction - numberOfResult * result.getRequiredMoles() / volumeInLiters);

            results.put(result, numberOfResult);
        };
        // reactionResults.keySet().removeIf(result -> { // Remove any one-off Results and Results which have run out
        //     return result.isOneOff() || areVeryClose(reactionResults.get(result), 0f);
        // }); 
        return results;
    };

    /**
     * Get the heat capacity (in joules per bucket-kelvin) of this Mixture. Probably best to cache this instead of calling it repeatedly.
     */
    public double getVolumetricHeatCapacity() {
        float totalHeatCapacity = 0f;
        for (Entry<LegacySpecies, Double> entry : contents.entrySet()) {
            totalHeatCapacity += entry.getKey().getMolarHeatCapacity() * entry.getValue();
        };
        return totalHeatCapacity;
    };

    /**
     * Set the Molecules which will be next to condense or boil if the temperature of this Mixture changes.
     */
    protected void updateNextBoilingPoints() { updateNextBoilingPoints(false); }
    protected void updateNextBoilingPoints(boolean ignoreCurrentTemperature) {
        nextHigherBoilingPoint = Pair.of(Float.MAX_VALUE, null);
        nextLowerBoilingPoint = Pair.of(0f, null);
        for (LegacySpecies molecule : contents.keySet()) {
            float bp = molecule.getBoilingPoint();
            if (bp < temperature || (bp == temperature && !ignoreCurrentTemperature)) {
                if (bp > nextLowerBoilingPoint.getFirst()) nextLowerBoilingPoint = Pair.of(bp, molecule);
            }
            if (bp > temperature || (bp == temperature && !ignoreCurrentTemperature)) { // If the boiling point is higher than the current temperture.
                if (bp < nextHigherBoilingPoint.getFirst()) nextHigherBoilingPoint = Pair.of(bp, molecule);
            };
        };
    };

    /**
     * Adds a {@link LegacySpecies} to this Mixture.
     * If a novel Molecule is being added, it is checked against pre-existing novel Molecules
     * and if a matching one already exists, the concentration of it is increased.
     * @param molecule The Molecule to add
     * @param concentration The starting concentration for the Molecule
     * @param shouldRefreshReactions Whether to {@link LegacyMixture#refreshPossibleReactions refresh possible Reactions} -
     * this should only be set to false when multiple Molecules are being added/removed at once (such as when {@link LegacyMixture#reactForTick reacting})
     * and it makes sense to only refresh the Reactions once
     * @return {@code true} if the possible reactions need to be refreshed (because a new Molecule was added); {@code false} otherwise
     * @see LegacyMixture#addMolecule The wrapper for this method
     * @see LegacyMixture#changeConcentrationOf Modifying the concentration of pre-existing Molecule
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean internalAddMolecule(LegacySpecies molecule, double concentration, boolean shouldRefreshReactions) {

        boolean newMoleculeAdded = true; // Start by assuming we're adding a brand new Molecule to this solution

        if (contents.containsKey(molecule)) { // Just in case this Molecule is already in the Mixture, increase its concentration
            changeConcentrationOf(molecule, concentration, shouldRefreshReactions);
            return false;
        };

        if (!molecule.isNovel()) super.addMolecule(molecule, concentration);

        List<LegacyFunctionalGroup<?>> functionalGroups = molecule.getFunctionalGroups();
        if (functionalGroups.size() != 0) {
            for (LegacyFunctionalGroup group : functionalGroups) { // Unparameterised raw type
                addGroupToMixture(molecule, group); // Unchecked conversion
            };
        };

        if (molecule.isNovel()) { // If this is a novel Molecule, it might already match to one of our existing novel Molecules
            boolean found = false; // Start by assuming it's not already in the Mixture
            for (LegacySpecies novelMolecule : novelMolecules) { // Check every novel Molecule
                if (novelMolecule.getFullID().equals(molecule.getFullID())) {
                    found = true;
                    newMoleculeAdded = false; // We haven't actually added a brand new Molecule so flag this
                    changeConcentrationOf(novelMolecule, concentration, true);
                    equilibrium = false;
                };
            };
            if (!found) {
                super.addMolecule(molecule, concentration);
                novelMolecules.add(molecule); // If it was actually a brand new Molecule, add it to the novel list
            };
            if (newMoleculeAdded) {
                reactionResults.put(new NovelCompoundSynthesizedReactionResult(0f, null, molecule), 1d);
            };
        };

        if (shouldRefreshReactions && newMoleculeAdded) {
            refreshPossibleReactions();
        };

        equilibrium = false;

        return newMoleculeAdded; // Return whether or not we actually added a brand new Molecule
    };

    private <G extends LegacyFunctionalGroup<G>> void addGroupToMixture(LegacySpecies molecule, G group) {
        LegacyFunctionalGroupType<? extends G> groupType = group.getType();
        if (!groupIDsAndMolecules.containsKey(groupType)) {
            groupIDsAndMolecules.put(groupType, new ArrayList<>());
        };
        groupIDsAndMolecules.get(groupType).add(new GenericReactant<>(molecule, group));
    };

    /**
     * Removes the given {@link LegacySpecies} from this Mixture, if that Molecule is already in it.
     * This does not refresh possible {@link LegacyReaction Reactions}.
     * @param molecule
     * @return This Mixture
     */
    private LegacyMixture removeMolecule(LegacySpecies molecule) {

        List<LegacyFunctionalGroup<?>> functionalGroups = molecule.getFunctionalGroups();
        if (functionalGroups.size() != 0) {
            for (LegacyFunctionalGroup<?> group : functionalGroups) {
                groupIDsAndMolecules.get(group.getType()).removeIf((reactant) -> {
                    return reactant.getMolecule() == molecule;
                });
            };
        };
        if (molecule.isNovel()) novelMolecules.remove(molecule);

        contents.remove(molecule);
        equilibrium = false; // As we have removed a Molecule the position of equilibrium is likely to change
        updateNextBoilingPoints();

        return this;
    };

    /**
     * Alters the concentration of a {@link LegacySpecies} in a Mixture.
     * This does not update the {@link ReadOnlyMixture#getName name} or equilibrium status of the Mixture.
     * @param molecule If not present in the Mixture, will be added to the Mixture
     * @param change The <em>change</em> in concentration, not the new value (can be positive or negative)
     * @param shouldRefreshReactions Whether to alter the possible {@link LegacyReaction Reactions} in the case that a new Molecule is added to the Mixture (should almost always be {@code true})
     */
    private LegacyMixture changeConcentrationOf(LegacySpecies molecule, double change, boolean shouldRefreshReactions) {
        double currentConcentration = getConcentrationOf(molecule);

        if (!contents.containsKey(molecule) && change > 0f) internalAddMolecule(molecule, change, shouldRefreshReactions);

        if (currentConcentration <= 0f && change < 0f) throw new IllegalArgumentException("Attempted to decrease concentration of Molecule '" + molecule.getFullID()+"', which was not in a Mixture. The Mixture contains " + getContentsString());

        double newConcentration = Math.max(currentConcentration + change, 0d);
        contents.replace(molecule, newConcentration);
        if (newConcentration <= 0f) moleculesToRemove.put(molecule, 10); // Mark this Molecule as imminent for removal - but don't actually remove it in case it gets added back soon
        if (newConcentration > 0f) moleculesToRemove.remove(molecule); // This molecule no longer needs to be removed if it was going to be
        return this;
    };

    /**
     * Get the rate - in moles of Reaction per Liter <em>per tick</em> (not per second) - at which this {@link LegacyReaction} will proceed in this Mixture.
     * @param reaction
     */
    private double calculateReactionRate(LegacyReaction reaction, ReactionContext context) {
        double rate = reaction.getRateConstant(temperature) / TICKS_PER_SECOND;
        for (LegacySpecies molecule : reaction.getOrders().keySet()) {
            rate *= Math.pow(getConcentrationOf(molecule), reaction.getOrders().get(molecule));
        };
        if (reaction.needsUV()) rate *= context.UVPower;
        return rate;
    };

    /**
     * Determine all {@link LegacyReaction Reactions} - including {@link GenericReactions Generic Reactions} that are possible with the {@link LegacySpecies Molecules} in this Mixture,
     * and update the {@link LegacyMixture#possibleReactions stored possible Reactions} accordingly.
     * This should be called whenever new Molecules have been {@link LegacyMixture#addMolecule added} to the Mixture, or a Molecule has been removed entirely, but rarely otherwise.
     */
    private void refreshPossibleReactions() {
        possibleReactions = new ArrayList<>();
        Set<LegacyReaction> newPossibleReactions = new HashSet<>();

        // Generate specific Generic Reactions
        for (LegacyFunctionalGroupType<?> groupType : groupIDsAndMolecules.keySet()) { // Only search for Generic Reactions of Groups present in this Molecule
            checkEachGenericReaction: for (GenericReaction genericReaction : LegacyFunctionalGroup.getReactionsOfGroupByID(groupType)) {

                if (!genericReaction.isPossibleIn(this)) continue checkEachGenericReaction;

                if (genericReaction.involvesSingleGroup()) { // Generic Reactions involving only one functional Group
                    newPossibleReactions.addAll(specifySingleGroupGenericReactions(genericReaction, groupIDsAndMolecules.get(groupType)));
                
                } else { // Generic Reactions involving two functional Groups
                    if (!(genericReaction instanceof DoubleGroupGenericReaction<?, ?> dggr)) continue checkEachGenericReaction; // This check should never fail
                    if (groupType != dggr.getFirstGroupType()) continue checkEachGenericReaction; // Only generate Reactions when we're dealing with the first Group type
                    
                    LegacyFunctionalGroupType<?> secondGroupType = dggr.getSecondGroupType();
                    if (!groupIDsAndMolecules.keySet().contains(secondGroupType)) continue checkEachGenericReaction; // We can't do this generic reaction if we only have one group type
                    
                    List<Pair<GenericReactant<?>, GenericReactant<?>>> reactantPairs = new ArrayList<>();
                    for (GenericReactant<?> firstGenericReactant : groupIDsAndMolecules.get(groupType)) {
                        for (GenericReactant<?> secondGenericReactant : groupIDsAndMolecules.get(secondGroupType)) {
                            reactantPairs.add(Pair.of(firstGenericReactant, secondGenericReactant));
                        };
                    };

                    newPossibleReactions.addAll(specifyDoubleGroupGenericReactions(dggr, reactantPairs));
                };
            };
        };

        //All Reactions
        for (LegacySpecies possibleReactant : contents.keySet()) {
            newPossibleReactions.addAll(possibleReactant.getReactantReactions());
        };
        for (LegacyReaction reaction : newPossibleReactions) {
            //possibleReactions.add(reaction);

            /* 
             * This checks if all necessary Reactants were present before proceeding, however this leads to some infinite loops
             * where one half of a reversible Reaction would happen one tick, then the other one the next, etc.
             */ 
            boolean reactionHasAllReactants = true;
            for (LegacySpecies necessaryReactantOrCatalyst : reaction.getOrders().keySet()) {
                if (!contents.containsKey(necessaryReactantOrCatalyst)) { //TODO maybe
                    reactionHasAllReactants = false;
                    break;
                };
            };
            if (reactionHasAllReactants) {
                possibleReactions.add(reaction);
            };
        };

    };

    /**
     * Given a {@link SingleGroupGenericReaction Generic Reaction} involving only one {@link LegacyFunctionalGroup functional Group},
     * generates the specified {@link LegacyReaction Reactions} that apply to this Mixture.
     * 
     * <p>For example, if the Generic Reaction supplied is the {@link com.petrolpark.destroy.chemistry.legacy.index.genericreaction.SaturatedCarbonHydrolysis hydration of an alkene},
     * and <b>reactants</b> includes {@code destroy:ethene}, the returned collection will include a Reaction with {@code destroy:ethene} and {@code destroy:water} as reactants,
     * {@code destroy:ethanol} as a product, and all the appropriate rate constants and catalysts as defined in the {@link com.petrolpark.destroy.chemistry.index.SaturatedCarbonHydrolysis.AlkeneHydration#generateReaction generator}.</p>
     * 
     * @param <G> <b>G</b> The Group to which this Generic Reaction applies
     * @param genericReaction
     * @param reactants All {@link GenericReactant Reactants} that have the Group
     * @return A Collection of all specified Reactions
     */
    @SuppressWarnings("unchecked")
    private <G extends LegacyFunctionalGroup<G>> List<LegacyReaction> specifySingleGroupGenericReactions(GenericReaction genericReaction, List<GenericReactant<?>> reactants) {
        List<LegacyReaction> reactions = new ArrayList<>();
        SingleGroupGenericReaction<G> singleGroupGenericReaction = (SingleGroupGenericReaction<G>) genericReaction; // Unchecked conversion
        for (GenericReactant<?> reactant : reactants) {
            try {
                LegacyReaction reaction = singleGroupGenericReaction.generateReaction((GenericReactant<G>)reactant);
                if (reaction != null) reactions.add(reaction); // Unchecked conversion
            } catch(ChemistryException e) {
                // Don't do anything for chemistry exceptions
            };
        };
        return reactions;
    };

    /**
     * Given a {@link DoubleGroupGenericReaction Generic Reaction} involving two {@link LegacyFunctionalGroup functional Groups},
     * generates the specified {@link LegacyReaction Reactions} that apply to this Mixture.
     * 
     * <p>For example, if the Generic Reaction supplied is {@link com.petrolpark.destroy.chemistry.legacy.index.genericreaction.AcylChlorideEsterification esterification},
     * and this this Mixture contains methanoyl chloride, ethanoyl chloride, and ethanol, the returned collection will include two Reactions, one of which makes 
     * ethyl ethanoate and the other ethyl methanoate.</p>
     * 
     * @param <G1> <b>G1</b> The first Group to which this Generic Reaction applies
     * @param <G2> <b>G2</b> The second Group to which this Generic Reaction applies
     * @param genericReaction
     * @param reactantPairs All possible pairs of {@link GenericReactant Reactants} in this Mixture
     * @return A Collection of specified Reactions
     * @see LegacyMixture#specifySingleGroupGenericReactions A more in-depth description
     */
    @SuppressWarnings("unchecked")
    private <G1 extends LegacyFunctionalGroup<G1>, G2 extends LegacyFunctionalGroup<G2>> List<LegacyReaction> specifyDoubleGroupGenericReactions(GenericReaction genericReaction, List<Pair<GenericReactant<?>, GenericReactant<?>>> reactantPairs) {
            DoubleGroupGenericReaction<G1, G2> doubleGroupGenericReaction = (DoubleGroupGenericReaction<G1, G2>) genericReaction; // Unchecked conversion
            List<LegacyReaction> reactions = new ArrayList<>();
            for (Pair<GenericReactant<?>, GenericReactant<?>> reactantPair : reactantPairs) {
                if (reactantPair.getFirst().getMolecule() == reactantPair.getSecond().getMolecule()) continue; // Cannot React Molecules with themselves
                try {
                    LegacyReaction reaction = doubleGroupGenericReaction.generateReaction((GenericReactant<G1>)reactantPair.getFirst(), (GenericReactant<G2>)reactantPair.getSecond());
                    if (reaction != null) reactions.add(reaction); // Unchecked conversions {
                } catch(ChemistryException e) {
                    // Do nothing for chemistry exceptions
                };
            };
            return reactions;
    };

    public static boolean areVeryClose(double f1, double f2) {
        return Math.abs(f1 - f2) <= (f1 + f2) / 512d / 512d;
    };

    /**
     * The context for the {@link LegacyMixture#reactForTick reaction} of a {@link LegacyMixture}.
     * <strong>Do not modify its fields, or anything contained within them.</em>
     */
    public static class ReactionContext {

        public final ImmutableList<ItemStack> availableItemStacks;
        public final float UVPower;
        public final boolean electrolysing;

        public ReactionContext(List<ItemStack> availableItemStacks, float UVPower, boolean electrolysing) {
            this.availableItemStacks = ImmutableList.copyOf(availableItemStacks);
            this.UVPower = UVPower;
            this.electrolysing = electrolysing;
        };
    };
};
