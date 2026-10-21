-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE GADTs #-}
{-# LANGUAGE OverloadedStrings #-}
import Control.Monad (forM_)
import Data.Aeson
import qualified Data.ByteString.Lazy.Char8 as B
import NativeBlock (BlockNo)
import NativePoint (WithOrigin(..))
import Network.TypedProtocol.Peer (Nat(..))
import Ouroboros.Network.Protocol.ChainSync.PipelineDecision

data SomeNat where SomeNat :: Nat n -> SomeNat
nat :: Int -> SomeNat
nat 0 = SomeNat Zero
nat n = case nat (n-1) of SomeNat x -> SomeNat (Succ x)
label :: PipelineDecision n -> String
label Request = "Request"
label Pipeline = "Pipeline"
label CollectOrPipeline = "CollectOrPipeline"
label Collect = "Collect"

tip :: Maybe Integer -> WithOrigin BlockNo
tip = maybe Origin (At . fromIntegral)

main :: IO ()
main = forM_ [(0,0),(0,1),(1,1),(1,2),(2,3),(10,20),(200,300)] $ \(low,high) -> do
  let controls = [(0,Nothing,Nothing), (0,Just 2,Just 2), (0,Just 3,Just 2),
                  (0,Nothing,Just 50), (1,Nothing,Just 2), (1,Nothing,Just 3),
                  (high+1,Just 1,Just 1000), (low+1,Just 1,Just 1000),
                  (low,Just 1,Just 1000), (1,Just 100,Just 100),
                  (0,Just 100,Just 100), (1,Just 1,Just 1000),
                  (1,Just 18446744073709551615,Just 1),
                  (1,Just 18446744073709551614,Just 18446744073709551615),
                  (2,Just 18446744073709551615,Just 1),
                  (high+1,Just 1,Just 1000), (0,Just 1,Just 1000),
                  (1,Just 1,Just 1000)]
      grid = [(n,cli,srv) | n <- [0,1,low,low+1,high,high+1],
                           cli <- [Nothing,Just 0,Just 1,Just 100],
                           srv <- [Nothing,Just 0,Just 1,Just 100]]
      steps = controls ++ grid
      go _ [] = []
      go policy ((n,cli,srv):rest) = case nat n of
        SomeNat count -> let (out,next) = runPipelineDecision policy count (tip cli) (tip srv)
                        in object ["outstanding" .= n,"client" .= cli,"server" .= srv,
                                   "decision" .= label out] : go next rest
  B.putStrLn $ encode $ object ["low" .= low,"high" .= high,
    "steps" .= go (pipelineDecisionLowHighMark (fromIntegral low) (fromIntegral high)) steps]
