-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE DeriveGeneric #-}
{-# LANGUAGE FlexibleContexts #-}
{-# LANGUAGE LambdaCase #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE RankNTypes #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

import Cardano.Binary (DecoderError (..), enforceSize)
import qualified Cardano.Crypto.Hash as Hash
import Cardano.Crypto.Util (SignableRepresentation (getSignableRepresentation))
import Cardano.Ledger.Alonzo.Tx (AlonzoEraTx (isPhase2ValidTxL), IsPhase2Valid (..))
import Cardano.Ledger.BaseTypes (ProtVer (..), StrictMaybe (..), natVersion)
import qualified Cardano.Ledger.Binary as Binary
import qualified Cardano.Ledger.Binary.Plain as Plain
import Cardano.Ledger.Block (Block (..), BlockHeaderVersionInfo (..), EbReferencesAnnouncement (..))
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Core (EraBlockBody (..), eraDecoder, txIdTx)
import Cardano.Ledger.Dijkstra (DijkstraEra)
import Cardano.Ledger.Dijkstra.BlockBody (DijkstraEraBlockBody (..))
import Cardano.Ledger.Hashes (extractHash)
import Cardano.Ledger.TxIn (TxId (..))
import Cardano.Protocol.Crypto (StandardCrypto)
import qualified Cardano.Protocol.Leios.BlockHeader as Leios
import qualified Cardano.Protocol.Praos.BlockHeader as Praos
import qualified Codec.CBOR.Decoding as CBOR
import Data.Aeson (FromJSON, Value, eitherDecode, encode, object, (.=))
import Data.Aeson.Types (Pair)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy as LBS
import Data.Foldable (toList)
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import GHC.Generics (Generic)
import Lens.Micro ((^.))
import System.Environment (getArgs)
import System.Exit (die)

-- | One original encoding. `kind` "block" is a block as BlockFetch carries it, `[tag, block]` inside
-- the CBOR-in-CBOR wrapper, read with the envelope of `decodeDiskHfcBlock` (ouroboros-consensus
-- 2416493b, `Ouroboros/Consensus/Cardano/Node.hs:185-198`, copied in `cardanoBlock`): tag 8 is
-- `ShelleyBlock (Praos2 c) DijkstraEra`, a ledger `Block` of the Leios header read at Dijkstra's
-- version 12, and tag 7 `ShelleyBlock (Praos c) ConwayEra` at Conway's 9 (`decodeShelleyBlock`,
-- `Shelley/Ledger/Block.hs:332-339`); the harness reads no other tag. `kind` "header" is a Leios
-- header alone (`decodeShelleyHeader`, `:359-369`), as ChainSync carries it inside its era
-- envelope.
data Case = Case
  { name :: String
  , kind :: String
  , bytes :: Text.Text
  }
  deriving (Generic)

instance FromJSON Case

-- | An input the harness has no decoder for, rendered only when it stops.
data CaseError
  = UnsupportedKind String
  | NotHex String
  deriving (Show)

type LeiosHeader = Leios.Header StandardCrypto

type PraosHeader = Praos.Header StandardCrypto

data CardanoBlock
  = BlockConway (Block PraosHeader ConwayEra)
  | BlockDijkstra (Block LeiosHeader DijkstraEra)

-- | `decodeDiskHfcBlock` of `CardanoEras` for the two tags the harness reads: the 2-tuple
-- envelope (`enforceSize`, a definite list), the tag, and the era's annotated block decoder run over
-- the whole input, as consensus passes it.
cardanoBlock :: Plain.Decoder s (LBS.ByteString -> Either DecoderError CardanoBlock)
cardanoBlock = do
  enforceSize "CardanoBlock" 2
  CBOR.decodeWord >>= \case
    7 -> fmap (fmap BlockConway) <$> eraDecoder @ConwayEra ((. Binary.Full) . Binary.runAnnotator <$> Binary.decCBOR)
    8 -> fmap (fmap BlockDijkstra) <$> eraDecoder @DijkstraEra ((. Binary.Full) . Binary.runAnnotator <$> Binary.decCBOR)
    t -> Plain.cborError $ DecoderErrorUnknownTag "CardanoBlock" (fromIntegral t)

refused :: DecoderError -> Value
refused failure = object ["accepted" .= False, "failure" .= show failure]

accepted :: [Pair] -> Value
accepted ps = object (("accepted" .= True) : ps)

hexBytes :: BS.ByteString -> Text.Text
hexBytes = Text.decodeUtf8 . Hex.encode

hexHash :: Hash.Hash h a -> Text.Text
hexHash = Hash.hashToTextAsHex

-- | A Leios header: its hash and size over the bytes read, the bytes its KES signature is checked
-- over (the header body's original bytes, `getSignableRepresentation = originalBytes`), and the
-- fields the block reports compare with its body.
leiosHeader :: LeiosHeader -> [Pair]
leiosHeader h =
  [ "hash" .= hexHash (Leios.headerHash h)
  , "headersize" .= Leios.headerSize h
  , "signable" .= hexBytes (getSignableRepresentation body)
  , "blockno" .= Leios.hbBlockNo body
  , "slot" .= Leios.hbSlotNo body
  , "declaredbodysize" .= Leios.hbBodySize body
  , "declaredbodyhash" .= hexHash (Leios.hbBodyHash body)
  , "versioninfo" .= [bhviHighestSupportedMajorVersion info, bhviSelfReportedSoftwareTag info]
  , "containscert" .= Leios.hbBlockBodyContainsLeiosCert body
  , "announcement" .= case Leios.hbEbReferencesAnnouncement body of
      SNothing -> Nothing
      SJust a ->
        Just
          ( object
              [ "hash" .= hexHash (extractHash (ebReferencesAnnouncementHash a))
              , "size" .= ebReferencesAnnouncementSize a
              ]
          )
  ]
  where
    body = Leios.headerBody h
    info = Leios.hbVersionInfo body

-- | A Dijkstra block: its header, the body's hash and size as the ledger computes them, each
-- transaction's id and validity flag, and which certificates the body carries.
dijkstraBlock :: Block LeiosHeader DijkstraEra -> Value
dijkstraBlock (Block h b) =
  accepted
    ( leiosHeader h
        <> [ "tag" .= (8 :: Int)
           , "bodyhash" .= hexHash (hashBlockBody b)
           , "bodysize" .= blockBodySize (ProtVer (natVersion @12) 0) b
           , "txs" .= map tx (toList (b ^. txSeqBlockBodyL))
           , "leioscert" .= present (b ^. leiosCertBlockBodyL)
           , "perascert" .= present (b ^. perasCertBlockBodyL)
           ]
    )
  where
    tx t = object ["id" .= hexHash (extractHash (unTxId (txIdTx t))), "valid" .= (t ^. isPhase2ValidTxL == Phase2Valid)]
    present :: StrictMaybe a -> Bool
    present = \case
      SNothing -> False
      SJust _ -> True

conwayBlock :: Block PraosHeader ConwayEra -> Value
conwayBlock (Block h b) =
  accepted ["tag" .= (7 :: Int), "hash" .= hexHash (Praos.headerHash h), "bodyhash" .= hexHash (hashBlockBody b)]

runCase :: Case -> IO Value
runCase c = do
  raw <- either (die . show . NotHex) (pure . LBS.fromStrict) (Hex.decode (Text.encodeUtf8 (bytes c)))
  result <- case kind c of
    "block" ->
      pure $
        either refused (\case BlockDijkstra b -> dijkstraBlock b; BlockConway b -> conwayBlock b) $
          Plain.decodeFullDecoder "CardanoBlock" cardanoBlock raw >>= ($ raw)
    "header" ->
      pure $
        either refused (accepted . leiosHeader) $
          (Binary.decodeFullAnnotator (natVersion @12) "Leios header" Binary.decCBOR raw :: Either DecoderError LeiosHeader)
    other -> die (show (UnsupportedKind other))
  pure (object ["name" .= name c, "kind" .= kind c, "result" .= result])

main :: IO ()
main = do
  args <- getArgs
  cases <- case args of
    [p] -> (LBS.readFile p >>= either die pure . eitherDecode) :: IO [Case]
    _ -> die "one input JSON path required"
  traverse runCase cases >>= LBS.putStr . encode
