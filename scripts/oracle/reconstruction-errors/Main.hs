-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DeriveDataTypeable #-}
{-# LANGUAGE ScopedTypeVariables #-}
module Main where
import Control.Exception (Exception, evaluate, throw, try)
import qualified Codec.CBOR.Decoding as D
import qualified Codec.CBOR.Read as R
import qualified Data.ByteString.Lazy as BS
import Data.Typeable (Typeable)

data Original = Original Int deriving (Show, Eq, Typeable)
instance Exception Original
main :: IO ()
main = do
  let raw = BS.pack [0]
  accepted <- evaluate (R.deserialiseFromBytes D.decodeWord raw)
  putStrLn ("accepted=" ++ show (either (const False) (const True) accepted))
  refused <- evaluate (R.deserialiseFromBytes (fail "controlled refusal" :: D.Decoder s ()) raw)
  putStrLn ("refused=" ++ show (either (const True) (const False) refused))
  thrown <- try (evaluate (R.deserialiseFromBytes (throw (Original 493) :: D.Decoder s ()) raw))
  putStrLn ("foreign-original=" ++ show (case thrown of Left (actual :: Original) -> actual == Original 493; Right _ -> False))
