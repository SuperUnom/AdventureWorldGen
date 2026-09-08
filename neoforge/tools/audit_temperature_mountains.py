"""Measure altitude belts around separated peaks in TemperatureFieldPreview output."""
import json
import math
import struct
import sys
from pathlib import Path

directory = Path(sys.argv[1])
metadata = json.loads((directory / "parameters.json").read_text())
side, extent = metadata["side"], metadata["extent"]
step = 2 * extent / side
rows = list(struct.iter_unpack(">ffffB", (directory / "fields.bin").read_bytes()))
assert len(rows) == side * side
peaks = []
for i in sorted((i for i, r in enumerate(rows) if r[4] and r[0] >= 190),
                key=lambda i: rows[i][0], reverse=True):
    x, z = i % side, i // side
    if all(math.hypot(x - a, z - b) * step >= 700 for a, b in peaks):
        peaks.append((x, z))
    if len(peaks) == 3:
        break

report = []
radius = math.ceil(600 / step)
for px, pz in peaks:
    strata = [[] for _ in range(3)]
    for z in range(max(0, pz - radius), min(side, pz + radius + 1)):
        for x in range(max(0, px - radius), min(side, px + radius + 1)):
            if math.hypot(x - px, z - pz) * step > 600:
                continue
            height, latitude, cooling, temperature, land = rows[z * side + x]
            if not land:
                continue
            layer = (0 if 76 <= height < 110 else 1 if 130 <= height < 170
                     else 2 if height >= 190 else -1)
            if layer >= 0:
                strata[layer].append(temperature)
    result = {"peak_x": round(-extent + (px + .5) * step),
              "peak_z": round(-extent + (pz + .5) * step),
              "height": round(rows[pz * side + px][0], 1)}
    for name, values in zip(["foot_y76_110", "slope_y130_170", "peak_y190_plus"], strata):
        counts = [sum(min(3, int(t / 2.5)) == band for t in values) for band in range(4)]
        result[name] = {"samples": len(values),
                        "mean_temperature": round(sum(values) / len(values), 2) if values else None,
                        "band_counts": counts,
                        "dominant_band": counts.index(max(counts)) if values else None}
    report.append(result)

output = json.dumps(report, indent=2) + "\n"
(directory / "mountain-audit.json").write_text(output)
print(output)
