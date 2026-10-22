-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE GADTs #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE TypeFamilies #-}
module Main (main) where

import Cardano.Base.Bytes (byteArrayFromByteString)
import qualified Cardano.Crypto.DSIGN as DSIGN
import qualified Cardano.Crypto.VRF as VRF
import Cardano.Ledger.Keys (VKey(..))
import Cardano.Protocol.Crypto (StandardCrypto)
import Control.Monad (forM)
import Data.Aeson
import Data.Aeson.Types (Parser, parseEither)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy.Char8 as BL
import Data.List (sortBy)
import Data.SOP.Strict (NS(..), NP(..))
import Ouroboros.Consensus.Block
import Ouroboros.Consensus.Cardano.Block (CardanoBlock, CardanoEras)
import Ouroboros.Consensus.Cardano.CanHardFork ()
import Ouroboros.Consensus.Shelley.Ledger.SupportsProtocol ()
import Ouroboros.Consensus.HardFork.Combinator.AcrossEras
import Ouroboros.Consensus.HardFork.Combinator.Protocol (HardForkTiebreakerView(..))
import Ouroboros.Consensus.Protocol.Abstract
import Ouroboros.Consensus.Protocol.PBFT (PBftTiebreakerView(..))
import Ouroboros.Consensus.Protocol.Praos.Common
import Ouroboros.Consensus.TypeFamilyWrappers
import System.Environment (getArgs)

type Block = CardanoBlock StandardCrypto
type View = SelectView (BlockProtocol Block)

view :: Value -> Parser (Int, View)
view = withObject "HFC view" $ \o -> do
  i <- o .: "id"
  era <- o .: "era" :: Parser Int
  n <- o .: "blockNo"
  slot <- o .: "slot"
  issuer <- o .: "issuer"
  issue <- o .: "issueNo"
  vrf <- o .: "vrf"
  ebb <- o .: "isEBB"
  key <- maybe (fail "invalid issuer bytes") pure $ DSIGN.rawDeserialiseVerKeyDSIGN (BS.replicate 32 issuer)
  let praos = PraosTiebreakerView (SlotNo slot) (VKey key) issue (VRF.OutputVRF (byteArrayFromByteString (BS.replicate 64 vrf)))
  ns <- case era of
    0 -> pure $ Z $ WrapTiebreakerView $ PBftTiebreakerView $ if ebb then IsEBB else IsNotEBB
    1 -> pure $ S $ Z $ WrapTiebreakerView praos
    2 -> pure $ S $ S $ Z $ WrapTiebreakerView praos
    3 -> pure $ S $ S $ S $ Z $ WrapTiebreakerView praos
    4 -> pure $ S $ S $ S $ S $ Z $ WrapTiebreakerView praos
    5 -> pure $ S $ S $ S $ S $ S $ Z $ WrapTiebreakerView praos
    6 -> pure $ S $ S $ S $ S $ S $ S $ Z $ WrapTiebreakerView praos
    _ -> fail "input domain is supported Byron through Conway"
  pure (i, SelectView (BlockNo n) (HardForkTiebreakerView (OneEraTiebreakerView ns)))

config :: PerEraChainOrderConfig (CardanoEras StandardCrypto)
config = PerEraChainOrderConfig $
  WrapChainOrderConfig () :*
  WrapChainOrderConfig UnrestrictedVRFTiebreaker :*
  WrapChainOrderConfig UnrestrictedVRFTiebreaker :*
  WrapChainOrderConfig UnrestrictedVRFTiebreaker :*
  WrapChainOrderConfig UnrestrictedVRFTiebreaker :*
  WrapChainOrderConfig UnrestrictedVRFTiebreaker :*
  WrapChainOrderConfig (RestrictedVRFTiebreaker (SlotNo 5)) :*
  WrapChainOrderConfig (RestrictedVRFTiebreaker (SlotNo 5)) :* Nil

row :: Value -> Parser Value
row original = withObject "HFC case" (\o -> do
  a <- o .: "local" >>= view
  b <- o .: "candidate" >>= view
  let (_,ours) = a
      (_,candidate) = b
  pure $ object
    [ "input" .= original
    , "output" .= object
      [ "ord" .= show (compare ours candidate)
      , "preferCandidate" .= shouldSwitch (preferCandidate config ours candidate)
      , "preferLocal" .= shouldSwitch (preferCandidate config candidate ours)
      , "sortedIds" .= map fst (sortBy (\(_,x) (_,y) -> compare y x) [a,b])
      ]
    ]) original

main :: IO ()
main = do
  [inputPath, outputPath] <- getArgs
  inputs <- BL.lines <$> BL.readFile inputPath
  rows <- forM inputs $ \line -> either fail pure $ eitherDecode line >>= parseEither row
  BL.writeFile outputPath (BL.unlines (map encode rows))
