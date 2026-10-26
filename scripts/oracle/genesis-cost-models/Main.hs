-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where
import Cardano.Ledger.Alonzo.Genesis (AlonzoGenesis (unAlonzoGenesisWrapper))
import Cardano.Ledger.Alonzo (AlonzoEra)
import Cardano.Ledger.Alonzo.Core (ppCostModelsL)
import Cardano.Ledger.Alonzo.PParams ()
import Cardano.Ledger.Babbage (BabbageEra)
import Cardano.Ledger.Babbage.PParams ()
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Conway.Genesis (ConwayGenesis (cgUpgradePParams))
import Cardano.Ledger.Conway.PParams ()
import Cardano.Ledger.Mary (MaryEra)
import Cardano.Ledger.Core (PParams, emptyPParams, upgradePParams)
import qualified Cardano.Ledger.Binary as Binary
import Data.Aeson (eitherDecode, encode, object, (.=))
import qualified Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy.Char8 as LBS
import qualified Data.Text.Encoding as Text
import Lens.Micro ((^.))
import System.Exit (die)
main :: IO ()
main = do
 ag <- LBS.readFile "/genesis/alonzo-genesis.json" >>= either die pure . eitherDecode @AlonzoGenesis
 cg <- LBS.readFile "/genesis/conway-genesis.json" >>= either die pure . eitherDecode @ConwayGenesis
 let alonzo = upgradePParams @AlonzoEra (unAlonzoGenesisWrapper ag) (emptyPParams @MaryEra)
     babbage = upgradePParams @BabbageEra () alonzo
     conway = upgradePParams @ConwayEra (cgUpgradePParams cg) babbage :: PParams ConwayEra
     models = conway ^. ppCostModelsL
 LBS.putStrLn $ encode $ object ["alonzo" .= (alonzo ^. ppCostModelsL), "babbage" .= (babbage ^. ppCostModelsL), "conway" .= models,
   "conwayCostModelsCbor" .= Text.decodeUtf8 (Hex.encode (Binary.serialize' (Binary.natVersion @9) models))]
