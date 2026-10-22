#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Observe pinned native Mux closure and trailing-byte properties in an offline container."""

from __future__ import annotations

import argparse
import difflib
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from repo_paths import relative_argv  # noqa: E402

PIN = "2fb69829a19b5c04e4fb212bdd78fb63f9d2d776"
SOURCE = "network-mux/test/Test/Mux.hs"
SOURCE_SHA = "4db2e8967fa712334341d70f6f465655db337d54c471ccba1bcc8a9d743191a2"
HERE = Path(__file__).resolve().parent


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def replace_once(text: str, old: str, new: str) -> str:
    if text.count(old) != 1:
        raise ValueError(f"pinned observer anchor changed: {old!r}")
    return text.replace(old, new)


def instrument(original: str) -> str:
    if hashlib.sha256(original.encode()).hexdigest() != SOURCE_SHA:
        raise ValueError("pinned Test.Mux source hash mismatch")
    text = replace_once(original, "module Test.Mux (tests) where",
                        "module Test.Mux (tests, exportLifecycleCases) where")
    text = replace_once(text, "import Codec.Serialise (Serialise (..))",
                        "import Codec.Serialise (Serialise (..), serialise)")
    text = replace_once(text, "import Data.List (dropWhileEnd, nub)",
                        "import Data.List (dropWhileEnd, intercalate, nub)")
    text = replace_once(text, "import System.IOManager\n", "import System.IOManager\n"
                        "import System.IO (withFile, IOMode (WriteMode), hPutStrLn)\n"
                        "import Numeric (showHex)\n"
                        "import Test.QuickCheck.Gen (unGen)\n"
                        "import Test.QuickCheck.Random (mkQCGen)\n")
    begin = text.index("close_experiment\n    :: forall")
    body = text.index("close_experiment\n#ifdef", begin)
    signature = text[begin:body]
    observed = signature.replace("close_experiment", "close_experimentObserved", 1)
    observed = replace_once(observed, "    => Bool", "    => (Maybe (Either [resp] [resp]) -> m ())\n    -> Bool")
    text = text[:begin] + signature + "close_experiment = close_experimentObserved (\\_ -> pure ())\n\n" + observed + text[body:]
    text = replace_once(text, "close_experiment\n#ifdef", "close_experimentObserved observe\n#ifdef")
    text = replace_once(text, "            case (fault, resClient, resServer) of",
                        "            observe (either (const Nothing) Just resClient)\n"
                        "            case (fault, resClient, resServer) of")
    return text + "\n" + (HERE / "lifecycle-observer.hs").read_text()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--probe-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--image", default="scarab-v02-49-protocols-cache:peer-acceptance-20261019")
    parser.add_argument("--container", default="scarab-49-mux-lifecycle")
    parser.add_argument("--cache-build", type=Path)
    parser.add_argument("--count", type=int, default=24)
    parser.add_argument("--seed", type=int, default=511001)
    args = parser.parse_args()
    if not 3 <= args.count <= 128:
        parser.error("count must be 3..128")
    probe, source = args.probe_dir.resolve(), args.source.resolve()
    probe.mkdir(parents=True, exist_ok=True)
    commands = []

    def run(argv: list[str]) -> str:
        log = probe / f"command-{len(commands)}.log"
        with log.open("w") as output:
            result = subprocess.run(argv, stdout=output, stderr=subprocess.STDOUT, text=True)
        commands.append({"argv": relative_argv(argv), "returncode": result.returncode, "logSha256": sha(log)})
        (probe / "commands.json").write_text(json.dumps(commands, indent=2) + "\n")
        if result.returncode:
            raise RuntimeError(f"command failed ({result.returncode}); see {log}")
        return log.read_text().strip()

    if run(["git", "-C", str(source), "rev-parse", "HEAD"]) != PIN:
        raise ValueError("upstream pin mismatch")
    overlay = probe / "source"
    if overlay.exists():
        raise ValueError("use a fresh probe directory")
    archive = probe / "source.tar"
    run(["git", "-C", str(source), "archive", "--format=tar", "-o", str(archive), PIN])
    overlay.mkdir()
    run(["tar", "-xf", str(archive), "-C", str(overlay)])
    archive.unlink()
    original = (overlay / SOURCE).read_text()
    (overlay / SOURCE).write_text(instrument(original))
    main_path = "network-mux/test/Main.hs"
    original_main = (overlay / main_path).read_text()
    modified_main = replace_once(original_main, "import Test.Mux qualified (tests)",
                                 "import Test.Mux qualified (tests, exportLifecycleCases)\nimport System.Environment (getArgs)")
    modified_main = replace_once(modified_main, "main = defaultMain tests",
                                 'main = getArgs >>= \\args -> case args of\n'
                                 '  ["--export-lifecycle", path, count, seed] -> Test.Mux.exportLifecycleCases path (read count) (read seed)\n'
                                 '  _ -> defaultMain tests')
    (overlay / main_path).write_text(modified_main)
    patch = "".join("".join(difflib.unified_diff(old.splitlines(True), new.splitlines(True),
                                              fromfile="a/" + name, tofile="b/" + name))
                    for name, old, new in ((SOURCE, original, (overlay / SOURCE).read_text()),
                                           (main_path, original_main, modified_main)))
    (probe / "executed-observer.patch").write_text(patch)
    project = probe / "reference.project"
    # Exact full upstream project, with local packages resolved from its disposable source root.
    project.write_text((overlay / "cabal.project").read_text())
    build = probe / "builddir"
    if args.cache_build:
        shutil.copytree(args.cache_build, build)
    created = False
    try:
        run(["docker", "create", "--network=none", "--name", args.container,
             "--entrypoint", "tail", "-v", f"{overlay}:/source", "-v", f"{probe}:/probe",
             "-w", "/source", args.image, "-f", "/dev/null"])
        created = True
        run(["docker", "start", args.container])
        image = run(["docker", "inspect", args.container, "--format", "{{.Image}}"])
        base = ["docker", "exec", "-w", "/source", args.container]
        run(base + ["cabal", "build", "--offline", "network-mux:test:test", "--builddir=/probe/builddir", "--jobs=1"])
        binary = run(base + ["cabal", "list-bin", "network-mux:test:test", "--builddir=/probe/builddir"]).splitlines()[-1]
        run(base + [binary, "--export-lifecycle", "/probe/lifecycle.tsv", str(args.count), str(args.seed)])
        # These are genuine upstream API-specific properties, retained as upstream evidence only;
        # Scarab is not required to introduce their mini-protocol activation API.
        for pattern, expected_tests in (("mux start and stop", 1), ("mux restart", 1), ("pure exception", 2)):
            result = run(base + [binary, "--pattern", pattern,
                                "--quickcheck-tests", "24", "--quickcheck-max-size", "12",
                                "--quickcheck-replay", str(args.seed)])
            if f"All {expected_tests} tests passed" not in result:
                raise ValueError(f"expected {expected_tests} executed upstream tests for {pattern}")
        output = probe / "lifecycle.tsv"
        rows = [line.split("\t") for line in output.read_text().splitlines()]
        if len(rows) != args.count * 2:
            raise ValueError("missing native observations")
        for i, row in enumerate(rows):
            expected = "mux-close-v1" if i % 2 == 0 else "mux-trailing-v1"
            if row[0] != expected or len(row) != (14 if i % 2 == 0 else 9):
                raise ValueError("invalid exported schema")
            if any(verdict not in ("pass", "upstream-failure") for verdict in row[-2:]):
                raise ValueError("invalid native verdict")
        manifest = {
            "schema": "scarab-upstream-mux-lifecycle-v1", "sourceCommit": PIN,
            "sourcePath": SOURCE, "sourceFileSha256": SOURCE_SHA,
            "seed": args.seed, "pairs": args.count, "sizeRule": "8 + ordinal mod 9",
            "function": "fn accumulator request = (accumulator + request, accumulator - request)",
            "faultRule": "CleanShutdown, CloseOnWrite, CloseOnRead cyclically; original FaultInjection constructors",
            "observations": "actual simulated client result, original request/response CBOR; original close/trailing IO and IOSim verdicts",
            "sha256": sha(output), "observerPatchSha256": sha(probe / "executed-observer.patch"),
            "observerSourceSha256": sha(HERE / "lifecycle-observer.hs"),
            "patchedSourceSha256": sha(overlay / SOURCE), "patchedMainSha256": sha(overlay / main_path),
            "executableSha256": run(base + ["sha256sum", binary]).split()[0],
            "planSha256": sha(build / "cache/plan.json"), "projectSha256": sha(overlay / "cabal.project"),
            "image": args.image, "imageDigest": image, "network": "none", "commands": commands.copy(),
            "limits": ["Bounded native lifecycle cases, not all seeds or exact scheduler traces",
                       "No native Haskell restart/activation API equivalence claim for Scarab",
                       "Scarab cancellation/resource finalizers have additional local controlled tests"],
        }
        args.output_dir.mkdir(parents=True, exist_ok=True)
        shutil.copy2(output, args.output_dir / "lifecycle.tsv")
        shutil.copy2(probe / "executed-observer.patch", args.output_dir / "observer.patch")
        (args.output_dir / "lifecycle.tsv.manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    finally:
        if created:
            run(["docker", "rm", "-f", args.container])


if __name__ == "__main__":
    main()
