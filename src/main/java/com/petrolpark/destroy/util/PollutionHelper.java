package com.petrolpark.destroy.util;

import java.util.List;
import java.util.Optional;

import org.joml.Vector3f;

import com.petrolpark.destroy.Destroy;
import com.petrolpark.destroy.capability.Pollution;
import com.petrolpark.destroy.capability.Pollution.PollutionType;
import com.petrolpark.destroy.chemistry.legacy.LegacySpecies;
import com.petrolpark.destroy.chemistry.legacy.ReadOnlyMixture;
import com.petrolpark.destroy.config.DestroyAllConfigs;
import com.petrolpark.destroy.fluid.DestroyFluids;
import com.petrolpark.destroy.network.DestroyMessages;
import com.petrolpark.destroy.network.packet.EvaporatingFluidS2CPacket;
import com.petrolpark.destroy.network.packet.LevelPollutionS2CPacket;
import com.petrolpark.destroy.network.packet.SyncChunkPollutionS2CPacket;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.fluids.FluidStack;

public class PollutionHelper {

    /**
     * Whether Pollution is enabled in this world.
     */
    public static boolean pollutionEnabled() {
        return DestroyAllConfigs.SERVER.pollution.enablePollution.get();
    };

    private static Optional<? extends Pollution> getCapOp(Level level, BlockPos pos, PollutionType pollutionType) {
        return pollutionType.local ? level.getChunkAt(pos).getCapability(Pollution.CAPABILITY).resolve() : level.getCapability(Pollution.CAPABILITY).resolve();
    };

    /**
     * Gets the level of pollution of the given Type in the given Level.
     * @param level
     * @param pollutionType
     * @return 0 if the Level does not have the Level Pollution capability
     */
    public static int getPollution(Level level, BlockPos pos, PollutionType pollutionType) {
        return getCapOp(level, pos, pollutionType).map(p -> p.get(pollutionType)).orElse(0);
    };

    /**
     * Sets the level of pollution of the given Type in the given Level.
     * The change is broadcast to all clients (Avoid this by using the {@link com.petrolpark.destroy.capability.Pollution#set set()} method instead).
     * @param level
     * @param pollutionType
     * @param value Will be set within the {@link com.petrolpark.destroy.capability.Pollution.PollutionType bounds}.
     * @return The actual value to which the level of pollution was set (0 if there was no Capability)
     */
    public static int setPollution(Level level, BlockPos pos, PollutionType pollutionType, int value) {
        return getCapOp(level, pos, pollutionType).map(pollution -> {
            int oldValue = pollution.get(pollutionType);
            int newValue = pollution.set(pollutionType, value); // Actually set the Pollution level

            if (oldValue != newValue && level instanceof ServerLevel serverLevel) {
                if (pollutionType.local) {
                    ChunkPos chunkPos = new ChunkPos(pos);
                    LevelChunk chunk = (LevelChunk)serverLevel.getChunk(chunkPos.x, chunkPos.z, ChunkStatus.FULL, false);
                    if (chunk != null) DestroyMessages.sendToClientsTrackingChunk(new SyncChunkPollutionS2CPacket(chunkPos, pollution), chunk);
                } else {
                    DestroyMessages.sendToAllClientsInDimension(new LevelPollutionS2CPacket(pollution), serverLevel);
                };
            };

            return newValue;
        }).orElse(0);
    };

    /**
     * Changes the level of pollution of the given Type in the given Level by the given amount.
     * The change is broadcast to all clients (Avoid this by using the {@link com.petrolpark.destroy.capability.Pollution#change change()} method instead).
     * @param level
     * @param pollutionType
     * @param change Can be positive or negative; will be set within the {@link com.petrolpark.destroy.capability.Pollution.PollutionType bounds}.
     * @return The actual value to which the level of pollution was set (0 if there was no Capability)
     */
    public static int changePollution(Level level, BlockPos pos, PollutionType pollutionType, int change) {
        return getCapOp(level, pos, pollutionType).map(pollution -> pollution.set(pollutionType, Mth.clamp(pollution.get(pollutionType) + change, 0, pollutionType.max))).orElse(0);
    };

    public static int changePollutionGlobal(Level level, PollutionType pollutionType, int change) {
        if (pollutionType.local) {
            Destroy.LOGGER.warn("Tried to change global Pollution level of a Pollution Type which is not global.");
            return 0;
        };
        return changePollution(level, BlockPos.ZERO, pollutionType, change);
    };

    /**
     * Pollute a single Fluid Stack, with no Particles, and without damaging nearby Entities.
     * @param level
     * @param fluidStack
     * @see PollutionHelper#pollute(Level, BlockPos, int, FluidStack...) Harming Entities and showing evaporation particles too
     */
    @SuppressWarnings("deprecation")
    public static void pollute(Level level, BlockPos pos, float multiplier, FluidStack fluidStack) {
        if (DestroyFluids.isMixture(fluidStack) && fluidStack.getOrCreateTag().contains("Mixture", Tag.TAG_COMPOUND)) {
            polluteMixture(level, pos, multiplier, fluidStack.getAmount(), fluidStack.getOrCreateTag());
        } else {
            for (PollutionType pollutionType : PollutionType.values()) {
                if (fluidStack.getFluid().is(pollutionType.fluidTag)) {
                    float pollutionAmount = multiplier * (float)fluidStack.getAmount() / 250f;
                    if (pollutionAmount < 1f) {
                        if (level.random.nextFloat() <= pollutionAmount) changePollution(level, pos, pollutionType, 1);
                    } else {
                        changePollution(level, pos, pollutionType, (int)pollutionAmount);
                    };
                };
            };
        };
    };

    public static void polluteMixture(Level level, BlockPos pos, float multiplier, int amount, CompoundTag fluidTag) {
        ReadOnlyMixture mixture = ReadOnlyMixture.readNBT(ReadOnlyMixture::new, fluidTag.getCompound("Mixture"));
        for (LegacySpecies molecule : mixture.getContents(true)) {
            double pollutionAmount = multiplier * mixture.getConcentrationOf(molecule) * amount / 1000; // One mole of polluting Molecule = one point of Pollution
            for (PollutionType pollutionType : PollutionType.values()) {
                if (molecule.hasTag(pollutionType.moleculeTag)) {
                    if (pollutionAmount < 1) {
                        if (level.random.nextFloat() <= pollutionAmount) changePollution(level, pos, pollutionType, 1);
                    } else {
                        changePollution(level, pos, pollutionType, (int)pollutionAmount);
                    };
                };
            };
        };
    };

    /**
     * Release the given Fluids into the environment, sometimes summon evaporation particles, and expose nearby entities to the effects of the chemicals.
     * @param level The level in which the pollution is taking place
     * @param blockPos The position from which the evaporation Particles should originate
     * @param particleWeight There will be a {@code 1} in {@code particleWeight} chance of a Particle being shown. If this is {@code 1} (as is the default), there will always be a Particle
     * @param fluidStacks The Fluids with which to pollute
     */
    public static void pollute(Level level, BlockPos blockPos, float multiplier, int particleWeight, FluidStack ...fluidStacks) {
        if (level.isClientSide()) return;
        List<LivingEntity> nearbyEntities = level.getEntities(null, new AABB(blockPos).inflate(2)).stream().filter(e -> e instanceof LivingEntity).map(e -> (LivingEntity)e).toList();
        for (FluidStack fluidStack : List.of(fluidStacks)) {
            pollute(level, blockPos, multiplier, fluidStack);
            if (particleWeight == 1 || level.getRandom().nextInt(particleWeight) == 0); DestroyMessages.sendToAllClients(new EvaporatingFluidS2CPacket(blockPos, fluidStack));
            for (LivingEntity entity : nearbyEntities) {
                ChemistryDamageHelper.damage(level, entity, fluidStack, true);
            };
        };
    };

    public static void pollute(Level level, BlockPos pos, FluidStack ...fluidStacks) {
        pollute(level, pos, 1f, 1, fluidStacks);
    };

    /**
     * Release the given Fluids into the environment and summon evaporation particles.
     * @param level The Level in which the pollution is taking place
     * @param blockPos The position from which the evaporation Particles should originate
     * @param fluidStacks The Fluids with which to pollute
     */
    public static void pollute(Level level, BlockPos blockPos, float multiplier, FluidStack ...fluidStacks) {
        pollute(level, blockPos, multiplier, 1, fluidStacks);
    };

    public static DustParticleOptions cropGrowthFailureParticles() {
        return new DustParticleOptions(new Vector3f(109 / 256f, 77 / 256f, 14 / 256f), 1f);
    };
};
