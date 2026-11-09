package com.humanagent.client.aim;

import com.humanagent.client.ml.PureJavaMLP;
import com.humanagent.config.AgentConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * Layer 4 Human Aiming Engine:
 * Combines WindMouse biomechanical physics + Colab PureJavaMLP neural trajectory prediction
 * + Vanilla Minecraft mouse sensitivity GCD quantization.
 * Strictly updates real client camera angles (zero silent packet rotation spoofing).
 */
public final class HumanAimProcessor {
    private static final double SQRT_3 = Math.sqrt(3.0);
    private static final double SQRT_5 = Math.sqrt(5.0);

    private final PureJavaMLP mlp;
    private final Random random = new Random();

    // WindMouse internal state (inertia + fluctuating wind force)
    private double windX = 0.0;
    private double windY = 0.0;
    private float prevStepYaw = 0.0f;
    private float prevStepPitch = 0.0f;
    private float lastTargetYaw = 0.0f;

    // Safe 60% block hitbox drift phase (Noise Without Risk)
    private double hitboxDriftPhase = 0.0;
    private int idleGlanceCooldown = 60;
    private float idleYawOffset = 0.0f;
    private float idlePitchOffset = 0.0f;

    public HumanAimProcessor(PureJavaMLP mlp) {
        this.mlp = mlp;
    }

    /**
     * Computes a human aim point inside the safe middle 60% [0.20, 0.80] of a block instead of dead-center (0.5, 0.5, 0.5).
     */
    public Vec3 getSafeHumanBlockAimPoint(BlockPos pos) {
        hitboxDriftPhase += 0.11 + random.nextDouble() * 0.04;
        double offsetX = 0.5 + 0.22 * Math.sin(hitboxDriftPhase) + 0.05 * Math.cos(hitboxDriftPhase * 2.3);
        double offsetY = 0.5 + 0.18 * Math.cos(hitboxDriftPhase * 0.85);
        double offsetZ = 0.5 + 0.22 * Math.sin(hitboxDriftPhase * 1.3 + 1.1);

        offsetX = Mth.clamp(offsetX, 0.20, 0.80);
        offsetY = Mth.clamp(offsetY, 0.20, 0.80);
        offsetZ = Mth.clamp(offsetZ, 0.20, 0.80);
        return new Vec3(pos.getX() + offsetX, pos.getY() + offsetY, pos.getZ() + offsetZ);
    }

    /**
     * Computes a human aim point within the inner 65% of an entity's bounding box (chest/neck height with natural drift).
     */
    public Vec3 getSafeHumanEntityAimPoint(LocalPlayer player, Entity target) {
        hitboxDriftPhase += 0.17;
        AABB box = target.getBoundingBox();
        double widthX = box.getXsize();
        double heightY = box.getYsize();
        double widthZ = box.getZsize();

        // Clamp closest point on entity torso with subtle sinusoidal tracking lag
        Vec3 eyePos = player.getEyePosition();
        double baseAimX = Mth.clamp(eyePos.x, box.minX + widthX * 0.22, box.maxX - widthX * 0.22);
        double baseAimY = Mth.clamp(eyePos.y - 0.12, box.minY + heightY * 0.35, box.maxY - heightY * 0.15);
        double baseAimZ = Mth.clamp(eyePos.z, box.minZ + widthZ * 0.22, box.maxZ - widthZ * 0.22);

        double driftX = Math.sin(hitboxDriftPhase) * (widthX * 0.12);
        double driftY = Math.cos(hitboxDriftPhase * 1.4) * (heightY * 0.06);
        double driftZ = Math.cos(hitboxDriftPhase * 0.9) * (widthZ * 0.12);

        return new Vec3(baseAimX + driftX, baseAimY + driftY, baseAimZ + driftZ);
    }

    /**
     * Smoothly rotates the player's real client camera toward a 3D world coordinate.
     * Returns true if the crosshair is within the given angular tolerance (degrees).
     */
    public boolean smoothLookAt(Minecraft mc, Vec3 targetWorldPos, float toleranceDeg) {
        LocalPlayer player = mc.player;
        if (player == null) return false;

        Vec3 eye = player.getEyePosition();
        double dx = targetWorldPos.x - eye.x;
        double dy = targetWorldPos.y - eye.y;
        double dz = targetWorldPos.z - eye.z;
        double horizDist = Math.hypot(dx, dz);

        float desiredYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float desiredPitch = (float) (-(Mth.atan2(dy, horizDist) * (180.0 / Math.PI)));

        return smoothLookAtAngles(mc, desiredYaw, desiredPitch, toleranceDeg);
    }

    /**
     * Smoothly rotates the player's real client camera toward (desiredYaw, desiredPitch) using
     * WindMouse physics + PureJavaMLP neural trajectory + hardware mouse sensitivity GCD rounding.
     */
    public boolean smoothLookAtAngles(Minecraft mc, float desiredYaw, float desiredPitch, float toleranceDeg) {
        LocalPlayer player = mc.player;
        if (player == null) return false;

        AgentConfig cfg = AgentConfig.get();
        float currentYaw = player.getYRot();
        float currentPitch = player.getXRot();

        float deltaYaw = Mth.wrapDegrees(desiredYaw - currentYaw);
        float deltaPitch = Mth.clamp(desiredPitch, -89.5f, 89.5f) - currentPitch;
        float angularDist = (float) Math.hypot(deltaYaw, deltaPitch);

        float targetAngVel = Mth.wrapDegrees(desiredYaw - lastTargetYaw);
        lastTargetYaw = desiredYaw;

        if (angularDist < 0.15f) {
            prevStepYaw *= 0.4f;
            prevStepPitch *= 0.4f;
            return true;
        }

        // 1. Neural Network Trajectory Prediction (Colab-trained MLP)
        float[] mlpStep = mlp.predictAimStep(deltaYaw, deltaPitch, angularDist, prevStepYaw, prevStepPitch, targetAngVel);

        // 2. WindMouse Biomechanical Physics Step
        double gravity = cfg.windGravity;
        double windMag = cfg.windNoiseStrength;
        if (angularDist >= 12.0f) {
            windX = windX / SQRT_3 + (random.nextDouble() * 2.0 - 1.0) * windMag / SQRT_5;
            windY = windY / SQRT_3 + (random.nextDouble() * 2.0 - 1.0) * windMag / SQRT_5;
        } else {
            // Dampen wind inside fine-aim zone so we never miss a block placement or jump
            windX /= SQRT_3;
            windY /= SQRT_3;
        }

        double gain = Math.min(0.42, 0.14 + 0.26 * Math.tanh(angularDist / 20.0));
        double windStepYaw = deltaYaw * gain + windX * (angularDist / 45.0) + 0.22 * prevStepYaw;
        double windStepPitch = deltaPitch * gain + windY * (angularDist / 45.0) + 0.22 * prevStepPitch;

        // 3. Blend Neural Network + WindMouse Physics
        float blend = (float) Mth.clamp(cfg.neuralBlendWeight, 0.0, 1.0);
        float rawStepYaw = (float) (blend * mlpStep[0] + (1.0f - blend) * windStepYaw);
        float rawStepPitch = (float) (blend * mlpStep[1] + (1.0f - blend) * windStepPitch);

        // Prevent overshooting when very close to target
        if (Math.abs(rawStepYaw) > Math.abs(deltaYaw) * 1.15f) {
            rawStepYaw = deltaYaw * 0.85f;
        }
        if (Math.abs(rawStepPitch) > Math.abs(deltaPitch) * 1.15f) {
            rawStepPitch = deltaPitch * 0.85f;
        }

        // 4. Hardware Mouse Sensitivity GCD Quantization (passes GrimAC / Vulcan mouse checks)
        float quantizedStepYaw = rawStepYaw;
        float quantizedStepPitch = rawStepPitch;
        if (cfg.quantizeToMouseSensitivityGcd) {
            double sens = mc.options.sensitivity().get();
            double f = sens * 0.6 + 0.2;
            double gcd = f * f * f * 8.0 * 0.15;
            if (gcd > 1e-6) {
                quantizedStepYaw = (float) (Math.rint(rawStepYaw / gcd) * gcd);
                quantizedStepPitch = (float) (Math.rint(rawStepPitch / gcd) * gcd);
            }
        }

        float newYaw = currentYaw + quantizedStepYaw;
        float newPitch = Mth.clamp(currentPitch + quantizedStepPitch, -89.5f, 89.5f);

        player.setYRot(newYaw);
        player.setXRot(newPitch);
        player.yRotO = currentYaw;
        player.xRotO = currentPitch;

        prevStepYaw = quantizedStepYaw;
        prevStepPitch = quantizedStepPitch;

        float remainingDist = (float) Math.hypot(Mth.wrapDegrees(desiredYaw - newYaw), desiredPitch - newPitch);
        return remainingDist <= toleranceDeg;
    }

    /**
     * Generates subtle human curiosity head glances when walking long open paths with no immediate block interaction.
     */
    public float[] computeNaturalIdleGlanceOffset() {
        if (!AgentConfig.get().enableIdleHeadGlances) {
            return new float[]{0.0f, 0.0f};
        }
        if (--idleGlanceCooldown <= 0) {
            idleGlanceCooldown = 35 + random.nextInt(55);
            if (random.nextFloat() < 0.35f) {
                idleYawOffset = (random.nextFloat() - 0.5f) * 14.0f;
                idlePitchOffset = (random.nextFloat() - 0.5f) * 6.0f;
            } else {
                idleYawOffset = 0.0f;
                idlePitchOffset = 0.0f;
            }
        }
        return new float[]{idleYawOffset, idlePitchOffset};
    }

    /**
     * Generates a Gaussian-distributed click interval in ticks (1 tick = 50ms) that avoids constant metronome patterns.
     */
    public int sampleGaussianClickDelayTicks(double targetCps, double stdDevCps) {
        double cps = Math.max(4.0, targetCps + random.nextGaussian() * stdDevCps);
        double ticksExact = 20.0 / cps;
        int baseTicks = (int) Math.floor(ticksExact);
        double fractional = ticksExact - baseTicks;
        return Math.max(1, baseTicks + (random.nextDouble() < fractional ? 1 : 0));
    }
}
