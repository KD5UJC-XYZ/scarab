-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE DeriveGeneric #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

import qualified Cardano.Ledger.Binary as Binary
import Cardano.Ledger.State (StakePoolParams (..))
import Data.Aeson (FromJSON, Result (..), Value, eitherDecode, encode, fromJSON, object, (.=))
import qualified Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy.Char8 as LBS
import qualified Data.Text.Encoding as Text
import Data.Foldable (toList)
import GHC.Generics (Generic)
import System.Exit (die)

data Case = Case { name :: String, pool :: Value }
  deriving (Generic)
instance FromJSON Case

runCase :: Case -> Value
runCase c = case fromJSON (pool c) :: Result StakePoolParams of
  Error failure -> object ["name" .= name c, "pool" .= pool c, "accepted" .= False, "failure" .= failure]
  Success p -> object
    [ "name" .= name c, "pool" .= pool c, "accepted" .= True
    , "relays" .= Text.decodeUtf8 (Hex.encode (Binary.serialize' (Binary.natVersion @11) (sppRelays p)))
    , "metadata" .= [Text.decodeUtf8 (Hex.encode (Binary.serialize' (Binary.natVersion @11) m)) | m <- toList (sppMetadata p)]
    ]

main :: IO ()
main = LBS.getContents >>= mapM_ (either die (LBS.putStrLn . encode . runCase) . eitherDecode) . LBS.lines
