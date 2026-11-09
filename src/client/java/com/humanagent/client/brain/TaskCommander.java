package com.humanagent.client.brain;

import com.humanagent.client.aim.HumanAimProcessor;
import com.humanagent.client.pathing.CurvePathNavigator;
import com.humanagent.client.skills.ClutchAndSurvivalReflex;
import com.humanagent.client.skills.CombatController;
import com.humanagent.client.skills.ProBridgingController;
import com.humanagent.config.AgentConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

/**
 * Layer 1 Autonomous Strategy Brain (Commander):
 * Evaluates real inventory, health, threats, and world state every tick without any fake stubs or pretend goals.
 */
public final class TaskCommander {
    private final HumanAimProcessor aimProcessor;
    private final CurvePathNavigator navigator;
    private final ProBridgingController bridgingController;
    private final CombatController combatController;
    private final ClutchAndSurvivalReflex reflexController;

    private BlockPos homeBedPos = null;
    private BlockPos currentTargetBlock = null;
    private String currentTaskStatus = "Idle";

    public TaskCommander(HumanAimProcessor aimProcessor,
                         CurvePathNavigator navigator,
                         ProBridgingController bridgingController,
                         CombatController combatController,
                         ClutchAndSurvivalReflex reflexController) {
        this.aimProcessor = aimProcessor;
        this.navigator = navigator;
        this.bridgingController = bridgingController;
        this.combatController = combatController;
        this.reflexController = reflexController;
    }

    public String getCurrentTaskStatus() {
        return currentTaskStatus;
    }

    public void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null) return;

        AgentConfig cfg = AgentConfig.get();
        if (cfg.mode == AgentConfig.OperatingMode.IDLE) {
            currentTaskStatus = "Idle";
            return;
        }

        // Priority 0: Emergency Survival Reflexes (Water MLG / Void Wall Block Clutch)
        if (cfg.enableWaterAndBlockClutch && reflexController.tickReflexes(mc)) {
            currentTaskStatus = "Executing Emergency Clutch Reflex";
            return;
        }

        // Priority 1: Dedicated Bridging Mode
        if (cfg.mode == AgentConfig.OperatingMode.BRIDGE_ONLY) {
            Direction dir = player.getDirection();
            currentTaskStatus = "Pro Bridging (" + cfg.bridgeStyle.name() + " -> " + dir.getName() + ")";
            bridgingController.tickBridge(mc, dir);
            return;
        }

        // Priority 2: Self-Defense & Active PvP Engagement
        double combatScanRange = (cfg.mode == AgentConfig.OperatingMode.PVP_ONLY) ? 14.0 : 6.5;
        LivingEntity threat = combatController.findNearestThreat(mc, combatScanRange);
        if (threat != null) {
            navigator.stop(mc);
            currentTaskStatus = "Engaging Threat: " + threat.getName().getString();
            combatController.tickCombat(mc, threat);
            return;
        }
        if (cfg.mode == AgentConfig.OperatingMode.PVP_ONLY) {
            currentTaskStatus = "Scanning for PvP Targets";
            CurvePathNavigator.releaseMovementKeys(mc.options);
            return;
        }

        // Priority 3: Autonomous Bedwars Loop
        if (cfg.mode == AgentConfig.OperatingMode.BEDWARS_AUTONOMOUS) {
            tickBedwarsAutonomy(mc, player, level);
            return;
        }

        // Priority 4: Autonomous Survival Progression Loop
        if (cfg.mode == AgentConfig.OperatingMode.SURVIVAL_AUTONOMOUS) {
            tickSurvivalProgression(mc, player, level);
        }
    }

    private void tickBedwarsAutonomy(Minecraft mc, LocalPlayer player, Level level) {
        // 1. Record our own spawn bed if nearby and unset
        if (homeBedPos == null) {
            homeBedPos = findNearestBlock(level, player.blockPosition(), 18, state -> state.getBlock() instanceof BedBlock);
        }

        // 2. Check for nearby generator drops (iron, gold, diamond, emerald)
        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(16.0), e -> {
            ItemStack st = e.getItem();
            return st.is(Items.IRON_INGOT) || st.is(Items.GOLD_INGOT) || st.is(Items.DIAMOND) || st.is(Items.EMERALD);
        });
        if (!drops.isEmpty()) {
            ItemEntity nearestDrop = drops.stream().min(Comparator.comparingDouble(player::distanceToSqr)).get();
            currentTaskStatus = "Collecting Generator Resource: " + nearestDrop.getItem().getHoverName().getString();
            if (!navigator.hasActivePath() || navigator.getActiveGoal().distManhattan(nearestDrop.blockPosition()) > 2) {
                navigator.setGoal(mc, nearestDrop.blockPosition());
            }
            navigator.tick(mc);
            return;
        }

        // 3. Scan for enemy beds within 45 blocks to break
        BlockPos enemyBed = findNearestBlock(level, player.blockPosition(), 45, state ->
                state.getBlock() instanceof BedBlock
        );
        if (enemyBed != null && (homeBedPos == null || enemyBed.distManhattan(homeBedPos) > 12)) {
            double distSq = player.getEyePosition().distanceToSqr(Vec3.atCenterOf(enemyBed));
            if (distSq <= 18.0) {
                navigator.stop(mc);
                currentTaskStatus = "Mining Enemy Bed at " + enemyBed.toShortString();
                mineTargetBlockHumanized(mc, player, enemyBed);
            } else {
                currentTaskStatus = "Rushing Enemy Bed at " + enemyBed.toShortString();
                if (!navigator.hasActivePath()) {
                    navigator.setGoal(mc, enemyBed);
                }
                navigator.tick(mc);
            }
            return;
        }

        currentTaskStatus = "Bedwars Patrol / Holding Island";
        navigator.tick(mc);
    }

    private void tickSurvivalProgression(Minecraft mc, LocalPlayer player, Level level) {
        int logsAndPlanks = countItems(player, stack -> stack.is(ItemTags.LOGS) || stack.is(ItemTags.PLANKS));
        int cobblestone = countItems(player, stack -> stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE));
        int ironOreAndIngots = countItems(player, stack -> stack.is(Items.RAW_IRON) || stack.is(Items.IRON_INGOT));

        // Real survival dependency checks based on actual player inventory
        if (logsAndPlanks < 16) {
            currentTaskStatus = "Survival Stage 1: Gathering Wood Logs (" + logsAndPlanks + "/16)";
            gatherNearestBlock(mc, player, level, 28, state -> state.is(BlockTags.LOGS));
        } else if (cobblestone < 24) {
            currentTaskStatus = "Survival Stage 2: Mining Stone (" + cobblestone + "/24)";
            gatherNearestBlock(mc, player, level, 24, state -> state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE));
        } else if (ironOreAndIngots < 12) {
            currentTaskStatus = "Survival Stage 3: Mining Iron Ore (" + ironOreAndIngots + "/12)";
            gatherNearestBlock(mc, player, level, 32, state -> state.is(BlockTags.IRON_ORES));
        } else {
            currentTaskStatus = "Survival Stage 4: Searching for Diamonds / Nether Prep";
            gatherNearestBlock(mc, player, level, 36, state -> state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE) || state.is(Blocks.OBSIDIAN));
        }
    }

    private void gatherNearestBlock(Minecraft mc, LocalPlayer player, Level level, int radius,
                                    java.util.function.Predicate<BlockState> predicate) {
        if (currentTargetBlock == null || !predicate.test(level.getBlockState(currentTargetBlock))) {
            currentTargetBlock = findNearestBlock(level, player.blockPosition(), radius, predicate);
        }
        if (currentTargetBlock == null) {
            currentTaskStatus += " (Exploring)";
            return;
        }

        double distSq = player.getEyePosition().distanceToSqr(Vec3.atCenterOf(currentTargetBlock));
        if (distSq <= 16.0) {
            navigator.stop(mc);
            mineTargetBlockHumanized(mc, player, currentTargetBlock);
        } else {
            if (!navigator.hasActivePath() || navigator.getActiveGoal().distManhattan(currentTargetBlock) > 2) {
                navigator.setGoal(mc, currentTargetBlock);
            }
            navigator.tick(mc);
        }
    }

    private void mineTargetBlockHumanized(Minecraft mc, LocalPlayer player, BlockPos targetPos) {
        if (mc.level == null || mc.gameMode == null) return;
        equipBestToolForBlock(player, mc.level.getBlockState(targetPos));
        Vec3 aimPt = aimProcessor.getSafeHumanBlockAimPoint(targetPos);
        boolean onTarget = aimProcessor.smoothLookAt(mc, aimPt, 6.0f);

        if (onTarget && mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) {
            BlockHitResult bhr = (BlockHitResult) mc.hitResult;
            mc.gameMode.continueDestroyBlock(bhr.getBlockPos(), bhr.getDirection());
            player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
            mc.options.keyAttack.setDown(true);
        } else {
            mc.options.keyAttack.setDown(false);
        }
    }

    private void equipBestToolForBlock(LocalPlayer player, BlockState state) {
        int bestSlot = player.getInventory().getSelectedSlot();
        float bestSpeed = player.getInventory().getItem(bestSlot).getDestroySpeed(state);
        for (int i = 0; i < 9; i++) {
            float speed = player.getInventory().getItem(i).getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }
        player.getInventory().setSelectedSlot(bestSlot);
    }

    private int countItems(LocalPlayer player, java.util.function.Predicate<ItemStack> matcher) {
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack st = player.getInventory().getItem(i);
            if (!st.isEmpty() && matcher.test(st)) {
                total += st.getCount();
            }
        }
        return total;
    }

    private BlockPos findNearestBlock(Level level, BlockPos origin, int radius,
                                      java.util.function.Predicate<BlockState> matcher) {
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius / 2; dy <= radius / 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (matcher.test(level.getBlockState(cursor))) {
                        double dSq = origin.distSqr(cursor);
                        if (dSq < bestDistSq) {
                            bestDistSq = dSq;
                            best = cursor.immutable();
                        }
                    }
                }
            }
        }
        return best;
    }
}
