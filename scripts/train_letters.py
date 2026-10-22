#!/usr/bin/env python3
"""Train the letter reader (a small 1-D CNN) on the recorder's handwriting sessions.

    adb pull /storage/emulated/0/Android/data/dev.kb896.airgestures.dev/files/handwriting rec
    (cd rust && HANDWRITING=../rec OUT=../letters.jsonl cargo test -p bridge --release --lib -- --ignored dump)
    scripts/train_letters.py letters.jsonl model.json            # needs torch and numpy
    scripts/train_letters.py letters.jsonl --score               # leave-one-session-out accuracy
    adb push model.json /storage/emulated/0/Android/data/dev.kb896.airgestures.dev/files/handwriting/

The features are the phone's own (rust/bridge/src/letters.rs), so the model reads letters as
the recorder sees them. model.json holds a few models trained from different seeds; the
phone averages their probabilities. Its layout matches `Model` in letters.rs.
"""

import json
import sys

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

HIDDEN = 48
EPOCHS = 150
SEEDS = 3
BATCH = 32


class Cnn(nn.Module):
    def __init__(self, inputs, letters):
        super().__init__()
        self.c1 = nn.Conv1d(inputs, HIDDEN, 5, padding=2)
        self.c2 = nn.Conv1d(HIDDEN, HIDDEN, 5, padding=2)
        self.c3 = nn.Conv1d(HIDDEN, HIDDEN, 3, padding=1)
        self.out = nn.Linear(2 * HIDDEN, letters)
        self.drop = nn.Dropout(0.3)

    def forward(self, x, lengths):  # x: (batch, inputs, frames), zero-padded past each length
        # Every layer's output is zeroed past the letter, so a padded letter in a batch is read
        # exactly as it is alone (as on the phone).
        def mask(length, frames):
            return (torch.arange(frames)[None, :] < length[:, None]).float()[:, None, :]

        full = mask(lengths, x.shape[2])
        x = F.relu(self.c1(x)) * full
        x = F.relu(self.c2(x)) * full
        x = F.max_pool1d(x, 2, ceil_mode=True)
        half = mask((lengths + 1) // 2, x.shape[2])
        x = F.relu(self.c3(x)) * half
        mean = x.sum(2) / half.sum(2)
        top = (x - 1e4 * (1 - half)).amax(2)
        return self.out(self.drop(torch.cat([mean, top], 1)))


def augment(frames, rng):
    """A plausible other copy: uneven speed, ends cropped, per-channel gain, noise."""
    n = len(frames)
    m = max(4, int(n * rng.uniform(0.75, 1.3)))
    warp = np.cumsum(np.exp(rng.normal(0, 0.25, m)))
    warp = (warp - warp[0]) / (warp[-1] - warp[0]) * (n - 1)
    out = np.stack([np.interp(warp, np.arange(n), frames[:, c]) for c in range(frames.shape[1])], 1)
    out = out[rng.integers(0, max(1, m // 8)): m - rng.integers(0, max(1, m // 8))]
    out = out + rng.normal(0, 0.15, (1, out.shape[1])) + rng.normal(0, 0.05, out.shape)
    return (out - out.mean(1, keepdims=True)).astype(np.float32)


def batch(letters):
    frames = max(len(f) for f in letters)
    x = np.zeros((len(letters), letters[0].shape[1], frames), np.float32)
    for i, f in enumerate(letters):
        x[i, :, : len(f)] = f.T
    return torch.tensor(x), torch.tensor([len(f) for f in letters])


def train(X, y, count, seed):
    rng = np.random.default_rng(seed)
    torch.manual_seed(seed)
    model = Cnn(X[0].shape[1], count)
    optimiser = torch.optim.AdamW(model.parameters(), 3e-3, weight_decay=1e-2)
    steps = EPOCHS * ((len(X) + BATCH - 1) // BATCH)
    schedule = torch.optim.lr_scheduler.OneCycleLR(optimiser, 3e-3, total_steps=steps)
    for _ in range(EPOCHS):
        model.train()
        order = rng.permutation(len(X))
        for start in range(0, len(order), BATCH):
            ids = order[start: start + BATCH]
            x, lengths = batch([augment(X[i], rng) for i in ids])
            loss = F.cross_entropy(model(x, lengths), torch.tensor(y[ids]), label_smoothing=0.1)
            optimiser.zero_grad()
            loss.backward()
            optimiser.step()
            schedule.step()
    return model.eval()


def probabilities(models, letters):
    with torch.no_grad():
        x, lengths = batch(letters)
        return np.mean([F.softmax(m(x, lengths), 1).numpy() for m in models], 0)


def load(path):
    rows = [json.loads(line) for line in open(path)]
    rows = [r for r in rows if len(r["frames"]) >= 2]
    labels = sorted({r["label"] for r in rows})
    X = [np.array(r["frames"], np.float32) for r in rows]
    y = np.array([labels.index(r["label"]) for r in rows])
    sessions = np.array([r["session"] for r in rows])
    return X, y, sessions, labels


def score(X, y, sessions, labels):
    """Each session read by models trained on the others (sessions of 20+ letters)."""
    first = three = total = 0
    for session in sorted(set(sessions)):
        test = np.nonzero(sessions == session)[0]
        if len(test) < 20:
            continue
        train_ids = np.nonzero(sessions != session)[0]
        models = [train([X[i] for i in train_ids], y[train_ids], len(labels), seed) for seed in range(SEEDS)]
        ranked = np.argsort(-probabilities(models, [X[i] for i in test]), 1)
        right = ranked[:, 0] == y[test]
        near = (ranked[:, :3] == y[test][:, None]).any(1)
        print(f"{session}: {100 * right.mean():.0f} % first, {100 * near.mean():.0f} % in the first three", flush=True)
        first += right.sum()
        three += near.sum()
        total += len(test)
    print(f"all: {100 * first / total:.0f} % first, {100 * three / total:.0f} % in the first three")


def export(models, labels, X, path):
    def tensor(t):
        return np.round(t.detach().numpy().astype(np.float64), 6).tolist()

    out = {"letters": labels, "models": []}
    for m in models:
        out["models"].append({
            name: {"weight": tensor(layer.weight), "bias": tensor(layer.bias)}
            for name, layer in [("c1", m.c1), ("c2", m.c2), ("c3", m.c3), ("out", m.out)]
        })
    # A worked example for the phone's test: the first letter and what the models make of it.
    out["check"] = {"frames": X[0].tolist(), "probabilities": probabilities(models, [X[0]])[0].tolist()}
    with open(path, "w") as f:
        json.dump(out, f)


def main():
    X, y, sessions, labels = load(sys.argv[1])
    print(f"{len(X)} letters from {len(set(sessions))} sessions")
    if sys.argv[2] == "--score":
        return score(X, y, sessions, labels)
    models = [train(X, y, len(labels), seed) for seed in range(SEEDS)]
    export(models, labels, X, sys.argv[2])
    print(f"saved {sys.argv[2]}")


if __name__ == "__main__":
    torch.set_num_threads(8)
    main()
