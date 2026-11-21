-- SPDX-License-Identifier: Apache-2.0
import System.Random
import System.Random.SplitMix (unseedSMGen)
import System.Random.Internal (StdGen(..))
import Data.List (unfoldr)
import Data.Word

st :: StdGen -> (Word64, Word64)
st = unseedSMGen . unStdGen

main :: IO ()
main = do
  -- seeds and gammas after mkStdGen
  mapM_ (\s -> print ("mk", s, st (mkStdGen s))) [0, 1, 4, 42, -1]
  -- raw 64-bit words
  mapM_ (\s -> print ("w64", s, take 4 (unfoldr (Just . genWord64) (mkStdGen s)))) [0, 4]
  -- uniformR draws for several ranges
  mapM_ (\(s, n) -> print ("ur", s, n, take 12 (unfoldr (Just . uniformR (0 :: Int, n)) (mkStdGen s)))) [(0, 5), (4, 0), (4, 1), (7, 2), (99, 183), (3, 1000000)]
  -- the state after a run of draws
  print ("after", st (snd (uniformR (0 :: Int, 5) (mkStdGen 0))))
  -- splitGen
  mapM_ (\s -> let (a, b) = splitGen (mkStdGen s) in print ("split", s, st a, st b)) [0, 4]
