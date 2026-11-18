-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

import Cardano.Ledger.Binary (
  DecShareCBOR (..),
  DecoderError,
  Interns,
  decodeBytes,
  decodeMap,
  getVersion64,
  internsFromMap,
  serialize',
 )
import qualified Cardano.Ledger.Binary.Plain as Plain
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Core (EraTxOut (..), TxOut, eraDecoder, eraProtVerLow, translateEra')
import Cardano.Ledger.Credential (Credential)
import Cardano.Ledger.Dijkstra (DijkstraEra)
import Cardano.Ledger.Dijkstra.Genesis (DijkstraGenesis)
import Cardano.Ledger.Hashes (KeyRole (Staking))
import Cardano.Ledger.Shelley.LedgerState (NewEpochState, esLStateL, lsCertStateL, nesEsL)
import Cardano.Ledger.State (accountsL, accountsMapL, certDStateL)
import Data.Aeson (Value, eitherDecodeFileStrict', encode, object, (.=))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as LBS
import qualified Data.Map.Strict as Map
import Data.MemPack (packByteString)
import Data.Text (Text)
import qualified Data.Text as T
import Lens.Micro ((^.))
import Numeric (showHex)
import System.Environment (getArgs)
import System.Exit (die)

hex :: BS.ByteString -> Text
hex = T.pack . concatMap (\w -> (if w < 16 then ('0' :) else id) (showHex w "")) . BS.unpack

refuse :: String -> Either DecoderError a -> IO a
refuse what = either (\e -> die (what <> ": " <> show e)) pure

-- The ledger state inside the snapshot's extended state, read and written as ouroboros-consensus
-- 2416493b reads and writes it (`fromCBOR` and `toCBOR`, `Shelley/Ledger/Ledger.hs:900,918`):
-- `fromEraCBOR` and `toEraCBOR`, at the era's lowest version (cardano-ledger 9df9f98c
-- `Shelley/LedgerState/Types.hs:423-433`).
conwayState :: BS.ByteString -> Either DecoderError (NewEpochState ConwayEra)
conwayState = Plain.decodeFull'

dijkstraState :: BS.ByteString -> Either DecoderError (NewEpochState DijkstraEra)
dijkstraState = Plain.decodeFull'

-- The UTxO of the snapshot as the in-memory backend reads it: `decodeListLenOf 1`
-- (`valuesMKDecoder`, `Ledger/Tables.hs:291-292`), then Conway's `getOne`
-- (`Cardano/Ledger.hs:240-257`): a map read at Conway's version, each output by `decShareCBOR` with
-- the interns of the state's accounts. The keys are the consensus layer's `BigEndianTxIn`, which the
-- translation carries unchanged (`translateTxInWith = coerce`, `Cardano/CanHardFork.hs:763`), so
-- they are kept as bytes.
tables :: Interns (Credential Staking) -> BS.ByteString -> Either DecoderError (Map.Map BS.ByteString (TxOut ConwayEra))
tables interns =
  Plain.decodeFullDecoder' "tables" $
    Plain.decodeListLenOf 1 >> eraDecoder @ConwayEra (decodeMap decodeBytes (decShareCBOR interns))

-- `upgradeTxOut` of Dijkstra (`Dijkstra/TxOut.hs:33`), the `translateTxOutWith` of the Conway to
-- Dijkstra crossing (`Cardano/CanHardFork.hs:757-765`) that ejecting a Conway output into Dijkstra
-- applies (`Cardano/Ledger.hs:150-162`).
output :: (BS.ByteString, TxOut ConwayEra) -> Value
output (txIn, conway) =
  let dijkstra = upgradeTxOut conway :: TxOut DijkstraEra
   in object
        [ "txIn" .= hex txIn
        , "conwayTxOut" .= hex (packByteString conway)
        , "dijkstraTxOut" .= hex (serialize' (eraProtVerLow @DijkstraEra) dijkstra)
        , "dijkstraTxOutMemPack" .= hex (packByteString dijkstra)
        ]

main :: IO ()
main = do
  args <- getArgs
  (statePath, tablesPath, genesisPath) <- case args of
    [s, t, g] -> pure (s, t, g)
    _ -> die "three paths required: the Conway NewEpochState, the tables, the Dijkstra genesis file"
  stateBytes <- BS.readFile statePath
  tablesBytes <- BS.readFile tablesPath
  -- The translation context is the Dijkstra genesis (`Dijkstra/Translation.hs:51`), which the node
  -- reads with its `FromJSON` (`Dijkstra/Genesis.hs:32-35`) into the Dijkstra ledger configuration
  -- (`Cardano/Node.hs:1026-1041`, `Dijkstra/Transition.hs:67-68`).
  genesis <- eitherDecodeFileStrict' genesisPath >>= either die pure :: IO DijkstraGenesis
  conway <- refuse "Conway NewEpochState" (conwayState stateBytes)
  let interns =
        internsFromMap $
          conway ^. nesEsL . esLStateL . lsCertStateL . certDStateL . accountsL . accountsMapL
  utxo <- refuse "tables" (tables interns tablesBytes)
  -- `TranslateEra DijkstraEra NewEpochState` (`Dijkstra/Translation.hs:70-80`), unchanged.
  let dijkstra = translateEra' genesis conway :: NewEpochState DijkstraEra
      dijkstraBytes = Plain.serialize' dijkstra
  reread <- refuse "Dijkstra NewEpochState" (dijkstraState dijkstraBytes)
  LBS.putStr . encode $
    object
      [ "conwayVersion" .= getVersion64 (eraProtVerLow @ConwayEra)
      , "dijkstraVersion" .= getVersion64 (eraProtVerLow @DijkstraEra)
      , "conwayRoundTrip" .= (Plain.serialize' conway == stateBytes)
      , "dijkstraNewEpochState" .= hex dijkstraBytes
      , "dijkstraRoundTrip" .= (Plain.serialize' reread == dijkstraBytes)
      , "outputs" .= map output (Map.toList utxo)
      ]
