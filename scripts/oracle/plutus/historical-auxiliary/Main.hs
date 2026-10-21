-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE AllowAmbiguousTypes #-}
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE DeriveGeneric #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

import Cardano.Crypto.Hash.Class (hashToBytes)
import qualified Cardano.Ledger.Binary as Binary
import Cardano.Ledger.Allegra (AllegraEra)
import Cardano.Ledger.BaseTypes (ProtVer (..), StrictMaybe (..))
import Cardano.Ledger.Core hiding (Value)
import Cardano.Ledger.Mary (MaryEra)
import Cardano.Ledger.Shelley (ShelleyEra)
import Cardano.Ledger.Shelley.Rules (ShelleyUtxowPredFailure (..), validateMetadata)
import Data.Aeson (FromJSON, Value, eitherDecode, encode, object, (.=))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy as LBS
import qualified Data.List.NonEmpty as NE
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import GHC.Generics (Generic)
import Lens.Micro ((&), (.~), (^.))
import System.Exit (die)
import Validation (Validation (..))

-- The observer changes no ledger source. Mode "aux" decodes a TxAuxData at the given decoder
-- version and runs the era's validateTxAuxData; mode "tx" decodes a whole Tx and then runs the
-- Utxow rule's own validateMetadata (cardano-ledger-shelley Rules/Utxow.hs 427-443), the
-- hash/presence/validity check that Shelley, Allegra and Mary all share.
data Case = Case { name :: String, era :: String, protocolMajor :: Int, mode :: String, cbor :: Text.Text
                 , auxiliary :: Maybe Text.Text }
  deriving (Generic)
instance FromJSON Case

hex :: BS.ByteString -> Text.Text
hex = Text.decodeUtf8 . Hex.encode

failureName :: ShelleyUtxowPredFailure era -> Text.Text
failureName f = case f of
  MissingTxBodyMetadataHash {} -> "MissingTxBodyMetadataHash"
  MissingTxMetadata {} -> "MissingTxMetadata"
  ConflictingMetadataHash {} -> "ConflictingMetadataHash"
  InvalidMetadata -> "InvalidMetadata"
  _ -> "OtherUtxowFailure"

auxHashHex :: EraTxAuxData era => TxAuxData era -> Text.Text
auxHashHex = hex . hashToBytes . extractHash . unTxAuxDataHash . hashTxAuxData

runFor :: forall era. EraTx era => (Tx TopTx era -> StAnnTx TopTx era) -> Case -> IO Value
runFor annotate c = do
  bytes <- either die pure (Hex.decode (Text.encodeUtf8 (cbor c)))
  version <- Binary.mkVersion (protocolMajor c)
  let pv = ProtVer version 0
      pp = emptyPParams @era & ppProtocolVersionL .~ pv
      label = ["name" .= name c]
  pure $ case mode c of
    "aux" ->
      case Binary.decodeFullAnnotator version "auxiliary data" Binary.decCBOR (LBS.fromStrict bytes)
             :: Either Binary.DecoderError (TxAuxData era) of
        Left _ -> object (label ++ ["decoded" .= False])
        Right aux -> object (label ++
          [ "decoded" .= True
          , "valid" .= validateTxAuxData @era mempty pv aux
          , "auxHash" .= auxHashHex aux
          , "originalBytesPreserved" .= (originalBytes aux == bytes) ])
    _ ->
      case Binary.decodeFullAnnotator version "transaction" Binary.decCBOR (LBS.fromStrict bytes)
             :: Either Binary.DecoderError (Tx TopTx era) of
        Left _ -> object (label ++ ["decoded" .= False])
        Right tx ->
          let failures = case validateMetadata pp (annotate tx) of
                Success () -> []
                Failure fs -> map failureName (NE.toList fs)
              aux = case tx ^. auxDataTxL of
                SNothing -> []
                SJust a -> ["auxHash" .= auxHashHex a, "originalBytesPreserved" .= (Just (hex (originalBytes a)) == auxiliary c)]
           in object (label ++ ["decoded" .= True, "failures" .= failures] ++ aux)

runCase :: Case -> IO Value
runCase c = case era c of
  "Shelley" -> runFor @ShelleyEra id c
  "Allegra" -> runFor @AllegraEra id c
  "Mary" -> runFor @MaryEra id c
  _ -> die "unsupported era"

main :: IO ()
main = do
  input <- LBS.getContents
  cases <- either die pure (eitherDecode input :: Either String [Case])
  results <- traverse runCase cases
  LBS.putStr (encode results)
