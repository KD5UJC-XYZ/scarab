-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE AllowAmbiguousTypes #-}
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE DeriveGeneric #-}
{-# LANGUAGE FlexibleContexts #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

import qualified Cardano.Ledger.Binary as Binary
import Cardano.Ledger.Alonzo (AlonzoEra)
import Cardano.Ledger.Babbage (BabbageEra)
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Core hiding (Value)
import Cardano.Ledger.BaseTypes (ProtVer (..))
import Data.Aeson (FromJSON, Value, eitherDecode, encode, object, (.=))
import qualified Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy as LBS
import qualified Data.Map.Strict as Map
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import GHC.Generics (Generic)
import System.Exit (die)

-- The observer changes no ledger or Plutus source. It exposes decoder admission and the
-- reference auxiliary-data validity predicate separately from transaction hash/signatures.
data Case = Case { name :: String, era :: String, protocolMajor :: Int, auxiliary :: Text.Text }
  deriving (Generic)
instance FromJSON Case

runFor :: forall era. EraTxAuxData era => StAnnTxCache era -> Case -> IO Value
runFor cache c = do
  bytes <- either die pure (Hex.decode (Text.encodeUtf8 (auxiliary c)))
  version <- Binary.mkVersion (protocolMajor c)
  let decoded = Binary.decodeFullAnnotator version "auxiliary data" Binary.decCBOR (LBS.fromStrict bytes)
        :: Either Binary.DecoderError (TxAuxData era)
  pure $ case decoded of
    Left _ -> object ["name" .= name c, "decoded" .= False]
    Right aux -> object ["name" .= name c, "decoded" .= True,
      "valid" .= validateTxAuxData @era cache (ProtVer version 0) aux]

runCase :: Case -> IO Value
runCase c = case era c of
  "Alonzo" -> runFor @AlonzoEra Map.empty c
  "Babbage" -> runFor @BabbageEra Map.empty c
  "Conway" -> runFor @ConwayEra Map.empty c
  _ -> die "unsupported era"

main :: IO ()
main = do
  input <- LBS.getContents
  cases <- either die pure (eitherDecode input :: Either String [Case])
  results <- traverse runCase cases
  LBS.putStr (encode results)
