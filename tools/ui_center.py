"""Print "x y" for the centre of the first uiautomator node matching a selector.

Usage: ui_center.py ui.xml "text=Sine 262 Hz"   or   "desc=Add to favorites"
Exits 1 when nothing matches, so the smoke script can fail with a clear message.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def main() -> int:
    dump, selector = Path(sys.argv[1]), sys.argv[2]
    kind, _, wanted = selector.partition("=")
    attribute = {"text": "text", "desc": "content-desc"}[kind]
    nodes = list(ET.parse(dump).iter("node"))
    # Exact match first. Compose sometimes folds a row's texts into one node
    # ("3, Sine 262 Hz, Test Tones"), so a substring match is the fallback.
    exact = [n for n in nodes if n.get(attribute) == wanted]
    loose = [n for n in nodes if wanted in (n.get(attribute) or "")]
    for node in exact + loose:
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
        print(f"{(x1 + x2) // 2} {(y1 + y2) // 2}")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
