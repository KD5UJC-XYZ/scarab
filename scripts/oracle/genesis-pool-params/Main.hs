-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE DeriveGeneric #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

import qualified Cardano.Ledger.Binary as Binary
import Cardano.Ledger.BaseTypes (unboundRational)
import Data.Ratio (numerator, denominator)
import Cardano.Ledger.State (StakePoolParams (..))
import Data.Aeson (FromJSON, Result (..), Value (..), eitherDecode, encode, fromJSON, object, (.=))
import qualified Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy.Char8 as LBS
import qualified Data.Text.Encoding as Text
import Data.Foldable (toList)
import GHC.Generics (Generic)
import System.Exit (die)

data Case = Case { name :: String, poolJson :: String }
  deriving (Generic)
instance FromJSON Case

runCase :: Case -> Value
runCase c = case decoded :: Result StakePoolParams of
  Error failure -> object ["name" .= name c, "pool" .= input, "poolJson" .= poolJson c, "accepted" .= False, "failure" .= failure]
  Success p -> object
    [ "name" .= name c, "pool" .= input, "poolJson" .= poolJson c, "accepted" .= True
    , "poolId" .= sppId p, "vrf" .= sppVrf p
    , "pledge" .= sppPledge p, "cost" .= sppCost p
    , "marginNumerator" .= numerator (unboundRational (sppMargin p))
    , "marginDenominator" .= denominator (unboundRational (sppMargin p))
    , "accountAddress" .= sppAccountAddress p, "owners" .= sppOwners p
    , "relays" .= Text.decodeUtf8 (Hex.encode (Binary.serialize' (Binary.natVersion @11) (sppRelays p)))
    , "metadata" .= [Text.decodeUtf8 (Hex.encode (Binary.serialize' (Binary.natVersion @11) m)) | m <- toList (sppMetadata p)]
    ]

  where
    parsed = eitherDecode (LBS.pack (poolJson c)) :: Either String Value
    input = either (const Null) id parsed
    decoded = either Error fromJSON parsed

main :: IO ()
main = LBS.getContents >>= mapM_ (either die (LBS.putStrLn . encode . runCase) . eitherDecode) . LBS.lines
