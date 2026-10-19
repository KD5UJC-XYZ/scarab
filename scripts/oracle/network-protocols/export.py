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
    block_module = block_fetch.read_text()
    block_export_anchor = "module Ouroboros.Network.Protocol.BlockFetch.Test (tests) where"
    if block_module.count(block_export_anchor) != 1:
        raise SystemExit("BlockFetch observer module export anchor changed")
    block_fetch.write_text(block_module.replace(
        block_export_anchor,
        "module Ouroboros.Network.Protocol.BlockFetch.Test (tests, exportBlockFetchCases) where",
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
        hPutStrLn outputHandle $ intercalate "\t"
          [ "localsq-codec-v1", show caseSeed, show ordinal, show caseSize
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
        "import Test.QuickCheck.Random (mkQCGen)\n",
    )
    local_state_module = local_state_query.read_text()
    local_state_export_anchor = "module Ouroboros.Network.Protocol.LocalStateQuery.Test\n"
    if local_state_module.count(local_state_export_anchor) != 1:
        raise SystemExit("LocalStateQuery observer module export anchor changed")
    export_block_anchor = "  ( tests\n"
    if local_state_module.count(export_block_anchor) != 1:
        raise SystemExit("LocalStateQuery observer module export list changed")
    local_state_query.write_text(local_state_module.replace(
        export_block_anchor,
        "  ( tests\n  , exportLocalStateQueryCases\n",
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
        hPutStrLn outputHandle $ intercalate "\t"
          [ "local-tx-submission-codec-v1", show caseSeed, show ordinal, show caseSize
          , state, sender, tag, hex (BL.toStrict raw), codecOutcome, cborOutcome
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
        "  ( tests\n  , exportLocalTxSubmissionCases\n  , exportLocalTxSubmissionSplitCases\n",
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
        hPutStrLn outputHandle $ intercalate "\t"
          [ "local-tx-monitor-codec-v1", show caseSeed, show ordinal, show caseSize
          , state, sender, tag, hex (BL.toStrict raw), codecOutcome, cborOutcome
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
        hPutStrLn outputHandle $ intercalate "\t"
          [ "txsubmission2-codec-v1", show caseSeed, show ordinal, show caseSize
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
        "  , labelMsg\n  , exportTxSubmission2Cases\n",
    ))

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
        "import Ouroboros.Network.Protocol.KeepAlive.Test qualified as KeepAlive (tests, exportKeepAliveCases, exportKeepAliveSplitCases)\n"
        "import Ouroboros.Network.Protocol.PeerSharing.Test qualified as PeerSharing (tests, exportPeerSharingCases)\n"
        "import Ouroboros.Network.Protocol.ChainSync.Test qualified as ChainSync (tests, exportChainSyncCases)\n"
        "import Ouroboros.Network.Protocol.BlockFetch.Test qualified as BlockFetch (tests, exportBlockFetchCases)\n"
        "import Ouroboros.Network.Protocol.LocalStateQuery.Test qualified as LocalStateQuery (tests, exportLocalStateQueryCases)\n"
        "import Ouroboros.Network.Protocol.LocalTxSubmission.Test qualified as LocalTxSubmission (tests, exportLocalTxSubmissionCases, exportLocalTxSubmissionSplitCases)\n"
        "import Ouroboros.Network.Protocol.LocalTxMonitor.Test qualified as LocalTxMonitor (tests, exportLocalTxMonitorCases, exportLocalTxMonitorSplitCases)\n"
        "import Ouroboros.Network.Protocol.TxSubmission2.Test qualified as TxSubmission2 (tests, exportTxSubmission2Cases)\n"
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
        "  [\"--export-network-codecs\", dir, count, seed] -> do\n"
        "    KeepAlive.exportKeepAliveCases (dir <> \"/keepalive.tsv\") (read count) (read seed)\n"
        "    KeepAlive.exportKeepAliveSplitCases (dir <> \"/keepalive-splits.tsv\") (read count) (read seed)\n"
        "    PeerSharing.exportPeerSharingCases (dir <> \"/peersharing.tsv\") (read count) (read seed)\n"
        "    ChainSync.exportChainSyncCases (dir <> \"/chainsync.tsv\") (read count) (read seed)\n"
        "    BlockFetch.exportBlockFetchCases (dir <> \"/blockfetch.tsv\") (read count) (read seed)\n"
        "    LocalStateQuery.exportLocalStateQueryCases (dir <> \"/localsq.tsv\") (read count) (read seed)\n"
        "    LocalTxSubmission.exportLocalTxSubmissionCases (dir <> \"/localtxsubmission.tsv\") (read count) (read seed)\n"
        "    LocalTxMonitor.exportLocalTxMonitorCases (dir <> \"/localtxmonitor.tsv\") (read count) (read seed)\n"
        "    LocalTxSubmission.exportLocalTxSubmissionSplitCases (dir <> \"/localtxsubmission-splits.tsv\") (read count) (read seed + 2800)\n"
        "    LocalTxMonitor.exportLocalTxMonitorSplitCases (dir <> \"/localtxmonitor-splits.tsv\") (read count) (read seed + 3800)\n"
        "    TxSubmission2.exportTxSubmission2Cases (dir <> \"/txsubmission2.tsv\") (read count) (read seed)\n"
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
    parser.add_argument("--seed", type=int, default=490201)
    parser.add_argument("--probe-dir", type=Path, default=Path("/tmp/scarab/v02-49-protocol-probe"))
    parser.add_argument("--reference-plan", type=Path)
    args = parser.parse_args()
    if not 1 <= args.count <= 128:
        raise SystemExit("case count must be 1..128")
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
        ("peersharing.tsv", "ouroboros-network:protocols-tests / PeerSharing / codec / prop_codec_PeerSharing"),
        ("chainsync.tsv", "ouroboros-network:protocols-tests / ChainSync / codecSerialised + codecSerialised cbor"),
        ("blockfetch.tsv", "ouroboros-network:protocols-tests / BlockFetch / codecSerialised + codecSerialised cbor"),
        ("localsq.tsv", "ouroboros-network:protocols-tests / LocalStateQuery / codec + codec cbor"),
        ("localtxsubmission.tsv", "ouroboros-network:protocols-tests / LocalTxSubmission / codec + codec cbor"),
        ("localtxsubmission-splits.tsv", "ouroboros-network:protocols-tests / LocalTxSubmission / prop_codec_splits2 + prop_codec_splits3"),
        ("localtxmonitor.tsv", "ouroboros-network:protocols-tests / LocalTxMonitor / codecM + codec cborM"),
        ("localtxmonitor-splits.tsv", "ouroboros-network:protocols-tests / LocalTxMonitor / prop_codec_splitsM_LocalTxMonitor splits2 + splits3"),
        ("txsubmission2.tsv", "ouroboros-network:protocols-tests / TxSubmission2 / codec + codec cbor"),
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
            } if name in ("chainsync.tsv", "blockfetch.tsv", "localsq.tsv", "localtxsubmission.tsv", "localtxmonitor.tsv", "txsubmission2.tsv", "keepalive-splits.tsv", "localtxsubmission-splits.tsv", "localtxmonitor-splits.tsv") else {
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
                status: sum(row[-3] == status for row in rows)
                for status in sorted({row[-3] for row in rows})
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
        if name in ("localtxsubmission-splits.tsv", "localtxmonitor-splits.tsv"):
            split2 = "prop_codec_splits2" if name == "localtxsubmission-splits.tsv" else "prop_codec_splitsM_LocalTxMonitor_splits2"
            split3 = "prop_codec_splits3" if name == "localtxsubmission-splits.tsv" else "prop_codec_splitsM_LocalTxMonitor_splits3"
            manifest["upstreamPropertyOutcomesByProperty"] = {
                split2: {"pass": sum(row[-2] == "pass" for row in rows), "upstream-failure": sum(row[-2] != "pass" for row in rows)},
                split3: {"pass": sum(row[-1] == "pass" for row in rows), "upstream-failure": sum(row[-1] != "pass" for row in rows)},
            }
            manifest["adapterInputStatuses"] = {
                "byConstructor": {tag: sum(row[6] == tag for row in rows) for tag in sorted({row[6] for row in rows})},
                "sourceStates": {state: sum(row[4] == state for row in rows) for state in sorted({row[4] for row in rows})},
            }
            manifest["propertyReplaySeedOffsets"] = {split2: 3000003, split3: 4000003}
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
        (args.output_dir / name).write_bytes(raw)
        (args.output_dir / (name + ".manifest.json")).write_text(json.dumps(manifest, indent=2) + "\n")
        manifests.append(manifest)
    print(json.dumps(manifests, indent=2))


if __name__ == "__main__":
    main()
