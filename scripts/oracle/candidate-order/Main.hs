-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

import Cardano.Protocol.Crypto (StandardCrypto)
import Cardano.Base.Bytes (byteArrayFromByteString)
import qualified Cardano.Crypto.DSIGN as DSIGN
import qualified Cardano.Crypto.VRF as VRF
import Cardano.Ledger.Keys (VKey(..))
import Control.Monad (forM)
import Data.Aeson
import Data.Aeson.Types (Parser, parseEither)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy.Char8 as BL
import Data.List (sortBy)
import Ouroboros.Consensus.Block (BlockNo(..), SlotNo(..), IsEBB(..))
import Ouroboros.Consensus.Protocol.Abstract
import Ouroboros.Consensus.Protocol.PBFT (PBft, PBftTiebreakerView(..))
import Ouroboros.Consensus.Protocol.Praos (Praos)
import Ouroboros.Consensus.Protocol.Praos.Common
import System.Environment (getArgs)

praos :: Value -> Parser (Int, SelectView (Praos StandardCrypto))
praos = withObject "Praos view" $ \o -> do
  i <- o .: "id"
  n <- o .: "blockNo"
  slot <- o .: "slot"
  issuer <- o .: "issuer"
  issue <- o .: "issueNo"
  vrf <- o .: "vrf"
  key <- maybe (fail "invalid issuer bytes") pure $ DSIGN.rawDeserialiseVerKeyDSIGN (BS.replicate 32 issuer)
  pure (i, SelectView (BlockNo n) (PraosTiebreakerView (SlotNo slot) (VKey key) issue (VRF.OutputVRF (byteArrayFromByteString (BS.replicate 64 vrf)))))

pbft :: Value -> Parser (Int, SelectView (PBft ()))
pbft = withObject "PBFT view" $ \o -> do
  i <- o .: "id"
  n <- o .: "blockNo"
  ebb <- o .: "isEBB"
  pure (i, SelectView (BlockNo n) (PBftTiebreakerView (if ebb then IsEBB else IsNotEBB)))

observe :: Ord a => (a -> Bool) -> [(Int,a)] -> Value
observe eligible values = object
  [ "sortedIds" .= ids values
  , "eligibleIds" .= map fst allowed
  , "eligibleSortedIds" .= ids allowed
  , "pairwiseOrd" .= [[show (compare a b) | (_,b) <- values] | (_,a) <- values]
  ]
 where
  allowed = filter (eligible . snd) values
  ids = map fst . sortBy (\(_,a) (_,b) -> compare b a)

row :: Value -> Parser Value
row original = withObject "case" (\o -> do
  kind <- o .: "kind" :: Parser String
  local <- o .: "local"
  inputs <- o .: "input"
  result <- case kind of
    "praos" -> do
      distance <- o .: "restriction"
      (_,ours) <- praos local
      values <- traverse praos inputs
      pure $ observe (shouldSwitch . preferCandidate (RestrictedVRFTiebreaker (SlotNo distance)) ours) values
    "pbft" -> do
      (_,ours) <- pbft local
      values <- traverse pbft inputs
      pure $ observe (shouldSwitch . preferCandidate () ours) values
    _ -> fail "unsupported kind"
  pure $ object ["input" .= original, "output" .= result]) original

main :: IO ()
main = do
  [inputPath, outputPath, sortPath] <- getArgs
  inputs <- BL.lines <$> BL.readFile inputPath
  rows <- forM inputs $ \line -> either fail pure $ eitherDecode line >>= parseEither row
  BL.writeFile outputPath (BL.unlines (map encode rows))
  let cases = [[4,0,8],[8,0,4],[0,4,8],[8,4,0],[4,8,0],[0,8,4],[4,0,8,1,9,2,7]] :: [[Int]]
      cmp a b = if abs (a-b) > 5 then EQ else compare a b
      observeSort values = object
        [ "input" .= values
        , "sorted" .= sortBy cmp values
        , "pairwiseOrd" .= [[show (cmp a b) | b <- values] | a <- values]
        ]
  BL.writeFile sortPath (BL.unlines (map (encode . observeSort) cases))
