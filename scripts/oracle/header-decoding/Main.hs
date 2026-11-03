-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE AllowAmbiguousTypes #-}
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE DeriveGeneric #-}
{-# LANGUAGE FlexibleContexts #-}
{-# LANGUAGE FlexibleInstances #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TypeApplications #-}
{-# LANGUAGE TypeFamilies #-}
module Main (main) where

import qualified Cardano.Crypto.Hash as Hash
import Cardano.Crypto.Util (SignableRepresentation (getSignableRepresentation))
import Cardano.Ledger.Allegra (AllegraEra)
import Cardano.Ledger.Alonzo (AlonzoEra)
import Cardano.Ledger.Babbage (BabbageEra)
import qualified Cardano.Ledger.Binary as Binary
import Cardano.Ledger.Block (Block (..))
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Core (EraBlockBody (hashBlockBody))
import Cardano.Ledger.Dijkstra (DijkstraEra)
import Cardano.Ledger.Mary (MaryEra)
import Cardano.Ledger.Shelley (ShelleyEra)
import Cardano.Protocol.Crypto (StandardCrypto)
import qualified Cardano.Protocol.Leios.BlockHeader as Leios
import qualified Cardano.Protocol.Praos.BlockHeader as Praos
import qualified Cardano.Protocol.TPraos.BlockHeader as TPraos
import Data.Aeson (FromJSON, Value, eitherDecode, encode, object, (.=))
import Data.Aeson.Types (Pair)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy as LBS
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import Data.Typeable (Typeable)
import GHC.Generics (Generic)
import System.Environment (getArgs)
import System.Exit (die)

-- | One original encoding of era `era`, read at that era's `eraProtVerLow` (Shelley 2, Allegra 3,
-- Mary 4, Alonzo 5, Babbage 7, Conway 9) as consensus reads it (`eraDecoder`); era "leios" is the
-- Leios header of `cardano-protocol`, read at Dijkstra's version 12. `kind` is "header" (a TPraos
-- `BHeader` to Alonzo, a Praos `Header` from Babbage, a Leios `Header`), "headerbody" (its `BHBody`
-- or `HeaderBody`) or "block" (a ledger `Block` holding that header). An accepted case reports the
-- header body's `getSignableRepresentation`, the bytes the KES signature is checked over (#698),
-- and for a header or a block the header hash, which is over the bytes read; a block also reports
-- its block body hash.
data Case = Case
  { name :: String
  , era :: String
  , kind :: String
  , bytes :: Text.Text
  }
  deriving (Generic)

instance FromJSON Case

-- | An input the harness has no decoder for, rendered only when it stops.
data CaseError
  = UnsupportedKind String
  | UnsupportedEra String
  | NotHex String
  deriving (Show)

refused :: Binary.DecoderError -> Value
refused failure = object ["accepted" .= False, "failure" .= show failure]

accepted :: [Pair] -> Value
accepted ps = object (("accepted" .= True) : ps)

hexBytes :: BS.ByteString -> Text.Text
hexBytes = Text.decodeUtf8 . Hex.encode

hexHash :: Hash.Hash h a -> Text.Text
hexHash = Hash.hashToTextAsHex

-- | A protocol's header: its body, the body's signable bytes and the header hash.
class (Typeable h, Binary.DecCBOR (Binary.Annotator h)) => ProtocolHeader h where
  type Body h
  signable :: h -> BS.ByteString
  headerHashHex :: h -> Text.Text

instance ProtocolHeader (TPraos.BHeader StandardCrypto) where
  type Body (TPraos.BHeader StandardCrypto) = TPraos.BHBody StandardCrypto
  signable = getSignableRepresentation . TPraos.bhbody
  headerHashHex h = case TPraos.bhHash h of TPraos.HashHeader x -> hexHash x

instance ProtocolHeader (Praos.Header StandardCrypto) where
  type Body (Praos.Header StandardCrypto) = Praos.HeaderBody StandardCrypto
  signable = getSignableRepresentation . Praos.headerBody
  headerHashHex = hexHash . Praos.headerHash

instance ProtocolHeader (Leios.Header StandardCrypto) where
  type Body (Leios.Header StandardCrypto) = Leios.HeaderBody StandardCrypto
  signable = getSignableRepresentation . Leios.headerBody
  headerHashHex = hexHash . Leios.headerHash

headerReport :: ProtocolHeader h => h -> [Pair]
headerReport h = ["signable" .= hexBytes (signable h), "hash" .= headerHashHex h]

readCase ::
  forall h era.
  ( ProtocolHeader h
  , Binary.DecCBOR (Body h)
  , SignableRepresentation (Body h)
  , EraBlockBody era
  ) =>
  Binary.Version -> String -> String -> LBS.ByteString -> Either CaseError Value
readCase version label k raw = case k of
  "header" ->
    Right (either refused (accepted . headerReport) (Binary.decodeFullAnnotator version (Text.pack (label <> " header")) Binary.decCBOR raw :: Either Binary.DecoderError h))
  "headerbody" ->
    Right (either refused (\b -> accepted ["signable" .= hexBytes (getSignableRepresentation b)]) (Binary.decodeFull version raw :: Either Binary.DecoderError (Body h)))
  "block" ->
    Right
      ( either
          refused
          (\(Block h b) -> accepted (headerReport h <> ["bodyhash" .= hexHash (hashBlockBody b)]))
          (Binary.decodeFullAnnotator version (Text.pack (label <> " block")) Binary.decCBOR raw :: Either Binary.DecoderError (Block h era))
      )
  other -> Left (UnsupportedKind other)

type TPraosHeader = TPraos.BHeader StandardCrypto

type PraosHeader = Praos.Header StandardCrypto

type LeiosHeader = Leios.Header StandardCrypto

runCase :: Case -> IO Value
runCase c = do
  raw <- either (die . show . NotHex) (pure . LBS.fromStrict) (Hex.decode (Text.encodeUtf8 (bytes c)))
  let k = kind c
  result <- either (die . show) pure $ case era c of
    "shelley" -> readCase @TPraosHeader @ShelleyEra (Binary.natVersion @2) "Shelley" k raw
    "allegra" -> readCase @TPraosHeader @AllegraEra (Binary.natVersion @3) "Allegra" k raw
    "mary" -> readCase @TPraosHeader @MaryEra (Binary.natVersion @4) "Mary" k raw
    "alonzo" -> readCase @TPraosHeader @AlonzoEra (Binary.natVersion @5) "Alonzo" k raw
    "babbage" -> readCase @PraosHeader @BabbageEra (Binary.natVersion @7) "Babbage" k raw
    "conway" -> readCase @PraosHeader @ConwayEra (Binary.natVersion @9) "Conway" k raw
    "leios" -> readCase @LeiosHeader @DijkstraEra (Binary.natVersion @12) "Leios" k raw
    other -> Left (UnsupportedEra other)
  pure (object ["name" .= name c, "era" .= era c, "kind" .= k, "result" .= result])

main :: IO ()
main = do
  args <- getArgs
  cases <- case args of
    [p] -> (LBS.readFile p >>= either die pure . eitherDecode) :: IO [Case]
    _ -> die "one input JSON path required"
  traverse runCase cases >>= LBS.putStr . encode
