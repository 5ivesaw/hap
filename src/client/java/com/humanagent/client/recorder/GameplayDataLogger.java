package com.humanagent.client.recorder;

import com.humanagent.HumanAgentMod;
import com.humanagent.config.AgentConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * 20Hz Gameplay Telemetry Recorder (like JellyLabScripts' NeuralTrainer).
 * Logs your real mouse deltas and key presses to .minecraft/config/recorded_gameplay.csv
 * so you can upload the file to Google Colab and fine-tune AimNet & StrafeNet on your personal playstyle.
 */
public final class GameplayDataLogger {
    private boolean recording = false;
    private BufferedWriter writer = null;
    private float prevYaw = 0.0f;
    private float prevPitch = 0.0f;
    private float prevStepYaw = 0.0f;
    private float prevStepPitch = 0.0f;
    private int loggedRows = 0;

    public boolean isRecording() {
        return recording;
    }

    public int getLoggedRows() {
        return loggedRows;
    }

    public void toggleRecording(Minecraft mc) {
        if (recording) {
            stopRecording();
        } else {
            startRecording(mc);
        }
    }

    private void startRecording(Minecraft mc) {
        Path csvPath = AgentConfig.getRecordingCsvPath();
        try {
            Files.createDirectories(csvPath.getParent());
            boolean writeHeader = Files.notExists(csvPath) || Files.size(csvPath) == 0;
            writer = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (writeHeader) {
                writer.write("delta_yaw,delta_pitch,angular_dist,prev_step_yaw,prev_step_pitch,target_ang_vel,actual_step_yaw,actual_step_pitch,enemy_dist,enemy_rel_angle,target_hurt,my_hurt,key_w,key_a,key_s,key_d,key_jump,key_sprint
");
            }
            if (mc.player != null) {
                prevYaw = mc.player.getYRot();
                prevPitch = mc.player.getXRot();
            }
            recording = true;
            HumanAgentMod.LOGGER.info("Started recording human gameplay to {}", csvPath);
        } catch (IOException e) {
            HumanAgentMod.LOGGER.error("Failed to open gameplay CSV logger", e);
        }
    }

    public void stopRecording() {
        recording = false;
        if (writer != null) {
            try {
                writer.flush();
                writer.close();
            } catch (IOException ignored) {
            }
            writer = null;
        }
    }

    public void tickRecord(Minecraft mc, Entity nearestTarget) {
        if (!recording || writer == null || mc.player == null) return;
        LocalPlayer player = mc.player;

        float currentYaw = player.getYRot();
        float currentPitch = player.getXRot();
        float actualStepYaw = Mth.wrapDegrees(currentYaw - prevYaw);
        float actualStepPitch = currentPitch - prevPitch;

        float deltaYaw = 0.0f;
        float deltaPitch = 0.0f;
        float enemyDist = 10.0f;
        float enemyRelAngle = 0.0f;
        int targetHurt = 0;

        if (nearestTarget != null) {
            Vec3 eye = player.getEyePosition();
            Vec3 targetCenter = nearestTarget.getBoundingBox().getCenter();
            double dx = targetCenter.x - eye.x;
            double dy = targetCenter.y - eye.y;
            double dz = targetCenter.z - eye.z;
            float idealYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
            float idealPitch = (float) (-(Mth.atan2(dy, Math.hypot(dx, dz)) * (180.0 / Math.PI)));
            deltaYaw = Mth.wrapDegrees(idealYaw - prevYaw);
            deltaPitch = idealPitch - prevPitch;
            enemyDist = player.distanceTo(nearestTarget);
            if (nearestTarget instanceof net.minecraft.world.entity.LivingEntity le) {
                targetHurt = le.hurtTime;
            }
        } else if (mc.hitResult != null && mc.hitResult.getType() != HitResult.Type.MISS) {
            Vec3 hit = mc.hitResult.getLocation();
            Vec3 eye = player.getEyePosition();
            double dx = hit.x - eye.x;
            double dy = hit.y - eye.y;
            double dz = hit.z - eye.z;
            float idealYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
            float idealPitch = (float) (-(Mth.atan2(dy, Math.hypot(dx, dz)) * (180.0 / Math.PI)));
            deltaYaw = Mth.wrapDegrees(idealYaw - prevYaw);
            deltaPitch = idealPitch - prevPitch;
        }

        float angularDist = (float) Math.hypot(deltaYaw, deltaPitch);
        try {
            String row = String.format(Locale.US,
                    "%.4f,%.4f,%.4f,%.4f,%.4f,0.0,%.4f,%.4f,%.4f,%.4f,%d,%d,%d,%d,%d,%d,%d,%d%n",
                    deltaYaw, deltaPitch, angularDist, prevStepYaw, prevStepPitch,
                    actualStepYaw, actualStepPitch, enemyDist, enemyRelAngle, targetHurt, player.hurtTime,
                    mc.options.keyUp.isDown() ? 1 : 0,
                    mc.options.keyLeft.isDown() ? 1 : 0,
                    mc.options.keyDown.isDown() ? 1 : 0,
                    mc.options.keyRight.isDown() ? 1 : 0,
                    mc.options.keyJump.isDown() ? 1 : 0,
                    player.isSprinting() ? 1 : 0
            );
            writer.write(row);
            loggedRows++;
            if (loggedRows % 100 == 0) {
                writer.flush();
            }
        } catch (IOException e) {
            stopRecording();
        }

        prevYaw = currentYaw;
        prevPitch = currentPitch;
        prevStepYaw = actualStepYaw;
        prevStepPitch = actualStepPitch;
    }
}
