-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE GADTs #-}
{-# LANGUAGE KindSignatures #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TypeFamilies #-}
-- | Reads ChainSync messages and encodes candidate lists with the released
-- `codecChainSync` of ouroboros-network 1.2.0.0, the version cardano-node 11.1.3 runs, and the
-- point and tip decoders consensus gives it (`decodePoint (decodeRawHash p)`, `decodeTip
-- (decodeRawHash p)`, `Ouroboros/Consensus/Network/NodeToNode.hs`, ouroboros-consensus 82ecba32).
-- `decodeRawHash` is the unchanged excerpt of `Ouroboros/Consensus/Block/Abstract.hs:307-314` with
-- `fromShortRawHash` (`:276-279`) at the hash size every Cardano era has, 32 bytes
-- (`HashSize ByronBlock = 32`, `Byron/Ledger/Block.hs:84`, equal across eras by `CanHardFork`).
-- Requests select the plain peer, incremental decoder, encoder, or anchored-fragment selector.
-- Results retain compact counts and SHA-256 wire identities instead of bulk point/byte dumps.
module Main (main) where

import qualified Codec.CBOR.Decoding as CBOR
import qualified Codec.CBOR.Encoding as CBOR
import Codec.CBOR.Read (DeserialiseFailure)
import qualified Codec.Serialise as Serialise
import Control.Monad (forM_)
import Data.Aeson (Value, object, (.=))
import qualified Data.Aeson as Json
import Control.Exception (try)
import Control.Tracer (nullTracer)
import Crypto.Hash (Digest, SHA256, hashlazy)
import qualified Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Char8 as BS8
import qualified Data.ByteString.Lazy as LBS
import qualified Data.ByteString.Lazy.Char8 as LBS8
import qualified Data.ByteString.Short as Short
import Data.ByteString.Short (ShortByteString)
import Data.Text.Encoding (decodeUtf8)
import Data.Word (Word64)
import Cardano.Slotting.Slot (SlotNo (..))
import Network.TypedProtocol.Codec (Codec, CodecF (encode, decode), DecodeStep (..))
import Ouroboros.Network.Block (HeaderHash, HeaderFields (..), BlockNo (..), Point (..), StandardHash, Tip (..), Serialised (..), castPoint, decodePoint, decodeTip, encodePoint, encodeTip)
import qualified Ouroboros.Network.AnchoredFragment as AF
import Ouroboros.Network.Channel (fixedInputChannel)
import Ouroboros.Network.Driver.Simple (DecoderFailure, runPeer)
import Ouroboros.Network.Point (Block (..), WithOrigin (..))
import Ouroboros.Network.Protocol.ChainSync.Codec (codecChainSync)
import Ouroboros.Network.Protocol.ChainSync.Type (ChainSync (StIdle, StNext), StNextKind (StCanAwait), Message (..), SingChainSync (SingIdle, SingNext), SingNextKind (SingCanAwait))
import Ouroboros.Network.Util.ShowProxy (ShowProxy (..))
import qualified Ouroboros.Network.Protocol.ChainSync.Server as Server
import qualified Ouroboros.Network.Protocol.ChainSync.Client as Client
import Text.Read (readMaybe)

-- | A block whose header hash is the raw hash of a Cardano block.
data B

type instance HeaderHash B = ShortByteString

instance StandardHash B

instance ShowProxy B where
  showProxy _ = "B"

newtype Opaque = Opaque BS8.ByteString

instance ShowProxy Opaque where
  showProxy _ = "Opaque"

encodeOpaque :: Opaque -> CBOR.Encoding
encodeOpaque (Opaque bytes) = CBOR.encodeBytes bytes

decodeOpaque :: CBOR.Decoder s Opaque
decodeOpaque = Opaque <$> CBOR.decodeBytes

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

codec :: Codec (ChainSync Opaque (Point B) (Tip B)) DeserialiseFailure IO LBS.ByteString
codec =
  codecChainSync
    encodeOpaque
    decodeOpaque
    (encodePoint encodeRawHash)
    (decodePoint decodeRawHash)
    (encodeTip encodeRawHash)
    (decodeTip decodeRawHash)

pointJson :: Point B -> Value
pointJson (Point Origin) = object ["origin" .= True]
pointJson (Point (At (Block s h))) = object ["slot" .= toInteger (unSlotNo s), "hash" .= decodeUtf8 (Hex.encode (Short.fromShort h))]

wireJson :: LBS.ByteString -> Value
wireJson bytes = object ["bytes" .= LBS.length bytes, "sha256" .= show (hashlazy bytes :: Digest SHA256)]

pointsJson :: [Point B] -> Value
pointsJson ps = object
  [ "accepted" .= True
  , "points" .= length ps
  , "first" .= case ps of { [] -> Nothing; p : _ -> Just (pointJson p) }
  , "encoded" .= wireJson (encode codec (MsgFindIntersect ps))
  ]

-- Observe the incremental codec before end-of-input. Plain runPeer reports EOF as a failure,
-- whereas a live channel must wait when a declared list/string has not arrived yet.
prefixVerdict :: LBS.ByteString -> IO Value
prefixVerdict raw = do
  initial <- decode codec SingIdle
  step <- case initial of
    DecodePartial more -> more (Just raw)
    other -> pure other
  pure $ case step of
    DecodePartial _ -> object ["accepted" .= False, "needMore" .= True]
    DecodeFail failure -> object ["accepted" .= False, "needMore" .= False, "failure" .= show failure]
    DecodeDone _ _ -> object ["accepted" .= True, "needMore" .= False]

blockPrefixVerdict :: LBS.ByteString -> IO Value
blockPrefixVerdict raw = do
  initial <- decode codec (SingNext SingCanAwait)
  step <- case initial of
    DecodePartial more -> more (Just raw)
    other -> pure other
  pure $ case step of
    DecodePartial _ -> object ["accepted" .= False, "needMore" .= True]
    DecodeFail failure -> object ["accepted" .= False, "needMore" .= False, "failure" .= show failure]
    DecodeDone _ _ -> object ["accepted" .= True, "needMore" .= False]

-- Use the same plain driver as consensus's node-to-client ChainSync server. The driver
-- has no byte/item/depth policy; node-to-node's separate limited driver is not exercised.
-- Append MsgDone so that the real server peer terminates after reading the candidate list.
verdict :: LBS.ByteString -> IO Value
verdict raw = do
  channel <- fixedInputChannel [raw, encode codec MsgDone]
  result <- try (runPeer nullTracer codec channel (Server.chainSyncServerPeer (server (object []))))
  pure $ case result of
    Left (failure :: DecoderFailure) -> object ["accepted" .= False, "failure" .= show failure]
    Right (value, _) -> value
  where
    server value = Server.ChainSyncServer $ pure Server.ServerStIdle
      { Server.recvMsgRequestNext = pure (Left (Server.SendMsgRollBackward (Point Origin) TipGenesis (server value)))
      , Server.recvMsgFindIntersect = \ps -> pure (Server.SendMsgIntersectNotFound TipGenesis (server (pointsJson ps)))
      , Server.recvMsgDoneClient = pure value
      }

-- Serialised's native instance is tag 24 plus a byte string; the block bytes remain opaque.
blockCodec :: Codec (ChainSync (Serialised B) (Point B) (Tip B)) DeserialiseFailure IO LBS.ByteString
blockCodec = codecChainSync Serialise.encode Serialise.decode
  (encodePoint encodeRawHash) (decodePoint decodeRawHash)
  (encodeTip encodeRawHash) (decodeTip decodeRawHash)

blockVerdict :: LBS.ByteString -> IO Value
blockVerdict raw = do
  channel <- fixedInputChannel [raw]
  let client = Client.ChainSyncClient $ pure $ Client.SendMsgRequestNext (pure ()) Client.ClientStNext
        { Client.recvMsgRollForward = \block@(Serialised bytes) tip -> done $ object
            [ "accepted" .= True, "payload" .= wireJson bytes
            , "encoded" .= wireJson (encode blockCodec (MsgRollForward block tip :: Message (ChainSync (Serialised B) (Point B) (Tip B)) ('StNext 'StCanAwait) 'StIdle)) ]
        , Client.recvMsgRollBackward = \_ _ -> done (object ["accepted" .= False])
        }
      done value = Client.ChainSyncClient (pure (Client.SendMsgDone value))
  result <- try (runPeer nullTracer blockCodec channel (Client.chainSyncClientPeer client))
  pure $ case result of
    Left (failure :: DecoderFailure) -> object ["accepted" .= False, "failure" .= show failure]
    Right (value, _) -> value

-- A generic opaque byte-string point demonstrates that encoding is not constrained by the
-- node-to-node receive bound, even when canonical list framing makes the message one byte larger.
opaqueVerdict :: LBS.ByteString -> IO Value
opaqueVerdict raw = do
  let opaqueCodec = codecChainSync encodeOpaque decodeOpaque
        encodeOpaque decodeOpaque encodeOpaque decodeOpaque
        :: Codec (ChainSync Opaque Opaque Opaque) DeserialiseFailure IO LBS.ByteString
      server value = Server.ChainSyncServer $ pure Server.ServerStIdle
        { Server.recvMsgRequestNext = pure (Left (Server.SendMsgRollBackward (Opaque BS8.empty) (Opaque BS8.empty) (server value)))
        , Server.recvMsgFindIntersect = \ps -> pure $ Server.SendMsgIntersectNotFound (Opaque BS8.empty) $ server $ object
            ["accepted" .= True, "points" .= length ps, "encoded" .= wireJson (encode opaqueCodec (MsgFindIntersect ps))]
        , Server.recvMsgDoneClient = pure value
        }
  channel <- fixedInputChannel [raw, encode opaqueCodec MsgDone]
  result <- try (runPeer nullTracer opaqueCodec channel (Server.chainSyncServerPeer (server (object []))))
  pure $ case result of
    Left (failure :: DecoderFailure) -> object ["accepted" .= False, "failure" .= show failure]
    Right (value, _) -> value

-- The body of consensus Client.mkOffsets, with its SecurityParam/NonZero wrappers removed;
-- fib is the exact Double/Binet implementation from consensus Util.hs:443-449, not an
-- independently chosen integer recurrence. Selection itself executes the released AF code.
offsets :: Word64 -> Word64 -> [Word64]
offsets k maxOffset = [0] ++ takeWhile (< l) [fib n | n <- [2 ..]] ++ [l]
  where
    l = k `min` maxOffset
    fib :: Word64 -> Word64
    fib n = round $ phi ** fromIntegral n / sq5
      where
        sq5, phi :: Double
        sq5 = sqrt 5
        phi = (1 + sq5) / 2

selection :: Word64 -> Word64 -> Bool -> Value
selection k len blockAnchor = object
  [ "k" .= k, "length" .= len, "blockAnchor" .= blockAnchor, "offsets" .= os
  , "points" .= map (pointJson . castPoint) (AF.selectPoints (map fromIntegral os) fragment)
  ]
  where
    os = offsets k len
    hash = Short.toShort (BS8.replicate 32 '\171')
    anchor = if blockAnchor then AF.Anchor (SlotNo 10) hash (BlockNo 10) else AF.AnchorGenesis
    base = if blockAnchor then 10 else 0
    fragment = AF.fromOldestFirst anchor
      [ HeaderFields (SlotNo (base + n)) (BlockNo (base + n)) hash :: HeaderFields B | n <- [1 .. len] ]

request :: BS8.ByteString -> IO Value
request line = case BS8.words line of
  ["select", k, len, anchor] | Just security <- readMaybe (BS8.unpack k), Just count <- readMaybe (BS8.unpack len) ->
    pure (selection security count (anchor == "block"))
  ["offsets", k, len] | Just security <- readMaybe (BS8.unpack k), Just count <- readMaybe (BS8.unpack len) ->
    pure (object ["k" .= security, "length" .= count, "offsets" .= offsets security count])
  ["encode", n, kind] | Just count <- readMaybe (BS8.unpack n), count >= 0 ->
    let p = if kind == "origin" then Point Origin else Point (At (Block (SlotNo 1000) (Short.toShort (BS8.replicate 32 '\171'))))
    in pure (pointsJson (replicate count p))
  ["decode", raw] -> withHex verdict raw
  ["prefix", raw] -> withHex prefixVerdict raw
  ["block-prefix", raw] -> withHex blockPrefixVerdict raw
  ["block", raw] -> withHex blockVerdict raw
  ["opaque", raw] -> withHex opaqueVerdict raw
  _ -> withHex verdict line
  where
    withHex action raw = case Hex.decode raw of
      Left e -> pure (object ["accepted" .= False, "failure" .= ("not hex: " <> e)])
      Right bytes -> action (LBS.fromStrict bytes)

main :: IO ()
main = do
  input <- BS8.getContents
  forM_ (BS8.lines input) $ \line -> request line >>= LBS8.putStrLn . Json.encode
