-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE AllowAmbiguousTypes #-}
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE DeriveGeneric #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

import Cardano.Ledger.Address (AccountAddress, Addr, Withdrawals (..), serialiseAccountAddress)
import Cardano.Ledger.Allegra (AllegraEra)
import Cardano.Ledger.Alonzo (AlonzoEra)
import Cardano.Ledger.Babbage (BabbageEra)
import qualified Cardano.Ledger.Binary as Binary
import Cardano.Ledger.Coin (Coin (..))
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Conway.Governance (GovActionId (..), GovActionIx (..))
import Cardano.Ledger.Core
  ( BlockBody
  , EraBlockBody (hashBlockBody, txSeqBlockBodyL)
  , EraTx (bodyTxL, witsTxL)
  , EraTxBody
  , EraTxWits (addrTxWitsL, bootAddrTxWitsL)
  , Tx
  , TopTx
  , TxAuxData
  , TxBody
  , TxWits
  , txIdTxBody
  , withdrawalsTxBodyL
  )
import qualified Cardano.Crypto.Hash as Hash
import Cardano.Ledger.Hashes (KeyHash (..), extractHash)
import Cardano.Ledger.Keys (BootstrapWitness, VKey (..), bootstrapWitKeyHash)
import Cardano.Ledger.Shelley.Rules (ShelleyUtxowPredFailure (InvalidWitnessesUTXOW), validateVerifiedWits)
import Cardano.Ledger.TxIn (TxId (..))
import Data.Foldable (toList)
import Cardano.Ledger.Mary (MaryEra)
import Cardano.Ledger.Shelley (ShelleyEra)
import Data.Aeson (FromJSON, Value, eitherDecode, encode, object, (.=))
import Data.Aeson.Types (Pair)
import qualified Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy as LBS
import qualified Data.Map.Strict as Map
import qualified Data.Set as Set
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import GHC.Generics (Generic)
import Lens.Micro ((^.))
import System.Environment (getArgs)
import System.Exit (die)
import Validation (Validation (..))

-- | One original encoding: `kind` is "body", "witnesses" or "transaction" (Allegra to Conway);
-- `era` is an era name, decoded at its `eraProtVerLow` (Shelley 2, Allegra 3, Mary 4, Alonzo 5,
-- Babbage 7, Conway 9). With `withdrawals` set, an accepted body also reports the decoded withdrawals.
-- A case with `ids` set is read by the kinds of `identified` instead: "body", "witnesses",
-- "transaction" and "auxiliary" of every era, and "blockbody" (the parts of a block after its
-- header, one after the other); an accepted one also reports the ids it hashes from the bytes it
-- read (#687). With `wire` set as well, an accepted transaction also reports the bytes the node
-- relays for it: its `encCBOR` at the era's version, which consensus wraps in CBOR for transaction
-- submission (`toCBOR (ShelleyTx _ tx) = wrapCBORinCBOR toCBOR tx`) and measures (`wireSizeTxF`)
-- (#699). A case of `kind` "address" or "account" is the CBOR byte string of an address, read alone
-- with `DecCBOR Addr` (`fromCborAddr`) or `DecCBOR AccountAddress` (`fromCborAccountAddress`) at the
-- era's version, the readers of an output's address and of a reward account (#723).
-- Kind "govactionids" is the set of governance action ids that the node-to-client
-- query `GetProposals` reads (#700).
-- Kind "bootstrap" is one bootstrap witness read alone with its `DecCBOR` at `decoderVersion`, and an
-- accepted one reports its `bootstrapWitKeyHash`; with `bootstraps` set, an accepted witness set or
-- whole transaction read by `identified` reports the `bootstrapWitKeyHash` of each bootstrap witness
-- it holds, in the order of its set.
-- With `kept` set, an accepted witness set or whole transaction read by `identified` also reports the
-- `encCBOR` of each vkey and bootstrap witness its sets hold, in the order of each set; with `verify`
-- set, an accepted whole transaction also reports the verdict of `validateVerifiedWits`
-- (`Shelley/Rules/Utxow.hs:391-407`), the signature check UTXOW runs in every era from Shelley to
-- Conway, and the key of each witness it found invalid. A `decoderVersion` given to a case read by
-- `identified` replaces the era's version.
data Case = Case
  { name :: String
  , era :: String
  , kind :: String
  , bytes :: Text.Text
  , withdrawals :: Maybe Bool
  , ids :: Maybe Bool
  , wire :: Maybe Bool
  , decoderVersion :: Maybe Integer
  , bootstraps :: Maybe Bool
  , kept :: Maybe Bool
  , verify :: Maybe Bool
  }
  deriving (Generic)

instance FromJSON Case

verdict :: Either Binary.DecoderError a -> Value
verdict (Right _) = object ["accepted" .= True]
verdict (Left failure) = object ["accepted" .= False, "failure" .= show failure]

decodeAs :: forall a. Binary.DecCBOR (Binary.Annotator a) => Binary.Version -> String -> LBS.ByteString -> Value
decodeAs version label raw = verdict (Binary.decodeFullAnnotator version (Text.pack label) Binary.decCBOR raw :: Either Binary.DecoderError a)

-- | The verdict on a body and, when it is accepted, its withdrawals as decoded: each reward account
-- (hex of its 29 bytes) with its amount, in the map's order.
bodyWithdrawals :: forall era. EraTxBody era => Binary.Version -> String -> LBS.ByteString -> Value
bodyWithdrawals version label raw =
  case Binary.decodeFullAnnotator version (Text.pack label) Binary.decCBOR raw :: Either Binary.DecoderError (TxBody TopTx era) of
    Left failure -> verdict (Left failure :: Either Binary.DecoderError ())
    Right body ->
      object
        [ "accepted" .= True
        , "withdrawals" .= [ (Text.decodeUtf8 (Hex.encode (serialiseAccountAddress k)), unCoin v)
                           | (k, v) <- Map.toList (unWithdrawals (body ^. withdrawalsTxBodyL)) ]
        ]

hex :: Hash.Hash h a -> Text.Text
hex = Hash.hashToTextAsHex

-- | The address root a bootstrap witness provides (`bootstrapWitKeyHash`), as hex.
keyHashOf :: BootstrapWitness -> Text.Text
keyHashOf w = case bootstrapWitKeyHash w of KeyHash h -> hex h

-- | The verdict on one bootstrap witness read alone at `version` and, when accepted, its address root.
bootstrapAlone :: Binary.Version -> LBS.ByteString -> Value
bootstrapAlone version raw =
  case Binary.decodeFull version raw :: Either Binary.DecoderError BootstrapWitness of
    Left failure -> verdict (Left failure :: Either Binary.DecoderError ())
    Right w -> object ["accepted" .= True, "keyHash" .= keyHashOf w]

txIdHex :: TxId -> Text.Text
txIdHex (TxId h) = hex (extractHash h)

-- | The verdict and, when accepted, the transaction id of a body (the hash of the bytes the decoder
-- read), of a whole transaction (its body's), or the block body hash and the id of each
-- transaction of a block body. With `relayed` set, an accepted transaction also reports its `encCBOR`.
idsOf ::
  forall era.
  ( EraBlockBody era
  , Binary.EncCBOR (Tx TopTx era)
  , Binary.DecCBOR (Binary.Annotator (TxBody TopTx era))
  , Binary.DecCBOR (Binary.Annotator (TxWits era))
  , Binary.DecCBOR (Binary.Annotator (TxAuxData era))
  ) =>
  Binary.Version -> Bool -> Bool -> Bool -> Bool -> String -> String -> LBS.ByteString -> Value
idsOf version relayed boot keep check label k raw = case k of
  "body" -> withIds (\b -> ["id" .= txIdHex (txIdTxBody (b :: TxBody TopTx era))])
  "transaction" ->
    withIds
      ( \t ->
          ("id" .= txIdHex (txIdTxBody ((t :: Tx TopTx era) ^. bodyTxL)))
            : ["wire" .= Text.decodeUtf8 (Hex.encode (LBS.toStrict (Binary.serialize version t))) | relayed]
            ++ ["bootstrap" .= map keyHashOf (Set.toList (t ^. witsTxL . bootAddrTxWitsL)) | boot]
            ++ (if keep then keptOf (t ^. witsTxL) else [])
            ++ (if check then verifiedOf t else [])
      )
  "witnesses" ->
    withIds
      ( \(w :: TxWits era) ->
          ["bootstrap" .= map keyHashOf (Set.toList (w ^. bootAddrTxWitsL)) | boot]
            ++ (if keep then keptOf w else [])
      )
  "auxiliary" -> withIds (\(_ :: TxAuxData era) -> [])
  "blockbody" ->
    withIds
      ( \b ->
          [ "hash" .= hex (hashBlockBody (b :: BlockBody era))
          , "ids" .= [txIdHex (txIdTxBody (t ^. bodyTxL)) | t <- toList (b ^. txSeqBlockBodyL)]
          ]
      )
  _ -> object ["unsupported" .= k]
  where
    encoded :: forall a. Binary.EncCBOR a => a -> Text.Text
    encoded = Text.decodeUtf8 . Hex.encode . LBS.toStrict . Binary.serialize version
    keptOf :: TxWits era -> [Pair]
    keptOf w =
      [ "keptVkeys" .= map encoded (Set.toList (w ^. addrTxWitsL))
      , "keptBootstraps" .= map encoded (Set.toList (w ^. bootAddrTxWitsL))
      ]
    verifiedOf :: Tx TopTx era -> [Pair]
    verifiedOf t = case validateVerifiedWits t of
      Success () -> ["verified" .= True]
      Failure failures -> ["verified" .= False, "invalidKeys" .= concatMap invalidKeys (toList failures)]
    invalidKeys :: ShelleyUtxowPredFailure era -> [Text.Text]
    invalidKeys failure = case failure of
      InvalidWitnessesUTXOW keys -> [Text.decodeUtf8 (Hex.encode (Binary.rawEncodeFixedSized vk)) | VKey vk <- toList keys]
      _ -> ["unexpected failure"]
    withIds :: forall a. Binary.DecCBOR (Binary.Annotator a) => (a -> [Pair]) -> Value
    withIds report =
      case Binary.decodeFullAnnotator version (Text.pack label) Binary.decCBOR raw :: Either Binary.DecoderError a of
        Left failure -> verdict (Left failure :: Either Binary.DecoderError ())
        Right a -> object (("accepted" .= True) : report a)

-- | The era's decoder version, for the cases read without an era's types.
versionOf :: String -> Maybe Binary.Version
versionOf e = case e of
  "shelley" -> Just (Binary.natVersion @2)
  "allegra" -> Just (Binary.natVersion @3)
  "mary" -> Just (Binary.natVersion @4)
  "alonzo" -> Just (Binary.natVersion @5)
  "babbage" -> Just (Binary.natVersion @7)
  "conway" -> Just (Binary.natVersion @9)
  _ -> Nothing

-- | The verdict on an address or a reward account read alone; `Nothing` for another kind.
addressed :: String -> Binary.Version -> LBS.ByteString -> Maybe Value
addressed k version raw = case k of
  "address" -> Just (verdict (Binary.decodeFull version raw :: Either Binary.DecoderError Addr))
  "account" -> Just (verdict (Binary.decodeFull version raw :: Either Binary.DecoderError AccountAddress))
  _ -> Nothing

-- | The verdict on a set of governance action ids as `GetProposals` reads it, with
-- `LC.fromEraCBOR @era` at Conway's lowest protocol version, 9 (`Shelley/Ledger/Query.hs:981`,
-- ouroboros-consensus 82ecba32) and, when accepted, each id as decoded, `txid#index` (#700).
govActionIds :: LBS.ByteString -> Value
govActionIds raw =
  case Binary.decodeFull (Binary.natVersion @9) raw :: Either Binary.DecoderError (Set.Set GovActionId) of
    Left failure -> verdict (Left failure :: Either Binary.DecoderError ())
    Right set ->
      object
        [ "accepted" .= True
        , "actions" .= [ txIdHex t <> "#" <> Text.pack (show (unGovActionIx i))
                       | GovActionId t i <- Set.toList set ]
        ]

identified :: Case -> LBS.ByteString -> Maybe Value
identified c raw = case era c of
  "shelley" -> Just (idsOf @ShelleyEra (at (Binary.natVersion @2)) relayed boot keep check "Shelley" (kind c) raw)
  "allegra" -> Just (idsOf @AllegraEra (at (Binary.natVersion @3)) relayed boot keep check "Allegra" (kind c) raw)
  "mary" -> Just (idsOf @MaryEra (at (Binary.natVersion @4)) relayed boot keep check "Mary" (kind c) raw)
  "alonzo" -> Just (idsOf @AlonzoEra (at (Binary.natVersion @5)) relayed boot keep check "Alonzo" (kind c) raw)
  "babbage" -> Just (idsOf @BabbageEra (at (Binary.natVersion @7)) relayed boot keep check "Babbage" (kind c) raw)
  "conway" -> Just (idsOf @ConwayEra (at (Binary.natVersion @9)) relayed boot keep check "Conway" (kind c) raw)
  _ -> Nothing
  where
    relayed = wire c == Just True
    boot = bootstraps c == Just True
    keep = kept c == Just True
    check = verify c == Just True
    at v = maybe v id (decoderVersion c >>= Binary.mkVersion)

runCase :: Case -> IO Value
runCase c = do
  raw <- either die (pure . LBS.fromStrict) (Hex.decode (Text.encodeUtf8 (bytes c)))
  let body_ = kind c == "body"
      wits_ = kind c == "witnesses"
      wdrl = body_ && withdrawals c == Just True
  result <- case (ids c, identified c raw, versionOf (era c) >>= \v -> addressed (kind c) v raw) of
   _ | kind c == "govactionids" -> pure (govActionIds raw)
   _ | kind c == "bootstrap" ->
         maybe (die "a bootstrap case needs its decoderVersion") (pure . (`bootstrapAlone` raw))
           (decoderVersion c >>= Binary.mkVersion)
   (Just True, Just v, _) -> pure v
   (_, _, Just v) -> pure v
   _ -> case (era c, kind c) of
       ("conway", _) | wdrl -> pure (bodyWithdrawals @ConwayEra (Binary.natVersion @9) "Conway TxBody" raw)
       ("babbage", _) | wdrl -> pure (bodyWithdrawals @BabbageEra (Binary.natVersion @7) "Babbage TxBody" raw)
       ("alonzo", _) | wdrl -> pure (bodyWithdrawals @AlonzoEra (Binary.natVersion @5) "Alonzo TxBody" raw)
       ("mary", _) | wdrl -> pure (bodyWithdrawals @MaryEra (Binary.natVersion @4) "Mary TxBody" raw)
       ("allegra", _) | wdrl -> pure (bodyWithdrawals @AllegraEra (Binary.natVersion @3) "Allegra TxBody" raw)
       ("shelley", _) | wdrl -> pure (bodyWithdrawals @ShelleyEra (Binary.natVersion @2) "Shelley TxBody" raw)
       ("conway", "transaction") -> pure (decodeAs @(Tx TopTx ConwayEra) (Binary.natVersion @9) "Conway Tx" raw)
       ("babbage", "transaction") -> pure (decodeAs @(Tx TopTx BabbageEra) (Binary.natVersion @7) "Babbage Tx" raw)
       ("alonzo", "transaction") -> pure (decodeAs @(Tx TopTx AlonzoEra) (Binary.natVersion @5) "Alonzo Tx" raw)
       ("mary", "transaction") -> pure (decodeAs @(Tx TopTx MaryEra) (Binary.natVersion @4) "Mary Tx" raw)
       ("allegra", "transaction") -> pure (decodeAs @(Tx TopTx AllegraEra) (Binary.natVersion @3) "Allegra Tx" raw)
       ("conway", _) | body_ -> pure (decodeAs @(TxBody TopTx ConwayEra) (Binary.natVersion @9) "Conway TxBody" raw)
       ("conway", _) | wits_ -> pure (decodeAs @(TxWits ConwayEra) (Binary.natVersion @9) "Conway TxWits" raw)
       ("babbage", _) | body_ -> pure (decodeAs @(TxBody TopTx BabbageEra) (Binary.natVersion @7) "Babbage TxBody" raw)
       ("babbage", _) | wits_ -> pure (decodeAs @(TxWits BabbageEra) (Binary.natVersion @7) "Babbage TxWits" raw)
       ("alonzo", _) | body_ -> pure (decodeAs @(TxBody TopTx AlonzoEra) (Binary.natVersion @5) "Alonzo TxBody" raw)
       ("alonzo", _) | wits_ -> pure (decodeAs @(TxWits AlonzoEra) (Binary.natVersion @5) "Alonzo TxWits" raw)
       ("mary", _) | body_ -> pure (decodeAs @(TxBody TopTx MaryEra) (Binary.natVersion @4) "Mary TxBody" raw)
       ("mary", _) | wits_ -> pure (decodeAs @(TxWits MaryEra) (Binary.natVersion @4) "Mary TxWits" raw)
       ("allegra", _) | body_ -> pure (decodeAs @(TxBody TopTx AllegraEra) (Binary.natVersion @3) "Allegra TxBody" raw)
       ("allegra", _) | wits_ -> pure (decodeAs @(TxWits AllegraEra) (Binary.natVersion @3) "Allegra TxWits" raw)
       ("shelley", _) | body_ -> pure (decodeAs @(TxBody TopTx ShelleyEra) (Binary.natVersion @2) "Shelley TxBody" raw)
       ("shelley", _) | wits_ -> pure (decodeAs @(TxWits ShelleyEra) (Binary.natVersion @2) "Shelley TxWits" raw)
       other -> die ("unsupported case " <> show other)
  pure (object ["name" .= name c, "era" .= era c, "kind" .= kind c, "result" .= result])

main :: IO ()
main = do
  args <- getArgs
  cases <- case args of
    [p] -> (LBS.readFile p >>= either die pure . eitherDecode) :: IO [Case]
    _ -> die "one input JSON path required"
  traverse runCase cases >>= LBS.putStr . encode
