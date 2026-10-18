#!/usr/bin/env python3
"""Export a bounded corpus from the pinned upstream `prop_sdu_codec` generator.

The exporter patches only a disposable copy of the pinned checkout. The copied Haskell test
retains its original `Arbitrary SDUCodecCase` instance and property; this is not a second vector
generator or a replacement for the upstream suite.
"""

from __future__ import annotations

import argparse
import difflib
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

UPSTREAM_COMMIT = "2fb69829a19b5c04e4fb212bdd78fb63f9d2d776"
TEST_SOURCE_SHA256 = "4db2e8967fa712334341d70f6f465655db337d54c471ccba1bcc8a9d743191a2"
IMAGE = "scarab-pre-conway178-oracle:local"
CONTAINER_NAME = "scarab-v02-49-mux-luna"


def checked(command: list[str], **kwargs: object) -> str:
    result = subprocess.run(command, text=True, capture_output=True, **kwargs)
    if result.returncode != 0:
        raise SystemExit(
            f"command failed ({result.returncode}): {' '.join(command)}\n"
            f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
        )
    return result.stdout.strip()


def prepare(source: Path, overlay: Path) -> None:
    actual = checked(["git", "-C", str(source), "rev-parse", "HEAD"])
    if actual != UPSTREAM_COMMIT:
        raise SystemExit(f"ouroboros-network pin mismatch: expected {UPSTREAM_COMMIT}, got {actual}")
    test_source = source / "network-mux/test/Test/Mux.hs"
    digest = hashlib.sha256(test_source.read_bytes()).hexdigest()
    if digest != TEST_SOURCE_SHA256:
        raise SystemExit(f"Test.Mux.hs changed: expected {TEST_SOURCE_SHA256}, got {digest}")

    shutil.copytree(
        source,
        overlay,
        dirs_exist_ok=True,
        ignore=shutil.ignore_patterns(".git", "dist-newstyle"),
    )
    module = overlay / "network-mux/test/Test/Mux.hs"
    original_module = (source / "network-mux/test/Test/Mux.hs").read_text()
    text = original_module
    old_header = "module Test.Mux (tests) where"
    if text.count(old_header) != 1:
        raise SystemExit("upstream Test.Mux export header changed")
    text = text.replace(old_header, "module Test.Mux (tests, exportSduCodecCases) where")
    imports = "import System.IO (withFile, IOMode (WriteMode), hPutStrLn)\n"
    marker = "import System.IOManager\n"
    if text.count(marker) != 1:
        raise SystemExit("upstream Test.Mux import anchor changed")
    text = text.replace(marker, marker + imports)
    code = r'''

-- Added by Scarab's disposable pinned-source adapter overlay. Cases come from the exact upstream
-- SDUCodecCase Arbitrary instance above and each is checked by the exact upstream property.
exportSduCodecCases :: FilePath -> Int -> Int -> IO ()
exportSduCodecCases path count seed = withFile path WriteMode $ \outputHandle ->
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 30 + (ordinal `mod` 21)
        testCase = unGen arbitrary (mkQCGen caseSeed) caseSize :: SDUCodecCase
    result <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 1000003), caseSize) }
      (once (prop_sdu_codec testCase))
    let SDUCodecCase ts num dir len = testCase
        payload = BL.replicate (fromIntegral len) 0xa5
        sdu = Mx.SDU
          { Mx.msHeader = Mx.SDUHeader
              { Mx.mhTimestamp = Mx.RemoteClockModel ts
              , Mx.mhNum = Mx.MiniProtocolNum num
              , Mx.mhDir = dir
              , Mx.mhLength = len
              }
          , Mx.msBlob = payload
          }
        encoded = Mx.encodeSDU sdu
        reference = Bin.runPut (do
          Bin.putWord32be ts
          Bin.putWord16be $ case dir of
            Mx.InitiatorDir -> num
            Mx.ResponderDir -> num .|. 0x8000
          Bin.putWord16be len) `BL.append` payload
        outcome = if isSuccess result then "pass" else "upstream-failure"
        row = intercalate "\t"
          [ "sdu-codec-v1", show caseSeed, show ordinal, show caseSize
          , show ts, show num, if dir == Mx.InitiatorDir then "initiator" else "responder"
          , show len, hex (BL.toStrict encoded), hex (BL.toStrict reference), outcome
          ]
    hPutStrLn outputHandle row
  where
    hex = concatMap (pad . (`showHex` "")) . BS.unpack
    pad [digit] = ['0', digit]
    pad digits = digits
'''
    if "import Test.QuickCheck.Random (mkQCGen)" not in text:
        text = text.replace(
            "import Test.QuickCheck hiding ((.&.))\n",
            "import Test.QuickCheck hiding ((.&.))\n"
            "import Test.QuickCheck.Gen (unGen)\n"
            "import Test.QuickCheck.Random (mkQCGen)\n"
        )
    if "import Data.ByteString qualified as BS" not in text:
        text = text.replace("import Data.Bits\n", "import Data.Bits\nimport Data.ByteString qualified as BS\n")
    if "import Data.List (dropWhileEnd, nub)" in text:
        text = text.replace("import Data.List (dropWhileEnd, nub)", "import Data.List (dropWhileEnd, intercalate, nub)")
    else:
        raise SystemExit("upstream Data.List import anchor changed")
    if "import Numeric (showHex)" not in text:
        text = text.replace("import Data.Maybe (fromMaybe, isNothing)\n", "import Data.Maybe (fromMaybe, isNothing)\nimport Numeric (showHex)\n")
    text += code
    module.write_text(text)

    main = overlay / "network-mux/test/Main.hs"
    original_main = (source / "network-mux/test/Main.hs").read_text()
    main_text = original_main
    main_text = main_text.replace("import Test.Mux qualified (tests)", "import Test.Mux qualified (tests, exportSduCodecCases)\nimport System.Environment (getArgs)")
    main_text = main_text.replace(
        "main :: IO ()\nmain = defaultMain tests",
        "main :: IO ()\nmain = getArgs >>= \\args -> case args of\n"
        "  [\"--export-sdu-codec\", path, count, seed] -> Test.Mux.exportSduCodecCases path (read count) (read seed)\n"
        "  _ -> defaultMain tests",
    )
    main.write_text(main_text)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True, help="read-only pinned ouroboros-network checkout")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--count", type=int, default=40)
    parser.add_argument("--seed", type=int, default=490049)
    parser.add_argument("--probe-dir", type=Path, default=Path("/tmp/scarab/v02-49-probe"))
    args = parser.parse_args()
    if not 1 <= args.count <= 256:
        raise SystemExit("case count must be 1..256")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.probe_dir.mkdir(parents=True, exist_ok=True)
    overlay = args.probe_dir / "ouroboros-network-overlay"
    original_module = (args.source / "network-mux/test/Test/Mux.hs").read_text()
    original_main = (args.source / "network-mux/test/Main.hs").read_text()
    prepare(args.source.resolve(), overlay)
    build = args.probe_dir / "builddir"
    build.mkdir(exist_ok=True)
    inspect = subprocess.run(
        ["docker", "container", "inspect", CONTAINER_NAME],
        text=True,
        capture_output=True,
    )
    if inspect.returncode != 0:
        checked([
        "docker", "create", "--name", CONTAINER_NAME, "--entrypoint", "sh",
        "-v", f"{overlay}:/source:ro", "-v", f"{args.probe_dir}:/probe",
        "-w", "/source", IMAGE, "-lc", "while :; do sleep 3600; done",
        ])
    checked(["docker", "start", CONTAINER_NAME])
    command = (
        "export PATH=/opt/ghc/9.10.3/bin:$PATH; "
        "cabal test network-mux:test --test-options=--pattern=sdu "
        "--builddir=/probe/builddir --jobs=2 && "
        "cabal list-bin network-mux:test:test --builddir=/probe/builddir > /probe/test-bin && "
        "$(cat /probe/test-bin) --export-sdu-codec /probe/cases.tsv "
        f"{args.count} {args.seed}"
    )
    checked(["docker", "exec", "-w", "/source", CONTAINER_NAME, "sh", "-lc", command])
    raw = (args.probe_dir / "cases.tsv").read_bytes()
    args.output.write_bytes(raw)
    plan = (build / "cache/plan.json").read_bytes()
    plan_json = json.loads(plan)
    log_path = next(build.glob("build/*/ghc-9.10.3/network-mux-*/t/test/test/*-test.log"))
    test_log = log_path.read_text()
    rows = [line.split("\t") for line in raw.decode("utf-8").splitlines()]
    status_counts: dict[str, int] = {}
    for row in rows:
        status_counts[row[-1]] = status_counts.get(row[-1], 0) + 1
    observer_patch = "\n".join(
        difflib.unified_diff(
            original_module.splitlines(),
            (overlay / "network-mux/test/Test/Mux.hs").read_text().splitlines(),
            fromfile="upstream/Test.Mux.hs",
            tofile="overlay/Test.Mux.hs",
        )
    ) + "\n" + "\n".join(
        difflib.unified_diff(
            original_main.splitlines(),
            (overlay / "network-mux/test/Main.hs").read_text().splitlines(),
            fromfile="upstream/Main.hs",
            tofile="overlay/Main.hs",
        )
    )
    manifest = {
        "schema": "scarab-upstream-mux-sdu-v1",
        "source": "ouroboros-network",
        "sourceCommit": UPSTREAM_COMMIT,
        "sourcePath": "network-mux/test/Test/Mux.hs",
        "sourceFileSha256": TEST_SOURCE_SHA256,
        "upstreamTest": "network-mux:test / Test.Mux.tests / sdu codec / prop_sdu_codec",
        "generator": "Arbitrary SDUCodecCase; each row uses mkQCGen(seed + ordinal), size=30 + ordinal mod 21",
        "seed": args.seed,
        "requestedCases": args.count,
        "exportedCases": len(raw.decode("utf-8").splitlines()),
        "upstreamGeneratedPropertyResults": status_counts,
        "upstreamTestSuiteResult": "pass" if "Test suite test: PASS" in test_log else "upstream-failure",
        "haskellTarget": "GHC 9.10.3, network-mux:test, Tasty --pattern=sdu",
        "dependencyPlanSha256": hashlib.sha256(plan).hexdigest(),
        "dependencyPlanUnits": len(plan_json.get("install-plan", [])),
        "observerPatchSha256": hashlib.sha256(observer_patch.encode("utf-8")).hexdigest(),
        "observerPatch": "two disposable source changes: export actual upstream generator/property; add exporter CLI dispatch",
        "file": args.output.name,
        "sha256": hashlib.sha256(raw).hexdigest(),
    }
    args.output.with_suffix(args.output.suffix + ".manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    checked(["docker", "rm", "-f", CONTAINER_NAME])


if __name__ == "__main__":
    main()
