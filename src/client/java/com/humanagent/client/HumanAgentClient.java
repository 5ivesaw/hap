package com.humanagent.client;

import com.humanagent.HumanAgentMod;
import com.humanagent.client.aim.HumanAimProcessor;
import com.humanagent.client.brain.TaskCommander;
import com.humanagent.client.ml.PureJavaMLP;
import com.humanagent.client.pathing.CurvePathNavigator;
import com.humanagent.client.recorder.GameplayDataLogger;
import com.humanagent.client.skills.ClutchAndSurvivalReflex;
import com.humanagent.client.skills.CombatController;
import com.humanagent.client.skills.ProBridgingController;
import com.humanagent.config.AgentConfig;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;

/**
 * Minecraft 26.3 Client Entrypoint (Uses 26.3 SDL InputConstants.Type.KEYBOARD & KeyMapping.Category).
 * Registers keybinds, /agent commands, and the 20Hz autonomous brain-to-hand tick loop.
 */
public class HumanAgentClient implements ClientModInitializer {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(HumanAgentMod.id("main"));

    private static KeyMapping toggleAgentKey;
    private static KeyMapping toggleBridgeKey;
    private static KeyMapping toggleRecordKey;

    private PureJavaMLP mlp;
    private HumanAimProcessor aimProcessor;
    private CurvePathNavigator navigator;
    private ProBridgingController bridgingController;
    private CombatController combatController;
    private ClutchAndSurvivalReflex reflexController;
    private TaskCommander commander;
    private GameplayDataLogger dataLogger;

    @Override
    public void onInitializeClient() {
        this.mlp = new PureJavaMLP();
        this.aimProcessor = new HumanAimProcessor(mlp);
        this.navigator = new CurvePathNavigator(aimProcessor);
        this.bridgingController = new ProBridgingController(aimProcessor);
        this.combatController = new CombatController(aimProcessor, mlp);
        this.reflexController = new ClutchAndSurvivalReflex(aimProcessor);
        this.commander = new TaskCommander(aimProcessor, navigator, bridgingController, combatController, reflexController);
        this.dataLogger = new GameplayDataLogger();

        toggleAgentKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.humanagent.toggle_agent",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_G,
                CATEGORY
        ));

        toggleBridgeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.humanagent.toggle_bridge",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_V,
                CATEGORY
        ));

        toggleRecordKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.humanagent.toggle_record",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_R,
                CATEGORY
        ));

        registerCommands();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.level == null) return;

            while (toggleAgentKey.consumeClick()) {
                AgentConfig cfg = AgentConfig.get();
                if (cfg.mode == AgentConfig.OperatingMode.IDLE) {
                    cfg.mode = AgentConfig.OperatingMode.SURVIVAL_AUTONOMOUS;
                } else {
                    cfg.mode = AgentConfig.OperatingMode.IDLE;
                    navigator.stop(client);
                }
                AgentConfig.save();
                sendStatus(client, "Mode set to: " + cfg.mode.name());
            }

            while (toggleBridgeKey.consumeClick()) {
                AgentConfig cfg = AgentConfig.get();
                if (cfg.mode == AgentConfig.OperatingMode.BRIDGE_ONLY) {
                    cfg.mode = AgentConfig.OperatingMode.IDLE;
                    CurvePathNavigator.releaseMovementKeys(client.options);
                } else {
                    cfg.mode = AgentConfig.OperatingMode.BRIDGE_ONLY;
                }
                AgentConfig.save();
                sendStatus(client, "Bridging Mode (" + cfg.bridgeStyle.name() + "): " + (cfg.mode == AgentConfig.OperatingMode.BRIDGE_ONLY ? "ON" : "OFF"));
            }

            while (toggleRecordKey.consumeClick()) {
                dataLogger.toggleRecording(client);
                sendStatus(client, "Colab CSV Recording: " + (dataLogger.isRecording() ? "ACTIVE (" + AgentConfig.getRecordingCsvPath().getFileName() + ")" : "STOPPED (Saved " + dataLogger.getLoggedRows() + " ticks)"));
            }

            if (dataLogger.isRecording()) {
                LivingEntity nearest = combatController.findNearestThreat(client, 12.0);
                dataLogger.tickRecord(client, nearest);
            }

            commander.tick(client);
        });
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("agent")
                    .then(ClientCommands.literal("mode")
                            .then(ClientCommands.argument("modeName", StringArgumentType.word())
                                    .executes(ctx -> {
                                        String m = StringArgumentType.getString(ctx, "modeName").toUpperCase();
                                        try {
                                            AgentConfig.get().mode = AgentConfig.OperatingMode.valueOf(m);
                                            AgentConfig.save();
                                            sendStatus(Minecraft.getInstance(), "Switched mode to " + m);
                                        } catch (IllegalArgumentException e) {
                                            sendStatus(Minecraft.getInstance(), "Valid modes: IDLE, SURVIVAL_AUTONOMOUS, BEDWARS_AUTONOMOUS, PVP_ONLY, BRIDGE_ONLY");
                                        }
                                        return 1;
                                    })))
                    .then(ClientCommands.literal("bridge")
                            .then(ClientCommands.argument("style", StringArgumentType.word())
                                    .executes(ctx -> {
                                        String s = StringArgumentType.getString(ctx, "style").toUpperCase();
                                        try {
                                            AgentConfig.get().bridgeStyle = AgentConfig.BridgeStyle.valueOf(s);
                                            AgentConfig.save();
                                            sendStatus(Minecraft.getInstance(), "Bridge style set to " + s);
                                        } catch (IllegalArgumentException e) {
                                            sendStatus(Minecraft.getInstance(), "Valid styles: SPEEDBRIDGE_EAGLE, MOONWALK, SAFE_SNEAK");
                                        }
                                        return 1;
                                    })))
                    .then(ClientCommands.literal("goto")
                            .then(ClientCommands.argument("x", IntegerArgumentType.integer())
                                    .then(ClientCommands.argument("y", IntegerArgumentType.integer())
                                            .then(ClientCommands.argument("z", IntegerArgumentType.integer())
                                                    .executes(ctx -> {
                                                        int x = IntegerArgumentType.getInteger(ctx, "x");
                                                        int y = IntegerArgumentType.getInteger(ctx, "y");
                                                        int z = IntegerArgumentType.getInteger(ctx, "z");
                                                        Minecraft mc = Minecraft.getInstance();
                                                        navigator.setGoal(mc, new BlockPos(x, y, z));
                                                        sendStatus(mc, "Curved spline pathfinding to (" + x + ", " + y + ", " + z + ")");
                                                        return 1;
                                                    })))))
                    .then(ClientCommands.literal("reloadweights")
                            .executes(ctx -> {
                                mlp.reloadWeights();
                                sendStatus(Minecraft.getInstance(), "Reloaded neural weights from human_weights.json");
                                return 1;
                            }))
                    .then(ClientCommands.literal("stop")
                            .executes(ctx -> {
                                AgentConfig.get().mode = AgentConfig.OperatingMode.IDLE;
                                navigator.stop(Minecraft.getInstance());
                                sendStatus(Minecraft.getInstance(), "Stopped all autonomous tasks.");
                                return 1;
                            }))
            );
        });
    }

    private static void sendStatus(Minecraft mc, String msg) {
        if (mc.player != null) {
            mc.gui.setOverlayMessage(Component.literal("[HumanAgent] " + msg), false);
        }
    }
}
