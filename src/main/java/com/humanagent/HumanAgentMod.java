package com.humanagent;

import com.humanagent.config.AgentConfig;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HumanAgentMod implements ModInitializer {
    public static final String MOD_ID = "humanagent";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        AgentConfig.load();
        LOGGER.info("Initialized HumanAgent Autonomous Mod ({})", MOD_ID);
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
