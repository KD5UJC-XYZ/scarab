-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
import Cardano.Crypto.PackedBytes (PackedBytes, packShortByteString)
import qualified Data.ByteString.Short as SBS
import Data.Word (Word8)

pb :: [Word8] -> PackedBytes 32
pb ws = maybe (error "not 32 bytes") id (packShortByteString (SBS.pack ws))

main :: IO ()
main = do
  let a = pb (0x80 : replicate 31 0)
      b = pb (0x7f : replicate 31 0xff)
      c = pb (replicate 31 0 ++ [0xff])
      d = pb (replicate 30 0 ++ [1, 0])
      e = pb (replicate 8 0 ++ [0x80] ++ replicate 23 0)
      f = pb (replicate 7 0 ++ [1] ++ replicate 24 0)
  print [compare a b, compare c d, compare e f, compare b a]
