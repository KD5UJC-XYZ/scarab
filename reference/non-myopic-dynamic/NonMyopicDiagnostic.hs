-- SPDX-License-Identifier: Apache-2.0
module NonMyopicDiagnostic where
import qualified Main as R
import Cardano.Ledger.BaseTypes
import Cardano.Ledger.Shelley.PoolRank
import Cardano.Slotting.Slot (EpochSize(..))
import Control.Monad (unless)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BC
import Data.Foldable (toList)
import Data.Ratio ((%))
import Data.Char (isDigit)
import GHC.Float (castFloatToWord32,castDoubleToWord64)
import Numeric (showHex)
import System.Environment (getArgs)
import System.IO (withBinaryFile,IOMode(ReadMode))
hex n width = let h=showHex n "" in replicate (width-length h) '0'++h
lowerHex n s = length s == n && all (`elem` "0123456789abcdef") s
number s = do
  unless (not (null s) && all isDigit s && (s == "0" || head s /= '0')) $ fail "canonical unsigned integer"
  let n=read s :: Integer
  unless (n <= 18446744073709551615) $ fail "uint64 bound"
  pure n
row line = case words line of
  [pool,s,c,b] -> do
    stake <- number s; circulation <- number c; blocks <- number b
    unless (lowerHex 56 pool && circulation > 0 && stake <= circulation && blocks <= 1000) $ fail "row bound"
    let probability = leaderProbability (activeSlotCoeff (R.globals (R.Case "dynamic" 0 0 0 True False 100 False []))) (stake%circulation) (R.unit 0)
    let weights = toList (unLikelihood (likelihood (fromInteger blocks) probability (EpochSize 1000)))
    pure (pool ++ " " ++ hex (castDoubleToWord64 probability) 16 ++ " " ++ concatMap (\(LogWeight value) -> hex (castFloatToWord32 value) 8) weights)
  _ -> fail "row shape"
main = do
  args <- getArgs
  input <- case args of
    [path] -> withBinaryFile path ReadMode (\h -> BS.hGet h 16385)
    _ -> fail "usage: synthetic-reward-diff request.txt"
  unless (BS.length input <= 16384 && not (BS.null input) && BS.last input == 10) $ fail "input bound"
  case lines (BC.unpack input) of
    "conway-native-likelihood-v1":identity:"1000 1 20 0 1":rows -> do
      unless (lowerHex 64 identity && length rows <= 64) $ fail "source/domain bound"
      output <- mapM row rows
      BC.putStr input
      putStrLn "--native--"
      mapM_ putStrLn output
    _ -> fail "input schema"
