#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Check the diagnostic producer and reference-boundary inventory.

Discovery is a lexical backstop, not a call-graph or conformance proof. It ignores
comments/literals, records presentation methods and closed constructors, and makes
new producers/constructors require an explicit inventory update. Boundary rows
separately record native counterparts, exposure and independent comparisons.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

from check_error_channels import make_source

ROOT = Path(__file__).resolve().parents[1]
REGISTER = "docs/data/diagnostic-presentation.json"
METHOD = re.compile(r"(?:message|description|pretty|show\w*|render\w*|format\w*|explain\w*|refusalText|errorText|reasonItem|toString)$")
ERROR_TYPE = re.compile(r"(?:Error|Failure|Problem|Refusal|Reason|Fault|Unavailable|Invalid|Defect|Rejection)")
SPECIAL = {"HaskellShow.scala", "InjectionShow.scala", "PlutusFailureCbor.scala",
           "PredicateFailureCbor.scala", "PreConwayFailureCbor.scala", "ContextFailureCbor.scala",
           "NodeEvent.scala"}
USES = {"native-wire", "native-json", "native-trace", "scarab-diagnostic", "needs-exposure-proof"}
STATES = {"verified", "incomplete", "not-exposed", "no-native-counterpart"}


def constructor_digest(family: dict) -> str:
    fields = [family["sourceSha256"], family["family"], *family["constructors"]]
    return hashlib.sha256("\0".join(fields).encode("utf-8")).hexdigest()


def stable_owner(name: str) -> str:
    return re.sub(r"\$local\$([^$]+)\$\d+", r"\1", name)


def signature(ts, start: int) -> str:
    parts = []
    depth = 0
    column = ts[start - 1].column
    for i in range(start, len(ts)):
        token = ts[i]
        if depth == 0 and parts and token.line > ts[i - 1].line and token.column <= column:
            break
        if depth == 0 and token.text == "=" and (i + 1 == len(ts) or ts[i + 1].text != ">"):
            break
        parts.append(token.text)
        depth += (token.text in {"(", "[", "{"}) - (token.text in {")", "]", "}"})
    return " ".join(parts)


def discover(root: Path) -> dict[str, dict]:
    found = {}
    for path in sorted(root.glob("*/src/main/scala/**/*.scala")):
        text = path.read_text(encoding="utf-8")
        marker = r"\b(?:" + METHOD.pattern.removesuffix("$") + r"|DomainError|getMessage)\b"
        if not re.search(marker, text) and path.name not in SPECIAL:
            continue
        source = make_source(text)
        ts = source.ts
        methods = set()
        for i, token in enumerate(ts):
            if token.text == "def" and i + 1 < len(ts) and (METHOD.fullmatch(ts[i + 1].text) or path.name in SPECIAL):
                owner = stable_owner(source.owner(i))
                methods.add(owner + "." + signature(ts, i + 1))
            elif token.text == "getMessage":
                methods.add("provider-message:" + stable_owner(source.owner(i)))
        if not methods and path.name not in SPECIAL:
            continue
        constructors = set()
        for i, token in enumerate(ts[:-2]):
            if token.text == "case" and ts[i + 1].text in {"class", "object"} and ERROR_TYPE.search(source.owner(i) + "." + ts[i + 2].text):
                constructors.add(stable_owner(source.owner(i)) + "." + ts[i + 2].text)
            elif token.text == "enum":
                end = next((s.end for s in source.scopes if s.start == i), len(ts))
                owner = stable_owner(source.owner(i)) + "." + ts[i + 1].text
                if not ERROR_TYPE.search(owner):
                    continue
                for j in range(i + 2, end):
                    if ts[j].text == "case" and ts[j].column == token.column + 2:
                        k = j + 1
                        constructors.add(owner + "." + ts[k].text)
                        while k + 2 < end and ts[k + 1].text == ",":
                            k += 2
                            constructors.add(owner + "." + ts[k].text)
        found[path.relative_to(root).as_posix()] = {
            "methods": sorted(methods), "constructors": sorted(constructors)}
    return found


def regular(root: Path, name: str) -> bool:
    path = Path(name)
    return bool(name) and not path.is_absolute() and ".." not in path.parts and root.joinpath(path).is_file() and not any(root.joinpath(*path.parts[:i]).is_symlink() for i in range(1, len(path.parts) + 1))


def validate(root: Path, data: dict) -> list[str]:
    errors = []
    if data.get("schemaVersion") != 1:
        return ["unsupported diagnostic inventory schema"]
    boundaries = data.get("boundaries", [])
    ids = [b.get("id") for b in boundaries]
    if len(ids) != len(set(ids)):
        errors.append("duplicate boundary id")
    for row in boundaries:
        label = row.get("id", "<missing>")
        if row.get("use") not in USES or row.get("state") not in STATES:
            errors.append(f"{label}: invalid use/state")
        if not row.get("scope") or not row.get("resolution") or not row.get("sources"):
            errors.append(f"{label}: missing scope, resolution or sources")
        for path in row.get("sources", []) + row.get("tests", []):
            if not regular(root, path):
                errors.append(f"{label}: missing/nonregular source {path}")
        if row.get("use", "").startswith("native-") and not row.get("native"):
            errors.append(f"{label}: missing pinned native counterpart")
        if row.get("state") == "verified" and (not row.get("tests") or not row.get("observations")):
            errors.append(f"{label}: verified needs tests and independent observations")
        if row.get("state") in {"incomplete", "not-exposed", "no-native-counterpart"} and not row.get("evidence"):
            errors.append(f"{label}: status needs explicit exposure/ownership evidence")
    pins = json.loads((root / "docs/data/reference-pins.json").read_text(encoding="utf-8"))
    def revisions(value):
        if isinstance(value, dict):
            if value.get("name", "").lower() == "plutus":
                yield value.get("revision")
            for child in value.values():
                yield from revisions(child)
        elif isinstance(value, list):
            for child in value:
                yield from revisions(child)
    plutus_pins = set(revisions(pins))
    families = data.get("nativeFamilies", [])
    if len({f.get("family") for f in families}) != len(families):
        errors.append("duplicate native family")
    for family in families:
        label = family.get("family", "<missing>")
        constructors = family.get("constructors", [])
        if not constructors or len(constructors) != len(set(constructors)):
            errors.append(f"{label}: empty/duplicate native constructors")
        if family.get("state") not in STATES:
            errors.append(f"{label}: invalid native family status")
        if family.get("constructorDigest") != constructor_digest(family):
            errors.append(f"{label}: constructor identity changed; verify the pinned native source")
        if family.get("revision") not in plutus_pins:
            errors.append(f"{label}: native source pin changed; refresh source inventory")
        if not re.fullmatch(r"[a-f0-9]{64}", family.get("sourceSha256", "")) or not family.get("source") or not isinstance(family.get("line"), int):
            errors.append(f"{label}: missing pinned source identity")
        if set(family.get("scalaMapping", {})) != set(constructors) or set(family.get("oracleCases", {})) != set(constructors):
            errors.append(f"{label}: every native constructor needs explicit capture/oracle slots")
        if family.get("state") == "verified" and any(not family["scalaMapping"][c] or not family["oracleCases"][c] for c in constructors):
            errors.append(f"{label}: native constructor mapping/oracle still incomplete")
    actual = discover(root)
    records = data.get("producers", [])
    registered = {r.get("source"): r for r in records}
    if len(registered) != len(records):
        errors.append("duplicate diagnostic producer source")
    for path in sorted(actual.keys() - registered.keys()):
        errors.append(f"unregistered diagnostic producer: {path}")
    for path in sorted(registered.keys() - actual.keys()):
        errors.append(f"stale diagnostic producer: {path}")
    for path in sorted(actual.keys() & registered.keys()):
        record = registered[path]
        if record.get("boundary") not in ids:
            errors.append(f"{path}: unknown presentation boundary")
        if record.get("methods") != actual[path]["methods"]:
            errors.append(f"{path}: presentation methods changed; review inventory")
        if record.get("constructors") != actual[path]["constructors"]:
            errors.append(f"{path}: closed constructors changed; review inventory")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--discover", action="store_true", help="print candidate producers, without classifying or accepting them")
    args = parser.parse_args()
    if args.discover:
        print(json.dumps(discover(args.root), indent=2))
        return 0
    try:
        data = json.loads((args.root / REGISTER).read_text(encoding="utf-8"))
        errors = validate(args.root, data)
    except (OSError, ValueError, TypeError, KeyError) as error:
        errors = [str(error)]
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"diagnostic presentation checked: {len(data['producers'])} producer files, {len(data['boundaries'])} boundaries, {len(data['nativeFamilies'])} native families; status is not inferred from discovery")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
