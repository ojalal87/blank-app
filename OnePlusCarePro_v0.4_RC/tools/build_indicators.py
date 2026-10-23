#!/usr/bin/env python3
"""Builds app/src/main/assets/spyware_indicators.tsv from the MVT indicators index
(https://github.com/mvt-project/mvt-indicators, MIT; Amnesty collections CC BY 2.0; Echap CC BY 4.0).
Only Android-relevant indicator types are kept. Usage: python3 -I tools/build_indicators.py <dir-with-stix2-files> <indicators.yaml>
Output line format: TYPE<TAB>value<TAB>collection name."""
import json, re, sys, pathlib

KEEP = {"app:id": "APP", "app:cert.sha1": "CERT_SHA1", "app:cert.sha256": "CERT_SHA256",
        "file:hashes.sha256": "SHA256", "domain-name:value": "DOMAIN"}
stix_dir, index = pathlib.Path(sys.argv[1]), sys.argv[2]
y = open(index).read()
items = re.findall(r"name: (.+?)\n.*?path: (\S+)", y, re.S)
rows = set()
for name, path in items:
    f = stix_dir / path.replace("/", "_")
    data = json.load(open(f))
    for obj in data.get("objects", []):
        if obj.get("type") != "indicator":
            continue
        m = re.match(r"\[([a-z0-9:\-._]+)\s*=\s*'(.*)'\]$", obj.get("pattern", "").strip())
        if not m or m.group(1) not in KEEP:
            continue
        value = m.group(2).strip().lower()
        if "\t" in value or "\n" in value or not value:
            continue
        rows.add((KEEP[m.group(1)], value, name.replace(" Indicators of Compromise", "").strip()))
out = pathlib.Path("app/src/main/assets/spyware_indicators.tsv")
out.write_text("".join(f"{t}\t{v}\t{c}\n" for t, v, c in sorted(rows)), encoding="utf-8")
print(len(rows), "indicators ->", out)
