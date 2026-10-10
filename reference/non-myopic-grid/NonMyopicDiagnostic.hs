-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE OverloadedStrings #-}
module NonMyopicDiagnostic where
import qualified Main as R
import Cardano.Ledger.BaseTypes
import Cardano.Ledger.Shelley.PoolRank
import Cardano.Slotting.Slot (EpochSize(..))
import Control.Monad (unless)
import Data.Aeson
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BC
import qualified Data.ByteString.Lazy.Char8 as BL
import Data.Foldable (toList)
import Data.Ratio ((%))
import GHC.Float (castFloatToWord32, castDoubleToWord64)
import Numeric (showHex)
import System.Environment (getArgs)
import System.IO (withBinaryFile,IOMode(ReadMode))
canonical = BC.pack "{\"cases\":[{\"blocks\":\"0\",\"decentralization\":{\"d\":\"1\",\"n\":\"0\"},\"epochSlots\":\"1000\",\"f\":{\"d\":\"20\",\"n\":\"1\"},\"id\":\"sigma-1-3-blocks-0\",\"sigma\":{\"d\":\"3\",\"n\":\"1\"}},{\"blocks\":\"1\",\"decentralization\":{\"d\":\"1\",\"n\":\"0\"},\"epochSlots\":\"1000\",\"f\":{\"d\":\"20\",\"n\":\"1\"},\"id\":\"sigma-1-3-blocks-1\",\"sigma\":{\"d\":\"3\",\"n\":\"1\"}},{\"blocks\":\"4\",\"decentralization\":{\"d\":\"1\",\"n\":\"0\"},\"epochSlots\":\"1000\",\"f\":{\"d\":\"20\",\"n\":\"1\"},\"id\":\"sigma-1-3-blocks-4\",\"sigma\":{\"d\":\"3\",\"n\":\"1\"}},{\"blocks\":\"50\",\"decentralization\":{\"d\":\"1\",\"n\":\"0\"},\"epochSlots\":\"1000\",\"f\":{\"d\":\"20\",\"n\":\"1\"},\"id\":\"sigma-1-3-blocks-50\",\"sigma\":{\"d\":\"3\",\"n\":\"1\"}},{\"blocks\":\"0\",\"decentralization\":{\"d\":\"1\",\"n\":\"0\"},\"epochSlots\":\"1000\",\"f\":{\"d\":\"20\",\"n\":\"1\"},\"id\":\"sigma-1-6-blocks-0\",\"sigma\":{\"d\":\"6\",\"n\":\"1\"}},{\"blocks\":\"1\",\"decentralization\":{\"d\":\"1\",\"n\":\"0\"},\"epochSlots\":\"1000\",\"f\":{\"d\":\"20\",\"n\":\"1\"},\"id\":\"sigma-1-6-blocks-1\",\"sigma\":{\"d\":\"6\",\"n\":\"1\"}},{\"blocks\":\"4\",\"decentralization\":{\"d\":\"1\",\"n\":\"0\"},\"epochSlots\":\"1000\",\"f\":{\"d\":\"20\",\"n\":\"1\"},\"id\":\"sigma-1-6-blocks-4\",\"sigma\":{\"d\":\"6\",\"n\":\"1\"}},{\"blocks\":\"50\",\"decentralization\":{\"d\":\"1\",\"n\":\"0\"},\"epochSlots\":\"1000\",\"f\":{\"d\":\"20\",\"n\":\"1\"},\"id\":\"sigma-1-6-blocks-50\",\"sigma\":{\"d\":\"6\",\"n\":\"1\"}}],\"schema\":\"non-myopic-grid-input-v1\"}\n"
hex n width = let h=showHex n "" in replicate (width-length h) '0'++h
probe denominator blocks = object
  [ "id" .= ("sigma-1-" ++ show denominator ++ "-blocks-" ++ show blocks)
  , "sigma" .= R.ratioJSON sigma, "blocks" .= R.dec blocks
  , "f" .= R.ratioJSON (1%20), "decentralization" .= R.ratioJSON 0
  , "epochSlots" .= ("1000" :: String)
  , "leaderProbabilityBits" .= hex (castDoubleToWord64 probability) 16
  , "weights" .= map (\(LogWeight value) -> hex (castFloatToWord32 value) 8)
      (toList (unLikelihood (likelihood (fromInteger blocks) probability (EpochSize 1000))))
  ]
  where
    sigma=1%denominator
    probability=leaderProbability (activeSlotCoeff (R.globals (R.Case "grid" 0 0 0 True False 100 False []))) sigma (R.unit 0)
main = do
  args <- getArgs
  input <- case args of
    [path] -> withBinaryFile path ReadMode (\h -> BS.hGet h 65537)
    _ -> fail "usage: synthetic-reward-diff cases.json"
  unless (input == canonical) $ fail "exact bounded eight-case input required"
  BL.putStrLn $ encode $ object
    [ "schema" .= ("non-myopic-grid-native-v1" :: String)
    , "producer" .= ("native" :: String)
    , "inputSHA256" .= ("b32511d9b33b6d55d77f2c23dcf3c89a1f007ef365d58c28b510365da8e92e3e" :: String)
    , "cases" .= [probe denominator blocks | denominator <- [3,6], blocks <- [0,1,4,50]]
    ]
