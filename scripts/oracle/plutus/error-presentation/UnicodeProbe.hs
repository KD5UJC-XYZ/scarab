-- SPDX-License-Identifier: Apache-2.0
module Main (main) where

import Data.Char qualified as Native
import Data.List (groupBy)
import Data.Version (showVersion)
import GHC.Unicode (unicodeVersion)
import GHC.Unicode.Internal.Char.UnicodeData.GeneralCategory qualified as G96

ranges :: (Int -> Bool) -> [(Int, Int)]
ranges predicate =
  [ (first, foldl' (\_ next -> next) first rest)
  | first : rest <- groupBy (\a b -> predicate a == predicate b) [0 .. 0x10ffff]
  , predicate first
  ]

source96 :: Int -> Bool
source96 point = G96.generalCategory (Native.chr point) `notElem` [23, 24, 25, 26, 27, 28, 29]

main :: IO ()
main = do
  putStrLn ("nativeVersion " ++ showVersion unicodeVersion)
  putStrLn ("native " ++ show (ranges (Native.isPrint . Native.chr)))
  putStrLn ("ghc96Source " ++ show (ranges source96))
