-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE TypeApplications #-}
{-# LANGUAGE TypeFamilies #-}
{-# LANGUAGE GADTs #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ScopedTypeVariables #-}
module Main where

import Cardano.Protocol.Crypto (StandardCrypto)
import qualified Cardano.Crypto.Hash.Class as Hash
import qualified Cardano.Ledger.Shelley.API as SL
import Data.Aeson
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy.Char8 as BL
import Data.List (mapAccumL)
import qualified Marker
import Ouroboros.Consensus.Util.IOLike (atomically)
import qualified Ouroboros.Consensus.Storage.ChainDB.API.Types.InvalidBlockPunishment as P
import Data.Proxy
import qualified Data.Map.Strict as Map
import Data.Time.Clock.POSIX (posixSecondsToUTCTime)
import qualified Codec.CBOR.Read as CBOR
import Cardano.Ledger.BaseTypes (natVersion)
import Cardano.Ledger.Conway (ConwayEra)
import Ouroboros.Consensus.BlockchainTime (SystemStart(..))
import Ouroboros.Network.Magic (NetworkMagic(..))
import Ouroboros.Consensus.Protocol.Praos (Praos)
import Ouroboros.Consensus.Protocol.Praos.Common (VRFTiebreakerFlavor(..))
import Ouroboros.Consensus.Shelley.Ledger.Block (ShelleyBlock, decodeShelleyHeader)
import Ouroboros.Consensus.Shelley.Ledger.Config (BlockConfig(..))
import qualified Data.Set as Set
import Data.SOP.Strict (NS(..))
import Data.Word
import Numeric (showHex)
import System.Environment
import Ouroboros.Consensus.Block
import Ouroboros.Consensus.Block.SupportsDiffusionPipelining
import Ouroboros.Consensus.Cardano.Block
import Ouroboros.Consensus.Cardano.CanHardFork ()
import Ouroboros.Consensus.HardFork.Combinator.AcrossEras
import Ouroboros.Consensus.HardFork.Combinator.Node.DiffusionPipelining ()
import Ouroboros.Consensus.Protocol.Abstract (SelectView(..))
import Ouroboros.Consensus.Protocol.PBFT (PBftTiebreakerView(..))
import Ouroboros.Consensus.Shelley.Ledger.SupportsProtocol ()
import Ouroboros.Consensus.Shelley.Node.DiffusionPipelining
import Ouroboros.Consensus.TypeFamilyWrappers

type Block = CardanoBlock StandardCrypto
type St = TentativeHeaderState Block
type Vw = TentativeHeaderView Block

data Input = Input Int Word64 Word8 Word64 Bool deriving Show
hex :: BS.ByteString -> String
hex = concatMap (\x -> let a=showHex x "" in replicate (2-length a) '0'++a) . BS.unpack
identity :: Word8 -> Word64 -> HotIdentity c
identity issuer issue = HotIdentity (SL.KeyHash $ Hash.castHash $ Hash.hashWith id $ BS.replicate 32 issuer) issue
inputJSON :: Input -> Value
inputJSON (Input era height issuer issue ebb) = object ["era" .= era,"blockNo" .= height,
 "issuerVkey" .= hex (BS.replicate 32 issuer),"issuer" .= hex (Hash.hashToBytes h),"issueNo" .= issue,"isEBB" .= ebb]
 where SL.KeyHash h = hiIssuer (identity issuer issue)
view :: Input -> Vw
view (Input era height issuer issue ebb) = OneEraTentativeHeaderView $ case era of
  0 -> Z (WrapTentativeHeaderView $ SelectView (BlockNo height) (PBftTiebreakerView $ if ebb then IsEBB else IsNotEBB))
  1 -> S $ Z $ WrapTentativeHeaderView $ ShelleyTentativeHeaderView (BlockNo height) (identity issuer issue)
  2 -> S $ S $ Z $ WrapTentativeHeaderView $ ShelleyTentativeHeaderView (BlockNo height) (identity issuer issue)
  3 -> S $ S $ S $ Z $ WrapTentativeHeaderView $ ShelleyTentativeHeaderView (BlockNo height) (identity issuer issue)
  4 -> S $ S $ S $ S $ Z $ WrapTentativeHeaderView $ ShelleyTentativeHeaderView (BlockNo height) (identity issuer issue)
  5 -> S $ S $ S $ S $ S $ Z $ WrapTentativeHeaderView $ ShelleyTentativeHeaderView (BlockNo height) (identity issuer issue)
  6 -> S $ S $ S $ S $ S $ S $ Z $ WrapTentativeHeaderView $ ShelleyTentativeHeaderView (BlockNo height) (identity issuer issue)
  _ -> error "observer input domain is exactly the supported Byron..Conway eras"
numberJSON :: WithOrigin BlockNo -> Value
numberJSON Origin = Null
numberJSON (NotOrigin (BlockNo b)) = toJSON b
shelleyJSON :: Int -> ShelleyTentativeHeaderState p -> Value
shelleyJSON era (ShelleyTentativeHeaderState height issuers) = object ["era" .= era,
 "lastBlockNo" .= numberJSON height,"badIdentities" .= map ident (Set.toAscList issuers)]
 where ident (HotIdentity (SL.KeyHash h) issue) = object ["issuer" .= hex (Hash.hashToBytes h),"issueNo" .= issue]
stateJSON :: St -> Value
stateJSON (OneEraTentativeHeaderState s) = case s of
  Z (WrapTentativeHeaderState st) -> object ["era" .= (0::Int),"previous" .= case st of
    NoLastInvalidSelectView -> Null
    LastInvalidSelectView (SelectView (BlockNo b) (PBftTiebreakerView ebb)) -> object ["blockNo" .= b,"isEBB" .= (ebb==IsEBB)]]
  S (Z (WrapTentativeHeaderState st)) -> shelleyJSON 1 st
  S (S (Z (WrapTentativeHeaderState st))) -> shelleyJSON 2 st
  S (S (S (Z (WrapTentativeHeaderState st)))) -> shelleyJSON 3 st
  S (S (S (S (Z (WrapTentativeHeaderState st))))) -> shelleyJSON 4 st
  S (S (S (S (S (Z (WrapTentativeHeaderState st)))))) -> shelleyJSON 5 st
  S (S (S (S (S (S (Z (WrapTentativeHeaderState st))))))) -> shelleyJSON 6 st
  _ -> error "no exported case enters the unsupported future Dijkstra era"
observe :: String -> [Input] -> Value
observe label inputs = object ["label" .= label,"steps" .= snd (mapAccumL step initial inputs)]
 where
  initial = initialTentativeHeaderState (Proxy @Block)
  step old input = let { result=applyTentativeHeaderView (Proxy @Block) (view input) old; next=maybe old id result }
                  in (next,object ["input" .= inputJSON input,"accepted" .= maybe False (const True) result,
                       "state" .= stateJSON next,"completeNativeState" .= show next])
-- Completion is synchronized with the actual child, never a scheduler delay.
observePunishment :: Marker.Observer -> (P.InvalidBlockPunishment IO -> P.InvalidBlockPunishment IO) -> P.Invalidity -> IO Bool
observePunishment observer make invalidity = do
 marker <- Marker.start
 P.enact (make $ Marker.punishment marker) invalidity
 Marker.finish observer marker
main :: IO ()
main = do
 observer <- Marker.calibrate
 args <- getArgs
 let output=head args
 case args of
  [_,headerFile,projectionFile] -> do
   raw <- BL.readFile headerFile
   case CBOR.deserialiseFromBytes (decodeShelleyHeader @(Praos StandardCrypto) @ConwayEra) raw of
    Left err -> fail (show err)
    Right (rest,annotate) | BL.null rest -> do
     let header=annotate raw
         cfg=ShelleyConfig (SL.ProtVer (natVersion @9) 0) (SystemStart $ posixSecondsToUTCTime 0) (NetworkMagic 2) Map.empty UnrestrictedVRFTiebreaker
         ShelleyTentativeHeaderView (BlockNo height) (HotIdentity (SL.KeyHash issuer) issue)=tentativeHeaderView cfg header
     criterion <- atomically $ P.mkForDiffusionPipelining @IO @(ShelleyBlock (Praos StandardCrypto) ConwayEra)
     let punishment target=P.branch $ \invalidity -> case invalidity of
          P.BlockPrefix -> target
          P.BlockItself -> criterion cfg header target
     prefix <- observePunishment observer punishment P.BlockPrefix
     first <- observePunishment observer punishment P.BlockItself
     repeated <- observePunishment observer punishment P.BlockItself
     fresh <- atomically $ P.mkForDiffusionPipelining @IO @(ShelleyBlock (Praos StandardCrypto) ConwayEra)
     reset <- observePunishment observer (fresh cfg header) P.BlockItself
     BL.writeFile projectionFile $ encode $ object ["dslPunished" .= [prefix,first,repeated,reset],"header" .= hex (BL.toStrict raw),"era" .= (6::Int),"blockNo" .= height,"issuer" .= hex (Hash.hashToBytes issuer),"issueNo" .= issue]
    Right _ -> fail "unconsumed original header bytes"
  [_] -> pure ()
  _ -> fail "expected corpus-output [original-header projection-output]"
 let byron = [Input 0 2 0 0 False,Input 0 2 0 0 False,Input 0 2 0 0 True,Input 0 2 0 0 False,
              Input 0 1 0 0 True,Input 0 3 0 0 False,Input 0 maxBound 0 0 False,Input 0 maxBound 0 0 True]
     eraCases = [("era-"++show era,[Input era 10 1 0 False,Input era 10 1 0 False,
       Input era 10 2 0 False,Input era 10 1 1 False,Input era 9 3 0 False,
       Input era 11 1 0 False,Input era 11 2 0 False,Input era 11 1 maxBound False,
       Input era maxBound 3 0 False,Input era maxBound 4 maxBound False]) | era<-[1..6]]
     transition = [Input era 10 1 0 False | era<-[0..6]] ++ [Input era 11 2 0 False | era<-reverse [0..6]]
     generated = [("generated-"++show seed,[Input ((seed+i*7) `mod` 7) (fromIntegral $ (seed+i*13) `mod` 19)
          (fromIntegral $ (seed+i*5) `mod` 5) (fromIntegral $ (seed+i*3) `mod` 4) (odd i) | i<-[0..63]]) | seed<-[1..64]]
     rows=map (uncurry observe) (("byron-ebb-boundaries",byron):("all-supported-hardforks",transition):eraCases++generated)
 BL.writeFile output $ BL.unlines $ map encode rows
