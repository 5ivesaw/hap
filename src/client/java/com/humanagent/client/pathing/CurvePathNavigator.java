package com.humanagent.client.pathing;

import com.humanagent.HumanAgentMod;
import com.humanagent.client.aim.HumanAimProcessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Layer 2 Navigator:
 * 1. Computes a 3D path using asynchronous A* (or delegates to Baritone API if loaded).
 * 2. Runs a Theta* Any-Angle Line-of-Sight pass to remove unnecessary 45/90-degree staircase nodes.
 * 3. Interpolates remaining waypoints with a Centripetal Catmull-Rom Spline so the bot walks in smooth, human curves.
 * 4. Drives movement exclusively via client KeyMappings (W, A, S, D, Space, Sprint).
 */
public final class CurvePathNavigator {
    private final HumanAimProcessor aimProcessor;
    private List<Vec3> currentSplinePoints = Collections.emptyList();
    private int currentSplineIndex = 0;
    private BlockPos activeGoal = null;
    private int ticksSinceLastRecalc = 0;
    private int stuckTicks = 0;
    private Vec3 lastPlayerPos = Vec3.ZERO;

    public CurvePathNavigator(HumanAimProcessor aimProcessor) {
        this.aimProcessor = aimProcessor;
    }

    public void setGoal(Minecraft mc, BlockPos goal) {
        this.activeGoal = goal;
        recalculatePath(mc);
    }

    public void stop(Minecraft mc) {
        this.activeGoal = null;
        this.currentSplinePoints = Collections.emptyList();
        this.currentSplineIndex = 0;
        releaseMovementKeys(mc.options);
    }

    public boolean hasActivePath() {
        return activeGoal != null && !currentSplinePoints.isEmpty() && currentSplineIndex < currentSplinePoints.size();
    }

    public BlockPos getActiveGoal() {
        return activeGoal;
    }

    public void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null || activeGoal == null) {
            return;
        }

        Vec3 playerPos = player.position();
        if (playerPos.distanceToSqr(Vec3.atBottomCenterOf(activeGoal)) <= 2.25) {
            stop(mc);
            return;
        }

        // Check stuck detection
        if (playerPos.distanceToSqr(lastPlayerPos) < 0.0025) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
        lastPlayerPos = playerPos;

        ticksSinceLastRecalc++;
        if (currentSplinePoints.isEmpty() || stuckTicks > 25 || ticksSinceLastRecalc > 100) {
            stuckTicks = 0;
            recalculatePath(mc);
            if (currentSplinePoints.isEmpty()) {
                releaseMovementKeys(mc.options);
                return;
            }
        }

        // Advance along Catmull-Rom curve points
        while (currentSplineIndex < currentSplinePoints.size() - 1) {
            Vec3 pt = currentSplinePoints.get(currentSplineIndex);
            double horizDistSq = Math.pow(pt.x - playerPos.x, 2) + Math.pow(pt.z - playerPos.z, 2);
            if (horizDistSq < 0.65 && Math.abs(pt.y - playerPos.y) < 1.6) {
                currentSplineIndex++;
            } else {
                break;
            }
        }

        Vec3 targetPt = currentSplinePoints.get(currentSplineIndex);
        // Look slightly ahead on the spline for natural human anticipatory steering
        int lookAheadIdx = Math.min(currentSplineIndex + 3, currentSplinePoints.size() - 1);
        Vec3 lookPt = currentSplinePoints.get(lookAheadIdx).add(0.0, player.getEyeHeight() * 0.85, 0.0);

        float[] glance = aimProcessor.computeNaturalIdleGlanceOffset();
        Vec3 eye = player.getEyePosition();
        double dx = lookPt.x - eye.x;
        double dy = lookPt.y - eye.y;
        double dz = lookPt.z - eye.z;
        float desiredYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f + glance[0];
        float desiredPitch = (float) (-(Mth.atan2(dy, Math.hypot(dx, dz)) * (180.0 / Math.PI))) + glance[1];

        aimProcessor.smoothLookAtAngles(mc, desiredYaw, Mth.clamp(desiredPitch, -35.0f, 40.0f), 8.0f);

        // Compute movement keys relative to current camera yaw (W + A/D strafe blending for smooth curves)
        double moveDx = targetPt.x - playerPos.x;
        double moveDz = targetPt.z - playerPos.z;
        float moveAngleYaw = (float) (Mth.atan2(moveDz, moveDx) * (180.0 / Math.PI)) - 90.0f;
        float angleDiff = Mth.wrapDegrees(moveAngleYaw - player.getYRot());

        Options opts = mc.options;
        opts.keyUp.setDown(Math.abs(angleDiff) < 105.0f);
        opts.keyDown.setDown(Math.abs(angleDiff) > 135.0f);
        opts.keyLeft.setDown(angleDiff < -16.0f && angleDiff > -155.0f);
        opts.keyRight.setDown(angleDiff > 16.0f && angleDiff < 155.0f);
        opts.keySprint.setDown(Math.abs(angleDiff) < 45.0f && player.getFoodData().getFoodLevel() > 6);

        // Jump when ascending a block step or crossing a 1-block gap
        boolean shouldJump = (targetPt.y > playerPos.y + 0.45 && player.onGround())
                || (player.horizontalCollision && player.onGround());
        opts.keyJump.setDown(shouldJump);
    }

    public static void releaseMovementKeys(Options opts) {
        opts.keyUp.setDown(false);
        opts.keyDown.setDown(false);
        opts.keyLeft.setDown(false);
        opts.keyRight.setDown(false);
        opts.keyJump.setDown(false);
        opts.keySprint.setDown(false);
    }

    private void recalculatePath(Minecraft mc) {
        ticksSinceLastRecalc = 0;
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null || activeGoal == null) return;

        // 1. Compute base grid path (via Baritone API if available, or built-in bounded 3D A*)
        List<BlockPos> rawGridPath = tryGetBaritonePath(activeGoal);
        if (rawGridPath == null || rawGridPath.isEmpty()) {
            rawGridPath = computeBoundedAStar(level, player.blockPosition(), activeGoal, 1800);
        }
        if (rawGridPath.isEmpty()) {
            this.currentSplinePoints = Collections.emptyList();
            return;
        }

        // 2. Theta* Any-Angle Line-of-Sight Reduction (eliminates robotic 45-degree zig-zags)
        List<Vec3> anyAngleWaypoints = simplifyWithLineOfSight(level, rawGridPath);

        // 3. Centripetal Catmull-Rom Spline Interpolation (creates smooth human walking arcs)
        this.currentSplinePoints = buildCatmullRomSpline(anyAngleWaypoints, 4);
        this.currentSplineIndex = 0;
    }

    /**
     * If Baritone is loaded alongside HumanAgent, queries Baritone's A* planner for long-range cached terrain paths
     * while keeping Baritone's own robotic movement/packet execution disabled.
     */
    @SuppressWarnings("unchecked")
    private List<BlockPos> tryGetBaritonePath(BlockPos goal) {
        try {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Object provider = apiClass.getMethod("getProvider").invoke(null);
            Object baritone = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
            Object pathingBehavior = baritone.getClass().getMethod("getPathingBehavior").invoke(baritone);
            Optional<?> currentPathOpt = (Optional<?>) pathingBehavior.getClass().getMethod("getPath").invoke(pathingBehavior);
            if (currentPathOpt != null && currentPathOpt.isPresent()) {
                Object pathObj = currentPathOpt.get();
                Method positionsMethod = pathObj.getClass().getMethod("positions");
                List<? extends BlockPos> positions = (List<? extends BlockPos>) positionsMethod.invoke(pathObj);
                if (positions != null && !positions.isEmpty()) {
                    // Cancel Baritone's own movement executor so our curved spline controller drives the keys
                    pathingBehavior.getClass().getMethod("cancelEverything").invoke(pathingBehavior);
                    return new ArrayList<>(positions);
                }
            }
        } catch (Throwable ignored) {
            // Baritone not installed; built-in A* handles navigation seamlessly
        }
        return null;
    }

    /**
     * Built-in lightweight 3D A* pathfinder with octile + vertical cost heuristics.
     */
    private List<BlockPos> computeBoundedAStar(Level level, BlockPos start, BlockPos goal, int maxIterations) {
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.fCost));
        Map<BlockPos, Node> allNodes = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();

        Node startNode = new Node(start, null, 0.0, heuristic(start, goal));
        open.add(startNode);
        allNodes.put(start, startNode);

        Node bestFallback = startNode;
        int iterations = 0;

        int[][] dirs = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
        };

        while (!open.isEmpty() && iterations++ < maxIterations) {
            Node current = open.poll();
            if (current.pos.distManhattan(goal) <= 2) {
                return reconstructPath(current);
            }
            closed.add(current.pos);
            if (current.hCost < bestFallback.hCost) {
                bestFallback = current;
            }

            for (int[] d : dirs) {
                for (int dy = 1; dy >= -2; dy--) {
                    BlockPos next = current.pos.offset(d[0], dy, d[1]);
                    if (closed.contains(next)) continue;
                    if (!isStandable(level, next)) continue;

                    // Prevent diagonal corner clipping through solid walls
                    if (d[0] != 0 && d[1] != 0) {
                        if (!isPassable(level, current.pos.offset(d[0], 0, 0))
                                || !isPassable(level, current.pos.offset(0, 0, d[1]))) {
                            continue;
                        }
                    }

                    double stepCost = (d[0] != 0 && d[1] != 0) ? 1.414 : 1.0;
                    if (dy > 0) stepCost += 0.65; // Jump tick penalty
                    if (dy < 0) stepCost += 0.35 * (-dy);

                    double newG = current.gCost + stepCost;
                    Node existing = allNodes.get(next);
                    if (existing == null || newG < existing.gCost) {
                        Node nextNode = new Node(next, current, newG, heuristic(next, goal));
                        allNodes.put(next, nextNode);
                        open.add(nextNode);
                    }
                    break; // Take highest valid Y at this (dx, dz)
                }
            }
        }
        return reconstructPath(bestFallback);
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        double dx = Math.abs(a.getX() - b.getX());
        double dy = Math.abs(a.getY() - b.getY());
        double dz = Math.abs(a.getZ() - b.getZ());
        return (dx + dz) + (1.414 - 2.0) * Math.min(dx, dz) + dy * 1.2;
    }

    private static List<BlockPos> reconstructPath(Node end) {
        LinkedList<BlockPos> path = new LinkedList<>();
        Node curr = end;
        while (curr != null) {
            path.addFirst(curr.pos);
            curr = curr.parent;
        }
        return path;
    }

    public static boolean isStandable(Level level, BlockPos footPos) {
        BlockPos below = footPos.below();
        BlockState belowState = level.getBlockState(below);
        if (belowState.isAir() || belowState.getCollisionShape(level, below).isEmpty()) {
            return false;
        }
        return isPassable(level, footPos) && isPassable(level, footPos.above());
    }

    public static boolean isPassable(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
    }

    /**
     * Theta* Any-Angle Line-of-Sight Pass: collapses collinear and stair-stepped grid nodes into direct vectors
     * whenever the flat walking corridor between two nodes is unobstructed.
     */
    private List<Vec3> simplifyWithLineOfSight(Level level, List<BlockPos> gridPath) {
        List<Vec3> result = new ArrayList<>();
        if (gridPath.isEmpty()) return result;

        int anchorIdx = 0;
        result.add(Vec3.atBottomCenterOf(gridPath.get(0)));

        while (anchorIdx < gridPath.size() - 1) {
            int furthestVisible = anchorIdx + 1;
            for (int probe = Math.min(anchorIdx + 12, gridPath.size() - 1); probe > anchorIdx + 1; probe--) {
                if (hasWalkableLineOfSight(level, gridPath.get(anchorIdx), gridPath.get(probe))) {
                    furthestVisible = probe;
                    break;
                }
            }
            result.add(Vec3.atBottomCenterOf(gridPath.get(furthestVisible)));
            anchorIdx = furthestVisible;
        }
        return result;
    }

    private boolean hasWalkableLineOfSight(Level level, BlockPos a, BlockPos b) {
        if (a.getY() != b.getY()) return false;
        double dist = Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ());
        int steps = Math.max(2, (int) Math.ceil(dist * 2.5));
        for (int i = 1; i < steps; i++) {
            double t = i / (double) steps;
            int x = Mth.floor(Mth.lerp(t, a.getX() + 0.5, b.getX() + 0.5));
            int z = Mth.floor(Mth.lerp(t, a.getZ() + 0.5, b.getZ() + 0.5));
            BlockPos check = new BlockPos(x, a.getY(), z);
            if (!isStandable(level, check)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Generates smooth Centripetal Catmull-Rom Spline points through the simplified waypoints.
     */
    private List<Vec3> buildCatmullRomSpline(List<Vec3> waypoints, int subdivisionsPerSegment) {
        if (waypoints.size() <= 2) {
            return waypoints;
        }
        List<Vec3> spline = new ArrayList<>();
        for (int i = 0; i < waypoints.size() - 1; i++) {
            Vec3 p0 = waypoints.get(Math.max(0, i - 1));
            Vec3 p1 = waypoints.get(i);
            Vec3 p2 = waypoints.get(i + 1);
            Vec3 p3 = waypoints.get(Math.min(waypoints.size() - 1, i + 2));

            // Do not curve across vertical jumps
            if (Math.abs(p1.y - p2.y) > 0.1) {
                spline.add(p1);
                continue;
            }

            for (int s = 0; s < subdivisionsPerSegment; s++) {
                double t = s / (double) subdivisionsPerSegment;
                double t2 = t * t;
                double t3 = t2 * t;

                double x = 0.5 * ((2.0 * p1.x)
                        + (-p0.x + p2.x) * t
                        + (2.0 * p0.x - 5.0 * p1.x + 4.0 * p2.x - p3.x) * t2
                        + (-p0.x + 3.0 * p1.x - 3.0 * p2.x + p3.x) * t3);
                double z = 0.5 * ((2.0 * p1.z)
                        + (-p0.z + p2.z) * t
                        + (2.0 * p0.z - 5.0 * p1.z + 4.0 * p2.z - p3.z) * t2
                        + (-p0.z + 3.0 * p1.z - 3.0 * p2.z + p3.z) * t3);
                spline.add(new Vec3(x, p1.y, z));
            }
        }
        spline.add(waypoints.get(waypoints.size() - 1));
        return spline;
    }

    private static final class Node {
        final BlockPos pos;
        final Node parent;
        final double gCost;
        final double hCost;
        final double fCost;

        Node(BlockPos pos, Node parent, double gCost, double hCost) {
            this.pos = pos;
            this.parent = parent;
            this.gCost = gCost;
            this.hCost = hCost;
            this.fCost = gCost + hCost;
        }
    }
}
