package com.humanagent.client.skills;

import com.humanagent.client.aim.HumanAimProcessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Layer 3C Survival Reflex & Clutch Controller:
 * Handles high-priority emergency interrupts:
 * 1. Water Bucket / Hay Bale / Slime Block MLG when falling onto solid ground.
 * 2. Bedwars Side-Wall Block Clutch when knocked off a bridge toward the void.
 */
public final class ClutchAndSurvivalReflex {
    private final HumanAimProcessor aimProcessor;
    private int clutchCooldown = 0;

    public ClutchAndSurvivalReflex(HumanAimProcessor aimProcessor) {
        this.aimProcessor = aimProcessor;
    }

    /**
     * Returns true if an emergency clutch reflex took control of the player this tick.
     */
    public boolean tickReflexes(Minecraft mc) {
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null || mc.gameMode == null) return false;

        if (clutchCooldown > 0) {
            clutchCooldown--;
        }

        if (player.onGround() || player.isInWater() || player.getAbilities().flying) {
            return false;
        }

        // Case 1: Falling steeply -> check for ground impact within 4 ticks (Water Bucket MLG)
        if (player.fallDistance > 3.6f && player.getDeltaMovement().y < -0.35) {
            BlockPos groundHit = findGroundBelow(level, player.blockPosition(), 12);
            if (groundHit != null) {
                double distToGround = player.getY() - (groundHit.getY() + 1.0);
                double ticksToImpact = distToGround / Math.abs(player.getDeltaMovement().y);
                if (ticksToImpact <= 4.2) {
                    if (equipWaterBucketOrFallBlock(player, level.dimension() == Level.NETHER)) {
                        Vec3 aimPt = new Vec3(player.getX(), groundHit.getY() + 1.0, player.getZ());
                        aimProcessor.smoothLookAt(mc, aimPt, 12.0f);
                        if (ticksToImpact <= 2.1 && clutchCooldown == 0) {
                            mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
                            clutchCooldown = 6;
                        }
                        return true;
                    }
                }
            } else {
                // Case 2: Falling over the void -> scan for nearest vertical wall within 4 blocks for a Block Clutch
                return attemptVoidWallBlockClutch(mc, player, level);
            }
        }
        return false;
    }

    private boolean attemptVoidWallBlockClutch(Minecraft mc, LocalPlayer player, Level level) {
        int blockSlot = findSolidBlockInHotbar(player);
        if (blockSlot == -1) return false;

        BlockPos center = player.blockPosition();
        for (int dy = 0; dy >= -2; dy--) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos wallPos = center.offset(dir.getStepX(), dy, dir.getStepZ());
                if (!level.getBlockState(wallPos).getCollisionShape(level, wallPos).isEmpty()) {
                    Direction placeFace = dir.getOpposite();
                    BlockPos targetAir = wallPos.relative(placeFace);
                    if (level.getBlockState(targetAir).isAir()) {
                        player.getInventory().setSelectedSlot(blockSlot);
                        Vec3 faceCenter = Vec3.atCenterOf(wallPos).add(
                                placeFace.getStepX() * 0.5, 0.1, placeFace.getStepZ() * 0.5
                        );
                        if (aimProcessor.smoothLookAt(mc, faceCenter, 10.0f) && clutchCooldown == 0 && mc. gameMode != null) {
                            BlockHitResult bhr = new BlockHitResult(faceCenter, placeFace, wallPos, false);
                            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, bhr);
                            player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
                            clutchCooldown = 3;
                        }
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private BlockPos findGroundBelow(Level level, BlockPos start, int maxDepth) {
        for (int i = 1; i <= maxDepth; i++) {
            BlockPos check = start.below(i);
            if (!level.getBlockState(check).getCollisionShape(level, check).isEmpty()) {
                return check;
            }
        }
        return null;
    }

    private boolean equipWaterBucketOrFallBlock(LocalPlayer player, boolean isNether) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!isNether && stack.is(Items.WATER_BUCKET)) {
                player.getInventory().setSelectedSlot(i);
                return true;
            }
            if (stack.is(Items.COBWEB) || stack.is(Items.SLIME_BLOCK) || stack.is(Items.HAY_BLOCK)) {
                player.getInventory().setSelectedSlot(i);
                return true;
            }
        }
        return false;
    }

    private int findSolidBlockInHotbar(LocalPlayer player) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.getItem() instanceof BlockItem && stack.getCount() > 0) {
                return i;
            }
        }
        return -1;
    }
}
