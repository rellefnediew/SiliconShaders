"""Compare saved GPU visibility profiles. Usage: check_sun_edges.py BASELINE RESULTS REPORT.json"""
import json
import struct
import sys
from pathlib import Path


def measure(path, short=False):
    data = path.read_bytes()
    count, width = struct.unpack_from("<II", data)
    assert len(data) == 8 + count * width
    start = int((0.5 + (-1.5 if short else 0.55) * 0.1) * width)
    end = int((0.5 + 3.9 * 0.1) * width)
    maximum = large = 0
    for frame in range(1, count):
        for pixel in range(start, end):
            change = abs(data[8 + frame * width + pixel] - data[8 + (frame - 1) * width + pixel])
            maximum = max(maximum, change)
            large += change > 25.5
    return {"maximum_visibility_step": maximum / 255, "changes_over_10_percent": large}


baseline, results, report = map(Path, sys.argv[1:])
summary = []
for size in (512, 1024, 2048, 4096):
    for short in (False, True):
        suffix = "-short" if short else ""
        old = (results if short else baseline) / f"production{suffix}-{size}-profiles.bin"
        new = results / f"smooth{suffix}-{size}-profiles.bin"
        before, after = measure(old, short), measure(new, short)
        assert after["maximum_visibility_step"] <= 0.04, (size, short, after)
        assert after["changes_over_10_percent"] == 0
        if before["maximum_visibility_step"] > 0.1:
            assert after["maximum_visibility_step"] < before["maximum_visibility_step"] / 10
        summary.append({"resolution": size, "one_block_short_shadow": short, "before": before, "after": after})
frozen = (results / "smooth-frozen-2048-profiles.bin").read_bytes()
count, width = struct.unpack_from("<II", frozen)
assert all(frozen[8 + frame * width:8 + (frame + 1) * width] == frozen[8:8 + width] for frame in range(count))
report.write_text(json.dumps(summary, indent=2) + "\n")
print("PASS: eight solar-motion cases; no visibility jumps over 4%; frozen-light profiles byte-identical")
