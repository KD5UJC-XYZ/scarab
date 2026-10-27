-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE DeriveGeneric #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

import Cardano.Crypto.Hash.Class (hashWith, castHash)
import Cardano.Ledger.BaseTypes (CertIx (..), TxIx (..), unNonZero)
import qualified Cardano.Ledger.Binary as Binary
import Cardano.Ledger.Coin (Coin (..), CompactForm (..), addCompactCoin, subtractCompactCoin)
import Cardano.Ledger.Compactible (toCompact, fromCompact)
import Cardano.Ledger.Credential (Credential (..), Ptr (..), SlotNo32 (..))
import Cardano.Ledger.Hashes (KeyHash (..))
import Cardano.Ledger.Keys (KeyRole (..))
import Cardano.Ledger.Shelley (ShelleyEra)
import Cardano.Ledger.Shelley.State (ShelleyAccounts (..), ShelleyAccountState (..), ShelleyInstantStake (..))
import Cardano.Ledger.State (ActiveStake (..), StakeWithDelegation (..), addToBalanceAccounts,
  resolveActiveInstantStakeCredentials, sumAllActiveStake, sumBalancesAccounts, sumDepositsAccounts)
import Data.Aeson (FromJSON (..), Value, eitherDecode, encode, object, withText, (.=))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy.Char8 as LBS
import qualified Data.Map.Strict as Map
import Data.Maybe.Strict (StrictMaybe (..))
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import qualified Data.VMap as VMap
import GHC.Generics (Generic)
import System.Exit (die)

newtype Decimal = Decimal Integer
instance FromJSON Decimal where
  parseJSON = withText "decimal integer" $ \t -> case reads (Text.unpack t) of
    [(n, "")] -> pure (Decimal n)
    _ -> fail "invalid decimal integer"

data Case = Case { name :: String, left :: Decimal, right :: Decimal }
  deriving Generic
instance FromJSON Case

credential :: Int -> Credential Staking
credential n = KeyHashObj (KeyHash (castHash (hashWith id (BS.replicate 28 (fromIntegral n)))))

pool :: KeyHash StakePool
pool = KeyHash (castHash (hashWith id (BS.replicate 28 9)))

amount :: CompactForm Coin -> String
amount = show . unCoin . fromCompact

nativeCompact :: CompactForm Coin -> CompactForm Coin -> Value
nativeCompact a b =
  let state n = ShelleyAccountState (Ptr (SlotNo32 0) (TxIx 0) (CertIx 0)) n n (SJust pool)
      accounts = ShelleyAccounts (Map.fromList [(credential 1, state a), (credential 2, state b)]) Map.empty :: ShelleyAccounts ShelleyEra
      credited = addToBalanceAccounts @ShelleyEra (Map.singleton (credential 1) b) accounts
      oneAccount = ShelleyAccounts (Map.singleton (credential 1) (state a)) Map.empty :: ShelleyAccounts ShelleyEra
      instant = ShelleyInstantStake (Map.singleton (credential 1) b) Map.empty :: ShelleyInstantStake ShelleyEra
      resolved = resolveActiveInstantStakeCredentials @ShelleyEra instant oneAccount
      active = ActiveStake (VMap.fromMap resolved)
   in object
      [ "sum" .= amount (addCompactCoin a b)
      , "difference" .= amount (subtractCompactCoin b a)
      , "accountBalances" .= show (unCoin (sumBalancesAccounts @ShelleyEra accounts))
      , "accountDeposits" .= show (unCoin (sumDepositsAccounts @ShelleyEra accounts))
      , "credited" .= fmap (amount . sasBalance) (Map.lookup (credential 1) (saStates credited))
      , "activeStake" .= fmap (amount . unNonZero . swdStake) (Map.lookup (credential 1) resolved)
      , "activeTotal" .= show (unCoin (unNonZero (sumAllActiveStake active)))
      ]

runCase :: Case -> Value
runCase (Case label (Decimal a) (Decimal b)) =
  let ca = Coin a
      cb = Coin b
      compactA = toCompact ca :: Maybe (CompactForm Coin)
      compactB = toCompact cb :: Maybe (CompactForm Coin)
   in object
      [ "name" .= label, "left" .= show a, "right" .= show b
      , "coinSum" .= show (unCoin (ca <> cb))
      , "coinBytes" .= Text.decodeUtf8 (Hex.encode (Binary.serialize' (Binary.natVersion @11) ca))
      , "compactLeft" .= fmap amount compactA
      , "compactRight" .= fmap amount compactB
      , "native" .= (nativeCompact <$> compactA <*> compactB)
      ]

main :: IO ()
main = LBS.getContents >>= mapM_ (either die (LBS.putStrLn . encode . runCase) . eitherDecode) . LBS.lines
