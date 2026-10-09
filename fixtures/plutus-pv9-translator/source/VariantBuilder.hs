{-# LANGUAGE DataKinds #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE PatternSynonyms #-}
{-# LANGUAGE TypeApplications #-}
{-# LANGUAGE TypeFamilies #-}
-- SPDX-License-Identifier: Apache-2.0
-- Pure bounded synthetic generator; no IO. Source-only until parent compile gate.
module VariantBuilder (variants) where
import PacketBuilder (Packet(..), buildPacket)
import Cardano.Ledger.Address (Addr(..))
import Cardano.Ledger.Alonzo.Scripts (AsIx(..), pattern SpendingPurpose)
import Cardano.Ledger.Alonzo.Tx (hashScriptIntegrity, mkScriptIntegrity)
import Cardano.Ledger.Alonzo.TxWits (Redeemers(..))
import Cardano.Ledger.BaseTypes (Network(..), StrictMaybe(..))
import Cardano.Ledger.Binary (DecCBOR(..), decodeFull', decodeFullAnnotator, natVersion, serialize')
import Cardano.Ledger.Coin (Coin(..))
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Conway.Core hiding (Value)
import Cardano.Ledger.Credential (Credential(..), StakeReference(..))
import Cardano.Ledger.Keys (KeyHash, KeyRole(Payment))
import qualified Cardano.Ledger.Plutus.Data as D
import Cardano.Ledger.Plutus.ExUnits (ExUnits(..))
import Cardano.Ledger.Plutus.Language (Language(..))
import Cardano.Ledger.TxIn (TxIn)
import Cardano.Slotting.Slot (SlotNo(..))
import Data.Aeson (Value, object, (.=))
import qualified Data.ByteString as B
import qualified Data.ByteString.Lazy as L
import qualified Data.Map.Strict as M
import qualified Data.Set as Set
import Data.Word (Word64)
import GHC.Exts (fromList)
import Lens.Micro ((&), (.~), (^.))
import qualified PlutusLedgerApi.V3 as V3

-- All amounts are lovelace; IDs/credentials consist of a repeated unsigned byte.
-- The record constructor is not exported: no arbitrary-case generator API.
data Spec = Spec
  { name :: String, beneficiary :: Int, minimumPaid :: Integer
  , ownByte :: Int, ownIndex :: Int, ownAmount :: Integer
  , extraKey :: Maybe (Int,Int), fee :: Integer, outputPair :: Bool
  , reversed :: Bool, redeemer :: Integer, lower :: Maybe Word64, upper :: Maybe Word64
  }
base :: Spec
base = Spec "base" 0x22 2000000 0x11 0 5000000 Nothing 2000000 False False 7 Nothing Nothing
cases :: [Spec]
cases =
  [ base
  , base {name="beneficiary-minimum-payment", beneficiary=0x33, minimumPaid=1000000, fee=1000000}
  , base {name="input-id-index-amount", ownByte=0x66, ownIndex=3, ownAmount=7000000}
  , base {name="fee-payment", fee=1000000}
  , base {name="key-before-7f-80", ownByte=0x80, extraKey=Just (0x7f,0)}
  , base {name="key-after-7f-80", ownByte=0x7f, extraKey=Just (0x80,0)}
  , base {name="same-id-key-index-before", ownByte=0x7f, ownIndex=1, extraKey=Just (0x7f,0)}
  , base {name="same-id-key-index-after", ownByte=0x7f, ownIndex=0, extraKey=Just (0x7f,1)}
  , base {name="output-pair", outputPair=True}
  , base {name="output-pair-reversed", outputPair=True, reversed=True}
  , base {name="redeemer-eight", redeemer=8}
  , base {name="interval-lower", lower=Just 10}
  , base {name="interval-upper", upper=Just 20}
  , base {name="interval-both", lower=Just 10, upper=Just 20}
  , base {name="interval-equal", lower=Just 10, upper=Just 10}
  , base {name="interval-domain-endpoints", lower=Just 0, upper=Just 1000000}
  , base {name="interval-lower-domain-end", lower=Just 1000000}
  , base {name="interval-upper-domain-start", upper=Just 0}
  ]

decodeOne :: DecCBOR a => B.ByteString -> Either String a
decodeOne = either (Left . show) Right . decodeFull' (natVersion @9)
-- Indices deliberately limited to 0..23, so this explicit original CBOR is canonical.
marker :: Int -> Int -> Either String TxIn
marker byte ix
  | byte<0 || byte>255 || ix<0 || ix>23 = Left "marker outside closed generator domain"
  | otherwise = decodeOne (B.pack [0x82,0x58,0x20] <> B.replicate 32 (fromIntegral byte) <> B.singleton (fromIntegral ix))
keyAddress :: Int -> Either String Addr
keyAddress byte = do
  key <- decodeOne (B.pack [0x58,0x1c] <> B.replicate 28 (fromIntegral byte)) :: Either String (KeyHash Payment)
  pure (Addr Testnet (KeyHashObj key) StakeRefNull)

variants :: B.ByteString -> Either String [(String, Value, Packet)]
variants script = do
  admitted <- buildPacket script
  traverse (buildVariant admitted) cases

buildVariant :: Packet -> Spec -> Either String (String,Value,Packet)
buildVariant admitted spec = do
  tx <- either (Left . show) Right $ decodeFullAnnotator (natVersion @9) "ConwayTx" decCBOR (L.fromStrict (transactionBytes admitted)) :: Either String (Tx TopTx ConwayEra)
  pp <- decodeOne (parameterBytes admitted) :: Either String (PParams ConwayEra)
  oldEntries <- traverse (\(a,b) -> (,) <$> (decodeOne a :: Either String TxIn) <*> (decodeOne b :: Either String (TxOut ConwayEra))) (preStateEntries admitted)
  (oldOwn,collateral) <- case oldEntries of
    [(_,o),c] -> Right (o,c)
    _ -> Left "approved baseline prestate shape changed"
  own <- marker (ownByte spec) (ownIndex spec)
  extra <- traverse (uncurry marker) (extraKey spec)
  payAddr <- keyAddress (beneficiary spec)
  otherAddr <- keyAddress 0x44
  let scriptAddr = oldOwn ^. addrTxOutL
      newOwn = mkCoinTxOut scriptAddr (Coin (ownAmount spec))
        & datumTxOutL .~ D.mkInlineDatum (V3.Constr 0 [V3.B (B.replicate 28 (fromIntegral (beneficiary spec))), V3.I (minimumPaid spec)])
      extraEntries = maybe [] (\ref -> [(ref,mkCoinTxOut otherAddr (Coin 1000000))]) extra
      entries = (own,newOwn):extraEntries ++ [collateral]
      inputs = Set.fromList (own:maybe [] (:[]) extra)
      payment = ownAmount spec + maybe 0 (const 1000000) extra - fee spec
      outputs = if outputPair spec
        then [mkCoinTxOut payAddr (Coin (payment-1000000)),mkCoinTxOut otherAddr (Coin 1000000)]
        else [mkCoinTxOut payAddr (Coin payment)]
      pointer = Set.findIndex own inputs
      bounds = ValidityInterval (maybe SNothing (SJust . SlotNo) (lower spec)) (maybe SNothing (SJust . SlotNo) (upper spec))
      body = (tx ^. bodyTxL)
        & inputsTxBodyL .~ inputs
        & outputsTxBodyL .~ fromList (if reversed spec then reverse outputs else outputs)
        & feeTxBodyL .~ Coin (fee spec)
        & vldtTxBodyL .~ bounds
        & scriptIntegrityHashTxBodyL .~ SNothing
      witnesses = (tx ^. witsTxL)
        & rdmrsTxWitsL .~ Redeemers (M.singleton (SpendingPurpose (AsIx (fromIntegral pointer))) (D.Data (V3.I (redeemer spec)),ExUnits 100000 30000000))
      unsigned = tx & bodyTxL .~ body & witsTxL .~ witnesses
      finalTx = unsigned & bodyTxL . scriptIntegrityHashTxBodyL .~ (hashScriptIntegrity <$> mkScriptIntegrity pp unsigned (Set.singleton PlutusV3))
      encode = serialize' (natVersion @9)
      packet = Packet (encode finalTx) (parameterBytes admitted) (reviewedScriptBytes admitted)
        [(serialize' (natVersion @9) a,serialize' (natVersion @9) b) | (a,b)<-entries]
      metadata = object
        [ "beneficiaryByte" .= beneficiary spec, "minimumPaid" .= show (minimumPaid spec)
        , "ownTransactionByte" .= ownByte spec, "ownIndex" .= ownIndex spec, "ownAmount" .= show (ownAmount spec)
        , "extraKeyInput" .= fmap (\(b,i) -> object ["transactionByte" .= b,"index" .= i,"amount" .= ("1000000" :: String),"credentialByte" .= (0x44 :: Int)]) (extraKey spec)
        , "fee" .= show (fee spec), "paymentTotal" .= show payment
        , "outputPair" .= outputPair spec, "reverseOutputs" .= reversed spec
        , "redeemer" .= show (redeemer spec), "spendingPointer" .= pointer
        , "lowerSlot" .= fmap show (lower spec), "upperSlot" .= fmap show (upper spec)
        , "intendedScriptSuccess" .= (extraKey spec==Nothing && not (outputPair spec) && redeemer spec==7)
        , "fullLedgerValidation" .= False
        ]
  if Set.size inputs /= 1+length extraEntries || any ((==fst collateral).fst) ((own,newOwn):extraEntries)
    then Left "duplicate generated input"
    else if payment <= 1000000 then Left "nonpositive generated payment partition"
    else pure (name spec,metadata,packet)
