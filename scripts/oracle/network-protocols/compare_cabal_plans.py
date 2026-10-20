#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Prove the selected protocol-test target closure matches a full Cabal plan."""

import argparse
import hashlib
import json
from pathlib import Path


def load_target(path: Path) -> tuple[list[dict], dict[str, dict]]:
    plan = json.loads(path.read_text())
    units = plan["install-plan"]
    by_id = {unit["id"]: unit for unit in units}
    roots = [
        unit
        for unit in units
        if unit.get("pkg-name") == "ouroboros-network"
        and unit.get("component-name") == "test:protocols-tests"
    ]
    if len(roots) != 1:
        raise ValueError(f"expected one protocols-tests root, found {len(roots)} in {path}")
    closure: set[str] = set()
    pending = [roots[0]["id"]]
    while pending:
        unit_id = pending.pop()
        if unit_id in closure:
            continue
        closure.add(unit_id)
        pending.extend(dep for dep in by_id[unit_id].get("depends", []) if dep in by_id)
    return [by_id[unit_id] for unit_id in closure], by_id


def unit_key(unit: dict) -> tuple:
    return unit.get("pkg-name"), unit.get("pkg-version"), unit.get("component-name")


def normalized_source(unit: dict) -> dict:
    source = unit.get("pkg-src") or {}
    if source.get("type") == "local":
        return {"type": "local"}
    return source


def signature(unit: dict, by_id: dict[str, dict]) -> dict:
    return {
        "pkgName": unit.get("pkg-name"),
        "pkgVersion": unit.get("pkg-version"),
        "component": unit.get("component-name"),
        "flags": unit.get("flags", {}),
        "cabalSha256": unit.get("pkg-cabal-sha256"),
        "sourceSha256": unit.get("pkg-src-sha256"),
        "source": normalized_source(unit),
        "dependencies": sorted(
            (by_id[dep].get("pkg-name"), by_id[dep].get("pkg-version"), by_id[dep].get("component-name"))
            for dep in unit.get("depends", [])
            if dep in by_id
        ),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("reference_plan", type=Path)
    parser.add_argument("selected_plan", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    reference, reference_by_id = load_target(args.reference_plan)
    selected, selected_by_id = load_target(args.selected_plan)
    reference_signatures = {unit_key(unit): signature(unit, reference_by_id) for unit in reference}
    selected_signatures = {unit_key(unit): signature(unit, selected_by_id) for unit in selected}
    same = reference_signatures == selected_signatures
    result = {
        "target": "ouroboros-network:protocols-tests",
        "referencePlan": str(args.reference_plan),
        "referencePlanSha256": hashlib.sha256(args.reference_plan.read_bytes()).hexdigest(),
        "referenceUnitCount": len(reference),
        "selectedPlan": str(args.selected_plan),
        "selectedPlanSha256": hashlib.sha256(args.selected_plan.read_bytes()).hexdigest(),
        "selectedUnitCount": len(selected),
        "sameTargetClosure": same,
        "mismatchedKeys": sorted(str(key) for key in reference_signatures.keys() ^ selected_signatures.keys()),
        "mismatchedSignatures": sorted(
            str(key)
            for key in reference_signatures.keys() & selected_signatures.keys()
            if reference_signatures[key] != selected_signatures[key]
        ),
    }
    rendered = json.dumps(result, indent=2) + "\n"
    if args.output:
        args.output.write_text(rendered)
    print(rendered, end="")
    if not same:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
