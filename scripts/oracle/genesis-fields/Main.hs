-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ScopedTypeVariables #-}
module Main (main) where

import Cardano.Ledger.Alonzo.Genesis (AlonzoGenesis)
import Cardano.Ledger.Conway.Genesis (ConwayGenesis)
import Cardano.Ledger.Shelley.Genesis (ShelleyGenesis (..))
import Data.Aeson (FromJSON (..), Value, eitherDecode, encode, object, withObject, (.:), (.=))
import Data.Aeson.Types (parseEither)
import qualified Data.ByteString.Lazy as LBS
import System.Environment (getArgs)
import System.Exit (die)

-- One measurement: a name, the genesis file it parses and the JSON of the file.
data Case = Case String String Value

instance FromJSON Case where
  parseJSON = withObject "case" $ \o -> Case <$> o .: "name" <*> o .: "kind" <*> o .: "input"

verdict :: Either String Value -> Value
verdict (Right v) = object ["accepted" .= True, "result" .= v]
verdict (Left err) = object ["accepted" .= False, "failure" .= err]

-- `FromJSON ShelleyGenesis` (Shelley/Genesis.hs:494-513) with its `LegacyJSONPParams`
-- (403-437). An acceptance keeps the protocol parameters and the update quorum the ledger read,
-- which hold the values of the optional fields.
shelley :: Value -> Either String Value
shelley v = do
  g :: ShelleyGenesis <- parseEither parseJSON v
  pure (object ["protocolParams" .= sgProtocolParams g, "updateQuorum" .= sgUpdateQuorum g])

-- `FromJSON AlonzoGenesis` (Alonzo/Genesis.hs:222-242).
alonzo :: Value -> Either String Value
alonzo v = do
  _ :: AlonzoGenesis <- parseEither parseJSON v
  pure (object [])

-- `FromJSON ConwayGenesis` (Conway/Genesis.hs:156-165) with `UpgradeConwayPParams`
-- (Conway/PParams.hs:1082-1095).
conway :: Value -> Either String Value
conway v = do
  _ :: ConwayGenesis <- parseEither parseJSON v
  pure (object [])

run :: Case -> Value
run (Case name kind input) =
  object ["name" .= name, "kind" .= kind, "reference" .= verdict (go kind input)]
  where
    go "shelleyGenesis" = shelley
    go "alonzoGenesis" = alonzo
    go "conwayGenesis" = conway
    go k = const (Left ("unknown kind " ++ k))

main :: IO ()
main = do
  args <- getArgs
  cases <- case args of
    [p] -> (LBS.readFile p >>= either die pure . eitherDecode) :: IO [Case]
    _ -> die "one input JSON path required"
  LBS.putStr (encode (map run cases))
