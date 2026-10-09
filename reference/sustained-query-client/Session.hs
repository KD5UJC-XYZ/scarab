{-# LANGUAGE DataKinds, GADTs, OverloadedStrings, ScopedTypeVariables #-}
-- Additive exact-point oracle; compiled against the frozen reviewed dependency closure.
module Session (Block, Capture(..), session, maxReplyBytes) where

import Cardano.Protocol.Crypto (StandardCrypto)
import qualified Data.ByteString.Lazy as LBS
import Data.IORef (IORef, writeIORef)
import Ouroboros.Consensus.Cardano.Block (CardanoBlock)
import qualified Ouroboros.Consensus.Cardano.Block as Cardano
import qualified Ouroboros.Consensus.Cardano.CanHardFork ()
import Ouroboros.Consensus.Shelley.Ledger.SupportsProtocol ()
import Ouroboros.Consensus.Block (Point, BlockNo)
import qualified Ouroboros.Consensus.Ledger.Query as Q
import qualified Ouroboros.Consensus.Shelley.Ledger.Query as Shelley
import Ouroboros.Network.Block (Serialised(..))
import Cardano.Slotting.Slot (WithOrigin)
import qualified Ouroboros.Network.Protocol.LocalStateQuery.Client as LSQ
import qualified Ouroboros.Network.Protocol.LocalStateQuery.Type as LSQ

type Block = CardanoBlock StandardCrypto

data Capture = Capture
  { acquiredPoint :: !(Point Block)
  , acquiredBlock :: !(WithOrigin BlockNo)
  , finalPoint :: !(Point Block)
  , finalBlock :: !(WithOrigin BlockNo)
  , epochBytes :: !LBS.ByteString
  , utxoBytes :: !LBS.ByteString
  , protocolBytes :: !LBS.ByteString
  , parameterBytes :: !LBS.ByteString
  }

maxReplyBytes :: Int
maxReplyBytes = 8 * 1024 * 1024

-- Exactly one acquire. No reacquire, retry, volatile-tip or immutable-tip fallback.
-- All ordinary failure branches after Acquired return through finish/SendMsgRelease.
-- A disconnected peer/async cancellation cannot guarantee a wire Release; Transport
-- closes the connection instead. Such failures never produce a capture result.
session :: Point Block -> IORef (Either String Capture)
        -> LSQ.LocalStateQueryClient Block (Point Block) (Q.Query Block) IO ()
session requested result = LSQ.LocalStateQueryClient $ pure $
  LSQ.SendMsgAcquire (LSQ.SpecificPoint requested) LSQ.ClientStAcquiring
    { LSQ.recvMsgFailure = \failure -> do
        writeIORef result (Left ("acquire failed: " ++ show failure))
        pure (LSQ.SendMsgDone ())
    , LSQ.recvMsgAcquired = pure $ ask Q.GetChainPoint $ \point ->
        if point /= requested then finish (Left "acquired point differs from requested point")
        else pure $ ask Q.GetChainBlockNo $ \block ->
          pure $ ask (Q.BlockQuery (Cardano.QueryIfCurrentConway
            (Shelley.GetCBOR Shelley.DebugNewEpochState))) $ \epoch ->
              case epoch of
                Left _ -> finish (Left "epoch query era mismatch")
                Right (Serialised rawEpoch)
                  | tooLarge rawEpoch -> finish (Left "epoch reply exceeds limit")
                  | otherwise -> pure $ ask (Q.BlockQuery (Cardano.QueryIfCurrentConway
                      (Shelley.GetCBOR Shelley.GetUTxOWhole))) $ \utxo ->
                        case utxo of
                          Left _ -> finish (Left "UTxO query era mismatch")
                          Right (Serialised rawUtxo)
                            | tooLarge rawUtxo -> finish (Left "UTxO reply exceeds limit")
                            | otherwise -> pure $ ask (Q.BlockQuery (Cardano.QueryIfCurrentConway
                                (Shelley.GetCBOR Shelley.DebugChainDepState))) $ \protocol ->
                                  case protocol of
                                    Left _ -> finish (Left "protocol query era mismatch")
                                    Right (Serialised rawProtocol)
                                      | tooLarge rawProtocol -> finish (Left "protocol reply exceeds limit")
                                      | otherwise -> pure $ ask (Q.BlockQuery (Cardano.QueryIfCurrentConway
                                          (Shelley.GetCBOR Shelley.GetCurrentPParams))) $ \parameters ->
                                            case parameters of
                                              Left _ -> finish (Left "parameters query era mismatch")
                                              Right (Serialised rawParameters)
                                                | tooLarge rawParameters -> finish (Left "parameters reply exceeds limit")
                                                | otherwise -> pure $ ask Q.GetChainPoint $ \lastPoint ->
                                                    if lastPoint /= requested then finish (Left "acquired point changed")
                                                    else pure $ ask Q.GetChainBlockNo $ \lastBlock ->
                                                      if lastBlock /= block then finish (Left "acquired block number changed")
                                                      else finish (Right (Capture point block lastPoint lastBlock rawEpoch rawUtxo rawProtocol rawParameters))

    }
 where
  -- Length checks occur after the framework has decoded a reply. The invocation
  -- contract therefore also requires an external memory/time limit; this is not
  -- a preallocation wire-size guarantee.
  tooLarge bytes = LBS.null bytes || LBS.length bytes > fromIntegral maxReplyBytes
  ask :: Q.Query Block a
      -> (a -> IO (LSQ.ClientStAcquired Block (Point Block) (Q.Query Block) IO ()))
      -> LSQ.ClientStAcquired Block (Point Block) (Q.Query Block) IO ()
  ask query next = LSQ.SendMsgQuery query LSQ.ClientStQuerying
    { LSQ.recvMsgResult = next }
  -- Write the result only in the Release continuation. Transport returns it only
  -- after connectTo returns successfully. There is no Release acknowledgement.
  finish value = pure $ LSQ.SendMsgRelease $ do
    writeIORef result value
    pure (LSQ.SendMsgDone ())
