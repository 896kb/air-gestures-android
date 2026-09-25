#!/usr/bin/env python3
"""Convert Lucide SVG icons to Android vector drawables at the CargoBolt 1.75 stroke.

    scripts/lucide-to-vector.py <lucide-static>/icons app/src/main/res/drawable arrow-up house …

Writes ic_<name>.xml (dashes become underscores). Handles path, line,
polyline, polygon, circle and rect, which is all Lucide uses.
"""
import re
import sys
import xml.etree.ElementTree as ET


def num(value):
    return float(value)


def to_path(element):
    tag = element.tag.split("}")[-1]
    a = element.attrib
    if tag == "path":
        return a["d"]
    if tag == "line":
        return f"M{a['x1']} {a['y1']}L{a['x2']} {a['y2']}"
    if tag in ("polyline", "polygon"):
        points = re.findall(r"-?[\d.]+", a["points"])
        pairs = list(zip(points[::2], points[1::2]))
        return "M" + " L".join(f"{x} {y}" for x, y in pairs) + ("Z" if tag == "polygon" else "")
    if tag == "circle":
        cx, cy, r = num(a["cx"]), num(a["cy"]), num(a["r"])
        return f"M{cx - r} {cy}a{r} {r} 0 1 0 {2 * r} 0a{r} {r} 0 1 0 {-2 * r} 0"
    if tag == "rect":
        x, y = num(a.get("x", "0")), num(a.get("y", "0"))
        w, h = num(a["width"]), num(a["height"])
        rx = num(a.get("rx", a.get("ry", "0")))
        ry = num(a.get("ry", a.get("rx", "0")))
        if rx == 0:
            return f"M{x} {y}h{w}v{h}h{-w}Z"
        return (
            f"M{x + rx} {y}h{w - 2 * rx}a{rx} {ry} 0 0 1 {rx} {ry}v{h - 2 * ry}"
            f"a{rx} {ry} 0 0 1 {-rx} {ry}h{-(w - 2 * rx)}a{rx} {ry} 0 0 1 {-rx} {-ry}"
            f"v{-(h - 2 * ry)}a{rx} {ry} 0 0 1 {rx} {-ry}Z"
        )
    raise ValueError(f"unsupported element {tag}")


def main():
    source, target, *names = sys.argv[1:]
    for name in names:
        root = ET.parse(f"{source}/{name}.svg").getroot()
        paths = "\n".join(
            "    <path\n"
            f'        android:pathData="{to_path(element)}"\n'
            '        android:strokeColor="#FF111214"\n'
            '        android:strokeWidth="1.75"\n'
            '        android:strokeLineCap="round"\n'
            '        android:strokeLineJoin="round"\n'
            '        android:fillColor="#00000000" />'
            for element in root
        )
        with open(f"{target}/ic_{name.replace('-', '_')}.xml", "w") as out:
            out.write(
                '<?xml version="1.0" encoding="utf-8"?>\n'
                f'<!-- Lucide "{name}" (ISC), 1.75 stroke as in the CargoBolt design system. -->\n'
                '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
                '    android:width="24dp"\n    android:height="24dp"\n'
                '    android:viewportWidth="24"\n    android:viewportHeight="24">\n'
                f"{paths}\n</vector>\n"
            )


if __name__ == "__main__":
    main()
