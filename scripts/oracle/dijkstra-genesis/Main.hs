-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ScopedTypeVariables #-}
module Main (main) where

import Cardano.Ledger.Dijkstra.Genesis (DijkstraGenesis)
import Data.Aeson (FromJSON (..), Value, eitherDecode, encode, object, toJSON, withObject, (.:), (.=))
import Data.Aeson.Types (parseEither)
import qualified Data.ByteString.Lazy as LBS
import System.Environment (getArgs)
import System.Exit (die)

-- One measurement: a name and the JSON of the file.
data Case = Case String Value

instance FromJSON Case where
  parseJSON = withObject "case" $ \o -> Case <$> o .: "name" <*> o .: "input"

-- `FromJSON DijkstraGenesis` (cardano-ledger 9df9f98c `Dijkstra/Genesis.hs:32-35`, a newtype of
-- `UpgradeDijkstraPParams`, `Dijkstra/PParams.hs:442-468`). An acceptance keeps the genesis as the
-- ledger writes it back (`ToKeyValuePairs`, `PParams.hs:470-496`), which holds the values read.
verdict :: Value -> Value
verdict v = case parseEither parseJSON v of
  Right (g :: DijkstraGenesis) -> object ["accepted" .= True, "result" .= toJSON g]
  Left err -> object ["accepted" .= False, "failure" .= err]

run :: Case -> Value
run (Case name input) = object ["name" .= name, "reference" .= verdict input]

main :: IO ()
main = do
  args <- getArgs
  cases <- case args of
    [p] -> (LBS.readFile p >>= either die pure . eitherDecode) :: IO [Case]
    _ -> die "one input JSON path required"
  LBS.putStr (encode (map run cases))
