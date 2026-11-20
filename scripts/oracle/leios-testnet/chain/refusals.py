#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run the chain context harness of the Leios testnet build on the cases no recorded transaction covers, and record
its answers (#85).

The cases are `leios_context_refusal_cases.py`'s, in the chain context fixture's environment. The image is the one
`run.py` binds, measured the same way (`run.image_binding`), and must be the build the chain context fixture's
provenance accepted, so both fixtures are answers of one build; `run.py` and its fixture are not changed. Unlike
the chain's own transactions, these cases may be refused: the answers are recorded as the harness writes them,
and the test that reads them says which verdict each case must have. `--verify-only` checks the retained fixture
without Docker.

    python3 -I scripts/oracle/leios-testnet/leios_context_refusal_cases.py > fixtures/reference/leios-testnet-context-refusals/cases.json
    python3 -I scripts/oracle/leios-testnet/chain/refusals.py --evidence <a directory outside the checkout>
    python3 -I scripts/oracle/leios-testnet/chain/refusals.py --verify-only
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


RUN = module("chain_run", HERE / "run.py")
DIRECTORY = ROOT / "fixtures/reference/leios-testnet-context-refusals"
GENERATOR = HERE.parent / "leios_context_refusal_cases.py"


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def runners():
    """The digests of this runner, of `run.py` whose measurement it uses, of the CBOR reader, and of the cases'
    generator with the helpers it imports (`leios_context_cases.py` and the CBOR encoders of
    `conway-decoding/cases.py`), by repository path."""
    paths = (HERE / "refusals.py", HERE / "run.py", HERE.parent / "leios_cbor.py", GENERATOR,
             HERE.parent / "leios_context_cases.py", HERE.parents[1] / "conway-decoding" / "cases.py")
    return {str(path.relative_to(ROOT)): sha256(path) for path in paths}


# A context as the harness writes it: the hex of its bytes, whole bytes only.
HEX = re.compile(r"(?:[0-9a-f]{2})+")


def level(collection, where):
    """A level's collection as the harness writes it: collected with a list of contexts (empty for a level that
    runs no script), each the hex of whole bytes, or refused with the ledger's failure, a string that is not blank;
    anything else, a refusal without its failure included, is refused."""
    if not isinstance(collection, dict):
        raise ValueError(f"{where}: no collection")
    if collection.get("collected") is True:
        contexts = collection.get("contexts")
        if set(collection) != {"collected", "contexts"} or not isinstance(contexts, list):
            raise ValueError(f"{where}: a collection that lists no contexts")
        if not all(isinstance(c, str) and HEX.fullmatch(c) for c in contexts):
            raise ValueError(f"{where}: a context that is not the hex of whole bytes")
        return None
    failure = collection.get("failure")
    if collection.get("collected") is not False or set(collection) != {"collected", "failure"} or not isinstance(
            failure, str) or not failure.strip():
        raise ValueError(f"{where}: a refused collection without the ledger's failure")
    return failure


def validate(fixtures):
    """A retained fixture: its files are the ones its provenance binds, every case answered in its order, each
    transaction decoded, its top level and each of its sub-transactions collected with their contexts or refused
    with the ledger's failure (the top level's also the answer's `failure`), and the provenance binds the build
    the chain context fixture accepted, the current harness, runners and generator."""
    provenance = json.loads((fixtures / "provenance.json").read_text())
    for name, file in (("cases", "cases.json"), ("results", "results.json")):
        record = provenance[name]
        if record.get("file") != file or sha256(fixtures / file) != record.get("sha256"):
            raise ValueError(f"changed fixture file, or a record that does not name it: {file}")
    if provenance.get("harness") != {"source": str((HERE / "Main.hs").relative_to(ROOT)),
                                     "sha256": sha256(HERE / "Main.hs")}:
        raise ValueError("the provenance's harness is not the repository's chain/Main.hs")
    if provenance.get("ledgerSourceCommit") != RUN.ledger_pin() or provenance.get("plutusSourceCommit") != RUN.PLUTUS:
        raise ValueError("the provenance's revisions are not the pinned ones")
    cases = json.loads((fixtures / "cases.json").read_text())
    results = json.loads((fixtures / "results.json").read_text())
    if not cases["cases"] or [c["name"] for c in cases["cases"]] != [r["name"] for r in results]:
        raise ValueError("the results are not those of the cases")
    chain = json.loads((RUN.DIRECTORY / "cases.json").read_text())
    if any(cases[key] != chain[key] for key in ("protocolVersion", "costModels", "systemStart", "epochSize",
                                                 "slotLength")):
        raise ValueError("the cases' environment is not the chain context fixture's")
    for case, r in zip(cases["cases"], results):
        result = r.get("result", {})
        if r.get("kind") != "context" or result.get("accepted") is not True:
            raise ValueError(f"{case['name']}: not a decoded transaction's context answer")
        subtxs = result.get("subtxs")
        if not isinstance(subtxs, list) or len(subtxs) != RUN.CBOR.subtransactions(bytes.fromhex(case["bytes"])):
            raise ValueError(f"{case['name']}: not one collection for each sub-transaction")
        if level(result.get("top"), f"{case['name']}, top level") != result.get("failure"):
            raise ValueError(f"{case['name']}: the top level's failure is not the answer's")
        for n, sub in enumerate(subtxs):
            level(sub, f"{case['name']}, sub-transaction {n}")
    counts = (provenance["cases"].get("count"), provenance["results"].get("topCollected"),
              provenance["results"].get("topRefused"))
    refused = sum(1 for r in results if r["result"].get("failure") is not None)
    if counts != (len(cases["cases"]), len(results) - refused, refused):
        raise ValueError(f"the provenance counts {counts}; the files hold {len(cases['cases'])} cases, "
                         f"{refused} refused at the top level")
    oracle = provenance.get("oracle")
    if not isinstance(oracle, dict):
        raise ValueError("the provenance binds no oracle image")
    # As the chain verifier checks its own (`run.validate`): the image's complete file map, each a SHA-256, the
    # repository's harness, package and cabal project among them, and the executable's digest the one recorded.
    files = RUN.build_files(oracle.get("imageFilesSha256"), "provenance.json")
    accepted = RUN.accepted_build(RUN.DIRECTORY)
    if (RUN.IMAGE_ID.fullmatch(str(oracle.get("imageId"))) is None
            or {path: files[path] for path in RUN.IMAGE_BUILD} != accepted
            or oracle.get("oracleBinarySha256") != files[RUN.IMAGE_BINARY]
            or any(files[path] != sha256(source) for path, source in RUN.REPOSITORY_FILES.items())
            or oracle.get("pinnedSources") != {commit: "unchanged" for commit in RUN.pinned()}
            or oracle.get("network") != "none" or oracle.get("runnerSha256") != runners()):
        raise ValueError("the provenance does not bind the chain fixture's accepted build, its executable, the "
                         "repository's harness, the pinned sources and the current runners and generator")
    return cases, results


def record(fixtures, evidence, owner):
    """The cases through the verified image, without network: its answers become `results.json` (a copy stays in
    `evidence`), and the provenance binds them and the image."""
    image_id = RUN.resolve(RUN.IMAGE)
    binding = RUN.image_binding(image_id, owner, RUN.accepted_build(RUN.DIRECTORY))
    evidence.mkdir(parents=True, exist_ok=True)
    raw = evidence / "results.json"
    with raw.open("wb") as out:
        subprocess.run(RUN.container(image_id, owner, "-v", f"{fixtures.resolve()}:/w:ro") + ["/w/cases.json"],
                       stdout=out, check=True)
    (fixtures / "results.json").write_bytes(raw.read_bytes())
    results = json.loads(raw.read_text())
    refused = sum(1 for r in results if r["result"].get("failure") is not None)
    provenance = json.loads((fixtures / "provenance.json").read_text())
    provenance["cases"] = {"file": "cases.json", "count": len(results), "sha256": sha256(fixtures / "cases.json")}
    provenance["results"] = {"file": "results.json", "sha256": sha256(fixtures / "results.json"),
                             "topCollected": len(results) - refused, "topRefused": refused}
    provenance["harness"] = {"source": str((HERE / "Main.hs").relative_to(ROOT)), "sha256": sha256(HERE / "Main.hs")}
    provenance["oracle"] = {
        "imageId": image_id,
        "imageFilesSha256": binding["imageFilesSha256"],
        "oracleBinarySha256": binding["imageFilesSha256"][RUN.IMAGE_BINARY],
        "pinnedSources": binding["pinnedSources"],
        "runnerSha256": runners(),
        "network": "none",
    }
    (fixtures / "provenance.json").write_text(json.dumps(provenance, indent=1) + "\n")
    validate(fixtures)
    print(json.dumps({"imageId": image_id, "results": provenance["results"]}))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--fixtures", type=Path, default=DIRECTORY)
    parser.add_argument("--evidence", type=Path, help="a directory outside the checkout for the image's answers")
    parser.add_argument("--owner", default="leios-context-refusals")
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    if args.verify_only:
        cases, _ = validate(args.fixtures)
        print(f"PASS: {len(cases['cases'])} cases, each answered; fixture files, build, harness and runners bound")
    elif args.evidence is None:
        parser.error("--evidence is required to record")
    else:
        record(args.fixtures, args.evidence, args.owner)


if __name__ == "__main__":
    main()
