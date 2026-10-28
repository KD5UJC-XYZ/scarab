-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ScopedTypeVariables #-}
module Main (main) where

import Cardano.Ledger.Alonzo.Genesis (AlonzoExtraConfig (..), AlonzoGenesis (..))
import Cardano.Ledger.BaseTypes (StrictMaybe (..))
import Cardano.Ledger.Conway.Genesis (ConwayGenesis (..))
import Cardano.Ledger.Conway.PParams (UpgradeConwayPParams (..))
import Cardano.Ledger.Plutus.CostModels
  ( CostModels
  , costModelsUnknown
  , costModelsValid
  , getCostModelParams
  , mkCostModel
  , parseCostModels
  )
import Cardano.Ledger.Plutus.Language (Language (..))
import Data.Aeson (FromJSON (..), Value, eitherDecode, encode, object, withObject, (.:), (.=))
import Data.Aeson.Types (Parser, parseEither)
import qualified Data.ByteString.Lazy as LBS
import Data.Int (Int64)
import qualified Data.Map.Strict as Map
import System.Environment (getArgs)
import System.Exit (die)

-- One measurement: a name, the rule it measures and the JSON it is given.
data Case = Case String String Value

instance FromJSON Case where
  parseJSON = withObject "case" $ \o -> Case <$> o .: "name" <*> o .: "kind" <*> o .: "input"

-- The arguments of `parseCostModels`.
data Parse = Parse Bool [Language] Value

instance FromJSON Parse where
  parseJSON = withObject "parseCostModels" $ \o ->
    Parse <$> o .: "lenient" <*> (o .: "languages" >>= traverse language) <*> o .: "value"

-- The arguments of `mkCostModel`.
data Make = Make Language [Int64]

instance FromJSON Make where
  parseJSON = withObject "mkCostModel" $ \o ->
    Make <$> (o .: "language" >>= language) <*> o .: "values"

language :: String -> Parser Language
language "PlutusV1" = pure PlutusV1
language "PlutusV2" = pure PlutusV2
language "PlutusV3" = pure PlutusV3
language "PlutusV4" = pure PlutusV4
language other = fail ("unknown language " ++ other)

-- The cost models as the ledger kept them: the languages it knows, with the values they were built
-- from, and the unknown ones.
models :: CostModels -> Value
models cm =
  object
    [ "known" .= [object ["language" .= show l, "values" .= getCostModelParams c] | (l, c) <- Map.toList (costModelsValid cm)]
    , "unknown" .= [object ["language" .= (fromIntegral l :: Int), "values" .= vs] | (l, vs) <- Map.toList (costModelsUnknown cm)]
    ]

verdict :: Either String Value -> Value
verdict (Right v) = object ["accepted" .= True, "result" .= v]
verdict (Left err) = object ["accepted" .= False, "failure" .= err]

-- `FromJSON AlonzoGenesis` (Alonzo/Genesis.hs:222-242): `costModels` through
-- `parseCostModels False [PlutusV1]`, `extraConfig` through `parseCostModels True []`.
alonzo :: Value -> Either String Value
alonzo v = do
  g :: AlonzoGenesis <- parseEither parseJSON v
  let extra = case agExtraConfig g of
        SNothing -> Nothing
        SJust c -> fmap models (aecCostModels c)
  pure (object ["plutusV1" .= getCostModelParams (agPlutusV1CostModel g), "extra" .= extra])

-- `FromJSON ConwayGenesis`, whose parameters read `plutusV3CostModel` through
-- `parseCostModelAsArray False PlutusV3` (Conway/PParams.hs:1095).
conway :: Value -> Either String Value
conway v = do
  g :: ConwayGenesis <- parseEither parseJSON v
  pure (object ["plutusV3" .= getCostModelParams (ucppPlutusV3CostModel (cgUpgradePParams g))])

-- `parseCostModels` itself, for the rules the genesis files do not exercise.
parsed :: Value -> Either String Value
parsed v = do
  Parse lenient languages value <- parseEither parseJSON v
  models <$> parseEither (parseCostModels lenient languages) value

-- `mkCostModel`: Plutus `mkEvaluationContext` over the values (CostModels.hs:278-291).
costModel :: Value -> Either String Value
costModel v = do
  Make l values <- parseEither parseJSON v
  pure $ case mkCostModel l values of
    Left err -> object ["valid" .= False, "error" .= show err]
    Right _ -> object ["valid" .= True]

run :: Case -> Value
run (Case name kind input) =
  object ["name" .= name, "kind" .= kind, "reference" .= verdict (go kind input)]
  where
    go "alonzoGenesis" = alonzo
    go "conwayGenesis" = conway
    go "parseCostModels" = parsed
    go "mkCostModel" = costModel
    go k = const (Left ("unknown kind " ++ k))

main :: IO ()
main = do
  args <- getArgs
  cases <- case args of
    [p] -> (LBS.readFile p >>= either die pure . eitherDecode) :: IO [Case]
    _ -> die "one input JSON path required"
  LBS.putStr (encode (map run cases))
