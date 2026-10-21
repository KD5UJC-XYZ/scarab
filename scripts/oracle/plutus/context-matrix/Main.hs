-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE DeriveGeneric #-}
{-# LANGUAGE GADTs #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

import Cardano.Ledger.Alonzo (AlonzoEra)
import Cardano.Ledger.Babbage (BabbageEra)
import Cardano.Ledger.Alonzo.Core (ppCostModelsL)
import Cardano.Ledger.Alonzo.Plutus.Evaluate (CollectError (..), collectPlutusScriptsWithContext)
import Cardano.Ledger.BaseTypes (ProtVer (..))
import qualified Cardano.Ledger.Binary as Binary
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Core hiding (Value)
import Cardano.Ledger.Plutus (Language (..), mkCostModel, mkCostModels, pwcScriptHash, evaluatePlutusWithContext)
import qualified PlutusLedgerApi.Common as P
import qualified Cardano.Ledger.Binary.Plain as Plain
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
import qualified Data.Map.Strict as Map
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import Data.Time.Clock.POSIX (posixSecondsToUTCTime)
import GHC.Generics (Generic)
import Lens.Micro ((&), (.~))
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
  , era :: String
  , systemStartSeconds :: Int
  , slotLengthMilliseconds :: Int
  } deriving (Show, Generic)
instance FromJSON Case
instance ToJSON Case

hex :: BS.ByteString -> Text.Text
hex = Text.decodeUtf8 . Hex.encode

raw :: Text.Text -> IO BS.ByteString
raw = either die pure . Hex.decode . Text.encodeUtf8

readNumber :: Text.Text -> IO Int64
readNumber text = maybe (die "invalid integer in cost model") pure (readMaybe (Text.unpack text))

runAlonzo :: Case -> IO Value
runAlonzo c = do
  hPutStrLn stderr (name c)
  major <- Binary.mkVersion (protocolMajor c)
  txBytes <- raw (transaction c)
  tx <- either (die . show) pure $
    Binary.decodeFullAnnotator major "Alonzo Tx" Binary.decCBOR (LBS.fromStrict txBytes)
      :: IO (Tx TopTx AlonzoEra)
  entries <- forM (utxo c) $ \entry -> do
    inBytes <- raw (input entry)
    outBytes <- raw (output entry)
    txIn <- either (die . show) pure (Binary.decodeFull' major inBytes)
    txOut <- either (die . show) pure (Binary.decodeFull' major outBytes)
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
  let pp = emptyPParams @AlonzoEra & ppProtocolVersionL .~ ProtVer major 0
            & ppCostModelsL .~ mkCostModels (Map.fromList models)
      result = collectPlutusScriptsWithContext
        (fixedEpochInfo (EpochSize 500) (mkSlotLength (fromIntegral (slotLengthMilliseconds c) / 1000)))
        (SystemStart (posixSecondsToUTCTime (fromIntegral (systemStartSeconds c)))) pp tx (UTxO (Map.fromList entries))
  pure $ case result of
    Left errors -> object
      [ "name" .= name c, "collected" .= False
      , "collectErrorsCbor" .= hex (Binary.serialize' major errors)
      , "failureKinds" .= map (\e -> case e of
            NoRedeemer _ -> ("NoRedeemer" :: String)
            NoWitness _ -> "NoWitness"
            NoCostModel _ -> "NoCostModel"
            BadTranslation _ -> "BadTranslation") (toList errors) ]
    Right requests -> object
      [ "name" .= name c, "collected" .= True
      , "scripts" .= map (\pwc -> let (_, evaluated) = evaluatePlutusWithContext P.Quiet pwc
                              in object ["scriptHash" .= show (pwcScriptHash pwc)
                                        ,"contextCbor" .= hex (Plain.serialize' pwc)
                                        ,"success" .= either (const False) (const True) evaluated
                                        ,"used" .= either (const Nothing) (\(P.ExBudget cpu mem) -> Just (object ["steps" .= cpu, "mem" .= mem])) evaluated]) requests ]

runBabbage :: Case -> IO Value
runBabbage c = do
  hPutStrLn stderr (name c)
  major <- Binary.mkVersion (protocolMajor c)
  txBytes <- raw (transaction c)
  tx <- either (die . show) pure $
    Binary.decodeFullAnnotator major "Babbage Tx" Binary.decCBOR (LBS.fromStrict txBytes)
      :: IO (Tx TopTx BabbageEra)
  entries <- forM (utxo c) $ \entry -> do
    inBytes <- raw (input entry)
    outBytes <- raw (output entry)
    txIn <- either (die . show) pure (Binary.decodeFull' major inBytes)
    txOut <- either (die . show) pure (Binary.decodeFull' major outBytes)
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
  let pp = emptyPParams @BabbageEra & ppProtocolVersionL .~ ProtVer major 0
            & ppCostModelsL .~ mkCostModels (Map.fromList models)
      result = collectPlutusScriptsWithContext
        (fixedEpochInfo (EpochSize 500) (mkSlotLength (fromIntegral (slotLengthMilliseconds c) / 1000)))
        (SystemStart (posixSecondsToUTCTime (fromIntegral (systemStartSeconds c)))) pp tx (UTxO (Map.fromList entries))
  pure $ case result of
    Left errors -> object
      [ "name" .= name c, "collected" .= False
      , "collectErrorsCbor" .= hex (Binary.serialize' major errors)
      , "failureKinds" .= map (\e -> case e of
            NoRedeemer _ -> ("NoRedeemer" :: String)
            NoWitness _ -> "NoWitness"
            NoCostModel _ -> "NoCostModel"
            BadTranslation _ -> "BadTranslation") (toList errors) ]
    Right requests -> object
      [ "name" .= name c, "collected" .= True
      , "scripts" .= map (\pwc -> let (_, evaluated) = evaluatePlutusWithContext P.Quiet pwc
                              in object ["scriptHash" .= show (pwcScriptHash pwc)
                                        ,"contextCbor" .= hex (Plain.serialize' pwc)
                                        ,"success" .= either (const False) (const True) evaluated
                                        ,"used" .= either (const Nothing) (\(P.ExBudget cpu mem) -> Just (object ["steps" .= cpu, "mem" .= mem])) evaluated]) requests ]

runConway :: Case -> IO Value
runConway c = do
  hPutStrLn stderr (name c)
  major <- Binary.mkVersion (protocolMajor c)
  txBytes <- raw (transaction c)
  tx <- either (die . show) pure $
    Binary.decodeFullAnnotator major "Conway Tx" Binary.decCBOR (LBS.fromStrict txBytes)
      :: IO (Tx TopTx ConwayEra)
  entries <- forM (utxo c) $ \entry -> do
    inBytes <- raw (input entry)
    outBytes <- raw (output entry)
    txIn <- either (die . show) pure (Binary.decodeFull' major inBytes)
    txOut <- either (die . show) pure (Binary.decodeFull' major outBytes)
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
  let pp = emptyPParams @ConwayEra & ppProtocolVersionL .~ ProtVer major 0
            & ppCostModelsL .~ mkCostModels (Map.fromList models)
      result = collectPlutusScriptsWithContext
        (fixedEpochInfo (EpochSize 500) (mkSlotLength (fromIntegral (slotLengthMilliseconds c) / 1000)))
        (SystemStart (posixSecondsToUTCTime (fromIntegral (systemStartSeconds c)))) pp tx (UTxO (Map.fromList entries))
  pure $ case result of
    Left errors -> object
      [ "name" .= name c, "collected" .= False
      , "collectErrorsCbor" .= hex (Binary.serialize' major errors)
      , "failureKinds" .= map (\e -> case e of
            NoRedeemer _ -> ("NoRedeemer" :: String)
            NoWitness _ -> "NoWitness"
            NoCostModel _ -> "NoCostModel"
            BadTranslation _ -> "BadTranslation") (toList errors) ]
    Right requests -> object
      [ "name" .= name c, "collected" .= True
      , "scripts" .= map (\pwc -> let (_, evaluated) = evaluatePlutusWithContext P.Quiet pwc
                              in object ["scriptHash" .= show (pwcScriptHash pwc)
                                        ,"contextCbor" .= hex (Plain.serialize' pwc)
                                        ,"success" .= either (const False) (const True) evaluated
                                        ,"used" .= either (const Nothing) (\(P.ExBudget cpu mem) -> Just (object ["steps" .= cpu, "mem" .= mem])) evaluated]) requests ]

main :: IO ()
main = do
  args <- getArgs
  case args of
    [path] -> do
      bytes <- LBS.readFile path
      cases <- either die pure (eitherDecode bytes :: Either String [Case])
      results <- traverse (\c -> case era c of
        "Alonzo" -> runAlonzo c
        "Babbage" -> runBabbage c
        "Conway" -> runConway c
        _ -> die "unsupported era") cases
      LBS.putStr (encode results)
    _ -> die "usage: scarab-context-matrix cases.json"
