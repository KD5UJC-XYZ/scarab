{-# LANGUAGE OverloadedStrings #-}
-- SPDX-License-Identifier: Apache-2.0
-- Source-only effect adapter. Raw stdin is the independently admitted script CBOR.
-- Emits all artifacts as hex JSON; it creates no files and does not invoke helper.
module Main where
import PacketBuilder
import VariantBuilder (variants)
import Control.Exception (evaluate)
import Data.Aeson (Value, object, (.=), encode)
import qualified Data.ByteString as B
import qualified Data.ByteString.Lazy as L
import qualified Data.Text as T
import Numeric (showHex)
import System.IO (stdin)
import System.Timeout (timeout)

hex :: B.ByteString -> T.Text
hex = T.pack . concatMap (\n -> let x = showHex n "" in if length x == 1 then '0':x else x) . B.unpack
packetJson :: Packet -> Value
packetJson p = object
  [ "transactionCborHex" .= hex (transactionBytes p)
  , "parametersCborHex" .= hex (parameterBytes p)
  , "reviewedScriptHex" .= hex (reviewedScriptBytes p)
  , "utxo" .= [object ["inputCborHex" .= hex a, "outputCborHex" .= hex b] | (a,b) <- preStateEntries p]
  ]
bundle :: B.ByteString -> Either String Value
bundle script = do
  rows <- variants script
  pure $ object
    [ "classification" .= ("original bounded synthetic translator variants; not full-ledger validation" :: T.Text)
    , "sourceScriptSha256" .= sourceScriptSha256
    , "variants" .= [object ["name" .= name, "spec" .= spec, "packet" .= packetJson p] | (name,spec,p) <- rows]
    ]
main :: IO ()
main = do
  -- The outer reviewed runner still supplies CPU/memory/network/process limits.
  completed <- timeout 30000000 $ do
    raw <- B.hGet stdin 65537
    if B.length raw > 65536 then fail "script input exceeds 64 KiB" else do
      result <- either fail pure (bundle raw)
      let output = encode result
      n <- evaluate (L.length output)
      if n > 8*1024*1024 then fail "builder output exceeds 8 MiB" else pure output
  case completed of
    Nothing -> fail "builder deadline exceeded"
    Just result -> L.putStr result
