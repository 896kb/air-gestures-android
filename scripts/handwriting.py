#!/usr/bin/env python3
"""Read a handwriting recording from the debug build's recorder (RecordActivity).

    adb pull /storage/emulated/0/Android/data/dev.kb896.airgestures.dev/files/handwriting/<name>
    scripts/handwriting.py <name>            # summary: rate, losses, letters, signal per window

Use as a module for training: `load(folder)` gives the samples and their times, and
`windows(recording)` each written letter's slice (discarded ones left out).
"""

import csv
import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np

HEADER = b"AGEMG1"


@dataclass
class Recording:
    rate: int
    channels: int
    samples: np.ndarray  # (n, channels) uint16, in arrival order
    times: np.ndarray  # (n,) host time of each sample (s), spread across its batch
    sequences: np.ndarray  # (batches,) band sequence numbers
    marks: list  # (time, event, label)


def load(folder) -> Recording:
    folder = Path(folder)
    data = (folder / "emg.bin").read_bytes()
    end = data.index(b"\n")
    magic, rate, channels, per_batch = data[:end].split()
    if magic != HEADER:
        raise ValueError(f"not a recording: {magic!r}")
    rate, channels, per_batch = int(rate), int(channels), int(per_batch)
    record = np.dtype([
        ("time", "<f8"), ("sequence", "<u8"), ("band_us", "<u8"),
        ("values", "<u2", (per_batch, channels)),
    ])
    body = data[end + 1:]
    batches = np.frombuffer(body[: len(body) // record.itemsize * record.itemsize], record)
    samples = batches["values"].reshape(-1, channels)
    # A batch arrives after its last sample: spread its samples back over one batch's time.
    offsets = (np.arange(per_batch) - (per_batch - 1)) / rate
    times = (batches["time"][:, None] + offsets[None, :]).reshape(-1)
    with open(folder / "marks.csv", newline="") as f:
        marks = [(float(row["time"]), row["event"], row["label"]) for row in csv.DictReader(f)]
    return Recording(rate, channels, samples, times, batches["sequence"], marks)


def windows(recording: Recording):
    """(letter, samples) for each writing window, without the ones marked "discard"."""
    found, start = [], {}
    for time, event, label in recording.marks:
        if event == "start":
            start[label] = time
        elif event == "end" and label in start:
            found.append([label, start.pop(label), time])
        elif event == "discard":
            # The latest window of that letter was written wrong.
            for item in reversed(found):
                if item[0] == label:
                    found.remove(item)
                    break
    for label, begin, end in found:
        inside = (recording.times >= begin) & (recording.times < end)
        yield label, recording.samples[inside]


def main(folder):
    rec = load(folder)
    seq = rec.sequences.astype(np.int64)
    missing = int(np.clip(np.diff(seq) - 1, 0, None).sum()) if len(seq) > 1 else 0
    span = rec.times[-1] - rec.times[0] if len(rec.times) else 0
    print(f"{len(seq)} batches over {span:.1f} s, {missing} missing "
          f"({100 * missing / max(1, len(seq) + missing):.1f} %), {rec.channels} ch at {rec.rate} Hz")
    centred = rec.samples.astype(np.float64) - np.median(rec.samples, axis=0)
    print("channel spread (std):", " ".join(f"{v:.0f}" for v in centred.std(axis=0)))
    letters = list(windows(rec))
    print(f"{len(letters)} letters written:", "".join(sorted(label for label, _ in letters)))
    for label, samples in letters[:10]:
        energy = (samples.astype(np.float64) - np.median(rec.samples, axis=0)).std(axis=0).mean()
        print(f"  {label}: {len(samples)} samples, mean std {energy:.0f}")


if __name__ == "__main__":
    main(sys.argv[1])
