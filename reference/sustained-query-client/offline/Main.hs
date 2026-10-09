{-# LANGUAGE DataKinds, GADTs, OverloadedStrings, PatternSynonyms, ScopedTypeVariables, TypeApplications #-}
-- Runs only in the reviewed bounded container after compilation is authorized.
-- Drives the real Session DSL, not a second Python model. It does not test mux,
-- codec bytes, handshake or the native ChainDB forker implementation.
module Main where
import Cardano.Chain.Slotting (EpochSlots(..))
import qualified Cardano.Network.NodeToClient.Version as N
import Ouroboros.Consensus.Cardano.Node (protocolClientInfoCardano)
import Ouroboros.Consensus.Node.ProtocolInfo (ProtocolClientInfo(..))
import qualified Ouroboros.Consensus.Node.NetworkProtocolVersion as C
import Ouroboros.Consensus.Node.Serialisation (SerialiseResult(..))
import Ouroboros.Consensus.Util (SomeSecond(..))
import qualified Codec.CBOR.Encoding as CBOR
import qualified Codec.CBOR.Write as CBOR
import Data.Aeson (object, (.=), Value(..))
import System.Timeout (timeout)
import System.IO (stdout, hFlush)
import qualified Adapter as A
import Transport (captureAt)
import StrictJson (readInput, fields, stringAt, hexBytes)
import System.Environment (getArgs)
import System.Exit (die)
import Control.Concurrent
import Control.Exception (SomeException, AsyncException(ThreadKilled), try, finally, throwIO)
import Control.Monad (unless, forM_)
import Data.Either (isLeft, isRight)
import Data.IORef
import Data.Default (def)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as LBS
import qualified Data.Map.Strict as Map
import Data.Proxy (Proxy(..))
import Data.SOP.Strict (NS(..))
import Data.SOP.Match (Mismatch(..))
import Cardano.Protocol.Crypto (StandardCrypto)
import Cardano.Ledger.Binary (DecCBOR, decodeFull', natVersion)
import qualified Cardano.Ledger.Binary.Plain as Plain
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Shelley (ShelleyEra)
import Cardano.Ledger.Shelley.LedgerState (NewEpochState)
import Cardano.Ledger.State (UTxO(..))
import Cardano.Ledger.Coin (Coin(..))
import Cardano.Ledger.Address (Addr(..))
import Cardano.Ledger.BaseTypes (Network(..))
import Cardano.Ledger.Credential (Credential(..), StakeReference(..))
import Cardano.Ledger.Conway.Core (mkCoinTxOut)
import Ouroboros.Consensus.Block (Point, SlotNo(..), BlockNo(..), pattern BlockPoint, fromRawHash)
import Ouroboros.Consensus.Byron.Ledger.Block (ByronBlock)
import Ouroboros.Consensus.Shelley.Ledger.Block (ShelleyBlock)
import Ouroboros.Consensus.Protocol.TPraos (TPraos)
import Ouroboros.Consensus.HardFork.Combinator (MismatchEraInfo(..), LedgerEraInfo(..), singleEraInfo)
import qualified Ouroboros.Consensus.Cardano.Block as Cardano
import qualified Ouroboros.Consensus.Ledger.Query as Q
import qualified Ouroboros.Consensus.Shelley.Ledger.Query as Shelley
import Ouroboros.Network.Block (Serialised(..))
import Cardano.Slotting.Slot (WithOrigin(..))
import qualified Ouroboros.Network.Protocol.LocalStateQuery.Client as LSQ
import qualified Ouroboros.Network.Protocol.LocalStateQuery.Type as LSQ
import Adapter (exactVersion, verifierEnvelope)
import Session
import StrictJson (decodeObject, hexText, writeOutput)
import VerifySeed
import Projection
import Ouroboros.Consensus.Protocol.Praos (PraosState(..))
import Cardano.Ledger.Core (PParams)
import Cardano.Ledger.BaseTypes (Nonce(..), mkNonceFromNumber)

check :: String -> Bool -> IO ()
check label ok = unless ok (fail label)
pointAt :: Word -> Point Block
pointAt n = case fromRawHash (Proxy @Block) (BS.replicate 32 0x11) of
  Just h -> BlockPoint (SlotNo (fromIntegral n)) h
  Nothing -> error "fixed 32-byte fixture hash"

data Mode = Good | Refuse | RefuseNotOnChain | WrongFirst | WrongLast | WrongBlock | WrongEra | WrongUTxOEra | BigEpoch | BigUTxO | WrongProtocolEra | WrongParametersEra | BigProtocol | BigParameters | Disconnect
  deriving (Eq, Show)

-- The responder advances its public tip on every query while replies are read
-- from the view frozen by this one acquire. A faulty changed bracket is separate.
runSession :: Mode -> IO (Either String Capture, [String], Int)
runSession mode = do
  result <- newIORef (Left "not completed")
  trace <- newIORef []
  tip <- newIORef (1505::Int)
  let logMsg x = modifyIORef' trace (++[x])
      idle :: LSQ.ClientStIdle Block (Point Block) (Q.Query Block) IO () -> IO ()
      idle (LSQ.SendMsgDone ()) = logMsg "done"
      idle (LSQ.SendMsgAcquire target next) = do
        logMsg "acquire"
        case target of
          LSQ.SpecificPoint p -> check "specific point" (p == pointAt 1505)
          _ -> fail "unexpected tip acquire"
        if mode `elem` [Refuse,RefuseNotOnChain] then LSQ.recvMsgFailure next (if mode == Refuse then LSQ.AcquireFailurePointTooOld else LSQ.AcquireFailurePointNotOnChain) >>= idle
        else LSQ.recvMsgAcquired next >>= acquired 0
      acquired :: Int -> LSQ.ClientStAcquired Block (Point Block) (Q.Query Block) IO () -> IO ()
      acquired _ (LSQ.SendMsgRelease next) = logMsg "release" >> next >>= idle
      acquired _ (LSQ.SendMsgReAcquire _ _) = fail "unexpected reacquire"
      acquired n (LSQ.SendMsgQuery query next) = do
        logMsg "query"
        modifyIORef' tip (+1)
        if mode == Disconnect && n == 2 then throwIO (userError "simulated disconnect") else pure ()
        let epoch = if mode == BigEpoch then LBS.replicate (fromIntegral maxReplyBytes+1) 0 else "epoch"
            utxo = if mode == BigUTxO then LBS.replicate (fromIntegral maxReplyBytes+1) 0 else "utxo"
            continue reply = LSQ.recvMsgResult next reply >>= acquired (n+1)
        case query of
          Q.GetChainPoint -> continue $ if (mode == WrongFirst && n == 0) || (mode == WrongLast && n > 0) then pointAt 1506 else pointAt 1505
          Q.GetChainBlockNo -> continue (At (BlockNo (if mode == WrongBlock && n > 1 then 67 else 66)))
          Q.BlockQuery (Cardano.QueryIfCurrentConway (Shelley.GetCBOR Shelley.DebugNewEpochState)) ->
            continue (if mode == WrongEra then Left mismatch else Right (Serialised epoch))
          Q.BlockQuery (Cardano.QueryIfCurrentConway (Shelley.GetCBOR Shelley.GetUTxOWhole)) -> continue (if mode == WrongUTxOEra then Left mismatch else Right (Serialised utxo))
          Q.BlockQuery (Cardano.QueryIfCurrentConway (Shelley.GetCBOR Shelley.DebugChainDepState)) -> continue (if mode == WrongProtocolEra then Left mismatch else Right (Serialised (if mode == BigProtocol then LBS.replicate (fromIntegral maxReplyBytes+1) 0 else nativeProtocol)))
          Q.BlockQuery (Cardano.QueryIfCurrentConway (Shelley.GetCBOR Shelley.GetCurrentPParams)) -> continue (if mode == WrongParametersEra then Left mismatch else Right (Serialised (if mode == BigParameters then LBS.replicate (fromIntegral maxReplyBytes+1) 0 else nativeParameters)))
          _ -> fail "unexpected query"
      mismatch = MismatchEraInfo $ MR
        (Z (singleEraInfo (Proxy @(ShelleyBlock (TPraos StandardCrypto) ShelleyEra))))
        (LedgerEraInfo (singleEraInfo (Proxy @ByronBlock)))
  outcome <- try (LSQ.runLocalStateQueryClient (session (pointAt 1505) result) >>= idle) :: IO (Either SomeException ())
  case outcome of Left _ -> check "disconnect mode only" (mode == Disconnect); Right () -> pure ()
  (,,) <$> readIORef result <*> readIORef trace <*> readIORef tip

-- Cancellation while waiting for a query reply never executes Release's
-- continuation or publishes a result. The simulated resource finally closes.
cancelCase :: IO ()
cancelCase = do
  result <- newIORef (Left "not completed")
  started <- newEmptyMVar
  blocker <- newEmptyMVar
  done <- newEmptyMVar
  closed <- newIORef False
  tid <- forkIO $ (do
    idle <- LSQ.runLocalStateQueryClient (session (pointAt 1505) result)
    case idle of
      LSQ.SendMsgAcquire _ next -> do
        acquired <- LSQ.recvMsgAcquired next
        case acquired of
          LSQ.SendMsgQuery _ _ -> putMVar started () >> takeMVar blocker
          _ -> fail "expected query"
      _ -> fail "expected acquire") `finally` (writeIORef closed True >> putMVar done ())
  takeMVar started
  throwTo tid ThreadKilled
  takeMVar done
  readIORef result >>= check "cancel must not publish" . isLeft
  readIORef closed >>= check "cancel closes simulated resource"

fixtureUTxO :: Int -> UTxO ConwayEra
fixtureUTxO count = UTxO (Map.fromList [(txin n,out) | n <- [0..count-1]])
 where
  marker :: DecCBOR a => BS.ByteString -> a
  marker = either (error . show) id . decodeFull' (natVersion @9)
  key = marker (BS.pack [0x58,0x1c] <> BS.replicate 28 5)
  out = mkCoinTxOut (Addr Testnet (KeyHashObj key) StakeRefNull) (Coin 1)
  index n | n < 24 = BS.singleton (fromIntegral n)
          | n < 256 = BS.pack [0x18,fromIntegral n]
          | otherwise = BS.pack [0x19,fromIntegral (n `div` 256),fromIntegral (n `mod` 256)]
  txin n = marker (BS.pack [0x82,0x58,0x20] <> BS.replicate 32 4 <> index n)

nativeEpoch :: LBS.ByteString
nativeEpoch = Plain.serialize (def :: NewEpochState ConwayEra)

nativeProtocol :: LBS.ByteString
nativeProtocol = Plain.serialize (PraosState (At (SlotNo 1505)) (Map.singleton (either (error . show) id (decodeFull' (natVersion @9) (BS.pack [0x58,0x1c] <> BS.replicate 28 5))) 7) (mkNonceFromNumber 1) NeutralNonce (mkNonceFromNumber 2) NeutralNonce (mkNonceFromNumber 3) NeutralNonce)
nativeParameters :: LBS.ByteString
nativeParameters = Plain.serialize (def :: PParams ConwayEra)
nativeCases :: IO ()
nativeCases = do
  let empty = Plain.serialize (fixtureUTxO 0)
      cap = Capture (pointAt 1505) (At (BlockNo 66)) (pointAt 1505) (At (BlockNo 66)) nativeEpoch empty nativeProtocol nativeParameters
  check "native projection" (isRight (projectCapture cap))
  forM_ [cap {protocolBytes = nativeProtocol <> "\0"}, cap {parameterBytes = nativeParameters <> "\0"}, cap {protocolBytes = "\xff"}, cap {parameterBytes = "\xff"}] $ \bad -> check "projection malformed/trailing" (isLeft (projectCapture bad))
  forM_ [0,1,maxUTxOEntries] $ \count ->
    case verifySeed nativeEpoch (Plain.serialize (fixtureUTxO count)) of
      Left err -> fail err
      Right verified -> do
        check "native reconstructed entry count" (wholeUTxOEntries verified == count)
        check "nonempty seed differs from debug" (count == 0 || derivedFullSeed verified /= nativeEpoch)
  forM_ [(nativeEpoch <> "\0",empty),(nativeEpoch,empty <> "\0"),("\xff",empty),(nativeEpoch,"\xff")] $ \(e,u) ->
    check "malformed/trailing native CBOR" (isLeft (verifySeed e u))
  check "4097 UTxO entry cap" (isLeft (verifySeed nativeEpoch (Plain.serialize (fixtureUTxO (maxUTxOEntries+1)))))

-- Every ledger query/result payload below is encoded by the frozen native
-- consensus implementation; the Python peer supplies only mux/LSQ framing.
wireFixtures :: Either String Value
wireFixtures = do
  let version = N.NodeToClientV_16
      config = pClientInfoCodecConfig (protocolClientInfoCardano @StandardCrypto (EpochSlots 21600))
  blockVersion <- maybe (Left "fixture version unsupported") Right
    (Map.lookup version (C.supportedNodeToClientVersions (Proxy @Block)))
  let query :: Q.Query Block a -> Value
      query q = hex (CBOR.encodeListLen 2 <> CBOR.encodeWord 3 <>
        Q.queryEncodeNodeToClient config (Q.nodeToClientVersionToQueryVersion version) blockVersion (SomeSecond q))
      result :: Q.Query Block a -> a -> Value
      result q value = hex (CBOR.encodeListLen 2 <> CBOR.encodeWord 4 <> encodeResult config blockVersion q value)
      hex encoding = toJSONText (hexText (LBS.toStrict (CBOR.toLazyByteString encoding)))
      toJSONText t = String t
      epochQuery = Q.BlockQuery (Cardano.QueryIfCurrentConway (Shelley.GetCBOR Shelley.DebugNewEpochState))
      utxoQuery = Q.BlockQuery (Cardano.QueryIfCurrentConway (Shelley.GetCBOR Shelley.GetUTxOWhole))
      protocolQuery = Q.BlockQuery (Cardano.QueryIfCurrentConway (Shelley.GetCBOR Shelley.DebugChainDepState))
      parametersQuery = Q.BlockQuery (Cardano.QueryIfCurrentConway (Shelley.GetCBOR Shelley.GetCurrentPParams))
      mismatch = MismatchEraInfo $ MR
        (Z (singleEraInfo (Proxy @(ShelleyBlock (TPraos StandardCrypto) ShelleyEra))))
        (LedgerEraInfo (singleEraInfo (Proxy @ByronBlock)))
      raw = hexText . LBS.toStrict
  pure $ object
    ["protocolHex" .= raw nativeProtocol,"parametersHex" .= raw nativeParameters,"epochHex" .= raw nativeEpoch,"utxoHex" .= raw (Plain.serialize (fixtureUTxO 1)),
     "boundaryUTxOHex" .= raw (Plain.serialize (fixtureUTxO maxUTxOEntries)),
     "oversizedUTxOHex" .= raw (Plain.serialize (fixtureUTxO (maxUTxOEntries+1))),
     "queries" .= [query Q.GetChainPoint,query Q.GetChainBlockNo,query epochQuery,query utxoQuery,query protocolQuery,query parametersQuery,query Q.GetChainPoint,query Q.GetChainBlockNo],
     "replies" .= [result Q.GetChainPoint (pointAt 1505), result Q.GetChainBlockNo (At (BlockNo 66)),
       result epochQuery (Right (Serialised nativeEpoch)),result utxoQuery (Right (Serialised (Plain.serialize (fixtureUTxO 1)))),
       result protocolQuery (Right (Serialised nativeProtocol)),result parametersQuery (Right (Serialised nativeParameters)),
       result Q.GetChainPoint (pointAt 1505),result Q.GetChainBlockNo (At (BlockNo 66))],
     "wrongPoint" .= result Q.GetChainPoint (pointAt 1506),"wrongBlock" .= result Q.GetChainBlockNo (At (BlockNo 67)),
     "protocolMismatch" .= result protocolQuery (Left mismatch),"parametersMismatch" .= result parametersQuery (Left mismatch),"epochMismatch" .= result epochQuery (Left mismatch),"utxoMismatch" .= result utxoQuery (Left mismatch),
     "oversizedEpochReply" .= result epochQuery (Right (Serialised (LBS.replicate (fromIntegral maxReplyBytes+1) 0))),
     "oversizedUTxOReply" .= result utxoQuery (Right (Serialised (LBS.replicate (fromIntegral maxReplyBytes+1) 0)))]

main :: IO ()
main = do
  args <- getArgs
  case args of
    ["fixtures"] -> either die writeOutput wireFixtures
    ["capture-cancel"] -> captureCancel
    ["project-retained"] -> do
      input <- readInput (4*maxReplyBytes+1024)
      obj <- either die pure (decodeObject input)
      either die pure (fields ["epochHex","utxoHex"] obj)
      epoch <- either die pure (stringAt "epochHex" obj >>= hexBytes maxReplyBytes)
      utxo <- either die pure (stringAt "utxoHex" obj >>= hexBytes maxReplyBytes)
      let capture = Capture (pointAt 1505) (At (BlockNo 66)) (pointAt 1505) (At (BlockNo 66)) (LBS.fromStrict epoch) (LBS.fromStrict utxo) nativeProtocol nativeParameters
      either die writeOutput (projectCapture capture)
    [] -> runCases
    _ -> die "usage: epoch-query-offline [fixtures]"

runCases :: IO ()
runCases = do
  forM_ [Good,Refuse,RefuseNotOnChain,WrongFirst,WrongLast,WrongBlock,WrongEra,WrongUTxOEra,BigEpoch,BigUTxO,WrongProtocolEra,WrongParametersEra,BigProtocol,BigParameters,Disconnect] $ \mode -> do
    (result,trace,tip) <- runSession mode
    check (show mode ++ " result") (isRight result == (mode == Good))
    check "exactly one acquire" (length (filter (=="acquire") trace) == 1)
    check "release lifecycle" (length (filter (=="release") trace) == if mode `elem` [Refuse,RefuseNotOnChain,Disconnect] then 0 else 1)
    if mode == Good then check "server advanced under acquired view" (tip == 1513) else pure ()
  cancelCase
  nativeCases
  check "unsupported version" (isLeft (exactVersion 15) && isLeft (exactVersion 24))
  forM_ ["{\"a\":1,\"a\":2}","{\"a\":1,\"\\u0061\":2}","{\"a\":NaN}","{\"a\":Infinity}","{\"a\":1.0}","{\"a\":1e0}","{\"a\":true} trailing"] $ \raw ->
    check "strict JSON rejection" (isLeft (decodeObject raw))
  check "verifier exact fields" (isLeft (verifierEnvelope "{\"epochHex\":\"ff\",\"utxoHex\":\"a0\",\"extra\":true}"))
  putStrLn "offline typed-session/native/strict-JSON cases passed; no socket or runtime admission"

-- An asynchronous timeout is delivered inside actual captureAt/IO-manager/mux.
-- Remain alive after cancellation so the fake peer can verify EOF before process
-- exit; mere OS process teardown is not accepted as the cleanup observation.
captureCancel :: IO ()
captureCancel = do
  req <- readInput 16384 >>= either die pure . A.parseRequest
  let config = pClientInfoCodecConfig (protocolClientInfoCardano @StandardCrypto (EpochSlots (A.byronSlots req)))
      versionData = N.NodeToClientVersionData {N.networkMagic=N.NetworkMagic (A.magic req),N.query=False}
  outcome <- timeout 1000000 (captureAt config versionData (A.exactWireVersion req) (A.socketPath req) (A.requestedPoint req))
  case outcome of
    Just _ -> die "expected cancellation of blocked fake peer"
    Nothing -> do
      writeOutput (object ["cancelled" .= True,"capturePublished" .= False])
      hFlush stdout
      threadDelay 1000000
