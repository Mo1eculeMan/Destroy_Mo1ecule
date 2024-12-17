package com.petrolpark.destroy.client.particle;

import javax.annotation.Nullable;

import com.petrolpark.destroy.client.particle.data.BoilingFluidBubbleParticleData;
import com.simibubi.create.content.fluids.particle.FluidStackParticle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraftforge.fluids.FluidStack;

public class BoilingFluidBubbleParticle extends FluidStackParticle {

    protected final SpriteSet spriteSet;

    public BoilingFluidBubbleParticle(ClientLevel world, FluidStack fluid, double x, double y, double z, double vx, double vy, double vz, SpriteSet sprites) {
        super(world, fluid, x, y, z, vx, vy, vz);
        this.spriteSet = sprites;
        setSprite(sprites.get(0, 6));
        lifetime = 10 + world.random.nextInt(20);
        gravity = 0f;
    };

    @Override
    public void tick() {
        if (lifetime - age >= 0 && lifetime - age < 6) {
            setSprite(spriteSet.get(age - lifetime + 5, 5));
        };

        scale(1.04f);
        super.tick();
    };

    @Override
    protected float getU0() {
        return sprite.getU0();
    };

    @Override
    protected float getU1() {
        return sprite.getU1();
    };

    @Override
    protected float getV0() {
        return sprite.getV0();
    };

    @Override
    protected float getV1() {
        return sprite.getV1();
    };

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    };

    @Override
    protected boolean canEvaporate() {
        return false;
    };

    public static class Provider implements ParticleProvider<BoilingFluidBubbleParticleData> {
        private final SpriteSet spriteSet;

        public Provider(SpriteSet spriteSet) {
            this.spriteSet = spriteSet;
        };

        @Override
        @Nullable
        public Particle createParticle(BoilingFluidBubbleParticleData data, ClientLevel level, double x, double y, double z, double vx, double vy, double vz) {
            return new BoilingFluidBubbleParticle(level, data.getFluid(), x, y, z, vx, vy, vz, spriteSet);
        };
    };
};
