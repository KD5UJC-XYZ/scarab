-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE DeriveGeneric #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

import Cardano.Ledger.BaseTypes (Globals(..), Network(Testnet), boundRational, mkActiveSlotCoeff)
import Cardano.Ledger.BaseTypes.NonZero (nonZero)
import qualified Cardano.Ledger.Binary as Binary
import Cardano.Ledger.Coin (Coin(..))
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Credential (Credential)
import Cardano.Ledger.Keys (KeyRole(Staking))
import Cardano.Ledger.Shelley.LedgerState (NewEpochState(..), EpochState(..))
import Cardano.Ledger.Shelley.PoolRank (NonMyopic(..))
import Cardano.Ledger.Shelley.API.Wallet (getRewardInfoPools, getNonMyopicMemberRewards)
import Cardano.Slotting.EpochInfo (fixedEpochInfo)
import Cardano.Slotting.Slot (EpochSize(..))
import Cardano.Slotting.Time (SystemStart(..), mkSlotLength)
import Data.Aeson (FromJSON, Value, eitherDecode, encode, object, (.=))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy as LBS
import qualified Data.Set as Set
import qualified Data.Text as T
import qualified Data.Text.Encoding as T
import Data.Ratio ((%))
import Data.Time.Clock.POSIX (posixSecondsToUTCTime)
import Data.Word (Word64)
import GHC.Generics (Generic)
import System.Environment (getArgs)
import System.Exit (die)

data Request = Request { coin :: Maybe Integer, credential :: Maybe T.Text } deriving Generic
instance FromJSON Request

data Case = Case { name :: String, state :: T.Text, supply :: Word64, credentials :: [Request] } deriving Generic
instance FromJSON Case

raw :: T.Text -> IO BS.ByteString
raw = either die pure . Hex.decode . T.encodeUtf8

readRequest :: Request -> IO (Either Coin (Credential Staking))
readRequest (Request (Just amount) Nothing) = pure $ Left (Coin amount)
readRequest (Request Nothing (Just bytes)) = Right <$> (raw bytes >>= either (die . show) pure . Binary.decodeFull' (Binary.natVersion @9))
readRequest _ = die "exactly one request constructor is required"

run :: Case -> IO Value
run c = do
  nes <- raw (state c) >>= either (die . show) pure . Binary.decodeFull' (Binary.natVersion @9) :: IO (NewEpochState ConwayEra)
  asc <- maybe (die "invalid coefficient") (pure . mkActiveSlotCoeff) (boundRational (1 % 20))
  security <- maybe (die "invalid security") pure (nonZero 10)
  requested <- Set.fromList <$> traverse readRequest (credentials c)
  let globals = Globals (fixedEpochInfo (EpochSize 1000) (mkSlotLength 1)) 100 100 100 security 62 5 (supply c) asc Testnet (SystemStart (posixSecondsToUTCTime 0))
      wide = nes { nesEs = (nesEs nes) { esNonMyopic = (esNonMyopic (nesEs nes)) { rewardPotNM = Coin (2^(80 :: Int)) } } }
      write :: Binary.EncCBOR a => a -> T.Text
      write = T.decodeUtf8 . Hex.encode . Binary.serialize' (Binary.natVersion @9)
  pure $ object [ "name" .= name c
                , "rewardInfo" .= write (getRewardInfoPools globals nes)
                , "memberRewards" .= write (getNonMyopicMemberRewards globals nes requested)
                , "wideRewardInfo" .= write (getRewardInfoPools globals wide)
                , "wideMemberRewards" .= write (getNonMyopicMemberRewards globals wide requested)
                ]

main :: IO ()
main = do
  args <- getArgs
  cases <- case args of
    [path] -> (LBS.readFile path >>= either die pure . eitherDecode) :: IO [Case]
    _ -> die "one input JSON path required"
  traverse run cases >>= LBS.putStr . encode
