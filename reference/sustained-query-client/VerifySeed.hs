{-# LANGUAGE DataKinds, TypeApplications #-}
module VerifySeed (VerifiedSeed(..), verifySeed, maxUTxOEntries) where

import qualified Cardano.Ledger.Binary.Plain as Plain
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Shelley.LedgerState
import Cardano.Ledger.State (UTxO(..))
import qualified Data.ByteString.Lazy as LBS
import qualified Data.Map.Strict as Map

data VerifiedSeed = VerifiedSeed
  { normalizedDebug :: !LBS.ByteString
  , normalizedWholeUTxO :: !LBS.ByteString
  , derivedFullSeed :: !LBS.ByteString
  , wholeUTxOEntries :: !Int
  }

maxUTxOEntries :: Int
maxUTxOEntries = 4096

-- Caller must establish same-acquire provenance first. Byte decoding alone cannot
-- establish it. Keep every original payload; the returned full seed is DERIVED.
verifySeed :: LBS.ByteString -> LBS.ByteString -> Either String VerifiedSeed
verifySeed rawEpoch rawUtxo = do
  epoch <- either (Left . show) Right (Plain.decodeFull rawEpoch)
    :: Either String (NewEpochState ConwayEra)
  utxo <- either (Left . show) Right (Plain.decodeFull rawUtxo)
    :: Either String (UTxO ConwayEra)
  if Map.size (unUTxO utxo) > maxUTxOEntries
    then Left "private-testnet UTxO entry cap exceeded" else pure ()
  let es = nesEs epoch
      ls = esLState es
      us = lsUTxOState ls
  if not (Map.null (unUTxO (utxosUtxo us)))
    then Left "unexpected nonempty debug UTxO; query contract requires review"
    else pure ()
  let epochAgain = Plain.serialize epoch
      utxoAgain = Plain.serialize utxo
      derived = epoch { nesEs = es { esLState = ls { lsUTxOState = us { utxosUtxo = utxo } } } }
      full = Plain.serialize derived
  e2 <- either (Left . show) Right (Plain.decodeFull epochAgain)
  u2 <- either (Left . show) Right (Plain.decodeFull utxoAgain)
  d2 <- either (Left . show) Right (Plain.decodeFull full)
  let ds = nesEs d2; dl = esLState ds; du = lsUTxOState dl
      restored = d2 { nesEs = ds { esLState = dl { lsUTxOState = du { utxosUtxo = utxosUtxo us } } } }
  if e2 /= epoch || u2 /= utxo || d2 /= derived || restored /= epoch
    then Left "native semantic round-trip mismatch"
    else Right (VerifiedSeed epochAgain utxoAgain full (Map.size (unUTxO utxo)))
