# ==============================================================================
# GOOGLE COLAB GPU TRAINER: HumanAgent AimNet & StrafeNet (PyTorch -> PureJavaMLP)
# ==============================================================================
# How to use in Google Colab:
# 1. Open https://colab.research.google.com/ and create a New Notebook.
# 2. Change Runtime -> Change runtime type -> T4 GPU (uses almost 0 compute units!).
# 3. Optional: Upload your `.minecraft/config/recorded_gameplay.csv` to the Colab Files sidebar.
# 4. Paste this entire script into a cell and click Run!
# 5. It will train both neural networks on the GPU and download `human_weights.json`.
#    Drop `human_weights.json` into `.minecraft/config/human_weights.json` and run `/agent reloadweights`.
# ==============================================================================

import json
import os
import numpy as np
import torch
import torch.nn as nn
import torch.optim as optim
from torch.utils.data import TensorDataset, DataLoader

device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
print(f"Using training device: {device}")

class PureJavaCompatibleMLP(nn.Module):
    def __init__(self, in_dim, h1_dim, h2_dim, out_dim, out_activation="tanh"):
        super().__init__()
        self.in_dim = in_dim
        self.h1_dim = h1_dim
        self.h2_dim = h2_dim
        self.out_dim = out_dim
        self.out_activation = out_activation

        self.fc1 = nn.Linear(in_dim, h1_dim)
        self.act1 = nn.LeakyReLU(0.01)
        self.fc2 = nn.Linear(h1_dim, h2_dim)
        self.act2 = nn.LeakyReLU(0.01)
        self.fc3 = nn.Linear(h2_dim, out_dim)
        self.out_fn = nn.Tanh() if out_activation == "tanh" else nn.Sigmoid()

    def forward(self, x):
        x = self.act1(self.fc1(x))
        x = self.act2(self.fc2(x))
        return self.out_fn(self.fc3(x))

    def export_for_java(self):
        return {
            "in_dim": self.in_dim,
            "h1_dim": self.h1_dim,
            "h2_dim": self.h2_dim,
            "out_dim": self.out_dim,
            "out_activation": self.out_activation,
            # Transpose weight matrices so Java can multiply input[i] * W[i * out + j]
            "W1": np.round(self.fc1.weight.detach().cpu().numpy().T, 5).flatten().tolist(),
            "b1": np.round(self.fc1.bias.detach().cpu().numpy(), 5).tolist(),
            "W2": np.round(self.fc2.weight.detach().cpu().numpy().T, 5).flatten().tolist(),
            "b2": np.round(self.fc2.bias.detach().cpu().numpy(), 5).tolist(),
            "W3": np.round(self.fc3.weight.detach().cpu().numpy().T, 5).flatten().tolist(),
            "b3": np.round(self.fc3.bias.detach().cpu().numpy(), 5).tolist(),
        }

def build_datasets(csv_path="recorded_gameplay.csv", n_synthetic=100000):
    dyaw = np.random.uniform(-120.0, 120.0, n_synthetic)
    dpitch = np.random.uniform(-60.0, 60.0, n_synthetic)
    fine_mask = np.random.rand(n_synthetic) < 0.45
    dyaw[fine_mask] = np.random.uniform(-15.0, 15.0, fine_mask.sum())
    dpitch[fine_mask] = np.random.uniform(-10.0, 10.0, fine_mask.sum())

    dist = np.hypot(dyaw, dpitch) + 1e-6
    prev_vx = np.random.uniform(-12.0, 12.0, n_synthetic)
    prev_vy = np.random.uniform(-8.0, 8.0, n_synthetic)
    target_vel = np.random.uniform(-5.0, 5.0, n_synthetic)

    gain = np.clip(0.32 * np.tanh(dist / 18.0) + 0.12, 0.08, 0.44)
    arc_x = -0.035 * dpitch * np.tanh(dist / 25.0)
    arc_y = 0.035 * dyaw * np.tanh(dist / 25.0)

    step_yaw = np.clip(dyaw * gain + arc_x + 0.28 * prev_vx + 0.45 * target_vel, -28.0, 28.0)
    step_pitch = np.clip(dpitch * gain + arc_y + 0.28 * prev_vy, -22.0, 22.0)

    X_aim = np.stack([dyaw / 180.0, dpitch / 90.0, dist / 180.0, prev_vx / 30.0, prev_vy / 30.0, target_vel / 20.0], axis=1).astype(np.float32)
    Y_aim = np.stack([step_yaw / 30.0, step_pitch / 30.0], axis=1).astype(np.float32)

    # Strafe dataset
    edist = np.random.uniform(0.5, 10.0, n_synthetic)
    rel_angle = np.random.uniform(-180.0, 180.0, n_synthetic)
    thurt = np.random.choice([0.0, 1.0, 2.0, 5.0, 8.0, 9.0, 10.0], size=n_synthetic)
    mhurt = np.random.choice([0.0, 2.0, 5.0, 8.0, 9.0], size=n_synthetic)
    phase = np.random.uniform(-1.0, 1.0, n_synthetic)
    haz_l = np.random.choice([0.0, 0.3, 0.8, 1.0], size=n_synthetic, p=[0.7, 0.1, 0.1, 0.1])
    haz_r = np.random.choice([0.0, 0.3, 0.8, 1.0], size=n_synthetic, p=[0.7, 0.1, 0.1, 0.1])
    pitch = np.random.uniform(-45.0, 45.0, n_synthetic)

    w_tap = ((thurt >= 8.0) & (edist < 3.8)) | (edist < 1.5)
    kw = (~w_tap).astype(np.float32)
    ks = ((edist < 1.8) & (thurt >= 8.0)).astype(np.float32)
    pref_l = (phase > 0.0) & (haz_l < 0.5) | (haz_r >= 0.6)
    pref_r = (~pref_l) & (haz_r < 0.5) | (haz_l >= 0.6)
    in_rng = (edist >= 1.6) & (edist <= 6.5)
    ka = (in_rng & pref_l & ~(haz_l >= 0.7)).astype(np.float32)
    kd = (in_rng & pref_r & ~(haz_r >= 0.7)).astype(np.float32)
    kjump = ((edist > 5.8) & (haz_l < 0.3) & (haz_r < 0.3) & (mhurt == 0)).astype(np.float32)
    ksprint = kw.copy()

    X_str = np.stack([np.clip(edist / 10.0, 0.0, 1.0), rel_angle / 180.0, thurt / 10.0, mhurt / 10.0, phase, haz_l, haz_r, pitch / 90.0], axis=1).astype(np.float32)
    Y_str = np.stack([kw, ka, ks, kd, kjump, ksprint], axis=1).astype(np.float32)

    # Blend in real recorded gameplay CSV if uploaded by user
    if os.path.exists(csv_path):
        print(f"Found user gameplay recording {csv_path}! Merging into training set with 5x priority weight...")
        data = np.genfromtxt(csv_path, delimiter=",", skip_header=1, dtype=np.float32)
        if data.ndim == 2 and data.shape[1] >= 18:
            rec_X_aim = np.stack([
                np.clip(data[:, 0] / 180.0, -1.0, 1.0),
                np.clip(data[:, 1] / 90.0, -1.0, 1.0),
                np.clip(data[:, 2] / 180.0, 0.0, 1.0),
                np.clip(data[:, 3] / 30.0, -1.0, 1.0),
                np.clip(data[:, 4] / 30.0, -1.0, 1.0),
                np.clip(data[:, 5] / 20.0, -1.0, 1.0),
            ], axis=1)
            rec_Y_aim = np.stack([
                np.clip(data[:, 6] / 30.0, -1.0, 1.0),
                np.clip(data[:, 7] / 30.0, -1.0, 1.0),
            ], axis=1)
            X_aim = np.concatenate([X_aim] + [rec_X_aim] * 5, axis=0)
            Y_aim = np.concatenate([Y_aim] + [rec_Y_aim] * 5, axis=0)

    return X_aim, Y_aim, X_str, Y_str

def train_model(model, X, Y, epochs=40, batch_size=512, lr=3e-3, is_bce=False):
    model.to(device)
    dataset = TensorDataset(torch.from_numpy(X), torch.from_numpy(Y))
    loader = DataLoader(dataset, batch_size=batch_size, shuffle=True)
    optimizer = optim.AdamW(model.parameters(), lr=lr, weight_decay=1e-5)
    criterion = nn.BCELoss() if is_bce else nn.MSELoss()

    for epoch in range(epochs):
        total_loss = 0.0
        for xb, yb in loader:
            xb, yb = xb.to(device), yb.to(device)
            optimizer.zero_grad()
            pred = model(xb)
            loss = criterion(pred, yb)
            loss.backward()
            optimizer.step()
            total_loss += loss.item() * xb.size(0)
        if (epoch + 1) % 10 == 0:
            print(f"Epoch {epoch+1}/{epochs} - Loss: {total_loss / len(dataset):.6f}")

if __name__ == "__main__":
    X_aim, Y_aim, X_str, Y_str = build_datasets()
    print("Training AimNet on GPU...")
    aim_model = PureJavaCompatibleMLP(6, 32, 32, 2, out_activation="tanh")
    train_model(aim_model, X_aim, Y_aim, epochs=40, is_bce=False)

    print("Training StrafeNet on GPU...")
    strafe_model = PureJavaCompatibleMLP(8, 32, 32, 6, out_activation="sigmoid")
    train_model(strafe_model, X_str, Y_str, epochs=40, is_bce=True)

    output = {
        "version": 1,
        "trained_samples": int(len(X_aim) + len(X_str)),
        "aim_net": aim_model.export_for_java(),
        "strafe_net": strafe_model.export_for_java()
    }
    with open("human_weights.json", "w") as f:
        json.dump(output, f)
    print("Saved human_weights.json successfully! Size:", os.path.getsize("human_weights.json"), "bytes")
    try:
        from google.colab import files
        files.download("human_weights.json")
    except ImportError:
        pass
