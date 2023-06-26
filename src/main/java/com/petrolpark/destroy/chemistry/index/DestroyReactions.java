package com.petrolpark.destroy.chemistry.index;

import com.petrolpark.destroy.chemistry.Reaction;
import com.petrolpark.destroy.chemistry.Reaction.ReactionBuilder;

public class DestroyReactions {

    public static final Reaction
    
    ACETIC_ANHYDRIDE_HYDROLYSIS = builder()
        .addReactant(DestroyMolecules.ACETIC_ANHYDRIDE)
        .addReactant(DestroyMolecules.WATER)
        .addProduct(DestroyMolecules.ACETIC_ACID, 2)
        .build(),

    CHLORODIFLUOROMETHANE_PYROLYSIS = builder()
        .addReactant(DestroyMolecules.CHLORODIFLUOROMETHANE, 2)
        .addProduct(DestroyMolecules.HYDROFLUORIC_ACID, 2)
        .addProduct(DestroyMolecules.TETRAFLUOROETHENE)
        .build(),

    CHLOROFORM_FLUORINATION = builder()
        .addReactant(DestroyMolecules.CHLOROFORM)
        .addReactant(DestroyMolecules.HYDROFLUORIC_ACID, 2)
        .addProduct(DestroyMolecules.CHLORODIFLUOROMETHANE)
        .addProduct(DestroyMolecules.HYDROCHLORIC_ACID, 2)
        .build(),

    METHYL_ACETATE_CARBONYLATION = builder()
        .translationKey("methyl_acetate_carbonylation")
        .addReactant(DestroyMolecules.METHANOL)
        .addReactant(DestroyMolecules.CARBON_MONOXIDE)
        .addProduct(DestroyMolecules.ACETIC_ACID)
        .build(),

    METHYL_ACETATE_ACID_HYDROLYSIS = builder()
        .addReactant(DestroyMolecules.METHYL_ACETATE)
        .addReactant(DestroyMolecules.WATER)
        .addProduct(DestroyMolecules.METHANOL)
        .addProduct(DestroyMolecules.ACETIC_ACID)
        .build(),

    HYDROXIDE_NEUTRALIZATION = builder()
        .translationKey("hydroxide_neutralization")
        .addReactant(DestroyMolecules.HYDROXIDE)
        .addReactant(DestroyMolecules.PROTON)
        .addProduct(DestroyMolecules.WATER)
        .activationEnergy(0f)
        .preexponentialFactor(1e14f)
        .build();

    private static ReactionBuilder builder() {
        return new ReactionBuilder(false);
    };

    public static void register() {};
}
