-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE DeriveGeneric #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

-- The Plutus script contexts the build of the public Leios testnet collects for transactions of its
-- chain (#54). It is the `kind` "context" of the parent harness (`../Main.hs`) with the chain's
-- environment in place of the fixed one: the protocol version and cost models in force, and the
-- chain's system start and epoch size, so that a transaction's validity interval translates to the
-- POSIX times the chain's scripts were given. `mkDijkstraStAnnTopTx` (cardano-ledger 9df9f98c,
-- `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra.hs:111-169`) reads nothing else of the protocol
-- parameters than `ppProtocolVersionL` and `ppCostModelsL`, and `mkDijkstraStAnnSubTx` (`:171-219`)
-- the same. The epoch information is `fixedEpochInfo`: every era of the chain begins at epoch 0 with
-- the same epoch size and slot length (the testnet's Shelley genesis and `Test*HardForkAtEpoch` 0),
-- so it gives the times the node's own epoch information gives within its horizon.
--
-- The input is one JSON object: `protocolVersion` (`[major, minor]`), `costModels` (each language's
-- list of parameters), `systemStart` (UTC), `epochSize` and `slotLength` (seconds), and `cases`,
-- each a `name`, a transaction as a Dijkstra block body holds it (`bytes`), and the outputs it
-- spends, references and pledges (`utxo`: pairs of a `TxIn` and a `TxOut`, each encoded at version
-- 12). The output is, for each case, what the parent harness writes for `kind` "context": every
-- context of the top-level transaction and of each sub-transaction, each the `ToCBOR
-- PlutusWithContext` encoding (`libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Evaluate.hs:
-- 186-193`), or the collection's failure.

import Cardano.Binary (DecoderError)
import qualified Cardano.Crypto.Hash as Hash
import Cardano.Ledger.Alonzo.PParams (ppCostModelsL)
import Cardano.Ledger.BaseTypes (ProtVer (..), mkVersion, natVersion)
import qualified Cardano.Ledger.Binary as Binary
import qualified Cardano.Ledger.Binary.Plain as Plain
import Cardano.Ledger.Core (PParams, TxOut, emptyPParams, ppProtocolVersionL, txIdTx)
import Cardano.Ledger.Dijkstra (DijkstraEra, mkDijkstraStAnnTopTx)
import Cardano.Ledger.Dijkstra.Tx (DijkstraStAnnTx (..), Tx (MkDijkstraTx), decodeDijkstraTopTxInBlock)
import Cardano.Ledger.Hashes (extractHash)
import Cardano.Ledger.Plutus.CostModels (mkCostModel, mkCostModels)
import Cardano.Ledger.Plutus.Evaluate (PlutusWithContext)
import Cardano.Ledger.Plutus.Language (Language)
import Cardano.Ledger.State (UTxO (..))
import Cardano.Ledger.TxIn (TxId (..), TxIn)
import Cardano.Slotting.EpochInfo (fixedEpochInfo)
import Cardano.Slotting.Slot (EpochSize (..))
import Cardano.Slotting.Time (SystemStart (..), mkSlotLength)
import Data.Aeson (FromJSON, Value, eitherDecode, encode, object, (.=))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy as LBS
import Data.Function ((&))
import Data.Int (Int64)
import qualified Data.Map.Strict as Map
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import Data.Time.Clock (UTCTime)
import Data.Word (Word64)
import GHC.Generics (Generic)
import Lens.Micro ((.~))
import System.Environment (getArgs)
import System.Exit (die)

data Case = Case
  { name :: String
  , bytes :: Text.Text
  , utxo :: [(Text.Text, Text.Text)]
  }
  deriving (Generic)

instance FromJSON Case

data Chain = Chain
  { protocolVersion :: (Word64, Word64)
  , costModels :: Map.Map Language [Int64]
  , systemStart :: UTCTime
  , epochSize :: Word64
  , slotLength :: Integer
  , cases :: [Case]
  }
  deriving (Generic)

instance FromJSON Chain

-- | What stops the harness before any case: an input it cannot read.
data InputError
  = NotHex String
  | BadProtocolVersion Word64
  deriving (Show)

hexBytes :: BS.ByteString -> Text.Text
hexBytes = Text.decodeUtf8 . Hex.encode

decodeHex :: Text.Text -> IO LBS.ByteString
decodeHex t = either (die . show . NotHex) (pure . LBS.fromStrict) (Hex.decode (Text.encodeUtf8 t))

params :: Chain -> IO (PParams DijkstraEra)
params c = do
  let (major, minor) = protocolVersion c
  version <- maybe (die (show (BadProtocolVersion major))) pure (mkVersion major)
  models <- either (die . show) pure (Map.traverseWithKey mkCostModel (costModels c))
  pure $
    emptyPParams
      & ppProtocolVersionL .~ ProtVer version (fromIntegral minor)
      & ppCostModelsL .~ mkCostModels models

spentOutput :: (Text.Text, Text.Text) -> IO (TxIn, TxOut DijkstraEra)
spentOutput (i, o) = do
  input <- decodeHex i >>= either (die . show) pure . Binary.decodeFull (natVersion @12)
  output <- decodeHex o >>= either (die . show) pure . Binary.decodeFull (natVersion @12)
  pure (input, output)

contexts :: Either e [PlutusWithContext] -> Value
contexts =
  either
    (const (object ["collected" .= False]))
    (\pwcs -> object ["collected" .= True, "contexts" .= map (hexBytes . Plain.serialize') pwcs])

runCase :: Chain -> PParams DijkstraEra -> Case -> IO Value
runCase c pp k = do
  spent <- Map.fromList <$> traverse spentOutput (utxo k)
  raw <- decodeHex (bytes k)
  let decoded = Binary.decodeFullAnnotator (natVersion @12) "DijkstraTx" decodeDijkstraTopTxInBlock raw
      epochInfo = fixedEpochInfo (EpochSize (epochSize c)) (mkSlotLength (fromIntegral (slotLength c)))
      result = case decoded of
        Left failure -> object ["accepted" .= False, "failure" .= show (failure :: DecoderError)]
        Right t ->
          let tx = MkDijkstraTx t
              annotated = mkDijkstraStAnnTopTx epochInfo (SystemStart (systemStart c)) pp (UTxO spent) mempty tx
           in object
                [ "accepted" .= True
                , "id" .= Hash.hashToTextAsHex (extractHash (unTxId (txIdTx tx)))
                , "top" .= contexts (dsattPlutusScriptsWithContext annotated)
                , "subtxs" .= map (contexts . dsastPlutusScriptsWithContext) (dsattSubTransactions annotated)
                , "failure" .= either (Just . show) (const Nothing) (dsattPlutusScriptsWithContext annotated)
                ]
  pure (object ["name" .= name k, "kind" .= ("context" :: Text.Text), "result" .= result])

main :: IO ()
main = do
  args <- getArgs
  chain <- case args of
    [p] -> LBS.readFile p >>= either die pure . eitherDecode
    _ -> die "one input JSON path required"
  pp <- params chain
  traverse (runCase chain pp) (cases chain) >>= LBS.putStr . encode
