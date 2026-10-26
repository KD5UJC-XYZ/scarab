-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE DeriveGeneric #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where
import Cardano.Ledger.Address (Addr, serialiseAddr)
import qualified Cardano.Ledger.Binary as Binary
import Cardano.Ledger.Coin (Coin (..), CompactForm)
import Cardano.Ledger.Compactible (toCompact, fromCompact)
import Cardano.Ledger.Shelley.Genesis (initialFundsPseudoTxIn)
import Data.Aeson (FromJSON, Value (..), Result (..), eitherDecode, encode, fromJSON, object, (.=))
import qualified Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy.Char8 as LBS
import Data.Text (Text)
import qualified Data.Text.Encoding as Text
import GHC.Generics (Generic)
import System.Exit (die)
data Case = Case {name :: String, address :: Text, amount :: Value} deriving Generic
instance FromJSON Case
resultMaybe :: Result a -> Maybe a
resultMaybe (Success value) = Just value
resultMaybe (Error _) = Nothing
runCase :: Case -> Value
runCase (Case label raw value) =
 let addr = resultMaybe (fromJSON (String raw) :: Result Addr)
     coin = resultMaybe (fromJSON value :: Result Coin)
     compact = coin >>= (toCompact :: Coin -> Maybe (CompactForm Coin))
 in object ["name" .= label, "address" .= raw, "amount" .= value,
            "canonicalAddress" .= fmap (Text.decodeUtf8 . Hex.encode . serialiseAddr) addr,
            "pseudoInput" .= fmap (Text.decodeUtf8 . Hex.encode . Binary.serialize' (Binary.natVersion @11) . initialFundsPseudoTxIn) addr,
            "coin" .= fmap (show . unCoin) coin,
            "compact" .= fmap (show . unCoin . fromCompact) compact]
main :: IO ()
main = LBS.getContents >>= mapM_ (either die (LBS.putStrLn . encode . runCase) . eitherDecode) . LBS.lines
