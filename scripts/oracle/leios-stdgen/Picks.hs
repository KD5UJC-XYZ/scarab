-- SPDX-License-Identifier: Apache-2.0
import System.Random
import Data.List (delete)

-- the eligible ids of one bucket, ascending, drawn as `pickLeastRequestedJobExcept` draws:
-- `uniformR (0, size - 1)` and the id at that index of the ascending list
picks :: StdGen -> [Int] -> Int -> [Int]
picks _ _ 0 = []
picks _ [] _ = []
picks g eligible n =
  let (i, g') = uniformR (0, length eligible - 1) g
      jid = eligible !! i
   in jid : picks g' (delete jid eligible) (n - 1)

main :: IO ()
main = do
  print ("picks0", picks (mkStdGen 0) [0 .. 5] 6)
  print ("picks4", picks (mkStdGen 4) [0 .. 5] 6)
  print ("picks7", picks (mkStdGen 7) [0 .. 9] 10)
