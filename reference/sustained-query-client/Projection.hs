{-# LANGUAGE DataKinds, OverloadedStrings, TypeApplications #-}
module Projection (projectCapture) where
import qualified Cardano.Ledger.Binary.Plain as Plain
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Core (PParams)
import Cardano.Ledger.Shelley.LedgerState (NewEpochState(..))
import Ouroboros.Consensus.Protocol.Praos (PraosState(..))
import Data.Aeson (Value, object, (.=), toJSON, encode)
import Cardano.Ledger.State (UTxO(..))
import StrictJson (hexText)
import qualified Data.ByteString.Lazy as LBS
import qualified Data.Text as T
import Session
import VerifySeed

-- These are derived native projections of original payloads, never CLI exports.
-- The original bytes remain independent fields of the capture envelope.
projectCapture :: Capture -> Either String Value
projectCapture c = do
  checked <- verifySeed (epochBytes c) (utxoBytes c)
  epoch <- either (Left . show) Right (Plain.decodeFull (epochBytes c)) :: Either String (NewEpochState ConwayEra)
  utxo <- either (Left . show) Right (Plain.decodeFull (utxoBytes c)) :: Either String (UTxO ConwayEra)
  protocol <- either (Left . show) Right (Plain.decodeFull (protocolBytes c)) :: Either String PraosState
  params <- either (Left . show) Right (Plain.decodeFull (parameterBytes c)) :: Either String (PParams ConwayEra)
  protocolAgain <- either (Left . show) Right (Plain.decodeFull (Plain.serialize protocol))
  paramsAgain <- either (Left . show) Right (Plain.decodeFull (Plain.serialize params))
  if protocolAgain /= protocol || paramsAgain /= params then Left "native protocol/parameter round-trip mismatch" else pure ()
  pure $ object [
    "kind" .= ("derived-native-supported-state" :: T.Text),
    "ledgerJsonHex" .= hexText (LBS.toStrict (encode (object ["lastEpoch" .= nesEL epoch, "stateBefore" .= nesEs epoch, "stakeDistrib" .= nesPd epoch, "blocksBefore" .= nesBprev epoch, "blocksCurrent" .= nesBcur epoch]))),
    "utxoJsonHex" .= hexText (LBS.toStrict (encode (toJSON utxo))),
    "parametersJsonHex" .= hexText (LBS.toStrict (encode (toJSON params))),
    "protocolJsonHex" .= hexText (LBS.toStrict (encode (object [
      "lastSlot" .= praosStateLastSlot protocol,
      "oCertCounters" .= praosStateOCertCounters protocol,
      "evolvingNonce" .= praosStateEvolvingNonce protocol,
      "candidateNonce" .= praosStateCandidateNonce protocol,
      "epochNonce" .= praosStateEpochNonce protocol,
      "previousEpochNonce" .= praosStatePreviousEpochNonce protocol,
      "labNonce" .= praosStateLabNonce protocol,
      "lastEpochBlockNonce" .= praosStateLastEpochBlockNonce protocol]))),
    "utxoEntries" .= wholeUTxOEntries checked,
    "nativeSemanticRoundTrips" .= True,
    "fullLedgerValidation" .= False,
    "monetaryParity" .= False]
