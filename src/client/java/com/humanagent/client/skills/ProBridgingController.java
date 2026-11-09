package com.humanagent.client.skills;

import com.humanagent.client.aim.HumanAimProcessor;
import com.humanagent.config.AgentConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * Layer 3A Pro Bridging Controller:
 * Supports Speedbridging (Ninja / Eagle) and Moonwalking with the 1-Tick Physics Lookahead "Edge Guard"
 * The edge prediction is experimental and does not guarantee fall prevention.
 */
public final class ProBridgingController {
    private final HumanAimProcessor aimProcessor;
    private final Random random = new Random();

    private int sneakHoldTicks = 0;
    private int rightClickCooldownTicks = 0;
    private int moonwalkStrafeCounter = 0;
    private float angleDriftOffset = 0.0f;
    private int driftTimer = 0;

    public ProBridgingController(HumanAimProcessor aimProcessor) {
        this.aimProcessor = aimProcessor;
    }

    public boolean equipBridgeBlocks(LocalPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof BlockItem && main.getCount() > 0) {
            return true;
        }
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.getItem() instanceof BlockItem && stack.getCount() >= 4) {
                player.getInventory().setSelectedSlot(slot);
                return true;
            }
        }
        return false;
    }

    /**
     * Executes one tick of humanized bridging in the cardinal direction the player is bridging toward.
     */
    public void tickBridge(Minecraft mc, Direction bridgeDirection) {
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null) return;
        if (!equipBridgeBlocks(player)) return;

        AgentConfig cfg = AgentConfig.get();
        Options opts = mc.options;

        // Subtle human angle drift (+/- 1.1 deg) within the safe placement window
        if (--driftTimer <= 0) {
            driftTimer = 14 + random.nextInt(18);
            angleDriftOffset = (random.nextFloat() - 0.5f) * 2.2f;
        }

        // Facing opposite to bridgeDirection so we walk backward/diagonally backward
        float baseFacingYaw = bridgeDirection.getOpposite().toYRot();

        if (cfg.bridgeStyle == AgentConfig.BridgeStyle.SPEEDBRIDGE_EAGLE) {
            // Eagle / Speedbridge: 135-degree diagonal backward (S + D)
            float targetYaw = Mth.wrapDegrees(baseFacingYaw - 45.0f + angleDriftOffset);
            float targetPitch = 78.6f + (angleDriftOffset * 0.35f);
            aimProcessor.smoothLookAtAngles(mc, targetYaw, targetPitch, 2.5f);

            opts.keyUp.setDown(false);
            opts.keyDown.setDown(true);
            opts.keyRight.setDown(true);
            opts.keyLeft.setDown(false);

            boolean nearEdge = isApproachingBlockEdge(player, bridgeDirection, 0.24);
            boolean predictedFallNextTick = cfg.enableEdgeGuardFailsafe && willSlipOffEdgeNextTick(player, level);

            if (nearEdge || predictedFallNextTick) {
                sneakHoldTicks = Math.max(sneakHoldTicks, 2 + (random.nextFloat() < 0.25f ? 1 : 0));
            }
            opts.keyShift.setDown(sneakHoldTicks > 0);
            if (sneakHoldTicks > 0) {
                sneakHoldTicks--;
            }

        } else if (cfg.bridgeStyle == AgentConfig.BridgeStyle.MOONWALK) {
            // Moonwalk: ~161.8-degree backward angle with rhythmic A/D strafe cadence
            float targetYaw = Mth.wrapDegrees(baseFacingYaw - 18.2f + (angleDriftOffset * 0.4f));
            float targetPitch = 79.4f + (angleDriftOffset * 0.2f);
            aimProcessor.smoothLookAtAngles(mc, targetYaw, targetPitch, 1.8f);

            opts.keyUp.setDown(false);
            opts.keyDown.setDown(true);
            moonwalkStrafeCounter = (moonwalkStrafeCounter + 1) % 5;
            opts.keyRight.setDown(moonwalkStrafeCounter < 3);
            opts.keyLeft.setDown(false);

            // 1-Tick Physics "Edge Guard" safety net: only sneaks if lag or drift would cause a fall
            boolean emergencySneak = cfg.enableEdgeGuardFailsafe && willSlipOffEdgeNextTick(player, level);
            opts.keyShift.setDown(emergencySneak);

        } else {
            // Safe Sneak Bridge
            float targetYaw = Mth.wrapDegrees(baseFacingYaw + angleDriftOffset);
            float targetPitch = 80.5f;
            aimProcessor.smoothLookAtAngles(mc, targetYaw, targetPitch, 3.0f);
            opts.keyDown.setDown(true);
            opts.keyShift.setDown(true);
        }

        // Trigger humanized right-click placement when crosshair hits the side face of the support block
        if (rightClickCooldownTicks > 0) {
            rightClickCooldownTicks--;
        }
        attemptHumanizedBlockPlace(mc, bridgeDirection);
    }

    private void attemptHumanizedBlockPlace(Minecraft mc, Direction bridgeDirection) {
        if (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.BLOCK) return;
        BlockHitResult bhr = (BlockHitResult) mc.hitResult;
        if (bhr.getDirection() == Direction.UP || bhr.getDirection() == Direction.DOWN) return;

        if (rightClickCooldownTicks <= 0 && mc.player != null && mc.gameMode != null) {
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, bhr);
            mc.player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
            // Gaussian right-click interval (~13-15 CPS during placement window)
            rightClickCooldownTicks = aimProcessor.sampleGaussianClickDelayTicks(14.0, 1.5);
        }
    }

    /**
     * Checks how far the player's feet have overhanging past the current supporting block along bridgeDirection.
     */
    private boolean isApproachingBlockEdge(LocalPlayer player, Direction dir, double thresholdFromEdge) {
        double fracX = player.getX() - Math.floor(player.getX());
        double fracZ = player.getZ() - Math.floor(player.getZ());
        return switch (dir) {
            case SOUTH -> fracZ > (1.0 - thresholdFromEdge);
            case NORTH -> fracZ < thresholdFromEdge;
            case EAST -> fracX > (1.0 - thresholdFromEdge);
            case WEST -> fracX < thresholdFromEdge;
            default -> false;
        };
    }

    /**
     * 1-Tick Physics Lookahead ("Edge Guard"):
     * Simulates the player's horizontal position on the next game tick (x + vx, z + vz) and checks
     * whether the player's 0.6-wide bounding box would have zero solid support underneath.
     */
    public boolean willSlipOffEdgeNextTick(LocalPlayer player, Level level) {
        if (!player.onGround()) return false;
        Vec3 vel = player.getDeltaMovement();
        double nextX = player.getX() + vel.x * 1.15;
        double nextZ = player.getZ() + vel.z * 1.15;
        int supportY = Mth.floor(player.getY() - 0.05);

        double halfWidth = 0.29;
        double[][] corners = {
                {nextX - halfWidth, nextZ - halfWidth},
                {nextX + halfWidth, nextZ - halfWidth},
                {nextX - halfWidth, nextZ + halfWidth},
                {nextX + halfWidth, nextZ + halfWidth}
        };

        for (double[] c : corners) {
            BlockPos bp = new BlockPos(Mth.floor(c[0]), supportY, Mth.floor(c[1]));
            if (!level.getBlockState(bp).getCollisionShape(level, bp).isEmpty()) {
                return false; // At least one corner of the player's hitbox is still supported next tick
            }
        }
        return true; // All 4 corners would be over air next tick -> trigger Edge Guard sneak!
    }
}
