{-# LANGUAGE DataKinds #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE TypeApplications #-}
{-# LANGUAGE TypeFamilies #-}
-- SPDX-License-Identifier: Apache-2.0
-- Pure integrity-only reference probe. No script evaluation or ledger transition.
module Kernel (InputBytes(..),exportContext) where
import Cardano.Ledger.Alonzo (mkAlonzoStAnnTx, AlonzoStAnnTx(..))
import Cardano.Ledger.Alonzo.Tx (mkScriptIntegrity,hashScriptIntegrity)
import Cardano.Ledger.Alonzo.PParams (getLanguageView,encodeLangViews)
import Cardano.Ledger.Alonzo.Rules (checkScriptIntegrityHash)
import Cardano.Ledger.Alonzo.TxWits (unTxDatsL)
import Cardano.Ledger.BaseTypes (ProtVer(..),StrictMaybe(..))
import Cardano.Ledger.Binary (DecCBOR(..),decodeFull',decodeFullAnnotator,natVersion,serialize')
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Conway.Scripts (AlonzoScript(..),PlutusScript(..))
import Cardano.Ledger.Conway.Core hiding (Value)
import Cardano.Ledger.Hashes (SafeToHash(..))
import Cardano.Ledger.Plutus.CostModels (costModelsValid,getCostModelParams)
import Cardano.Ledger.Plutus.Language (Language(..),Plutus(..),PlutusBinary(..))
import Cardano.Ledger.State (UTxO(..))
import Cardano.Ledger.TxIn (TxIn)
import Cardano.Slotting.EpochInfo (fixedEpochInfo)
import Cardano.Slotting.Slot (EpochSize(..))
import Cardano.Slotting.Time (SystemStart(..),slotLengthFromSec)
import Data.Time (UTCTime(..),fromGregorian)
import Control.Monad (unless)
import Data.Aeson (Value,object,(.=))
import qualified Data.ByteString as B
import qualified Data.ByteString.Lazy as L
import qualified Data.ByteString.Short as S
import qualified Data.Map.Strict as M
import qualified Data.Set as Set
import qualified Data.Text as T
import Data.Text (Text)
import Lens.Micro ((^.),(&),(.~))
import Numeric (showHex)

data InputBytes = InputBytes B.ByteString B.ByteString B.ByteString [(B.ByteString,B.ByteString)]
check :: Bool -> Text -> Either Text ()
check ok msg = unless ok (Left msg)
decodeOne :: DecCBOR a => B.ByteString -> Either Text a
decodeOne = either (Left . T.pack . show) Right . decodeFull' (natVersion @9)
hex :: B.ByteString -> Text
hex = T.pack . concatMap (\n -> let s=showHex n "" in if length s==1 then '0':s else s) . B.unpack
optional :: StrictMaybe a -> Maybe a
optional SNothing = Nothing
optional (SJust x) = Just x

exportContext :: InputBytes -> Either Text Value
exportContext (InputBytes txBytes ppBytes expectedScript entries) = do
  check (B.length txBytes<=65536 && B.length ppBytes<=65536 && B.length expectedScript==369) "input size bound"
  check (length entries<=3 && all (\(a,b)->B.length a<=128 && B.length b<=4096) entries && sum [B.length a+B.length b | (a,b)<-entries]<=16384) "prestate bound"
  tx <- either (Left . T.pack . show) Right $ decodeFullAnnotator (natVersion @9) "ConwayTx" decCBOR (L.fromStrict txBytes) :: Either Text (Tx TopTx ConwayEra)
  pp <- decodeOne ppBytes :: Either Text (PParams ConwayEra)
  check (pp ^. ppProtocolVersionL == ProtVer (natVersion @9) 0) "requires PV9.0"
  cm <- maybe (Left "missing V3 model") Right (M.lookup PlutusV3 (costModelsValid (pp ^. ppCostModelsL)))
  check (length (getCostModelParams cm)==251) "requires251 costs"
  pairs <- traverse (\(a,b)->(,) <$> (decodeOne a :: Either Text TxIn) <*> (decodeOne b :: Either Text (TxOut ConwayEra))) entries
  let utxo = M.fromList pairs
  check (M.size utxo==length pairs) "duplicate prestate"
  check ((tx ^. bodyTxL . allInputsTxBodyF) `Set.isSubsetOf` M.keysSet utxo) "missing prestate"
  let expected :: Script ConwayEra
      expected = PlutusScript (ConwayPlutusV3 (Plutus (PlutusBinary (S.toShort expectedScript))))
  check (tx ^. witsTxL . scriptTxWitsL == M.singleton (hashScript @ConwayEra expected) expected) "script witness differs from reviewed bytes"
  let ann = mkAlonzoStAnnTx (fixedEpochInfo (EpochSize 1000) (slotLengthFromSec 1)) (SystemStart (UTCTime (fromGregorian 2020 1 1) 0)) pp (UTxO utxo) M.empty tx
      languages = asatPlutusLanguagesUsed ann
      integrity = mkScriptIntegrity pp tx languages
      computed = hashScriptIntegrity <$> integrity
      supplied = tx ^. bodyTxL . scriptIntegrityHashTxBodyL
      r = originalBytes (tx ^. witsTxL . rdmrsTxWitsL)
      dats = tx ^. witsTxL . datsTxWitsL
      datumCount = M.size (dats ^. unTxDatsL)
      datumRaw = originalBytes dats
      d = if datumCount==0 then B.empty else datumRaw
      langBytes = serialize' (natVersion @9) (encodeLangViews (Set.map (getLanguageView pp) languages))
      preimage = originalBytes <$> integrity
      repaired = tx & bodyTxL . scriptIntegrityHashTxBodyL .~ computed
  check (languages==Set.singleton PlutusV3) "actual reference resolution is not exactly V3"
  check (datumCount==0) "witness datums outside probe scope"
  check (optional preimage==Just (r<>d<>langBytes)) "reference integrity domain mismatch"
  pure $ object
    [ "profile" .= ("synthetic-conway-pv9-v3-integrity" :: Text)
    , "decodedOriginalTransaction" .= True
    , "languageResolution" .= ("mkAlonzoStAnnTx/asatPlutusLanguagesUsed" :: Text)
    , "usedLanguages" .= map show (Set.toAscList languages)
    , "redeemerOriginalHex" .= hex r
    , "datumCount" .= datumCount
    , "datumOriginalHex" .= hex datumRaw
    , "datumDomainHex" .= hex d
    , "languageViewHex" .= hex langBytes
    , "integrityPreimageHex" .= fmap hex (optional preimage)
    , "computedCommitmentHex" .= fmap (hex . originalBytes) (optional computed)
    , "suppliedCommitmentHex" .= fmap (hex . originalBytes) (optional supplied)
    , "matches" .= (computed==supplied)
    , "referenceCheckDiagnostic" .= show (checkScriptIntegrityHash tx pp integrity)
    , "repairedTransactionCborHex" .= hex (serialize' (natVersion @9) repaired)
    , "scriptHex" .= hex expectedScript
    , "scriptEvaluationPerformed" .= False
    , "fullLedgerValidation" .= False
    ]
