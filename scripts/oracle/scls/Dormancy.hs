-- SPDX-License-Identifier: Apache-2.0
-- Execute the unmodified ledger f649f975 updateDormantDRepExpiry function.
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE TypeApplications #-}
module Main where

import Cardano.Ledger.BaseTypes (EpochNo (..), StrictMaybe (..))
import Cardano.Ledger.Binary qualified as Binary
import Cardano.Ledger.Coin (CompactForm (..))
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Conway.Rules (updateDormantDRepExpiry)
import Cardano.Ledger.Conway.State (VState (..), CommitteeState (..))
import Cardano.Ledger.DRep (DRepState (..))
import Data.ByteString qualified as BS
import Data.Map.Strict qualified as Map
import Data.Set qualified as Set
import Data.Word (Word64)
import System.Exit (die)

cases :: [(Word64, Word64, Word64)]
cases =
  [ (10, 0, 7), (10, 2, 7), (10, 2, 8), (10, 2, 9)
  , (10, 2, maxBound), (10, 2, maxBound - 2), (0, 2, maxBound), (1, 1, maxBound)
  , (maxBound, 1, maxBound - 1), (10, maxBound, 12)
  ]

main :: IO ()
main = do
  credential <- either (die . show) pure $
    Binary.decodeFull' (Binary.natVersion @9) (BS.pack [0x82, 0, 0x58, 28] <> BS.replicate 28 0)
  mapM_ (emit credential) cases
  where
    emit credential (epoch, dormant, expiry) = do
      let original = DRepState (EpochNo expiry) SNothing (CompactCoin 0) Set.empty
          state = VState (Map.singleton credential original) (CommitteeState Map.empty) (EpochNo dormant) :: VState ConwayEra
          result = updateDormantDRepExpiry (EpochNo epoch) state
      normalized <- maybe (die "reference removed DRep") pure (Map.lookup credential (vsDReps result))
      let EpochNo actual = drepExpiry normalized
          EpochNo remaining = vsNumDormantEpochs result
      putStrLn (unwords (map show [epoch, dormant, expiry, actual, remaining]))
