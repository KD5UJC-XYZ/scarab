-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE GADTs #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TypeFamilies #-}
module Main (main) where
import qualified Codec.CBOR.Decoding as CBOR
import qualified Codec.CBOR.Encoding as CBOR
import Codec.CBOR.Read (DeserialiseFailure)
import qualified Codec.Serialise as Serialise
import Crypto.Hash (Digest, SHA256, hashlazy)
import qualified Data.ByteString.Short as Short
import Data.ByteString.Short (ShortByteString)
import Control.Exception (try)
import Control.Tracer (nullTracer)
import Data.Aeson (Value, object, (.=))
import qualified Data.Aeson as Json
import qualified Data.ByteString.Lazy as LBS
import qualified Data.ByteString.Lazy.Char8 as LBS8
import Data.Int (Int64)
import Data.List (unfoldr)
import Network.TypedProtocol.Codec (Codec, CodecF (encode))
import Ouroboros.Network.Block (HeaderHash, Point, StandardHash, Tip (TipGenesis), Serialised (..), decodePoint, decodeTip, encodePoint, encodeTip)
import Ouroboros.Network.Util.ShowProxy (ShowProxy (..))
import Ouroboros.Network.Protocol.ChainSync.Codec (codecChainSync)
import Ouroboros.Network.Channel (fixedInputChannel)
import Ouroboros.Network.Driver.Simple (DecoderFailure, runPeer)
import qualified Ouroboros.Network.Protocol.ChainSync.Client as Client
import Ouroboros.Network.Protocol.ChainSync.Type (ChainSync (StIdle, StNext), StNextKind (StCanAwait), Message (..))
import System.IO (hFlush, stdout)

-- | A block whose header hash is the raw hash of a Cardano block.
data B

type instance HeaderHash B = ShortByteString

instance StandardHash B

instance ShowProxy B where
  showProxy _ = "B"

-- | `decodeRawHash` (`Abstract.hs:307-314`) with `fromShortRawHash` at 32 bytes.
decodeRawHash :: CBOR.Decoder s ShortByteString
decodeRawHash = do
  sbs <- Serialise.decode
  case fromShortRawHash sbs of
    Just h -> pure h
    Nothing -> fail "decodeRawHash: hash has unexpected size"
  where
    fromShortRawHash s
      | Short.length s == 32 = Just s
      | otherwise = Nothing

encodeRawHash :: ShortByteString -> CBOR.Encoding
encodeRawHash = Serialise.encode

wireJson :: LBS.ByteString -> Value
wireJson bytes = object ["bytes" .= LBS.length bytes, "sha256" .= show (hashlazy bytes :: Digest SHA256)]

blockCodec :: Codec (ChainSync (Serialised B) (Point B) (Tip B)) DeserialiseFailure IO LBS.ByteString
blockCodec = codecChainSync Serialise.encode Serialise.decode
  (encodePoint encodeRawHash) (decodePoint decodeRawHash)
  (encodeTip encodeRawHash) (decodeTip decodeRawHash)

payloadLength :: Int64
payloadLength = 2147483649

slices :: LBS.ByteString -> [LBS.ByteString]
slices = unfoldr step
  where
    step bytes | LBS.null bytes = Nothing
               | otherwise = Just (LBS.splitAt 1048576 bytes)

observe :: LBS.ByteString -> IO Value
observe raw = do
  channel <- fixedInputChannel (slices raw)
  let done value = Client.ChainSyncClient (pure (Client.SendMsgDone value))
      client = Client.ChainSyncClient $ pure $ Client.SendMsgRequestNext (pure ()) Client.ClientStNext
        { Client.recvMsgRollForward = \block@(Serialised bytes) tip -> done $ object
            [ "accepted" .= True
            , "payload" .= wireJson bytes
            , "encoded" .= wireJson (encode blockCodec (MsgRollForward block tip :: Message (ChainSync (Serialised B) (Point B) (Tip B)) ('StNext 'StCanAwait) 'StIdle))
            ]
        , Client.recvMsgRollBackward = \_ _ -> done (object ["accepted" .= False])
        }
  result <- try (runPeer nullTracer blockCodec channel (Client.chainSyncClientPeer client))
  pure $ case result of
    Left (failure :: DecoderFailure) -> object ["accepted" .= False, "failure" .= show failure]
    Right (value, _) -> value

main :: IO ()
main = do
  let payload = LBS.take payloadLength (LBS.repeat 165)
      message = MsgRollForward (Serialised payload) TipGenesis :: Message (ChainSync (Serialised B) (Point B) (Tip B)) ('StNext 'StCanAwait) 'StIdle
      raw = encode blockCodec message
  LBS8.putStrLn (Json.encode (object ["stage" .= ("encoded" :: String), "wire" .= wireJson raw, "payload" .= wireJson payload, "feedChunkBytes" .= (1048576 :: Int), "scope" .= ("opaque serialized payload; not a ledger-valid block" :: String)]))
  hFlush stdout
  result <- observe raw
  LBS8.putStrLn (Json.encode (object ["stage" .= ("decoded" :: String), "result" .= result]))
  hFlush stdout
