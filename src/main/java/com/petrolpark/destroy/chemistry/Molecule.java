package com.petrolpark.destroy.chemistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableSet;
import com.petrolpark.destroy.Destroy;
import com.petrolpark.destroy.chemistry.Formula.Topology.SideChainInformation;
import com.petrolpark.destroy.chemistry.index.DestroyMolecules;
import com.petrolpark.destroy.chemistry.serializer.Branch;
import com.petrolpark.destroy.client.gui.MoleculeRenderer;
import com.petrolpark.destroy.util.DestroyLang;
import com.simibubi.create.foundation.utility.Pair;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * A Molecule is any species - that could be an actual chemical molecule or an inorganic ion.
 * There are two types of Molecule:<ol>
 * <li>Molecules which have an ID (in the form {@code <namespace>:<name>}), and a defined {@link Formula structure} and properties - like {@link DestroyMolecules here}, for example.</li>
 * <li>"Novel" Molecules, which are automatically generated by {@link com.petrolpark.destroy.chemistry.genericreaction.GenericReaction Generic Reactions}.
 * Instead of an ID, these Molecules are stored and refered to with a <a href="https://github.com/petrolpark/Destroy/wiki/FROWNS">FROWNS</a> code.</li>
 * </ol><p>An instance of this class does not refer to a singular occurence of a Molecule in a {@link Mixture}. Rather each instance is a different Molecule (for example ethene might be one instance, and methanol another).</p>
 * <p>Molecules should only ever be instantiated with a {@link MoleculeBuilder builder}.</p>
 */
public class Molecule implements INameableProduct {

    // ID

    /**
     * The set of name spaces of all mods which add Molecules.
     */
    public static final Set<String> NAMESPACES = new HashSet<>(); // Set of all Molecular namespaces
    /**
     * The set of name spaces which are reserved for novel Molecules.
     */
    public static final Set<String> FORBIDDEN_NAMESPACES = new HashSet<>(); // Set of all forbidden namespaces
    /**
     * All non-novel Molecules known to Destroy, indexed by their {@link Molecule#getFullID ID}.
     */
    public static final Map<String, Molecule> MOLECULES = new HashMap<>(); // Map of Molecules stored by their IDs

    /**
     * The name space of the mod by which this Molecule was defined.
     */
    private String nameSpace;
    /**
     * The {@link Molecule#getFullID ID} of this Molecule, not including its {@link Molecule#nameSpace name space}.
     */
    private String id;

    // PHYSICAL PROPERTIES

    /**
     * The charge of this Molecule, used to balance salts.
     */
    private int charge;
    /**
     * The RAM of this Molecule, in grams per mole.
     */
    private float mass;

    /**
     * The density of this Molecule, in grams per Bucket
     */
    private float density;
    /**
     * The {@link ReadOnlyMixture#temperature temperature} (in kelvins) at which this Molecule forms a gas.
     */
    private float boilingPoint;
    /**
     * The polarity of this Molecule, used to determine its solubility with other Molecules.
     */
    private float dipoleMoment;
    /**
     * The specific heat capacity in joules per kilogram-kelvin of this Molecule.
     */
    private float molarHeatCapacity;
    /**
     * The {@link Formula} of this Molecule.
     */
    private Formula structure;

    // REACTIONS

    /**
     * The {@link MoleculeTag tags} which apply to this Molecule.
     */
    private Set<MoleculeTag> tags;

    /**
     * The specific {@link Reaction Reactions} in which this Molecule is a {@link Reaction#getReactants reactant}.
     */
    private List<Reaction> reactantReactions;
    /**
     * The specific {@link Reaction Reactions} in which this Molecule is a {@link Reaction#getProducts product}.
     */
    private List<Reaction> productReactions;

    // DISPLAY PROPERTIES

    /**
     * The last term in the translation key of this Molecule. For non-novel Molecules this is usually derived from their {@link Molecule#getFullID ID}.
     * For example, for acetone this would be {@code acetone} (and not {@code destroy.chemical.acetone}).
     * <p>If a Molecule has a sensible IUPAC recommended/systematic name, it is recommended that this is also defined in the language file, for example:
     * <blockquote><pre>
     * "destroy.chemical.acetone": "Acetone",
     * "destroy.chemical.acetone.iupac": "Propan-2-one"
     * </pre></blockquote></p>
     */
    private String translationKey;
    /**
     * The display name of this Molecule. For non-novel Molecules this should be {@link Molecule#translationKey defined in a language file} -
     * for novel Molecules this will be their {@link Molecule#getSerlializedMolecularFormula molecular formula}.
     */
    private Component name;
    /**
     * The color this Molecule adds to a {@link Mixture}.
     */
    private int color;

    /**
     * The {@link com.petrolpark.destroy.client.gui.MoleculeRenderer Renderer} for this Molecule.
     */
    private MoleculeRenderer renderer;

    private Molecule(String nameSpace) {
        this.nameSpace = nameSpace;
        id = null;
        structure = null;

        tags = new HashSet<>();

        reactantReactions = new ArrayList<>();
        productReactions = new ArrayList<>();
    };

    /**
     * If given a:<ul>
     * <li>{@link Molecule} {@link Molecule#getFullID ID} (e.g. {@code destroy:ethanol}), gives the Molecule identified by that ID, or {@code null} if it does not exist.</li>
     * <li><a href="https://github.com/petrolpark/Destroy/wiki/FROWNS">FROWNS</a> code (e.g. {@code destroy:linear:OCO}), generates the novel Molecule with that {@link Formula}.
     * An error will be thrown if the FROWNS code is invalid.</li>
     * </ul><p>This method does not {@link Molecule#getEquivalent check for pre-existing defined Molecules} of the same structure. To generate novel Molecules from a
     * FROWNS code {@code x:y} and also check if they already exist, use a {@link MoleculeBuilder Molecule Builder} with {@code .structure(Formula.deserialize(x:y))}.</p>
     * @param id ID or full FROWNS code.
     * @return A new Molecule instance for novel Molecules; the existing Molecule object for known ones
     */
    @Nullable
    public static Molecule getMolecule(String id) {
        String[] idComponents = id.split(":");
        if (idComponents.length == 3) {
            return new MoleculeBuilder("novel")
                .structure(Formula.deserialize(id))
                .build();
        } else if (idComponents.length == 2) {
            return MOLECULES.get(id);
        };
        Destroy.LOGGER.warn("Could not find Molecule '"+id+"'.");
        return null;
    };

    /**
     * Get the String used for storing this Molecule in NBT.<ul>
     * <li>For known Molecules, this will be of the format {@code <namespace>:<id>}.</li>
     * <li>For novel Molecules, this will be their <a link href="https://github.com/petrolpark/Destroy/wiki/FROWNS"> FROWNS</a> code.</li>
     * </ul><p>Both known Molecules and novel Molecules of a certain {@link Formula structure} will always generate the same ID/FROWNS code,
     * meaning they can be used to compare.</p>
     * @return ID or FROWNS Code
     * @see Molecule#getStructuralFormula Getting the FROWNS code of a known Molecule
     */
    public String getFullID() {
        if (id == null) {
            /*
             * This method caches the FROWNS code and simply refers to it if it has already been calculated,
             * meaning we are free to refer to it as much as we want without the risk of unnecessarily undergoing
             * the processing-intensive task of generating a name.
             */
            return structure.serialize();
        } else {
            return nameSpace+":"+id;
        }
    };

    /**
     * @deprecated
     */
    @Deprecated
    public void pee() {
        System.out.println(structure.serialize());
    };

    /**
     * Checks all {@link Molecule#MOLECULES known Molecules} for those which have the same structure as this one - this allows novel Molecules
     * generated by {@link com.petrolpark.destroy.chemistry.genericreaction.GenericReaction Generic Reactions} to be matched to pre-existing Molecules,
     * which are less processing-intensive.
     * <p>For example, if this Molecule has structure {@code linear:CC(=O)C} this will return {@link DestroyMolecules#ACETONE acetone}.</p>
     * <p>When Generic Reactions are making Molecules to add to {@link Mixture Mixtures}, this step is done at {@link Reaction} generation, not when {@link Mixture#addMolecule adding}.</p>
     * @return A pre-existing Molecule object if there is a match, or this Molecule otherwise
     */
    public Molecule getEquivalent() {
        for (Molecule molecule : MOLECULES.values()) {
            if (Math.abs(getMass() - molecule.getMass()) < 0.001) { // Initially just check the masses match
                if (structure.serialize().equals(molecule.structure.serialize())) { // Ceck the structures match
                    return molecule;
                };
            };
        };
        return this;
    };

    /**
     * The charge of this Molecule, used to balance salts.
     */
    public int getCharge() {
        return charge;
    };

    /**
     * The RAM of this Molecule, in grams per mole.
     */
    public float getMass() {
        return mass;
    };

    /**
     * The density of this Molecule when pure, in grams per Bucket.
     */
    public float getDensity() {
        // TODO check if this is an ion as these do not really have densities
        return density;
    };

    /**
     * Get the 'concentration' (molar density) of this Molecule when it is the only Molecule in a solution.
     */
    public float getPureConcentration() {
        return getDensity() / getMass();
    };

    /**
     * The {@link ReadOnlyMixture#temperature temperature} (in kelvins) at which this Molecule forms a gas.
     */
    public float getBoilingPoint() {
        return boilingPoint;
    };

    /**
     * The polarity of this Molecule, used to determine its solubility with other Molecules.
     */
    public float getDipoleMoment() {
        return dipoleMoment;
    };

    /**
     * The energy required to heat one mole of this Molecule by one kelvin.
     */
    public float getMolarHeatCapacity() {
        return molarHeatCapacity;
    };

    /**
     * Whether this Molecule is cyclic (its {@link Formula structure} is not {@link Formula.Topology#LINEAR linear}).
     */
    public boolean isCyclic() {
        return structure.isCyclic();
    };

    /**
     * Generates a {@link Formula#shallowCopy copy} of the {@link Formula structure} of this Molecule.
     * @return A new Formula instance
     * @see Formula#shallowCopy How the copy is made
     */
    public Formula shallowCopyStructure() {
        return structure.shallowCopy();
    };

    /**
     * The Set of every {@link Atom} in this Formula - essentially its molecular formula.
     */
    public Set<Atom> getAtoms() {
        return structure.getAllAtoms();
    };

    /**
     * Whether this Molecule is {@link MoleculeTag tagged} as being {@link DestroyMolecules.Tags.HYPOTHETICAL hypothetical}.
     * This is typically any {@link Group#getExampleMolecule exemplar Molecule} for a {@link Group functional Group},
     * something than contains {@link Element#R_GROUP R-groups}, or a Molecule that can't actually exist.
     */
    public boolean isHypothetical() {
        return tags.contains(DestroyMolecules.Tags.HYPOTHETICAL);
    };

    /**
     * Get all the {@link MoleculeTag tags} this Molecule has.
     * @return
     */
    public ImmutableSet<MoleculeTag> getTags() {
        return ImmutableSet.copyOf(tags);
    };

    /**
     * Whether this Molecule has the given {@link MoleculeTag tag}.
     * @param tag
     */
    public boolean hasTag(MoleculeTag tag) {
        if (tag == null) return false;
        return tags.contains(tag);
    };

    /**
     * Whether this Molecule was generated by a {@link com.petrolpark.destroy.chemistry.genericreaction.GenericReaction Generic Reaction}.
     * @see Molecule What a novel Molecule is
     */
    public boolean isNovel() {
        return this.nameSpace == "novel";
    };

    /**
     * Gives all {@link Atom Atoms} in this Molecule, and how many of each there are.
     * @return Map of {@link Atom Atoms} to their quantities
     */
    public Map<Element, Integer> getMolecularFormula() {
        Map<Element, Integer> empiricalFormula = new HashMap<Element, Integer>();
        for (Atom atom : structure.getAllAtoms()) {
            Element element = atom.getElement();
            if (empiricalFormula.containsKey(element)) {
                int count = empiricalFormula.get(element);
                empiricalFormula.replace(element, count + 1);
            } else {
                empiricalFormula.put(element, 1);
            };
        };
        return empiricalFormula;
    };

    /**
     * Gives all {@link Atom Atoms} in this Molecule, and their quantities, in the format {@code AaBbCc...}, where {@code a} = number of Atoms of A, etc.
     * Elements are given in the order in which they are declared in {@link Element the Element Enum}.
     * @param subscript If {@code true}, Unicode subscript numbers will be used rather than ASCII numbers
     */
    @SuppressWarnings("unicode")
    public String getSerlializedMolecularFormula(boolean subscript) {
        Map<Element, Integer> formulaMap = getMolecularFormula();
        List<Element> elements = new ArrayList<>(formulaMap.keySet());
        elements.sort(Comparator.naturalOrder()); //sort Elements based on their order of declaration
        String formula = "";
        for (Element element : elements) {
            int count = formulaMap.get(element);
            String number = count == 1 ? "" : (subscript ? DestroyLang.toSubscript(count) : String.valueOf(count)); //if there is only one then don't print the number
            formula += element.getSymbol() + number;
        };
        return formula;
    };

    /**
     * Gives the <a href="https://github.com/petrolpark/Destroy/wiki/FROWNS">FROWNS</a> code of this Molecule.
     * @see Molecule#getFullID Getting the ID of this Molecule
     */
    public String getStructuralFormula() {
        return structure.serialize();
    };

    /**
     * A convenience method for getting the stability (relative to a carbon bonded to four other carbons) of a {@link Atom carbon}
     * in this structure, according to <a href="https://www.desmos.com/calculator/ks82fh30xq">this formula</a>. This is on the basis
     * that being surrounded by EDGs increases the stability of a carbocation.
     * @param carbon Can be any Atom, but the approximation is best for an Atom of the {@link Element} carbon
     * @param isCarbanion Whether this calculation should be inverted (to calculate the relative stability of a carbanion)
     * @return A value typically from 0-216
     */
    public Float getCarbocationStability(Atom carbon, boolean isCarbanion) {
        return structure.getCarbocationStability(carbon, isCarbanion);
    };

    /**
     * Mark this Molecule as being a necessary reactant in the given {@link Reaction}.
     * There should never be any need to call this method (it is done automatically when {@link Reaction.ReactionBuilder#build building} a Reaction).
     * @param reaction
     */
    public void addReactantReaction(Reaction reaction) {
        if (reaction.containsReactant(this)) reactantReactions.add(reaction);
    };

    /**
     * Mark this Molecule as being a necessary reactant in the given {@link Reaction}.
     * There should never be any need to call this method (it is done automatically when {@link Reaction.ReactionBuilder#build building} a Reaction).
     * @param reaction
     */
    public void addProductReaction(Reaction reaction) {
        if (reaction.containsProduct(this)) productReactions.add(reaction);
    };

    /**
     * Get the list of {@link Reaction Reactions} of which this Molecule is a necessary Reactant.
     * @return List of Reactions ordered by declaration
     */
    public List<Reaction> getReactantReactions() {
        return this.reactantReactions;
    };

    /**
     * Get the list of {@link Reaction Reactions} by which this Molecule is made.
     * @return List of Reactions ordered by declaration
     */
    public List<Reaction> getProductReactions() {
        return this.productReactions;
    };

    /**
     * Get the {@link Molecule#name display name} of this Molecule.
     * @param iupac Whether to use the IUPAC systematic name rather than the common one
     */
    @Override
    public Component getName(boolean iupac) {
        if (name == null) {
            String key = nameSpace + ".chemical." + translationKey;
            String iupacKey = key + ".iupac";
            if (iupac && I18n.exists(iupacKey)) {
                key = iupacKey;
            };
            name = Component.translatable(key);
        };
        return name;
    };

    /**
     * The color this Molecule adds to a {@link Mixture}.
     * @return ARGB value e.g. {@code 0xFFFF00FF}
     */
    public int getColor() {
        return color;
    };

    /**
     * Whether this Molecule's {@link Molecule#getColor color} is completely transparent (its alpha value is {@code 00}).
     */
    public boolean isColorless() {
        return color >> 24 == 0;
    };

    /**
     * Get a String representing the {@link Molecule#charge charge} of this Molecule.
     * For example, this will be {@code -} for the chloride ion, and {@code 2+} for the magnesium ion.
     * @param alwaysShowNumber If true, a Molecule with charge -1 will return {@code 1-} instead of {@code -}, etc.
     */
    public String getSerializedCharge(boolean alwaysShowNumber) {
        String chargeString = "";
        if (charge == 0) return chargeString;
        if (alwaysShowNumber || (charge != 1 && charge != -1)) chargeString += Math.abs(charge);
        chargeString += charge < 0 ? "-" : "+";
        return chargeString;
    };

    /**
     * Get all the {@link Group functional Groups} contained by this Molecule.
     */
    public List<Group> getFunctionalGroups() {
        return structure.getFunctionalGroups();
    };

    // RENDERING

    /**
     * Get a directed structure of this Molecule for use in {@link Molecule#getRenderer rendering}.
     */
    public Branch getRenderBranch() {
        return structure.getRenderBranch();
    };

    /**
     * Get the list of {@link Atom Atoms} (and their locations relative to the starting Atom) of all Atoms
     * in the base {@link Formula.Topology Topology} of this Molecule.
     * This does not include Atoms in side branches.
     * @return An empty list if this is an acyclic Molecule
     */
    public List<Pair<Vec3, Atom>> getCyclicAtomsForRendering() {
        if (!isCyclic()) return List.of();
        return structure.getCyclicAtomsForRendering();
    };

    /**
     * Get the list of {@link Bond Bonds} in the base {@link Formula.Topology Topology} of this Molecule.
     * @return An empty list if this is an acyclic Molecule
     */
    public List<Bond> getCyclicBondsForRendering() {
        if (!isCyclic()) return List.of();
        return structure.getCyclicBondsForRendering();
    };

    /**
     * Get the side-chains off of {@link Topology cyclic} {@link Atom Atoms} in this Molecule.
     * <strong>Do not use this for modifying {@link Formula structures} in {@link com.petrolpark.destroy.chemistry.genericreaction.GenericReaction
     * Generic Reaction generation}.</strong>
     * @return An empty list if this is an acyclic Molecule
     * @see Molecule#shallowCopyStructure How to properly modify structures
     */
    public List<Pair<SideChainInformation, Branch>> getSideChainsForRendering() {
        if (!isCyclic()) return List.of();
        return structure.getSideChainsForRendering();
    };

    /**
     * Get the {@link com.petrolpark.destroy.client.gui.MoleculeRenderer Renderer} for this Molecule.
     * To save on processing time, the first time Renderer is generated, it is {@link Molecule#renderer stored}
     * in this Molecule and referred to for later use.
     */
    public MoleculeRenderer getRenderer() {
        if (renderer == null) {
            renderer = new MoleculeRenderer(this);
        };
        return renderer;
    };

    /**
     * A class for constructing {@link Molecule Molecules}. This is typically used for:<ul>
     * <li>Declaring known Molecules ({@link DestroyMolecules example})</li>
     * <li>Constructing novel Molecules for {@link com.petrolpark.destroy.chemistry.genericreaction.GenericReaction Generic Reactions}
     * ({@link com.petrolpark.destroy.chemistry.index.genericreaction.HydroxideSubstitutions#generateReaction example}).</li>
     * </ul>
     * <p>Use {@code build()} to build get the Molecule.</p>
     */
    public static class MoleculeBuilder {

        private Molecule molecule;

        private Boolean hasForcedDensity = false; // Whether this molecule has a custom density or it should be calculated
        private Boolean hasForcedBoilingPoint = false; // Whether this molecule has a custom boiling point or it should be calculated
        private Boolean hasForcedDipoleMoment = false; // Whether this molecule has a forced dipole moment or it should be calculated
        private Boolean hasForcedMolarHeatCapacity = false; // Whether this molecule has a forced specific heat capacity or it should be calculated

        private String translationKey;

        /**
         * A {@link Molecule} constructor.
         * @param nameSpace The {@link Molecule#nameSpace name space} to which all Molecules constructed with this builder will belong
         * @throws IllegalArgumentException If a {@link Molecule#FORBIDDEN_NAMESPACES forbidden name space} is used, for example {@code novel} or any
         * {@link Formula.CycleType cycle type} ID.
         */
        public MoleculeBuilder(String nameSpace) {
            molecule = new Molecule(nameSpace);
            if (FORBIDDEN_NAMESPACES.contains(nameSpace)) {
                throw new IllegalArgumentException("Cannot use name space '"+nameSpace+"'.");
            };
            NAMESPACES.add(nameSpace);
            molecule.charge = 0; //default
        };

        /**
         * The internal {@link Molecule#id ID} for this Molecule.
         * By default, the {@link Molecule#translationKey translation key} for this Molecule will be set to its ID. 
         * This can be {@link Molecule.MoleculeBuilder#translationKey changed}.
         * If a Molecule is declared without an ID it will not be added to the {@link Molecule#MOLECULES Molecule register}.
         * @param id Must be unique
         * @return This Molecule Builder
         */
        public MoleculeBuilder id(String id) {
            molecule.id = id;
            translationKey(id);
            return this;
        };

        /**
         * Set the {@link Formula structure} of this Molecule.
         * To {@link Formula#addAllHydrogens add all hydrogens automatically}, use {@code addAllHydrogens()}.
         * @param structure
         * @return This Molecule Builder
         * @see DestroyMolecules Examples of use
         */
        public MoleculeBuilder structure(Formula structure) {
            molecule.structure = structure;
            return this;
        };

        /**
         * Set the {@link Molecule#density density} of this Molecule in grams per Bucket.
         * @param density
         * @return This Molecule Builder
         */
        public MoleculeBuilder density(float density) {
            molecule.density = density;
            hasForcedDensity = true;
            return this;
        };

        /**
         * Set the overall {@link Molecule#charge charge} of this Molecule.
         * @param charge
         * @return This Molecule Builder
         */
        public MoleculeBuilder charge(int charge) {
            molecule.charge = charge;
            return this;
        };

        /**
         * Set the {@link Molecule#boilingPoint boiling point} in degrees Celsius.
         * If not supplied, the boiling point will be very loosely {@link MoleculeBuilder#calculateBoilingPoint estimated}, but setting one is recommended.
         * @param boilingPoint In degrees Celcius
         * @return This Molecule Builder
         */
        public MoleculeBuilder boilingPoint(float boilingPoint) {
            return boilingPointInKelvins(boilingPoint + 273);
        };

        /**
         * Set the {@link Molecule#boilingPoint boiling point} in kelvins.
         * If not supplied, the boiling point will be very loosely {@link MoleculeBuilder#calculateBoilingPoint estimated}, but setting one is recommended.
         * @param boilingPoint In kelvins
         * @return This Molecule Builder
         */
        public MoleculeBuilder boilingPointInKelvins(float boilingPoint) {
            molecule.boilingPoint = boilingPoint;
            hasForcedBoilingPoint = true;
            return this;
        };

        /**
         * Set the {@link Molecule#dipoleMoment dipole moment} of this Molecule.
         * If not supplied, a dipole moment will be very loosely {@link MoleculeBuilder#calculateDipoleMoment estimated}, but setting one is recommended.
         * @return This Molecule Builder
         */
        public MoleculeBuilder dipoleMoment(int dipoleMoment) {
            molecule.dipoleMoment = dipoleMoment;
            hasForcedDipoleMoment = true;
            return this;
        };

        /**
         * Set the specific heat capacity of this Molecule, in joules per kilogram-kelvin.
         * This method is just for ease of input - Molecules work with {@link Molecule#molarHeatCapacity}
         * and the specific heat capacity given here will be converted to this.
         * @return This Molecule Builder
         */
        public MoleculeBuilder specificHeatCapacity(float specificHeatCapacity) {
            molecule.molarHeatCapacity = specificHeatCapacity * 1000 / calculateMass();
            hasForcedMolarHeatCapacity = true;
            return this;
        };

        /**
         * Set the {@link Molecule#molarHeatCapacity molar heat capacity} for this Molecule,
         * in joules per mole-kelvin.
         * @param molarHeatCapacity
         */
        public MoleculeBuilder molarHeatCapacity(float molarHeatCapacity) {
            molecule.molarHeatCapacity = molarHeatCapacity;
            hasForcedMolarHeatCapacity = true;
            return this;
        };

        /**
         * Sets the {@link Molecule#translationKey translation key} of this Molecule, so that the full translation key is {@code <namespace>.chemical.<translationKey>}.
         * @param translationKey The last term in the full translation key (e.g. {@code acetone} not {@code destroy.chemical.acetone}.)
         * @return This Molecule Builder
         */
        public MoleculeBuilder translationKey(String translationKey) {
            this.translationKey = translationKey;
            return this;
        };

        /**
         * Set the {@link Molecule#color color} of this Molecule.
         * @param color ARGB color e.g. {@code 0xFFFF00FF}
         * @return This Molecule Builder
         */
        public MoleculeBuilder color(int color) {
            molecule.color = color;
            return this;
        };

        /**
         * Mark this Molecule as being hypothetical - if this Molecule appears in a solution, an error will be raised.
         * @return This Molecule Builder
         */
        public MoleculeBuilder hypothetical() {
            return tag(DestroyMolecules.Tags.HYPOTHETICAL);
        };

        /**
         * Adds the given tags to this Molecule.
         * @return This Molecule Builder
         */
        public MoleculeBuilder tag(MoleculeTag ...tags) {
            molecule.tags.addAll(List.of(tags));
            return this;
        };

        /**
         * Builds the {@link Molecule}. This will also:<ul>
         * <li>Estimate the {@link MoleculeBuilder#boilingPoint boiling point} and {@link MoleculeBuilder#dipoleMoment dipole moment} if they were not supplied.</li>
         * <li>Check {@link Molecule#MOLECULES existing Molecules} to see if a Molecule with the same {@link Formula structure} already exists.</li>
         * <li>Use all known {@link GroupFinder functional Group Finders} to identify {@link Group functional Groups} in the Molecule.</li>
         * </ul><p>This is the only safe way to declare a Molecule.</p>
         * @return A new Molecule instance
         * @throws IllegalArgumentException If the Molecule's {@link Formula structure} was not {@link MoleculeBuilder#structure declared},
         * or it is not novel and the {@link Molecule#nameSpace name space} was not declared.
         */
        public Molecule build() {

            molecule.mass = calculateMass();
            molecule.translationKey = translationKey;

            if (molecule.structure == null) {
                throw new IllegalArgumentException("Molecule's structure has not been declared");
            };

            if (molecule.nameSpace == "novel") {
                Molecule equivalentMolecule = molecule.getEquivalent();
                if (equivalentMolecule != molecule) {
                    return equivalentMolecule;
                };
            };

            if (molecule.getMolecularFormula().containsKey(Element.R_GROUP)) {
                tag(DestroyMolecules.Tags.HYPOTHETICAL);
            };

            if (!hasForcedDensity && molecule.charge != 0) {
                molecule.density = 1000f;
            };
            
            if (!hasForcedBoilingPoint) {
                molecule.boilingPoint = calculateBoilingPoint();
            };

            if (!hasForcedDipoleMoment) {
                molecule.dipoleMoment = calculateDipoleMoment();
            };

            if (!hasForcedMolarHeatCapacity) {
                molecule.molarHeatCapacity = 100f;
            };
            
            if (molecule.color == 0) {
                molecule.color = 0x20FFFFFF;
            };

            molecule.refreshFunctionalGroups();

            if (molecule.nameSpace != "novel") {
                if (molecule.id == null) {
                    throw new IllegalArgumentException("Molecule's ID has not been declared.");
                } else {
                    MOLECULES.put(molecule.nameSpace+":"+molecule.id, molecule);
                };
            };

            return molecule;
        };

        private float calculateMass() {
            float total = 0f;
            Set<Atom> atoms = molecule.structure.getAllAtoms();
            for (Atom atom : atoms) {
                total += atom.getElement().getMass();
            };
            return total;
        };

        private float calculateBoilingPoint() {
            return Float.MAX_VALUE; //TODO calculate boiling point
        };

        private int calculateDipoleMoment() {
            //TODO calculate Dipole Moment
            return 0;
        };
    };

    private void refreshFunctionalGroups() {
        structure.refreshFunctionalGroups();
    };

};

