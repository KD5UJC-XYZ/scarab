-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE AllowAmbiguousTypes #-}
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE DeriveGeneric #-}
{-# LANGUAGE FlexibleContexts #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

import Cardano.Ledger.Alonzo (AlonzoEra)
import Cardano.Ledger.Alonzo.Core (AlonzoEraPParams, ppuCostModelsL)
import Cardano.Ledger.Babbage (BabbageEra)
import qualified Cardano.Ledger.Binary as Binary
import Cardano.Ledger.Core hiding (Value)
import Cardano.Ledger.Plutus.CostModels (CostModels, costModelsUnknown, costModelsValid, getCostModelParams)
import Cardano.Ledger.Shelley.Core (ShelleyEraTxBody, updateTxBodyL)
import Cardano.Ledger.Shelley.PParams (ProposedPPUpdates (..), Update (..))
import Cardano.Ledger.BaseTypes (StrictMaybe (..))
import Data.Aeson (FromJSON, Value, eitherDecode, encode, object, (.=))
import qualified Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy as LBS
import qualified Data.Map.Strict as Map
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import GHC.Generics (Generic)
import Lens.Micro ((^.))
import System.Environment (getArgs)
import System.Exit (die)

data Case = Case { name :: String, era :: String, tx :: Text.Text } deriving (Generic)
instance FromJSON Case

-- The cost models of every proposal the transaction body carries, as the ledger kept them after
-- decoding: the languages it knows with the values they were built from, and the unknown ones.
surviving :: forall era. (EraTx era, ShelleyEraTxBody era, AlonzoEraPParams era) => Tx TopTx era -> [Value]
surviving t = case t ^. bodyTxL . updateTxBodyL of
  SNothing -> []
  SJust (Update (ProposedPPUpdates m) _) -> [proposal k u | (k, u) <- Map.toList m]
  where
    proposal k u =
      object
        [ "genesisKey" .= show k
        , "costModels" .= fmap models (strict (u ^. ppuCostModelsL))
        ]
    strict (SJust x) = Just x
    strict SNothing = Nothing
    models :: CostModels -> Value
    models cm =
      object
        [ "known" .= [object ["language" .= show l, "count" .= length (getCostModelParams c), "values" .= getCostModelParams c] | (l, c) <- Map.toList (costModelsValid cm)]
        , "unknown" .= [object ["language" .= (fromIntegral l :: Int), "values" .= vs] | (l, vs) <- Map.toList (costModelsUnknown cm)]
        ]

decodeTx :: forall era. (EraTx era, ShelleyEraTxBody era, AlonzoEraPParams era) => Binary.Version -> String -> LBS.ByteString -> Value
decodeTx version label bytes =
  case Binary.decodeFullAnnotator version (Text.pack label) Binary.decCBOR bytes :: Either Binary.DecoderError (Tx TopTx era) of
    Right t -> object ["accepted" .= True, "proposals" .= surviving t]
    Left err -> object ["accepted" .= False, "failure" .= show err]

runCase :: Case -> IO Value
runCase c = do
  bytes <- either die (pure . LBS.fromStrict) (Hex.decode (Text.encodeUtf8 (tx c)))
  verdict <- case era c of
    "alonzo" -> pure (decodeTx @AlonzoEra (Binary.natVersion @5) "Alonzo Tx" bytes)
    "babbage" -> pure (decodeTx @BabbageEra (Binary.natVersion @7) "Babbage Tx" bytes)
    _ -> die "unsupported era"
  pure (object ["name" .= name c, "era" .= era c, "tx" .= tx c, "reference" .= verdict])

main :: IO ()
main = do
  args <- getArgs
  cases <- case args of
    [p] -> (LBS.readFile p >>= either die pure . eitherDecode) :: IO [Case]
    _ -> die "one input JSON path required"
  traverse runCase cases >>= LBS.putStr . encode
