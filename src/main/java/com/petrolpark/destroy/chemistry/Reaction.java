package com.petrolpark.destroy.chemistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Map.Entry;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.petrolpark.destroy.Destroy;
import com.petrolpark.destroy.chemistry.error.ChemistryException;
import com.petrolpark.destroy.chemistry.genericreaction.GenericReaction;
import com.petrolpark.destroy.chemistry.index.DestroyMolecules;

import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * A Reaction takes place between specific {@link Molecule Molecules}, and produces specific Molecules.
 * This is in contrast with {@link com.petrolpark.destroy.chemistry.genericreaction.GenericReaction Generic
 * Reactions}, which essentially function as Reaction generators, and will generate Reactions based on the
 * available Molecules in a {@link Mixture}.
 */
public class Reaction {

    public static final Float GAS_CONSTANT = 8.3145f;

    /**
     * The set of all Reactions known to Destroy, indexed by their {@link Reaction#id IDs}.
     */
    public static final Map<String, Reaction> REACTIONS = new HashMap<>();

    public static ReactionBuilder generatedReactionBuilder() {
        return new ReactionBuilder(new Reaction("novel"), true);
    };

    private Map<Molecule, Integer> reactants, products, orders;

    /**
     * All {@link IItemReactant Item Reactants} (and catalysts) this Reaction.
     */
    private List<IItemReactant> itemReactants;
    /**
     * The number of moles of Reaction which will occur if all {@link Reaction#itemReactants Item requirements} are met.
     */
    private float molesPerItem;

    /**
     * Whether this Reaction needs UV light to proceed.
     */
    private boolean isCatalysedByUV;

    /**
     * The {@link ReactionResult Reaction Result} of this Reaction, if there is one.
     */
    private ReactionResult result;

    // THERMODYNAMICS

    /**
     * {@code A} in {@code k = Aexp(-E/RT)}.
     */
    private float preexponentialFactor;
    /**
     * {@code E} in {@code k = Aexp(-E/RT)}, in kJ/mol/s.
     */
    private float activationEnergy;
    /**
     * The change in enthalpy (in kJ/mol) for this Reaction.
     */
    private float enthalpyChange;

    /**
     * The namespace of the mod by which this Reaction was declared, or {@code novel} if this was generated
     * by a {@link com.petrolpark.destroy.chemistry.genericreaction.GenericReaction Reaction generator}.
     */
    private final String namespace;
    /**
     * The ID of this reaction, not including its {@link Reaction#namespace name space}, and {@code null} if this
     * Reaction was generated by a {@link com.petrolpark.destroy.chemistry.genericreaction.GenericReaction Reaction
     * generator}.
     * @see Reaction#getId The getter for this field
     */
    private String id;

    // JEI DISPLAY INFORMATION

    /**
     * Whether this Reaction should be shown in JEI. Examples of Reactions which shouldn't be shown are {@link
     * com.petrolpark.destroy.chemistry.genericreaction.GenericReaction generated Reactions}, and reverse Reactions
     * whose corresponding Reactions are already shown in JEI.
     */
    private boolean includeInJei;
    /**
     * Whether this Reaction should use an equilibrium arrow when displayed in JEI, rather than the normal one. This
     * is just for display, and has no effect on the behaviour of the Reaction in a {@link Mixture}.
     */
    private boolean displayAsReversible;
    /**
     * If this is the 'forward' half of a reversible Reaction, this points to the reverse Reaction. This is so JEI
     * knows the products of the forward Reaction are the reactants of the reverse, and vice versa.
     */
    private Reaction reverseReaction;

    /**
     * Get the Reaction with the given {@link Reaction#getFullId ID}.
     * @param reactionId In the format {@code <namespace>:<id>}
     * @return {@code null} if no Reaction exists with that ID
     */
    public static Reaction get(String reactionId) {
        return REACTIONS.get(reactionId);
    };

    private Reaction(String nameSpace) {
        this.namespace = nameSpace;
    };

    /**
     * Whether this Molecule gets consumed in this Reaction (does not include catalysts).
     */
    public Boolean containsReactant(Molecule molecule) {
        return this.reactants.keySet().contains(molecule);
    };

    /**
     * Whether this Molecule is created in this Reaction.
     */
    public Boolean containsProduct(Molecule molecule) {
        return this.products.keySet().contains(molecule);
    };

    /**
     * All Molecules which are consumed in this Reaction (but not their molar ratios).
     */
    public Set<Molecule> getReactants() {
        return this.reactants.keySet();
    };

    /**
     * Whether this Reaction needs any Item Stack as a {@link IItemReactant reactant}. Even if this is
     * {@code true}, the Reaction may still have {@link IItemReactant#isCatalyst Item Stack catalysts}.
     */
    public boolean consumesItem() {
        for (IItemReactant itemReactant : itemReactants) {
            if (!itemReactant.isCatalyst()) return true;
        };
        return false;
    };

    /**
     * Get the {@link IItemReactant required Items} for this Reaction.
     */
    public List<IItemReactant> getItemReactants() {
        return itemReactants;
    };

    /**
     * Get the moles of this Reaction that will occur once all {@link Reaction#itemReactants Item requirements} are fulfilled.
     */
    public float getMolesPerItem() {
        return molesPerItem;
    };

    /**
     * Whether this Reaction needs UV light to proceed.
     */
    public boolean needsUV() {
        return isCatalysedByUV;
    };

    /**
     * All Molecules which are created in this Reaction (but not their molar ratios).
     */
    public Set<Molecule> getProducts() {
        return this.products.keySet();
    };

    /**
     * Get the {@link Reaction#activationEnergy activation energy} for this Reaction, in kJ.
     * @see Reaction#getRateConstant Arrhenius equation
     */
    public float getActivationEnergy() {
        return activationEnergy;
    };

    /**
     * Get the {@link Reaction#preexponentialFactor preexponential} for this Reaction, in mol/B/s.
     * @see Reaction#getRateConstant Arrhenius equation
     */
    public float getPreexponentialFactor() {
        return preexponentialFactor;
    };

    /**
     * The rate constant of this Reaction at the given temperature.
     * @param temperature (in kelvins).
     */
    public float getRateConstant(float temperature) {
        return preexponentialFactor * (float)Math.exp(-((activationEnergy * 1000) / (GAS_CONSTANT * temperature)));
    };

    /**
     * The {@link Reaction#enthalpyChange enthalpy change} for this Reaction, in kJ/mol.
     */
    public float getEnthalpyChange() {
        return enthalpyChange;
    };

    /**
     * Whether this Reaction has a {@link ReactionResult Result}. 
     */
    public boolean hasResult() {
        return result != null;
    };

    /**
     * The {@link ReactionResult Result} of this Reaction, which occurs once a set
     * number of moles of Reaction have occured.
     * @return {@code null} if this Reaction has no result.
     */
    public ReactionResult getResult() {
        return result;
    };

    /**
     * The unique identifier for this Reaction (not including its namespace), which
     * also acts as its translation key. {@code <namespace>.reaction.<id>} should hold
     * the name of this Reaction, and {@code <namespace>.reaction.<id>.description}
     * should hold the description of this Reaction.
     * @see Reaction#getFullId Get the full ID
     */
    public String getId() {
        return id;
    };

    /**
     * Get the fully unique ID for this Reaction, in the format {@code <namespace>:
     * <id>}, for example {@code destroy:chloroform_fluorination}.
     */
    public String getFullId() {
        return namespace + ":" + id;
    };

    /**
     * Whether this Reaction should be displayed in the list of Reactions in JEI.
     */
    public boolean includeInJei() {
        return includeInJei;
    };

    /**
     * Whether this Reaction should be displayed in JEI with an equilibrium arrow rather than a normal one.
     */
    public boolean displayAsReversible() {
        return displayAsReversible;
    };

    /**
     * If this is the 'forward' half of a reversible Reaction, this contains the reverse Reaction. This is so JEI
     * knows the products of the forward Reaction are the reactants of the reverse, and vice versa. If this is not
     * part of a reversible Reaction, this is empty. This is just for display; if a Reaction has a reverse and is needed
     * for logic (e.g. Reacting in a Mixture) it should not be accessed in this way.
     */
    public Optional<Reaction> getReverseReactionForDisplay() {
        return Optional.ofNullable(reverseReaction);
    };

    /**
     * The name space of the mod by which this Reaction was defined.
     * @return {@code "novel"} if this was generated automatically by a {@link com.petrolpark.destroy.chemistry.genericreaction.GenericReaction Reaction generator}.
     */
    public String getNamespace() {
        return namespace;
    };

    /**
     * Get the stoichometric ratio of this {@link Molecule reactant} or catalyst in this Reaction.
     * @param reactant
     * @return {@code 0} if this Molecule is not a reactant
     */
    public Integer getReactantMolarRatio(Molecule reactant) {
        if (!reactants.keySet().contains(reactant)) {
            return 0;
        } else {
            return reactants.get(reactant);
        }
    };

    /**
     * Get the stoichometric ratio of this {@link Molecule product} in this Reaction.
     * @param product
     * @return {@code 0} if this Molecule is not a product
     */
    public Integer getProductMolarRatio(Molecule product) {
        if (!products.keySet().contains(product)) {
            return 0;
        } else {
            return products.get(product);
        }
    };

    /**
     * Get every {@link Molecule reactant} and catalyst in this Reaction, mapped to their
     * orders in the rate equation.
     */
    public Map<Molecule, Integer> getOrders() {
        return this.orders;
    };

    /**
     * A class for constructing {@link Reaction Reactions}.
     * <ul>
     * <li>If this is for a Reaction with named {@link Molecule Molecules}, {@link ReactionBuilder#ReactionBuilder(String) instantiate with a name space}.</li>
     * <li>If this is for a {@link com.petrolpark.destroy.chemistry.genericreaction.GenericReaction generated} Reaction, get a builder {@link Reaction#generatedReactionBuilder here}.</li>
     * </ul> 
     * A new builder must be used for each Reaction.
     */
    public static class ReactionBuilder {

        private String namespace;

        /**
         * Whether this Reaction is being generated by a {@link GenericReaction Generic Reaction generator}.
         */
        final boolean generated;
        Reaction reaction;

        private boolean hasForcedPreExponentialFactor;
        private boolean hasForcedActivationEnergy;
        private boolean hasForcedEnthalpyChange;

        private ReactionBuilder(Reaction reaction, boolean generated) {
            this.generated = generated;
            this.reaction = reaction;

            reaction.reactants = new HashMap<>();
            reaction.products = new HashMap<>();
            reaction.orders = new HashMap<>();

            reaction.itemReactants = new ArrayList<>();
            reaction.molesPerItem = 0f;

            reaction.includeInJei = !generated;
            reaction.displayAsReversible = false;

            hasForcedPreExponentialFactor = false;
            hasForcedActivationEnergy = false;
            hasForcedEnthalpyChange = false;
        };

        public ReactionBuilder(String namespace) {
            this(new Reaction(namespace), false);
            this.namespace = namespace;
        };

        private void checkNull(Molecule molecule) {
            if (molecule == null) error("Molecules cannot be null");
        };

        /**
         * Add a {@link Molecule} of which one mole will be consumed per mole of Reaction.
         * By default, the order of rate of reaction with respect to this Molecule will be one.
         * @param molecule
         * @return This Reaction Builder
         * @see ReactionBuilder#addReactant(Molecule, int) Adding a different stoichometric ratio
         * @see ReactionBuilder#addReactant(Molecule, int, int) Adding a Molecule with a different order
         */
        public ReactionBuilder addReactant(Molecule molecule) {
            return addReactant(molecule, 1);
        };

        /**
         * Add a {@link Molecule} of which {@code ratio} moles will be consumed per mole of Reaction.
         * By default, the order of rate of reaction with respect to this Molecule will be one.
         * @param molecule
         * @param ratio The stoichometric ratio of this reactant in this Reaction
         * @return This Reaction Builder
         * @see ReactionBuilder#addReactant(Molecule, int, int) Adding a Molecule with a different order
         */
        public ReactionBuilder addReactant(Molecule molecule, int ratio) {
            return addReactant(molecule, ratio, ratio);
        };

        /**
         * Add a {@link Molecule} which will be consumed in this Reaction.
         * @param molecule
         * @param ratio The stoichometric ratio of this reactant in this Reaction
         * @param order The order of the rate of the Reaction with respect to this Molecule
         * @return This Reaction Builder
         */
        public ReactionBuilder addReactant(Molecule molecule, int ratio, int order) {
            checkNull(molecule);
            reaction.reactants.put(molecule, ratio);
            reaction.orders.put(molecule, order);
            return this;
        };

        /**
         * Sets the order of rate of Reaction of the given {@link Molecule}.
         * @param molecule If this is not a reactant of this Reaction, an error will be thrown
         * @param order
         * @return This Reaction Builder
         * @see ReactionBuilder#addCatalyst(Molecule, int) Adding order with respect to a Molecule that is not a reactant (i.e. a catalyst)
         */
        public ReactionBuilder setOrder(Molecule molecule, int order) {
            if (!reaction.reactants.keySet().contains(molecule)) error("Cannot modify order of a Molecule ("+ molecule.getFullID() +") that is not a reactant.");
            addCatalyst(molecule, order);
            return this;
        };

        /**
         * Adds an {@link IItemReactant Item Reactant} (or catalyst) to this {@link Reaction}.
         * @param itemReactant The Item Reactant
         * @param moles The {@link Reaction#getMolesPerItem moles of Reaction} which will occur if all necessary Item Reactants are present. If this
         * Reaction has multiple Item Reactants, this must be the same each time.
         * @return This Reaction Builder
         * @see ReactionBuilder#addSimpleItemReactant Adding a single Item as a Reactant
         * @see ReactionBuilder#addSimpleItemTagReactant Adding an Item Tag as a Reactant
         */
        public ReactionBuilder addItemReactant(IItemReactant itemReactant, float moles) {
            if (reaction.molesPerItem != 0f && reaction.molesPerItem != moles) error("The number of moles of Reaction which occur when all Item Requirements are met is constant for a Reaction, not individual per Item Reactant. The same number must be supplied each time an Item Reactant is added.");
            reaction.molesPerItem = moles;
            reaction.itemReactants.add(itemReactant);
            return this;
        };

        /**
         * Adds an Item as a {@link IItemReactant reactant} for this {@link Reaction}. An Item Stack of size {@code 1},
         * with this Item will be consumed in the Reaction.
         * @param item
         * @param moles The {@link Reaction#getMolesPerItem moles of Reaction} which will occur if all necessary Item Reactants are present. If this
         * Reaction has multiple Item Reactants, this must be the same each time.
         * @return This Reaction Builder
         */
        public ReactionBuilder addSimpleItemReactant(Supplier<Item> item, float moles) {
            return addItemReactant(new IItemReactant.SimpleItemReactant(item), moles);
        };

        /**
         * Adds an Item Tag as a {@link IItemReactant reactant} for this {@link Reaction}. An Item Stack of size {@code 1},
         * containing an Item with this Tag will be consumed in the Reaction.
         * @param tag
         * @param moles The {@link Reaction#getMolesPerItem moles of Reaction} which will occur if all necessary Item Reactants are present. If this
         * Reaction has multiple Item Reactants, this must be the same each time.
         * @return This Reaction Builder
         */
        public ReactionBuilder addSimpleItemTagReactant(TagKey<Item> tag, float moles) {
            return addItemReactant(new IItemReactant.SimpleItemTagReactant(tag), moles);
        };

        /**
         * Adds an Item as a {@link IItemReactant catalyst} for this {@link Reaction}. An Item Stack containing the Item
         * must be present for the Reaction to occur.
         * @param item
         * @param moles The {@link Reaction#getMolesPerItem moles of Reaction} which will occur if all necessary Item Reactants are present. If this
         * Reaction has multiple Item Reactants, this must be the same each time.
         * @return This Reaction Builder
         */
        public ReactionBuilder addSimpleItemCatalyst(Supplier<Item> item, float moles) {
            return addItemReactant(new IItemReactant.SimpleItemCatalyst(item), moles);
        };

        /**
         * Adds an Item Ta as a {@link IItemReactant catalyst} for this {@link Reaction}. An Item Stack containing Items with the tag
         * must be present for the Reaction to occur.
         * @param tag
         * @param moles The {@link Reaction#getMolesPerItem moles of Reaction} which will occur if all necessary Item Reactants are present. If this
         * Reaction has multiple Item Reactants, this must be the same each time.
         * @return This Reaction Builder
         */
        public ReactionBuilder addSimpleItemTagCatalyst(TagKey<Item> tag, float moles) {
            return addItemReactant(new IItemReactant.SimpleItemTagCatalyst(tag), moles);
        };

        /**
         * Set this Reaction as requiring ultraviolet light, from the sun or a Blacklight.
         * If {@code true}, the rate of this Reaction will be multiplied by {@code 0} to {@code 1} depending on the amount of incident UV.
         * @return This Reaction Builder
         */
        public ReactionBuilder requireUV() {
            reaction.isCatalysedByUV = true;
            return this;
        };

        /**
         * Add a {@link Molecule} of which one mole will be produced per mole of Reaction.
         * @param molecule
         * @return This Reaction Builder
         */
        public ReactionBuilder addProduct(Molecule molecule) {
            return addProduct(molecule, 1);
        };

        /**
         * Add a {@link Molecule} of which {@code ratio} moles will be produced per mole of Reaction.
         * @param molecule
         * @param ratio The stoichometric ratio of this product in this Reaction
         * @return This Reaction Builder
         * @see ReactionBuilder#addProduct(Molecule, int) Adding a different stoichometric ratio
         */
        public ReactionBuilder addProduct(Molecule molecule, int ratio) {
            checkNull(molecule);
            reaction.products.put(molecule, ratio);
            return this;
        };

        /**
         * Add a {@link Molecule} which does not get consumed in this Reaction, but which affects the rate.
         * @param molecule
         * @param order If this is is 0, the rate will not be affected but the Molecule will need to be present for the Reaction to proceed
         * @return This Reaction Builder
         */
        public ReactionBuilder addCatalyst(Molecule molecule, int order) {
            checkNull(molecule);
            reaction.orders.put(molecule, order);
            return this;
        };

        /**
         * Don't include this Reaction in the list of Reactions shown in JEI.
         * @return This Reaction Builder
         */
        public ReactionBuilder dontIncludeInJei() {
            reaction.includeInJei = false;
            return this;
        };

        /**
         * Show a double-headed arrow for this Reaction in JEI. To actually make this a reversible reaction, use {@link ReactionBuilder#reverseReaction this}.
         * @return This Reaction Builder
         */
        public ReactionBuilder displayAsReversible() {
            reaction.displayAsReversible = true;
            return this;
        };

        /**
         * Set the ID for the Reaction. The title and description of the Reaction will be looked for at {@code "<namespace>.reaction.<id>"}.
         * @param id A unique string
         * @return This Reaction Builder
         */
        public ReactionBuilder id(String id) {
            reaction.id = id;
            return this;
        };

        /**
         * Set the pre-exponential factor in the Arrhenius equation for this Reaction.
         * @param preexponentialFactor
         * @return This Reaction Builder
         */
        public ReactionBuilder preexponentialFactor(float preexponentialFactor) {
            reaction.preexponentialFactor = preexponentialFactor;
            hasForcedPreExponentialFactor = true;
            return this;
        };

        /**
         * Set the activation energy (in kJ) for this Reaction.
         * If no activation energy is given, defaults to 50kJ.
         * @param activationEnergy
         * @return This Reaction Builder
         */
        public ReactionBuilder activationEnergy(float activationEnergy) {
            reaction.activationEnergy = activationEnergy;
            hasForcedActivationEnergy = true;
            return this;
        };

        /**
         * Set the enthalpy change (in kJ/mol) for this Reaction.
         * If no enthalpy change is given, defaults to 0kJ.
         * @param enthalpyChange
         * @return This Reaction Builder
         */
        public ReactionBuilder enthalpyChange(float enthalpyChange) {
            reaction.enthalpyChange = enthalpyChange;
            hasForcedEnthalpyChange = true;
            return this;
        };

        /**
         * Set the {@link ReactionResult Reaction Result} for this Reaction.
         * Use a {@link com.petrolpark.destroy.chemistry.reactionresult.CombinedReactionResult CombinedReactionResult} to set multiple
         * @return This Reaction Builder
         */
        public ReactionBuilder withResult(float moles, BiFunction<Float, Reaction, ReactionResult> reactionresultFactory)  {
            if (reaction.result != null) error("Reaction already has a Reaction Result. Use a CombinedReactionResult to have multiple.");
            reaction.result = reactionresultFactory.apply(moles, reaction);
            return this;
        };

        /**
         * Registers an acid. This automatially registers two {@link Reaction Reactions} (one for the association,
         * one for the dissociation). The pKa is assumed to be temperature-independent - if this is not wanted, manually register
         * the two Reactions.
         * @param acid
         * @param conjugateBase This should have a charge one less than the acid and should ideally conserve Atoms
         * @param pKa
         * @return The dissociation Reaction
         */
        public Reaction acid(Molecule acid, Molecule conjugateBase, float pKa) {

            if (conjugateBase.getCharge() + 1 != acid.getCharge()) error("Acids must not violate the conservation of charge.");

            // Dissociation
            Reaction dissociationReaction = this
                .id(acid.getFullID().split(":")[1] + ".dissociation")
                .addReactant(acid)
                .addCatalyst(DestroyMolecules.WATER, 0)
                .addProduct(DestroyMolecules.PROTON)
                .addProduct(conjugateBase)
                .activationEnergy(GAS_CONSTANT * 0.298f) // Makes the pKa accurate at room temperature
                .preexponentialFactor((float)Math.pow(10, -pKa))
                .dontIncludeInJei()
                .build();

            // Association
            new ReactionBuilder(namespace)
                .id(acid.getFullID().split(":")[1] + ".association")
                .addReactant(conjugateBase)
                .addReactant(DestroyMolecules.PROTON)
                .addProduct(acid)
                .activationEnergy(GAS_CONSTANT * 0.298f)
                .preexponentialFactor(1f)
                .dontIncludeInJei()
                .build();

            return dissociationReaction;
        };

        /**
         * Register a reverse Reaction for this Reaction.
         * <p>This reverse Reaction will have opposite {@link ReactionBuilder#addReactant reactants} and {@link ReactionBuilder#addProduct products},
         * but all the same {@link ReactionBuilder#addCatalyst catalysts}. It will {@link ReactionBuilder#dontIncludeInJei not be shown in JEI}, but
         * the original Reaction will include the reverse symbol.</p>
         * <p>The reverse Reaction does not automatically add Item Stack reactants, products or catalysts, or UV requirements.</p>
         * @param reverseReactionModifier A consumer which gets passed the Builder of the reverse Reaction once its reactants, products and catalysts
         * have been added, and the {@link ReactionBuilder#enthalpyChange enthalpy change} and {@link ReactionBuilder#activationEnergy activation energy}
         * have been set, if applicable. This allows you to add {@link ReactionBuilder#setOrder orders with respect to the new reactants}, and {@link
         * ReactionBuilder#withResult 
         * Reaction results}.
         * @return This Reaction Builder (not the reverse Reaction Builder)
         */
        public ReactionBuilder reverseReaction(Consumer<ReactionBuilder> reverseReactionModifier) {
            if (generated) error("Generated Reactions cannot be reversible. Add another Generic Reaction instead.");
            reaction.displayAsReversible = true;
            ReactionBuilder reverseBuilder = new ReactionBuilder(namespace);
            for (Entry<Molecule, Integer> reactant : reaction.reactants.entrySet()) {
                reverseBuilder.addProduct(reactant.getKey(), reactant.getValue());
            };
            for (Entry<Molecule, Integer> product : reaction.products.entrySet()) {
                reverseBuilder.addReactant(product.getKey(), product.getValue());
            };
            for (Entry<Molecule, Integer> rateAffecter : reaction.orders.entrySet()) {
                if (reaction.reactants.containsKey(rateAffecter.getKey())) continue; // Ignore reactants, only add catalysts
                reverseBuilder.addCatalyst(rateAffecter.getKey(), rateAffecter.getValue());
            };
            reaction.reverseReaction = reverseBuilder.reaction; // Set this so JEI knows

            reverseBuilder
                .id(reaction.id + ".reverse")
                .dontIncludeInJei();

            if (hasForcedEnthalpyChange && hasForcedActivationEnergy) { // If we've set the enthalpy change and activation energy for this Reaction, the values for the reverse are set in stone
                reverseBuilder
                    .activationEnergy(reaction.activationEnergy - reaction.enthalpyChange)
                    .enthalpyChange(-reaction.enthalpyChange);
            };

            if (reaction.needsUV()) reverseBuilder.requireUV();

            reverseReactionModifier.accept(reverseBuilder); // Allow the user to manipulate the reverse Reaction

            if ( // Check thermodynamics are correct
                reaction.activationEnergy - reaction.enthalpyChange != reverseBuilder.reaction.activationEnergy
                || reaction.enthalpyChange != -reverseBuilder.reaction.enthalpyChange
            ) { 
                error("Activation energies and enthalpy changes for reversible Reactions must obey Hess' Law");
            };

            reverseBuilder.build();
            return this;
        };

        public Reaction build() {

            if (reaction.id == null && !generated) {
                error("Reaction is missing an ID.");
            };

            if (!hasForcedActivationEnergy) {
                reaction.activationEnergy = 2.5f;
                //Destroy.LOGGER.warn("Activation energy of reaction '"+reactionString()+"' was missing or invalid, so estimated as 50kJ.");
            };

            if (!hasForcedPreExponentialFactor || reaction.preexponentialFactor <= 0f) {
                reaction.preexponentialFactor = 1e4f;
            };

            if (!hasForcedEnthalpyChange) reaction.enthalpyChange = 0f;

            if (reaction.consumesItem() && reaction.molesPerItem == 0f) {
                Destroy.LOGGER.warn("Reaction '"+reactionString()+"' does not do anything when its required Items are consumed.");
            };

            if (!generated) {
                for (Molecule reactant : reaction.reactants.keySet()) {
                    reactant.addReactantReaction(reaction);
                };
                for (Molecule product : reaction.products.keySet()) {
                    product.addProductReaction(reaction);
                };
                REACTIONS.put(reaction.getFullId(), reaction);
            };
            
            return reaction;
        };

        public static class ReactionConstructionException extends ChemistryException {

            public ReactionConstructionException(String message) {
                super(message);
            };
            
        };

        private void error(String message) {
            String id = reaction.id == null ? reactionString() : reaction.namespace + ":" + reaction.id;
            throw new ReactionConstructionException("Problem generating reaction (" + id + "): " + message);
        };

        private String reactionString() {
            StringBuilder reactionString = new StringBuilder();
            for (Molecule reactant : reaction.reactants.keySet()) {
                reactionString.append(reactant.getSerlializedMolecularFormula(false));
                reactionString.append(" + ");
            };
            reactionString = new StringBuilder(reactionString.substring(0, reactionString.length() - 3) + " => ");
            for (Molecule product : reaction.products.keySet()) {
                reactionString.append(product.getSerlializedMolecularFormula(false));
                reactionString.append(" + ");
            };
            reactionString = new StringBuilder(reactionString.substring(0, reactionString.length() - 3));
            return reactionString.toString();
        };
    };
}
