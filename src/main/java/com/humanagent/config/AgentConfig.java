package com.humanagent.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.humanagent.HumanAgentMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class AgentConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve(HumanAgentMod.MOD_ID + ".json");
    private static AgentConfig instance = new AgentConfig();

    public enum OperatingMode {
        IDLE,
        SURVIVAL_AUTONOMOUS,
        BEDWARS_AUTONOMOUS,
        PVP_ONLY,
        BRIDGE_ONLY
    }

    public enum BridgeStyle {
        SPEEDBRIDGE_EAGLE,
        MOONWALK,
        SAFE_SNEAK
    }

    public int configVersion = 1;
    public OperatingMode mode = OperatingMode.IDLE;
    public BridgeStyle bridgeStyle = BridgeStyle.SPEEDBRIDGE_EAGLE;

    // Combat settings
    public boolean forceLegacy18Pvp = false;
    public boolean autoDetectViaFabricPlus = true;
    public double baseCps18 = 13.6;
    public double cpsStdDev18 = 1.7;
    public boolean enableWTap = true;
    public boolean enableCircleStrafe = true;

    // Humanization & Aim settings
    public double windGravity = 8.5;
    public double windNoiseStrength = 2.4;
    public double neuralBlendWeight = 0.65;
    public boolean quantizeToMouseSensitivityGcd = true;
    public boolean enableIdleHeadGlances = true;

    // Bridging & Reflex safety settings
    public boolean enableEdgeGuardFailsafe = true;
    public boolean enableWaterAndBlockClutch = true;
    public double minHealthForAutoHeal = 9.0;

    public static AgentConfig get() {
        return instance;
    }

    public static Path getWeightsCustomPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("human_weights.json");
    }

    public static Path getRecordingCsvPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("recorded_gameplay.csv");
    }

    public static void load() {
        if (Files.notExists(PATH)) {
            save();
            return;
        }
        try (Reader reader = Files.newBufferedReader(PATH)) {
            AgentConfig loaded = GSON.fromJson(reader, AgentConfig.class);
            instance = loaded != null ? loaded : new AgentConfig();
        } catch (IOException | JsonParseException e) {
            HumanAgentMod.LOGGER.error("Invalid config at {}, keeping defaults", PATH, e);
            instance = new AgentConfig();
        }
    }

    public static void save() {
        try {
            Files.createDirectories(PATH.getParent());
            Path tmp = PATH.resolveSibling(PATH.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp)) {
                GSON.toJson(instance, writer);
            }
            try {
                Files.move(tmp, PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, PATH, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            HumanAgentMod.LOGGER.error("Failed to save config {}", PATH, e);
        }
    }
}
