-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE BangPatterns #-}
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TypeApplications #-}

-- The reference side of the endorser-block closure benchmark (#1380): the closure check of the
-- Leios voting thread (`validateEbClosure`, ouroboros-consensus d91b3557
-- `ouroboros-consensus/src/ouroboros-consensus/LeiosVoting.hs:467-560`) as the ledger runs it, on
-- the input `ClosureBench --export` writes, timed per run.
--
-- The state is ticked to the slot (`applyTickNoEvents`, cardano-ledger 9df9f98c
-- `eras/shelley/impl/src/Cardano/Ledger/Shelley/API/Validation.hs:186-193`), and each transaction
-- of the closure, in order, is either applied in full (`applyTx`,
-- `eras/shelley/impl/src/Cardano/Ledger/Shelley/API/Mempool.hs:447-453`), as `validateEbClosure`
-- does with `Intervene`, or, at a position the cache holds, reapplied from the token
-- `assumeValidatedClosureTx` makes (`unsafeMakeValidatedTx`, `Mempool.hs:132-140`, on the state the
-- transaction meets, `ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Ledger/
-- Leios.hs:183-191` at 2416493b) with `reapplyValidatedTx` (`Mempool.hs:420-445`, as
-- `reapplyShelleyTx` calls it, `Shelley/Ledger/Mempool.hs:368-387`). The mempool environment is
-- made once from the ticked state (`mkMempoolEnv`), as it depends on no UTxO. What consensus adds
-- around these calls (its ledger tables, the per-transaction input restriction, the cache's
-- bookkeeping) is not run: this times the ledger's work.
--
-- Every input is forced before the first run, so each run starts at the same boundary. Inside the
-- timed span the verdict, the counts and the final UTxO are forced, not the whole ledger state the
-- voter discards; a refusal's rendering is made after the clock stops. What this times is the
-- validation calls alone: the measure a miss also takes (`txMeasurePhase1`/`2`) is not computed.
-- Each run prints one JSON line.
--
-- Arguments: <shelley-genesis.json> <export dir> <runs>.
module Main (main) where

import Cardano.Ledger.BaseTypes (Globals, natVersion)
import qualified Cardano.Ledger.Binary as Binary
import Cardano.Ledger.Core (Tx, TxLevel (..), TxOut)
import Cardano.Ledger.Dijkstra (DijkstraEra)
import Cardano.Ledger.Dijkstra.Tx (Tx (MkDijkstraTx), decodeDijkstraTopTxInBlock)
import Cardano.Ledger.Shelley.API.Mempool (applyTx, mkMempoolEnv, mkMempoolState, reapplyValidatedTx, unsafeMakeValidatedTx)
import Cardano.Ledger.Shelley.API.Validation (applyTickNoEvents)
import Cardano.Ledger.Shelley.Genesis (ShelleyGenesis (..), fromNominalDiffTimeMicro, mkShelleyGlobals)
import Cardano.Ledger.Shelley.LedgerState (LedgerState (..), NewEpochState, UTxOState (..), esLStateL, nesEsL)
import Cardano.Ledger.State (UTxO (..))
import Cardano.Ledger.TxIn (TxIn)
import Cardano.Slotting.EpochInfo (fixedEpochInfo)
import Cardano.Slotting.Slot (SlotNo (..))
import Cardano.Slotting.Time (mkSlotLength)
import Control.DeepSeq (NFData (..), force)
import Control.Exception (evaluate)
import Data.Aeson (eitherDecodeFileStrict, encode, object, (.=))
import qualified Data.ByteString.Lazy as LBS
import qualified Data.ByteString.Lazy.Char8 as LBS8
import qualified Data.IntSet as IntSet
import qualified Data.Map.Strict as Map
import GHC.Clock (getMonotonicTimeNSec)
import Lens.Micro (over)
import System.Environment (getArgs)
import System.Exit (die)

type Era = DijkstraEra

-- | Why a run stops: a transaction the rules refuse, at its position.
data Refused = Refused Int String

instance NFData Refused where
  rnf (Refused i e) = rnf i `seq` rnf e

main :: IO ()
main = do
  args <- getArgs
  case args of
    [genesisFile, dir, runsText] -> run genesisFile dir (read runsText)
    _ -> die "usage: scarab-leios-closure-bench <shelley-genesis.json> <export dir> <runs>"

decoded :: String -> Either Binary.DecoderError a -> IO a
decoded what = either (die . ((what <> ": ") <>) . show) pure

run :: FilePath -> FilePath -> Int -> IO ()
run genesisFile dir runs = do
  genesis <- either die pure =<< eitherDecodeFileStrict @ShelleyGenesis genesisFile
  let globals =
        mkShelleyGlobals
          genesis
          (fixedEpochInfo (sgEpochLength genesis) (mkSlotLength (fromNominalDiffTimeMicro (sgSlotLength genesis))))
  nes <- decoded "nes" . Binary.decodeFull (natVersion @12) =<< LBS.readFile (dir <> "/nes.cbor")
  spent <- decoded "utxo" . Binary.decodeFull (natVersion @12) =<< LBS.readFile (dir <> "/utxo.cbor")
  closure <-
    decoded "closure"
      . Binary.decodeFullAnnotator (natVersion @12) "closure" (fmap (map MkDijkstraTx) . sequence <$> Binary.decodeList decodeDijkstraTopTxInBlock)
      =<< LBS.readFile (dir <> "/closure.cbor")
  cached <- either die (pure . IntSet.fromList) =<< eitherDecodeFileStrict @[Int] (dir <> "/cached.json")
  slot <- SlotNo . read <$> readFile (dir <> "/slot.txt")
  let withSpent :: NewEpochState Era
      withSpent = over (nesEsL . esLStateL) (\ls -> ls {lsUTxOState = (lsUTxOState ls) {utxosUtxo = UTxO (spent :: Map.Map TxIn (TxOut Era))}}) nes
      txs = zip [0 ..] (closure :: [Tx TopTx Era])
  _ <- evaluate (force (withSpent, txs, cached, slot))
  mapM_ (\r -> timed globals slot cached withSpent txs r >>= LBS8.putStrLn) [1 .. runs]

timed :: Globals -> SlotNo -> IntSet.IntSet -> NewEpochState Era -> [(Int, Tx TopTx Era)] -> Int -> IO LBS.ByteString
timed globals slot cached nes txs r = do
  start <- getMonotonicTimeNSec
  let ticked = applyTickNoEvents globals nes slot
      env = mkMempoolEnv ticked slot
      go !st !reapplied !applied [] = Right (st, reapplied, applied)
      go !st !reapplied !applied ((i, tx) : rest)
        | i `IntSet.member` cached =
            either (Left . Refused i . show) (\st' -> go st' (reapplied + 1) applied rest) $
              reapplyValidatedTx globals env st (unsafeMakeValidatedTx globals env st tx)
        | otherwise =
            either (Left . Refused i . show) (\(st', _) -> go st' reapplied (applied + 1) rest) $
              applyTx globals env st tx
  outcome <- case go (mkMempoolState ticked) (0 :: Int) (0 :: Int) txs of
    Right (st, reapplied, applied) -> do
      _ <- evaluate (force (utxosUtxo (lsUTxOState st)))
      pure (Right (reapplied, applied))
    Left refused -> pure (Left refused)
  end <- getMonotonicTimeNSec
  let seconds = fromIntegral (end - start) / 1e9 :: Double
      n = length txs
  pure . encode $ case outcome of
    Right (reapplied, applied) ->
      object
        [ "closureBenchReference" .= ("run" :: String)
        , "run" .= (r :: Int)
        , "verdict" .= ("valid" :: String)
        , "transactions" .= n
        , "reapplied" .= reapplied
        , "applied" .= applied
        , "seconds" .= seconds
        , "msPerTx" .= (1000 * seconds / fromIntegral n)
        ]
    Left (Refused i e) ->
      object
        [ "closureBenchReference" .= ("run" :: String)
        , "run" .= r
        , "verdict" .= ("refused at " <> show i <> ": " <> take 2000 e)
        , "seconds" .= seconds
        ]
