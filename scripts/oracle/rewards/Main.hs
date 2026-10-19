-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE AllowAmbiguousTypes #-}
{-# LANGUAGE ConstraintKinds #-}
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE DeriveGeneric #-}
{-# LANGUAGE FlexibleContexts #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

import Cardano.Ledger.BaseTypes (Globals(..), Network(Testnet), BoundedRational(boundRational), mkActiveSlotCoeff)
import Cardano.Ledger.BaseTypes.NonZero (nonZero)
import qualified Cardano.Ledger.Binary as Binary
import Cardano.Ledger.Coin (Coin(..))
import Cardano.Ledger.Shelley.Governance (EraGov)
import Cardano.Ledger.Core (EraTxOut)
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Shelley (ShelleyEra)
import Cardano.Ledger.Babbage (BabbageEra)
import Cardano.Ledger.Shelley.LedgerState (EpochState, startStep, pulseStep, completeStep)
import Cardano.Ledger.State (EraCertState, EraStake)
import Cardano.Slotting.EpochInfo (fixedEpochInfo)
import Cardano.Slotting.Slot (EpochSize(..))
import Cardano.Slotting.Time (SystemStart(..), mkSlotLength)
import Control.Monad (foldM)
import Control.Monad.Reader (runReader)
import Data.Aeson (FromJSON(..), ToJSON(..), Value, eitherDecode, encode, object, withText, (.=))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy as LBS
import qualified Data.Text as T
import qualified Data.Text.Encoding as T
import Data.Ratio ((%))
import Data.Time.Clock.POSIX (posixSecondsToUTCTime)
import Data.Word (Word64)
import GHC.Generics (Generic)
import System.Environment (getArgs)
import System.Exit (die)

data Operation = Pulse | Complete deriving (Show)
instance FromJSON Operation where
  parseJSON = withText "reward operation" $ \text -> case text of
    "pulse" -> pure Pulse
    "complete" -> pure Complete
    _ -> fail "expected pulse or complete"
instance ToJSON Operation where
  toJSON Pulse = toJSON ("pulse" :: T.Text)
  toJSON Complete = toJSON ("complete" :: T.Text)

data EraProfile = ShelleyProfile | BabbageProfile | ConwayProfile
instance FromJSON EraProfile where
  parseJSON = withText "reward era" $ \text -> case text of
    "Shelley" -> pure ShelleyProfile
    "Babbage" -> pure BabbageProfile
    "Conway" -> pure ConwayProfile
    _ -> fail "expected Shelley, Babbage or Conway"

data Case = Case
  { name :: String, era :: EraProfile, epochState :: Maybe T.Text, blocksMade :: T.Text
  , pending :: Maybe T.Text, slots :: Word64, supply :: Word64, fNum :: Integer, fDen :: Integer
  , security :: Word64, operations :: [Operation]
  } deriving (Generic)
instance FromJSON Case

raw :: T.Text -> IO BS.ByteString
raw = either die pure . Hex.decode . T.encodeUtf8

hex :: BS.ByteString -> T.Text
hex = T.decodeUtf8 . Hex.encode

runEra :: forall e. (EraGov e, EraCertState e, EraTxOut e, EraStake e) => Case -> IO Value
runEra c = do
  asc <- maybe (die "active-slot coefficient outside (0,1]") (pure . mkActiveSlotCoeff) (boundRational (fNum c % fDen c))
  k <- maybe (die "security parameter must be nonzero") pure (nonZero (security c))
  b <- raw (blocksMade c) >>= either (die . show) pure . Binary.decodeFull' (Binary.natVersion @9)
  let globals = Globals (fixedEpochInfo (EpochSize (slots c)) (mkSlotLength 1)) 100 100 100 k 62 5 (supply c) asc Testnet (SystemStart (posixSecondsToUTCTime 0))
      write p = hex (Binary.serialize' (Binary.natVersion @9) p)
  initial <- case (epochState c, pending c) of
    (Just esHex, Nothing) -> do
      es <- raw esHex >>= either (die . show) pure . Binary.decodeFull' (Binary.natVersion @9) :: IO (EpochState e)
      pure (startStep (EpochSize (slots c)) b es (Coin (fromIntegral (supply c))) asc k)
    (Nothing, Just pHex) -> raw pHex >>= either (die . show) pure . Binary.decodeFull' (Binary.natVersion @9)
    _ -> die "supply exactly one of epochState or pending"
  let step (p, out) operation = case operation of
        Pulse -> let (next, event) = runReader (pulseStep p) globals in pure (next, out ++ [object ["operation" .= operation, "state" .= write next, "event" .= write event]])
        Complete -> let (next, event) = runReader (completeStep p) globals in pure (next, out ++ [object ["operation" .= operation, "state" .= write next, "event" .= write event]])
  (_, rows) <- foldM step (initial, []) (operations c)
  pure (object ["name" .= name c, "initial" .= write initial, "transitions" .= rows])

runCase :: Case -> IO Value
runCase c = case era c of
  ShelleyProfile -> runEra @ShelleyEra c
  BabbageProfile -> runEra @BabbageEra c
  ConwayProfile -> runEra @ConwayEra c

main :: IO ()
main = do
  args <- getArgs
  cases <- case args of
    [path] -> (LBS.readFile path >>= either die pure . eitherDecode) :: IO [Case]
    _ -> die "one input JSON path required"
  traverse runCase cases >>= LBS.putStr . encode
