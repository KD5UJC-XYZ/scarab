-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE GADTs #-}
{-# LANGUAGE KindSignatures #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TypeFamilies #-}
-- | Reads client messages of node-to-node ChainSync in the server's `StIdle` with the released
-- `codecChainSync` of ouroboros-network 1.2.0.0, the version cardano-node 11.1.3 runs, and the
-- point and tip decoders consensus gives it (`decodePoint (decodeRawHash p)`, `decodeTip
-- (decodeRawHash p)`, `Ouroboros/Consensus/Network/NodeToNode.hs`, ouroboros-consensus 82ecba32).
-- `decodeRawHash` is the unchanged excerpt of `Ouroboros/Consensus/Block/Abstract.hs:307-314` with
-- `fromShortRawHash` (`:276-279`) at the hash size every Cardano era has, 32 bytes
-- (`HashSize ByronBlock = 32`, `Byron/Ledger/Block.hs:84`, equal across eras by `CanHardFork`).
-- Each input line is a hex message; each output line its verdict and, for `MsgFindIntersect`, the
-- points read (#755).
module Main (main) where

import qualified Codec.CBOR.Decoding as CBOR
import qualified Codec.CBOR.Encoding as CBOR
import Codec.CBOR.Read (DeserialiseFailure)
import qualified Codec.Serialise as Serialise
import Control.Monad (forM_)
import Data.Aeson (Value, encode, object, (.=))
import qualified Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Char8 as BS8
import qualified Data.ByteString.Lazy as LBS
import qualified Data.ByteString.Lazy.Char8 as LBS8
import qualified Data.ByteString.Short as Short
import Data.ByteString.Short (ShortByteString)
import Data.Text.Encoding (decodeUtf8)
import Cardano.Slotting.Slot (SlotNo (..))
import Network.TypedProtocol.Codec (Codec, CodecF (decode), SomeMessage (..), runDecoder)
import Ouroboros.Network.Block (HeaderHash, Point (..), StandardHash, Tip, decodePoint, decodeTip, encodePoint, encodeTip)
import Ouroboros.Network.Point (Block (..), WithOrigin (..))
import Ouroboros.Network.Protocol.ChainSync.Codec (codecChainSync)
import Ouroboros.Network.Protocol.ChainSync.Type (ChainSync (StIdle), Message (..), SingChainSync (..))

-- | A block whose header hash is the raw hash of a Cardano block.
data B

type instance HeaderHash B = ShortByteString

instance StandardHash B

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

codec :: Codec (ChainSync BS8.ByteString (Point B) (Tip B)) DeserialiseFailure IO LBS.ByteString
codec =
  codecChainSync
    CBOR.encodeBytes
    CBOR.decodeBytes
    (encodePoint encodeRawHash)
    (decodePoint decodeRawHash)
    (encodeTip encodeRawHash)
    (decodeTip decodeRawHash)

pointJson :: Point B -> Value
pointJson (Point Origin) = object ["origin" .= True]
pointJson (Point (At (Block s h))) = object ["slot" .= toInteger (unSlotNo s), "hash" .= decodeUtf8 (Hex.encode (Short.fromShort h))]

verdict :: LBS.ByteString -> IO Value
verdict raw = do
  step <- decode codec SingIdle
  result <- runDecoder [raw] step :: IO (Either DeserialiseFailure (SomeMessage ('StIdle :: ChainSync BS8.ByteString (Point B) (Tip B))))
  pure $ case result of
    Left failure -> object ["accepted" .= False, "failure" .= show failure]
    Right (SomeMessage (MsgFindIntersect ps)) -> object ["accepted" .= True, "message" .= ("FindIntersect" :: String), "points" .= map pointJson ps]
    Right (SomeMessage MsgRequestNext) -> object ["accepted" .= True, "message" .= ("RequestNext" :: String)]
    Right (SomeMessage MsgDone) -> object ["accepted" .= True, "message" .= ("Done" :: String)]

main :: IO ()
main = do
  input <- BS8.getContents
  forM_ (BS8.lines input) $ \line -> case Hex.decode line of
    Left e -> LBS8.putStrLn (encode (object ["accepted" .= False, "failure" .= ("not hex: " <> e)]))
    Right bytes -> verdict (LBS.fromStrict bytes) >>= LBS8.putStrLn . encode
