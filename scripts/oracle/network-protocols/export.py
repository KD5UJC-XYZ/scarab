#!/usr/bin/env python3
"""Export generated typed-protocol codec cases from the pinned upstream properties."""

from __future__ import annotations

import argparse
import difflib
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
from datetime import datetime, timezone

UPSTREAM_COMMIT = "2fb69829a19b5c04e4fb212bdd78fb63f9d2d776"
IMAGE = "scarab-pre-conway178-oracle:local"
CONTAINER_NAME = "scarab-v02-49-protocols-luna"
COMMAND_RESULTS: list[dict[str, object]] = []
COMMAND_LOG: Path | None = None


def checked(command: list[str], **kwargs: object) -> str:
    started = datetime.now(timezone.utc).isoformat()
    log_path = COMMAND_LOG.parent / (str(len(COMMAND_RESULTS)) + ".log") if COMMAND_LOG is not None else None
    record: dict[str, object] = {
        "argv": command, "startedAt": started, "endedAt": None,
        "returncode": None, "status": "running",
        "log": str(log_path) if log_path is not None else None,
    }
    if COMMAND_LOG is not None:
        COMMAND_RESULTS.append(record)
        COMMAND_LOG.write_text(json.dumps(COMMAND_RESULTS, indent=2) + "\n")

    output: list[str] = []
    with (log_path.open("w") if log_path is not None else open("/dev/null", "w")) as stream:
        stream.write("$ " + " ".join(command) + "\n\nCOMBINED OUTPUT\n")
        stream.flush()
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, **kwargs)
        assert process.stdout is not None
        for line in process.stdout:
            stream.write(line)
            stream.flush()
            output.append(line)
        returncode = process.wait()
    record.update({
        "endedAt": datetime.now(timezone.utc).isoformat(),
        "returncode": returncode,
        "status": "complete",
    })
    if COMMAND_LOG is not None:
        COMMAND_LOG.write_text(json.dumps(COMMAND_RESULTS, indent=2) + "\n")
    stdout = "".join(output)
    if returncode != 0:
        raise SystemExit(
            f"command failed ({returncode}): {' '.join(command)}\n"
            f"output:\n{stdout}"
        )
    return stdout.strip()


def inject(module: Path, import_anchor: str, code: str, imports: str) -> str:
    original = module.read_text()
    if original.count(import_anchor) != 1:
        raise SystemExit(f"observer import anchor changed in {module}: {import_anchor!r}")
    module.write_text(original.replace(import_anchor, import_anchor + imports) + code)
    return original


def prepare(source: Path, overlay: Path) -> dict[str, str]:
    actual = checked(["git", "-C", str(source), "rev-parse", "HEAD"])
    if actual != UPSTREAM_COMMIT:
        raise SystemExit(f"ouroboros-network pin mismatch: expected {UPSTREAM_COMMIT}, got {actual}")
    modules = {
        "KeepAlive": source / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/KeepAlive/Test.hs",
        "PeerSharing": source / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/PeerSharing/Test.hs",
        "ChainSync": source / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/ChainSync/Test.hs",
        "Main": source / "ouroboros-network/protocols/tests/Main.hs",
    }
    source_hashes = {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in modules.items()}
    shutil.copytree(source, overlay, dirs_exist_ok=True, ignore=shutil.ignore_patterns(".git", "dist-newstyle"))
    keep = overlay / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/KeepAlive/Test.hs"
    peer = overlay / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/PeerSharing/Test.hs"
    chain = overlay / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/ChainSync/Test.hs"
    keep_original = inject(
        keep,
        "import Test.QuickCheck\n",
        r'''

-- Disposable observer: use the exact AnyMessage generator and prop_codec_v2 above.
exportKeepAliveCases :: FilePath -> Int -> Int -> IO ()
exportKeepAliveCases path count seed = withFile path WriteMode $ \outputHandle ->
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 30 + (ordinal `mod` 21)
        testCase = unGen arbitrary (mkQCGen caseSeed) caseSize :: AnyMessage KeepAlive
    result <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 1000003), caseSize) }
      (once (prop_codec_v2 testCase))
    case testCase of
      AnyMessage msg -> do
        let Codec { encode } = codecKeepAlive_v2
              :: Codec KeepAlive CBOR.DeserialiseFailure IO ByteString
            raw = encode msg
            (state, sender, tag, cookie) = case msg of
              MsgKeepAlive (Cookie value)         -> ("client", "client", "request", show value)
              MsgKeepAliveResponse (Cookie value) -> ("server", "server", "response", show value)
              MsgDone                             -> ("client", "client", "done", "")
            outcome = if isSuccess result then "pass" else "upstream-failure"
        hPutStrLn outputHandle $ intercalate "\t"
          [ "keepalive-codec-v1", show caseSeed, show ordinal, show caseSize
          , state, sender, tag, cookie, hex (BL.toStrict raw), outcome
          ]
  where
    hex = concatMap (pad . (`showHex` "")) . BS.unpack
    pad [digit] = ['0', digit]
    pad digits = digits
''',
        "import Control.Monad (forM_)\n"
        "import Data.ByteString qualified as BS\n"
        "import Data.List (intercalate)\n"
        "import Numeric (showHex)\n"
        "import System.IO (withFile, IOMode (WriteMode), hPutStrLn)\n"
        "import Test.QuickCheck.Gen (unGen)\n"
        "import Test.QuickCheck.Random (mkQCGen)\n",
    )
    peer_original = inject(
        peer,
        "import Test.QuickCheck.Function (Fun, applyFun)\n",
        r'''

-- Disposable observer: use the exact AnyMessage generator and prop_codec_PeerSharing above.
exportPeerSharingCases :: FilePath -> Int -> Int -> IO ()
exportPeerSharingCases path count seed = withFile path WriteMode $ \outputHandle ->
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 30 + (ordinal `mod` 21)
        testCase = unGen arbitrary (mkQCGen caseSeed) caseSize :: AnyMessage (PeerSharing Int)
    result <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 1000003), caseSize) }
      (once (prop_codec_PeerSharing testCase))
    case testCase of
      AnyMessage msg -> do
        let Codec { encode } = codecPeerSharing @IO CBOR.encodeInt CBOR.decodeInt
            raw = encode msg
            (state, sender, tag, fields, adapterStatus) = case msg of
              MsgShareRequest (PeerSharingAmount amount) ->
                ("idle", "client", "request", show (fromIntegral amount :: Int), "candidate")
              MsgSharePeers peers ->
                ("busy", "server", "peers", show peers, "unsupported-int-addresses")
              MsgDone -> ("idle", "client", "done", "", "candidate")
            outcome = if isSuccess result then "pass" else "upstream-failure"
        hPutStrLn outputHandle $ intercalate "\t"
          [ "peersharing-codec-int-v1", show caseSeed, show ordinal, show caseSize
          , state, sender, tag, fields, adapterStatus, hex (BL.toStrict raw), outcome
          ]
  where
    hex = concatMap (pad . (`showHex` "")) . BS.unpack
    pad [digit] = ['0', digit]
    pad digits = digits
''',
        "import Control.Monad (forM_)\n"
        "import Data.ByteString qualified as BS\n"
        "import Data.List (intercalate)\n"
        "import Numeric (showHex)\n"
        "import System.IO (withFile, IOMode (WriteMode), hPutStrLn)\n"
        "import Test.QuickCheck.Gen (unGen)\n"
        "import Test.QuickCheck.Random (mkQCGen)\n"
        "import Test.QuickCheck (stdArgs, chatty, replay, quickCheckWithResult, once, isSuccess)\n",
    )
    chain_original = inject(
        chain,
        "import Test.QuickCheck hiding (Result)\n",
        r'''

-- Disposable observer: retain the real Serialised-block AnyMessage generator and properties.
exportChainSyncCases :: FilePath -> Int -> Int -> IO ()
exportChainSyncCases path count seed = withFile path WriteMode $ \outputHandle ->
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 30 + (ordinal `mod` 21)
        testCase = unGen arbitrary (mkQCGen caseSeed) caseSize
          :: AnyMessage ChainSync_Serialised_BlockHeader
    codecResult <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 1000003), caseSize) }
      (once (prop_codec_ChainSyncSerialised testCase))
    cborResult <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 2000003), caseSize) }
      (once (prop_codec_cbor_ChainSyncSerialised testCase))
    case testCase of
      AnyMessage (msg :: Message ChainSync_Serialised_BlockHeader st st') -> do
        let chainCodec = codecSerialised
              :: Codec (ChainSync (Serialised BlockHeader) (Point BlockHeader) (Tip BlockHeader))
                   S.DeserialiseFailure IO ByteString
            raw = encode chainCodec msg
            token = stateToken :: StateToken st
            (state, sender) = case token of
              SingIdle -> ("idle", "client")
              SingNext SingCanAwait -> ("next-can-await", "server")
              SingNext SingMustReply -> ("next-must-reply", "server")
              SingIntersect -> ("intersect", "server")
              SingDone -> ("done", "client")
            tag = case msg of
              MsgRequestNext -> "request-next"
              MsgAwaitReply -> "await-reply"
              MsgRollForward _ _ -> "roll-forward"
              MsgRollBackward _ _ -> "roll-backward"
              MsgFindIntersect _ -> "find-intersect"
              MsgIntersectFound _ _ -> "intersect-found"
              MsgIntersectNotFound _ -> "intersect-not-found"
              MsgDone -> "done"
            codecOutcome = if isSuccess codecResult then "pass" else "upstream-failure"
            cborOutcome = if isSuccess cborResult then "pass" else "upstream-failure"
        hPutStrLn outputHandle $ intercalate "\t"
          [ "chainsync-serialised-codec-v1", show caseSeed, show ordinal, show caseSize
          , state, sender, tag, hex (BL.toStrict raw), codecOutcome, cborOutcome
          ]
  where
    hex = concatMap (pad . (`showHex` "")) . BS.unpack
    pad [digit] = ['0', digit]
    pad digits = digits
''',
        "import Control.Monad (forM_)\n"
        "import Data.ByteString qualified as BS\n"
        "import Data.ByteString.Lazy qualified as BL\n"
        "import Data.List (intercalate)\n"
        "import Numeric (showHex)\n"
        "import System.IO (withFile, IOMode (WriteMode), hPutStrLn)\n"
        "import Test.QuickCheck.Gen (unGen)\n"
        "import Test.QuickCheck.Random (mkQCGen)\n"
        "",
    )
    chain_module = chain.read_text()
    chain_export_anchor = "module Ouroboros.Network.Protocol.ChainSync.Test (tests) where"
    if chain_module.count(chain_export_anchor) != 1:
        raise SystemExit("ChainSync observer module export anchor changed")
    chain.write_text(chain_module.replace(
        chain_export_anchor,
        "module Ouroboros.Network.Protocol.ChainSync.Test (tests, exportChainSyncCases) where",
    ))
    main = overlay / "ouroboros-network/protocols/tests/Main.hs"
    main_original = main.read_text()
    dispatch_imports = (
        "import Ouroboros.Network.Protocol.KeepAlive.Test qualified as KeepAlive (tests, exportKeepAliveCases)\n"
        "import Ouroboros.Network.Protocol.PeerSharing.Test qualified as PeerSharing (tests, exportPeerSharingCases)\n"
        "import Ouroboros.Network.Protocol.ChainSync.Test qualified as ChainSync (tests, exportChainSyncCases)\n"
        "import System.Environment (getArgs)\n"
    )
    main_text = main_original.replace(
        "import Ouroboros.Network.Protocol.KeepAlive.Test qualified (tests)",
        dispatch_imports,
    )
    main_text = main_text.replace(
        "import Ouroboros.Network.Protocol.PeerSharing.Test qualified (tests)\n", ""
    )
    main_text = main_text.replace(
        "import Ouroboros.Network.Protocol.ChainSync.Test qualified (tests)\n", ""
    )
    main_text = main_text.replace(
        "Ouroboros.Network.Protocol.KeepAlive.Test.tests", "KeepAlive.tests"
    ).replace("Ouroboros.Network.Protocol.PeerSharing.Test.tests", "PeerSharing.tests")
    main_text = main_text.replace("Ouroboros.Network.Protocol.ChainSync.Test.tests", "ChainSync.tests")
    main_text = main_text.replace(
        "main = defaultMain tests",
        "main = getArgs >>= \\args -> case args of\n"
        "  [\"--export-network-codecs\", dir, count, seed] -> do\n"
        "    KeepAlive.exportKeepAliveCases (dir <> \"/keepalive.tsv\") (read count) (read seed)\n"
        "    PeerSharing.exportPeerSharingCases (dir <> \"/peersharing.tsv\") (read count) (read seed)\n"
        "    ChainSync.exportChainSyncCases (dir <> \"/chainsync.tsv\") (read count) (read seed)\n"
        "  _ -> defaultMain tests",
    )
    main.write_text(main_text)
    return {
        "KeepAlive/Test.hs": keep_original,
        "PeerSharing/Test.hs": peer_original,
        "ChainSync/Test.hs": chain_original,
        "protocols/tests/Main.hs": main_original,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--count", type=int, default=24)
    parser.add_argument("--seed", type=int, default=490201)
    parser.add_argument("--probe-dir", type=Path, default=Path("/tmp/scarab/v02-49-protocol-probe"))
    args = parser.parse_args()
    if not 1 <= args.count <= 128:
        raise SystemExit("case count must be 1..128")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    args.probe_dir.mkdir(parents=True, exist_ok=True)
    global COMMAND_LOG
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    COMMAND_LOG = args.probe_dir / f"chain-sync-export-{run_id}-commands.json"
    COMMAND_LOG.write_text("[]\n")
    (args.probe_dir / "exports").mkdir(parents=True, exist_ok=True)
    overlay = args.probe_dir / "ouroboros-network-overlay"
    originals = prepare(args.source.resolve(), overlay)
    build = args.probe_dir / "builddir"
    build.mkdir(exist_ok=True)
    container = subprocess.run(["docker", "container", "inspect", CONTAINER_NAME], text=True, capture_output=True)
    if container.returncode != 0:
        checked([
            "docker", "create", "--network=none", "--name", CONTAINER_NAME, "--entrypoint", "tail",
            "-v", f"{overlay}:/source", "-v", f"{args.probe_dir}:/probe",
            "-w", "/source", IMAGE, "-f", "/dev/null",
        ])
    checked(["docker", "start", CONTAINER_NAME])
    for test_name in ("KeepAlive", "PeerSharing", "ChainSync"):
        checked([
            "docker", "exec", "-w", "/source", CONTAINER_NAME,
            "cabal", "test", "ouroboros-network:protocols-tests", "--offline",
            f"--test-options=--pattern={test_name}",
            "--builddir=/probe/builddir", "--jobs=2",
        ])
    binary_output = checked([
        "docker", "exec", "-w", "/source", CONTAINER_NAME,
        "cabal", "list-bin", "ouroboros-network:protocols-tests", "--offline",
        "--builddir=/probe/builddir",
    ])
    binary = next(
        (line.strip() for line in reversed(binary_output.splitlines()) if line.strip().startswith("/probe/builddir/")),
        None,
    )
    if binary is None:
        raise SystemExit(f"cabal list-bin did not return a build executable path: {binary_output!r}")
    checked([
        "docker", "exec", "-w", "/source", CONTAINER_NAME,
        binary, "--export-network-codecs", "/probe/exports",
        str(args.count), str(args.seed),
    ])
    patch = ""
    for name, original in originals.items():
        target = (
            overlay / "ouroboros-network" / name
            if name == "protocols/tests/Main.hs"
            else overlay / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol" / name
        )
        patch += "\n".join(difflib.unified_diff(
            original.splitlines(), target.read_text().splitlines(), fromfile="upstream/" + name, tofile="overlay/" + name
        )) + "\n"
    plan = (build / "cache/plan.json").read_bytes()
    plan_units = json.loads(plan)["install-plan"]
    manifests = []
    for name, upstream_test in [
        ("keepalive.tsv", "ouroboros-network:protocols-tests / KeepAlive / codec v2 / prop_codec_v2"),
        ("peersharing.tsv", "ouroboros-network:protocols-tests / PeerSharing / codec / prop_codec_PeerSharing"),
        ("chainsync.tsv", "ouroboros-network:protocols-tests / ChainSync / codecSerialised + codecSerialised cbor"),
    ]:
        raw = (args.probe_dir / "exports" / name).read_bytes()
        rows = [line.split("\t") for line in raw.decode().splitlines()]
        manifest = {
            "schema": "scarab-upstream-network-protocol-cases-v1",
            "source": "ouroboros-network",
            "sourceCommit": UPSTREAM_COMMIT,
            "upstreamTest": upstream_test,
            "seed": args.seed,
            "requestedCases": args.count,
            "exportedCases": len(rows),
            "upstreamPropertyOutcomes": {
                "both-properties-pass": sum(row[-2:] == ["pass", "pass"] for row in rows),
                "at-least-one-upstream-failure": sum(row[-2:] != ["pass", "pass"] for row in rows),
            } if name == "chainsync.tsv" else {
                "pass": sum(row[-1] == "pass" for row in rows),
                "upstream-failure": sum(row[-1] != "pass" for row in rows),
            },
            "dependencyPlanInstallUnits": len(plan_units),
            "dependencyPlanConfiguredUnits": sum(unit["type"] == "configured" for unit in plan_units),
            "dependencyPlanPreExistingUnits": sum(unit["type"] != "configured" for unit in plan_units),
            "dependencyPlanSha256": hashlib.sha256(plan).hexdigest(),
            "sourceFiles": {key: hashlib.sha256((args.source / key).read_bytes()).hexdigest() for key in [
                "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/KeepAlive/Test.hs",
                "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/PeerSharing/Test.hs",
                "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/ChainSync/Test.hs",
                "ouroboros-network/protocols/tests/Main.hs",
            ]},
            "observerPatchSha256": hashlib.sha256(patch.encode()).hexdigest(),
            "observerPatch": "disposable changes add deterministic exporter entrypoints using unchanged Arbitrary instances and property functions",
            "caseFile": name,
            "sha256": hashlib.sha256(raw).hexdigest(),
        }
        manifest["filteredUpstreamSuiteCommands"] = [
            result for result in COMMAND_RESULTS
            if "test" in result["argv"]
            and any("protocols-tests" in arg for arg in result["argv"])
        ]
        manifest["caseExportCommand"] = COMMAND_RESULTS[-1]
        manifest["execution"] = {"containerNetwork": "none", "cabalOffline": True}
        if name == "peersharing.tsv":
            manifest["adapterInputStatuses"] = {
                status: sum(row[-3] == status for row in rows)
                for status in sorted({row[-3] for row in rows})
            }
        if name == "chainsync.tsv":
            manifest["adapterInputStatuses"] = {
                "byConstructor": {
                    tag: sum(row[6] == tag for row in rows)
                    for tag in sorted({row[6] for row in rows})
                },
                "sourceStates": {
                    state: sum(row[4] == state for row in rows)
                    for state in sorted({row[4] for row in rows})
                },
            }
            manifest["upstreamPropertyOutcomesByProperty"] = {
                "prop_codec_ChainSyncSerialised": {
                    "pass": sum(row[-2] == "pass" for row in rows),
                    "upstream-failure": sum(row[-2] != "pass" for row in rows),
                },
                "prop_codec_cbor_ChainSyncSerialised": {
                    "pass": sum(row[-1] == "pass" for row in rows),
                    "upstream-failure": sum(row[-1] != "pass" for row in rows),
                },
            }
            manifest["propertyReplaySeedOffsets"] = {
                "prop_codec_ChainSyncSerialised": 1000003,
                "prop_codec_cbor_ChainSyncSerialised": 2000003,
            }
        (args.output_dir / name).write_bytes(raw)
        (args.output_dir / (name + ".manifest.json")).write_text(json.dumps(manifest, indent=2) + "\n")
        manifests.append(manifest)
    checked(["docker", "rm", "-f", CONTAINER_NAME])
    print(json.dumps(manifests, indent=2))


if __name__ == "__main__":
    main()
