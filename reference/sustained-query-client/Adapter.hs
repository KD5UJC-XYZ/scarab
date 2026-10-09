{-# LANGUAGE OverloadedStrings, PatternSynonyms, TypeApplications #-}
module Adapter (Request(..), parseRequest, exactVersion, captureEnvelope, verifierEnvelope) where
import qualified Cardano.Network.NodeToClient.Version as N
import Data.Aeson (Value(..), object, (.=))
import qualified Data.Aeson.KeyMap as KM
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as LBS
import Data.Proxy (Proxy(..))
import qualified Data.Text as T
import qualified Data.Text.Encoding as T
import Data.Word (Word32, Word64)
import Ouroboros.Consensus.Block (Point, SlotNo(..), BlockNo(..), pattern BlockPoint, pattern GenesisPoint, fromRawHash, toRawHash)
import Cardano.Slotting.Slot (WithOrigin(..))
import Session
import StrictJson
import VerifySeed
import Projection

data Request = Request
  { socketPath :: !FilePath, requestedPoint :: !(Point Block), magic :: !Word32
  , byronSlots :: !Word64, versionNumber :: !Integer, exactWireVersion :: !N.NodeToClientVersion
  }

-- Explicit wire-number mapping. Enum ordinals are NOT wire versions.
exactVersion :: Integer -> Either String N.NodeToClientVersion
exactVersion n = case n of
  16 -> Right N.NodeToClientV_16; 17 -> Right N.NodeToClientV_17
  18 -> Right N.NodeToClientV_18; 19 -> Right N.NodeToClientV_19
  20 -> Right N.NodeToClientV_20; 21 -> Right N.NodeToClientV_21
  22 -> Right N.NodeToClientV_22; 23 -> Right N.NodeToClientV_23
  _ -> Left "unsupported exact node-to-client wire version"

parseRequest :: BS.ByteString -> Either String Request
parseRequest bytes = do
  require (BS.length bytes <= 16384) "request byte limit"
  obj <- decodeObject bytes
  fields ["schema","socket","point","networkMagic","byronEpochSlots","ntcVersion","producerBinarySHA256","producerImage"] obj
  _ <- unsigned "schema" 1 1 obj
  socket <- stringAt "socket" obj
  require (T.isPrefixOf "/" socket && not (T.any (=='\0') socket) && BS.length (T.encodeUtf8 socket) <= 100) "invalid socket path"
  p <- valueAt "point" obj >>= \v -> case v of Object o -> Right o; _ -> Left "point must be object"
  fields ["slot","hash"] p
  slot <- unsigned "slot" 0 (2^64-1) p
  h <- stringAt "hash" p >>= hexBytes 32
  require (BS.length h == 32) "point hash must be 32 bytes"
  nativeHash <- maybe (Left "native point hash size mismatch") Right (fromRawHash (Proxy @Block) h)
  m <- unsigned "networkMagic" 1 (2^32-1) obj
  slots <- unsigned "byronEpochSlots" 1 (2^64-1) obj
  n <- unsigned "ntcVersion" 1 (2^16-1) obj
  v <- exactVersion n
  producer <- stringAt "producerBinarySHA256" obj >>= hexBytes 32
  require (BS.length producer == 32) "producer binary digest length"
  img <- stringAt "producerImage" obj
  rest <- maybe (Left "producer image digest prefix") Right (T.stripPrefix "sha256:" img)
  imageHash <- hexBytes 32 rest
  require (BS.length imageHash == 32) "producer image digest length"
  pure (Request (T.unpack socket) (BlockPoint (SlotNo (fromInteger slot)) nativeHash)
        (fromInteger m) (fromInteger slots) n v)

pointValue :: Point Block -> Either String Value
pointValue GenesisPoint = Left "unexpected origin point"
pointValue (BlockPoint (SlotNo slot) h) = Right (object ["slot" .= slot,"hash" .= hexText (toRawHash (Proxy @Block) h)])
blockValue :: WithOrigin BlockNo -> Either String Word64
blockValue Origin = Left "unexpected origin block number"
blockValue (At (BlockNo n)) = Right n
captureEnvelope :: Request -> Capture -> Either String Value
captureEnvelope req capture = do
  requested <- pointValue (requestedPoint req)
  first <- pointValue (acquiredPoint capture)
  lastP <- pointValue (finalPoint capture)
  firstN <- blockValue (acquiredBlock capture)
  lastN <- blockValue (finalBlock capture)
  projected <- projectCapture capture
  pure $ object ["schema" .= (1::Int),"kind" .= ("single-acquire-sustained-payloads"::T.Text),
    "requestedPoint" .= requested,"acquiredPoint" .= first,"finalPoint" .= lastP,
    "blockNo" .= firstN,"finalBlockNo" .= lastN,"ntcVersion" .= versionNumber req,
    "acquireCount" .= (1::Int),"reacquireCount" .= (0::Int),"release" .= ("sent-no-ack"::T.Text),
    "queryEncoding" .= ("GetCBOR-server-maxBound"::T.Text),
    "epochHex" .= hexText (LBS.toStrict (epochBytes capture)),"utxoHex" .= hexText (LBS.toStrict (utxoBytes capture)),
    "protocolHex" .= hexText (LBS.toStrict (protocolBytes capture)),
    "parametersHex" .= hexText (LBS.toStrict (parameterBytes capture)),
    "projection" .= projected]

verifierEnvelope :: BS.ByteString -> Either String Value
verifierEnvelope input = do
  require (BS.length input <= 4*maxReplyBytes+1024) "verifier input byte limit"
  obj <- decodeObject input
  fields ["epochHex","utxoHex"] obj
  epoch <- stringAt "epochHex" obj >>= hexBytes maxReplyBytes
  utxo <- stringAt "utxoHex" obj >>= hexBytes maxReplyBytes
  verified <- verifySeed (LBS.fromStrict epoch) (LBS.fromStrict utxo)
  pure $ object ["schema" .= (1::Int),"kind" .= ("derived-native-full-epoch-seed"::T.Text),
    "epochInputSHA256" .= digest epoch,"utxoInputSHA256" .= digest utxo,
    "epochFullConsumption" .= True,"utxoFullConsumption" .= True,
    "epochRoundTripEqual" .= True,"utxoRoundTripEqual" .= True,
    "derivedRoundTripEqual" .= True,"onlyUtxoReplaced" .= True,
    "wholeUTxOEntries" .= wholeUTxOEntries verified,
    "derivedSeedHex" .= hexText (LBS.toStrict (derivedFullSeed verified)),
    "runtimeImport" .= False,"monetaryParity" .= False,"rewardSeedAdmission" .= False,
    "admissionChecks" .= ("not-performed"::T.Text)]
