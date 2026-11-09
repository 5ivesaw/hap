# HAHA — Human-Agent Humanisation and Autonomy

An experimental Minecraft Java 26.3 Fabric client mod exploring lightweight navigation, aiming, combat, bridging, and gameplay data collection. This repository starts from the supplied `human-agent-26.3.zip` and is a development starting point, not a finished autonomous player.

## Current scope

- Java implementations of a bounded path search, camera controller, bridging and combat controllers, simple task selection, and a CSV gameplay recorder.
- A small Java neural network inference class with bundled weights.
- A Colab compatible PyTorch training script and a GitHub Actions build workflow.

These components have not been validated in live gameplay. The project does not yet demonstrate a complete survival run, Bedwars match, reliable clutch, safe bridging, or anti-cheat compatibility. The bundled weights' training provenance is unverified; do not treat them as proof of human gameplay training. The Colab script creates synthetic examples by default and can add recorded aim samples from its CSV input. It does not currently train the strafe model on recorded gameplay.

## Build

GitHub Actions runs the build on each push. The workflow artifact is the compiled JAR if the build succeeds. To build elsewhere, use JDK 25 and Gradle 9.6.0:

```sh
gradle build --no-daemon
```

Runtime behavior still needs testing in Minecraft Java 26.3 with Fabric Loader and Fabric API. Use a test world before relying on any automated action.

## Controls in the source

- `G`: toggle survival mode
- `V`: toggle bridge mode
- `R`: toggle CSV recording at `.minecraft/config/recorded_gameplay.csv`
- `/agent mode <IDLE | SURVIVAL_AUTONOMOUS | BEDWARS_AUTONOMOUS | PVP_ONLY | BRIDGE_ONLY>`
- `/agent bridge <SPEEDBRIDGE_EAGLE | MOONWALK | SAFE_SNEAK>`
- `/agent goto <x> <y> <z>`
- `/agent reloadweights`
- `/agent stop`

These are implemented entry points, not a claim that each mode achieves its name reliably.

## Colab

`colab/train_human_agent_colab.py` can be run in a Colab code cell. Upload a recording named `recorded_gameplay.csv` before running it if you want the aim dataset to include your data. The script exports `human_weights.json`. Its default synthetic dataset is useful for checking the training pipeline, not for establishing human-like behavior. GPU time is optional for this small model.

## Development priorities

1. Get a clean 26.3 build and test that the mod loads.
2. Test each controller in isolated worlds and record failures and performance.
3. Add safety checks and goal completion tests before claiming autonomy.
4. Collect consented gameplay data, document provenance, and evaluate learned models against held-out recordings.

License: MIT. Minecraft and Fabric are separate projects with their own terms.
