-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE DeriveGeneric #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

import Cardano.Ledger.BaseTypes (Globals(..), Network(Testnet), ProtVer(..), BoundedRational(boundRational), mkActiveSlotCoeff)
import Cardano.Ledger.BaseTypes.NonZero (nonZero)
import qualified Cardano.Ledger.Binary as Binary
import Cardano.Ledger.Core (PParams, ppProtocolVersionL, ppMinPoolCostL, emptyPParams)
import Cardano.Ledger.Coin (Coin(..))
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Shelley.TxCert (PoolCert(..))
import Cardano.Ledger.Shelley.Rules (POOL, PoolEnv(..))
import Cardano.Ledger.State (PState(..), StakePoolParams)
import Cardano.Slotting.EpochInfo (fixedEpochInfo)
import Cardano.Slotting.Slot (EpochSize(..), EpochNo(..))
import Cardano.Slotting.Time (SystemStart(..), mkSlotLength)
import Control.State.Transition.Extended (TRC(..), applySTS)
import Control.Monad.Reader (runReader)
import Data.Aeson (FromJSON, Value, eitherDecode, encode, object, (.=))
import qualified Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy as LBS
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import qualified Data.Map.Strict as Map
import Data.Foldable (toList)
import Data.Time.Clock.POSIX (posixSecondsToUTCTime)
import Data.Word (Word32)
import GHC.Generics (Generic)
import Lens.Micro ((&), (.~))
import System.Environment (getArgs)
import System.Exit (die)

data Case = Case { name :: String, major :: Int, minor :: Word32, params :: Text.Text, previous :: Maybe Text.Text, minCost :: Integer } deriving Generic
instance FromJSON Case

readParams :: Text.Text -> IO StakePoolParams
readParams raw = do
  bytes <- either die pure (Hex.decode (Text.encodeUtf8 raw))
  either (die . show) pure (Binary.decodeFull' (Binary.natVersion @9) bytes)

runCase :: Case -> IO Value
runCase c = do
  version <- Binary.mkVersion (major c)
  k <- maybe (die "security must be nonzero") pure (nonZero 1)
  asc <- case boundRational (1 / 2) of
    Nothing -> die "active-slot coefficient"
    Just x -> pure (mkActiveSlotCoeff x)
  p <- readParams (params c)
  old <- traverse readParams (previous c)
  let globals = Globals (fixedEpochInfo (EpochSize 100) (mkSlotLength 1)) 100 100 100 k 62 5 45000000000000000 asc Testnet (SystemStart (posixSecondsToUTCTime 0))
      pp = emptyPParams & ppProtocolVersionL .~ ProtVer version (minor c) & ppMinPoolCostL .~ Coin (minCost c) :: PParams ConwayEra
      empty = PState Map.empty Map.empty Map.empty Map.empty :: PState ConwayEra
      apply s cert = runReader (applySTS @(POOL ConwayEra) (TRC (PoolEnv (EpochNo 5) pp, s, RegPool cert))) globals
      write x = Text.decodeUtf8 (Hex.encode (Binary.serialize' (Binary.natVersion @9) x))
  initial <- case old of
    Nothing -> pure empty
    Just previousParams -> either (die . show) pure (apply empty previousParams)
  let outcome = case apply initial p of
        Left failures -> object ["accepted" .= False, "failures" .= show failures, "failureCbor" .= map write (toList failures)]
        Right s -> object ["accepted" .= True, "stateCbor" .= write s]
  pure (object ["name" .= name c, "major" .= major c, "minor" .= minor c, "params" .= params c, "previous" .= previous c, "minCost" .= minCost c, "result" .= outcome])

main :: IO ()
main = do
  args <- getArgs
  cases <- case args of
    [p] -> (LBS.readFile p >>= either die pure . eitherDecode) :: IO [Case]
    _ -> die "one input JSON path required"
  traverse runCase cases >>= LBS.putStr . encode
