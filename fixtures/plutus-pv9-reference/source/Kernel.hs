{-# LANGUAGE DataKinds #-}
{-# LANGUAGE GADTs #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TypeApplications #-}
{-# LANGUAGE TypeFamilies #-}
-- Draft only: not yet compiled or admitted as a reference oracle.
module Kernel (InputBytes(..), exportContext) where

import Cardano.Ledger.Alonzo.PParams (ppCostModelsL)
import Cardano.Ledger.Alonzo.Plutus.Evaluate (collectPlutusScriptsWithContext)
import Cardano.Ledger.BaseTypes (ProtVer(..))
import Cardano.Ledger.Binary (DecCBOR(..), decodeFull', decodeFullAnnotator, natVersion)
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Core hiding (Value)
import Cardano.Ledger.Plutus.CostModels (costModelsValid, getCostModelParams)
import Cardano.Ledger.Plutus.Evaluate (PlutusWithContext(..), evaluatePlutusWithContext)
import Cardano.Ledger.Plutus.TxInfo (exBudgetToExUnits)
import Cardano.Ledger.Plutus.ExUnits (ExUnits(..))
import Cardano.Ledger.Plutus.Language
import Cardano.Ledger.TxIn (TxIn)
import Cardano.Ledger.State (UTxO(..))
import Cardano.Slotting.EpochInfo (EpochInfo(..), fixedEpochInfo)
import Cardano.Slotting.Slot (EpochSize(..), EpochNo(..), SlotNo(..))
import Cardano.Slotting.Time (SystemStart(..), slotLengthFromSec)
import Codec.Serialise (serialise)
import Control.Monad (unless)
import Data.Aeson (Value, object, (.=))
import qualified Data.ByteString as B
import qualified Data.ByteString.Lazy as L
import qualified Data.ByteString.Short as S
import qualified Data.Map.Strict as M
import qualified Data.Set as Set
import qualified Data.Text as T
import Data.Text (Text)
import Data.Time (UTCTime(..), fromGregorian)
import Lens.Micro ((^.))
import Numeric (showHex)
import qualified PlutusLedgerApi.Common as P
import qualified PlutusLedgerApi.V3 as V3

-- Raw final transaction, full protocol parameters, exact ledger TxIn/TxOut CBOR.
-- Hash/provenance verification belongs to the separate file orchestrator.
data InputBytes = InputBytes B.ByteString B.ByteString B.ByteString [(B.ByteString, B.ByteString)]

check :: Bool -> Text -> Either Text ()
check ok why = unless ok (Left why)
decodeOne :: DecCBOR a => B.ByteString -> Either Text a
decodeOne = either (Left . T.pack . show) Right . decodeFull' (natVersion @9)
hex :: B.ByteString -> Text
hex = T.pack . concatMap (\n -> let s = showHex n "" in if length s == 1 then '0':s else s) . B.unpack
decimal :: Integral a => a -> Text
decimal = T.pack . show . toInteger

-- Synthetic schedule only. No historical chain anchor or hard-fork extrapolation.
syntheticEpochInfo :: EpochInfo (Either Text)
syntheticEpochInfo = base
  { epochInfoSize_ = \e -> epoch e >> epochInfoSize_ base e
  , epochInfoFirst_ = \e -> epoch e >> epochInfoFirst_ base e
  , epochInfoEpoch_ = \s -> slot s >> epochInfoEpoch_ base s
  , epochInfoSlotToRelativeTime_ = \s -> slot s >> epochInfoSlotToRelativeTime_ base s
  , epochInfoSlotLength_ = \s -> slot s >> epochInfoSlotLength_ base s
  }
  where
    base = fixedEpochInfo (EpochSize 1000) (slotLengthFromSec 1)
    slot (SlotNo s) = check (s <= 1000000) "slot outside synthetic domain"
    epoch (EpochNo e) = check (e <= 1000) "epoch outside synthetic domain"

exportContext :: InputBytes -> Either Text Value
exportContext (InputBytes txBytes ppBytes expectedScript entries) = do
  check (B.length expectedScript <= 65536) "reviewed script exceeds 64 KiB"
  check (B.length txBytes <= 65536) "transaction exceeds 64 KiB"
  check (B.length ppBytes <= 65536) "parameters exceed 64 KiB"
  check (length entries <= 256) "too many UTxO entries"
  check (sum [B.length a + B.length b | (a,b) <- entries] <= 1048576) "UTxO input exceeds 1 MiB"
  tx <- either (Left . T.pack . show) Right $
    decodeFullAnnotator (natVersion @9) "ConwayTx" decCBOR (L.fromStrict txBytes)
      :: Either Text (Tx TopTx ConwayEra)
  pp <- decodeOne ppBytes :: Either Text (PParams ConwayEra)
  check (pp ^. ppProtocolVersionL == ProtVer (natVersion @9) 0) "requires exact PV 9.0"
  cm <- maybe (Left "missing valid V3 cost model") Right $
    M.lookup PlutusV3 (costModelsValid (pp ^. ppCostModelsL))
  check (length (getCostModelParams cm) == 251) "requires 251 V3 cost coefficients"
  pairs <- traverse (\(a,b) -> (,) <$> (decodeOne a :: Either Text TxIn) <*> (decodeOne b :: Either Text (TxOut ConwayEra))) entries
  let utxoMap = M.fromList pairs
  check (M.size utxoMap == length pairs) "duplicate pre-state input"
  check ((tx ^. bodyTxL . allInputsTxBodyF) `Set.isSubsetOf` M.keysSet utxoMap) "missing pre-state input"
  contexts <- either (Left . T.pack . show) Right $
    collectPlutusScriptsWithContext syntheticEpochInfo
      (SystemStart (UTCTime (fromGregorian 2020 1 1) 0)) pp tx (UTxO utxoMap)
  case contexts of
    [pwc] -> renderContext expectedScript pwc
    _ -> Left "requires exactly one collected Plutus script"

renderContext :: B.ByteString -> PlutusWithContext -> Either Text Value
renderContext expectedScript pwc@PlutusWithContext{pwcArgs = args, pwcScript = script, pwcExUnits = units, pwcCostModel = cm} =
  case plutusSLanguage script of
    SPlutusV3 -> do
      let PlutusV3Args context = args
      check (exUnitsSteps units <= 10000000000 && exUnitsMem units <= 14000000) "declared execution budget exceeds helper cap"
      let dat = V3.toData context
          encoded = L.toStrict (serialise dat)
          Plutus (PlutusBinary binary) = plutusRunnableBinary script
      check (S.fromShort binary == expectedScript) "script does not match reviewed bytes"
      case V3.scriptContextScriptInfo context of
        V3.SpendingScript _ _ -> pure ()
        _ -> Left "only spending purpose is admitted"
      check (B.length encoded <= 1048576) "context exceeds 1 MiB"
      tree <- dataTree 0 dat
      -- Evaluate the SAME collected object; never construct a replacement context.
      -- Quiet mode intentionally suppresses unbounded trace logs.
      let (_, outcome) = evaluatePlutusWithContext P.Quiet pwc
          result = budgetResult outcome
          experiments = case outcome of
            Right budget -> case exBudgetToExUnits budget of
              Just used | exUnitsSteps used > 0 && exUnitsMem used > 0 ->
                [ machineProbe "exact-consumed" used
                , machineProbe "cpu-one-below" (ExUnits (exUnitsMem used) (exUnitsSteps used - 1))
                , machineProbe "memory-one-below" (ExUnits (exUnitsMem used - 1) (exUnitsSteps used))
                ]
              _ -> []
            _ -> []
          machineProbe name limit = object
            [ "name" .= (name :: Text)
            , "contextUnchanged" .= True
            , "ledgerTransactionReconstructed" .= False
            , "cpuLimit" .= decimal (exUnitsSteps limit)
            , "memoryLimit" .= decimal (exUnitsMem limit)
            , "result" .= budgetResult (snd (evaluatePlutusWithContext P.Quiet (pwc {pwcExUnits = limit})))
            ]

      pure $ object
        [ "profile" .= ("synthetic-conway-pv9-v3-draft" :: Text)
        , "historicalChainAnchor" .= False
        , "fullTransactionValidation" .= False
        , "scriptHex" .= hex (S.fromShort binary)
        , "contextDataCborHex" .= hex encoded
        , "contextDataTree" .= tree
        , "costModel" .= map decimal (getCostModelParams cm)
        , "declaredCpuSteps" .= decimal (exUnitsSteps units)
        , "declaredMemoryUnits" .= decimal (exUnitsMem units)
        , "evaluation" .= result
        , "machineLimitExperiments" .= experiments
        , "countingEvaluationPerformed" .= False
        , "logMode" .= ("quiet" :: Text)
        ]
    _ -> Left "only PlutusV3 context is supported"

-- Ordered pairs stay ordered; arbitrary integers are decimal strings.
dataTree :: Int -> V3.Data -> Either Text Value
dataTree depth dat = do
  check (depth <= 128) "Data tree exceeds depth 128"
  let children = traverse (dataTree (depth+1))
  case dat of
    V3.Constr tag xs -> do ys <- children xs; pure $ object ["constructor" .= decimal tag, "fields" .= ys]
    V3.Map pairs -> do
      ys <- traverse (\(k,v) -> do a <- dataTree (depth+1) k; b <- dataTree (depth+1) v; pure [a,b]) pairs
      pure $ object ["map" .= ys]
    V3.List xs -> do ys <- children xs; pure $ object ["list" .= ys]
    V3.I n -> pure $ object ["integer" .= decimal n]
    V3.B bytes -> pure $ object ["bytes" .= hex bytes]

-- Public API categories; CEK subtypes remain opaque until separately reviewed.
-- Do not infer budget exhaustion from Show text or the requested limit.
errorCategory :: P.EvaluationError -> Text
errorCategory (P.CekError _) = "cek-error-unclassified"
errorCategory (P.DeBruijnError _) = "free-variable"
errorCategory (P.CodecError _) = "script-codec"
errorCategory P.CostModelParameterMismatch = "cost-model-parameter-mismatch"
errorCategory P.InvalidReturnValue = "invalid-v3-return-value"

budgetResult :: Either P.EvaluationError P.ExBudget -> Value
budgetResult (Left err) = object
  [ "status" .= ("evaluation-error" :: Text)
  , "category" .= errorCategory err
  , "error" .= T.take 8192 (T.pack (show err))
  , "consumedBudget" .= (Nothing :: Maybe Value)
  ]
budgetResult (Right budget) = case exBudgetToExUnits budget of
  Nothing -> object ["status" .= ("invalid-reference-budget" :: Text)]
  Just used -> object ["status" .= ("v3-validation-success" :: Text), "cpuSteps" .= decimal (exUnitsSteps used), "memoryUnits" .= decimal (exUnitsMem used)]
