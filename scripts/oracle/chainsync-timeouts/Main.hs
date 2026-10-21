-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE GADTs #-}
{-# LANGUAGE OverloadedStrings #-}
module Main where

import Cardano.Network.PeerSelection.PeerTrustable
import Cardano.Network.Protocol.ChainSync.Codec.TimeLimits
import Control.Monad (forM_)
import Data.Aeson (encode, object, (.=))
import qualified Data.ByteString.Lazy.Char8 as LBS
import Data.Ratio (numerator, denominator)
import Data.Word (Word64)
import Ouroboros.Network.Protocol.ChainSync.Codec
import Ouroboros.Network.Protocol.ChainSync.Type
import Ouroboros.Network.Protocol.Limits
import System.Random (StdGen, mkStdGen, randomR, uniform)
import System.Random.Internal (scaleFloating)

states :: [String]
states = ["Idle", "Intersect", "CanAwait", "MustReply", "MustReply", "CanAwait", "Idle", "MustReply", "Intersect", "MustReply", "CanAwait", "Idle"]

main :: IO ()
main = do
  forM_ [0, 1, 33, 149, 310, 311, 601, 911] $ \seed ->
    forM_ [False, True] $ \trusted ->
      forM_ [False, True] $ \noIdle -> do
        let idle = if noIdle then ChainSyncNoIdleTimeout else ChainSyncIdleTimeout 3373
            trust = if trusted then IsTrustable else IsNotTrustable
            limits = timeLimitsChainSync idle trust :: ProtocolTimeLimitsWithRnd (ChainSync () () ())
            go _ [] = pure ()
            go gen ((index,state):rest) = do
              let (deadline,after) = case state of
                    "Idle" -> timeLimitForStateWithRnd limits SingIdle gen
                    "Intersect" -> timeLimitForStateWithRnd limits SingIntersect gen
                    "CanAwait" -> timeLimitForStateWithRnd limits (SingNext SingCanAwait) gen
                    _ -> timeLimitForStateWithRnd limits (SingNext SingMustReply) gen
                  sampled = state == "MustReply" && not trusted
                  (seconds,sampleAfter) = randomR (601 :: Double,911) gen
                  (word,wordAfter) = uniform gen :: (Word64,StdGen)
                  ratio = toRational seconds
                  picos t = floor (toRational t * 1000000000000) :: Integer
              if sampled && (sampleAfter /= after || wordAfter /= after || scaleFloating 601 911 word /= seconds)
                then fail "native sample/word/state generator disagreement"
                else LBS.putStrLn $ encode $ object
                  ["kind" .= ("state" :: String), "seed" .= seed, "index" .= (index :: Int),
                   "trusted" .= trusted, "noIdle" .= noIdle, "state" .= state,
                   "generatorBefore" .= show gen, "generatorAfter" .= show after,
                   "advanced" .= (gen /= after), "picoseconds" .= fmap (show . picos) deadline,
                   "sampleWord64" .= (if sampled then Just (show word) else Nothing),
                   "sampleSeconds" .= (if sampled then Just seconds else Nothing),
                   "sampleNumerator" .= (if sampled then Just (show (numerator ratio)) else Nothing),
                   "sampleDenominator" .= (if sampled then Just (show (denominator ratio)) else Nothing)]
              go after rest
        go (mkStdGen seed) (zip [0..] states)
  forM_ [0,1,9223372036854775807,9223372036854775808,9223372036854775809,18446744073709551614,18446744073709551615] $ \word -> do
    let seconds = scaleFloating 601 911 word :: Double
        ratio = toRational seconds
    LBS.putStrLn $ encode $ object
      ["kind" .= ("word" :: String), "word64" .= show (word :: Word64),
       "sampleSeconds" .= seconds, "sampleNumerator" .= show (numerator ratio),
       "sampleDenominator" .= show (denominator ratio),
       "picoseconds" .= show (floor (ratio * 1000000000000) :: Integer)]
