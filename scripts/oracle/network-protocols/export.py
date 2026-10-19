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
CONTAINER_NAME = "scarab-v02-49-protocols-luna"
COMMAND_RESULTS: list[dict[str, object]] = []
COMMAND_LOG: Path | None = None

PROPERTY_COLUMNS: dict[str, tuple[tuple[str, int], ...]] = {
    "keepalive.tsv": (("prop_codec_v2", 9), ("prop_codec_v2_valid_cbor", 10)),
    "keepalive-behavior.tsv": (
        ("prop_direct", 7),
        ("prop_connect", 8),
        ("prop_channel_ST", 9),
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
        "BlockFetch": source / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/BlockFetch/Test.hs",
        "LocalStateQuery": source / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/LocalStateQuery/Test.hs",
        "LocalTxSubmission": source / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/LocalTxSubmission/Test.hs",
        "LocalTxMonitor": source / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/LocalTxMonitor/Test.hs",
        "TxSubmission2": source / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/TxSubmission2/Test.hs",
        "Main": source / "ouroboros-network/protocols/tests/Main.hs",
    }
    source_hashes = {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in modules.items()}
    shutil.copytree(source, overlay, dirs_exist_ok=True, ignore=shutil.ignore_patterns(".git", "dist-newstyle"))
    keep = overlay / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/KeepAlive/Test.hs"
    peer = overlay / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/PeerSharing/Test.hs"
    chain = overlay / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/ChainSync/Test.hs"
    block_fetch = overlay / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/BlockFetch/Test.hs"
    local_state_query = overlay / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/LocalStateQuery/Test.hs"
    local_tx_submission = overlay / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/LocalTxSubmission/Test.hs"
    local_tx_monitor = overlay / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/LocalTxMonitor/Test.hs"
    tx_submission2 = overlay / "ouroboros-network/protocols/tests-lib/Ouroboros/Network/Protocol/TxSubmission2/Test.hs"
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
    block_module = block_fetch.read_text()
    block_export_anchor = "module Ouroboros.Network.Protocol.BlockFetch.Test (tests) where"
    if block_module.count(block_export_anchor) != 1:
        raise SystemExit("BlockFetch observer module export anchor changed")
    block_fetch.write_text(block_module.replace(
        block_export_anchor,
        "module Ouroboros.Network.Protocol.BlockFetch.Test (tests, exportBlockFetchCases, exportBlockFetchSplitCases, exportBlockFetchBinaryCases) where",
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
    local_state_module = local_state_query.read_text()
    local_state_export_anchor = "module Ouroboros.Network.Protocol.LocalStateQuery.Test\n"
    if local_state_module.count(local_state_export_anchor) != 1:
        raise SystemExit("LocalStateQuery observer module export anchor changed")
    export_block_anchor = "  ( tests\n"
    if local_state_module.count(export_block_anchor) != 1:
        raise SystemExit("LocalStateQuery observer module export list changed")
    local_state_query.write_text(local_state_module.replace(
        export_block_anchor,
        "  ( tests\n  , exportLocalStateQueryCases\n  , exportLocalStateQuerySplitCases\n",
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
    local_tx_monitor_module = local_tx_monitor.read_text()
    local_tx_monitor_export_anchor = "module Ouroboros.Network.Protocol.LocalTxMonitor.Test (tests) where"
    if local_tx_monitor_module.count(local_tx_monitor_export_anchor) != 1:
        raise SystemExit("LocalTxMonitor observer module export anchor changed")
    local_tx_monitor.write_text(local_tx_monitor_module.replace(
        local_tx_monitor_export_anchor,
        "module Ouroboros.Network.Protocol.LocalTxMonitor.Test (tests, exportLocalTxMonitorCases, exportLocalTxMonitorSplitCases) where",
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
        "  , labelMsg\n  , exportTxSubmission2Cases\n  , exportTxSubmission2SplitCases\n  , exportTxSubmission2AnnotatedCases\n",
    ))

    chain_module = chain.read_text()
    chain_export_anchor = "module Ouroboros.Network.Protocol.ChainSync.Test (tests) where"
    if chain_module.count(chain_export_anchor) != 1:
        raise SystemExit("ChainSync observer module export anchor changed")
    chain.write_text(chain_module.replace(
        chain_export_anchor,
        "module Ouroboros.Network.Protocol.ChainSync.Test (tests, exportChainSyncCases, exportChainSyncSplitCases, exportChainSyncBinaryCases) where",
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
        "import Ouroboros.Network.Protocol.ChainSync.Test qualified as ChainSync (tests, exportChainSyncCases, exportChainSyncSplitCases, exportChainSyncBinaryCases)\n"
        "import Ouroboros.Network.Protocol.BlockFetch.Test qualified as BlockFetch (tests, exportBlockFetchCases, exportBlockFetchSplitCases, exportBlockFetchBinaryCases)\n"
        "import Ouroboros.Network.Protocol.LocalStateQuery.Test qualified as LocalStateQuery (tests, exportLocalStateQueryCases, exportLocalStateQuerySplitCases)\n"
        "import Ouroboros.Network.Protocol.LocalTxSubmission.Test qualified as LocalTxSubmission (tests, exportLocalTxSubmissionCases, exportLocalTxSubmissionSplitCases, exportLocalTxSubmissionAnnotatedCases, exportLocalTxSubmissionBehaviorCases)\n"
        "import Ouroboros.Network.Protocol.LocalTxMonitor.Test qualified as LocalTxMonitor (tests, exportLocalTxMonitorCases, exportLocalTxMonitorSplitCases)\n"
        "import Ouroboros.Network.Protocol.TxSubmission2.Test qualified as TxSubmission2 (tests, exportTxSubmission2Cases, exportTxSubmission2SplitCases, exportTxSubmission2AnnotatedCases)\n"
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
        "    LocalTxSubmission.exportLocalTxSubmissionCases (dir <> \"/localtxsubmission.tsv\") (read count) (read seed + 2800)\n"
        "    LocalTxSubmission.exportLocalTxSubmissionBehaviorCases (dir <> \"/localtxsubmission-behavior.tsv\") (read count) (read seed + 8800)\n"
        "    LocalTxMonitor.exportLocalTxMonitorCases (dir <> \"/localtxmonitor.tsv\") (read count) (read seed + 3800)\n"
        "    LocalTxSubmission.exportLocalTxSubmissionSplitCases (dir <> \"/localtxsubmission-splits.tsv\") (read count) (read seed + 2800)\n"
        "    LocalTxSubmission.exportLocalTxSubmissionAnnotatedCases (dir <> \"/localtxsubmission-annotated.tsv\") (read count) (read seed + 5800)\n"
        "    LocalTxMonitor.exportLocalTxMonitorSplitCases (dir <> \"/localtxmonitor-splits.tsv\") (read count) (read seed + 3800)\n"
        "    TxSubmission2.exportTxSubmission2Cases (dir <> \"/txsubmission2.tsv\") (read count) (read seed + 4800)\n"
        "    TxSubmission2.exportTxSubmission2SplitCases (dir <> \"/txsubmission2-splits.tsv\") (read count) (read seed + 4800)\n"
        "    TxSubmission2.exportTxSubmission2AnnotatedCases (dir <> \"/txsubmission2-annotated.tsv\") (read count) (read seed + 6800)\n"
        "  _ -> defaultMain tests",
    )
    main.write_text(main_text)
    return {
        "KeepAlive/Test.hs": keep_original,
        "PeerSharing/Test.hs": peer_original,
        "ChainSync/Test.hs": chain_original,
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
    for test_name in ("KeepAlive", "PeerSharing", "ChainSync", "BlockFetch", "LocalStateQuery", "LocalTxSubmission", "LocalTxMonitor", "TxSubmission2"):
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
    checked([
        "docker", "exec", "-w", "/source", CONTAINER_NAME,
        binary, "--export-network-codecs", "/probe/exports",
        str(args.count), str(args.seed), str(args.chainsync_count),
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
    plan_by_id = {unit["id"]: unit for unit in plan_units}
    target_roots = [
        unit for unit in plan_units
        if unit.get("pkg-name") == "ouroboros-network"
        and unit.get("component-name") == "test:protocols-tests"
    ]
    if len(target_roots) != 1:
        raise SystemExit(f"expected one protocols-tests plan root, found {len(target_roots)}")
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
        ("localtxmonitor-splits.tsv", "ouroboros-network:protocols-tests / LocalTxMonitor / prop_codec_splitsM_LocalTxMonitor splits2 + splits3"),
        ("txsubmission2.tsv", "ouroboros-network:protocols-tests / TxSubmission2 / codec + codec cbor"),
        ("txsubmission2-annotated.tsv", "ouroboros-network:protocols-tests / TxSubmission2 / prop_anncodec + prop_anncodec_splits2 + prop_anncodec_splits3"),
        ("txsubmission2-splits.tsv", "ouroboros-network:protocols-tests / TxSubmission2 / prop_codec_splits2 + prop_codec_splits3"),
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
