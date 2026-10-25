-- SPDX-License-Identifier: Apache-2.0
-- | Prints the reference `serialiseData` bytes (and their SHA-256) of constructor data with wide
-- indices. The denotation is the one of the builtin: `BSL.toStrict . serialise`
-- (PlutusCore/Default/Builtins.hs, `SerialiseData`), over `PlutusCore.Data.encodeData`.
module Main where

import Codec.Serialise (serialise)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Char8 as BC
import qualified Data.ByteString.Lazy as BSL
import Data.Foldable (for_)
import PlutusCore.Crypto.Hash (sha2_256)
import PlutusCore.Data (Data (..))
import System.Environment (getArgs)

serialiseData :: Data -> BS.ByteString
serialiseData = BSL.toStrict . serialise

hex :: BS.ByteString -> String
hex = BC.unpack . Hex.encode

-- | Columns: bit length of the magnitude, sign, index (decimal), serialiseData, sha2_256 of it,
-- the bytes a chunked-integer index would give, and their sha2_256.
wide :: IO ()
wide = for_ [65, 72, 512, 513, 600, 1024 :: Int] $ \bits ->
  for_ [(1 :: Integer, "+"), (-1, "-")] $ \(sign, name) -> do
    let i = sign * (2 ^ bits - 1)
        bytes = serialiseData (Constr i [])
        -- tag 102, array(2), the index as `I` writes it, empty fields
        chunked = BS.concat [BS.pack [0xd8, 0x66, 0x82], serialiseData (I i), BS.pack [0x80]]
    putStrLn $ unwords
      [show bits, name, show i, hex bytes, hex (sha2_256 bytes), hex chunked, hex (sha2_256 chunked)]

-- | `--indices`: the constructor indices around the sign and Word64 boundaries. Columns: index
-- (decimal), serialiseData of `Constr i []`, and of `Constr i [I 1]`.
indices :: IO ()
indices = for_ [-1, -(2 ^ (64 :: Int)), -(2 ^ (600 :: Int)), 2 ^ (64 :: Int) - 1, 2 ^ (64 :: Int)] $ \i ->
  putStrLn $ unwords [show (i :: Integer), hex (serialiseData (Constr i [])), hex (serialiseData (Constr i [I 1]))]

main :: IO ()
main = do
  args <- getArgs
  if args == ["--indices"] then indices else wide
