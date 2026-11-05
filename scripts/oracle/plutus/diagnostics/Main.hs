-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE AllowAmbiguousTypes #-}
{-# LANGUAGE DeriveGeneric #-}
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

import Cardano.Ledger.Plutus.Language (Language(..), LegacyPlutusArgs(..))
import Codec.Serialise (deserialiseOrFail)
import Data.Aeson (FromJSON, Value, eitherDecode, encode, object, (.=))
import qualified Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy as LBS
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import GHC.Generics (Generic)
import qualified PlutusLedgerApi.V1 as V1
import qualified PlutusLedgerApi.V2 as V2
import qualified PlutusLedgerApi.V3 as V3
import qualified PlutusTx
import Prettyprinter (Pretty, pretty)
import System.Exit (die)

data Input = Input { name :: String, kind :: String, dataCbor :: Text.Text }
  deriving (Generic)
instance FromJSON Input

render :: forall a. (PlutusTx.FromData a, Pretty a) => V1.Data -> IO String
render d = maybe (die "context data does not inhabit its declared type") (pure . show . pretty) (PlutusTx.fromData @a d)

run :: Input -> IO Value
run i = do
  bytes <- either die pure (Hex.decode (Text.encodeUtf8 (dataCbor i)))
  d <- either (die . show) pure (deserialiseOrFail (LBS.fromStrict bytes))
  result <- case kind i of
    "ContextV1" -> render @V1.ScriptContext d
    "ContextV2" -> render @V2.ScriptContext d
    "ContextV3" -> render @V3.ScriptContext d
    "ArgumentsV1" -> case d of
      V1.List [r, ctx] -> maybe (die "invalid V1 context") (pure . show . pretty) ((LegacyPlutusArgs2 r <$> PlutusTx.fromData ctx) :: Maybe (LegacyPlutusArgs 'PlutusV1))
      V1.List [dt, r, ctx] -> maybe (die "invalid V1 context") (pure . show . pretty) ((LegacyPlutusArgs3 dt r <$> PlutusTx.fromData ctx) :: Maybe (LegacyPlutusArgs 'PlutusV1))
      _ -> die "invalid V1 arguments"
    "ArgumentsV2" -> case d of
      V1.List [r, ctx] -> maybe (die "invalid V2 context") (pure . show . pretty) ((LegacyPlutusArgs2 r <$> PlutusTx.fromData ctx) :: Maybe (LegacyPlutusArgs 'PlutusV2))
      V1.List [dt, r, ctx] -> maybe (die "invalid V2 context") (pure . show . pretty) ((LegacyPlutusArgs3 dt r <$> PlutusTx.fromData ctx) :: Maybe (LegacyPlutusArgs 'PlutusV2))
      _ -> die "invalid V2 arguments"
    "RawData" -> pure (show (pretty (d :: V1.Data)))
    _ -> die "unknown diagnostic type"
  pure (object ["name" .= name i, "kind" .= kind i, "dataCbor" .= dataCbor i, "text" .= result])

main :: IO ()
main = do
  inputs <- either die pure . (eitherDecode :: LBS.ByteString -> Either String [Input]) =<< LBS.getContents
  outputs <- traverse run inputs
  LBS.putStr (encode outputs)
