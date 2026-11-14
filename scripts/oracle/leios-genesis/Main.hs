-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE PackageImports #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

import Cardano.Ledger.Allegra (AllegraEra)
import Cardano.Ledger.Alonzo.Genesis (AlonzoGenesis)
import Cardano.Ledger.Babbage (BabbageEra)
import Cardano.Ledger.Binary (serialize')
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Conway.Genesis (ConwayGenesis)
import Cardano.Ledger.Core (eraProtVerLow)
import Cardano.Ledger.Genesis (NoGenesis (..))
import Cardano.Ledger.Mary (MaryEra)
import Cardano.Ledger.Shelley.Genesis (ShelleyGenesis)
import Cardano.Ledger.Shelley.LedgerState (NewEpochState, esLStateL, esSnapshotsL, lsCertStateL, nesEsL)
import Cardano.Ledger.Shelley.Transition (
  EraTransition (..),
  createInitialState,
  mkShelleyTransitionConfig,
 )
import Cardano.Ledger.State (UTxO (..), certPStateL, psStakePoolsL, utxoL)
import Control.Exception (SomeException, displayException, evaluate, try)
import Data.Aeson (FromJSON (..), Value, eitherDecode, eitherDecodeStrict, encode, object, withObject, (.:), (.=))
import qualified Data.ByteString as BS
import qualified "base16-bytestring" Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy.Char8 as LBS
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import Lens.Micro ((&), (.~), (^.))
import System.Exit (die)
import System.FS.API.Types (MountPoint (..))
import System.FS.IO (ioHasFS)

-- A case is the three genesis files a chain that starts in Conway is configured with, as text.
data Case = Case Text.Text Text.Text Text.Text Text.Text

instance FromJSON Case where
  parseJSON = withObject "case" $ \o ->
    Case <$> o .: "name" <*> o .: "shelley" <*> o .: "alonzo" <*> o .: "conway"

hex :: BS.ByteString -> Text.Text
hex = Text.decodeUtf8 . Hex.encode

-- The transition configuration of Conway as the node composes it from the genesis files
-- (cardano-ledger-api's mkLatestTransitionConfig, one era short of Dijkstra).
conway :: ShelleyGenesis -> AlonzoGenesis -> ConwayGenesis -> TransitionConfig ConwayEra
conway sg ag cg =
  mkTransitionConfig cg $
    mkTransitionConfig (NoGenesis :: NoGenesis BabbageEra) $
      mkTransitionConfig ag $
        mkTransitionConfig (NoGenesis :: NoGenesis MaryEra) $
          mkTransitionConfig (NoGenesis :: NoGenesis AllegraEra) $
            mkShelleyTransitionConfig sg

encoded :: NewEpochState ConwayEra -> BS.ByteString
encoded = serialize' (eraProtVerLow @ConwayEra)

refused :: String -> Value
refused failure = object ["accepted" .= False, "failure" .= failure]

-- The genesis files read with their unchanged FromJSON instances, the state createInitialState
-- builds from them, and Conway's unchanged injectIntoTestState (conwayInjectIntoTestState: the
-- cost models, the funds, the pools with their voting keys, the stake, the DReps and delegations,
-- then resetStakeDistribution) applied to it. Every source is embedded, so the file system the
-- injection is given is never read.
reference :: Case -> IO Value
reference (Case _ shelley alonzo conwayGenesis) =
  case (,,) <$> parse shelley <*> parse alonzo <*> parse conwayGenesis of
    Left failure -> pure (refused failure)
    Right (sg, ag, cg) -> do
      let tc = conway sg ag cg
      outcome <- try $ do
        start <- evaluate (createInitialState tc)
        startBytes <- evaluate (encoded start)
        final <- injectIntoTestState (ioHasFS (MountPoint "/nonexistent")) tc start
        _ <- evaluate (BS.length (encoded final))
        pure (startBytes, final)
      pure $ case outcome of
        Left (e :: SomeException) -> refused (displayException e)
        Right (startBytes, final) ->
          object
            [ "accepted" .= True
            , "start" .= hex startBytes
            , -- the state as the node's ledger snapshot holds it, its UTxO in the tables beside it
              "state" .= hex (encoded (final & utxoL .~ UTxO mempty))
            , "utxo" .= hex (serialize' (eraProtVerLow @ConwayEra) (final ^. utxoL))
            , "snapshots" .= hex (serialize' (eraProtVerLow @ConwayEra) (final ^. nesEsL . esSnapshotsL))
            , "pools"
                .= hex
                  (serialize' (eraProtVerLow @ConwayEra) (final ^. nesEsL . esLStateL . lsCertStateL . certPStateL . psStakePoolsL))
            ]
  where
    parse :: FromJSON a => Text.Text -> Either String a
    parse = eitherDecodeStrict . Text.encodeUtf8

run :: Case -> IO Value
run c@(Case name _ _ _) = do
  result <- reference c
  pure $ object ["name" .= name, "reference" .= result]

main :: IO ()
main = LBS.getContents >>= mapM_ (\line -> either die run (eitherDecode line) >>= LBS.putStrLn . encode) . LBS.lines
