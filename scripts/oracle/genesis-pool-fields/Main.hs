-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE DeriveGeneric #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

import qualified Cardano.Ledger.Binary as Binary
import Cardano.Ledger.BaseTypes (unboundRational)
import Cardano.Ledger.State (StakePoolParams (..))
import Data.Aeson (FromJSON, Result (..), Value, eitherDecode, encode, fromJSON, object, (.=))
import qualified Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy.Char8 as LBS
import Data.Foldable (toList)
import Data.Ratio (denominator, numerator)
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import qualified Data.Text.Lazy as LText
import qualified Data.Text.Lazy.Encoding as LText
import GHC.Generics (Generic)
import System.Exit (die)

-- The pool is JSON text, decoded here exactly as a genesis file is, so that numeric spellings
-- reach the reference reader unchanged.
data Case = Case { name :: String, poolJson :: Text.Text }
  deriving (Generic)
instance FromJSON Case

runCase :: Case -> Value
runCase c = case either Error fromJSON parsed :: Result StakePoolParams of
  Error failure -> object ["name" .= name c, "accepted" .= False, "failure" .= failure]
  Success p -> object
    [ "name" .= name c, "accepted" .= True
    , "poolId" .= sppId p, "vrf" .= sppVrf p
    , "pledge" .= sppPledge p, "cost" .= sppCost p
    , "marginNumerator" .= numerator (unboundRational (sppMargin p))
    , "marginDenominator" .= denominator (unboundRational (sppMargin p))
    , "accountAddress" .= sppAccountAddress p, "owners" .= sppOwners p
    , "relays" .= Text.decodeUtf8 (Hex.encode (Binary.serialize' (Binary.natVersion @11) (sppRelays p)))
    , "metadata" .= [Text.decodeUtf8 (Hex.encode (Binary.serialize' (Binary.natVersion @11) m)) | m <- toList (sppMetadata p)]
    ]
  where
    parsed = eitherDecode (LText.encodeUtf8 (LText.fromStrict (poolJson c))) :: Either String Value

main :: IO ()
main = LBS.getContents >>= mapM_ (either die (LBS.putStrLn . encode . runCase) . eitherDecode) . LBS.lines
