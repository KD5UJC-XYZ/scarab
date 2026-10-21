{-# LANGUAGE NamedFieldPuns #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TypeApplications #-}
-- SPDX-License-Identifier: Apache-2.0
-- Thin observer: all consensus decisions below call the pinned native implementation.
module Main where
import Cardano.Ledger.BaseTypes (nonZero)
import Data.Aeson
import qualified Data.ByteString.Lazy.Char8 as BL
import qualified Data.List.NonEmpty as NE
import qualified Data.Map.Strict as Map
import Data.Maybe (fromJust)
import Data.Maybe.Strict (StrictMaybe(..))
import Data.Word
import Numeric (showHex)
import Ouroboros.Consensus.Block
import Ouroboros.Consensus.Config.SecurityParam
import Ouroboros.Consensus.Genesis.Governor
import Ouroboros.Consensus.HeaderValidation
import Ouroboros.Consensus.MiniProtocol.ChainSync.Client.State
import qualified Ouroboros.Network.AnchoredFragment as AF
import qualified Ouroboros.Network.Mock.Chain as Chain
import System.Environment
import Test.QuickCheck (arbitrary)
import Test.QuickCheck.Gen (unGen)
import Test.QuickCheck.Random (mkQCGen)
import Test.Util.Header
import Test.Util.TestBlock

type Frag = AF.AnchoredFragment (HeaderWithTime TestBlock)
pointJSON :: Point TestBlock -> Value
pointJSON GenesisPoint = Null
pointJSON (BlockPoint slot hash) = object ["slot" .= unSlotNo slot, "hash" .= concatMap fixed (NE.toList $ unTestHash hash)]
  where fixed x = let s = showHex x "" in replicate (16 - length s) '0' ++ s
fragmentJSON :: Frag -> Value
fragmentJSON fragment = object ["anchor" .= pointJSON (castPoint $ AF.anchorPoint fragment), "headers" .= map (pointJSON . castPoint . blockPoint) (AF.toOldestFirst fragment)]
latestJSON :: StrictMaybe (WithOrigin SlotNo) -> Value
latestJSON SNothing = object ["tag" .= ("unknown" :: String)]
latestJSON (SJust Origin) = object ["tag" .= ("origin" :: String)]
latestJSON (SJust (NotOrigin slot)) = object ["tag" .= ("at" :: String), "slot" .= unSlotNo slot]
stateJSON :: (Int, ChainSyncState TestBlock) -> Value
stateJSON (peer, ChainSyncState{csCandidate,csLatestSlot,csIdling}) = object ["peer" .= peer, "candidate" .= fragmentJSON csCandidate, "latestSlot" .= latestJSON csLatestSlot, "idling" .= csIdling]
boundsJSON :: (Int,DensityBounds TestBlock) -> Value
boundsJSON (peer,DensityBounds{clippedFragment,offersMoreThanK,lowerBound,upperBound,hasBlockAfter,latestSlot,idling}) = object
  ["peer" .= peer,"clippedFragment" .= fragmentJSON (AF.mapAnchoredFragment (attachSlotTime singleNodeTestConfig) clippedFragment),
   "offersMoreThanK" .= offersMoreThanK,"lowerBound" .= lowerBound,"upperBound" .= upperBound,
   "hasBlockAfter" .= hasBlockAfter,"latestSlot" .= latestJSON (SJust latestSlot),"idling" .= idling]
observe :: String -> Word64 -> Word64 -> Frag -> [(Int,ChainSyncState TestBlock)] -> Value
observe label window k current inputs = object
  ["label" .= label, "genesisWindow" .= window, "k" .= k, "current" .= fragmentJSON current,
   "states" .= map stateJSON inputs, "sharedPrefix" .= fragmentJSON prefix,
   "suffixes" .= [object ["peer" .= p,"fragment" .= fragmentJSON f] | (p,f)<-suffixes],
   "losingPeers" .= losers,"bounds" .= map boundsJSON bounds]
  where
    states = Map.fromList inputs
    (prefix,suffixes) = sharedCandidatePrefix current [(p,csCandidate s) | (p,s)<-Map.toList states]
    (losers,bounds) = densityDisconnect (GenesisWindow window) (SecurityParam $ fromJust $ nonZero k) states suffixes prefix
attach :: [TestBlock] -> Frag
attach = attachSlotTimeToFragment singleNodeTestConfig . AF.fromOldestFirst AF.AnchorGenesis . map getHeader
empty :: Frag
empty = attach []
mkState :: Frag -> StrictMaybe (WithOrigin SlotNo) -> Bool -> ChainSyncState TestBlock
mkState f l idle = ChainSyncState{csCandidate=f, csLatestSlot=l, csIdling=idle}
known :: Frag -> StrictMaybe (WithOrigin SlotNo)
known f = SJust (AF.headSlot f)
chain :: Int -> Word64 -> [TestBlock]
chain n branch = take n $ iterate successorBlock (firstBlock branch)
generated :: Int -> Value
generated seed = observe ("generated-BlockTree-" ++ show seed) window k current states
  where
    tree = unGen (arbitrary @BlockTree) (mkQCGen seed) (10 + seed `mod` 55)
    fragments = [attach $ Chain.toOldestFirst c | c <- treeToChains tree]
    current = case fragments of [] -> empty; f:_ -> AF.takeOldest (seed `mod` 4) f
    window = fromIntegral (1 + seed `mod` 20)
    k = fromIntegral (1 + seed `mod` 5)
    states = [(p,mkState f (if (p+seed) `mod` 7==0 then SNothing else if (p+seed) `mod` 5==0 then SJust (NotOrigin 100) else known f) ((p+seed) `mod` 3==0)) | (p,f)<-zip [0..] fragments]
boundaries :: [Value]
boundaries =
  [observe ("boundary-"++show ordinal) window k current states |
   (ordinal,(window,k,current,states))<-zip [(0::Int)..]
    [ (5,1,empty,[])
    , (5,1,empty,[(0,mkState a (known a) False)])
    , (5,1,empty,[(0,mkState a SNothing False),(1,mkState b (known b) False)])
    , (5,1,empty,[(0,mkState empty (SJust Origin) False),(1,mkState a (known a) False)])
    , (5,1,empty,[(0,mkState empty (SJust Origin) True),(1,mkState a (known a) False)])
    , (5,9,empty,[(0,mkState a (SJust (NotOrigin 5)) False),(1,mkState b (SJust (NotOrigin 5)) False)])
    , (5,1,empty,[(0,mkState a (SJust (NotOrigin 4)) False),(1,mkState b (SJust (NotOrigin 4)) False)])
    , (5,1,AF.anchorNewest 3 a,[(0,mkState a (known a) False),(1,mkState b (known b) False)])
    ]]
  where a=attach $ chain 5 0; b=attach $ chain 5 1
main :: IO ()
main = do
  [output] <- getArgs
  BL.writeFile output $ BL.unlines $ map encode (boundaries ++ map generated [330001..330128])
