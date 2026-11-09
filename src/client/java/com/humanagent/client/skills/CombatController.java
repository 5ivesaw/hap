package com.humanagent.client.skills;

import com.humanagent.client.aim.HumanAimProcessor;
import com.humanagent.client.ml.PureJavaMLP;
import com.humanagent.config.AgentConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.Random;

/**
 * Layer 3B Dual Combat Controller:
 * Supports both 1.8.9 PvP (via ViaFabricPlus on Hypixel Bedwars/SkyWars) and Modern 26.3 PvP.
 * Uses PureJavaMLP (StrafeNet) for W-tapping, S-tapping, and void-aware circle strafing.
 */
public final class CombatController {
    private final HumanAimProcessor aimProcessor;
    private final PureJavaMLP mlp;
    private final Random random = new Random();

    private int attackClickTimer = 0;
    private int wTapReleaseTicks = 0;
    private double strafePhase = 0.0;

    public CombatController(HumanAimProcessor aimProcessor, PureJavaMLP mlp) {
        this.aimProcessor = aimProcessor;
        this.mlp = mlp;
    }

    public LivingEntity findNearestThreat(Minecraft mc, double maxRange) {
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null) return null;

        return level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(maxRange), e -> {
            if (e == player || !e.isAlive() || e.isSpectator()) return false;
            if (e instanceof Player otherPlayer) {
                return !otherPlayer.isCreative() && !isSameTeam(player, otherPlayer);
            }
            return e instanceof Monster;
        }).stream().min(Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
    }

    private boolean isSameTeam(LocalPlayer me, Player other) {
        if (me.getTeam() != null && other.getTeam() != null) {
            return me.getTeam().isAlliedTo(other.getTeam());
        }
        return false;
    }

    public void tickCombat(Minecraft mc, LivingEntity target) {
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null || target == null) return;

        AgentConfig cfg = AgentConfig.get();
        // 1. Humanized torso/neck tracking with safe hitbox drift
        Vec3 aimPoint = aimProcessor.getSafeHumanEntityAimPoint(player, target);
        aimProcessor.smoothLookAt(mc, aimPoint, 3.5f);

        // 2. Neural Strafe & W-Tap Movement Execution
        strafePhase += 0.14 + (random.nextDouble() - 0.5) * 0.05;
        float dist = player.distanceTo(target);

        Vec3 enemyLook = target.getLookAngle();
        Vec3 toUs = player.position().subtract(target.position()).normalize();
        float enemyRelAngle = (float) (Math.acos(Mth.clamp(enemyLook.dot(toUs), -1.0, 1.0)) * (180.0 / Math.PI));

        float hazardLeft = computeDropHazard(level, player, -90.0f);
        float hazardRight = computeDropHazard(level, player, 90.0f);

        float[] keyProbs = mlp.predictCombatStrafe(
                dist, enemyRelAngle, target.hurtTime, player.hurtTime,
                (float) Math.sin(strafePhase), hazardLeft, hazardRight, player.getXRot()
        );

        Options opts = mc.options;
        if (wTapReleaseTicks > 0) {
            wTapReleaseTicks--;
            opts.keyUp.setDown(false);
        } else {
            opts.keyUp.setDown(keyProbs[0] > 0.5f);
        }
        opts.keyLeft.setDown(cfg.enableCircleStrafe && keyProbs[1] > 0.52f && hazardLeft < 0.6f);
        opts.keyDown.setDown(keyProbs[2] > 0.55f);
        opts.keyRight.setDown(cfg.enableCircleStrafe && keyProbs[3] > 0.52f && hazardRight < 0.6f && !opts.keyLeft.isDown());
        opts.keyJump.setDown(keyProbs[4] > 0.55f && player.onGround());
        opts.keySprint.setDown(opts.keyUp.isDown());

        // 3. Protocol-Aware Attack Execution (1.8 ViaFabricPlus vs 26.3 Cooldown)
        if (attackClickTimer > 0) {
            attackClickTimer--;
        }

        boolean isLegacy18 = isLegacy18CombatActive(cfg);
        if (isLegacy18) {
            executeLegacy18Attack(mc, player, target, cfg);
        } else {
            executeModern263Attack(mc, player, target);
        }
    }

    private void executeLegacy18Attack(Minecraft mc, LocalPlayer player, LivingEntity target, AgentConfig cfg) {
        if (attackClickTimer > 0) return;
        double distSq = player.getEyePosition().distanceToSqr(aimProcessor.getSafeHumanEntityAimPoint(player, target));
        if (distSq > 11.5) return; // Only swing within realistic anticipation/reach range (~3.39 blocks)

        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY) {
            Entity hitEntity = ((EntityHitResult) mc.hitResult).getEntity();
            if (hitEntity == target && mc.gameMode != null) {
                mc.gameMode.attack(player, target);
                player.swing(InteractionHand.MAIN_HAND);
                if (cfg.enableWTap && player.isSprinting()) {
                    wTapReleaseTicks = 1 + random.nextInt(2); // 1-2 tick W-tap sprint reset
                }
            }
        } else if (distSq <= 10.5) {
            // Natural air-swing when tracking fast-strafing opponents in 1.8 PvP
            player.swing(InteractionHand.MAIN_HAND);
        }
        attackClickTimer = aimProcessor.sampleGaussianClickDelayTicks(cfg.baseCps18, cfg.cpsStdDev18);
    }

    private void executeModern263Attack(Minecraft mc, LocalPlayer player, LivingEntity target) {
        if (attackClickTimer > 0 || mc.gameMode == null) return;

        // If target is blocking with a shield, briefly swap to an axe if in hotbar
        if (target instanceof Player targetPlayer && targetPlayer.isBlocking()) {
            equipHotbarAxe(player);
        }

        float cooldown = player.getAttackStrengthScale(0.0f);
        float threshold = 0.91f + random.nextFloat() * 0.08f;
        if (cooldown >= threshold && mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY) {
            Entity hitEntity = ((EntityHitResult) mc.hitResult).getEntity();
            if (hitEntity == target) {
                mc.gameMode.attack(player, target);
                player.swing(InteractionHand.MAIN_HAND);
                attackClickTimer = 2 + random.nextInt(2);
            }
        }
    }

    private void equipHotbarAxe(LocalPlayer player) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getItem(i).getItem() instanceof AxeItem) {
                player.getInventory().setSelectedSlot(i);
                return;
            }
        }
    }

    private boolean isLegacy18CombatActive(AgentConfig cfg) {
        if (cfg.forceLegacy18Pvp) return true;
        if (!cfg.autoDetectViaFabricPlus) return false;
        return FabricLoader.getInstance().isModLoaded("viafabricplus");
    }

    /**
     * Checks 2.5 blocks to the left or right of the player for lethal drops or Bedwars void edges.
     */
    private float computeDropHazard(Level level, LocalPlayer player, float relativeYawOffset) {
        float checkYawRad = (float) Math.toRadians(player.getYRot() + relativeYawOffset);
        double dx = -Math.sin(checkYawRad) * 2.2;
        double dz = Math.cos(checkYawRad) * 2.2;
        BlockPos checkBase = BlockPos.containing(player.getX() + dx, player.getY(), player.getZ() + dz);

        int airDepth = 0;
        for (int dy = 0; dy >= -5; dy--) {
            BlockPos bp = checkBase.offset(0, dy, 0);
            if (level.getBlockState(bp).getCollisionShape(level, bp).isEmpty()) {
                airDepth++;
            } else {
                break;
            }
        }
        return airDepth >= 4 ? 1.0f : (airDepth >= 2 ? 0.45f : 0.0f);
    }
}
