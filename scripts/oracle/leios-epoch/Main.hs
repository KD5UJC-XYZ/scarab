-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE PackageImports #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

import Cardano.Ledger.Allegra (AllegraEra)
import Cardano.Ledger.Alonzo.Genesis (AlonzoGenesis)
import Cardano.Ledger.Babbage (BabbageEra)
import Cardano.Ledger.BaseTypes (EpochNo (..), Globals (..), Network, StrictMaybe (..))
import Cardano.Ledger.Binary (serialize')
import Cardano.Ledger.Coin (Coin (..))
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Conway.Genesis (ConwayGenesis)
import Cardano.Ledger.Core (eraProtVerLow)
import Cardano.Ledger.Genesis (NoGenesis (..))
import Cardano.Ledger.Mary (MaryEra)
import Cardano.Ledger.Shelley.API (applyTickNoEvents)
import Cardano.Ledger.Shelley.Genesis (ShelleyGenesis (..), fromNominalDiffTimeMicro, mkShelleyGlobals)
import Cardano.Ledger.Shelley.LedgerState (NewEpochState, esLStateL, esSnapshotsL, lsCertStateL, nesEsL)
import Cardano.Ledger.Shelley.Transition (
  EraTransition (..),
  createInitialState,
  mkShelleyTransitionConfig,
 )
import Cardano.Ledger.State (
  StakePoolParams (..),
  UTxO (..),
  certPStateL,
  psFutureStakePoolParamsL,
  psRetiringL,
  psStakePoolsL,
  sppCostL,
  stakePoolStateToStakePoolParams,
  utxoL,
 )
import Cardano.Slotting.EpochInfo.Impl (fixedEpochInfo)
import Cardano.Slotting.Slot (EpochSize (..), SlotNo (..))
import Cardano.Slotting.Time (mkSlotLength)
import Control.Exception (SomeException, displayException, evaluate, try)
import Data.Aeson (FromJSON (..), Value, eitherDecode, eitherDecodeStrict, encode, object, withObject, (.:), (.=))
import qualified Data.ByteString as BS
import qualified "base16-bytestring" Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy.Char8 as LBS
import Data.List (scanl')
import qualified Data.Map.Strict as Map
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import Lens.Micro ((%~), (&), (.~), (^.))
import System.Exit (die)
import System.FS.API.Types (MountPoint (..))
import System.FS.IO (ioHasFS)

-- A case: the three genesis files, the change made to the injected state before the ticks, and the
-- slots the state is ticked to, in order (each from the state of the one before).
data Case = Case Text.Text Text.Text Text.Text Text.Text Seed [SlotNo]

-- The change made to the injected state: none, a re-registration of the genesis pool whose
-- parameters are its own with the cost one lovelace higher and no voting key (as a certificate
-- registers them up to Conway), its retirement at an epoch, or both.
data Seed = Seed Bool (Maybe EpochNo)

instance FromJSON Case where
  parseJSON = withObject "case" $ \o ->
    Case
      <$> o .: "name"
      <*> o .: "shelley"
      <*> o .: "alonzo"
      <*> o .: "conway"
      <*> (Seed <$> o .: "reregister" <*> (fmap EpochNo <$> o .: "retireAt"))
      <*> (map SlotNo <$> o .: "slots")

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

-- The globals of a chain whose eras all have the Shelley genesis's epoch size: what the hard-fork
-- combinator's epoch information answers for such a chain.
globalsOf :: ShelleyGenesis -> Globals
globalsOf sg =
  mkShelleyGlobals
    sg
    (fixedEpochInfo (sgEpochLength sg) (mkSlotLength (fromNominalDiffTimeMicro (sgSlotLength sg))))

encoded :: NewEpochState ConwayEra -> BS.ByteString
encoded = serialize' (eraProtVerLow @ConwayEra)

seeded :: Network -> Seed -> NewEpochState ConwayEra -> NewEpochState ConwayEra
seeded network (Seed reregister retireAt) nes =
  nes & nesEsL . esLStateL . lsCertStateL . certPStateL %~ (retire . future)
  where
    pools = nes ^. nesEsL . esLStateL . lsCertStateL . certPStateL . psStakePoolsL
    future ps
      | reregister =
          ps
            & psFutureStakePoolParamsL
              .~ Map.mapWithKey
                ( \pid sps ->
                    (stakePoolStateToStakePoolParams network pid sps) {sppBlsKey = SNothing}
                      & sppCostL %~ (\(Coin c) -> Coin (c + 1))
                )
                pools
      | otherwise = ps
    retire ps = maybe ps (\e -> ps & psRetiringL .~ Map.map (const e) pools) retireAt

refused :: String -> Value
refused failure = object ["accepted" .= False, "failure" .= failure]

-- The genesis files read with their unchanged FromJSON instances, createInitialState, Conway's
-- unchanged injectIntoTestState, the seed, then Conway's unchanged TICK (applyTickNoEvents) to each
-- slot. Every state is written without its UTxO, and ticked without it, as the node's ledger
-- snapshot holds it.
reference :: Case -> IO Value
reference (Case _ shelley alonzo conwayGenesis seed slots) =
  case (,,) <$> parse shelley <*> parse alonzo <*> parse conwayGenesis of
    Left failure -> pure (refused failure)
    Right (sg, ag, cg) -> do
      let tc = conway sg ag cg
          globals = globalsOf sg
      outcome <- try $ do
        start <- evaluate (createInitialState tc)
        injected <- injectIntoTestState (ioHasFS (MountPoint "/nonexistent")) tc start
        let input = seeded (sgNetworkId sg) seed injected & utxoL .~ UTxO mempty
            states = drop 1 (scanl' (applyTickNoEvents globals) input slots)
        inputBytes <- evaluate (encoded input)
        ticked <- traverse (\(slot, nes) -> (\bytes -> (slot, bytes, nes)) <$> evaluate (encoded nes)) (zip slots states)
        pure (inputBytes, ticked)
      pure $ case outcome of
        Left (e :: SomeException) -> refused (displayException e)
        Right (inputBytes, ticked) ->
          object
            [ "accepted" .= True
            , "globals"
                .= object
                  [ "epochSize" .= unEpochSize (sgEpochLength sg)
                  , "securityParameter" .= sgSecurityParam sg
                  , "stabilityWindow" .= stabilityWindow globals
                  , "randomnessStabilisationWindow" .= randomnessStabilisationWindow globals
                  ]
            , "input" .= hex inputBytes
            , "ticks"
                .= [ object
                       [ "slot" .= unSlotNo slot
                       , "state" .= hex bytes
                       , "snapshots" .= hex (serialize' (eraProtVerLow @ConwayEra) (nes ^. nesEsL . esSnapshotsL))
                       , "pools"
                           .= hex
                             ( serialize'
                                 (eraProtVerLow @ConwayEra)
                                 (nes ^. nesEsL . esLStateL . lsCertStateL . certPStateL . psStakePoolsL)
                             )
                       ]
                   | (slot, bytes, nes) <- ticked
                   ]
            ]
  where
    parse :: FromJSON a => Text.Text -> Either String a
    parse = eitherDecodeStrict . Text.encodeUtf8

run :: Case -> IO Value
run c@(Case name _ _ _ _ _) = do
  result <- reference c
  pure $ object ["name" .= name, "reference" .= result]

main :: IO ()
main = LBS.getContents >>= mapM_ (\line -> either die run (eitherDecode line) >>= LBS.putStrLn . encode) . LBS.lines
