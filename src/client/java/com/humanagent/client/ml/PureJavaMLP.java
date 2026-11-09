package com.humanagent.client.ml;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.humanagent.HumanAgentMod;
import com.humanagent.config.AgentConfig;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Small Java Multi-Layer Perceptron inference engine.
 * Performance has not been benchmarked on the target laptop.
 */
public final class PureJavaMLP {
    public static final class NetworkWeights {
        public final int inDim;
        public final int h1Dim;
        public final int h2Dim;
        public final int outDim;
        public final boolean useSigmoidOutput;
        public final float[] w1;
        public final float[] b1;
        public final float[] w2;
        public final float[] b2;
        public final float[] w3;
        public final float[] b3;

        // Reuse scratch buffers during each forward pass.
        private final float[] bufH1;
        private final float[] bufH2;
        private final float[] bufOut;

        public NetworkWeights(int inDim, int h1Dim, int h2Dim, int outDim, boolean useSigmoidOutput,
                              float[] w1, float[] b1, float[] w2, float[] b2, float[] w3, float[] b3) {
            this.inDim = inDim;
            this.h1Dim = h1Dim;
            this.h2Dim = h2Dim;
            this.outDim = outDim;
            this.useSigmoidOutput = useSigmoidOutput;
            this.w1 = w1;
            this.b1 = b1;
            this.w2 = w2;
            this.b2 = b2;
            this.w3 = w3;
            this.b3 = b3;
            this.bufH1 = new float[h1Dim];
            this.bufH2 = new float[h2Dim];
            this.bufOut = new float[outDim];
        }

        public synchronized float[] forward(float[] input) {
            // Layer 1: Dense + LeakyReLU(0.01)
            for (int j = 0; j < h1Dim; j++) {
                float sum = b1[j];
                for (int i = 0; i < inDim; i++) {
                    sum += input[i] * w1[i * h1Dim + j];
                }
                bufH1[j] = sum > 0.0f ? sum : 0.01f * sum;
            }
            // Layer 2: Dense + LeakyReLU(0.01)
            for (int j = 0; j < h2Dim; j++) {
                float sum = b2[j];
                for (int i = 0; i < h1Dim; i++) {
                    sum += bufH1[i] * w2[i * h2Dim + j];
                }
                bufH2[j] = sum > 0.0f ? sum : 0.01f * sum;
            }
            // Layer 3: Output Layer (Tanh for continuous angles, Sigmoid for key probabilities)
            for (int j = 0; j < outDim; j++) {
                float sum = b3[j];
                for (int i = 0; i < h2Dim; i++) {
                    sum += bufH2[i] * w3[i * outDim + j];
                }
                if (useSigmoidOutput) {
                    float clipped = Math.max(-15.0f, Math.min(15.0f, sum));
                    bufOut[j] = (float) (1.0 / (1.0 + Math.exp(-clipped)));
                } else {
                    bufOut[j] = (float) Math.tanh(sum);
                }
            }
            return bufOut;
        }
    }

    private NetworkWeights aimNet;
    private NetworkWeights strafeNet;
    private final float[] aimInputBuf = new float[6];
    private final float[] strafeInputBuf = new float[8];

    public PureJavaMLP() {
        reloadWeights();
    }

    public void reloadWeights() {
        Path customPath = AgentConfig.getWeightsCustomPath();
        if (Files.exists(customPath)) {
            try (Reader reader = Files.newBufferedReader(customPath, StandardCharsets.UTF_8)) {
                parseWeightsJson(reader, "custom config/human_weights.json");
                return;
            } catch (Exception e) {
                HumanAgentMod.LOGGER.warn("Failed to load custom weights at {}, falling back to embedded weights", customPath, e);
            }
        }
        try (InputStream is = PureJavaMLP.class.getResourceAsStream("/assets/humanagent/human_weights.json")) {
            if (is != null) {
                try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                    parseWeightsJson(reader, "embedded assets/humanagent/human_weights.json");
                    return;
                }
            }
        } catch (Exception e) {
            HumanAgentMod.LOGGER.error("Failed to load embedded neural weights", e);
        }
    }

    private void parseWeightsJson(Reader reader, String sourceLabel) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        this.aimNet = parseSingleNet(root.getAsJsonObject("aim_net"));
        this.strafeNet = parseSingleNet(root.getAsJsonObject("strafe_net"));
        HumanAgentMod.LOGGER.info("Loaded PureJavaMLP weights from {} (AimNet: {}->{}->{}->{}, StrafeNet: {}->{}->{}->{})",
                sourceLabel,
                aimNet.inDim, aimNet.h1Dim, aimNet.h2Dim, aimNet.outDim,
                strafeNet.inDim, strafeNet.h1Dim, strafeNet.h2Dim, strafeNet.outDim);
    }

    private NetworkWeights parseSingleNet(JsonObject obj) {
        int inDim = obj.get("in_dim").getAsInt();
        int h1Dim = obj.get("h1_dim").getAsInt();
        int h2Dim = obj.get("h2_dim").getAsInt();
        int outDim = obj.get("out_dim").getAsInt();
        String act = obj.get("out_activation").getAsString();
        boolean sigmoid = "sigmoid".equalsIgnoreCase(act);

        return new NetworkWeights(
                inDim, h1Dim, h2Dim, outDim, sigmoid,
                toFloatArray(obj.getAsJsonArray("W1")),
                toFloatArray(obj.getAsJsonArray("b1")),
                toFloatArray(obj.getAsJsonArray("W2")),
                toFloatArray(obj.getAsJsonArray("b2")),
                toFloatArray(obj.getAsJsonArray("W3")),
                toFloatArray(obj.getAsJsonArray("b3"))
        );
    }

    private static float[] toFloatArray(JsonArray arr) {
        float[] res = new float[arr.size()];
        int idx = 0;
        for (JsonElement el : arr) {
            res[idx++] = el.getAsFloat();
        }
        return res;
    }

    /**
     * Predicts humanized (stepYaw, stepPitch) in degrees for the current tick.
     */
    public float[] predictAimStep(float deltaYaw, float deltaPitch, float angularDist,
                                  float prevStepYaw, float prevStepPitch, float targetAngVel) {
        if (aimNet == null) {
            return new float[]{deltaYaw * 0.28f, deltaPitch * 0.28f};
        }
        aimInputBuf[0] = Math.max(-1.0f, Math.min(1.0f, deltaYaw / 180.0f));
        aimInputBuf[1] = Math.max(-1.0f, Math.min(1.0f, deltaPitch / 90.0f));
        aimInputBuf[2] = Math.max(0.0f, Math.min(1.0f, angularDist / 180.0f));
        aimInputBuf[3] = Math.max(-1.0f, Math.min(1.0f, prevStepYaw / 30.0f));
        aimInputBuf[4] = Math.max(-1.0f, Math.min(1.0f, prevStepPitch / 30.0f));
        aimInputBuf[5] = Math.max(-1.0f, Math.min(1.0f, targetAngVel / 20.0f));

        float[] out = aimNet.forward(aimInputBuf);
        return new float[]{out[0] * 30.0f, out[1] * 30.0f};
    }

    /**
     * Predicts 6 movement probabilities: [W, A, S, D, Jump, Sprint].
     */
    public float[] predictCombatStrafe(float enemyDist, float enemyRelAngleDeg, int targetHurtTime,
                                       int myHurtTime, float strafePhaseSin, float hazardLeft,
                                       float hazardRight, float pitchToTarget) {
        if (strafeNet == null) {
            return new float[]{1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f};
        }
        strafeInputBuf[0] = Math.max(0.0f, Math.min(1.0f, enemyDist / 10.0f));
        strafeInputBuf[1] = Math.max(-1.0f, Math.min(1.0f, enemyRelAngleDeg / 180.0f));
        strafeInputBuf[2] = Math.max(0.0f, Math.min(1.0f, targetHurtTime / 10.0f));
        strafeInputBuf[3] = Math.max(0.0f, Math.min(1.0f, myHurtTime / 10.0f));
        strafeInputBuf[4] = strafePhaseSin;
        strafeInputBuf[5] = Math.max(0.0f, Math.min(1.0f, hazardLeft));
        strafeInputBuf[6] = Math.max(0.0f, Math.min(1.0f, hazardRight));
        strafeInputBuf[7] = Math.max(-1.0f, Math.min(1.0f, pitchToTarget / 90.0f));

        return strafeNet.forward(strafeInputBuf);
    }
}
