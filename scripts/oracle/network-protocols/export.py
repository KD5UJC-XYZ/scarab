#!/usr/bin/env python3
"""Export generated typed-protocol codec cases from the pinned upstream properties."""

from __future__ import annotations

import argparse
import difflib
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
from datetime import datetime, timezone

UPSTREAM_COMMIT = "2fb69829a19b5c04e4fb212bdd78fb63f9d2d776"
IMAGE = os.environ.get("SCARAB_PROTOCOL_REFERENCE_IMAGE", "scarab-pre-conway178-oracle:local")
CONTAINER_NAME = os.environ.get("SCARAB_PROTOCOL_REFERENCE_CONTAINER", "scarab-v02-49-protocols-luna")
COMMAND_RESULTS: list[dict[str, object]] = []
COMMAND_LOG: Path | None = None

PROPERTY_COLUMNS: dict[str, tuple[tuple[str, int], ...]] = {
    "keepalive.tsv": (("prop_codec_v2", 9), ("prop_codec_v2_valid_cbor", 10)),
    "keepalive-behavior.tsv": (
        ("prop_direct", 7),
        ("prop_connect", 8),
        ("prop_channel_ST", 9),
    ),
    "blockfetch-behavior.tsv": (
        ("prop_direct", 11),
        ("prop_connect", 12),
        ("prop_channel_ST", 13),
    ),
    "peersharing.tsv": (
        ("prop_codec_PeerSharing", 10),
        ("prop_codec_cbor", 11),
        ("prop_codec_valid_cbor", 12),
        ("prop_codec_splits2", 13),
        ("prop_codec_splits3", 14),
    ),
    "localsq.tsv": (
        ("prop_codec_LocalStateQuery", 8),
        ("prop_codec_cbor", 9),
        ("prop_codec_valid_cbor", 10),
    ),
    "localtxsubmission.tsv": (
        ("prop_codec", 8),
        ("prop_codec_cbor", 9),
        ("prop_codec_valid_cbor", 10),
    ),
    "localtxmonitor.tsv": (
        ("prop_codecM_LocalTxMonitor", 8),
        ("prop_codec_cborM_LocalTxMonitor", 9),
        ("prop_codecIdM_LocalTxMonitor", 10),
        ("prop_codec_valid_cbor_encoding_LocalTxMonitor", 11),
    ),
    "txsubmission2.tsv": (
        ("prop_codec", 8),
        ("prop_codec_cbor", 9),
        ("prop_codec_id", 10),
        ("prop_codec_valid_cbor", 11),
    ),
    "localtxsubmission-annotated.tsv": (
        ("prop_anncodec", 8),
        ("prop_anncodec_splits2", 9),
        ("prop_anncodec_splits3", 10),
    ),
    "txsubmission2-annotated.tsv": (
        ("prop_anncodec", 8),
        ("prop_anncodec_splits2", 9),
        ("prop_anncodec_splits3", 10),
    ),
}


def summarize_property_outcomes(
    rows: list[list[str]], properties: tuple[tuple[str, int], ...]
) -> dict[str, object]:
    per_property = {
        property_name: {
            "pass": sum(row[column] == "pass" for row in rows),
            "upstream-failure": sum(row[column] != "pass" for row in rows),
        }
        for property_name, column in properties
    }
    all_pass = sum(
        all(row[column] == "pass" for _, column in properties) for row in rows
    )
    return {
        "upstreamPropertyOutcomesByProperty": per_property,
        "upstreamPropertyOutcomes": {
            "all-recorded-properties-pass": all_pass,
            "at-least-one-recorded-property-failure": len(rows) - all_pass,
        },
    }


def validate_pipe_demo_export(
    cases: list[list[str]], blocks: list[list[str]], updates: list[list[str]], requested: int
) -> dict[str, int]:
    if len(cases) != requested or any(len(row) != 14 for row in cases):
        raise ValueError("pipe demo case export does not match requested count/schema")
    if any(row[0] != "ouroboros-pipe-demo-v1" for row in cases):
        raise ValueError("pipe demo case export contains an unexpected schema tag")
    if any(row[0] not in ("initial", "final", "candidate") for row in blocks):
        raise ValueError("pipe demo block export contains an unexpected row kind")
    if any(len(row) != (5 if row[0] in ("initial", "final") else 4) for row in blocks):
        raise ValueError("pipe demo block export contains an invalid row shape")
    if any(len(row) != 6 for row in updates):
        raise ValueError("pipe demo update export contains an invalid row shape")
    for ordinal, row in enumerate(cases):
        if int(row[2]) != ordinal or int(row[1]) != int(cases[0][1]) + ordinal:
            raise ValueError("pipe demo generated case ordinals/seeds are not contiguous")
        if int(row[3]) != 30 + ordinal % 21:
            raise ValueError("pipe demo generated case size differs from the recorded generator rule")
        if row[4] not in ("pass", "upstream-failure"):
            raise ValueError(f"invalid upstream pipe demo outcome: {row[4]!r}")
        own_blocks = [item for item in blocks if int(item[1]) == ordinal and item[0] == "initial"]
        own_final = [item for item in blocks if int(item[1]) == ordinal and item[0] == "final"]
        own_candidates = [item for item in blocks if int(item[1]) == ordinal and item[0] == "candidate"]
        own_updates = [item for item in updates if int(item[0]) == ordinal]
        if (
            len(own_blocks) != int(row[5])
            or len(own_final) != int(row[8])
            or len(own_candidates) != int(row[6])
            or len(own_updates) != int(row[7])
        ):
            raise ValueError(f"pipe demo generated row counts disagree for ordinal {ordinal}")
        if int(row[13]) < 0 or int(row[13]) > int(row[7]):
            raise ValueError(f"pipe demo target stop ordinal is outside generated updates for {ordinal}")
        for label, rows in (("initial block", own_blocks), ("final block", own_final), ("candidate", own_candidates)):
            if [int(item[2]) for item in rows] != list(range(len(rows))):
                raise ValueError(f"pipe demo {label} ordinals are not contiguous for {ordinal}")
        if [int(item[1]) for item in own_updates] != list(range(len(own_updates))):
            raise ValueError(f"pipe demo update ordinals are not contiguous for {ordinal}")
    return {
        "cases": len(cases),
        "initialBlocks": sum(row[0] == "initial" for row in blocks),
        "finalBlocks": sum(row[0] == "final" for row in blocks),
        "candidatePoints": sum(row[0] == "candidate" for row in blocks),
        "updates": len(updates),
        "pass": sum(row[4] == "pass" for row in cases),
        "upstreamFailure": sum(row[4] == "upstream-failure" for row in cases),
        "updatesConsumedUntilTarget": sum(int(row[13]) for row in cases),
        "targetReachedBeforeGeneratedTail": sum(int(row[13]) < int(row[7]) for row in cases),
        "unusedGeneratedUpdateTail": sum(int(row[7]) - int(row[13]) for row in cases),
        "rollForwardUpdates": sum(row[2] == "roll-forward" for row in updates),
        "rollBackwardUpdates": sum(row[2] == "roll-backward" for row in updates),
    }


def summarize_chainsync_pipeline_rows(
    rows: list[list[str]],
    version: str = "chainsync-pipelined-max-v1",
    depth_columns: tuple[int, ...] = (5,),
    require_all_positive: bool = False,
) -> dict[str, int]:
    allowed = {"pass", "upstream-failure", "precondition-discard"}
    for ordinal, row in enumerate(rows):
        if len(row) != 5 + 2 * len(depth_columns) or row[0] != version:
            raise ValueError(f"invalid ChainSync pipelined-max row {ordinal}")
        if int(row[2]) != ordinal or int(row[1]) != int(rows[0][1]) + ordinal:
            raise ValueError(f"noncontiguous ChainSync pipelined-max row identity {ordinal}")
        depths = [int(row[index]) for index in depth_columns]
        if any(depth < 0 or depth > 300 for depth in depths) or row[-1] not in allowed:
            raise ValueError(f"invalid ChainSync pipelined-max depth/outcome at row {ordinal}")
        positive_precondition = all(depth > 0 for depth in depths) if require_all_positive else max(depths) > 0
        if positive_precondition != (row[-1] != "precondition-discard"):
            raise ValueError(f"pipelined-max precondition/outcome mismatch at row {ordinal}")
    return {status: sum(row[-1] == status for row in rows) for status in sorted(allowed)}


def summarize_chainsync_direct_connect_rows(rows: list[list[str]]) -> dict[str, dict[str, int]]:
    properties = (
        "propChainSyncPipelinedMaxDirectST",
        "propChainSyncPipelinedMinDirectST",
        "propChainSyncPipelinedMaxConnectST",
        "propChainSyncPipelinedMinConnectST",
        "propChainSyncPipelinedMaxDirectIO",
        "propChainSyncPipelinedMinDirectIO",
        "propChainSyncPipelinedMaxConnectIO",
        "propChainSyncPipelinedMinConnectIO",
    )
    for ordinal, row in enumerate(rows):
        if len(row) != 16 or row[0] != "chainsync-pipelined-direct-connect-v1":
            raise ValueError(f"invalid ChainSync pipelined direct/connect row {ordinal}")
        if int(row[2]) != ordinal or int(row[1]) != int(rows[0][1]) + ordinal:
            raise ValueError(f"noncontiguous ChainSync direct/connect row {ordinal}")
        if int(row[4]) != int(row[1]) + 7000003 or int(row[6]) != int(row[1]) + 9000007:
            raise ValueError(f"ChainSync direct/connect seed offset mismatch at row {ordinal}")
        if not set(row[7]) <= {"0", "1"} or not 0 <= int(row[5]) <= 300:
            raise ValueError(f"invalid ChainSync connect choice/depth at row {ordinal}")
        if any(value not in {"pass", "upstream-failure"} for value in row[8:16]):
            raise ValueError(f"invalid ChainSync direct/connect outcome at row {ordinal}")
    return {
        name: {
            "pass": sum(row[8 + index] == "pass" for row in rows),
            "upstream-failure": sum(row[8 + index] == "upstream-failure" for row in rows),
        }
        for index, name in enumerate(properties)
    }


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
        "ChainSync/Examples.hs": source / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/ChainSync/Examples.hs",
        "BlockFetch": source / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/BlockFetch/Test.hs",
        "LocalStateQuery": source / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/LocalStateQuery/Test.hs",
        "LocalTxSubmission": source / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/LocalTxSubmission/Test.hs",
        "LocalTxMonitor": source / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/LocalTxMonitor/Test.hs",
        "TxSubmission2": source / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/TxSubmission2/Test.hs",
        "Main": source / "ouroboros-network/protocols/tests/Main.hs",
        "Pipe": source / "ouroboros-network/tests/io/Test/Ouroboros/Network/Pipe.hs",
        "PipeMain": source / "ouroboros-network/tests/io/Main.hs",
    }
    source_hashes = {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in modules.items()}
    shutil.copytree(source, overlay, dirs_exist_ok=True, ignore=shutil.ignore_patterns(".git", "dist-newstyle"))
    keep = overlay / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/KeepAlive/Test.hs"
    peer = overlay / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/PeerSharing/Test.hs"
    chain = overlay / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/ChainSync/Test.hs"
    chain_examples = overlay / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/ChainSync/Examples.hs"
    block_fetch = overlay / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/BlockFetch/Test.hs"
    local_state_query = overlay / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/LocalStateQuery/Test.hs"
    local_tx_submission = overlay / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/LocalTxSubmission/Test.hs"
    local_tx_monitor = overlay / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/LocalTxMonitor/Test.hs"
    tx_submission2 = overlay / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/TxSubmission2/Test.hs"
    pipe = overlay / "ouroboros-network/tests/io/Test/Ouroboros/Network/Pipe.hs"
    pipe_main = overlay / "ouroboros-network/tests/io/Main.hs"

    pipe_original = pipe.read_text()
    pipe_export_anchor = "module Test.Ouroboros.Network.Pipe (tests) where"
    if pipe_original.count(pipe_export_anchor) != 1:
        raise SystemExit("Pipe observer module export anchor changed")
    pipe_original = pipe_original.replace(
        pipe_export_anchor,
        "module Test.Ouroboros.Network.Pipe (tests, exportPipeDemoBehaviorCases) where",
    )
    pipe_import_anchor = "import Codec.Serialise (Serialise (..))\n"
    if pipe_original.count(pipe_import_anchor) != 1:
        raise SystemExit("Pipe observer Serialise import anchor changed")
    pipe_original = pipe_original.replace(
        pipe_import_anchor,
        pipe_import_anchor +
        "import Codec.CBOR.Write qualified as CBOR\n"
        "import Codec.Serialise qualified as S\n"
        "import Data.ByteString qualified as BS\n"
        "import Data.List (findIndex, intercalate)\n"
        "import Numeric (showHex)\n"
        "import System.IO (IOMode (WriteMode), hPutStrLn, withFile)\n"
        "import Test.QuickCheck.Gen (unGen)\n"
        "import Test.QuickCheck.Random (mkQCGen)\n",
    )
    pipe_update_import = "import Ouroboros.Network.Mock.Chain (Chain, ChainUpdate, Point)\n"
    if pipe_original.count(pipe_update_import) != 1:
        raise SystemExit("Pipe observer ChainUpdate import anchor changed")
    pipe_original = pipe_original.replace(
        pipe_update_import,
        "import Ouroboros.Network.Mock.Chain (Chain, ChainUpdate (..), Point)\n"
        "import Ouroboros.Network.Mock.ConcreteBlock (Block)\n",
    )
    pipe.write_text(pipe_original + r'''

-- Disposable observer: execute the unchanged registered IO property over the
-- unchanged Arbitrary generator and export the exact generated chain updates.
exportPipeDemoBehaviorCases :: FilePath -> FilePath -> FilePath -> Int -> Int -> IO ()
exportPipeDemoBehaviorCases casesPath blocksPath updatesPath count seed =
  withFile casesPath WriteMode $ \caseHandle ->
  withFile blocksPath WriteMode $ \blockHandle ->
  withFile updatesPath WriteMode $ \updateHandle ->
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 30 + (ordinal `mod` 21)
        input = unGen arbitrary (mkQCGen caseSeed) caseSize :: TestBlockChainAndUpdates
    result <- quickCheckWithResult stdArgs { chatty = False } (once (prop_pipe_demo input))
    case input of
      TestBlockChainAndUpdates initial updates -> do
            (_, updateStates) <- foldM advance (initial, []) updates
            let finalChain = case reverse updateStates of
                  (_, state) : _ -> state
                  [] -> initial
                stopCount = case findIndex (\(_, state) -> Chain.headPoint state == Chain.headPoint finalChain) updateStates of
                  Just index -> index + 1
                  Nothing -> 0
            let candidates = Chain.selectPoints ChainSync.recentOffsets initial
                initialBlocks = Chain.toOldestFirst initial
                finalBlocks = Chain.toOldestFirst finalChain
            hPutStrLn caseHandle $ intercalate "\t"
              [ "ouroboros-pipe-demo-v1", show caseSeed, show ordinal, show caseSize
              , if isSuccess result then "pass" else "upstream-failure"
              , show (length initialBlocks), show (length candidates)
              , show (length updates), show (length finalBlocks)
              , hex (BL.toStrict (CBOR.toLazyByteString (encode (Chain.headPoint initial))))
              , hex (BL.toStrict (CBOR.toLazyByteString (encode (Chain.headPoint finalChain))))
              , hex (BL.toStrict (CBOR.toLazyByteString (encodeTip encode (Chain.headTip finalChain))))
              , hex (BL.toStrict (CBOR.toLazyByteString (encodeTip encode (Chain.headTip initial))))
              , show stopCount
              ]
            forM_ (zip [0 :: Int ..] initialBlocks) $ \(blockOrdinal, block) ->
              hPutStrLn blockHandle $ intercalate "\t"
                [ "initial", show ordinal, show blockOrdinal
                , hex (BL.toStrict (S.serialise block))
                , hex (BL.toStrict (CBOR.toLazyByteString (encode (Chain.blockPoint block))))
                ]
            forM_ (zip [0 :: Int ..] finalBlocks) $ \(blockOrdinal, block) ->
              hPutStrLn blockHandle $ intercalate "\t"
                [ "final", show ordinal, show blockOrdinal
                , hex (BL.toStrict (S.serialise block))
                , hex (BL.toStrict (CBOR.toLazyByteString (encode (Chain.blockPoint block))))
                ]
            forM_ (zip [0 :: Int ..] candidates) $ \(candidateOrdinal, point) ->
              hPutStrLn blockHandle $ intercalate "\t"
                [ "candidate", show ordinal, show candidateOrdinal, hex (BL.toStrict (S.serialise point)) ]
            forM_ (zip [0 :: Int ..] updateStates) $ \(updateOrdinal, (update, state)) -> do
              let (kind, payload, point) = case update of
                    AddBlock block ->
                      ( "roll-forward"
                      , hex (BL.toStrict (S.serialise block))
                      , hex (BL.toStrict (CBOR.toLazyByteString (encode (Chain.blockPoint block))))
                      )
                    RollBack point ->
                      ( "roll-backward"
                      , ""
                      , hex (BL.toStrict (CBOR.toLazyByteString (encode point)))
                      )
                  tip = hex (BL.toStrict (CBOR.toLazyByteString (encodeTip encode (Chain.headTip state))))
              hPutStrLn updateHandle $ intercalate "\t"
                [ show ordinal, show updateOrdinal, kind, payload, point, tip ]
  where
    advance
      :: (Chain Block, [(ChainUpdate Block Block, Chain Block)])
      -> ChainUpdate Block Block
      -> IO (Chain Block, [(ChainUpdate Block Block, Chain Block)])
    advance (chain, states) update = case Chain.applyChainUpdate update chain of
      Nothing -> fail "generated update sequence was not applicable"
      Just next -> pure (next, states ++ [(update, next)])

    hex = concatMap (pad . (`showHex` "")) . BS.unpack
    pad [digit] = ['0', digit]
    pad digits = digits
''')
    pipe_main_original = pipe_main.read_text()
    pipe_main_import = "import Test.Ouroboros.Network.Pipe qualified (tests)\n"
    if pipe_main_original.count(pipe_main_import) != 1:
        raise SystemExit("IO test main Pipe import anchor changed")
    pipe_main_original = pipe_main_original.replace(
        pipe_main_import,
        "import Test.Ouroboros.Network.Pipe qualified as Pipe (tests, exportPipeDemoBehaviorCases)\n"
        "import System.Environment (getArgs)\n",
    )
    pipe_main_original = pipe_main_original.replace(
        "main = withUtf8 $ defaultMain tests",
        "main = withUtf8 $ do\n"
        "  args <- getArgs\n"
        "  case args of\n"
        "    [\"--export-pipe-demo\", dir, count, seed] ->\n"
        "      Pipe.exportPipeDemoBehaviorCases (dir <> \"/pipe-demo-cases.tsv\") (dir <> \"/pipe-demo-blocks.tsv\") (dir <> \"/pipe-demo-updates.tsv\") (read count) (read seed)\n"
        "    _ -> defaultMain tests",
    )
    pipe_main_original = pipe_main_original.replace(
        "Test.Ouroboros.Network.Pipe.tests", "Pipe.tests"
    )
    pipe_main.write_text(pipe_main_original)
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
    validCborResult <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 6000003), caseSize) }
      (once (prop_codec_v2_valid_cbor testCase))
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
            validCborOutcome = if isSuccess validCborResult then "pass" else "upstream-failure"
        hPutStrLn outputHandle $ intercalate "\t"
          [ "keepalive-codec-v1", show caseSeed, show ordinal, show caseSize
          , state, sender, tag, cookie, hex (BL.toStrict raw), outcome, validCborOutcome
          ]
  where
    hex = concatMap (pad . (`showHex` "")) . BS.unpack
    pad [digit] = ['0', digit]
    pad digits = digits
''',
        "",
    )
    keep = overlay / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/KeepAlive/Test.hs"
    inject(
        keep,
        "import Test.QuickCheck\n",
        r'''

-- Disposable observer: run the unchanged upstream split properties over the
-- same deterministic AnyMessage rows as exportKeepAliveCases.
exportKeepAliveSplitCases :: FilePath -> Int -> Int -> IO ()
exportKeepAliveSplitCases path count seed = withFile path WriteMode $ \outputHandle ->
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 30 + (ordinal `mod` 21)
        testCase = unGen arbitrary (mkQCGen caseSeed) caseSize :: AnyMessage KeepAlive
    split2Result <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 3000003), caseSize) }
      (once (prop_codec_v2_splits2 testCase))
    split3Result <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 4000003), caseSize) }
      (once (prop_codec_v2_splits3 testCase))
    case testCase of
      AnyMessage msg -> do
        let Codec { encode } = codecKeepAlive_v2
              :: Codec KeepAlive CBOR.DeserialiseFailure IO ByteString
            raw = BL.toStrict (encode msg)
            (state, sender, tag, cookie) = case msg of
              MsgKeepAlive (Cookie value)         -> ("client", "client", "request", show value)
              MsgKeepAliveResponse (Cookie value) -> ("server", "server", "response", show value)
              MsgDone                             -> ("client", "client", "done", "")
            split2Outcome = if isSuccess split2Result then "pass" else "upstream-failure"
            split3Outcome = if isSuccess split3Result then "pass" else "upstream-failure"
        hPutStrLn outputHandle $ intercalate "\t"
          [ "keepalive-splits-v1", show caseSeed, show ordinal, show caseSize
          , state, sender, tag, cookie, hex raw, split2Outcome, split3Outcome
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
    inject(
        keep,
        "import Test.QuickCheck.Random (mkQCGen)\n",
        r'''

-- Export observations of the unchanged direct/connect/channel properties. A generated QuickCheck
-- function is recorded by its complete trace on the finite accumulator path the properties use.
exportKeepAliveBehaviorCases :: FilePath -> Int -> Int -> IO ()
exportKeepAliveBehaviorCases path count seed = withFile path WriteMode $ \outputHandle ->
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 12 + (ordinal `mod` 13)
        function = unGen arbitrary (mkQCGen caseSeed) caseSize :: Int -> Int
        NonNegative n = unGen arbitrary (mkQCGen (caseSeed + 7000003)) caseSize
        accumulatorTrace = scanl (\acc _ -> function acc) 0 [1 .. n]
        cookies = [n, n - 1 .. 1]
    directResult <- quickCheckWithResult stdArgs { chatty = False }
      (once (prop_direct function (NonNegative n)))
    connectResult <- quickCheckWithResult stdArgs { chatty = False }
      (once (prop_connect function (NonNegative n)))
    channelResult <- quickCheckWithResult stdArgs { chatty = False }
      (once (prop_channel_ST function (NonNegative n)))
    hPutStrLn outputHandle $ intercalate "\t"
      [ "keepalive-behavior-v1", show caseSeed, show ordinal, show caseSize, show n
      , intercalate "," (map show accumulatorTrace)
      , intercalate "," (map show cookies)
      , verdict directResult, verdict connectResult, verdict channelResult
      ]
  where
    verdict result = if isSuccess result then "pass" else "upstream-failure"
''',
        "",
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
    cborResult <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 2000003), caseSize) }
      (once (prop_codec_cbor testCase))
    validCborResult <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 6000003), caseSize) }
      (once (prop_codec_valid_cbor testCase))
    split2Result <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 3000003), caseSize) }
      (once (prop_codec_splits2 testCase))
    split3Result <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 4000003), caseSize) }
      (once (prop_codec_splits3 testCase))
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
            cborOutcome = if isSuccess cborResult then "pass" else "upstream-failure"
            validCborOutcome = if isSuccess validCborResult then "pass" else "upstream-failure"
            split2Outcome = if isSuccess split2Result then "pass" else "upstream-failure"
            split3Outcome = if isSuccess split3Result then "pass" else "upstream-failure"
        hPutStrLn outputHandle $ intercalate "\t"
          [ "peersharing-codec-int-v1", show caseSeed, show ordinal, show caseSize
          , state, sender, tag, fields, adapterStatus, hex (BL.toStrict raw), outcome, cborOutcome, validCborOutcome
          , split2Outcome, split3Outcome
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
-- Disposable observer: rerun the original split properties for the same seeded message.
exportChainSyncSplitCases :: FilePath -> Int -> Int -> IO ()
exportChainSyncSplitCases path count seed = withFile path WriteMode $ \outputHandle ->
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 30 + (ordinal `mod` 21)
        testCase = unGen arbitrary (mkQCGen caseSeed) caseSize
          :: AnyMessage ChainSync_Serialised_BlockHeader
    split2Result <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 3000003), caseSize) }
      (once (prop_codec_splits2_ChainSyncSerialised testCase))
    split3Result <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 4000003), caseSize) }
      (once (prop_codec_splits3_ChainSyncSerialised testCase))
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
            split2Outcome = if isSuccess split2Result then "pass" else "upstream-failure"
            split3Outcome = if isSuccess split3Result then "pass" else "upstream-failure"
        hPutStrLn outputHandle $ intercalate "\t"
          [ "chainsync-splits-v1", show caseSeed, show ordinal, show caseSize
          , state, sender, tag, hex (BL.toStrict raw), split2Outcome, split3Outcome
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
    chain_examples_original = chain_examples.read_text()
    recent_offsets_export = "  , chainSyncServerExample\n"
    if chain_examples_original.count(recent_offsets_export) != 1:
        raise SystemExit("ChainSync Examples recentOffsets export anchor changed")
    chain_examples.write_text(chain_examples_original.replace(
        recent_offsets_export,
        recent_offsets_export + "  , recentOffsets\n",
    ))
    chain.write_text(chain.read_text().replace(
        "import Codec.Serialise qualified as S\n",
        "import Codec.Serialise qualified as S\nimport Codec.CBOR.Write qualified as CBOR\n",
    ))
    chain.write_text(chain.read_text() + r'''

-- Disposable observer: preserve the unchanged ChainProducerStateForkTest
-- generator and direct/connect/channel property verdicts for the typed runtime.
exportChainSyncBehaviorCases :: FilePath -> FilePath -> FilePath -> Int -> Int -> IO ()
exportChainSyncBehaviorCases casesPath blocksPath pointsPath count seed = do
  withFile casesPath WriteMode $ \casesHandle ->
    withFile blocksPath WriteMode $ \blocksHandle ->
      withFile pointsPath WriteMode $ \pointsHandle ->
        forM_ [0 .. count - 1] $ \ordinal -> do
          let caseSeed = seed + ordinal
              caseSize = 30 + (ordinal `mod` 21)
              input = unGen arbitrary (mkQCGen caseSeed) caseSize
                :: ChainProducerStateForkTest
          directResult <- quickCheckWithResult stdArgs { chatty = False }
            (once (propChainSyncDirectST input))
          connectResult <- quickCheckWithResult stdArgs { chatty = False }
            (once (propChainSyncConnectST input))
          channelResult <- quickCheckWithResult stdArgs { chatty = False }
            (once (propChainSyncDemoST input))
          case input of
            ChainProducerStateForkTest cps fork -> do
              let producer = ChainProducerState.producerChain cps
                  producerBlocks = Chain.toOldestFirst producer
                  forkBlocks = Chain.toOldestFirst fork
                  candidatePoints = Chain.selectPoints ChainSyncExamples.recentOffsets fork
                  targetPoint = Chain.headPoint producer
                  targetTip = Chain.headTip producer
                  expectedIntersection = ChainProducerState.findFirstPoint candidatePoints cps
                  pointBytes = BL.toStrict . S.serialise
                  blockBytes = BL.toStrict . S.serialise
                  tipBytes = BL.toStrict . CBOR.toLazyByteString . encodeTip S.encode $ targetTip
                  propertyResult result = if isSuccess result then "pass" else "upstream-failure"
              hPutStrLn casesHandle $ intercalate "\t"
                [ "chainsync-behavior-case-v2", show caseSeed, show ordinal, show caseSize
                , propertyResult directResult, propertyResult connectResult, propertyResult channelResult
                , hex (pointBytes targetPoint), hex tipBytes
                , show (length producerBlocks), show (length forkBlocks), show (length candidatePoints)
                , hex (pointBytes (Chain.headPoint Chain.genesis))
                , maybe "none" (hex . pointBytes) expectedIntersection
                ]
              forM_ (zip [0 :: Int ..] producerBlocks) $ \(blockOrdinal, block) ->
                hPutStrLn blocksHandle $ intercalate "\t"
                  [ "chainsync-behavior-block-v1", show caseSeed, show ordinal, "producer"
                  , show blockOrdinal, hex (pointBytes (Chain.blockPoint block)), hex (blockBytes block)
                  ]
              forM_ (zip [0 :: Int ..] forkBlocks) $ \(blockOrdinal, block) ->
                hPutStrLn blocksHandle $ intercalate "\t"
                  [ "chainsync-behavior-block-v1", show caseSeed, show ordinal, "client"
                  , show blockOrdinal, hex (pointBytes (Chain.blockPoint block)), hex (blockBytes block)
                  ]
              forM_ (zip [0 :: Int ..] candidatePoints) $ \(pointOrdinal, point) ->
                hPutStrLn pointsHandle $ intercalate "\t"
                  [ "chainsync-behavior-point-v1", show caseSeed, show ordinal
                  , show pointOrdinal, hex (pointBytes point)
                  ]
  where
    hex = concatMap (pad . (`showHex` "")) . BS.unpack
    pad [digit] = ['0', digit]
    pad digits = digits
''')
    block_fetch_original = inject(
        block_fetch,
        "import Test.QuickCheck\n",
        r'''

-- Disposable observer: retain the actual Serialised-block generator and codec properties.
exportBlockFetchCases :: FilePath -> Int -> Int -> IO ()
exportBlockFetchCases path count seed = withFile path WriteMode $ \outputHandle ->
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 30 + (ordinal `mod` 21)
        testCase = unGen arbitrary (mkQCGen caseSeed) caseSize
          :: AnyMessage (BlockFetch (Serialised Block) (Point Block))
    codecResult <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 1000003), caseSize) }
      (once (prop_codec_BlockFetchSerialised testCase))
    cborResult <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 2000003), caseSize) }
      (once (prop_codec_cbor_BlockFetchSerialised testCase))
    case testCase of
      AnyMessage (msg :: Message (BlockFetch (Serialised Block) (Point Block)) st st') -> do
        let blockCodec = codecSerialised
              :: Codec (BlockFetch (Serialised Block) (Point Block))
                   S.DeserialiseFailure IO ByteString
            raw = encode blockCodec msg
            token = stateToken :: StateToken st
            (state, sender) = case token of
              SingBFIdle -> ("idle", "client")
              SingBFBusy -> ("busy", "server")
              SingBFStreaming -> ("streaming", "server")
              SingBFDone -> error "BlockFetch generator produced terminal source state"
            tag = case msg of
              MsgRequestRange _ -> "request-range"
              MsgClientDone -> "client-done"
              MsgStartBatch -> "start-batch"
              MsgNoBlocks -> "no-blocks"
              MsgBlock _ -> "block"
              MsgBatchDone -> "batch-done"
            codecOutcome = if isSuccess codecResult then "pass" else "upstream-failure"
            cborOutcome = if isSuccess cborResult then "pass" else "upstream-failure"
        hPutStrLn outputHandle $ intercalate "\t"
          [ "blockfetch-serialised-codec-v1", show caseSeed, show ordinal, show caseSize
          , state, sender, tag, hex (BL.toStrict raw), codecOutcome, cborOutcome
          ]
  where
    hex = concatMap (pad . (`showHex` "")) . BS.unpack
    pad [digit] = ['0', digit]
    pad digits = digits
''',
        "import Control.Monad (forM_)\n"
        "import qualified Data.ByteString as BS\n"
        "import qualified Data.ByteString.Lazy as BL\n"
        "import Data.List (intercalate)\n"
        "import Numeric (showHex)\n"
        "import System.IO (withFile, IOMode (WriteMode), hPutStrLn)\n"
        "import Test.QuickCheck.Gen (unGen)\n"
        "import Test.QuickCheck.Random (mkQCGen)\n",
    )
    block_fetch.write_text(block_fetch.read_text() + r'''

-- Disposable observer: run unchanged serialized-codec split properties over
-- the same deterministic AnyMessage rows as exportBlockFetchCases.
exportBlockFetchSplitCases :: FilePath -> Int -> Int -> IO ()
exportBlockFetchSplitCases path count seed = withFile path WriteMode $ \outputHandle ->
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 30 + (ordinal `mod` 21)
        testCase = unGen arbitrary (mkQCGen caseSeed) caseSize
          :: AnyMessage (BlockFetch (Serialised Block) (Point Block))
    split2Result <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 3000003), caseSize) }
      (once (prop_codec_splits2_BlockFetchSerialised testCase))
    split3Result <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 4000003), caseSize) }
      (once (prop_codec_splits3_BlockFetchSerialised testCase))
    case testCase of
      AnyMessage (msg :: Message (BlockFetch (Serialised Block) (Point Block)) st st') -> do
        let blockCodec = codecSerialised
              :: Codec (BlockFetch (Serialised Block) (Point Block))
                   S.DeserialiseFailure IO ByteString
            raw = BL.toStrict (encode blockCodec msg)
            token = stateToken :: StateToken st
            (state, sender) = case token of
              SingBFIdle -> ("idle", "client")
              SingBFBusy -> ("busy", "server")
              SingBFStreaming -> ("streaming", "server")
              SingBFDone -> error "BlockFetch generator produced terminal source state"
            tag = case msg of
              MsgRequestRange _ -> "request-range"
              MsgClientDone -> "client-done"
              MsgStartBatch -> "start-batch"
              MsgNoBlocks -> "no-blocks"
              MsgBlock _ -> "block"
              MsgBatchDone -> "batch-done"
            split2Outcome = if isSuccess split2Result then "pass" else "upstream-failure"
            split3Outcome = if isSuccess split3Result then "pass" else "upstream-failure"
        hPutStrLn outputHandle $ intercalate "\t"
          [ "blockfetch-splits-v1", show caseSeed, show ordinal, show caseSize
          , state, sender, tag, hex raw, split2Outcome, split3Outcome
          ]
  where
    hex = concatMap (pad . (`showHex` "")) . BS.unpack
    pad [digit] = ['0', digit]
    pad digits = digits
''')
    block_fetch.write_text(block_fetch.read_text() + r'''

-- Disposable observer: preserve the unchanged generator and direct/connect/channel properties.
exportBlockFetchBehaviorCases :: FilePath -> Int -> Int -> IO ()
exportBlockFetchBehaviorCases path count seed = withFile path WriteMode $ \outputHandle ->
  withFile (path <> ".ranges.tsv") WriteMode $ \rangesHandle ->
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 30 + (ordinal `mod` 21)
        testCase = unGen arbitrary (mkQCGen caseSeed) caseSize :: TestChainAndPoints
    directResult <- quickCheckWithResult stdArgs { chatty = False }
      (once (prop_direct testCase))
    connectResult <- quickCheckWithResult stdArgs { chatty = False }
      (once (prop_connect testCase))
    channelResult <- quickCheckWithResult stdArgs { chatty = False }
      (once (prop_channel_ST testCase))
    case testCase of
      TestChainAndPoints chain points -> do
        let chainBlocks = Chain.toOldestFirst chain
            chainPoints = map Chain.blockPoint chainBlocks
            ranges = pointsToRanges chain points
            rangePoints = map (\(ChainRange from to) -> (from, to)) ranges
            rangeBodies = receivedBlockBodies chain points
            expectedBodies = reverse (concat (receivedBlockBodies chain points))
            outcome result = if isSuccess result then "pass" else "upstream-failure"
        forM_ (zip3 [0 :: Int ..] ranges rangeBodies) $ \(rangeOrdinal, ChainRange from to, bodies) ->
          hPutStrLn rangesHandle $ intercalate "\t"
            ([ "blockfetch-range-v1", show caseSeed, show ordinal, show rangeOrdinal
             , hex (BL.toStrict (S.serialise from)), hex (BL.toStrict (S.serialise to))
             , show (length bodies)
             ] ++ map (hex . BL.toStrict . S.serialise) bodies)
        hPutStrLn outputHandle $ intercalate "\t"
          [ "blockfetch-behavior-v1", show caseSeed, show ordinal, show caseSize
          , hex (BL.toStrict (S.serialise chainBlocks))
          , hex (BL.toStrict (S.serialise chainPoints))
          , hex (BL.toStrict (S.serialise points))
          , hex (BL.toStrict (S.serialise rangePoints))
          , hex (BL.toStrict (S.serialise rangeBodies))
          , hex (BL.toStrict (S.serialise expectedBodies))
          , intercalate "," (map (hex . BL.toStrict . S.serialise) expectedBodies)
          , outcome directResult, outcome connectResult, outcome channelResult
          ]
  where
    hex = concatMap (pad . (`showHex` "")) . BS.unpack
    pad [digit] = ['0', digit]
    pad digits = digits
''')
    block_module = block_fetch.read_text()
    block_export_anchor = "module Ouroboros.Network.Protocol.BlockFetch.Test (tests) where"
    if block_module.count(block_export_anchor) != 1:
        raise SystemExit("BlockFetch observer module export anchor changed")
    block_fetch.write_text(block_module.replace(
        block_export_anchor,
        "module Ouroboros.Network.Protocol.BlockFetch.Test (tests, exportBlockFetchCases, exportBlockFetchSplitCases, exportBlockFetchBinaryCases, exportBlockFetchBehaviorCases) where",
    ))

    local_state_query_original = inject(
        local_state_query,
        "import Test.QuickCheck as QC hiding (Result)\n",
        r'''

-- Disposable observer: preserve the real stateful generator, codec, and codec properties.
exportLocalStateQueryCases :: FilePath -> Int -> Int -> IO ()
exportLocalStateQueryCases path count seed = withFile path WriteMode $ \outputHandle ->
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 30 + (ordinal `mod` 21)
        testCase = unGen arbitrary (mkQCGen caseSeed) caseSize
          :: AnyMessageWithResult Block (Point Block) Query MockLedgerState
    codecResult <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 1000003), caseSize) }
      (once (prop_codec_LocalStateQuery testCase))
    cborResult <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 2000003), caseSize) }
      (once (prop_codec_cbor testCase))
    validCborResult <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 6000003), caseSize) }
      (once (prop_codec_valid_cbor testCase))
    case testCase of
      AnyMessageWithResult (Stateful.AnyMessage localState msg) -> do
        let statefulCodec = codec
              :: Stateful.Codec (LocalStateQuery Block (Point Block) Query)
                   DeserialiseFailure State IO ByteString
            raw = Stateful.encode statefulCodec localState msg
            (state, sender) = case localState of
              StateIdle -> ("idle", "client")
              StateAcquiring -> ("acquiring", "server")
              StateAcquired -> ("acquired", "client")
              StateQuerying _ -> ("querying", "server")
              StateDone -> ("done", "none")
            tag = case msg of
              MsgAcquire VolatileTip -> "acquire-volatile-tip"
              MsgAcquire ImmutableTip -> "acquire-immutable-tip"
              MsgAcquire (SpecificPoint _) -> "acquire-specific-point"
              MsgAcquired -> "acquired"
              MsgFailure AcquireFailurePointTooOld -> "failure-point-too-old"
              MsgFailure AcquireFailurePointNotOnChain -> "failure-point-not-on-chain"
              MsgQuery _ -> "query"
              MsgResult _ -> "result"
              MsgRelease -> "release"
              MsgReAcquire VolatileTip -> "reacquire-volatile-tip"
              MsgReAcquire ImmutableTip -> "reacquire-immutable-tip"
              MsgReAcquire (SpecificPoint _) -> "reacquire-specific-point"
              MsgDone -> "done"
            codecOutcome = if isSuccess codecResult then "pass" else "upstream-failure"
            cborOutcome = if isSuccess cborResult then "pass" else "upstream-failure"
            validCborOutcome = if isSuccess validCborResult then "pass" else "upstream-failure"
        hPutStrLn outputHandle $ intercalate "\t"
          [ "localsq-codec-v1", show caseSeed, show ordinal, show caseSize
          , state, sender, tag, hex (BL.toStrict raw), codecOutcome, cborOutcome, validCborOutcome
          ]
  where
    hex = concatMap (pad . (`showHex` "")) . BS.unpack
    pad [digit] = ['0', digit]
    pad digits = digits
''',
        "import Control.Monad (forM_)\n"
        "import Codec.Serialise (serialise)\n"
        "import Data.ByteString qualified as BS\n"
        "import Data.ByteString.Lazy qualified as BL\n"
        "import Data.List (intercalate)\n"
        "import Numeric (showHex)\n"
        "import System.IO (withFile, IOMode (WriteMode), hPutStrLn)\n"
        "import Test.QuickCheck.Gen (unGen)\n"
        "import Test.QuickCheck.Random (mkQCGen)\n",
    )
    local_state_query.write_text(local_state_query.read_text() + r'''

-- Disposable observer: run the unchanged stateful split properties over the
-- same deterministic AnyMessageWithResult rows as exportLocalStateQueryCases.
exportLocalStateQuerySplitCases :: FilePath -> Int -> Int -> IO ()
exportLocalStateQuerySplitCases path count seed = withFile path WriteMode $ \outputHandle ->
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 30 + (ordinal `mod` 21)
        testCase = unGen arbitrary (mkQCGen caseSeed) caseSize
          :: AnyMessageWithResult Block (Point Block) Query MockLedgerState
    split2Result <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 3000003), caseSize) }
      (once (prop_codec_splits2 testCase))
    split3Result <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 4000003), caseSize) }
      (once (prop_codec_splits3 testCase))
    case testCase of
      AnyMessageWithResult (Stateful.AnyMessage localState msg) -> do
        let statefulCodec = codec
              :: Stateful.Codec (LocalStateQuery Block (Point Block) Query)
                   DeserialiseFailure State IO ByteString
            raw = Stateful.encode statefulCodec localState msg
            (state, sender) = case localState of
              StateIdle -> ("idle", "client")
              StateAcquiring -> ("acquiring", "server")
              StateAcquired -> ("acquired", "client")
              StateQuerying _ -> ("querying", "server")
              StateDone -> ("done", "none")
            tag = case msg of
              MsgAcquire VolatileTip -> "acquire-volatile-tip"
              MsgAcquire ImmutableTip -> "acquire-immutable-tip"
              MsgAcquire (SpecificPoint _) -> "acquire-specific-point"
              MsgAcquired -> "acquired"
              MsgFailure AcquireFailurePointTooOld -> "failure-point-too-old"
              MsgFailure AcquireFailurePointNotOnChain -> "failure-point-not-on-chain"
              MsgQuery _ -> "query"
              MsgResult _ -> "result"
              MsgRelease -> "release"
              MsgReAcquire VolatileTip -> "reacquire-volatile-tip"
              MsgReAcquire ImmutableTip -> "reacquire-immutable-tip"
              MsgReAcquire (SpecificPoint _) -> "reacquire-specific-point"
              MsgDone -> "done"
            split2Outcome = if isSuccess split2Result then "pass" else "upstream-failure"
            split3Outcome = if isSuccess split3Result then "pass" else "upstream-failure"
        hPutStrLn outputHandle $ intercalate "\t"
          [ "localsq-splits-v1", show caseSeed, show ordinal, show caseSize
          , state, sender, tag, hex (BL.toStrict raw), split2Outcome, split3Outcome
          ]
  where
    hex = concatMap (pad . (`showHex` "")) . BS.unpack
    pad [digit] = ['0', digit]
    pad digits = digits
''')
    local_state_query.write_text(local_state_query.read_text() + r'''

-- Disposable observer: retain the unchanged SetupData map and run every original
-- direct/connect/channel property against that exact generated value.
exportLocalStateQueryBehaviorCases :: FilePath -> FilePath -> Int -> Int -> IO ()
exportLocalStateQueryBehaviorCases casesPath actionsPath count seed = do
  withFile casesPath WriteMode $ \casesHandle ->
    withFile actionsPath WriteMode $ \actionsHandle ->
      forM_ [0 .. count - 1] $ \ordinal -> do
        let caseSeed = seed + ordinal
            caseSize = ordinal `mod` 7
            input = unGen arbitrary (mkQCGen caseSeed) caseSize :: SetupData
        directResult <- quickCheckWithResult stdArgs { chatty = False } (once (prop_direct input))
        connectResult <- quickCheckWithResult stdArgs { chatty = False } (once (prop_connect input))
        channelSTResult <- quickCheckWithResult stdArgs { chatty = False } (once (prop_channel_ST input))
        channelIOResult <- quickCheckWithResult stdArgs { chatty = False } (once (prop_channel_IO input))
        pipeIOResult <- quickCheckWithResult stdArgs { chatty = False } (once (prop_pipe_IO input))
        let entries = Map.toList input
            verdict result = if isSuccess result then "pass" else "upstream-failure"
        hPutStrLn casesHandle $ intercalate "\t"
          [ "localsq-behavior-case-v1", show caseSeed, show ordinal, show caseSize
          , show (length entries), verdict directResult, verdict connectResult
          , verdict channelSTResult, verdict channelIOResult, verdict pipeIOResult
          ]
        forM_ (zip [0 :: Int ..] entries) $ \(actionOrdinal, (target, (failure, _query))) -> do
          let statefulCodec = codec
                :: Stateful.Codec (LocalStateQuery Block (Point Block) Query)
                     DeserialiseFailure State IO ByteString
              acquireWire = BL.toStrict (Stateful.encode statefulCodec StateIdle (MsgAcquire target))
              queryWire = BL.toStrict (serialise ())
              resultWire = BL.toStrict (serialise (MockLedgerState target))
              failureTag = case failure of
                Nothing -> "acquired"
                Just AcquireFailurePointTooOld -> "point-too-old"
                Just AcquireFailurePointNotOnChain -> "point-not-on-chain"
          hPutStrLn actionsHandle $ intercalate "\t"
            [ "localsq-behavior-v1", show caseSeed, show ordinal, show caseSize
            , show actionOrdinal, show (length entries), hex acquireWire
            , failureTag, hex queryWire, hex resultWire
            , verdict directResult, verdict connectResult, verdict channelSTResult
            , verdict channelIOResult, verdict pipeIOResult
            ]
  where
    hex = concatMap (pad . (`showHex` "")) . BS.unpack
    pad [digit] = ['0', digit]
    pad digits = digits
''')
    local_state_module = local_state_query.read_text()
    local_state_export_anchor = "module Ouroboros.Network.Protocol.LocalStateQuery.Test\n"
    if local_state_module.count(local_state_export_anchor) != 1:
        raise SystemExit("LocalStateQuery observer module export anchor changed")
    export_block_anchor = "  ( tests\n"
    if local_state_module.count(export_block_anchor) != 1:
        raise SystemExit("LocalStateQuery observer module export list changed")
    local_state_query.write_text(local_state_module.replace(
        export_block_anchor,
        "  ( tests\n  , exportLocalStateQueryCases\n  , exportLocalStateQuerySplitCases\n  , exportLocalStateQueryBehaviorCases\n",
    ))

    local_tx_submission_original = inject(
        local_tx_submission,
        "import Test.QuickCheck as QC\n",
        r'''

-- Disposable observer: use the actual typed-protocol AnyMessage generator and codec properties.
exportLocalTxSubmissionCases :: FilePath -> Int -> Int -> IO ()
exportLocalTxSubmissionCases path count seed = withFile path WriteMode $ \outputHandle ->
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 30 + (ordinal `mod` 21)
        testCase = unGen arbitrary (mkQCGen caseSeed) caseSize
          :: AnyMessage (LocalTxSubmission Tx Reject)
    codecResult <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 1000003), caseSize) }
      (once (prop_codec testCase))
    cborResult <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 2000003), caseSize) }
      (once (prop_codec_cbor testCase))
    validCborResult <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 6000003), caseSize) }
      (once (prop_codec_valid_cbor testCase))
    case testCase of
      AnyMessage (msg :: Message (LocalTxSubmission Tx Reject) st st') -> do
        let txCodec = codec
              :: Codec (LocalTxSubmission Tx Reject) DeserialiseFailure IO ByteString
            raw = encode txCodec msg
            token = stateToken :: StateToken st
            (state, sender) = case token of
              SingIdle -> ("idle", "client")
              SingBusy -> ("busy", "server")
              SingDone -> ("done", "none")
            tag = case msg of
              MsgSubmitTx _ -> "submit-tx"
              MsgAcceptTx -> "accept-tx"
              MsgRejectTx _ -> "reject-tx"
              MsgDone -> "done"
            codecOutcome = if isSuccess codecResult then "pass" else "upstream-failure"
            cborOutcome = if isSuccess cborResult then "pass" else "upstream-failure"
            validCborOutcome = if isSuccess validCborResult then "pass" else "upstream-failure"
        hPutStrLn outputHandle $ intercalate "\t"
          [ "local-tx-submission-codec-v1", show caseSeed, show ordinal, show caseSize
          , state, sender, tag, hex (BL.toStrict raw), codecOutcome, cborOutcome, validCborOutcome
          ]
  where
    hex = concatMap (pad . (`showHex` "")) . BS.unpack
    pad [digit] = ['0', digit]
    pad digits = digits

-- Disposable observer: use the same generated message with unchanged split properties.
exportLocalTxSubmissionSplitCases :: FilePath -> Int -> Int -> IO ()
exportLocalTxSubmissionSplitCases path count seed = withFile path WriteMode $ \outputHandle ->
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 30 + (ordinal `mod` 21)
        testCase = unGen arbitrary (mkQCGen caseSeed) caseSize
          :: AnyMessage (LocalTxSubmission Tx Reject)
    split2Result <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 3000003), caseSize) }
      (once (prop_codec_splits2 testCase))
    split3Result <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 4000003), caseSize) }
      (once (prop_codec_splits3 testCase))
    case testCase of
      AnyMessage (msg :: Message (LocalTxSubmission Tx Reject) st st') -> do
        let txCodec = codec
              :: Codec (LocalTxSubmission Tx Reject) DeserialiseFailure IO ByteString
            raw = BL.toStrict (encode txCodec msg)
            token = stateToken :: StateToken st
            (state, sender) = case token of
              SingIdle -> ("idle", "client")
              SingBusy -> ("busy", "server")
              SingDone -> ("done", "none")
            tag = case msg of
              MsgSubmitTx _ -> "submit-tx"
              MsgAcceptTx -> "accept-tx"
              MsgRejectTx _ -> "reject-tx"
              MsgDone -> "done"
            split2Outcome = if isSuccess split2Result then "pass" else "upstream-failure"
            split3Outcome = if isSuccess split3Result then "pass" else "upstream-failure"
        hPutStrLn outputHandle $ intercalate "\t"
          [ "local-tx-submission-splits-v1", show caseSeed, show ordinal, show caseSize
          , state, sender, tag, hex raw, split2Outcome, split3Outcome
          ]
  where
    hex = concatMap (pad . (`showHex` "")) . BS.unpack
    pad [digit] = ['0', digit]
    pad digits = digits



-- Disposable observer: retain the distinct WithBytes generator and annotated codec laws.
exportLocalTxSubmissionAnnotatedCases :: FilePath -> Int -> Int -> IO ()
exportLocalTxSubmissionAnnotatedCases path count seed = withFile path WriteMode $ \outputHandle ->
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 30 + (ordinal `mod` 21)
        testCase = unGen arbitrary (mkQCGen caseSeed) caseSize
          :: AnyMessage (LocalTxSubmission (WithBytes Tx) Reject)
    annResult <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 7000003), caseSize) }
      (once (prop_anncodec testCase))
    split2Result <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 8000003), caseSize) }
      (once (prop_anncodec_splits2 testCase))
    split3Result <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 9000003), caseSize) }
      (once (prop_anncodec_splits3 testCase))
    case testCase of
      AnyMessage (msg :: Message (LocalTxSubmission (WithBytes Tx) Reject) st st') -> do
        let Codec { encode } = anncodec
              :: AnnotatedCodec (LocalTxSubmission (WithBytes Tx) Reject)
                   DeserialiseFailure IO ByteString
            raw = BL.toStrict (encode msg)
            token = stateToken :: StateToken st
            (state, sender) = case token of
              SingIdle -> ("idle", "client")
              SingBusy -> ("busy", "server")
              SingDone -> ("done", "none")
            tag = case msg of
              MsgSubmitTx _ -> "submit-tx"
              MsgAcceptTx -> "accept-tx"
              MsgRejectTx _ -> "reject-tx"
              MsgDone -> "done"
            annOutcome = if isSuccess annResult then "pass" else "upstream-failure"
            split2Outcome = if isSuccess split2Result then "pass" else "upstream-failure"
            split3Outcome = if isSuccess split3Result then "pass" else "upstream-failure"
        hPutStrLn outputHandle $ intercalate "\t"
          [ "local-tx-submission-annotated-v1", show caseSeed, show ordinal, show caseSize
          , state, sender, tag, hex raw, annOutcome, split2Outcome, split3Outcome
          ]
  where
    hex = concatMap (pad . (`showHex` "")) . BS.unpack
    pad [digit] = ['0', digit]
    pad digits = digits
''',
        "import Codec.CBOR.Write qualified as CBOR\n"
        "import Control.Monad (forM_)\n"
        "import Data.ByteString qualified as BS\n"
        "import Data.ByteString.Lazy qualified as BL\n"
        "import Data.List (intercalate)\n"
        "import Numeric (showHex)\n"
        "import System.IO (withFile, IOMode (WriteMode), hPutStrLn)\n"
        "import Test.QuickCheck.Gen (unGen)\n"
        "import Test.QuickCheck.Random (mkQCGen)\n",
    )
    local_tx_submission.write_text(local_tx_submission.read_text() + r'''

-- Disposable observer: run the unchanged direct/connect/channel properties over
-- one original generated arbitrary function and transaction list, retaining each
-- exact serialized Tx and SubmitResult for a production-server replay.
exportLocalTxSubmissionBehaviorCases :: FilePath -> Int -> Int -> IO ()
exportLocalTxSubmissionBehaviorCases path count seed = withFile path WriteMode $ \outputHandle ->
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 8 + (ordinal `mod` 9)
        p = unGen arbitrary (mkQCGen (caseSeed + 1000003)) caseSize
              :: Tx -> SubmitResult Reject
        txs = unGen arbitrary (mkQCGen caseSeed) caseSize :: [Tx]
        results = map p txs
        wireResult tx result =
          let raw = BL.toStrict (CBOR.toLazyByteString (Serialise.encode tx))
              outcome = case result of
                SubmitSuccess -> Left ()
                SubmitFail reject -> Right (BL.toStrict (CBOR.toLazyByteString (Serialise.encode reject)))
          in (raw, outcome)
        aliases = map (uncurry wireResult) (zip txs results)
        consistent = and
          [ wire /= wire' || result == result'
          | (index, (wire, result)) <- zip [0 :: Int ..] aliases
          , (wire', result') <- drop (index + 1) aliases
          ]
    if not consistent then fail "generated Tx wire aliases have conflicting SubmitResult values" else pure ()
    directResult <- quickCheckWithResult stdArgs { chatty = False }
      (once (prop_direct p txs))
    connectResult <- quickCheckWithResult stdArgs { chatty = False }
      (once (prop_connect p txs))
    channelResult <- quickCheckWithResult stdArgs { chatty = False }
      (once (prop_channel_ST p txs))
    forM_ (zip3 [0 :: Int ..] txs results) $ \(txIndex, tx, result) -> do
      let txWire = hex (BL.toStrict (CBOR.toLazyByteString (Serialise.encode tx)))
          (outcome, rejection) = case result of
            SubmitSuccess -> ("accepted", "")
            SubmitFail reject -> ("rejected", hex (BL.toStrict (CBOR.toLazyByteString (Serialise.encode reject))))
      hPutStrLn outputHandle $ intercalate "\t"
        [ "localtxsubmission-behavior-v1", show caseSeed, show ordinal, show caseSize
        , show txIndex, txWire, outcome, rejection
        , if isSuccess directResult then "pass" else "upstream-failure"
        , if isSuccess connectResult then "pass" else "upstream-failure"
        , if isSuccess channelResult then "pass" else "upstream-failure"
        ]
  where
    hex = concatMap (pad . (`showHex` "")) . BS.unpack
    pad [digit] = ['0', digit]
    pad digits = digits
''')
    local_tx_module = local_tx_submission.read_text()
    tx_pragma_anchor = "{-# LANGUAGE RankNTypes                 #-}\n"
    if local_tx_module.count(tx_pragma_anchor) != 1:
        raise SystemExit("LocalTxSubmission observer language pragma anchor changed")
    local_tx_module = local_tx_module.replace(
        tx_pragma_anchor,
        tx_pragma_anchor + "{-# LANGUAGE ScopedTypeVariables        #-}\n",
    )
    local_tx_submission.write_text(local_tx_module)
    local_tx_module = local_tx_submission.read_text()
    local_tx_export_anchor = "  ( tests\n"
    if local_tx_module.count(local_tx_export_anchor) != 1:
        raise SystemExit("LocalTxSubmission observer module export list changed")
    local_tx_submission.write_text(local_tx_module.replace(
        local_tx_export_anchor,
        "  ( tests\n  , exportLocalTxSubmissionCases\n  , exportLocalTxSubmissionSplitCases\n  , exportLocalTxSubmissionAnnotatedCases\n  , exportLocalTxSubmissionBehaviorCases\n",
    ))

    local_tx_monitor_original = inject(
        local_tx_monitor,
        "import Test.QuickCheck hiding (Result)\n",
        r'''

-- Disposable observer: retain the real AnyMessage generator and both codec properties.
exportLocalTxMonitorCases :: FilePath -> Int -> Int -> IO ()
exportLocalTxMonitorCases path count seed = withFile path WriteMode $ \outputHandle ->
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 30 + (ordinal `mod` 21)
        testCase = unGen arbitrary (mkQCGen caseSeed) caseSize
          :: AnyMessage (LocalTxMonitor TxId Tx SlotNo)
    codecResult <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 1000003), caseSize) }
      (once (prop_codecM_LocalTxMonitor testCase))
    cborResult <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 2000003), caseSize) }
      (once (prop_codec_cborM_LocalTxMonitor testCase))
    codecIdResult <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 5000003), caseSize) }
      (once (prop_codecIdM_LocalTxMonitor testCase))
    validCborResult <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 6000003), caseSize) }
      (once (prop_codec_valid_cbor_encoding_LocalTxMonitor testCase))
    case testCase of
      AnyMessage (msg :: Message (LocalTxMonitor TxId Tx SlotNo) st st') -> do
        let monitorCodec = codec
              :: Codec (LocalTxMonitor TxId Tx SlotNo) S.DeserialiseFailure IO ByteString
            raw = encode monitorCodec msg
            token = stateToken :: StateToken st
            (state, sender) = case token of
              SingIdle -> ("idle", "client")
              SingAcquiring -> ("acquiring", "server")
              SingAcquired -> ("acquired", "client")
              SingBusy SingNextTx -> ("busy-next-tx", "server")
              SingBusy SingHasTx -> ("busy-has-tx", "server")
              SingBusy SingGetSizes -> ("busy-get-sizes", "server")
              SingBusy SingGetMeasures -> ("busy-get-measures", "server")
              SingDone -> ("done", "none")
            tag = case msg of
              MsgAcquire -> "acquire"
              MsgAcquired _ -> "acquired"
              MsgAwaitAcquire -> "await-acquire"
              MsgNextTx -> "next-tx"
              MsgReplyNextTx _ -> "reply-next-tx"
              MsgHasTx _ -> "has-tx"
              MsgReplyHasTx _ -> "reply-has-tx"
              MsgGetSizes -> "get-sizes"
              MsgReplyGetSizes _ -> "reply-get-sizes"
              MsgGetMeasures -> "get-measures"
              MsgReplyGetMeasures _ -> "reply-get-measures"
              MsgRelease -> "release"
              MsgDone -> "done"
            codecOutcome = if isSuccess codecResult then "pass" else "upstream-failure"
            cborOutcome = if isSuccess cborResult then "pass" else "upstream-failure"
            codecIdOutcome = if isSuccess codecIdResult then "pass" else "upstream-failure"
            validCborOutcome = if isSuccess validCborResult then "pass" else "upstream-failure"
        hPutStrLn outputHandle $ intercalate "\t"
          [ "local-tx-monitor-codec-v1", show caseSeed, show ordinal, show caseSize
          , state, sender, tag, hex (BL.toStrict raw), codecOutcome, cborOutcome, codecIdOutcome, validCborOutcome
          ]
  where
    hex = concatMap (pad . (`showHex` "")) . BS.unpack
    pad [digit] = ['0', digit]
    pad digits = digits

-- Disposable observer: use the same generated message with unchanged split properties.
exportLocalTxMonitorSplitCases :: FilePath -> Int -> Int -> IO ()
exportLocalTxMonitorSplitCases path count seed = withFile path WriteMode $ \outputHandle ->
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 30 + (ordinal `mod` 21)
        testCase = unGen arbitrary (mkQCGen caseSeed) caseSize
          :: AnyMessage (LocalTxMonitor TxId Tx SlotNo)
    split2Result <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 3000003), caseSize) }
      (once (prop_codec_splitsM_LocalTxMonitor splits2 testCase))
    split3Result <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 4000003), caseSize) }
      (once (prop_codec_splitsM_LocalTxMonitor splits3 testCase))
    case testCase of
      AnyMessage (msg :: Message (LocalTxMonitor TxId Tx SlotNo) st st') -> do
        let monitorCodec = codec
              :: Codec (LocalTxMonitor TxId Tx SlotNo) S.DeserialiseFailure IO ByteString
            raw = BL.toStrict (encode monitorCodec msg)
            token = stateToken :: StateToken st
            (state, sender) = case token of
              SingIdle -> ("idle", "client")
              SingAcquiring -> ("acquiring", "server")
              SingAcquired -> ("acquired", "client")
              SingBusy SingNextTx -> ("busy-next-tx", "server")
              SingBusy SingHasTx -> ("busy-has-tx", "server")
              SingBusy SingGetSizes -> ("busy-get-sizes", "server")
              SingBusy SingGetMeasures -> ("busy-get-measures", "server")
              SingDone -> ("done", "none")
            tag = case msg of
              MsgAcquire -> "acquire"
              MsgAcquired _ -> "acquired"
              MsgAwaitAcquire -> "await-acquire"
              MsgNextTx -> "next-tx"
              MsgReplyNextTx _ -> "reply-next-tx"
              MsgHasTx _ -> "has-tx"
              MsgReplyHasTx _ -> "reply-has-tx"
              MsgGetSizes -> "get-sizes"
              MsgReplyGetSizes _ -> "reply-get-sizes"
              MsgGetMeasures -> "get-measures"
              MsgReplyGetMeasures _ -> "reply-get-measures"
              MsgRelease -> "release"
              MsgDone -> "done"
            split2Outcome = if isSuccess split2Result then "pass" else "upstream-failure"
            split3Outcome = if isSuccess split3Result then "pass" else "upstream-failure"
        hPutStrLn outputHandle $ intercalate "\t"
          [ "local-tx-monitor-splits-v1", show caseSeed, show ordinal, show caseSize
          , state, sender, tag, hex raw, split2Outcome, split3Outcome
          ]
  where
    hex = concatMap (pad . (`showHex` "")) . BS.unpack
    pad [digit] = ['0', digit]
    pad digits = digits

-- Disposable observer: retain exact generated inputs and unchanged property verdicts.
exportLocalTxMonitorBehaviorCases :: FilePath -> Int -> Int -> IO ()
exportLocalTxMonitorBehaviorCases path count seed = withFile path WriteMode $ \outputHandle ->
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 8 + (ordinal `mod` 9)
        testCase = unGen arbitrary (mkQCGen caseSeed) caseSize :: (SlotNo, [Tx])
        (slot, txs) = testCase
        txRows =
          [ (show txIndex, hex (BL.toStrict (CBOR.toLazyByteString (S.encode tx)))
            , hex (BL.toStrict (CBOR.toLazyByteString (S.encode (txId tx)))))
          | (txIndex, tx) <- zip [0 :: Int ..] txs
          ]
        rows = if null txRows then [("-1", "", "")] else txRows
    directResult <- quickCheckWithResult stdArgs { chatty = False }
      (once (prop_direct testCase))
    connectResult <- quickCheckWithResult stdArgs { chatty = False }
      (once (prop_connect testCase))
    channelResult <- quickCheckWithResult stdArgs { chatty = False }
      (once (prop_channel_ST testCase))
    forM_ rows $ \(txIndex, txWire, idWire) ->
      hPutStrLn outputHandle $ intercalate "\t"
        [ "localtxmonitor-behavior-v1", show caseSeed, show ordinal, show caseSize
        , hex (BL.toStrict (CBOR.toLazyByteString (S.encode slot))), txIndex, txWire, idWire
        , if null txs then "empty" else "transaction"
        , if isSuccess directResult then "pass" else "upstream-failure"
        , if isSuccess connectResult then "pass" else "upstream-failure"
        , if isSuccess channelResult then "pass" else "upstream-failure"
        ]
  where
    hex = concatMap (pad . (`showHex` "")) . BS.unpack
    pad [digit] = ['0', digit]
    pad digits = digits

''',
        "import Codec.CBOR.Write qualified as CBOR\n"
        "import Control.Monad (forM_)\n"
        "import Data.ByteString qualified as BS\n"
        "import Data.ByteString.Lazy qualified as BL\n"
        "import Data.List (intercalate)\n"
        "import Numeric (showHex)\n"
        "import System.IO (withFile, IOMode (WriteMode), hPutStrLn)\n"
        "import Test.QuickCheck.Gen (unGen)\n"
        "import Test.QuickCheck.Random (mkQCGen)\n",
    )
    local_tx_monitor_module = local_tx_monitor.read_text()
    local_tx_monitor_export_anchor = "module Ouroboros.Network.Protocol.LocalTxMonitor.Test (tests) where"
    if local_tx_monitor_module.count(local_tx_monitor_export_anchor) != 1:
        raise SystemExit("LocalTxMonitor observer module export anchor changed")
    local_tx_monitor.write_text(local_tx_monitor_module.replace(
        local_tx_monitor_export_anchor,
        "module Ouroboros.Network.Protocol.LocalTxMonitor.Test (tests, exportLocalTxMonitorCases, exportLocalTxMonitorSplitCases, exportLocalTxMonitorBehaviorCases) where",
    ))

    tx_submission2_original = inject(
        tx_submission2,
        "import Test.QuickCheck as QC\n",
        r'''

-- Disposable observer: preserve the original typed-protocol generator, codec, and properties.
exportTxSubmission2Cases :: FilePath -> Int -> Int -> IO ()
exportTxSubmission2Cases path count seed = withFile path WriteMode $ \outputHandle ->
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 30 + (ordinal `mod` 21)
        testCase = unGen arbitrary (mkQCGen caseSeed) caseSize
          :: AnyMessage (TxSubmission2 TxId Tx)
    codecResult <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 1000003), caseSize) }
      (once (prop_codec testCase))
    cborResult <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 2000003), caseSize) }
      (once (prop_codec_cbor testCase))
    codecIdResult <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 5000003), caseSize) }
      (once (prop_codec_id testCase))
    validCborResult <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 6000003), caseSize) }
      (once (prop_codec_valid_cbor testCase))
    case testCase of
      AnyMessage (msg :: Message (TxSubmission2 TxId Tx) st st') -> do
        let txCodec = codec_v2
              :: Codec (TxSubmission2 TxId Tx) DeserialiseFailure IO ByteString
            raw = encode txCodec msg
            token = stateToken :: StateToken st
            (state, sender) = case token of
              SingInit -> ("init", "client")
              SingIdle -> ("idle", "server")
              SingTxIds SingBlocking -> ("ids-blocking", "client")
              SingTxIds SingNonBlocking -> ("ids-nonblocking", "client")
              SingTxs -> ("txs", "client")
              SingDone -> ("done", "none")
            tag = case msg of
              MsgInit -> "init"
              MsgRequestTxIds SingBlocking _ _ -> "request-ids-blocking"
              MsgRequestTxIds SingNonBlocking _ _ -> "request-ids-nonblocking"
              MsgReplyTxIds _ -> "reply-ids"
              MsgRequestTxs _ -> "request-txs"
              MsgReplyTxs _ -> "reply-txs"
              MsgDone -> "done"
            codecOutcome = if isSuccess codecResult then "pass" else "upstream-failure"
            cborOutcome = if isSuccess cborResult then "pass" else "upstream-failure"
            codecIdOutcome = if isSuccess codecIdResult then "pass" else "upstream-failure"
            validCborOutcome = if isSuccess validCborResult then "pass" else "upstream-failure"
        hPutStrLn outputHandle $ intercalate "\t"
          [ "txsubmission2-codec-v1", show caseSeed, show ordinal, show caseSize
          , state, sender, tag, hex (BL.toStrict raw), codecOutcome, cborOutcome, codecIdOutcome, validCborOutcome
          ]
  where
    hex = concatMap (pad . (`showHex` "")) . BS.unpack
    pad [digit] = ['0', digit]
    pad digits = digits

-- Disposable observer: replay the unchanged codec split properties for each seeded message.
exportTxSubmission2SplitCases :: FilePath -> Int -> Int -> IO ()
exportTxSubmission2SplitCases path count seed = withFile path WriteMode $ \outputHandle ->
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 30 + (ordinal `mod` 21)
        testCase = unGen arbitrary (mkQCGen caseSeed) caseSize
          :: AnyMessage (TxSubmission2 TxId Tx)
    split2Result <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 3000003), caseSize) }
      (once (prop_codec_splits2 testCase))
    split3Result <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 4000003), caseSize) }
      (once (prop_codec_splits3 testCase))
    case testCase of
      AnyMessage (msg :: Message (TxSubmission2 TxId Tx) st st') -> do
        let txCodec = codec_v2
              :: Codec (TxSubmission2 TxId Tx) DeserialiseFailure IO ByteString
            raw = encode txCodec msg
            token = stateToken :: StateToken st
            (state, sender) = case token of
              SingInit -> ("init", "client")
              SingIdle -> ("idle", "server")
              SingTxIds SingBlocking -> ("ids-blocking", "client")
              SingTxIds SingNonBlocking -> ("ids-nonblocking", "client")
              SingTxs -> ("txs", "client")
              SingDone -> ("done", "none")
            tag = case msg of
              MsgInit -> "init"
              MsgRequestTxIds SingBlocking _ _ -> "request-ids-blocking"
              MsgRequestTxIds SingNonBlocking _ _ -> "request-ids-nonblocking"
              MsgReplyTxIds _ -> "reply-ids"
              MsgRequestTxs _ -> "request-txs"
              MsgReplyTxs _ -> "reply-txs"
              MsgDone -> "done"
            split2Outcome = if isSuccess split2Result then "pass" else "upstream-failure"
            split3Outcome = if isSuccess split3Result then "pass" else "upstream-failure"
        hPutStrLn outputHandle $ intercalate "\t"
          [ "txsubmission2-splits-v1", show caseSeed, show ordinal, show caseSize
          , state, sender, tag, hex (BL.toStrict raw), split2Outcome, split3Outcome
          ]
  where
    hex = concatMap (pad . (`showHex` "")) . BS.unpack
    pad [digit] = ['0', digit]
    pad digits = digits


-- Disposable observer: retain the distinct WithBytes generator and annotated codec laws.
exportTxSubmission2AnnotatedCases :: FilePath -> Int -> Int -> IO ()
exportTxSubmission2AnnotatedCases path count seed = withFile path WriteMode $ \outputHandle ->
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 30 + (ordinal `mod` 21)
        testCase = unGen arbitrary (mkQCGen caseSeed) caseSize
          :: AnyMessage (TxSubmission2 TxId (WithBytes Tx))
    annResult <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 7000003), caseSize) }
      (once (prop_anncodec testCase))
    split2Result <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 8000003), caseSize) }
      (once (prop_anncodec_splits2 testCase))
    split3Result <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 9000003), caseSize) }
      (once (prop_anncodec_splits3 testCase))
    case testCase of
      AnyMessage (msg :: Message (TxSubmission2 TxId (WithBytes Tx)) st st') -> do
        let Codec { encode } = anncodec_v2
              :: AnnotatedCodec (TxSubmission2 TxId (WithBytes Tx))
                   DeserialiseFailure IO ByteString
            raw = BL.toStrict (encode msg)
            token = stateToken :: StateToken st
            (state, sender) = case token of
              SingInit -> ("init", "client")
              SingIdle -> ("idle", "server")
              SingTxIds SingBlocking -> ("ids-blocking", "client")
              SingTxIds SingNonBlocking -> ("ids-nonblocking", "client")
              SingTxs -> ("txs", "client")
              SingDone -> ("done", "none")
            tag = case msg of
              MsgInit -> "init"
              MsgRequestTxIds SingBlocking _ _ -> "request-ids-blocking"
              MsgRequestTxIds SingNonBlocking _ _ -> "request-ids-nonblocking"
              MsgReplyTxIds _ -> "reply-ids"
              MsgRequestTxs _ -> "request-txs"
              MsgReplyTxs _ -> "reply-txs"
              MsgDone -> "done"
            annOutcome = if isSuccess annResult then "pass" else "upstream-failure"
            split2Outcome = if isSuccess split2Result then "pass" else "upstream-failure"
            split3Outcome = if isSuccess split3Result then "pass" else "upstream-failure"
        hPutStrLn outputHandle $ intercalate "\t"
          [ "txsubmission2-annotated-v1", show caseSeed, show ordinal, show caseSize
          , state, sender, tag, hex raw, annOutcome, split2Outcome, split3Outcome
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
        "import Test.QuickCheck.Random (mkQCGen)\n",
    )
    tx_submission2_original = tx_submission2.read_text()
    tx_behavior_anchor = "-- Disposable observer: preserve the original typed-protocol generator, codec, and properties.\n"
    tx_behavior_observer = r'''
-- Disposable observer: export original TxSubmission2 test params and their
-- unchanged direct/connect/channel property verdicts for Scarab runtime replay.
exportTxSubmission2BehaviorCases :: FilePath -> Int -> Int -> IO ()
exportTxSubmission2BehaviorCases path count seed = withFile path WriteMode $ \outputHandle ->
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 4 + (ordinal `mod` 9)
        params = unGen arbitrary (mkQCGen caseSeed) caseSize :: TxSubmissionTestParams
        choices = unGen arbitrary (mkQCGen (caseSeed + 3000007)) caseSize :: NonEmptyList Bool
    directResult <- quickCheckWithResult stdArgs { chatty = False }
      (once (prop_direct params))
    connectResult <- quickCheckWithResult stdArgs { chatty = False }
      (once (prop_connect1 params))
    pipelinedResult <- quickCheckWithResult stdArgs { chatty = False }
      (once (prop_connect2 params choices))
    channelResult <- quickCheckWithResult stdArgs { chatty = False }
      (once (prop_channel_ST params))
    case params of
      TxSubmissionTestParams
        { testMaxUnacked = Positive (Small maxUnacked)
        , testMaxTxIdsToRequest = Positive (Small maxIdsToRequest)
        , testMaxTxToRequest = Positive (Small maxTxToRequest)
        , testTransactions = DistinctList txs
        } -> do
          let pairs = intercalate ";"
                [ hex (BL.toStrict (CBOR.toLazyByteString (Serialise.encode (txId tx)))) <> ":" <>
                  hex (BL.toStrict (CBOR.toLazyByteString (Serialise.encode tx)))
                | tx <- txs
                ]
          hPutStrLn outputHandle $ intercalate "\t"
            [ "txsubmission2-behavior-v2", show caseSeed, show ordinal, show caseSize
            , show maxUnacked, show maxIdsToRequest, show maxTxToRequest
            , if isSuccess directResult then "pass" else "upstream-failure"
            , if isSuccess connectResult then "pass" else "upstream-failure"
            , if isSuccess pipelinedResult then "pass" else "upstream-failure"
            , if isSuccess channelResult then "pass" else "upstream-failure"
            , show (length txs), pairs
            , map (\choice -> if choice then '1' else '0') (getNonEmpty choices)
            ]
  where
    hex = concatMap (pad . (`showHex` "")) . BS.unpack
    pad [digit] = ['0', digit]
    pad digits = digits

'''
    if tx_submission2_original.count(tx_behavior_anchor) != 1:
        raise SystemExit("TxSubmission2 behavior observer insertion anchor changed")
    tx_submission2.write_text(tx_submission2_original.replace(
        tx_behavior_anchor,
        tx_behavior_observer + tx_behavior_anchor,
    ))
    tx_submission2_module = tx_submission2.read_text()
    tx_scoped_anchor = "{-# LANGUAGE RankNTypes                 #-}\n"
    if tx_submission2_module.count(tx_scoped_anchor) != 1:
        raise SystemExit("TxSubmission2 observer language pragma anchor changed")
    tx_submission2_module = tx_submission2_module.replace(
        tx_scoped_anchor,
        tx_scoped_anchor + "{-# LANGUAGE ScopedTypeVariables        #-}\n",
    )
    tx_submission2.write_text(tx_submission2_module)
    tx_submission2_module = tx_submission2.read_text()
    tx_submission2_export_anchor = "  , labelMsg\n"
    if tx_submission2_module.count(tx_submission2_export_anchor) != 1:
        raise SystemExit("TxSubmission2 observer module export list changed")
    tx_submission2.write_text(tx_submission2_module.replace(
        tx_submission2_export_anchor,
        "  , labelMsg\n  , exportTxSubmission2Cases\n  , exportTxSubmission2SplitCases\n  , exportTxSubmission2AnnotatedCases\n  , exportTxSubmission2BehaviorCases\n",
    ))

    chain_module = chain.read_text()
    chain_export_anchor = "module Ouroboros.Network.Protocol.ChainSync.Test (tests) where"
    if chain_module.count(chain_export_anchor) != 1:
        raise SystemExit("ChainSync observer module export anchor changed")
    chain.write_text(chain_module.replace(
        chain_export_anchor,
        "module Ouroboros.Network.Protocol.ChainSync.Test (tests, exportChainSyncCases, exportChainSyncSplitCases, exportChainSyncBinaryCases, exportChainSyncBehaviorCases, exportChainSyncIOBehaviorCases, exportChainSyncPipelinedMaxCases, exportChainSyncPipelinedMinCases, exportChainSyncPipelinedLowHighCases, exportChainSyncPipelinedDirectConnectCases, exportChainSyncPipelinedMinBufferedCases, exportChainSyncDemoPipelinedIOCases) where",
    ))
    chain.write_text(chain.read_text() + r'''

-- Disposable observer: execute both original binary-compatibility properties
-- and retain the wire emitted by each property's original generator domain.
exportChainSyncBinaryCases :: FilePath -> Int -> Int -> IO ()
exportChainSyncBinaryCases path count seed = withFile path WriteMode $ \outputHandle -> do
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 30 + (ordinal `mod` 21)
        testCase = unGen arbitrary (mkQCGen caseSeed) caseSize :: AnyMessage ChainSync_BlockHeader
    result <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 5000003), caseSize) }
      (once (prop_codec_binary_compat_ChainSync_ChainSyncSerialised testCase))
    case testCase of
      AnyMessage (msg :: Message ChainSync_BlockHeader st st') -> do
        let wrappedCodec = codecWrapped
              :: Codec (ChainSync BlockHeader (Point BlockHeader) (Tip BlockHeader))
                   S.DeserialiseFailure IO ByteString
            raw = encode wrappedCodec msg
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
        hPutStrLn outputHandle $ intercalate "\t"
          [ "chainsync-binary-v1", show caseSeed, show ordinal, show caseSize
          , "codec/codecSerialised", state, sender, tag, hex (BL.toStrict raw)
          , if isSuccess result then "pass" else "upstream-failure"
          ]
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 30 + (ordinal `mod` 21)
        testCase = unGen arbitrary (mkQCGen caseSeed) caseSize :: AnyMessage ChainSync_Serialised_BlockHeader
    result <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 6000003), caseSize) }
      (once (prop_codec_binary_compat_ChainSyncSerialised_ChainSync testCase))
    case testCase of
      AnyMessage (msg :: Message ChainSync_Serialised_BlockHeader st st') -> do
        let serialisedCodec = codecSerialised
              :: Codec (ChainSync (Serialised BlockHeader) (Point BlockHeader) (Tip BlockHeader))
                   S.DeserialiseFailure IO ByteString
            raw = encode serialisedCodec msg
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
        hPutStrLn outputHandle $ intercalate "\t"
          [ "chainsync-binary-v1", show caseSeed, show ordinal, show caseSize
          , "codecSerialised/codec", state, sender, tag, hex (BL.toStrict raw)
          , if isSuccess result then "pass" else "upstream-failure"
          ]
  where
    hex = concatMap (pad . (`showHex` "")) . BS.unpack
    pad [digit] = ['0', digit]
    pad digits = digits
''')
    chain.write_text(chain.read_text() + r'''

-- Disposable observer: retain the unchanged generated fork cases and actual
-- PipeliningDepth inputs for the original demoPipelinedMax ST property.
exportChainSyncPipelinedMaxCases :: FilePath -> Int -> Int -> IO ()
exportChainSyncPipelinedMaxCases path count seed = withFile path WriteMode $ \outputHandle ->
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 30 + (ordinal `mod` 21)
        input = unGen arbitrary (mkQCGen caseSeed) caseSize
          :: ChainProducerStateForkTest
        depthSeed = caseSeed + 7000003
        depth@(PipeliningDepth maxDepth) = unGen arbitrary (mkQCGen depthSeed) caseSize
    outcome <- if maxDepth == 0
      then pure "precondition-discard"
      else do
        result <- quickCheckWithResult
          stdArgs { chatty = False }
          (once (propChainSyncDemoPipelinedMaxST input depth))
        pure (if isSuccess result then "pass" else "upstream-failure")
    hPutStrLn outputHandle $ intercalate "\t"
      [ "chainsync-pipelined-max-v1", show caseSeed, show ordinal
      , show caseSize, show depthSeed, show maxDepth, outcome
      ]
''')
    chain.write_text(chain.read_text() + r'''

-- Disposable observer for the unchanged non-pipelined IO and pipe properties.
-- The full generated fork cases remain in chainsync-behavior v2 fixtures.
exportChainSyncIOBehaviorCases :: FilePath -> Int -> Int -> IO ()
exportChainSyncIOBehaviorCases path count seed = withFile path WriteMode $ \outputHandle ->
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 30 + (ordinal `mod` 21)
        input = unGen arbitrary (mkQCGen caseSeed) caseSize
          :: ChainProducerStateForkTest
    directIO <- quickCheckWithResult stdArgs { chatty = False }
      (once (propChainSyncDirectIO input))
    connectIO <- quickCheckWithResult stdArgs { chatty = False }
      (once (propChainSyncConnectIO input))
    demoIO <- quickCheckWithResult stdArgs { chatty = False }
      (once (propChainSyncDemoIO input))
    pipeIO <- quickCheckWithResult stdArgs { chatty = False }
      (once (propChainSyncPipe input))
    hPutStrLn outputHandle $ intercalate "\t"
      [ "chainsync-behavior-io-v1", show caseSeed, show ordinal, show caseSize
      , if isSuccess directIO then "pass" else "upstream-failure"
      , if isSuccess connectIO then "pass" else "upstream-failure"
      , if isSuccess demoIO then "pass" else "upstream-failure"
      , if isSuccess pipeIO then "pass" else "upstream-failure"
      ]
''')
    chain.write_text(chain.read_text() + r'''

-- Disposable observer for the unchanged min-pipelining property over the same
-- generated fork/depth pairs as exportChainSyncPipelinedMaxCases.
exportChainSyncPipelinedMinCases :: FilePath -> Int -> Int -> IO ()
exportChainSyncPipelinedMinCases path count seed = withFile path WriteMode $ \outputHandle ->
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 30 + (ordinal `mod` 21)
        input = unGen arbitrary (mkQCGen caseSeed) caseSize
          :: ChainProducerStateForkTest
        depthSeed = caseSeed + 7000003
        depth@(PipeliningDepth maxDepth) = unGen arbitrary (mkQCGen depthSeed) caseSize
    outcome <- if maxDepth == 0
      then pure "precondition-discard"
      else do
        result <- quickCheckWithResult
          stdArgs { chatty = False }
          (once (propChainSyncDemoPipelinedMinST input depth))
        pure (if isSuccess result then "pass" else "upstream-failure")
    hPutStrLn outputHandle $ intercalate "\t"
      [ "chainsync-pipelined-min-v1", show caseSeed, show ordinal
      , show caseSize, show depthSeed, show maxDepth, outcome
      ]
''')
    chain.write_text(chain.read_text() + r'''

-- Disposable observer for the unchanged low/high property and both original
-- PipeliningDepth-domain values. The property itself orders the two marks.
exportChainSyncPipelinedLowHighCases :: FilePath -> Int -> Int -> IO ()
exportChainSyncPipelinedLowHighCases path count seed = withFile path WriteMode $ \outputHandle ->
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 30 + (ordinal `mod` 21)
        input = unGen arbitrary (mkQCGen caseSeed) caseSize
          :: ChainProducerStateForkTest
        firstDepthSeed = caseSeed + 7000003
        firstDepth@(PipeliningDepth firstDepthValue) = unGen arbitrary (mkQCGen firstDepthSeed) caseSize
        secondDepthSeed = caseSeed + 8000003
        secondDepth@(PipeliningDepth secondDepthValue) = unGen arbitrary (mkQCGen secondDepthSeed) caseSize
        highDepth = max firstDepthValue secondDepthValue
    outcome <- if highDepth == 0
      then pure "precondition-discard"
      else do
        result <- quickCheckWithResult
          stdArgs { chatty = False }
          (once (propChainSyncDemoPipelinedLowHighST input firstDepth secondDepth))
        pure (if isSuccess result then "pass" else "upstream-failure")
    hPutStrLn outputHandle $ intercalate "\t"
      [ "chainsync-pipelined-low-high-v1", show caseSeed, show ordinal
      , show caseSize, show firstDepthSeed, show firstDepthValue
      , show secondDepthSeed, show secondDepthValue, outcome
      ]
''')
    chain.write_text(chain.read_text() + r'''

-- Disposable observer for unchanged direct/connect max/min pipelining ST
-- properties. Preserve the generated connect scheduling-choice vector.
exportChainSyncPipelinedDirectConnectCases :: FilePath -> Int -> Int -> IO ()
exportChainSyncPipelinedDirectConnectCases path count seed = withFile path WriteMode $ \outputHandle ->
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 30 + (ordinal `mod` 21)
        input = unGen arbitrary (mkQCGen caseSeed) caseSize
          :: ChainProducerStateForkTest
        depthSeed = caseSeed + 7000003
        depth@(PipeliningDepth maxDepth) = unGen arbitrary (mkQCGen depthSeed) caseSize
        choicesSeed = caseSeed + 9000007
        choices = unGen arbitrary (mkQCGen choicesSeed) caseSize :: [Bool]
        encodedChoices = map (\choice -> if choice then '1' else '0') choices
    maxDirect <- quickCheckWithResult stdArgs { chatty = False }
      (once (propChainSyncPipelinedMaxDirectST input depth))
    minDirect <- quickCheckWithResult stdArgs { chatty = False }
      (once (propChainSyncPipelinedMinDirectST input depth))
    maxConnect <- quickCheckWithResult stdArgs { chatty = False }
      (once (propChainSyncPipelinedMaxConnectST input choices depth))
    minConnect <- quickCheckWithResult stdArgs { chatty = False }
      (once (propChainSyncPipelinedMinConnectST input choices depth))
    maxDirectIO <- quickCheckWithResult stdArgs { chatty = False }
      (once (propChainSyncPipelinedMaxDirectIO input depth))
    minDirectIO <- quickCheckWithResult stdArgs { chatty = False }
      (once (propChainSyncPipelinedMinDirectIO input depth))
    maxConnectIO <- quickCheckWithResult stdArgs { chatty = False }
      (once (propChainSyncPipelinedMaxConnectIO input choices depth))
    minConnectIO <- quickCheckWithResult stdArgs { chatty = False }
      (once (propChainSyncPipelinedMinConnectIO input choices depth))
    hPutStrLn outputHandle $ intercalate "\t"
      [ "chainsync-pipelined-direct-connect-v1", show caseSeed, show ordinal
      , show caseSize, show depthSeed, show maxDepth, show choicesSeed, encodedChoices
      , if isSuccess maxDirect then "pass" else "upstream-failure"
      , if isSuccess minDirect then "pass" else "upstream-failure"
      , if isSuccess maxConnect then "pass" else "upstream-failure"
      , if isSuccess minConnect then "pass" else "upstream-failure"
      , if isSuccess maxDirectIO then "pass" else "upstream-failure"
      , if isSuccess minDirectIO then "pass" else "upstream-failure"
      , if isSuccess maxConnectIO then "pass" else "upstream-failure"
      , if isSuccess minConnectIO then "pass" else "upstream-failure"
      ]
''')
    chain.write_text(chain.read_text() + r'''

-- Disposable observer for the unchanged connected buffered-channel IO
-- property. Preserve both generated depths; the property uses min as buffer
-- capacity and max as the client depth.
exportChainSyncPipelinedMinBufferedCases :: FilePath -> Int -> Int -> IO ()
exportChainSyncPipelinedMinBufferedCases path count seed = withFile path WriteMode $ \outputHandle ->
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 30 + (ordinal `mod` 21)
        input = unGen arbitrary (mkQCGen caseSeed) caseSize
          :: ChainProducerStateForkTest
        firstDepthSeed = caseSeed + 7000003
        firstDepth@(PipeliningDepth firstDepthValue) = unGen arbitrary (mkQCGen firstDepthSeed) caseSize
        secondDepthSeed = caseSeed + 8000003
        secondDepth@(PipeliningDepth secondDepthValue) = unGen arbitrary (mkQCGen secondDepthSeed) caseSize
        minDepth = min firstDepthValue secondDepthValue
    outcome <- if minDepth <= 0
      then pure "precondition-discard"
      else do
        result <- quickCheckWithResult
          stdArgs { chatty = False }
          (once (propChainSyncDemoPipelinedMinBufferedIO input firstDepth secondDepth))
        pure (if isSuccess result then "pass" else "upstream-failure")
    hPutStrLn outputHandle $ intercalate "\t"
      [ "chainsync-pipelined-min-buffered-v1", show caseSeed, show ordinal
      , show caseSize, show firstDepthSeed, show firstDepthValue
      , show secondDepthSeed, show secondDepthValue, outcome
      ]
''')
    chain.write_text(chain.read_text() + r'''

-- Disposable observer for the unchanged demo max/min/low-high IO properties.
-- Preserve both generated depth values and each property outcome separately.
exportChainSyncDemoPipelinedIOCases :: FilePath -> Int -> Int -> IO ()
exportChainSyncDemoPipelinedIOCases path count seed = withFile path WriteMode $ \outputHandle ->
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 30 + (ordinal `mod` 21)
        input = unGen arbitrary (mkQCGen caseSeed) caseSize
          :: ChainProducerStateForkTest
        firstDepthSeed = caseSeed + 7000003
        firstDepth@(PipeliningDepth firstDepthValue) = unGen arbitrary (mkQCGen firstDepthSeed) caseSize
        secondDepthSeed = caseSeed + 8000003
        secondDepth@(PipeliningDepth secondDepthValue) = unGen arbitrary (mkQCGen secondDepthSeed) caseSize
    maxIO <- if firstDepthValue == 0 then pure "precondition-discard" else do
      result <- quickCheckWithResult stdArgs { chatty = False }
        (once (propChainSyncDemoPipelinedMaxIO input firstDepth))
      pure (if isSuccess result then "pass" else "upstream-failure")
    minIO <- if firstDepthValue == 0 then pure "precondition-discard" else do
      result <- quickCheckWithResult stdArgs { chatty = False }
        (once (propChainSyncDemoPipelinedMinIO input firstDepth))
      pure (if isSuccess result then "pass" else "upstream-failure")
    lowHighIO <- if max firstDepthValue secondDepthValue == 0 then pure "precondition-discard" else do
      result <- quickCheckWithResult stdArgs { chatty = False }
        (once (propChainSyncDemoPipelinedLowHighIO input firstDepth secondDepth))
      pure (if isSuccess result then "pass" else "upstream-failure")
    hPutStrLn outputHandle $ intercalate "\t"
      [ "chainsync-demo-pipelined-io-v1", show caseSeed, show ordinal
      , show caseSize, show firstDepthSeed, show firstDepthValue
      , show secondDepthSeed, show secondDepthValue, maxIO, minIO, lowHighIO
      ]
''')
    block_fetch.write_text(block_fetch.read_text() + r'''

-- Disposable observer for the two original binary-compatibility directions.
exportBlockFetchBinaryCases :: FilePath -> Int -> Int -> IO ()
exportBlockFetchBinaryCases path count seed = withFile path WriteMode $ \outputHandle -> do
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 30 + (ordinal `mod` 21)
        testCase = unGen arbitrary (mkQCGen caseSeed) caseSize
          :: AnyMessage (BlockFetch Block (Point Block))
    result <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 5000003), caseSize) }
      (once (prop_codec_binary_compat_BlockFetch_BlockFetchSerialised testCase))
    case testCase of
      AnyMessage (msg :: Message (BlockFetch Block (Point Block)) st st') -> do
        let wrappedCodec = codecWrapped
              :: Codec (BlockFetch Block (Point Block)) S.DeserialiseFailure IO ByteString
            raw = encode wrappedCodec msg
            token = stateToken :: StateToken st
            (state, sender) = case token of
              SingBFIdle -> ("idle", "client")
              SingBFBusy -> ("busy", "server")
              SingBFStreaming -> ("streaming", "server")
              SingBFDone -> ("done", "client")
            tag = case msg of
              MsgRequestRange _ -> "request-range"
              MsgClientDone -> "client-done"
              MsgStartBatch -> "start-batch"
              MsgNoBlocks -> "no-blocks"
              MsgBlock _ -> "block"
              MsgBatchDone -> "batch-done"
        hPutStrLn outputHandle $ intercalate "\t"
          [ "blockfetch-binary-v1", show caseSeed, show ordinal, show caseSize
          , "codec/codecSerialised", state, sender, tag, hex (BL.toStrict raw)
          , if isSuccess result then "pass" else "upstream-failure"
          ]
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        caseSize = 30 + (ordinal `mod` 21)
        testCase = unGen arbitrary (mkQCGen caseSeed) caseSize
          :: AnyMessage (BlockFetch (Serialised Block) (Point Block))
    result <- quickCheckWithResult
      stdArgs { chatty = False, replay = Just (mkQCGen (caseSeed + 6000003), caseSize) }
      (once (prop_codec_binary_compat_BlockFetchSerialised_BlockFetch testCase))
    case testCase of
      AnyMessage (msg :: Message (BlockFetch (Serialised Block) (Point Block)) st st') -> do
        let serialisedCodec = codecSerialised
              :: Codec (BlockFetch (Serialised Block) (Point Block))
                   S.DeserialiseFailure IO ByteString
            raw = encode serialisedCodec msg
            token = stateToken :: StateToken st
            (state, sender) = case token of
              SingBFIdle -> ("idle", "client")
              SingBFBusy -> ("busy", "server")
              SingBFStreaming -> ("streaming", "server")
              SingBFDone -> ("done", "client")
            tag = case msg of
              MsgRequestRange _ -> "request-range"
              MsgClientDone -> "client-done"
              MsgStartBatch -> "start-batch"
              MsgNoBlocks -> "no-blocks"
              MsgBlock _ -> "block"
              MsgBatchDone -> "batch-done"
        hPutStrLn outputHandle $ intercalate "\t"
          [ "blockfetch-binary-v1", show caseSeed, show ordinal, show caseSize
          , "codecSerialised/codec", state, sender, tag, hex (BL.toStrict raw)
          , if isSuccess result then "pass" else "upstream-failure"
          ]
  where
    hex = concatMap (pad . (`showHex` "")) . BS.unpack
    pad [digit] = ['0', digit]
    pad digits = digits
''')
    main = overlay / "ouroboros-network/protocols/tests/Main.hs"
    main_original = main.read_text()
    dispatch_imports = (
        "import Ouroboros.Network.Protocol.KeepAlive.Test qualified as KeepAlive (tests, exportKeepAliveCases, exportKeepAliveSplitCases, exportKeepAliveBehaviorCases)\n"
        "import Ouroboros.Network.Protocol.PeerSharing.Test qualified as PeerSharing (tests, exportPeerSharingCases)\n"
        "import Ouroboros.Network.Protocol.ChainSync.Test qualified as ChainSync (tests, exportChainSyncCases, exportChainSyncSplitCases, exportChainSyncBinaryCases, exportChainSyncBehaviorCases, exportChainSyncIOBehaviorCases, exportChainSyncPipelinedMaxCases, exportChainSyncPipelinedMinCases, exportChainSyncPipelinedLowHighCases, exportChainSyncPipelinedDirectConnectCases, exportChainSyncPipelinedMinBufferedCases, exportChainSyncDemoPipelinedIOCases)\n"
        "import Ouroboros.Network.Protocol.BlockFetch.Test qualified as BlockFetch (tests, exportBlockFetchCases, exportBlockFetchSplitCases, exportBlockFetchBinaryCases, exportBlockFetchBehaviorCases)\n"
        "import Ouroboros.Network.Protocol.LocalStateQuery.Test qualified as LocalStateQuery (tests, exportLocalStateQueryCases, exportLocalStateQuerySplitCases, exportLocalStateQueryBehaviorCases)\n"
        "import Ouroboros.Network.Protocol.LocalTxSubmission.Test qualified as LocalTxSubmission (tests, exportLocalTxSubmissionCases, exportLocalTxSubmissionSplitCases, exportLocalTxSubmissionAnnotatedCases, exportLocalTxSubmissionBehaviorCases)\n"
        "import Ouroboros.Network.Protocol.LocalTxMonitor.Test qualified as LocalTxMonitor (tests, exportLocalTxMonitorCases, exportLocalTxMonitorSplitCases, exportLocalTxMonitorBehaviorCases)\n"
        "import Ouroboros.Network.Protocol.TxSubmission2.Test qualified as TxSubmission2 (tests, exportTxSubmission2Cases, exportTxSubmission2SplitCases, exportTxSubmission2AnnotatedCases, exportTxSubmission2BehaviorCases)\n"
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
        "import Ouroboros.Network.Protocol.BlockFetch.Test qualified (tests)\n", ""
    )
    main_text = main_text.replace(
        "import Ouroboros.Network.Protocol.LocalStateQuery.Test qualified (tests)\n", ""
    )
    main_text = main_text.replace(
        "import Ouroboros.Network.Protocol.LocalTxSubmission.Test qualified (tests)\n", ""
    )
    main_text = main_text.replace(
        "import Ouroboros.Network.Protocol.LocalTxMonitor.Test qualified (tests)\n", ""
    )
    main_text = main_text.replace(
        "import Ouroboros.Network.Protocol.TxSubmission2.Test qualified (tests)\n", ""
    )
    main_text = main_text.replace(
        "Ouroboros.Network.Protocol.KeepAlive.Test.tests", "KeepAlive.tests"
    ).replace("Ouroboros.Network.Protocol.PeerSharing.Test.tests", "PeerSharing.tests")
    main_text = main_text.replace("Ouroboros.Network.Protocol.ChainSync.Test.tests", "ChainSync.tests")
    main_text = main_text.replace("Ouroboros.Network.Protocol.BlockFetch.Test.tests", "BlockFetch.tests")
    main_text = main_text.replace("Ouroboros.Network.Protocol.LocalStateQuery.Test.tests", "LocalStateQuery.tests")
    main_text = main_text.replace("Ouroboros.Network.Protocol.LocalTxSubmission.Test.tests", "LocalTxSubmission.tests")
    main_text = main_text.replace("Ouroboros.Network.Protocol.LocalTxMonitor.Test.tests", "LocalTxMonitor.tests")
    main_text = main_text.replace("Ouroboros.Network.Protocol.TxSubmission2.Test.tests", "TxSubmission2.tests")
    main_text = main_text.replace(
        "main = defaultMain tests",
        "main = getArgs >>= \\args -> case args of\n"
        "  [\"--export-lsq-behavior\", dir, count, seed] -> LocalStateQuery.exportLocalStateQueryBehaviorCases (dir <> \"/localsq-behavior-cases.tsv\") (dir <> \"/localsq-behavior-actions.tsv\") (read count) (read seed)\n"
        "  [\"--export-chainsync-behavior\", dir, count, seed] -> ChainSync.exportChainSyncBehaviorCases (dir <> \"/chainsync-behavior-cases.tsv\") (dir <> \"/chainsync-behavior-blocks.tsv\") (dir <> \"/chainsync-behavior-points.tsv\") (read count) (read seed)\n"
        "  [\"--export-chainsync-io-behavior\", dir, count, seed] -> ChainSync.exportChainSyncIOBehaviorCases (dir <> \"/chainsync-behavior-io.tsv\") (read count) (read seed)\n"
        "  [\"--export-chainsync-pipelined-max\", dir, count, seed] -> ChainSync.exportChainSyncPipelinedMaxCases (dir <> \"/chainsync-pipelined-max.tsv\") (read count) (read seed)\n"
        "  [\"--export-chainsync-pipelined-min\", dir, count, seed] -> ChainSync.exportChainSyncPipelinedMinCases (dir <> \"/chainsync-pipelined-min.tsv\") (read count) (read seed)\n"
        "  [\"--export-chainsync-pipelined-low-high\", dir, count, seed] -> ChainSync.exportChainSyncPipelinedLowHighCases (dir <> \"/chainsync-pipelined-low-high.tsv\") (read count) (read seed)\n"
        "  [\"--export-chainsync-pipelined-direct-connect\", dir, count, seed] -> ChainSync.exportChainSyncPipelinedDirectConnectCases (dir <> \"/chainsync-pipelined-direct-connect.tsv\") (read count) (read seed)\n"
        "  [\"--export-chainsync-pipelined-min-buffered\", dir, count, seed] -> ChainSync.exportChainSyncPipelinedMinBufferedCases (dir <> \"/chainsync-pipelined-min-buffered.tsv\") (read count) (read seed)\n"
        "  [\"--export-chainsync-demo-pipelined-io\", dir, count, seed] -> ChainSync.exportChainSyncDemoPipelinedIOCases (dir <> \"/chainsync-demo-pipelined-io.tsv\") (read count) (read seed)\n"
        "  [\"--export-lsq-behavior\", dir, count, seed] -> LocalStateQuery.exportLocalStateQueryBehaviorCases (dir <> \"/localsq-behavior-cases.tsv\") (dir <> \"/localsq-behavior-actions.tsv\") (read count) (read seed)\n"
        "  [\"--export-txsubmission2-behavior\", dir, count, seed] -> TxSubmission2.exportTxSubmission2BehaviorCases (dir <> \"/txsubmission2-behavior.tsv\") (read count) (read seed)\n"
        "  [\"--export-blockfetch-behavior\", dir, count, seed] -> BlockFetch.exportBlockFetchBehaviorCases (dir <> \"/blockfetch-behavior.tsv\") (read count) (read seed)\n"
        "  [\"--export-network-codecs\", dir, count, seed, chainCount] -> do\n"
        "    KeepAlive.exportKeepAliveBehaviorCases (dir <> \"/keepalive-behavior.tsv\") (read count) (read seed + 700)\n"
        "    KeepAlive.exportKeepAliveCases (dir <> \"/keepalive.tsv\") (read count) (read seed)\n"
        "    KeepAlive.exportKeepAliveSplitCases (dir <> \"/keepalive-splits.tsv\") (read count) (read seed)\n"
        "    PeerSharing.exportPeerSharingCases (dir <> \"/peersharing.tsv\") (read count) (read seed)\n"
        "    ChainSync.exportChainSyncCases (dir <> \"/chainsync.tsv\") (read chainCount) (read seed + 900)\n"
        "    ChainSync.exportChainSyncSplitCases (dir <> \"/chainsync-splits.tsv\") (read chainCount) (read seed + 900)\n"
        "    ChainSync.exportChainSyncBinaryCases (dir <> \"/chainsync-binary.tsv\") (read chainCount) (read seed + 900)\n"
        "    BlockFetch.exportBlockFetchCases (dir <> \"/blockfetch.tsv\") (read count) (read seed)\n"
        "    BlockFetch.exportBlockFetchSplitCases (dir <> \"/blockfetch-splits.tsv\") (read count) (read seed)\n"
        "    BlockFetch.exportBlockFetchBinaryCases (dir <> \"/blockfetch-binary.tsv\") (read count) (read seed)\n"
        "    LocalStateQuery.exportLocalStateQueryCases (dir <> \"/localsq.tsv\") (read count) (read seed + 1800)\n"
        "    LocalStateQuery.exportLocalStateQuerySplitCases (dir <> \"/localsq-splits.tsv\") (read count) (read seed + 1800)\n"
        "    LocalStateQuery.exportLocalStateQueryBehaviorCases (dir <> \"/localsq-behavior-cases.tsv\") (dir <> \"/localsq-behavior-actions.tsv\") (read count) (read seed + 11800)\n"
        "    LocalTxSubmission.exportLocalTxSubmissionCases (dir <> \"/localtxsubmission.tsv\") (read count) (read seed + 2800)\n"
        "    LocalTxSubmission.exportLocalTxSubmissionBehaviorCases (dir <> \"/localtxsubmission-behavior.tsv\") (read count) (read seed + 8800)\n"
        "    LocalTxMonitor.exportLocalTxMonitorCases (dir <> \"/localtxmonitor.tsv\") (read count) (read seed + 3800)\n"
        "    LocalTxMonitor.exportLocalTxMonitorBehaviorCases (dir <> \"/localtxmonitor-behavior.tsv\") (read count) (read seed + 9800)\n"
        "    LocalTxSubmission.exportLocalTxSubmissionSplitCases (dir <> \"/localtxsubmission-splits.tsv\") (read count) (read seed + 2800)\n"
        "    LocalTxSubmission.exportLocalTxSubmissionAnnotatedCases (dir <> \"/localtxsubmission-annotated.tsv\") (read count) (read seed + 5800)\n"
        "    LocalTxMonitor.exportLocalTxMonitorSplitCases (dir <> \"/localtxmonitor-splits.tsv\") (read count) (read seed + 3800)\n"
        "    TxSubmission2.exportTxSubmission2Cases (dir <> \"/txsubmission2.tsv\") (read count) (read seed + 4800)\n"
        "    TxSubmission2.exportTxSubmission2SplitCases (dir <> \"/txsubmission2-splits.tsv\") (read count) (read seed + 4800)\n"
        "    TxSubmission2.exportTxSubmission2AnnotatedCases (dir <> \"/txsubmission2-annotated.tsv\") (read count) (read seed + 6800)\n"
        "    TxSubmission2.exportTxSubmission2BehaviorCases (dir <> \"/txsubmission2-behavior.tsv\") (read count) (read seed + 12800)\n"
        "  _ -> defaultMain tests",
    )
    main.write_text(main_text)
    return {
        "KeepAlive/Test.hs": keep_original,
        "PeerSharing/Test.hs": peer_original,
        "ChainSync/Test.hs": chain_original,
        "ChainSync/Examples.hs": chain_examples_original,
        "BlockFetch/Test.hs": block_fetch_original,
        "LocalStateQuery/Test.hs": local_state_query_original,
        "LocalTxSubmission/Test.hs": local_tx_submission_original,
        "LocalTxMonitor/Test.hs": local_tx_monitor_original,
        "TxSubmission2/Test.hs": tx_submission2_original,
        "protocols/tests/Main.hs": main_original,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--count", type=int, default=24)
    parser.add_argument("--chainsync-count", type=int, default=128)
    parser.add_argument("--seed", type=int, default=490201)
    parser.add_argument("--probe-dir", type=Path, default=Path("/tmp/scarab/v02-49-protocol-probe"))
    parser.add_argument("--reference-plan", type=Path)
    parser.add_argument("--lsq-behavior-only", action="store_true")
    parser.add_argument("--txsubmission2-behavior-only", action="store_true")
    parser.add_argument("--blockfetch-behavior-only", action="store_true")
    parser.add_argument("--chainsync-behavior-only", action="store_true")
    parser.add_argument("--chainsync-io-behavior-only", action="store_true")
    parser.add_argument("--chainsync-pipelined-max-only", action="store_true")
    parser.add_argument("--chainsync-pipelined-min-only", action="store_true")
    parser.add_argument("--chainsync-pipelined-low-high-only", action="store_true")
    parser.add_argument("--chainsync-pipelined-direct-connect-only", action="store_true")
    parser.add_argument("--chainsync-pipelined-min-buffered-only", action="store_true")
    parser.add_argument("--chainsync-demo-pipelined-io-only", action="store_true")
    parser.add_argument("--pipe-demo-behavior-only", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.count <= 128:
        raise SystemExit("case count must be 1..128")
    if not 1 <= args.chainsync_count <= 128:
        raise SystemExit("ChainSync case count must be 1..128")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    args.probe_dir.mkdir(parents=True, exist_ok=True)
    global COMMAND_LOG
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    COMMAND_LOG = args.probe_dir / f"network-codec-export-{run_id}-commands.json"
    COMMAND_LOG.write_text("[]\n")
    (args.probe_dir / "exports").mkdir(parents=True, exist_ok=True)
    overlay = args.probe_dir / "ouroboros-network-overlay"
    originals = prepare(args.source.resolve(), overlay)
    # Keep the upstream source/project pins and every target dependency exact,
    # while restricting Cabal's project-wide solver to the three local packages
    # that own this test target.  Closure equivalence to the recorded full
    # project plan is checked and retained alongside the probe result.
    project_file = args.probe_dir / "protocols-tests.project"
    build = args.probe_dir / "narrow-builddir"
    build.mkdir(exist_ok=True)
    image_digest = checked(["docker", "image", "inspect", IMAGE, "--format", "{{.Id}}"])
    container = subprocess.run(["docker", "container", "inspect", CONTAINER_NAME], text=True, capture_output=True)
    if container.returncode != 0:
        checked([
            "docker", "create", "--network=none", "--name", CONTAINER_NAME, "--entrypoint", "tail",
            "-v", f"{overlay}:/source", "-v", f"{args.probe_dir}:/probe",
            "-w", "/source", IMAGE, "-f", "/dev/null",
        ])
    checked(["docker", "start", CONTAINER_NAME])
    test_names = () if args.pipe_demo_behavior_only else ("LocalStateQuery",) if args.lsq_behavior_only else ("TxSubmission2",) if args.txsubmission2_behavior_only else ("BlockFetch",) if args.blockfetch_behavior_only else ("ChainSync",) if args.chainsync_behavior_only or args.chainsync_pipelined_max_only or args.chainsync_pipelined_min_only or args.chainsync_pipelined_low_high_only or args.chainsync_pipelined_direct_connect_only or args.chainsync_pipelined_min_buffered_only or args.chainsync_demo_pipelined_io_only or args.chainsync_io_behavior_only else (
        "KeepAlive", "PeerSharing", "ChainSync", "BlockFetch", "LocalStateQuery",
        "LocalTxSubmission", "LocalTxMonitor", "TxSubmission2",
    )
    for test_name in test_names:
        checked([
            "docker", "exec", "-w", "/source", CONTAINER_NAME,
            "cabal", "test", "--project-file=/probe/protocols-tests.project",
            "ouroboros-network:protocols-tests",
            f"--test-options=--pattern={test_name}",
            "--builddir=/probe/narrow-builddir", "--jobs=2",
        ])
    binary_output = checked([
        "docker", "exec", "-w", "/source", CONTAINER_NAME,
        "cabal", "list-bin", "--project-file=/probe/protocols-tests.project",
        "ouroboros-network:protocols-tests",
        "--builddir=/probe/narrow-builddir",
    ])
    binary = next(
        (line.strip() for line in reversed(binary_output.splitlines()) if line.strip().startswith("/probe/narrow-builddir/")),
        None,
    )
    if binary is None:
        raise SystemExit(f"cabal list-bin did not return a build executable path: {binary_output!r}")
    if args.chainsync_pipelined_max_only:
        checked([
            "docker", "exec", "-w", "/source", CONTAINER_NAME,
            binary, "--export-chainsync-pipelined-max", "/probe/exports",
            str(args.count), str(args.seed + 13800),
        ])
    elif args.chainsync_pipelined_min_only:
        checked([
            "docker", "exec", "-w", "/source", CONTAINER_NAME,
            binary, "--export-chainsync-pipelined-min", "/probe/exports",
            str(args.count), str(args.seed + 13800),
        ])
    elif args.chainsync_pipelined_low_high_only:
        checked([
            "docker", "exec", "-w", "/source", CONTAINER_NAME,
            binary, "--export-chainsync-pipelined-low-high", "/probe/exports",
            str(args.count), str(args.seed + 13800),
        ])
    elif args.chainsync_pipelined_direct_connect_only:
        checked([
            "docker", "exec", "-w", "/source", CONTAINER_NAME,
            binary, "--export-chainsync-pipelined-direct-connect", "/probe/exports",
            str(args.count), str(args.seed + 13800),
        ])
    elif args.chainsync_pipelined_min_buffered_only:
        checked([
            "docker", "exec", "-w", "/source", CONTAINER_NAME,
            binary, "--export-chainsync-pipelined-min-buffered", "/probe/exports",
            str(args.count), str(args.seed + 13800),
        ])
    elif args.chainsync_demo_pipelined_io_only:
        checked([
            "docker", "exec", "-w", "/source", CONTAINER_NAME,
            binary, "--export-chainsync-demo-pipelined-io", "/probe/exports",
            str(args.count), str(args.seed + 13800),
        ])
    elif args.chainsync_behavior_only:
        checked([
            "docker", "exec", "-w", "/source", CONTAINER_NAME,
            binary, "--export-chainsync-behavior", "/probe/exports",
            str(args.count), str(args.seed + 13800),
        ])
    elif args.chainsync_io_behavior_only:
        checked([
            "docker", "exec", "-w", "/source", CONTAINER_NAME,
            binary, "--export-chainsync-io-behavior", "/probe/exports",
            str(args.count), str(args.seed + 13800),
        ])
    elif args.lsq_behavior_only:
        checked([
            "docker", "exec", "-w", "/source", CONTAINER_NAME,
            binary, "--export-lsq-behavior", "/probe/exports",
            str(args.count), str(args.seed + 11800),
        ])
    elif args.txsubmission2_behavior_only:
        checked([
            "docker", "exec", "-w", "/source", CONTAINER_NAME,
            binary, "--export-txsubmission2-behavior", "/probe/exports",
            str(args.count), str(args.seed + 12800),
        ])
    elif args.blockfetch_behavior_only:
        checked([
            "docker", "exec", "-w", "/source", CONTAINER_NAME,
            binary, "--export-blockfetch-behavior", "/probe/exports",
            str(args.count), str(args.seed),
        ])
    elif args.pipe_demo_behavior_only:
        checked([
            "docker", "exec", "-w", "/source", CONTAINER_NAME,
            "cabal", "build", "--project-file=/probe/protocols-tests.project",
            "ouroboros-network:ouroboros-network-io-tests",
            "--builddir=/probe/narrow-builddir", "--jobs=2",
        ])
        io_binary_output = checked([
            "docker", "exec", "-w", "/source", CONTAINER_NAME,
            "cabal", "list-bin", "--project-file=/probe/protocols-tests.project",
            "ouroboros-network:ouroboros-network-io-tests",
            "--builddir=/probe/narrow-builddir",
        ])
        io_binary = next(
            (line.strip() for line in reversed(io_binary_output.splitlines()) if line.strip().startswith("/probe/narrow-builddir/")),
            None,
        )
        if io_binary is None:
            raise SystemExit(f"cabal list-bin did not return the IO test executable path: {io_binary_output!r}")
        checked([
            "docker", "exec", "-w", "/source", CONTAINER_NAME,
            io_binary, "--export-pipe-demo", "/probe/exports",
            str(args.count), str(args.seed + 14800),
        ])
    else:
        checked([
            "docker", "exec", "-w", "/source", CONTAINER_NAME,
            binary, "--export-network-codecs", "/probe/exports",
            str(args.count), str(args.seed), str(args.chainsync_count),
        ])
    patch = ""
    for name, original in originals.items():
        if name == "protocols/tests/Main.hs":
            target = overlay / "ouroboros-network" / name
        elif name == "Pipe":
            target = overlay / "ouroboros-network/tests/io/Test/Ouroboros/Network/Pipe.hs"
        elif name == "PipeMain":
            target = overlay / "ouroboros-network/tests/io/Main.hs"
        else:
            target = overlay / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol" / name
        patch += "\n".join(difflib.unified_diff(
            original.splitlines(), target.read_text().splitlines(), fromfile="upstream/" + name, tofile="overlay/" + name
        )) + "\n"
    plan = (build / "cache/plan.json").read_bytes()
    plan_units = json.loads(plan)["install-plan"]
    plan_by_id = {unit["id"]: unit for unit in plan_units}
    target_roots = [
        unit for unit in plan_units
        if unit.get("pkg-name") == "ouroboros-network"
        and unit.get("component-name") == (
            "test:ouroboros-network-io-tests" if args.pipe_demo_behavior_only else "test:protocols-tests"
        )
    ]
    if len(target_roots) != 1:
        raise SystemExit(f"expected one selected upstream test plan root, found {len(target_roots)}")
    target_closure: set[str] = set()
    pending = [target_roots[0]["id"]]
    while pending:
        unit_id = pending.pop()
        if unit_id in target_closure:
            continue
        target_closure.add(unit_id)
        pending.extend(dep for dep in plan_by_id[unit_id].get("depends", []) if dep in plan_by_id)
    equivalence_path = args.probe_dir / "network-target-plan-equivalence.json"
    if args.reference_plan:
        checked([
            "python3", "scripts/oracle/network-protocols/compare_cabal_plans.py",
            str(args.reference_plan), str(build / "cache/plan.json"), "--output", str(equivalence_path),
        ])
    if args.pipe_demo_behavior_only:
        cases_raw = (args.probe_dir / "exports/pipe-demo-cases.tsv").read_bytes()
        blocks_raw = (args.probe_dir / "exports/pipe-demo-blocks.tsv").read_bytes()
        updates_raw = (args.probe_dir / "exports/pipe-demo-updates.tsv").read_bytes()
        cases = [line.split("\t") for line in cases_raw.decode().splitlines()]
        blocks = [line.split("\t") for line in blocks_raw.decode().splitlines()]
        updates = [line.split("\t") for line in updates_raw.decode().splitlines()]
        try:
            pipe_counts = validate_pipe_demo_export(cases, blocks, updates, args.count)
        except ValueError as error:
            raise SystemExit(str(error)) from error
        cases_path = args.output_dir / "pipe-demo-cases.tsv"
        blocks_path = args.output_dir / "pipe-demo-blocks.tsv"
        updates_path = args.output_dir / "pipe-demo-updates.tsv"
        cases_path.write_bytes(cases_raw)
        blocks_path.write_bytes(blocks_raw)
        updates_path.write_bytes(updates_raw)
        pipe_source = args.source / "ouroboros-network/tests/io/Test/Ouroboros/Network/Pipe.hs"
        pipe_main_source = args.source / "ouroboros-network/tests/io/Main.hs"
        manifest = {
            "schema": "scarab-upstream-network-protocol-behavior-v1",
            "source": "ouroboros-network",
            "sourceCommit": UPSTREAM_COMMIT,
            "upstreamTest": "ouroboros-network:ouroboros-network-io-tests / Pipe / prop_pipe_demo",
            "generatorType": "TestBlockChainAndUpdates",
            "requestedCases": args.count,
            "exportedCases": len(cases),
            "seed": int(cases[0][1]),
            "generatorSizePerOrdinal": "30 + (ordinal mod 21)",
            "upstreamPropertyOutcomes": {
                "pass": pipe_counts["pass"],
                "upstream-failure": pipe_counts["upstreamFailure"],
            },
            "initialBlocks": pipe_counts["initialBlocks"],
            "finalBlocks": pipe_counts["finalBlocks"],
            "candidatePoints": pipe_counts["candidatePoints"],
            "updateRows": pipe_counts["updates"],
            "updateConstructors": {
                "roll-forward": pipe_counts["rollForwardUpdates"],
                "roll-backward": pipe_counts["rollBackwardUpdates"],
            },
            "consumerStopsAtFirstTarget": {
                "updatesConsumed": pipe_counts["updatesConsumedUntilTarget"],
                "casesReachingTargetBeforeGeneratedTail": pipe_counts["targetReachedBeforeGeneratedTail"],
                "unusedGeneratedUpdateTailRows": pipe_counts["unusedGeneratedUpdateTail"],
            },
            "sourceFiles": {
                "ouroboros-network/tests/io/Test/Ouroboros/Network/Pipe.hs": hashlib.sha256(pipe_source.read_bytes()).hexdigest(),
                "ouroboros-network/tests/io/Main.hs": hashlib.sha256(pipe_main_source.read_bytes()).hexdigest(),
                "ouroboros-network/api/tests-lib/Ouroboros/Network/Mock/ChainGenerators.hs": hashlib.sha256((args.source / "ouroboros-network/api/tests-lib/Ouroboros/Network/Mock/ChainGenerators.hs").read_bytes()).hexdigest(),
                "ouroboros-network/api/tests-lib/Ouroboros/Network/Mock/ConcreteBlock.hs": hashlib.sha256((args.source / "ouroboros-network/api/tests-lib/Ouroboros/Network/Mock/ConcreteBlock.hs").read_bytes()).hexdigest(),
                "ouroboros-network/api/tests-lib/Ouroboros/Network/Mock/Chain.hs": hashlib.sha256((args.source / "ouroboros-network/api/tests-lib/Ouroboros/Network/Mock/Chain.hs").read_bytes()).hexdigest(),
                "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/ChainSync/Examples.hs": hashlib.sha256((args.source / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/ChainSync/Examples.hs").read_bytes()).hexdigest(),
            },
            "observerPatchSha256": hashlib.sha256(patch.encode()).hexdigest(),
            "dependencyPlanSha256": hashlib.sha256(plan).hexdigest(),
            "targetDependencyClosureUnitCount": len(target_closure),
            "caseTsvSha256": hashlib.sha256(cases_raw).hexdigest(),
            "blocksTsvSha256": hashlib.sha256(blocks_raw).hexdigest(),
            "updatesTsvSha256": hashlib.sha256(updates_raw).hexdigest(),
            "scarabRuntimeProfiles": 1,
            "limitations": [
                "The unchanged registered upstream property runs over its unchanged TestBlockChainAndUpdates Arbitrary inputs; every original generated update and exact serialized block/point/tip item is retained.",
                "Scarab replays the exact generated update prefix through one production linked Mux ChainSync runtime until the generated final point is reached; it compares the full ordered final chain and does not claim OS pipe bearer topology or scheduler equivalence.",
                "The full original update list is retained even where the pinned consumer stops at the target before the generated list ends.",
            ],
        }
        (args.output_dir / "pipe-demo-behavior.manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        return
    if args.chainsync_pipelined_max_only:
        raw = (args.probe_dir / "exports/chainsync-pipelined-max.tsv").read_bytes()
        rows = [line.split("\t") for line in raw.decode().splitlines()]
        if len(rows) != args.count:
            raise SystemExit("ChainSync pipelined-max export does not match requested schema/count")
        try:
            statuses = summarize_chainsync_pipeline_rows(rows)
        except ValueError as error:
            raise SystemExit(str(error)) from error
        manifest = {
            "schema": "scarab-upstream-network-protocol-behavior-v1",
            "source": "ouroboros-network",
            "sourceCommit": UPSTREAM_COMMIT,
            "upstreamTest": "ouroboros-network:protocols-tests / ChainSync / propChainSyncDemoPipelinedMaxST",
            "generatorType": "ChainProducerStateForkTest × PipeliningDepth",
            "requestedCases": args.count,
            "exportedCases": len(rows),
            "seed": int(rows[0][1]),
            "upstreamPropertyOutcomes": {
                "pass": statuses["pass"],
                "upstream-failure": statuses["upstream-failure"],
                "precondition-discard": statuses["precondition-discard"],
            },
            "sourceFiles": {
                relative: hashlib.sha256((args.source / relative).read_bytes()).hexdigest()
                for relative in [
                    "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/ChainSync/Test.hs",
                    "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/ChainSync/ExamplesPipelined.hs",
                    "ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/ChainSync/PipelineDecision.hs",
                    "ouroboros-network/protocols/tests-lib/Test/Data/PipeliningDepth.hs",
                ]
            },
            "observerPatchSha256": hashlib.sha256(patch.encode()).hexdigest(),
            "caseTsvSha256": hashlib.sha256(raw).hexdigest(),
            "generatorParameters": {
                "forkCaseSeedPerOrdinal": "seed + ordinal",
                "caseSizePerOrdinal": "30 + (ordinal mod 21)",
                "depthSeedOffset": 7000003,
                "depthGenerator": "unchanged Arbitrary PipeliningDepth, choose (0, 300)",
                "zeroDepth": "recorded as precondition-discard from the unchanged omax > 0 implication; not replayed",
            },
            "limitations": [
                "Rows link to the exact chain/body/point bytes and independent producer-chain oracle in chainsync-behavior v2 fixtures.",
                "Scarab replay uses its production fixed-batch requestNextBatch API with the original max depth as a window bound and compares final chain only.",
                "The observer preserves original generated depth and property verdicts; it does not claim Haskell max-policy request/collect trace equivalence.",
            ],
        }
        (args.output_dir / "chainsync-pipelined-max.tsv").write_bytes(raw)
        (args.output_dir / "chainsync-pipelined-max.manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        return
    if args.chainsync_pipelined_min_only:
        raw = (args.probe_dir / "exports/chainsync-pipelined-min.tsv").read_bytes()
        rows = [line.split("\t") for line in raw.decode().splitlines()]
        if len(rows) != args.count:
            raise SystemExit("ChainSync pipelined-min export does not match requested schema/count")
        try:
            statuses = summarize_chainsync_pipeline_rows(rows, "chainsync-pipelined-min-v1")
        except ValueError as error:
            raise SystemExit(str(error)) from error
        manifest = {
            "schema": "scarab-upstream-network-protocol-behavior-v1",
            "source": "ouroboros-network",
            "sourceCommit": UPSTREAM_COMMIT,
            "upstreamTest": "ouroboros-network:protocols-tests / ChainSync / propChainSyncDemoPipelinedMinST",
            "generatorType": "ChainProducerStateForkTest × PipeliningDepth",
            "requestedCases": args.count,
            "exportedCases": len(rows),
            "seed": int(rows[0][1]),
            "upstreamPropertyOutcomes": {
                "pass": statuses["pass"],
                "upstream-failure": statuses["upstream-failure"],
                "precondition-discard": statuses["precondition-discard"],
            },
            "sourceFiles": {
                relative: hashlib.sha256((args.source / relative).read_bytes()).hexdigest()
                for relative in [
                    "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/ChainSync/Test.hs",
                    "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/ChainSync/ExamplesPipelined.hs",
                    "ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/ChainSync/PipelineDecision.hs",
                    "ouroboros-network/protocols/tests-lib/Test/Data/PipeliningDepth.hs",
                ]
            },
            "observerPatchSha256": hashlib.sha256(patch.encode()).hexdigest(),
            "caseTsvSha256": hashlib.sha256(raw).hexdigest(),
            "generatorParameters": {
                "forkCaseSeedPerOrdinal": "seed + ordinal",
                "caseSizePerOrdinal": "30 + (ordinal mod 21)",
                "depthSeedOffset": 7000003,
                "depthGenerator": "unchanged Arbitrary PipeliningDepth, choose (0, 300)",
                "zeroDepth": "recorded as precondition-discard from the unchanged omax > 0 implication; not replayed",
            },
            "limitations": [
                "Rows link to the exact chain/body/point bytes and independent producer-chain oracle in chainsync-behavior v2 fixtures.",
                "Scarab replay uses its production fixed-batch requestNextBatch API with the original max depth as a window bound and compares final chain only.",
                "The observer preserves original generated depth and property verdicts; it does not claim Haskell min-policy request/collect trace equivalence.",
            ],
        }
        (args.output_dir / "chainsync-pipelined-min.tsv").write_bytes(raw)
        (args.output_dir / "chainsync-pipelined-min.manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        return
    if args.chainsync_pipelined_low_high_only:
        raw = (args.probe_dir / "exports/chainsync-pipelined-low-high.tsv").read_bytes()
        rows = [line.split("\t") for line in raw.decode().splitlines()]
        if len(rows) != args.count:
            raise SystemExit("ChainSync pipelined-low-high export does not match requested schema/count")
        try:
            statuses = summarize_chainsync_pipeline_rows(
                rows, "chainsync-pipelined-low-high-v1", depth_columns=(5, 7)
            )
        except ValueError as error:
            raise SystemExit(str(error)) from error
        manifest = {
            "schema": "scarab-upstream-network-protocol-behavior-v1",
            "source": "ouroboros-network",
            "sourceCommit": UPSTREAM_COMMIT,
            "upstreamTest": "ouroboros-network:protocols-tests / ChainSync / propChainSyncDemoPipelinedLowHighST",
            "generatorType": "ChainProducerStateForkTest × PipeliningDepth × PipeliningDepth",
            "requestedCases": args.count,
            "exportedCases": len(rows),
            "seed": int(rows[0][1]),
            "upstreamPropertyOutcomes": {
                "pass": statuses["pass"],
                "upstream-failure": statuses["upstream-failure"],
                "precondition-discard": statuses["precondition-discard"],
            },
            "sourceFiles": {
                relative: hashlib.sha256((args.source / relative).read_bytes()).hexdigest()
                for relative in [
                    "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/ChainSync/Test.hs",
                    "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/ChainSync/ExamplesPipelined.hs",
                    "ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/ChainSync/PipelineDecision.hs",
                    "ouroboros-network/protocols/tests-lib/Test/Data/PipeliningDepth.hs",
                ]
            },
            "observerPatchSha256": hashlib.sha256(patch.encode()).hexdigest(),
            "caseTsvSha256": hashlib.sha256(raw).hexdigest(),
            "generatorParameters": {
                "forkCaseSeedPerOrdinal": "seed + ordinal",
                "caseSizePerOrdinal": "30 + (ordinal mod 21)",
                "firstDepthSeedOffset": 7000003,
                "secondDepthSeedOffset": 8000003,
                "depthGenerator": "unchanged Arbitrary PipeliningDepth, choose (0, 300), independently seeded",
                "zeroHighMark": "recorded as precondition-discard when max(firstDepth, secondDepth) == 0; not replayed",
            },
            "limitations": [
                "Rows link to the exact chain/body/point bytes and independent producer-chain oracle in chainsync-behavior v2 fixtures.",
                "Scarab replay uses its production fixed-batch requestNextBatch API with max(firstDepth, secondDepth) as a window bound and compares final chain only.",
                "The observer preserves both original depth inputs and verdict; it does not claim Haskell low/high decision-trace equivalence.",
            ],
        }
        (args.output_dir / "chainsync-pipelined-low-high.tsv").write_bytes(raw)
        (args.output_dir / "chainsync-pipelined-low-high.manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        return
    if args.chainsync_pipelined_direct_connect_only:
        raw = (args.probe_dir / "exports/chainsync-pipelined-direct-connect.tsv").read_bytes()
        rows = [line.split("\t") for line in raw.decode().splitlines()]
        if len(rows) != args.count:
            raise SystemExit("ChainSync pipelined direct/connect export does not match requested schema/count")
        properties = (
            "propChainSyncPipelinedMaxDirectST",
            "propChainSyncPipelinedMinDirectST",
            "propChainSyncPipelinedMaxConnectST",
            "propChainSyncPipelinedMinConnectST",
            "propChainSyncPipelinedMaxDirectIO",
            "propChainSyncPipelinedMinDirectIO",
            "propChainSyncPipelinedMaxConnectIO",
            "propChainSyncPipelinedMinConnectIO",
        )
        try:
            outcomes = summarize_chainsync_direct_connect_rows(rows)
        except ValueError as error:
            raise SystemExit(str(error)) from error
        manifest = {
            "schema": "scarab-upstream-network-protocol-behavior-v1",
            "source": "ouroboros-network",
            "sourceCommit": UPSTREAM_COMMIT,
            "upstreamTests": [f"ouroboros-network:protocols-tests / ChainSync / {name}" for name in properties],
            "generatorType": "ChainProducerStateForkTest × PipeliningDepth × [Bool] for connect properties",
            "requestedCases": args.count,
            "exportedCases": len(rows),
            "seed": int(rows[0][1]),
            "upstreamPropertyOutcomesByProperty": outcomes,
            "adapterInputStatuses": {
                "connectChoiceSeedOffset": 9000007,
                "connectChoiceVectorLengthBySeed": [{"seed": int(row[1]), "length": len(row[7])} for row in rows],
                "connectChoiceTrueValues": sum(row[7].count("1") for row in rows),
                "connectChoiceFalseValues": sum(row[7].count("0") for row in rows),
            },
            "sourceFiles": {
                relative: hashlib.sha256((args.source / relative).read_bytes()).hexdigest()
                for relative in [
                    "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/ChainSync/Test.hs",
                    "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/ChainSync/ExamplesPipelined.hs",
                    "ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/ChainSync/PipelineDecision.hs",
                    "ouroboros-network/protocols/tests-lib/Test/Data/PipeliningDepth.hs",
                ]
            },
            "observerPatchSha256": hashlib.sha256(patch.encode()).hexdigest(),
            "caseTsvSha256": hashlib.sha256(raw).hexdigest(),
            "generatorParameters": {
                "forkCaseSeedPerOrdinal": "seed + ordinal",
                "caseSizePerOrdinal": "30 + (ordinal mod 21)",
                "depthSeedOffset": 7000003,
                "connectChoiceSeedOffset": 9000007,
                "depthGenerator": "unchanged Arbitrary PipeliningDepth, choose (0, 300)",
                "connectChoicesGenerator": "unchanged Arbitrary [Bool]",
                "connectChoiceVectors": "preserved exact generated bit strings in TSV order",
            },
            "scarabRuntimeProfiles": 1,
            "limitations": [
                "The four unchanged Haskell properties retain direct and connect experiment outcomes; connect boolean scheduling choices are preserved per row.",
                "Scarab uses one production generic Mux runtime replay per passing row, with fixed requestNextBatch windows bounded by the generated depth, and compares final chain only.",
                "The Scarab replay does not claim direct-peer or connect choice scheduling trace equivalence with the upstream ST experiments.",
            ],
        }
        (args.output_dir / "chainsync-pipelined-direct-connect.tsv").write_bytes(raw)
        (args.output_dir / "chainsync-pipelined-direct-connect.manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        return
    if args.chainsync_pipelined_min_buffered_only:
        raw = (args.probe_dir / "exports/chainsync-pipelined-min-buffered.tsv").read_bytes()
        rows = [line.split("\t") for line in raw.decode().splitlines()]
        if len(rows) != args.count:
            raise SystemExit("ChainSync min-buffered export does not match requested schema/count")
        try:
            statuses = summarize_chainsync_pipeline_rows(
                rows,
                "chainsync-pipelined-min-buffered-v1",
                depth_columns=(5, 7),
                require_all_positive=True,
            )
        except ValueError as error:
            raise SystemExit(str(error)) from error
        manifest = {
            "schema": "scarab-upstream-network-protocol-behavior-v1",
            "source": "ouroboros-network",
            "sourceCommit": UPSTREAM_COMMIT,
            "upstreamTest": "ouroboros-network:protocols-tests / ChainSync / propChainSyncDemoPipelinedMinBufferedIO",
            "generatorType": "ChainProducerStateForkTest × PipeliningDepth × PipeliningDepth",
            "requestedCases": args.count,
            "exportedCases": len(rows),
            "seed": int(rows[0][1]),
            "upstreamPropertyOutcomes": {
                "pass": statuses["pass"],
                "upstream-failure": statuses["upstream-failure"],
                "precondition-discard": statuses["precondition-discard"],
            },
            "sourceFiles": {
                relative: hashlib.sha256((args.source / relative).read_bytes()).hexdigest()
                for relative in [
                    "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/ChainSync/Test.hs",
                    "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/ChainSync/ExamplesPipelined.hs",
                    "ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/ChainSync/PipelineDecision.hs",
                    "ouroboros-network/protocols/tests-lib/Test/Data/PipeliningDepth.hs",
                ]
            },
            "observerPatchSha256": hashlib.sha256(patch.encode()).hexdigest(),
            "caseTsvSha256": hashlib.sha256(raw).hexdigest(),
            "generatorParameters": {
                "forkCaseSeedPerOrdinal": "seed + ordinal",
                "caseSizePerOrdinal": "30 + (ordinal mod 21)",
                "firstDepthSeedOffset": 7000003,
                "secondDepthSeedOffset": 8000003,
                "depthGenerator": "unchanged Arbitrary PipeliningDepth, choose (0, 300), independently seeded",
                "precondition": "min(firstDepth, secondDepth) > 0; failed implications are recorded as discards",
            },
            "limitations": [
                "The original property uses connected buffered channels with buffer capacity min(firstDepth, secondDepth), and a min-pipelined client bounded by max(firstDepth, secondDepth).",
                "Scarab uses one production generic Mux runtime profile with fixed requestNextBatch windows bounded by the generated maximum and compares final chain only.",
                "The adapter does not claim equality of buffered-channel capacity, IO scheduling, or pipeline decision traces.",
            ],
        }
        (args.output_dir / "chainsync-pipelined-min-buffered.tsv").write_bytes(raw)
        (args.output_dir / "chainsync-pipelined-min-buffered.manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        return
    if args.chainsync_demo_pipelined_io_only:
        raw = (args.probe_dir / "exports/chainsync-demo-pipelined-io.tsv").read_bytes()
        rows = [line.split("\t") for line in raw.decode().splitlines()]
        if len(rows) != args.count or any(len(row) != 11 for row in rows):
            raise SystemExit("ChainSync demo-pipelined IO export does not match requested schema/count")
        for ordinal, row in enumerate(rows):
            if row[0] != "chainsync-demo-pipelined-io-v1" or int(row[2]) != ordinal or int(row[1]) != int(rows[0][1]) + ordinal:
                raise SystemExit(f"invalid ChainSync demo-pipelined IO identity at row {ordinal}")
            if int(row[4]) != int(row[1]) + 7000003 or int(row[6]) != int(row[1]) + 8000003:
                raise SystemExit(f"ChainSync demo-pipelined IO seed offset mismatch at row {ordinal}")
            first_depth, second_depth = int(row[5]), int(row[7])
            if any(depth < 0 or depth > 300 for depth in (first_depth, second_depth)):
                raise SystemExit(f"invalid ChainSync demo-pipelined IO depth at row {ordinal}")
            if any(value not in {"pass", "upstream-failure", "precondition-discard"} for value in row[8:11]):
                raise SystemExit(f"invalid ChainSync demo-pipelined IO outcome at row {ordinal}")
            if (first_depth == 0) != (row[8] == "precondition-discard") or (first_depth == 0) != (row[9] == "precondition-discard"):
                raise SystemExit(f"ChainSync max/min IO precondition mismatch at row {ordinal}")
            if (max(first_depth, second_depth) == 0) != (row[10] == "precondition-discard"):
                raise SystemExit(f"ChainSync low-high IO precondition mismatch at row {ordinal}")
        properties = (
            "propChainSyncDemoPipelinedMaxIO",
            "propChainSyncDemoPipelinedMinIO",
            "propChainSyncDemoPipelinedLowHighIO",
        )
        outcomes = {
            name: {
                status: sum(row[8 + index] == status for row in rows)
                for status in ("pass", "upstream-failure", "precondition-discard")
            }
            for index, name in enumerate(properties)
        }
        manifest = {
            "schema": "scarab-upstream-network-protocol-behavior-v1",
            "source": "ouroboros-network",
            "sourceCommit": UPSTREAM_COMMIT,
            "upstreamTests": [f"ouroboros-network:protocols-tests / ChainSync / {name}" for name in properties],
            "generatorType": "ChainProducerStateForkTest × PipeliningDepth × PipeliningDepth",
            "requestedCases": args.count,
            "exportedCases": len(rows),
            "seed": int(rows[0][1]),
            "upstreamPropertyOutcomesByProperty": outcomes,
            "sourceFiles": {
                relative: hashlib.sha256((args.source / relative).read_bytes()).hexdigest()
                for relative in [
                    "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/ChainSync/Test.hs",
                    "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/ChainSync/ExamplesPipelined.hs",
                    "ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/ChainSync/PipelineDecision.hs",
                    "ouroboros-network/protocols/tests-lib/Test/Data/PipeliningDepth.hs",
                ]
            },
            "observerPatchSha256": hashlib.sha256(patch.encode()).hexdigest(),
            "caseTsvSha256": hashlib.sha256(raw).hexdigest(),
            "generatorParameters": {
                "forkCaseSeedPerOrdinal": "seed + ordinal",
                "caseSizePerOrdinal": "30 + (ordinal mod 21)",
                "firstDepthSeedOffset": 7000003,
                "secondDepthSeedOffset": 8000003,
                "depthGenerator": "unchanged Arbitrary PipeliningDepth, choose (0, 300), independently seeded",
                "maxAndMinPrecondition": "firstDepth > 0",
                "lowHighPrecondition": "max(firstDepth, secondDepth) > 0",
                "failedImplications": "retained as per-property precondition-discard outcomes",
            },
            "scarabRuntimeProfiles": 1,
            "limitations": [
                "Original upstream IO properties and both generated depth inputs remain distinct in the fixture.",
                "Scarab replays each property’s final chain with fixed requestNextBatch windows bounded by that property’s original maximum depth.",
                "The adapter does not claim upstream IO scheduler or pipeline-decision trace equivalence.",
            ],
        }
        (args.output_dir / "chainsync-demo-pipelined-io.tsv").write_bytes(raw)
        (args.output_dir / "chainsync-demo-pipelined-io.manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        return
    if args.chainsync_io_behavior_only:
        raw = (args.probe_dir / "exports/chainsync-behavior-io.tsv").read_bytes()
        rows = [line.split("\t") for line in raw.decode().splitlines()]
        properties = (
            "propChainSyncDirectIO",
            "propChainSyncConnectIO",
            "propChainSyncDemoIO",
            "propChainSyncPipe",
        )
        if len(rows) != args.count or any(len(row) != 8 for row in rows):
            raise SystemExit("ChainSync IO behavior export does not match requested schema/count")
        for ordinal, row in enumerate(rows):
            if row[0] != "chainsync-behavior-io-v1" or int(row[2]) != ordinal or int(row[1]) != int(rows[0][1]) + ordinal:
                raise SystemExit(f"invalid ChainSync IO row identity at {ordinal}")
            if int(row[3]) != 30 + ordinal % 21:
                raise SystemExit(f"ChainSync IO size mismatch at row {ordinal}")
            if any(value not in {"pass", "upstream-failure"} for value in row[4:8]):
                raise SystemExit(f"invalid ChainSync IO outcome at row {ordinal}")
        outcomes = {
            name: {
                "pass": sum(row[4 + index] == "pass" for row in rows),
                "upstream-failure": sum(row[4 + index] == "upstream-failure" for row in rows),
            }
            for index, name in enumerate(properties)
        }
        manifest = {
            "schema": "scarab-upstream-network-protocol-behavior-v2",
            "source": "ouroboros-network",
            "sourceCommit": UPSTREAM_COMMIT,
            "upstreamTests": [f"ouroboros-network:protocols-tests / ChainSync / {name}" for name in properties],
            "generatorType": "ChainProducerStateForkTest",
            "requestedCases": args.count,
            "exportedCases": len(rows),
            "seed": int(rows[0][1]),
            "upstreamPropertyOutcomesByProperty": outcomes,
            "sourceFiles": {
                relative: hashlib.sha256((args.source / relative).read_bytes()).hexdigest()
                for relative in [
                    "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/ChainSync/Test.hs",
                    "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/ChainSync/Examples.hs",
                    "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/ChainSync/ExamplesPipelined.hs",
                ]
            },
            "observerPatchSha256": hashlib.sha256(patch.encode()).hexdigest(),
            "caseTsvSha256": hashlib.sha256(raw).hexdigest(),
            "generatorParameters": {
                "forkCaseSeedPerOrdinal": "seed + ordinal",
                "caseSizePerOrdinal": "30 + (ordinal mod 21)",
                "caseInputRows": "same seed/size domain as chainsync-behavior v2; full generated blocks and points remain in that fixture",
            },
            "scarabRuntimeProfiles": 1,
            "limitations": [
                "The four unchanged upstream direct/connect/demo/pipe IO property outcomes are retained per original case.",
                "The linked generic Mux runtime replay consumes the same generated mock point/block items and compares final chain, as recorded in the ChainSync v2 behavior fixture.",
                "This does not claim upstream direct/connect/pipe transport topology or IO scheduling trace equivalence.",
            ],
        }
        (args.output_dir / "chainsync-behavior-io.tsv").write_bytes(raw)
        (args.output_dir / "chainsync-behavior-io.manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        return
    if args.chainsync_behavior_only:
        cases_raw = (args.probe_dir / "exports/chainsync-behavior-cases.tsv").read_bytes()
        blocks_raw = (args.probe_dir / "exports/chainsync-behavior-blocks.tsv").read_bytes()
        points_raw = (args.probe_dir / "exports/chainsync-behavior-points.tsv").read_bytes()
        case_rows = [line.split("\t") for line in cases_raw.decode().splitlines()]
        block_rows = [line.split("\t") for line in blocks_raw.decode().splitlines()]
        point_rows = [line.split("\t") for line in points_raw.decode().splitlines()]
        properties = {
            "propChainSyncDirectST": 4,
            "propChainSyncConnectST": 5,
            "propChainSyncDemoST": 6,
        }
        if len(case_rows) != args.count or any(len(row) != 14 for row in case_rows):
            raise SystemExit("ChainSync behavior case export does not match requested schema/count")
        if any(len(row) != 7 or row[0] != "chainsync-behavior-block-v1" for row in block_rows):
            raise SystemExit("ChainSync behavior block export has invalid schema")
        if any(len(row) != 5 or row[0] != "chainsync-behavior-point-v1" for row in point_rows):
            raise SystemExit("ChainSync behavior point export has invalid schema")
        manifest = {
            "schema": "scarab-upstream-network-protocol-behavior-v2",
            "source": "ouroboros-network",
            "sourceCommit": UPSTREAM_COMMIT,
            "upstreamTest": "ouroboros-network:protocols-tests / ChainSync / unchanged Arbitrary ChainProducerStateForkTest + propChainSyncDirectST + propChainSyncConnectST + propChainSyncDemoST",
            "generatorType": "ChainProducerStateForkTest",
            "requestedCases": args.count,
            "exportedCases": len(case_rows),
            "seed": int(case_rows[0][1]),
            "generatorSizes": sorted({int(row[3]) for row in case_rows}),
            "blockRows": len(block_rows),
            "pointRows": len(point_rows),
            "upstreamPropertyOutcomesByProperty": {
                name: {
                    "pass": sum(row[column] == "pass" for row in case_rows),
                    "upstream-failure": sum(row[column] != "pass" for row in case_rows),
                }
                for name, column in properties.items()
            },
            "sourceFiles": {
                "ChainSync/Test.hs": hashlib.sha256((args.source / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/ChainSync/Test.hs").read_bytes()).hexdigest(),
                "ChainSync/Examples.hs": hashlib.sha256((args.source / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/ChainSync/Examples.hs").read_bytes()).hexdigest(),
                "ChainSync/Codec.hs": hashlib.sha256((args.source / "ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/ChainSync/Codec.hs").read_bytes()).hexdigest(),
                "Chain.hs": hashlib.sha256((args.source / "ouroboros-network/api/tests-lib/Ouroboros/Network/Mock/Chain.hs").read_bytes()).hexdigest(),
                "ChainGenerators.hs": hashlib.sha256((args.source / "ouroboros-network/api/tests-lib/Ouroboros/Network/Mock/ChainGenerators.hs").read_bytes()).hexdigest(),
                "ProducerState.hs": hashlib.sha256((args.source / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Mock/ProducerState.hs").read_bytes()).hexdigest(),
                "ConcreteBlock.hs": hashlib.sha256((args.source / "ouroboros-network/api/tests-lib/Ouroboros/Network/Mock/ConcreteBlock.hs").read_bytes()).hexdigest(),
                "Block.hs": hashlib.sha256((args.source / "ouroboros-network/api/lib/Ouroboros/Network/Block.hs").read_bytes()).hexdigest(),
            },
            "observerPatchSha256": hashlib.sha256(patch.encode()).hexdigest(),
            "dependencyPlanSha256": hashlib.sha256(plan).hexdigest(),
            "targetDependencyClosureUnitCount": len(target_closure),
            "casesTsvSha256": hashlib.sha256(cases_raw).hexdigest(),
            "blocksTsvSha256": hashlib.sha256(blocks_raw).hexdigest(),
            "pointsTsvSha256": hashlib.sha256(points_raw).hexdigest(),
            "generatorParameters": {
                "seedPerCase": "seed + ordinal",
                "sizePerCase": "30 + (ordinal mod 21)",
                "forkChoiceOffsets": hashlib.sha256((args.source / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/ChainSync/Examples.hs").read_bytes()).hexdigest(),
            },
            "wireItems": {
                "point": "exact Serialise (Point Block) bytes",
                "tip": "exact encodeTip S.encode bytes used by the upstream codec",
                "payload": "exact Serialise Block item bytes; generated mock Int hashes retained as CBOR integers",
                "genesisPoint": "exact Serialise Point Block bytes used by the unchanged upstream chain model",
                "expectedIntersection": "exact first candidate returned by pinned ChainProducerState.findFirstPoint; none when no candidate is on-chain",
            },
            "limitations": [
                "Rows retain the complete generated producer and fork chains plus exact candidate-point order and unchanged original property verdicts.",
                "The Scarab consumer replays the bounded direct/connect/channel client-server behaviors over the generic production runtime and compares the final client chain to the independent producer-chain oracle.",
                "This mock-domain replay does not claim Cardano block/hash instantiation, dynamic pipelining-policy equivalence, or node-level fork choice.",
            ],
        }
        for filename, raw in (
            ("chainsync-behavior-cases.tsv", cases_raw),
            ("chainsync-behavior-blocks.tsv", blocks_raw),
            ("chainsync-behavior-points.tsv", points_raw),
        ):
            (args.output_dir / filename).write_bytes(raw)
        (args.output_dir / "chainsync-behavior.manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        return
    if args.lsq_behavior_only:
        cases_raw = (args.probe_dir / "exports/localsq-behavior-cases.tsv").read_bytes()
        actions_raw = (args.probe_dir / "exports/localsq-behavior-actions.tsv").read_bytes()
        case_rows = [line.split("\t") for line in cases_raw.decode().splitlines()]
        action_rows = [line.split("\t") for line in actions_raw.decode().splitlines()]
        outcomes = {
            property_name: {
                "pass": sum(row[column] == "pass" for row in case_rows),
                "upstream-failure": sum(row[column] != "pass" for row in case_rows),
            }
            for property_name, column in zip(
                ("prop_direct", "prop_connect", "prop_channel_ST", "prop_channel_IO", "prop_pipe_IO"),
                range(5, 10),
            )
        }
        manifest = {
            "schema": "scarab-upstream-network-protocol-behavior-v1",
            "source": "ouroboros-network",
            "sourceCommit": UPSTREAM_COMMIT,
            "upstreamTest": "ouroboros-network:protocols-tests / LocalStateQuery / prop_direct + prop_connect + prop_channel_ST + prop_channel_IO + prop_pipe_IO",
            "requestedCases": args.count,
            "exportedCases": len(case_rows),
            "seed": int(case_rows[0][1]),
            "generatorSizes": sorted({int(row[3]) for row in case_rows}),
            "actionRows": len(action_rows),
            "emptyMapCases": sum(int(row[4]) == 0 for row in case_rows),
            "upstreamPropertyOutcomesByProperty": outcomes,
            "allPropertiesPassCases": sum(all(row[column] == "pass" for column in range(5, 10)) for row in case_rows),
            "sourceFiles": {
                "LocalStateQuery/Test.hs": hashlib.sha256((args.source / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/LocalStateQuery/Test.hs").read_bytes()).hexdigest(),
                "LocalStateQuery/Type.hs": hashlib.sha256((args.source / "ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/LocalStateQuery/Type.hs").read_bytes()).hexdigest(),
            },
            "observerPatchSha256": hashlib.sha256(patch.encode()).hexdigest(),
            "dependencyPlanSha256": hashlib.sha256(plan).hexdigest(),
            "targetDependencyClosureUnitCount": len(target_closure),
            "casesTsvSha256": hashlib.sha256(cases_raw).hexdigest(),
            "actionsTsvSha256": hashlib.sha256(actions_raw).hexdigest(),
            "limitations": [
                "Rows preserve the complete generated SetupData map, each exact stateful Acquire wire, query/result CBOR, and unchanged upstream verdicts.",
                "Scarab consumer may replay only targets its typed production codec decodes losslessly; SpecificPoint mock HeaderHash Int rows remain unsupported.",
                "Server-side replay exercises LocalStateQueryServer; it does not claim a production LocalStateQuery client or ledger interpreter.",
            ],
        }
        (args.output_dir / "localsq-behavior-cases.tsv").write_bytes(cases_raw)
        (args.output_dir / "localsq-behavior-actions.tsv").write_bytes(actions_raw)
        (args.output_dir / "localsq-behavior.manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        return
    if args.blockfetch_behavior_only:
        raw = (args.probe_dir / "exports/blockfetch-behavior.tsv").read_bytes()
        ranges_raw = (args.probe_dir / "exports/blockfetch-behavior.tsv.ranges.tsv").read_bytes()
        rows = [line.split("\t") for line in raw.decode().splitlines()]
        range_rows = [line.split("\t") for line in ranges_raw.decode().splitlines()]
        properties = {"prop_direct": 11, "prop_connect": 12, "prop_channel_ST": 13}
        manifest = {
            "schema": "scarab-upstream-network-protocol-behavior-v1",
            "source": "ouroboros-network",
            "sourceCommit": UPSTREAM_COMMIT,
            "upstreamTest": "ouroboros-network:protocols-tests / BlockFetch / unchanged Arbitrary TestChainAndPoints + prop_direct + prop_connect + prop_channel_ST",
            "caseFile": "blockfetch-behavior.tsv",
            "rangeFile": "blockfetch-behavior-ranges.tsv",
            "rangeRows": len(range_rows),
            "rangeTsvSha256": hashlib.sha256(ranges_raw).hexdigest(),
            "seed": int(rows[0][1]),
            "requestedCases": args.count,
            "exportedCases": len(rows),
            "generatorSizes": sorted({int(row[3]) for row in rows}),
            "upstreamPropertyOutcomesByProperty": {
                name: {"pass": sum(row[column] == "pass" for row in rows),
                       "upstream-failure": sum(row[column] != "pass" for row in rows)}
                for name, column in properties.items()
            },
            "fixtureColumns": ["schema", "seed", "ordinal", "size", "oldestFirstChainBlocksCborHex", "oldestFirstChainPointsCborHex", "generatedPointsCborHex", "generatedRangesCborHex", "rangeBodiesCborHex", "expectedNewestFirstBlocksCborHex", "expectedNewestFirstBlockItemsCborHex", "prop_direct", "prop_connect", "prop_channel_ST"],
            "propertyInput": "original TestChainAndPoints Arbitrary instance; direct/connect/channel ST properties run unchanged once per deterministic generated row",
            "sourceFiles": {
                "BlockFetch/Test.hs": hashlib.sha256((args.source / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/BlockFetch/Test.hs").read_bytes()).hexdigest(),
                "Mock/ChainGenerators.hs": hashlib.sha256((args.source / "ouroboros-network/api/tests-lib/Ouroboros/Network/Mock/ChainGenerators.hs").read_bytes()).hexdigest(),
            },
            "observerPatchSha256": hashlib.sha256(patch.encode()).hexdigest(),
            "dependencyPlanSha256": hashlib.sha256(plan).hexdigest(),
            "caseTsvSha256": hashlib.sha256(raw).hexdigest(),
            "filteredUpstreamSuiteCommands": [result for result in COMMAND_RESULTS if "test" in result["argv"] and any("protocols-tests" in arg for arg in result["argv"])],
            "caseExportCommand": next(result for result in COMMAND_RESULTS if "--export-blockfetch-behavior" in result["argv"]),
            "execution": {"image": IMAGE, "imageDigest": image_digest, "containerNetwork": "none", "projectFileSha256": hashlib.sha256(project_file.read_bytes()).hexdigest(), "projectPlanSha256": hashlib.sha256(plan).hexdigest(), "projectPlanUnitCount": len(plan_units)},
        }
        (args.output_dir / "blockfetch-behavior.tsv").write_bytes(raw)
        (args.output_dir / "blockfetch-behavior-ranges.tsv").write_bytes(ranges_raw)
        (args.output_dir / "blockfetch-behavior.manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        return
    if args.txsubmission2_behavior_only:
        raw = (args.probe_dir / "exports/txsubmission2-behavior.tsv").read_bytes()
        rows = [line.split("\t") for line in raw.decode().splitlines()]
        property_columns = {
            "prop_direct": 7,
            "prop_connect1": 8,
            "prop_connect2": 9,
            "prop_channel_ST": 10,
        }
        manifest = {
            "schema": "scarab-upstream-network-protocol-behavior-v1",
            "source": "ouroboros-network",
            "sourceCommit": UPSTREAM_COMMIT,
            "upstreamTest": "ouroboros-network:protocols-tests / TxSubmission2 / unchanged Arbitrary TxSubmissionTestParams + prop_direct + prop_connect1 + prop_connect2 + prop_channel_ST",
            "caseFile": "txsubmission2-behavior.tsv",
            "seed": int(rows[0][1]),
            "requestedCases": args.count,
            "exportedCases": len(rows),
            "generatorSizes": sorted({int(row[3]) for row in rows}),
            "upstreamPropertyOutcomesByProperty": {
                property_name: {
                    "pass": sum(row[column] == "pass" for row in rows),
                    "upstream-failure": sum(row[column] != "pass" for row in rows),
                }
                for property_name, column in property_columns.items()
            },
            "upstreamPropertyOutcomes": {
                "allFourPropertiesPass": sum(all(row[column] == "pass" for column in property_columns.values()) for row in rows),
                "atLeastOnePropertyFailure": sum(any(row[column] != "pass" for column in property_columns.values()) for row in rows),
            },
            "generatorParameters": {
                "unchangedType": "TxSubmissionTestParams",
                "propertyInput": "original Arbitrary instance, one deterministic seed/case size per row",
                "codec2PipelineChoicesSeedOffset": 3000007,
                "codec2PipelineChoicesColumn": 13,
                "codec2PipelineChoicesEncoding": "one character per original NonEmptyList Bool element: 1=True, 0=False",
                "transactionPairing": "original txId mapping; each TxId and Tx serialized independently with Serialise",
            },
            "adapterInputStatuses": {
                "casesWithTransactions": sum(int(row[11]) > 0 for row in rows),
                "emptyTransactionCases": sum(int(row[11]) == 0 for row in rows),
                "transactionPairs": sum(int(row[11]) for row in rows),
                "maxUnackedValues": sorted({int(row[4]) for row in rows}),
                "maxTxIdsToRequestValues": sorted({int(row[5]) for row in rows}),
                "maxTxToRequestValues": sorted({int(row[6]) for row in rows}),
                "connect2PipelineChoiceLengths": sorted({len(row[13]) for row in rows}),
                "connect2PipelineChoiceTrueValues": sum(row[13].count("1") for row in rows),
                "connect2PipelineChoiceFalseValues": sum(row[13].count("0") for row in rows),
                "connect2PipelineChoicesBySeed": [
                    {"seed": int(row[1]), "choices": row[13]} for row in rows
                ],
            },
            "propertyReplaySeedOffsets": {
                "prop_direct": 0,
                "prop_connect1": 0,
                "prop_connect2_choices": 3000007,
                "prop_channel_ST": 0,
            },
            "sourceFiles": {
                "TxSubmission2/Test.hs": hashlib.sha256((args.source / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/TxSubmission2/Test.hs").read_bytes()).hexdigest(),
                "TxSubmission2/Client.hs": hashlib.sha256((args.source / "ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/TxSubmission2/Client.hs").read_bytes()).hexdigest(),
                "TxSubmission2/Server.hs": hashlib.sha256((args.source / "ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/TxSubmission2/Server.hs").read_bytes()).hexdigest(),
            },
            "observerPatchSha256": hashlib.sha256(patch.encode()).hexdigest(),
            "dependencyPlanSha256": hashlib.sha256(plan).hexdigest(),
            "targetDependencyClosureUnitCount": len(target_closure),
            "caseTsvSha256": hashlib.sha256(raw).hexdigest(),
            "filteredUpstreamSuiteCommands": [
                result for result in COMMAND_RESULTS
                if "test" in result["argv"] and any("protocols-tests" in arg for arg in result["argv"])
            ],
            "caseExportCommand": next(result for result in COMMAND_RESULTS if "--export-txsubmission2-behavior" in result["argv"]),
            "execution": {
                "image": IMAGE,
                "imageDigest": image_digest,
                "containerNetwork": "none",
                "cabalOffline": False,
                "projectFileSha256": hashlib.sha256(project_file.read_bytes()).hexdigest(),
                "projectPlanSha256": hashlib.sha256(plan).hexdigest(),
                "projectPlanUnitCount": len(plan_units),
            },
        }
        (args.output_dir / "txsubmission2-behavior.tsv").write_bytes(raw)
        (args.output_dir / "txsubmission2-behavior.manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        return
    manifests = []
    for name, upstream_test in [
        ("keepalive.tsv", "ouroboros-network:protocols-tests / KeepAlive / codec v2 / prop_codec_v2"),
        ("keepalive-splits.tsv", "ouroboros-network:protocols-tests / KeepAlive / prop_codec_v2_splits2 + prop_codec_v2_splits3"),
        ("keepalive-behavior.tsv", "ouroboros-network:protocols-tests / KeepAlive / prop_direct + prop_connect + prop_channel_ST"),
        ("peersharing.tsv", "ouroboros-network:protocols-tests / PeerSharing / prop_codec_PeerSharing + prop_codec_cbor + prop_codec_valid_cbor + prop_codec_splits2 + prop_codec_splits3"),
        ("chainsync.tsv", "ouroboros-network:protocols-tests / ChainSync / codecSerialised + codecSerialised cbor"),
        ("blockfetch.tsv", "ouroboros-network:protocols-tests / BlockFetch / codecSerialised + codecSerialised cbor"),
        ("chainsync-splits.tsv", "ouroboros-network:protocols-tests / ChainSync / prop_codec_splits2_ChainSyncSerialised + prop_codec_splits3_ChainSyncSerialised"),
        ("blockfetch-splits.tsv", "ouroboros-network:protocols-tests / BlockFetch / prop_codec_splits2_BlockFetchSerialised + prop_codec_splits3_BlockFetchSerialised"),
        ("chainsync-binary.tsv", "ouroboros-network:protocols-tests / ChainSync / prop_codec_binary_compat_ChainSync_ChainSyncSerialised + prop_codec_binary_compat_ChainSyncSerialised_ChainSync"),
        ("blockfetch-binary.tsv", "ouroboros-network:protocols-tests / BlockFetch / prop_codec_binary_compat_BlockFetch_BlockFetchSerialised + prop_codec_binary_compat_BlockFetchSerialised_BlockFetch"),
        ("localsq.tsv", "ouroboros-network:protocols-tests / LocalStateQuery / codec + codec cbor"),
        ("localsq-splits.tsv", "ouroboros-network:protocols-tests / LocalStateQuery / prop_codec_splits2 + prop_codec_splits3"),
        ("localtxsubmission.tsv", "ouroboros-network:protocols-tests / LocalTxSubmission / codec + codec cbor"),
        ("localtxsubmission-behavior.tsv", "ouroboros-network:protocols-tests / LocalTxSubmission / prop_direct + prop_connect + prop_channel_ST"),
        ("localtxsubmission-annotated.tsv", "ouroboros-network:protocols-tests / LocalTxSubmission / prop_anncodec + prop_anncodec_splits2 + prop_anncodec_splits3"),
        ("localtxsubmission-splits.tsv", "ouroboros-network:protocols-tests / LocalTxSubmission / prop_codec_splits2 + prop_codec_splits3"),
        ("localtxmonitor.tsv", "ouroboros-network:protocols-tests / LocalTxMonitor / codecM + codec cborM"),
        ("localtxmonitor-behavior.tsv", "ouroboros-network:protocols-tests / LocalTxMonitor / prop_direct + prop_connect + prop_channel_ST"),
        ("localtxmonitor-splits.tsv", "ouroboros-network:protocols-tests / LocalTxMonitor / prop_codec_splitsM_LocalTxMonitor splits2 + splits3"),
        ("txsubmission2.tsv", "ouroboros-network:protocols-tests / TxSubmission2 / codec + codec cbor"),
        ("txsubmission2-annotated.tsv", "ouroboros-network:protocols-tests / TxSubmission2 / prop_anncodec + prop_anncodec_splits2 + prop_anncodec_splits3"),
        ("txsubmission2-splits.tsv", "ouroboros-network:protocols-tests / TxSubmission2 / prop_codec_splits2 + prop_codec_splits3"),
        ("txsubmission2-behavior.tsv", "ouroboros-network:protocols-tests / TxSubmission2 / unchanged Arbitrary TxSubmissionTestParams + prop_direct + prop_connect1 + prop_connect2 + prop_channel_ST"),
    ]:
        raw = (args.probe_dir / "exports" / name).read_bytes()
        rows = [line.split("\t") for line in raw.decode().splitlines()]
        manifest = {
            "schema": "scarab-upstream-network-protocol-cases-v1",
            "source": "ouroboros-network",
            "sourceCommit": UPSTREAM_COMMIT,
            "upstreamTest": upstream_test,
            "seed": int(rows[0][1]),
            "requestedCases": args.count,
            "exportedCases": len(rows),
            "upstreamPropertyOutcomes": {
                "both-properties-pass": sum(row[-2:] == ["pass", "pass"] for row in rows),
                "at-least-one-upstream-failure": sum(row[-2:] != ["pass", "pass"] for row in rows),
            } if name in ("chainsync.tsv", "chainsync-splits.tsv", "blockfetch.tsv", "blockfetch-splits.tsv", "localsq.tsv", "localsq-splits.tsv", "localtxsubmission.tsv", "localtxmonitor.tsv", "txsubmission2.tsv", "txsubmission2-splits.tsv", "keepalive-splits.tsv", "localtxsubmission-splits.tsv", "localtxmonitor-splits.tsv") else {
                "pass": sum(row[-1] == "pass" for row in rows),
                "upstream-failure": sum(row[-1] != "pass" for row in rows),
            },
            "projectPlanInstallUnits": len(plan_units),
            "projectPlanConfiguredUnits": sum(unit["type"] == "configured" for unit in plan_units),
            "projectPlanPreExistingUnits": sum(unit["type"] != "configured" for unit in plan_units),
            "targetDependencyClosureUnitCount": len(target_closure),
            "dependencyPlanSha256": hashlib.sha256(plan).hexdigest(),
            "sourceFiles": {key: hashlib.sha256((args.source / key).read_bytes()).hexdigest() for key in [
                "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/KeepAlive/Test.hs",
                "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/PeerSharing/Test.hs",
                "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/ChainSync/Test.hs",
                "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/BlockFetch/Test.hs",
                "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/LocalStateQuery/Test.hs",
                "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/LocalTxSubmission/Test.hs",
                "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/LocalTxMonitor/Test.hs",
                "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/TxSubmission2/Test.hs",
                "ouroboros-network/protocols/tests/Main.hs",
            ]},
            "observerPatchSha256": hashlib.sha256(patch.encode()).hexdigest(),
            "observerPatch": "disposable changes add deterministic exporter entrypoints using unchanged Arbitrary instances and property functions",
            "caseFile": name,
            "sha256": hashlib.sha256(raw).hexdigest(),
        }
        if name == "txsubmission2-behavior.tsv":
            property_columns = {
                "prop_direct": 7,
                "prop_connect1": 8,
                "prop_connect2": 9,
                "prop_channel_ST": 10,
            }
            manifest["upstreamPropertyOutcomesByProperty"] = {
                property_name: {
                    "pass": sum(row[column] == "pass" for row in rows),
                    "upstream-failure": sum(row[column] != "pass" for row in rows),
                }
                for property_name, column in property_columns.items()
            }
            manifest["upstreamPropertyOutcomes"] = {
                "allFourPropertiesPass": sum(all(row[column] == "pass" for column in property_columns.values()) for row in rows),
                "atLeastOnePropertyFailure": sum(any(row[column] != "pass" for column in property_columns.values()) for row in rows),
            }
            manifest["generatorParameters"] = {
                "unchangedType": "TxSubmissionTestParams",
                "properties": "original Arbitrary instance, one deterministic seed/case size per row",
                "codec2PipelineChoicesSeedOffset": 3000007,
                "expectedTransactions": "exact Serialise TxId and Tx wire paired after original txId mapping",
            }
            manifest["adapterInputStatuses"] = {
                "casesWithTransactions": sum(int(row[11]) > 0 for row in rows),
                "emptyTransactionCases": sum(int(row[11]) == 0 for row in rows),
                "transactionPairs": sum(int(row[11]) for row in rows),
                "maxUnackedValues": sorted({int(row[4]) for row in rows}),
                "maxTxIdsToRequestValues": sorted({int(row[5]) for row in rows}),
                "maxTxToRequestValues": sorted({int(row[6]) for row in rows}),
            }
            manifest["propertyReplaySeedOffsets"] = {
                "prop_direct": 0,
                "prop_connect1": 0,
                "prop_connect2_choices": 3000007,
                "prop_channel_ST": 0,
            }
        if name in ("chainsync-binary.tsv", "blockfetch-binary.tsv"):
            is_chain = name == "chainsync-binary.tsv"
            wrapped_to_serialised = "codec/codecSerialised"
            serialised_to_wrapped = "codecSerialised/codec"
            first_property = (
                "prop_codec_binary_compat_ChainSync_ChainSyncSerialised"
                if is_chain else "prop_codec_binary_compat_BlockFetch_BlockFetchSerialised"
            )
            second_property = (
                "prop_codec_binary_compat_ChainSyncSerialised_ChainSync"
                if is_chain else "prop_codec_binary_compat_BlockFetchSerialised_BlockFetch"
            )
            per_direction = {
                first_property: [row for row in rows if row[4] == wrapped_to_serialised],
                second_property: [row for row in rows if row[4] == serialised_to_wrapped],
            }
            manifest["requestedCases"] = args.chainsync_count if is_chain else args.count
            manifest["upstreamPropertyOutcomesByProperty"] = {
                property_name: {
                    "pass": sum(row[-1] == "pass" for row in property_rows),
                    "upstream-failure": sum(row[-1] != "pass" for row in property_rows),
                }
                for property_name, property_rows in per_direction.items()
            }
            manifest["upstreamPropertyOutcomes"] = {
                "directional-cases-pass": sum(row[-1] == "pass" for row in rows),
                "directional-cases-failure": sum(row[-1] != "pass" for row in rows),
                "paired-generator-ordinals": len(rows) // 2,
                "paired-ordinals-all-directions-pass": sum(
                    all(row[-1] == "pass" for row in rows if row[2] == ordinal)
                    for ordinal in {row[2] for row in rows}
                ),
            }
            manifest["adapterInputStatuses"] = {
                "byDirection": {direction: len(direction_rows) for direction, direction_rows in per_direction.items()},
                "byConstructor": {tag: sum(row[7] == tag for row in rows) for tag in sorted({row[7] for row in rows})},
                "sourceStates": {state: sum(row[5] == state for row in rows) for state in sorted({row[5] for row in rows})},
            }
            manifest["propertyReplaySeedOffsets"] = {
                first_property: 5000003,
                second_property: 6000003,
            }
        manifest["filteredUpstreamSuiteCommands"] = [
            result for result in COMMAND_RESULTS
            if "test" in result["argv"]
            and any("protocols-tests" in arg for arg in result["argv"])
        ]
        manifest["caseExportCommand"] = next(
            result for result in COMMAND_RESULTS
            if "--export-network-codecs" in result["argv"]
        )
        manifest["execution"] = {
            "image": IMAGE,
            "imageDigest": image_digest,
            "containerNetwork": "none",
            "cabalOffline": False,
            "projectFile": str(project_file),
            "projectFileSha256": hashlib.sha256(project_file.read_bytes()).hexdigest(),
            "projectPlanSha256": hashlib.sha256(plan).hexdigest(),
            "projectPlanUnitCount": len(plan_units),
        }
        if args.reference_plan:
            manifest["targetPlanEquivalence"] = {
                "referencePlan": str(args.reference_plan),
                "referencePlanSha256": hashlib.sha256(args.reference_plan.read_bytes()).hexdigest(),
                "result": str(equivalence_path),
                "resultSha256": hashlib.sha256(equivalence_path.read_bytes()).hexdigest(),
                "sameTargetClosure": json.loads(equivalence_path.read_text())["sameTargetClosure"],
            }
        if name == "peersharing.tsv":
            manifest["adapterInputStatuses"] = {
                status: sum(row[8] == status for row in rows)
                for status in sorted({row[8] for row in rows})
            }
            manifest["propertyReplaySeedOffsets"] = {
                "prop_codec_PeerSharing": 1000003,
                "prop_codec_cbor": 2000003,
                "prop_codec_valid_cbor": 6000003,
                "prop_codec_splits2": 3000003,
                "prop_codec_splits3": 4000003,
            }
        if name == "keepalive-splits.tsv":
            manifest["upstreamPropertyOutcomesByProperty"] = {
                "prop_codec_v2_splits2": {
                    "pass": sum(row[-2] == "pass" for row in rows),
                    "upstream-failure": sum(row[-2] != "pass" for row in rows),
                },
                "prop_codec_v2_splits3": {
                    "pass": sum(row[-1] == "pass" for row in rows),
                    "upstream-failure": sum(row[-1] != "pass" for row in rows),
                },
            }
            manifest["propertyReplaySeedOffsets"] = {
                "prop_codec_v2_splits2": 3000003,
                "prop_codec_v2_splits3": 4000003,
            }
        if name in ("localsq-splits.tsv", "localtxsubmission-splits.tsv", "localtxmonitor-splits.tsv"):
            split2 = "prop_codec_splits2" if name in ("localsq-splits.tsv", "localtxsubmission-splits.tsv") else "prop_codec_splitsM_LocalTxMonitor_splits2"
            split3 = "prop_codec_splits3" if name in ("localsq-splits.tsv", "localtxsubmission-splits.tsv") else "prop_codec_splitsM_LocalTxMonitor_splits3"
            manifest["upstreamPropertyOutcomesByProperty"] = {
                split2: {"pass": sum(row[-2] == "pass" for row in rows), "upstream-failure": sum(row[-2] != "pass" for row in rows)},
                split3: {"pass": sum(row[-1] == "pass" for row in rows), "upstream-failure": sum(row[-1] != "pass" for row in rows)},
            }
            manifest["adapterInputStatuses"] = {
                "byConstructor": {tag: sum(row[6] == tag for row in rows) for tag in sorted({row[6] for row in rows})},
                "sourceStates": {state: sum(row[4] == state for row in rows) for state in sorted({row[4] for row in rows})},
            }
            manifest["propertyReplaySeedOffsets"] = {split2: 3000003, split3: 4000003}
        if name == "chainsync-splits.tsv":
            manifest["upstreamPropertyOutcomesByProperty"] = {
                "prop_codec_splits2_ChainSyncSerialised": {
                    "pass": sum(row[-2] == "pass" for row in rows),
                    "upstream-failure": sum(row[-2] != "pass" for row in rows),
                },
                "prop_codec_splits3_ChainSyncSerialised": {
                    "pass": sum(row[-1] == "pass" for row in rows),
                    "upstream-failure": sum(row[-1] != "pass" for row in rows),
                },
            }
            manifest["adapterInputStatuses"] = {
                "byConstructor": {tag: sum(row[6] == tag for row in rows) for tag in sorted({row[6] for row in rows})},
                "sourceStates": {state: sum(row[4] == state for row in rows) for state in sorted({row[4] for row in rows})},
            }
            manifest["propertyReplaySeedOffsets"] = {
                "prop_codec_splits2_ChainSyncSerialised": 3000003,
                "prop_codec_splits3_ChainSyncSerialised": 4000003,
            }
        if name == "blockfetch-splits.tsv":
            manifest["upstreamPropertyOutcomesByProperty"] = {
                "prop_codec_splits2_BlockFetchSerialised": {
                    "pass": sum(row[-2] == "pass" for row in rows),
                    "upstream-failure": sum(row[-2] != "pass" for row in rows),
                },
                "prop_codec_splits3_BlockFetchSerialised": {
                    "pass": sum(row[-1] == "pass" for row in rows),
                    "upstream-failure": sum(row[-1] != "pass" for row in rows),
                },
            }
            manifest["adapterInputStatuses"] = {
                "byConstructor": {tag: sum(row[6] == tag for row in rows) for tag in sorted({row[6] for row in rows})},
                "sourceStates": {state: sum(row[4] == state for row in rows) for state in sorted({row[4] for row in rows})},
            }
            manifest["propertyReplaySeedOffsets"] = {
                "prop_codec_splits2_BlockFetchSerialised": 3000003,
                "prop_codec_splits3_BlockFetchSerialised": 4000003,
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
        if name == "blockfetch.tsv":
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
                "prop_codec_BlockFetchSerialised": {
                    "pass": sum(row[-2] == "pass" for row in rows),
                    "upstream-failure": sum(row[-2] != "pass" for row in rows),
                },
                "prop_codec_cbor_BlockFetchSerialised": {
                    "pass": sum(row[-1] == "pass" for row in rows),
                    "upstream-failure": sum(row[-1] != "pass" for row in rows),
                },
            }
            manifest["propertyReplaySeedOffsets"] = {
                "prop_codec_BlockFetchSerialised": 1000003,
                "prop_codec_cbor_BlockFetchSerialised": 2000003,
            }
        if name == "localsq.tsv":
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
                "prop_codec_LocalStateQuery": {
                    "pass": sum(row[-2] == "pass" for row in rows),
                    "upstream-failure": sum(row[-2] != "pass" for row in rows),
                },
                "prop_codec_cbor": {
                    "pass": sum(row[-1] == "pass" for row in rows),
                    "upstream-failure": sum(row[-1] != "pass" for row in rows),
                },
            }
            manifest["propertyReplaySeedOffsets"] = {
                "prop_codec_LocalStateQuery": 1000003,
                "prop_codec_cbor": 2000003,
            }
        if name == "localtxsubmission.tsv":
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
                "prop_codec": {
                    "pass": sum(row[-2] == "pass" for row in rows),
                    "upstream-failure": sum(row[-2] != "pass" for row in rows),
                },
                "prop_codec_cbor": {
                    "pass": sum(row[-1] == "pass" for row in rows),
                    "upstream-failure": sum(row[-1] != "pass" for row in rows),
                },
            }
            manifest["propertyReplaySeedOffsets"] = {
                "prop_codec": 1000003,
                "prop_codec_cbor": 2000003,
            }
        if name == "localtxmonitor.tsv":
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
                "prop_codecM_LocalTxMonitor": {
                    "pass": sum(row[-2] == "pass" for row in rows),
                    "upstream-failure": sum(row[-2] != "pass" for row in rows),
                },
                "prop_codec_cborM_LocalTxMonitor": {
                    "pass": sum(row[-1] == "pass" for row in rows),
                    "upstream-failure": sum(row[-1] != "pass" for row in rows),
                },
            }
            manifest["propertyReplaySeedOffsets"] = {
                "prop_codecM_LocalTxMonitor": 1000003,
                "prop_codec_cborM_LocalTxMonitor": 2000003,
            }
        if name == "txsubmission2-splits.tsv":
            manifest["upstreamPropertyOutcomesByProperty"] = {
                "prop_codec_splits2": {
                    "pass": sum(row[-2] == "pass" for row in rows),
                    "upstream-failure": sum(row[-2] != "pass" for row in rows),
                },
                "prop_codec_splits3": {
                    "pass": sum(row[-1] == "pass" for row in rows),
                    "upstream-failure": sum(row[-1] != "pass" for row in rows),
                },
            }
            manifest["adapterInputStatuses"] = {
                "byConstructor": {tag: sum(row[6] == tag for row in rows) for tag in sorted({row[6] for row in rows})},
                "sourceStates": {state: sum(row[4] == state for row in rows) for state in sorted({row[4] for row in rows})},
            }
            manifest["propertyReplaySeedOffsets"] = {
                "prop_codec_splits2": 3000003,
                "prop_codec_splits3": 4000003,
            }
        if name == "txsubmission2.tsv":
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
                "prop_codec": {
                    "pass": sum(row[-2] == "pass" for row in rows),
                    "upstream-failure": sum(row[-2] != "pass" for row in rows),
                },
                "prop_codec_cbor": {
                    "pass": sum(row[-1] == "pass" for row in rows),
                    "upstream-failure": sum(row[-1] != "pass" for row in rows),
                },
            }
            manifest["propertyReplaySeedOffsets"] = {
                "prop_codec": 1000003,
                "prop_codec_cbor": 2000003,
            }
        if name in PROPERTY_COLUMNS:
            properties = PROPERTY_COLUMNS[name]
            manifest.update(summarize_property_outcomes(rows, properties))
        if name == "keepalive-behavior.tsv":
            manifest["generatedInputSeedOffsets"] = {
                "quickCheckFunction": 0,
                "NonNegativeRequestCount": 7000003,
            }
            manifest["observation"] = (
                "The function is recorded as its exact finite accumulator trace for this generated n; "
                "cookies are the unchanged upstream descending Word16 sequence."
            )
        if name == "localtxsubmission-behavior.tsv":
            manifest["generatedInputSeedOffsets"] = {
                "transactionList": 0,
                "submitResultFunction": 1000003,
            }
            manifest["caseCount"] = manifest["requestedCases"]
            cases = {}
            for row in rows:
                key = (row[1], row[2])
                cases.setdefault(key, []).append(row)
            manifest["caseSizes"] = {
                str(size): sum(int(case_rows[0][3]) == size for case_rows in cases.values())
                for size in sorted({int(row[3]) for row in rows})
            }
            manifest["observedBehaviorCounts"] = {
                "acceptedTransactions": sum(row[6] == "accepted" for row in rows),
                "rejectedTransactions": sum(row[6] == "rejected" for row in rows),
                "emptyInputCases": manifest["requestedCases"] - len(cases),
                "duplicateRequestCases": sum(
                    len([row[5] for row in case_rows])
                    != len({row[5] for row in case_rows})
                    for case_rows in cases.values()
                ),
            }
            manifest["rowMeaning"] = (
                "Each row is one ordered input from the same generated Tx list and arbitrary "
                "SubmitResult function passed to prop_direct, prop_connect, and prop_channel_ST; "
                "Tx Any and Reject Int are retained as their exact serialized CBOR items."
            )
        if name == "localtxmonitor-behavior.tsv":
            cases = {}
            for row in rows:
                cases.setdefault((row[1], row[2]), []).append(row)
            manifest["generatedInputSeedOffsets"] = {"slotAndTransactionList": 0}
            manifest["caseCount"] = manifest["requestedCases"]
            manifest["caseSizes"] = {
                str(size): sum(int(case_rows[0][3]) == size for case_rows in cases.values())
                for size in sorted({int(row[3]) for row in rows})
            }
            manifest["observedBehaviorCounts"] = {
                "transactionRows": sum(row[8] == "transaction" for row in rows),
                "emptyInputCases": sum(row[8] == "empty" for row in rows),
                "allTxMembershipRows": sum(row[8] == "transaction" for row in rows),
                "directPassCases": sum(
                    row[9] == "pass" and row[5] == ("-1" if row[8] == "empty" else "0")
                    for row in rows
                ),
                "connectPassCases": sum(
                    row[10] == "pass" and row[5] == ("-1" if row[8] == "empty" else "0")
                    for row in rows
                ),
                "channelPassCases": sum(
                    row[11] == "pass" and row[5] == ("-1" if row[8] == "empty" else "0")
                    for row in rows
                ),
            }
            manifest["scope"] = (
                "The unchanged properties assert ordered generated transactions, positive HasTx "
                "answers, and transaction count. The upstream example's byte capacity/size values "
                "are not asserted; Scarab reports byte measures from the exact serialized items."
            )
        if name in ("localtxsubmission-annotated.tsv", "txsubmission2-annotated.tsv"):
            manifest["adapterInputStatuses"] = {
                "byConstructor": {
                    tag: sum(row[6] == tag for row in rows)
                    for tag in sorted({row[6] for row in rows})
                },
                "sourceStates": {
                    state: sum(row[4] == state for row in rows)
                    for state in sorted({row[4] for row in rows})
                },
                "byUpstreamOutcome": {
                    property_name: {
                        status: sum(row[column] == status for row in rows)
                        for status in ("pass", "upstream-failure")
                    }
                    for property_name, column in properties
                },
            }
            manifest["propertyReplaySeedOffsets"] = {
                property_name: offset
                for property_name, offset in (
                    ("prop_codec_v2", 1000003),
                    ("prop_codec_PeerSharing", 1000003),
                    ("prop_codec_LocalStateQuery", 1000003),
                    ("prop_codec", 1000003),
                    ("prop_codecM_LocalTxMonitor", 1000003),
                    ("prop_codec_cbor", 2000003),
                    ("prop_codec_cborM_LocalTxMonitor", 2000003),
                    ("prop_codecIdM_LocalTxMonitor", 5000003),
                    ("prop_codec_id", 5000003),
                    ("prop_codec_v2_valid_cbor", 6000003),
                    ("prop_codec_valid_cbor", 6000003),
                    ("prop_codec_valid_cbor_encoding_LocalTxMonitor", 6000003),
                    ("prop_anncodec", 7000003),
                    ("prop_anncodec_splits2", 8000003),
                    ("prop_anncodec_splits3", 9000003),
                )
                if property_name in {name for name, _ in properties}
            }
        (args.output_dir / name).write_bytes(raw)
        (args.output_dir / (name + ".manifest.json")).write_text(json.dumps(manifest, indent=2) + "\n")
        manifests.append(manifest)
    print(json.dumps(manifests, indent=2))


if __name__ == "__main__":
    main()
