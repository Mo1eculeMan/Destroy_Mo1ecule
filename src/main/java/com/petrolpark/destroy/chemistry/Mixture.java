package com.petrolpark.destroy.chemistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import java.util.Set;

import com.google.common.collect.ImmutableList;
import com.petrolpark.destroy.Destroy;
import com.petrolpark.destroy.chemistry.genericreaction.GenericReactant;
import com.petrolpark.destroy.chemistry.genericreaction.GenericReaction;
import com.petrolpark.destroy.chemistry.genericreaction.SingleGroupGenericReaction;
import com.petrolpark.destroy.chemistry.index.DestroyMolecules;
import com.petrolpark.destroy.recipe.ReactionInBasinRecipe.ReactionInBasinResult;
import com.simibubi.create.foundation.utility.NBTHelper;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

public class Mixture extends ReadOnlyMixture {

    private static final int TICKS_PER_SECOND = 20;

    /**
     * A Map of all {@link ReactionResult Results} of {@link Reaction Reactions} generated in this Mixture, mapped
     * to their 'concentrations' (moles of the Reaction which have occured per Bucket of this Mixture, since the last
     * instance of that Reaction Result was dealt with).
     */
    private Map<ReactionResult, Float> reactionresults;
    /**
     * {@link Molecule Molecules} which do not have a name space or ID.
     */
    private List<Molecule> novelMolecules;

    /**
     * All {@link Reaction Reactions} with specific Reactants and specified {@link GenericReaction Generic Reactions}
     * which are possible given the {@link Molecule Molecules} in this Mixture.
     */
    private List<Reaction> possibleReactions;

    /**
     * Every {@link Molecule} in this Mixture that has a {@link Group functional Group}, indexed by the {@link Group#getID ID} of that Group.
     * Molecules are stored as {@link com.petrolpark.destroy.chemistry.genericreaction.GenericReactant Generic Reactants}.
     * Molecules which have multiple of the same Group are indexed for each occurence of the Group.
     */
    private Map<String, List<GenericReactant<?>>> groupIDsAndMolecules;

    /**
     * Whether this Mixture has reached equilibrium. This means either:
     * <ul>
     * <li>No more {@link Reaction Reactions} will occur.</li>
     * <li>Any further Reactions that occur will balance each other out and there will be no change in concentration of any {@link Molecule}.</li>
     * </ul>
     */
    private boolean equilibrium;

    public Mixture() {
        super();

        reactionresults = new HashMap<>();

        novelMolecules = new ArrayList<>();

        possibleReactions = new ArrayList<>();
        groupIDsAndMolecules = new HashMap<>();

        equilibrium = false;
    };

    /**
     * Get a Mixture containing only the given Molecule, unless it is charged, in which case get a Mixture
     * containing the sodium salt or chloride of the ion.
     * @param molecule
     */
    public static Mixture pure(Molecule molecule) {
        Mixture mixture = new Mixture();
        if (molecule.getCharge() == 0) {
            mixture.addMolecule(molecule, molecule.getPureConcentration());
            return mixture;
        };
        Molecule otherIon = molecule.getCharge() < 0 ? DestroyMolecules.SODIUM_ION : DestroyMolecules.CHLORIDE;
        int chargeMagnitude = Math.abs(molecule.getCharge());
        mixture.addMolecule(molecule, 1f);
        mixture.addMolecule(otherIon, chargeMagnitude);
        mixture.recalculateVolume(1000);
        return mixture;
    };

    public static Mixture readNBT(CompoundTag compound) {
        Mixture mixture = new Mixture();
        
        if (compound == null) {
            Destroy.LOGGER.warn("Null Mixture loaded");
            return mixture;
        };

        mixture.translationKey = compound.getString("TranslationKey"); // Set to "" if the key is not present

        if (compound.contains("Temperature")) mixture.temperature = compound.getFloat("Temperature");

        ListTag contents = compound.getList("Contents", Tag.TAG_COMPOUND);
        contents.forEach(tag -> {
            CompoundTag moleculeTag = (CompoundTag) tag;
            mixture.addMolecule(Molecule.getMolecule(moleculeTag.getString("Molecule")), moleculeTag.getFloat("Concentration"));
        });

        mixture.equilibrium = compound.getBoolean("AtEquilibrium");

        if (compound.contains("Results", Tag.TAG_LIST)) {
            ListTag results = compound.getList("Results", Tag.TAG_COMPOUND);
            results.forEach(tag -> {
                CompoundTag resultTag = (CompoundTag) tag;
                ReactionResult result = Reaction.get(resultTag.getString("Result")).getResult();
                if (result == null) return;
                mixture.reactionresults.put(result, resultTag.getFloat("MolesPerBucket"));
            });
        };

        mixture.updateName();
        if (!mixture.equilibrium) mixture.refreshPossibleReactions();

        return mixture;
    };

    @Override
    public CompoundTag writeNBT() {
        CompoundTag tag = super.writeNBT();
        tag.putBoolean("AtEquilibrium", equilibrium);

        if (!reactionresults.isEmpty()) {
            tag.put("Results", NBTHelper.writeCompoundList(reactionresults.entrySet(), entry -> {
                CompoundTag resultTag = new CompoundTag();
                resultTag.putString("Result", entry.getKey().getReaction().getFullId());
                resultTag.putFloat("MolesPerBucket", entry.getValue());
                return resultTag;
            }));
        };
        return tag;
    };

    /**
     * Set the temperature (in kelvins) of this Mixture. This is mutative.
     * @param temperature in Kelvins
     * @return This Mixture
     */
    public Mixture setTemperature(float temperature) {
        this.temperature = temperature;
        return this; 
    };

    /**
     * Adds a {@link Molecule} to this Mixture.
     * If the Molecule is already in the Mixture, its concentration is increased by the given amount.
     * @param molecule The Molecule to add
     * @param concentration The initial concentration of this Molecule, or the amount to add
     */
    @Override
    public Mixture addMolecule(Molecule molecule, float concentration) {

        if (getConcentrationOf(molecule) > 0f) { // If we already have this Molecule
            changeConcentrationOf(molecule, concentration, true);
            updateName();
            return this;
        };

        // If we're not adding a pre-existing Molecule
        internalAddMolecule(molecule, concentration, true);
        equilibrium = false;
        return this;
    };

    /**
     * Creates a new Mixture by mixing together existing ones. This does not give the volume of the new Mixture.
     * @param mixtures A Map of all Mixtures to their volumes (in Buckets)
     * @return A new Mixture instance
     */
    public static Mixture mix(Map<Mixture, Double> mixtures) {
        if (mixtures.size() == 1) return mixtures.keySet().iterator().next();
        Mixture resultMixture = new Mixture();
        Map<Molecule, Double> moleculesAndMoles = new HashMap<>(); // A Map of all Molecules to their quantity in moles (not their concentration)
        Map<ReactionResult, Double> reactionresultsAndMoles = new HashMap<>(); // A Map of all Reaction Results to their quantity in moles
        double totalAmount = 0d;

        for (Entry<Mixture, Double> mixtureAndAmount : mixtures.entrySet()) {
            Mixture mixture = mixtureAndAmount.getKey();
            double amount = mixtureAndAmount.getValue();
            totalAmount += amount;

            for (Entry<Molecule, Float> entry : mixture.contents.entrySet()) {
                moleculesAndMoles.merge(entry.getKey(), entry.getValue() * amount, (m1, m2) -> m1 + m2); // Add the Molecule to the map if it's a new one, or increase the existing molar quantity otherwise
            };

            for (Entry<ReactionResult, Float> entry : mixture.reactionresults.entrySet()) {
                reactionresultsAndMoles.merge(entry.getKey(), entry.getValue() * amount, (r1, r2) -> r1 + r2); // Same for Reaction Results
            };
        };

        for (Entry<Molecule, Double> moleculeAndMoles : moleculesAndMoles.entrySet()) {
            resultMixture.internalAddMolecule(moleculeAndMoles.getKey(), (float)(moleculeAndMoles.getValue() / totalAmount), false); // Add all these Molecules to the new Mixture
        };

        for (Entry<ReactionResult, Double> reactionresultAndMoles : reactionresultsAndMoles.entrySet()) {
            resultMixture.incrementReactionResults(reactionresultAndMoles.getKey().getReaction(), (float)(reactionresultAndMoles.getValue() / totalAmount)); // Add all Reaction Results to the new Mixture
        };

        resultMixture.refreshPossibleReactions();
        resultMixture.updateName();
        //TODO determine temperature

        return resultMixture;
    };

    @Override
    public float getConcentrationOf(Molecule molecule) {
        return super.getConcentrationOf(molecule);
    };

    /**
     * Checks that this Mixture contains a suitable concentration of the given {@link Molecule}, and that all other substances present are solvents or low-concentration impurities.
     * This is used in Recipes.
     * @param molecule Only known (non-novel) Molecules (i.e. those with a name space) will be detected
     * @param concentration
     * @param ignoreableMolecules Molecules other than solvents and low-concentration impurities that should be ignored should return {@code true}. The predicate can be {@code null} if there are no other Molecules that can be ignored
     */
    public boolean hasUsableMolecule(Molecule molecule, float concentration, @Nullable Predicate<Molecule> ignore) {
        if (!contents.containsKey(molecule)) return false;
        if (ignore == null) ignore = (m) -> false;
        if (Math.abs(concentration - getConcentrationOf(molecule)) > IMPURITY_THRESHOLD) return false; //TODO replace with a more lenient check
        for (Entry<Molecule, Float> otherMolecule : contents.entrySet()) {
            if (ignore.test(molecule)) continue; // If this molecule is specified as ignoreable, ignore it
            if (otherMolecule.getKey() == molecule) continue; // If this is the Molecule we want, ignore it.
            if (otherMolecule.getKey().hasTag(DestroyMolecules.Tags.SOLVENT)) continue; // If this is a solvent, ignore it
            if (otherMolecule.getValue() < IMPURITY_THRESHOLD) continue; // If this impurity is in low-enough concentration, ignore it.
            return false;
        };
        return true;
    };

    /**
     * Whether this Mixture will {@link Mixture#equilibrium react any further}.
     */
    public boolean isAtEquilibrium() {
        return equilibrium;
    };

    /**
     * Reacts the contents of this Mixture for one tick, if it is not already at {@link Mixture#equilibrium equilibrium}.
     * @param context
     */
    public void reactForTick(ReactionContext context) {

        if (equilibrium) return; // If we have already reached equilibrium, nothing more is going to happen, so don't bother reacting

        equilibrium = true; // Start by assuming we have reached equilibrium
        boolean shouldRefreshPossibleReactions = false; // Rather than refreshing the possible Reactions every time a new Molecule is added or removed, start by assuming we won't need to, and flag for refreshing if we ever do

        Map<Molecule, Float> oldContents = new HashMap<>(contents); // Copy all the old concentrations of everything

        Map<Reaction, Float> reactionRates = new HashMap<>(); // Rates of all Reactions
        List<Reaction> orderedReactions = new ArrayList<>(); // A list of Reactions in the order of their current rate, fastest first

        orderEachReaction: for (Reaction possibleReaction : possibleReactions) {
            if (possibleReaction.consumesItem()) continue orderEachReaction; // Don't include Reactions which CONSUME Items at this stage

            for (IItemReactant itemReactant : possibleReaction.getItemReactants()) { // Check all Reactions have the necessary Item catalysts
                boolean validStackFound = false; // Start by assuming we won't have the required Item Stack...
                checkAllItems: for (ItemStack stack : context.getItemStacks()) {
                    if (itemReactant.isItemValid(stack)) {
                        validStackFound = true; // ...If we do, correct this assumption
                        break checkAllItems;
                    };
                };
                if (!validStackFound) continue orderEachReaction; // If we don't have the requesite Item Stacks, don't do this Reaction
            };

            reactionRates.put(possibleReaction, calculateReactionRate(possibleReaction)); // Calculate the Reaction data for this tick
            orderedReactions.add(possibleReaction); // Add the Reaction to the rate-ordered list, which is currently not sorted
        };

        Collections.sort(orderedReactions, (r1, r2) -> reactionRates.get(r1).compareTo(reactionRates.get(r2))); // Sort the Reactions by rate

        doEachReaction: for (Reaction reaction : orderedReactions) { // Go through each Reaction, fastest first

            Float molesOfReaction = reactionRates.get(reaction); // We are reacting over one tick, so moles of Reaction that take place in this time = rate of Reaction in M per tick

            for (Molecule reactant : reaction.getReactants()) {
                int reactantMolarRatio = reaction.getReactantMolarRatio(reactant);
                float reactantConcentration = getConcentrationOf(reactant);
                if (reactantConcentration < reactantMolarRatio * molesOfReaction) { // Determine the limiting reagent, if there is one
                    molesOfReaction = reactantConcentration / (float) reactantMolarRatio; // If there is a new limiting reagent, alter the moles of reaction which will take place
                    shouldRefreshPossibleReactions = true; // If there is a new limiting reagent, one Molecule is going to be used up, so the possible Reactions will change
                };
            };

            if (molesOfReaction <= 0f) continue doEachReaction; // Don't bother going any further if this Reaction won't happen

            shouldRefreshPossibleReactions |= doReaction(reaction, molesOfReaction); // Increment the amount of this Reaction which has occured, add all products and remove all reactants
        };

        // Check now if we have actually reached equilibrium or if that was a false assumption at the start
        for (Molecule molecule : oldContents.keySet()) {
            if (!areVeryClose(oldContents.get(molecule), getConcentrationOf(molecule))) { // If there's something that has changed concentration noticeably in this tick...
                equilibrium = false; // ...we cannot have reached equilibrium
            };
        };

        if (shouldRefreshPossibleReactions) { // If we added a new Molecule or removed an old one at any point
            refreshPossibleReactions();
        };

        updateName();
    };

    /**
     * Add or take heat from this Mixture.
     * @param energy In joules per bucket
     */
    public void heat(float energyDensity) {
        // We have assumed that the latent heat of all substances is 0
        temperature += energyDensity / getVolumetricHeatCapacity();
    };

    /**
     * Enact all {@link Reactions} that {@link Reaction#getItemReactants involve Item Stacks}. This does not just
     * include dissolutions, but Item-catalyzed Reactions too.
     * @param availableStacks The Item Stacks available to this Mixture. This Stacks in this List will be modified
     * @param volume The amount of this Mixture there is, in buckets
     */
    public void dissolveItems(List<ItemStack> availableStacks, double volume) {
        if (availableStacks.isEmpty()) return;
        boolean shouldRefreshReactions = false;

        List<Reaction> orderedReactions = new ArrayList<>();

        for (Reaction possibleReaction : possibleReactions) {
            if (!possibleReaction.consumesItem()) continue; // Ignore Reactions which don't consume Items
            orderedReactions.add(possibleReaction); // Add the Reaction to the list of possible Reactions, which is currently not ordered
        };

        if (orderedReactions.isEmpty()) return; // Don't go any further if there aren't any items to dissolve

        Collections.sort(possibleReactions, (r1, r2) -> ((Float)calculateReactionRate(r1)).compareTo(calculateReactionRate(r2))); // Order the list of Item-consuming Reactions by rate, in case multiple of them want the same Item

        tryEachReaction: for (Reaction reaction : orderedReactions) {

            /*
             * Copies of available Stacks mapped to their real counterparts.
             * When simulating, check the copies, as multiple different Item Reactants for one Reaction might want the same Item Stack
             * and we want to make sure a Stack doesn't get used twice. If a Stack's copy is successfully used, its original is added
             * to the second Map so if the simulation is successful, the Item Reactant can easily find the Item Stack it is to consume.
             * This is still not perfect as if Reactant A wants Stacks A or B, and Reactant B only wants Stack A, and Reactant A gets
             * Stack A before Reactant B can, it will fail. Therefore you should be careful about what Item Stacks your Reaction wants.
             */
            Map<ItemStack, ItemStack> copiesAndStacks = new HashMap<>(availableStacks.size());
            /*
             * Item Reactants mapped to the actual Stacks they will consume.
             */
            Map<IItemReactant, ItemStack> reactantsAndStacks = new HashMap<>(reaction.getItemReactants().size());

            for (ItemStack stack : availableStacks) { // Fill the map
                if (!stack.isEmpty()) copiesAndStacks.put(stack.copy(), stack); // Check if the Item Stack has since been emptied by another Reaction
            };

            while (true) { // Go on dissolving Items until we run out

                for (Molecule reactant : reaction.getReactants()) { // Check we have enough non-Item reactants
                    if (getConcentrationOf(reactant) < (float)reaction.getReactantMolarRatio(reactant) * reaction.getMolesPerItem() / (float)volume) continue tryEachReaction; // If there is not enough Reactant to use up the Item Stacks, give up now
                };
                
                for (IItemReactant itemReactant : reaction.getItemReactants()) {
                    boolean validItemFound = false; // Start by assuming we haven't yet come across the right Stack
                    for (ItemStack stackCopy : copiesAndStacks.keySet()) {
                        if (itemReactant.isItemValid(stackCopy)) {
                            validItemFound = true; // We have now found the right Stack
                            if (!itemReactant.isCatalyst()) { // If this Item gets used up
                                itemReactant.consume(stackCopy); // Consume the Stack copy so we know for future simulations that it can't be used
                                reactantsAndStacks.put(itemReactant, copiesAndStacks.get(stackCopy)); // Store the actual Item Stack to be consumed later
                            };
                        };
                    };
                    if (!validItemFound) continue tryEachReaction; // If the simulation was a failure, move onto the next Reaction.
                };

                // If we've gotten to this point, all Items can be successfully consumed

                for (IItemReactant itemReactant : reaction.getItemReactants()) {
                    if (!itemReactant.isCatalyst()) itemReactant.consume(reactantsAndStacks.get(itemReactant)); // Consume each actual Item Stack
                };

                equilibrium = false;
                shouldRefreshReactions |= doReaction(reaction, reaction.getMolesPerItem() / (float)volume); // Add all Molecular products and remove Molecular reactants
            }
        };

        updateName();
        if (shouldRefreshReactions) refreshPossibleReactions();
    };

    /**
     * Corrects the volume of this Mixture, to prevent things like Mixtures containing
     * 1M water and nothing else (which is physically impossible). This is mutative.
     * @param initialVolume The initial volume (in mB)
     * @return The new volume (in mB) of this Mixture
     */
    public int recalculateVolume(int initialVolume) {
        if (contents.isEmpty()) return 0;
        double initialVolumeInBuckets = (double)initialVolume / 1000d;
        double newVolumeInBuckets = 0d;
        Map<Molecule, Double> molesOfMolecules = new HashMap<>();
        for (Entry<Molecule, Float> entry : contents.entrySet()) {
            Molecule molecule = entry.getKey();
            double molesOfMolecule = entry.getValue() * initialVolumeInBuckets;
            molesOfMolecules.put(molecule, molesOfMolecule);
            newVolumeInBuckets += molesOfMolecule / molecule.getPureConcentration();
        };
        for (Entry<Molecule, Double> entry : molesOfMolecules.entrySet()) {
            contents.replace(entry.getKey(), (float)(entry.getValue() / newVolumeInBuckets));
        };
        return (int)(newVolumeInBuckets * 1000);
    };

    /**
     * Increase the number of moles of Reaction which have occured, add all products, and remove all reactants.
     * @param reaction
     * @param molesPerBucket Moles (per Bucket) of Reaction
     * @return Whether the possible Reactions for this Mixture should be updated
     */
    protected boolean doReaction(Reaction reaction, float molesPerBucket) {

        boolean shouldRefreshPossibleReactions = false;

        for (Molecule reactant : reaction.getReactants()) {
            changeConcentrationOf(reactant, - (molesPerBucket * reaction.getReactantMolarRatio(reactant)), false); // Use up the right amount of all the reagents
        };

        addEachProduct: for (Molecule product : reaction.getProducts()) {
            if (product.isNovel() && getConcentrationOf(product) == 0f) { // If we have a novel Molecule that we don't think currently exists in the Mixture...
                if (internalAddMolecule(product, molesPerBucket * reaction.getProductMolarRatio(product), false)) { // ...add it with this method, as this automatically checks for pre-existing novel Molecules, and if it was actually a brand new Molecule...
                    shouldRefreshPossibleReactions = true; // ...flag this
                }; 
                continue addEachProduct;
            };

            if (getConcentrationOf(product) == 0f) { // If we are adding a new product, the possible Reactions will change
                shouldRefreshPossibleReactions = true;
            };
            changeConcentrationOf(product, molesPerBucket * reaction.getProductMolarRatio(product), false); // Increase the concentration of the product
        };

        heat(-reaction.getEnthalpyChange() * 1000 * molesPerBucket);
        incrementReactionResults(reaction, molesPerBucket);

        return shouldRefreshPossibleReactions;
    };

    /**
     * Increase the number of moles of this Reaction which have occured in this Mixture.
     * @param reaction
     * @param molesPerBucket Moles (per Bucket) of this Reaction
     */
    protected void incrementReactionResults(Reaction reaction, float molesPerBucket) {
        if (!reaction.hasResult()) return;
        ReactionResult result = reaction.getResult();
        reactionresults.merge(result, molesPerBucket, (f1, f2) -> f1 + f2);
    };

    /**
     * {@link Mixture#reactForTick React} this Mixture until it reaches {@link Mixture#equilibrium equilibrium}. This is mutative.
     * @return A {@link com.petrolpark.destroy.recipe.ReactionInBasinRecipe.ReactionInBasinResult ReactionInBasinResult} containing
     * the number of ticks it took to reach equilibrium, the {@link ReactionResult Reaction Results} and the new volume of Mixture.
     * @param volume (in mB) of this Reaction
     * @param availableStacks Item Stacks available for reacting. This List and its contents will be modified.
     */
    public ReactionInBasinResult reactInBasin(int volume, List<ItemStack> availableStacks) {
        float volumeInBuckets = (float)volume / 1000f;
        int ticks = 0;

        dissolveItems(availableStacks, volumeInBuckets); // Dissolve all Items
        ReactionContext context = new ReactionContext(availableStacks); // React the Mixture
        while (!equilibrium && ticks < 600) {
            reactForTick(context);
            ticks++;
        };

        if (ticks == 0) return new ReactionInBasinResult(0, List.of(), volume); // If no reactions occured (because we were already at equilibrium), cancel early

        List<ReactionResult> results = new ArrayList<>();
        for (ReactionResult result : reactionresults.keySet()) {
            Float molesPerBucketOfReaction = reactionresults.get(result);
            int numberOfResult = (int) (volumeInBuckets * molesPerBucketOfReaction / result.getRequiredMoles());

            // Decrease the amount of Reaction that has happened
            reactionresults.replace(result, molesPerBucketOfReaction - numberOfResult * result.getRequiredMoles() / volumeInBuckets);

            // Add the Reaction Result the required number of times
            for (int i = 0; i < numberOfResult; i++) {
                results.add(result);
            };
        };

        int amount = recalculateVolume(volume);

        return new ReactionInBasinResult(ticks, results, amount);
    };

    /**
     * Get the heat capacity (in joules per bucket-kelvin) of this Mixture.
     */
    public float getVolumetricHeatCapacity() {
        float totalHeatCapacity = 0f;
        for (Entry<Molecule, Float> entry : contents.entrySet()) {
            totalHeatCapacity += entry.getKey().getMolarHeatCapacity() * entry.getValue();
        };
        return totalHeatCapacity;
    };

    /**
     * Adds a {@link Molecule} to this Mixture.
     * If a novel Molecule is being added, it is checked against pre-existing novel Molecules
     * and if a matching one already exists, the concentration of it is increased.
     * @param molecule The Molecule to add
     * @param concentration The starting concentration for the Molecule
     * @param shouldRefreshReactions Whether to {@link Mixture#refreshPossibleReactions refresh possible Reactions} -
     * this should only be set to false when multiple Molecules are being added/removed at once (such as when {@link Mixture#reactForTick reacting})
     * and it makes sense to only refresh the Reactions once
     * @return {@code true} if a brand new Molecule that was not already in this Mixture was added; {@code false} otherwise
     * @see Mixture#addMolecule The wrapper for this method
     * @see Mixture#changeConcentrationOf Modifying the concentration of pre-existing Molecule
     */
    private boolean internalAddMolecule(Molecule molecule, float concentration, Boolean shouldRefreshReactions) {

        boolean newMoleculeAdded = true; // Start by assuming we're adding a brand new Molecule to this solution

        if (getConcentrationOf(molecule) != 0f) { // Just in case this Molecule is already in the Mixture, increase its concentration
            changeConcentrationOf(molecule, concentration, shouldRefreshReactions);
            return false;
        };

        super.addMolecule(molecule, concentration); //TODO not do this if it turns out to already be in solution

        List<Group> functionalGroups = molecule.getFunctionalGroups();
        if (functionalGroups.size() != 0) {
            for (Group group : functionalGroups) {
                String groupID = group.getID();
                if (!groupIDsAndMolecules.containsKey(groupID)) {
                    groupIDsAndMolecules.put(groupID, new ArrayList<>());
                };
                groupIDsAndMolecules.get(groupID).add(new GenericReactant<>(molecule, group));
            };
        };

        if (molecule.isNovel()) { // If this is a novel Molecule, it might already match to one of our existing novel Molecules
            boolean found = false; // Start by assuming it's not already in the Mixture
            for (Molecule novelMolecule : novelMolecules) { // Check every novel Molecule
                if (novelMolecule.getFullID().equals(molecule.getFullID())) {
                    found = true;
                    newMoleculeAdded = false; // We haven't actually added a brand new Molecule so flag this
                    changeConcentrationOf(molecule, concentration, true);
                    equilibrium = false;
                };
            };
            if (!found) novelMolecules.add(molecule); // If it was actually a brand new Molecule, add it to the novel list
        };

        if (shouldRefreshReactions && newMoleculeAdded) {
            refreshPossibleReactions();
        };

        equilibrium = false;

        return newMoleculeAdded; // Return whether or not we actually added a brand new Molecule
    };

    /**
     * Removes the given {@link Molecule} from this Mixture, if that Molecule is already in it.
     * This does not refresh possible {@link Reaction Reactions}.
     * @param molecule
     * @return This Mixture
     */
    private Mixture removeMolecule(Molecule molecule) {

        List<Group> functionalGroups = molecule.getFunctionalGroups();
        if (functionalGroups.size() != 0) {
            for (Group group : functionalGroups) {
                groupIDsAndMolecules.get(group.getID()).removeIf((reactant) -> {
                    return reactant.getMolecule() == molecule;
                });
            };
        };

        contents.remove(molecule);
        equilibrium = false; // As we have removed a Molecule the position equilibrium is likely to change

        return this;
    };

    /**
     * Alters the concentration of a {@link Molecule} in a Mixture.
     * This does not update the {@link ReadOnlyMixture#getName name} or equilibrium status of the Mixture.
     * @param molecule If not present in the Mixture, will be added to the Mixture
     * @param change The <em>change</em> in concentration, not the new value (can be positive or negative)
     * @param shouldRefreshReactions Whether to alter the possible {@link Reaction Reactions} in the case that a new Molecule is added to the Mixture (should almost always be {@code true})
     */
    private Mixture changeConcentrationOf(Molecule molecule, float change, boolean shouldRefreshReactions) {
        Float currentConcentration = getConcentrationOf(molecule);

        if (currentConcentration == 0f) {
            if (change > 0f) {
                internalAddMolecule(molecule, change, shouldRefreshReactions);
            } else if (change < 0f) {
                throw new IllegalArgumentException("Attempted to decrease concentration of Molecule '" + molecule.getFullID()+"', which was not in a Mixture. The Mixture contains " + getContentsString());
            };
        };

        float newConcentration = currentConcentration + change;
        if (newConcentration <= 0f) {
            removeMolecule(molecule);
        } else {
            contents.replace(molecule, newConcentration);
        };
        
        return this;
    };

    /**
     * Get the rate - in moles of Reaction per Bucket <em>per tick</em> (not per second) - at which this {@link Reaction} will proceed in this Mixture.
     * @param reaction
     */
    private float calculateReactionRate(Reaction reaction) {
        float rate = reaction.getRateConstant(temperature) / (float) TICKS_PER_SECOND;
        for (Molecule molecule : reaction.getOrders().keySet()) {
            rate *= (float)Math.pow(getConcentrationOf(molecule), reaction.getOrders().get(molecule));
        };
        return rate;
    };

    /**
     * Determine all {@link Reaction Reactions} - including {@link GenericReactions Generic Reactions} that are possible with the {@link Molecule Molecules} in this Mixture,
     * and update the {@link Mixture#possibleReactions stored possible Reactions} accordingly.
     * This should be called whenever new Molecules have been {@link Mixture#addMolecule added} to the Mixture, or a Molecule has been removed entirely.
     */
    private void refreshPossibleReactions() {
        possibleReactions = new ArrayList<>();
        Set<Reaction> newPossibleReactions = new HashSet<>();

        // Generate specific Generic Reactions
        for (String id : groupIDsAndMolecules.keySet()) {
            for (GenericReaction genericreaction : Group.getReactionsOfGroupByID(id)) {
                if (genericreaction.involvesSingleGroup()) { // Generic Reactions involving only one functional Group
                    newPossibleReactions.addAll(specifySingleGroupGenericReactions(genericreaction, groupIDsAndMolecules.get(id)));
                } else { // Generic Reactions involving two functional Groups
                    //TODO
                    //TODO check for polymerisation
                };
            };
        };

        //All Reactions
        for (Molecule possibleReactant : contents.keySet()) {
            newPossibleReactions.addAll(possibleReactant.getReactantReactions());
        };
        for (Reaction reaction : newPossibleReactions) {
            //possibleReactions.add(reaction);

            /* 
             * This checks if all necessary Reactants were present before proceeding, however this leads to some infinite loops
             * where one half of a reversible Reaction would happen one tick, then the other one the next, etc.
             */ 
            Boolean reactionHasAllReactants = true;
            for (Molecule necessaryReactantOrCatalyst : reaction.getOrders().keySet()) {
                if (getConcentrationOf(necessaryReactantOrCatalyst) == 0) {
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
     * Given a {@link SingleGroupGenericReaction Generic Reaction} involving only one {@link Group functional Group},
     * generates the specified {@link Reaction Reactions} that apply to this Mixture.
     * 
     * <p>For example, if the Generic Reaction supplied is the {@link com.petrolpark.destroy.chemistry.index.genericreaction.AlkeneHydration hydration of an alkene},
     * and <b>reactants</b> includes {@code destroy:ethene}, the returned collection will include a Reaction with {@code destroy:ethene} and {@code destroy:water} as reactants,
     * {@code destroy:ethanol} as a product, and all the appropriate rate constants and catalysts as defined in the {@link com.petrolpark.destroy.chemistry.index.genericreaction.AlkeneHydration#generateReaction generator}.</p>
     * 
     * @param <G> <b>G</b> The Group to which this Generic Reaction applies
     * @param genericreaction
     * @param reactants All {@link GenericReactant Reactants} that have the Group.
     * @return A Collection of all specified Reactions.
     */
    @SuppressWarnings("unchecked")
    private <G extends Group> List<Reaction> specifySingleGroupGenericReactions(GenericReaction genericreaction, List<GenericReactant<?>> reactants) {
        try {
            SingleGroupGenericReaction<G> singleGroupGenericReaction = (SingleGroupGenericReaction<G>) genericreaction; // Unchecked conversion
            List<Reaction> reactions = new ArrayList<>();
            for (GenericReactant<?> reactant : reactants) {
                reactions.add(singleGroupGenericReaction.generateReaction((GenericReactant<G>)reactant)); // Unchecked conversion
            };
            return reactions;
        } catch(Error e) {
            throw new IllegalStateException("Wasn't able to generate Single-Group Reaction: " + e);
        }
    };

    public static boolean areVeryClose(Float f1, Float f2) {
        return Math.abs(f1 - f2) <= 0.0001f;
    };

    /**
     * The context for the {@link Mixture#reactForTick reaction} of a {@link Mixture}.
     * <strong>Do not modify its fields, or anything contained within them.</em>
     */
    public static class ReactionContext {

        private final ImmutableList<ItemStack> availableItemStacks;

        public ReactionContext(List<ItemStack> availableItemStacks) {
            this.availableItemStacks = ImmutableList.copyOf(availableItemStacks);
        };

        public List<ItemStack> getItemStacks() {
            return availableItemStacks;
        };
    };
};
