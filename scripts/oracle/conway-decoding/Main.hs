-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE DeriveGeneric #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

import Cardano.Ledger.Babbage (BabbageEra)
import qualified Cardano.Ledger.Binary as Binary
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Core (Tx, TopTx, TxBody, TxWits)
import Data.Aeson (FromJSON, Value, eitherDecode, encode, object, (.=))
import qualified Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy as LBS
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import GHC.Generics (Generic)
import System.Environment (getArgs)
import System.Exit (die)

-- | One original encoding: `kind` is "body", "witnesses" or "transaction"; `era` is "conway"
-- (decoder version 9, `eraProtVerLow` of Conway) or "babbage" (version 7, bodies only).
data Case = Case
  { name :: String
  , era :: String
  , kind :: String
  , bytes :: Text.Text
  }
  deriving (Generic)

instance FromJSON Case

verdict :: Either Binary.DecoderError a -> Value
verdict (Right _) = object ["accepted" .= True]
verdict (Left failure) = object ["accepted" .= False, "failure" .= show failure]

runCase :: Case -> IO Value
runCase c = do
  raw <- either die (pure . LBS.fromStrict) (Hex.decode (Text.encodeUtf8 (bytes c)))
  result <- case (era c, kind c) of
    ("conway", "body") ->
      pure . verdict $
        (Binary.decodeFullAnnotator (Binary.natVersion @9) "Conway TxBody" Binary.decCBOR raw :: Either Binary.DecoderError (TxBody TopTx ConwayEra))
    ("conway", "witnesses") ->
      pure . verdict $
        (Binary.decodeFullAnnotator (Binary.natVersion @9) "Conway TxWits" Binary.decCBOR raw :: Either Binary.DecoderError (TxWits ConwayEra))
    ("conway", "transaction") ->
      pure . verdict $
        (Binary.decodeFullAnnotator (Binary.natVersion @9) "Conway Tx" Binary.decCBOR raw :: Either Binary.DecoderError (Tx TopTx ConwayEra))
    ("babbage", "body") ->
      pure . verdict $
        (Binary.decodeFullAnnotator (Binary.natVersion @7) "Babbage TxBody" Binary.decCBOR raw :: Either Binary.DecoderError (TxBody TopTx BabbageEra))
    other -> die ("unsupported case " <> show other)
  pure (object ["name" .= name c, "era" .= era c, "kind" .= kind c, "result" .= result])

main :: IO ()
main = do
  args <- getArgs
  cases <- case args of
    [p] -> (LBS.readFile p >>= either die pure . eitherDecode) :: IO [Case]
    _ -> die "one input JSON path required"
  traverse runCase cases >>= LBS.putStr . encode
