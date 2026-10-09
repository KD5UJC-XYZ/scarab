{-# LANGUAGE DataKinds #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE PatternSynonyms #-}
{-# LANGUAGE TypeApplications #-}
{-# LANGUAGE TypeFamilies #-}
-- SPDX-License-Identifier: Apache-2.0
-- Source-only draft: not compiled or run. This module performs no IO.
module PacketBuilder
  ( Packet(..), buildPacket, buildNonSpendingPacket, negativePackets, frozenCosts, sourceScriptSha256
  ) where

import Cardano.Ledger.Address (Addr(..), AccountAddress(..), AccountId(..), Withdrawals(..))
import Cardano.Ledger.Alonzo.Scripts (AsIx(..), pattern SpendingPurpose, pattern WithdrawingPurpose)
import Cardano.Ledger.Alonzo.Tx (hashScriptIntegrity, mkScriptIntegrity)
import Cardano.Ledger.Alonzo.TxWits (Redeemers(..))
import Cardano.Ledger.BaseTypes (Network(..), ProtVer(..))
import Cardano.Ledger.Binary (DecCBOR, decodeFull', natVersion, serialize')
import Cardano.Ledger.Coin (Coin(..))
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Conway.Core
import Cardano.Ledger.Conway.Scripts (AlonzoScript(..), PlutusScript(..))
import Cardano.Ledger.Credential (Credential(..), StakeReference(..))
import Cardano.Ledger.Keys (KeyHash, KeyRole(Payment))
import Cardano.Ledger.Plutus.CostModels (mkCostModel, mkCostModels)
import qualified Cardano.Ledger.Plutus.Data as D
import Cardano.Ledger.Plutus.ExUnits (ExUnits(..))
import Cardano.Ledger.Plutus.Language (Language(..), Plutus(..), PlutusBinary(..))
import Cardano.Ledger.TxIn (TxIn)
import qualified Data.ByteString as B
import qualified Data.ByteString.Short as S
import Data.Int (Int64)
import qualified Data.Map.Strict as M
import qualified Data.Set as Set
import GHC.Exts (fromList)
import Lens.Micro ((&), (.~))
import qualified PlutusLedgerApi.Common as P
import qualified PlutusLedgerApi.V3 as V3

-- The caller admits the exact serialized script digest before invoking buildPacket.
-- This is the hash of the reviewed textual source, NOT the serialized script hash.
sourceScriptSha256 :: String
sourceScriptSha256 = "1129132bf56b79e2492e88a096d816d695e28131b29ff7218d2362078146062e"

data Packet = Packet
  { transactionBytes :: B.ByteString
  , parameterBytes :: B.ByteString
  , reviewedScriptBytes :: B.ByteString
  , preStateEntries :: [(B.ByteString, B.ByteString)]
  } deriving (Eq, Show)

decodeMarker :: DecCBOR a => B.ByteString -> Either String a
decodeMarker = either (Left . show) Right . decodeFull' (natVersion @9)

-- Original synthetic IDs are deliberately decoded from literal CBOR. They do
-- not identify real transactions or keys; no signatures or keys are generated.
markerInput :: Int -> Either String TxIn
markerInput n = decodeMarker (B.pack [0x82,0x58,0x20] <> B.replicate 32 (fromIntegral n) <> B.singleton 0)

parameters :: [Int64] -> Either String (PParams ConwayEra)
parameters costs = do
  cm <- either (Left . show) Right (mkCostModel PlutusV3 costs)
  pure $ emptyPParams
    & ppProtocolVersionL .~ ProtVer (natVersion @9) 0
    & ppCostModelsL .~ mkCostModels (M.singleton PlutusV3 cm)
    & ppMaxTxSizeL .~ 65536
    & ppMaxTxExUnitsL .~ ExUnits 14000000 10000000000
    & ppMaxBlockExUnitsL .~ ExUnits 14000000 10000000000

-- Must receive the exact ledger PlutusBinary payload: one CBOR byte-string
-- wrapping the Flat-encoded de-Bruijn program, not a text envelope or double CBOR.
-- The V3 deserializer is an independent wrapping/Flat compatibility check.
buildPacket :: B.ByteString -> Either String Packet
buildPacket = buildWithPurpose False

-- Negative control: the same binary is attached to a withdrawal instead.
-- A real RewardingScript context must be rejected by the helper before evaluation.
buildNonSpendingPacket :: B.ByteString -> Either String Packet
buildNonSpendingPacket = buildWithPurpose True

buildWithPurpose :: Bool -> B.ByteString -> Either String Packet
buildWithPurpose nonSpending scriptBytes = do
  if B.null scriptBytes || B.length scriptBytes > 65536
    then Left "serialized script must contain 1..65536 bytes"
    else pure ()
  if scriptBytes /= admittedScriptBytes then Left "unregistered serialized spending script" else pure ()
  _ <- either (Left . show) Right $
    V3.deserialiseScript (P.MajorProtocolVersion 9) (S.toShort scriptBytes)
  ownRef <- markerInput 0x11
  collateralRef <- markerInput 0x55
  beneficiary <- decodeMarker (B.pack [0x58,0x1c] <> B.replicate 28 0x22)
    :: Either String (KeyHash Payment)
  pp <- parameters frozenCosts
  let script :: Script ConwayEra
      script = PlutusScript (ConwayPlutusV3 (Plutus (PlutusBinary (S.toShort scriptBytes))))
      sh = hashScript @ConwayEra script
      scriptAddress = Addr Testnet (ScriptHashObj sh) StakeRefNull
      paymentAddress = Addr Testnet (KeyHashObj beneficiary) StakeRefNull
      datum = V3.Constr 0 [V3.B (B.replicate 28 0x22), V3.I 2000000]
      ownOutput :: TxOut ConwayEra
      ownOutput = mkCoinTxOut (if nonSpending then paymentAddress else scriptAddress) (Coin 5000000)
        & datumTxOutL .~ D.mkInlineDatum datum
      paymentOutput :: TxOut ConwayEra
      paymentOutput = mkCoinTxOut paymentAddress (Coin 3000000)
      collateralOutput :: TxOut ConwayEra
      collateralOutput = mkCoinTxOut paymentAddress (Coin 10000000)
      body :: TxBody TopTx ConwayEra
      body = mkBasicTxBody
        & inputsTxBodyL .~ Set.singleton ownRef
        & collateralInputsTxBodyL .~ Set.singleton collateralRef
        & outputsTxBodyL .~ fromList [paymentOutput]
        & feeTxBodyL .~ Coin (if nonSpending then 2000001 else 2000000)
        & withdrawalsTxBodyL .~ Withdrawals (if nonSpending
            then M.singleton (AccountAddress Testnet (AccountId (ScriptHashObj sh))) (Coin 1)
            else M.empty)
      witnesses :: TxWits ConwayEra
      witnesses = mkBasicTxWits
        & scriptTxWitsL .~ M.singleton sh script
        & rdmrsTxWitsL .~ Redeemers (M.singleton (if nonSpending then WithdrawingPurpose (AsIx 0) else SpendingPurpose (AsIx 0))
            (D.Data (V3.I 7), ExUnits 100000 30000000))
      unsigned :: Tx TopTx ConwayEra
      unsigned = mkBasicTx body & witsTxL .~ witnesses
      tx :: Tx TopTx ConwayEra
      tx = unsigned & bodyTxL . scriptIntegrityHashTxBodyL .~
        (hashScriptIntegrity <$> mkScriptIntegrity pp unsigned (Set.singleton PlutusV3))
      encode = serialize' (natVersion @9)
  pure Packet
    { transactionBytes = encode tx
    , parameterBytes = serialize' (natVersion @9) pp
    , reviewedScriptBytes = scriptBytes
    , preStateEntries =
        [ (serialize' (natVersion @9) ownRef, serialize' (natVersion @9) ownOutput)
        , (serialize' (natVersion @9) collateralRef, serialize' (natVersion @9) collateralOutput)
        ]
    }

-- Derived rejection probes, not independently captured reference fixtures.
-- The orchestrator MUST give every variant its own manifest/file hashes, so it
-- reaches the intended kernel rejection instead of failing only SHA admission.
negativePackets :: Packet -> Either String [(String, Packet)]
negativePackets base = do
  pv9 <- parameters frozenCosts
  shortModelBytes <- replaceUnique
    (serialize' (natVersion @9) frozenCosts)
    (serialize' (natVersion @9) (take 250 frozenCosts))
    (parameterBytes base)
  let pv10 = pv9 & ppProtocolVersionL .~ ProtVer (natVersion @10) 0
      missingModel = pv9 & ppCostModelsL .~ mkCostModels M.empty
      duplicate = case preStateEntries base of
        x:xs -> x:x:xs
        [] -> []
  pure
    [ ("malformed-transaction", base {transactionBytes = B.singleton 0xff})
    , ("trailing-transaction", base {transactionBytes = transactionBytes base <> B.singleton 0})
    , ("missing-prestate", base {preStateEntries = []})
    , ("duplicate-prestate", base {preStateEntries = duplicate})
    , ("wrong-protocol", base {parameterBytes = serialize' (natVersion @9) pv10})
    , ("missing-model", base {parameterBytes = serialize' (natVersion @9) missingModel})
    , ("short-model-raw-cbor", base {parameterBytes = shortModelBytes})
    , ("script-mismatch", base {reviewedScriptBytes = reviewedScriptBytes base <> B.singleton 0})
    ]

-- Raw codec mutation: CostModels serializes its map values as ordinary [Int64].
-- Replace the unique exact serialized 251-value list with the serialized prefix.
-- No mkCostModel call can cause the short-model negative to abort baseline build.
replaceUnique :: B.ByteString -> B.ByteString -> B.ByteString -> Either String B.ByteString
replaceUnique needle replacement original =
  let (prefix, suffix) = B.breakSubstring needle original
      rest = B.drop (B.length needle) suffix
  in if B.null needle || B.null suffix || needle `B.isInfixOf` rest
     then Left "cost-list raw mutation requires exactly one byte-identical occurrence"
     else Right (prefix <> replacement <> rest)

-- Exact signed decimal values copied, in order, from the pinned local model.
-- model JSON SHA256: 6ab455d588e186649a6aae2761fec85ae5a2647cb002a2acb737604f698b21a2
frozenCosts :: [Int64]
frozenCosts = [ 100788, 420, 1, 1, 1000, 173, 0, 1, 1000, 59957, 4, 1, 11183, 32, 201305, 8356, 4, 16000, 100, 16000, 100, 16000, 100, 16000, 100, 16000, 100, 16000, 100, 100, 100, 16000, 100, 94375, 32, 132994, 32, 61462, 4, 72010, 178, 0, 1, 22151, 32, 91189, 769, 4, 2, 85848, 123203, 7305, -900, 1716, 960, 57, 85848, 0, 1, 1, 1000, 42921, 4, 2, 30623, 28755, 75, 1, 898148, 27279, 1, 51775, 558, 1, 39184, 1000, 60594, 1, 141895, 32, 83150, 32, 15299, 32, 76049, 1, 13169, 4, 22100, 10, 28999, 74, 1, 28999, 74, 1, 43285, 552, 1, 44749, 541, 1, 33852, 32, 68246, 32, 72362, 32, 7243, 32, 7391, 32, 11546, 32, 85848, 123203, 7305, -900, 1716, 960, 57, 85848, 0, 1, 90434, 519, 0, 1, 74433, 32, 85848, 123203, 7305, -900, 1716, 960, 57, 85848, 0, 1, 1, 85848, 123203, 7305, -900, 1716, 960, 57, 85848, 0, 1, 955506, 213312, 0, 2, 270652, 22588, 4, 1457325, 64566, 4, 20467, 1, 4, 0, 141992, 32, 100788, 420, 1, 1, 81663, 32, 59498, 32, 20142, 32, 24588, 32, 20744, 32, 25933, 32, 24623, 32, 43053543, 10, 53384111, 14333, 10, 43574283, 26308, 10, 16000, 100, 16000, 100, 962335, 18, 2780678, 6, 442008, 1, 52538055, 3756, 18, 267929, 18, 76433006, 8868, 18, 52948122, 18, 1995836, 36, 3227919, 12, 901022, 1, 166917843, 4307, 36, 284546, 36, 158221314, 26549, 36, 74698472, 36, 333849714, 1, 254006273, 72, 2174038, 72, 2261318, 64571, 4, 207616, 8310, 4, 1293828, 28716, 63, 0, 1, 1006041, 43623, 251, 0, 1 ]

-- Independently byte-admitted serialization of the exact source above.
-- ledgerScript SHA256: 57fb50f08ffc1222cbe2b652db3dcfed0f714da98f8170cb104aee2bde4070f6
admittedScriptBytes :: B.ByteString
admittedScriptBytes = B.pack [ 89, 1, 110, 1, 0, 0, 35, 35, 35, 37, 51, 53, 115, 70, 110, 28, 213, 92, 233, 186, 160, 1, 72, 0, 132, 200, 200, 200, 200, 200, 200, 200, 200, 200, 200, 201, 76, 205, 92, 209, 155, 135, 53, 87, 58, 110, 168, 2, 210, 0, 1, 83, 51, 87, 52, 106, 232, 205, 93, 16, 4, 138, 153, 154, 185, 163, 87, 70, 106, 232, 128, 32, 84, 204, 213, 205, 25, 186, 243, 87, 66, 106, 174, 120, 221, 80, 6, 26, 186, 19, 85, 115, 198, 234, 128, 28, 84, 204, 213, 205, 25, 184, 115, 85, 115, 166, 234, 128, 21, 32, 4, 21, 51, 53, 115, 70, 110, 188, 2, 141, 93, 9, 170, 185, 227, 117, 64, 10, 42, 102, 106, 230, 140, 220, 57, 170, 185, 211, 117, 64, 8, 144, 0, 10, 153, 154, 185, 163, 55, 94, 106, 232, 77, 85, 207, 27, 170, 0, 163, 87, 66, 106, 174, 120, 221, 80, 2, 10, 153, 154, 185, 163, 55, 14, 110, 180, 3, 82, 0, 225, 83, 51, 87, 52, 102, 226, 77, 214, 154, 186, 19, 87, 68, 106, 174, 120, 221, 80, 5, 0, 8, 164, 194, 194, 194, 194, 194, 194, 194, 194, 194, 194, 198, 235, 77, 85, 207, 0, 9, 171, 161, 55, 86, 106, 174, 120, 0, 77, 93, 9, 186, 179, 87, 66, 106, 232, 141, 85, 207, 27, 170, 0, 51, 87, 66, 106, 174, 120, 221, 81, 171, 161, 53, 87, 60, 110, 168, 0, 141, 93, 9, 171, 162, 53, 116, 70, 170, 231, 141, 213, 26, 186, 19, 87, 68, 106, 174, 120, 221, 80, 1, 26, 186, 16, 2, 53, 116, 32, 4, 110, 176, 213, 208, 154, 186, 35, 87, 68, 106, 174, 120, 221, 80, 3, 27, 172, 53, 116, 38, 170, 231, 141, 213, 0, 41, 171, 161, 53, 87, 60, 110, 168, 0, 77, 93, 9, 171, 162, 53, 87, 60, 110, 168, 0, 69, 141, 93, 9, 171, 162, 53, 116, 70, 170, 231, 141, 213, 0, 25, 171, 161, 53, 116, 70, 170, 231, 141, 213, 0, 17, 171, 161, 53, 87, 60, 110, 168, 0, 65 ]
