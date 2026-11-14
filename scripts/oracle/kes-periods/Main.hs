-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE TypeApplications #-}
module Main where

import Cardano.Crypto.KES
import Cardano.Binary.FixedSizeCodec (rawEncodeFixedSized)
import Cardano.Crypto.DSIGN.Ed25519 (Ed25519DSIGN)
import Cardano.Crypto.Hash.Blake2b (Blake2b_256)
import Cardano.Crypto.Seed (mkSeedFromBytes)
import qualified Data.ByteString as BS
import Control.Monad (foldM, forM_)
import Data.List (intercalate)
import Numeric (showHex)

type K = Sum6KES Ed25519DSIGN Blake2b_256

hex :: BS.ByteString -> String
hex = concatMap (\b -> let s = showHex b "" in replicate (2 - length s) '0' ++ s) . BS.unpack

main :: IO ()
main = forM_ cases $ \(name, leaf, period) -> do
  let sk0 = unsoundPureGenKeyKES @K (mkSeedFromBytes (BS.replicate 32 7))
      vk = unsoundPureDeriveVerKeyKES @K sk0
      msg = BS.pack [75,69,83,45,112,114,111,98,101]
      indices = if leaf == 0 then [] else [0..leaf-1]
  case foldM (unsoundPureUpdateKES @K ()) sk0 indices of
    Nothing -> fail "key evolution failed"
    Just sk -> do
      let sig = unsoundPureSignKES @K () leaf msg sk
          verdict = either (const "REJECTED") (const "VERIFIED") (verifyKES @K () vk period msg sig)
      putStrLn $ intercalate "\t"
        [name, hex (rawEncodeFixedSized vk), show period, hex msg, hex (rawEncodeFixedSized sig), verdict]
  where
    cases :: [(String, Word, Word)]
    cases =
      [ ("leaf0-period0", 0, 0)
      , ("leaf0-period2pow32", 0, 4294967296)
      , ("leaf35-period35", 35, 35)
      , ("leaf35-period2pow32plus35", 35, 4294967331)
      , ("leaf63-period62", 63, 62)
      , ("leaf63-period63", 63, 63)
      , ("leaf63-period64", 63, 64)
      , ("leaf63-period2pow32plus63", 63, 4294967359)
      , ("leaf63-period-word64max-minus1", 63, 18446744073709551614)
      ]
