#!/usr/bin/env python3
"""Reproduce the pinned regional resources. Pass the project's sing-box binary."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import urllib.request


def inline_rules(document):
    prefixes = {"domain": "full:", "domain_suffix": "domain:", "domain_keyword": "keyword:",
                "domain_regex": "regexp:", "ip_cidr": ""}
    result = []
    for rule in document["rules"]:
        for key, values in rule.items():
            if key not in prefixes:
                raise ValueError(f"Unsupported rule-set field: {key}")
            # sing-box emits a scalar for a single value, including a regex.
            # Iterating that string would turn one expression into invalid rules.
            for value in [values] if isinstance(values, str) else values:
                if key == "ip_cidr" and ":" in value:
                    continue  # Same IPv4-only iOS inline format as the RU bundle.
                result.append(prefixes[key] + value)
    return ("\n".join(result) + "\n").encode()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--singbox", required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    resources = root / "sharedUI/src/commonMain/composeResources/files"
    manifest = json.loads((root / "scripts/regional-rules.lock.json").read_text())
    with tempfile.TemporaryDirectory() as temporary:
        for entry in manifest:
            with urllib.request.urlopen(entry["source"], timeout=30) as response:
                binary = response.read()
            if hashlib.sha256(binary).hexdigest() != entry["srs_sha256"]:
                raise ValueError(f"Hash mismatch: {entry['name']}")
            staged = Path(temporary) / (entry["name"] + ".srs")
            staged.write_bytes(binary)
            decoded = staged.with_suffix(".json")
            subprocess.run([args.singbox, "rule-set", "decompile", str(staged), "-o", str(decoded)], check=True)
            text = inline_rules(json.loads(decoded.read_text(encoding="utf-8")))
            if hashlib.sha256(text).hexdigest() != entry["text_sha256"]:
                raise ValueError(f"Inline conversion differs: {entry['name']}")
            (resources / "rules" / staged.name).write_bytes(binary)
            (resources / "xray" / (entry["name"] + ".txt")).write_bytes(text)
            print(entry["name"])


if __name__ == "__main__":
    main()
