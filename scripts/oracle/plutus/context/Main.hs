-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE DeriveGeneric #-}
{-# LANGUAGE GADTs #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

import Cardano.Ledger.Alonzo.Core (ppCostModelsL)
import Cardano.Ledger.Alonzo.Plutus.Evaluate (CollectError (..), collectPlutusScriptsWithContext)
import Cardano.Ledger.Alonzo.Scripts (AlonzoEraScript (mkCertifyingPurpose), AsItem (..))
import Cardano.Ledger.BaseTypes (ProtVer (..))
import qualified Cardano.Ledger.Binary as Binary
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Conway.Rules (ConwayUtxosPredFailure (CollectErrors))
import Cardano.Ledger.Core hiding (Value)
import Cardano.Ledger.Plutus (Language (..), mkCostModel, mkCostModels, pwcScriptHash)
import Cardano.Ledger.State (UTxO (..))
import Cardano.Slotting.EpochInfo (fixedEpochInfo)
import Cardano.Slotting.Slot (EpochSize (..))
import Cardano.Slotting.Time (SystemStart (..), mkSlotLength)
import Control.Monad (forM)
import Data.Aeson (FromJSON, ToJSON, Value, eitherDecode, encode, object, (.=))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy as LBS
import Data.Int (Int64)
import Data.Foldable (toList)
import Data.List.NonEmpty (NonEmpty ((:|)))
import qualified Data.Map.Strict as Map
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import Data.Time.Clock.POSIX (posixSecondsToUTCTime)
import GHC.Generics (Generic)
import Lens.Micro ((&), (.~), (^.))
import System.Environment (getArgs)
import System.Exit (die)
import System.IO (hPutStrLn, stderr)
import Text.Read (readMaybe)

data Entry = Entry { input :: Text.Text, output :: Text.Text } deriving (Show, Generic)
instance FromJSON Entry
instance ToJSON Entry

data Case = Case
  { name :: String
  , transaction :: Text.Text
  , utxo :: [Entry]
  , costModels :: Map.Map Text.Text [Text.Text]
  , protocolMajor :: Int
  } deriving (Show, Generic)
instance FromJSON Case
instance ToJSON Case

hex :: BS.ByteString -> Text.Text
hex = Text.decodeUtf8 . Hex.encode

raw :: Text.Text -> IO BS.ByteString
raw = either die pure . Hex.decode . Text.encodeUtf8

readNumber :: Text.Text -> IO Int64
readNumber text = maybe (die "invalid integer in cost model") pure (readMaybe (Text.unpack text))

runCase :: Case -> IO Value
runCase c = do
  hPutStrLn stderr (name c)
  txBytes <- raw (transaction c)
  tx <- either (die . show) pure $
    Binary.decodeFullAnnotator (Binary.natVersion @9) "Conway Tx" Binary.decCBOR (LBS.fromStrict txBytes)
      :: IO (Tx TopTx ConwayEra)
  entries <- forM (utxo c) $ \entry -> do
    inBytes <- raw (input entry)
    outBytes <- raw (output entry)
    txIn <- either (die . show) pure (Binary.decodeFull' (Binary.natVersion @9) inBytes)
    txOut <- either (die . show) pure (Binary.decodeFull' (Binary.natVersion @9) outBytes)
    pure (txIn, txOut)
  models <- forM (Map.toList (costModels c)) $ \(key, values) -> do
    lang <- case key of
      "0" -> pure PlutusV1
      "1" -> pure PlutusV2
      "2" -> pure PlutusV3
      _ -> die "unknown language in cost model"
    params <- traverse readNumber values
    model <- either (die . show) pure (mkCostModel lang params)
    pure (lang, model)
  major <- Binary.mkVersion (protocolMajor c)
  let pp = emptyPParams @ConwayEra & ppProtocolVersionL .~ ProtVer major 0
            & ppCostModelsL .~ mkCostModels (Map.fromList models)
      result = collectPlutusScriptsWithContext
        (fixedEpochInfo (EpochSize 500) (mkSlotLength 0.1))
        (SystemStart (posixSecondsToUTCTime 0)) pp tx (UTxO (Map.fromList entries))
      certificateEncodings = map (\cert -> object
        [ "certificateCbor" .= hex (Binary.serialize' (Binary.natVersion @9) cert)
        , "noRedeemerUtxosCbor" .= hex (Binary.serialize' (Binary.natVersion @9)
            (CollectErrors (NoRedeemer (mkCertifyingPurpose (AsItem cert)) :| []) :: ConwayUtxosPredFailure ConwayEra))
        ]) (toList (tx ^. bodyTxL . certsTxBodyL))
  pure $ case result of
    Left errors -> object
      [ "name" .= name c, "collected" .= False, "errors" .= show errors
      , "certificateEncodings" .= certificateEncodings
      , "utxosFailureCbor" .= hex (Binary.serialize' (Binary.natVersion @9) (CollectErrors errors :: ConwayUtxosPredFailure ConwayEra))
      , "collectErrorsCbor" .= hex (Binary.serialize' (Binary.natVersion @9) errors)
      ]
    Right requests -> object
      [ "name" .= name c, "collected" .= True, "scripts" .= length requests
      , "certificateEncodings" .= certificateEncodings
      , "scriptHashes" .= map (show . pwcScriptHash) requests
      ]

main :: IO ()
main = do
  args <- getArgs
  case args of
    [path] -> do
      bytes <- LBS.readFile path
      cases <- either die pure (eitherDecode bytes :: Either String [Case])
      results <- traverse runCase cases
      LBS.putStr (encode results)
    _ -> die "usage: scarab-context-oracle cases.json"
