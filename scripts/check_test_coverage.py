# SPDX-License-Identifier: Apache-2.0
"""Validate fixture identities, corpus documentation, provenance and declared comparison scope.

The register is an inventory, never an instruction to ignore a failed assertion.
Refreshing identities does not regenerate any fixture or expected result.
"""
import argparse
import datetime
import hashlib
import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
INVENTORY = "docs/data/fixture-inventory.json"
REGISTER = "docs/data/test-coverage-register.json"
# A gap's issue is also recorded in the compatibility register entry that cites the corpus.
COMPONENTS = "docs/data/compatibility.json"
KINDS = {"native-parity", "regression", "input-boundary", "reference-non-result",
         "canonicalization", "sampling-limit", "external-input", "known-divergence"}
# covered: the entry's assertion proves the invariant its scope names. bounded: the assertion holds but proves
# less than its scope names (membership instead of list equality, a projection instead of a whole state); the
# rationale states the gap and the tracking issue owns the stronger contract. open / pending-integration: the
# assertion is not yet enforced.
STATES = {"covered", "bounded", "open", "pending-integration"}
ISSUE = re.compile(r"https://github\.com/KD5UJC-XYZ/scarab/issues/[1-9][0-9]*")
# Provenance schema 1, enforced by validate_provenance below and described in fixtures/README.md.
PROVENANCE_SCHEMA_VERSION = 1
REFERENCE_CORPUS = re.compile(r"fixtures/reference/[^/]+")
SOURCE_FIELDS = ("ledgerSourceCommit", "ledgerSourceRevision", "ledgerPin", "consensusSourceCommit",
                 "plutusSourceCommit", "sourceCommit", "referenceGitRevision")
DATE_FIELDS = ("measuredAt", "capturedAt", "date")
CASE_FILES = ("cases.jsonl", "cases.json")
SHA256 = re.compile(r"[0-9a-f]{64}")
REVISION = re.compile(r"[0-9a-f]{7,40}")
DATE = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}")
GAP_ISSUE = re.compile(r"(?:#|/issues/)([1-9][0-9]*)")


def read_json(path):
    def unique_fields(pairs):
        fields = {}
        for key, value in pairs:
            if key in fields:
                raise ValueError(f"duplicate JSON field: {key}")
            fields[key] = value
        return fields
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_fields)


def relative_file(root, value):
    path = Path(value)
    if path.is_absolute() or ".." in path.parts or not value:
        raise ValueError(f"unsafe repository path: {value!r}")
    target = root / path
    linked = any(root.joinpath(*path.parts[:n]).is_symlink()
                 for n in range(1, len(path.parts) + 1))
    if linked or not target.is_file() or root.resolve() not in target.resolve().parents:
        raise ValueError(f"missing or nonregular repository file: {value}")
    return target


def discover_corpora(root):
    corpora = set()
    fixtures = root / "fixtures"
    if fixtures.is_dir():
        for path in fixtures.rglob("*"):
            if path.is_symlink():
                raise ValueError(f"nonregular fixture path: {path.relative_to(root)}")
            if not path.is_file():
                continue
            parts = path.relative_to(root).parts
            if len(parts) < 3:
                continue
            depth = 3 if parts[1] in {"reference", "preview"} and len(parts) > 3 else 2
            corpora.add("/".join(parts[:depth]))
    for resources in root.glob("*/src/test/resources"):
        if resources.is_symlink():
            raise ValueError(f"nonregular fixture path: {resources.relative_to(root)}")
        for path in resources.rglob("*"):
            if path.is_symlink():
                raise ValueError(f"nonregular fixture path: {path.relative_to(root)}")
            if not path.is_file():
                continue
            parts = path.relative_to(resources).parts
            depth = 2 if parts[0] == "upstream" and len(parts) > 2 else 1
            corpus = resources if len(parts) == 1 else resources.joinpath(*parts[:depth])
            corpora.add(corpus.relative_to(root).as_posix())
    return sorted(corpora)


def fixture_inventory(root):
    corpora = discover_corpora(root)
    payloads = {}
    for corpus in corpora:
        for path in sorted((root / corpus).rglob("*")):
            if path.is_file() and path.suffix.lower() != ".md":
                rel = path.relative_to(root).as_posix()
                relative_file(root, rel)
                payloads[rel] = hashlib.sha256(path.read_bytes()).hexdigest()
    return {"schemaVersion": 1, "corpora": corpora, "files": dict(sorted(payloads.items()))}


def validate_inventory(root, expected):
    if set(expected) != {"schemaVersion", "corpora", "files"} or expected["schemaVersion"] != 1:
        raise ValueError("unsupported fixture inventory schema")
    actual = fixture_inventory(root)
    if expected != actual:
        missing = sorted(set(expected["files"]) - set(actual["files"]))
        added = sorted(set(actual["files"]) - set(expected["files"]))
        changed = sorted(p for p in set(actual["files"]) & set(expected["files"])
                         if actual["files"][p] != expected["files"][p])
        raise ValueError(f"fixture inventory differs: missing={missing[:8]}, added={added[:8]}, "
                         f"changed={changed[:8]}, corpusSetChanged={expected['corpora'] != actual['corpora']}")
    for corpus in actual["corpora"]:
        readme = relative_file(root, f"{corpus}/README.md")
        if not readme.read_text(encoding="utf-8").strip():
            raise ValueError(f"empty corpus README: {corpus}")
    return actual


def case_file(path):
    """Return (name, count) of the corpus's case file, or (None, None) when it records none."""
    for name in CASE_FILES:
        target = path / name
        if not target.is_file():
            continue
        if name.endswith(".jsonl"):
            return name, sum(1 for line in target.read_text(encoding="utf-8").splitlines() if line.strip())
        payload = read_json(target)
        if isinstance(payload, list):
            return name, len(payload)
        if isinstance(payload, dict) and isinstance(payload.get("cases"), list):
            return name, len(payload["cases"])
        raise ValueError(f"{name}: a case corpus must be a list of cases")
    return None, None


def integer(value):
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0


def nested_objects(node, prefix=""):
    """Every object of the record with its field path, so a nested count is checked where it sits."""
    if isinstance(node, dict):
        yield prefix, node
        for key, value in node.items():
            yield from nested_objects(value, f"{prefix}{key}.")
    elif isinstance(node, list):
        for index, value in enumerate(node):
            yield from nested_objects(value, f"{prefix}{index}.")


def digest_fields(node, prefix=""):
    """Every digest the record claims, with its field path: a *Sha256 field and the entries it names."""
    if isinstance(node, list):
        for index, entry in enumerate(node):
            yield from digest_fields(entry, f"{prefix}{index}.")
        return
    if not isinstance(node, dict):
        return
    for key, value in node.items():
        field = f"{prefix}{key}"
        if key.lower().endswith("sha256"):
            yield from leaves(value, f"{field}.")
        else:
            yield from digest_fields(value, f"{field}.")


def leaves(node, prefix=""):
    if isinstance(node, str):
        yield prefix.rstrip("."), node
    elif isinstance(node, list):
        for index, entry in enumerate(node):
            yield from leaves(entry, f"{prefix}{index}.")
    elif isinstance(node, dict):
        for key, entry in node.items():
            yield from leaves(entry, f"{prefix}{key}.")


def recorded_count(node):
    """The count a record (or a batch object inside one) states, with the field that states it."""
    cases = node.get("cases")
    if integer(cases):
        return "cases", cases
    if isinstance(cases, dict) and integer(cases.get("count")):
        return "cases.count", cases["count"]
    for field in ("caseCount", "lines"):
        if integer(node.get(field)):
            return field, node[field]
    return None, None


def gap_owners(components, corpus):
    """The register entries that cite this corpus as evidence."""
    return [row for row in components
            if any(path.startswith(f"{corpus}/") for path in row.get("evidence", {}).get("paths", []))]


def validate_provenance(root, corpus, components):
    """Validate one fixtures/reference/<corpus>/provenance.json against provenance schema 1.

    Every failure names the file and the field, so a defect is actionable without reading this module.
    """
    relative = f"{corpus}/provenance.json"
    path = root / relative
    if not path.is_file():
        return None
    record = read_json(path)

    def fail(field, problem):
        raise ValueError(f"{relative}: {field}: {problem}")

    if not isinstance(record, dict):
        fail("(root)", "must be one JSON object per corpus; record per-batch runs under a 'batches' list")
    version = record.get("schemaVersion")
    if not integer(version) or version != PROVENANCE_SCHEMA_VERSION:
        fail("schemaVersion", f"must be {PROVENANCE_SCHEMA_VERSION}")
    scope = record.get("scope")
    if not (isinstance(scope, str) and scope.strip()) and not (isinstance(scope, dict) and scope):
        fail("scope", "must be a non-empty string or object saying which behaviour the corpus measures")
    if not any(field in record for field in SOURCE_FIELDS):
        nested = record.get("ledger")
        revision = nested.get("revision") if isinstance(nested, dict) else None
        if not isinstance(revision, str) or not REVISION.fullmatch(revision):
            fail("source revision", "must record the source under one of " + ", ".join(SOURCE_FIELDS)
                 + ", or as ledger.revision")
    for field in SOURCE_FIELDS:
        if field in record and (not isinstance(record[field], str) or not REVISION.fullmatch(record[field])):
            fail(field, "must be a hexadecimal source revision")
    for field in DATE_FIELDS:
        if field not in record:
            continue
        value = record[field]
        if not isinstance(value, str) or not DATE.fullmatch(value):
            fail(field, "must be an ISO 8601 date")
        try:
            datetime.date.fromisoformat(value)
        except ValueError:
            fail(field, "must be a real calendar date")
    for field, value in digest_fields(record):
        if not SHA256.fullmatch(value):
            fail(field, "must be a lowercase hexadecimal SHA-256")

    name, count = case_file(root / corpus)
    if name is not None and "casesSha256" in record:
        actual = hashlib.sha256((root / corpus / name).read_bytes()).hexdigest()
        if record["casesSha256"] != actual:
            fail("casesSha256", f"does not match {name}: recorded {record['casesSha256']}, file {actual}")

    cases = record.get("cases")
    if isinstance(cases, dict):
        named = cases.get("file")
        if named not in CASE_FILES or not (root / corpus / named).is_file():
            fail("cases.file", "must name the corpus's case file")
        if not integer(cases.get("count")):
            fail("cases.count", "must record the number of cases")
        digest = cases.get("sha256")
        if not isinstance(digest, str) or not SHA256.fullmatch(digest):
            fail("cases.sha256", "must be a lowercase hexadecimal SHA-256")
        actual = hashlib.sha256((root / corpus / named).read_bytes()).hexdigest()
        if digest != actual:
            fail("cases.sha256", f"does not match {named}: recorded {digest}, file {actual}")
    field, recorded = recorded_count(record)
    if count is not None and field is None:
        fail("cases", f"must record how many cases {name} holds ({count})")
    if count is not None and recorded != count:
        fail(field, f"records {recorded} cases, {name} holds {count}")

    for prefix, node in nested_objects(record):
        total_field, total = recorded_count(node)
        admitted, refused = node.get("accepted"), node.get("refused")
        if integer(total) and integer(admitted) and integer(refused) and admitted + refused != total:
            fail(f"{prefix}{total_field}", f"records {total} cases, accepted {admitted} plus refused "
                                          f"{refused} is {admitted + refused}")

    gaps = record.get("notCovered")
    if gaps is not None:
        if not isinstance(gaps, dict) or not gaps:
            fail("notCovered", "must map each uncovered topic to a sentence naming its issue")
        owners = gap_owners(components, corpus)
        if len(owners) != 1:
            fail("notCovered", f"the corpus must be cited by exactly one register entry to own its gaps, "
                               f"not {len(owners)}")
        limited = set(owners[0].get("limitations", {}).get("issues", []))
        for topic, sentence in gaps.items():
            if not topic.strip():
                fail("notCovered", "must name the uncovered topic")
            if not isinstance(sentence, str) or not sentence.strip():
                fail(f"notCovered.{topic}", "must say what the corpus does not cover")
            named = {int(number) for number in GAP_ISSUE.findall(sentence)}
            if not named:
                fail(f"notCovered.{topic}", "must name the issue that owns the gap")
            for number in sorted(named - limited):
                fail(f"notCovered.{topic}", f"names issue #{number}, which the register entry "
                                            f"'{owners[0]['id']}' does not list in limitations.issues")
    return relative


def validate_register(root, register, candidate=False):
    if set(register) != {"schemaVersion", "entries"} or register["schemaVersion"] != 1:
        raise ValueError("unsupported comparison register schema")
    required = {"id", "classification", "status", "scope", "assertion", "rationale",
                "owner", "tracking", "resolution", "evidence", "candidateRequired"}
    seen = set()
    if not register["entries"]:
        raise ValueError("comparison register has no entries")
    for row in register["entries"]:
        if set(row) != required:
            raise ValueError(f"invalid fields in coverage entry: {row.get('id')}")
        if row["id"] in seen:
            raise ValueError(f"duplicate coverage ID: {row['id']}")
        seen.add(row["id"])
        if row["classification"] not in KINDS or row["status"] not in STATES:
            raise ValueError(f"unknown classification/status: {row['id']}")
        for key in required - {"evidence", "candidateRequired"}:
            if not isinstance(row[key], str) or not row[key].strip():
                raise ValueError(f"empty {key}: {row['id']}")
        if (not isinstance(row["candidateRequired"], bool) or
                not isinstance(row["evidence"], list) or not row["evidence"] or
                any(not isinstance(path, str) for path in row["evidence"])):
            raise ValueError(f"missing candidate policy/evidence: {row['id']}")
        for path in row["evidence"]:
            relative_file(root, path)
        if row["status"] == "bounded" and not ISSUE.fullmatch(row["tracking"]):
            raise ValueError(f"a bounded entry must name the issue of its stronger contract: {row['id']}")
        if candidate and row["candidateRequired"] and row["status"] != "covered":
            raise ValueError(f"candidate has unresolved required coverage: {row['id']}")
    return len(seen)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--refresh-fixtures", action="store_true",
                        help="write reviewed fixture identities; never regenerate expected results")
    parser.add_argument("--candidate", action="store_true", help="also reject unresolved candidate requirements")
    args = parser.parse_args()
    root = args.root.resolve()
    try:
        if args.refresh_fixtures:
            target = root / INVENTORY
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(fixture_inventory(root), indent=2) + "\n", encoding="utf-8")
        # Provenance first: a defect in a record must be reported as that record's field, not as the
        # inventory hash it also moves.
        components = read_json(root / COMPONENTS)["components"]
        provenance = [validate_provenance(root, corpus, components)
                      for corpus in discover_corpora(root) if REFERENCE_CORPUS.fullmatch(corpus)]
        provenance = [relative for relative in provenance if relative]
        inventory = validate_inventory(root, read_json(root / INVENTORY))
        entries = validate_register(root, read_json(root / REGISTER), args.candidate)
    except (ValueError, OSError, KeyError, TypeError) as error:
        parser.exit(1, f"coverage check failed: {error}\n")
    print(f"coverage checked: {len(inventory['corpora'])} documented corpora, "
          f"{len(inventory['files'])} pinned files, {entries} comparison contracts, "
          f"{len(provenance)} reference provenance records")


if __name__ == "__main__":
    main()
