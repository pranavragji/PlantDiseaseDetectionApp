"""Draw the README accuracy chart from ml/metrics.json.

    python charts.py
"""
import json
import xml.etree.ElementTree as ET
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch

ROOT = Path(__file__).resolve().parent.parent
GREEN, INK, MUTED, GRID = "#2E7D32", "#1C2B1F", "#5F6D61", "#E3E8E0"


def main():
    metrics = json.loads((ROOT / "ml" / "metrics.json").read_text())
    names = {e.get("name"): e.text for e in ET.parse(ROOT / "app/src/main/res/values/plants.xml").getroot()}
    rows = sorted(((names.get("crop_" + k, k), v["acc"], v["n"]) for k, v in metrics["per_crop"].items() if k != "none"),
                  key=lambda r: r[1])

    fig, ax = plt.subplots(figsize=(8, 0.34 * len(rows) + 1.2), dpi=160)
    for i, (name, acc, n) in enumerate(rows):
        # thin bar, rounded data end, anchored to the baseline
        ax.add_patch(FancyBboxPatch((0, i - 0.3), acc * 100, 0.6, boxstyle="round,pad=0,rounding_size=0.25",
                                    mutation_aspect=1 / 25, color=GREEN, linewidth=0))
        ax.text(acc * 100 + 1, i, f"{acc * 100:.1f}%", va="center", fontsize=9, color=INK)
        ax.text(-1.5, i, f"{name}  ", va="center", ha="right", fontsize=9.5, color=INK)
        ax.text(-1.5, i - 0.36, f"{n} test photos  ", va="center", ha="right", fontsize=7, color=MUTED)
    ax.set_xlim(0, 108)
    ax.set_ylim(-0.7, len(rows) - 0.3)
    ax.set_yticks([])
    ax.set_xticks(range(0, 101, 20), [f"{x}%" for x in range(0, 101, 20)], fontsize=8, color=MUTED)
    ax.grid(axis="x", color=GRID, linewidth=0.8)
    ax.set_axisbelow(True)
    for s in ax.spines.values():
        s.set_visible(False)
    ax.tick_params(length=0)
    ax.set_title("Accuracy by plant (held-out test photos)", loc="left", fontsize=12, color=INK, pad=12, x=-0.28)
    fig.tight_layout()
    out = ROOT / "docs" / "accuracy_by_plant.png"
    fig.savefig(out, facecolor="white")
    print("wrote", out)


if __name__ == "__main__":
    main()
