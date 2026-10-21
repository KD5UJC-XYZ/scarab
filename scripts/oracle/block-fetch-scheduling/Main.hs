{-# LANGUAGE NamedFieldPuns #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE TypeFamilies #-}
-- SPDX-License-Identifier: Apache-2.0
-- Observes pinned native functions. No scheduler implementation is copied here.
module Main where
import Data.Aeson (Value, object, (.=), encode)
import qualified Data.ByteString.Lazy.Char8 as LBS
import qualified Data.ByteString.Char8 as BS
import Data.Bits (xor)
import Data.Word (Word64)
import qualified Data.Set as Set
import Numeric (showHex)
import System.Environment (getArgs)
import Test.QuickCheck (arbitrary, vectorOf)
import Test.QuickCheck.Gen (unGen)
import Test.QuickCheck.Random (mkQCGen)
import Control.Monad.Class.MonadTime.SI (DiffTime, Time(..))
import Ouroboros.Network.Block
import qualified Ouroboros.Network.Point as P
import qualified Ouroboros.Network.AnchoredFragment as AF
import qualified Ouroboros.Network.Mock.Chain as Chain
import Ouroboros.Network.Mock.ChainGenerators (TestChainFork(..))
import Ouroboros.Network.Mock.ConcreteBlock
import Ouroboros.Network.DeltaQ
import qualified Ouroboros.Network.BlockFetch.Decision as D
import Ouroboros.Network.BlockFetch.ClientState
import qualified Ouroboros.Network.BlockFetch.DeltaQ as Q
import Ouroboros.Network.Hashable
import Ouroboros.Network.BlockFetch.ConsensusInterface (PraosFetchMode(..))
import Test.Ouroboros.Network.BlockFetch (PeerGSVT(..))

type Frag = AF.AnchoredFragment Block
-- Order-preserving, lossless representation of the original mock Int hash.
key (HeaderHash h) = let s = showHex ((fromIntegral h :: Word64) `xor` 9223372036854775808) "" in replicate (16-length s) '0' ++ s
pointKey p = case p of Point P.Origin -> Nothing; Point (P.At b) -> Just (key (P.blockPointHash b))
pico :: DiffTime -> Integer
pico t = round (toRational t * 1000000000000)
gsvValue (GSV g s v) = object ["g" .= pico g, "s" .= pico (s 1), "v" .= pico (deltaqQ50thPercentile (DeltaQ v))]
peerValue (PeerGSV (Time t) outbound inbound) = object ["time" .= pico t,"outbound" .= gsvValue outbound,"inbound" .= gsvValue inbound]
size :: Block -> SizeInBytes
size b = fromIntegral (1 + BS.length body) where BlockBody body = blockBody b
fragmentValue f = object ["anchor" .= pointKey (AF.anchorPoint f), "headers" .= [object ["hash" .= key (blockHash b),"slot" .= unSlotNo (blockSlot b),"bytes" .= (fromIntegral (size b) :: Integer)] | b <- AF.toOldestFirst f]]
reason d = case d of
 D.FetchDeclineChainNotPlausible -> object ["tag" .= ("ChainNotPlausible"::String)]
 D.FetchDeclineChainIntersectionTooDeep -> object ["tag" .= ("IntersectionTooDeep"::String)]
 D.FetchDeclineAlreadyFetched -> object ["tag" .= ("AlreadyFetched"::String)]
 D.FetchDeclineInFlightThisPeer -> object ["tag" .= ("InFlightThisPeer"::String)]
 D.FetchDeclineInFlightOtherPeer -> object ["tag" .= ("InFlightOtherPeer"::String)]
 D.FetchDeclinePeerShutdown -> object ["tag" .= ("PeerShutdown"::String)]
 D.FetchDeclinePeerStarting -> object ["tag" .= ("PeerStarting"::String)]
 D.FetchDeclinePeerSlow -> object ["tag" .= ("PeerSlow"::String)]
 D.FetchDeclineReqsInFlightLimit n -> object ["tag" .= ("RequestsInFlight"::String),"limit" .= n]
 D.FetchDeclineBytesInFlightLimit n lo hi -> object ["tag" .= ("BytesInFlight"::String),"bytes" .= (fromIntegral n::Integer),"low" .= (fromIntegral lo::Integer),"high" .= (fromIntegral hi::Integer)]
 D.FetchDeclinePeerBusy n lo hi -> object ["tag" .= ("PeerBusy"::String),"bytes" .= (fromIntegral n::Integer),"low" .= (fromIntegral lo::Integer),"high" .= (fromIntegral hi::Integer)]
 D.FetchDeclineConcurrencyLimit _ n -> object ["tag" .= ("Concurrency"::String),"limit" .= n]
statusName s = case s of PeerFetchStatusShutdown -> "Shutdown"; PeerFetchStatusStarting -> "Starting"; PeerFetchStatusAberrant -> "Aberrant"; PeerFetchStatusBusy -> "Busy"; _ -> "Ready"
policy n = D.FetchDecisionPolicy {D.maxInFlightReqsPerPeer=3,D.maxConcurrencyBulkSync=n,D.maxConcurrencyDeadline=n,D.decisionLoopIntervalGenesis=0.01,D.decisionLoopIntervalPraos=0.01,D.peerSalt=mkUnsafeSalt 148,D.bulkSyncGracePeriod=0,D.blockFetchSize=size}
fromChain = AF.fromNewestFirst AF.AnchorGenesis . Chain.chainToList
compareChain a b = compare (AF.length a) (AF.length b)

observe :: String -> Int -> PraosFetchMode -> D.FetchDecisionPolicy Block -> Frag -> Set.Set (Point Block) -> [(Frag,(PeerFetchStatus Block,PeerFetchInFlight Block,PeerGSV,Int,()))] -> Value
observe label seed mode config current fetched cs = object ["label" .= label,"seed" .= seed,"mode" .= (if mode==FetchModeBulkSync then "BulkSync" else "Deadline"::String),"config" .= object ["bulk" .= D.maxConcurrencyBulkSync config,"deadline" .= D.maxConcurrencyDeadline config,"requests" .= D.maxInFlightReqsPerPeer config],"candidates" .= map candidateValue staged,"rank" .= map (D.peerInfoPeer . snd) ranked,"decisions" .= map decisionValue decisions]
 where
  -- These are the actual upstream preprocessing functions used by fetchDecisions.
  staged = D.filterNotAlreadyInFlightWithPeer [(d,flight,info) | (d,info@(_,flight,_,_,_)) <- D.filterNotAlreadyFetched (`Set.member` fetched) maxFetched $ D.selectForkSuffixes current $ D.filterPlausibleCandidates (\currentChain c -> AF.length c > AF.length currentChain) current cs]
  maxFetched = foldr max NoMaxSlotNo [MaxSlotNo (blockSlot b) | (c,_) <- cs,b <- AF.toOldestFirst c,blockPoint b `Set.member` fetched]
  ranked = D.prioritisePeerChains mode (D.peerSalt config) compareChain size [(d,flight,g,p,info) | (d,info@(_,flight,g,p,_)) <- staged]
  filtered = D.filterNotAlreadyInFlightWithOtherPeers mode [(d,s,f,info) | (d,info@(s,f,_,_,_)) <- ranked]
  decisions = D.fetchRequestDecisions config mode [(d,s,f,g,p,p) | (d,(s,f,g,p,_)) <- filtered]
  candidateValue (d,(s,f,g,p,_)) =
    let (pref,headKey,slot,frags) = case d of
          Left _ -> (0,Nothing,0,[])
          Right (suffix,fs) ->
            (AF.length (D.getFullCandidate suffix),pointKey (AF.headPoint (D.getChainSuffix suffix)),
             case AF.headSlot (D.getChainSuffix suffix) of P.Origin -> 0; P.At (SlotNo n) -> n,fs)
        Q.PeerFetchInFlightLimits hi lo = Q.calculatePeerFetchInFlightLimits g
        requested = sum [size b | z <- frags,b <- AF.toOldestFirst z]
    in object ["peer" .= p,"preference" .= pref,"head" .= headKey,"headSlot" .= slot,"fragments" .= map fragmentValue frags,"gsv" .= peerValue g,"status" .= (statusName s::String),"requests" .= peerFetchReqsInFlight f,"bytes" .= (fromIntegral (peerFetchBytesInFlight f)::Integer),"blocks" .= map pointKey (Set.toList (peerFetchBlocksInFlight f)),"tie" .= hashWithSalt (D.peerSalt config) p,"refusal" .= either (Just . reason) (const Nothing) d,"windowLow" .= (fromIntegral lo::Integer),"windowHigh" .= (fromIntegral hi::Integer),"duration" .= pico (Q.estimateExpectedResponseDuration g (peerFetchBytesInFlight f) requested),"probability" .= Q.estimateResponseDeadlineProbability g (peerFetchBytesInFlight f) requested 2]
  decisionValue (d,p) = case d of Left e -> object ["peer" .= p,"refusal" .= reason e]; Right (FetchRequest fs) -> object ["peer" .= p,"fragments" .= map fragmentValue fs]

main = do
 [out] <- getArgs
 let generated = [let (TestChainFork common a b, gs) = unGen ((,) <$> arbitrary <*> vectorOf 4 arbitrary) (mkQCGen seed) (20 + ordinal `mod` 20)
                      chains = map fromChain [a,b,a,b]
                      peers = [(c,(PeerFetchStatusReady Set.empty IsIdle, initialPeerFetchInFlight,g,p,())) | (c,PeerGSVT g,p) <- zip3 chains gs [1..4::Int]]
                  in observe ("generated-" ++ show ordinal) seed mode (policy (fromIntegral (1+ordinal `mod` 3))) (fromChain common) Set.empty peers
                 | ordinal <- [0..127::Int],let seed=148001+ordinal,mode <- [FetchModeBulkSync,FetchModeDeadline]]
 let g = PeerGSV (Time 0) direction direction
     direction = ballisticGSV 0.05 0.000002 (degenerateDistribution 0)
     f = mkAnchoredFragmentSimple [BlockBody (BS.replicate (n-1) 'a') | n <- [200000,200000,200000]]
     other = mkAnchoredFragmentSimple [BlockBody (BS.replicate (n-1) 'b') | n <- [200000,200000,200000]]
     small = mkAnchoredFragmentSimple [BlockBody (BS.pack (show n)) | n <- [1..8::Int]]
     origin = AF.Empty AF.AnchorGenesis
     ready = PeerFetchStatusReady Set.empty IsIdle
     flight requests bytes blocks = initialPeerFetchInFlight {peerFetchReqsInFlight=requests,peerFetchBytesInFlight=bytes,peerFetchBlocksInFlight=Set.fromList (map blockPoint blocks),peerFetchMaxSlotNo=foldr max NoMaxSlotNo [MaxSlotNo (blockSlot b) | b <- blocks]}
     peer p c status fl timing = (c,(status,fl,timing,p,()))
     one label c status fl = observe label 0 FetchModeBulkSync (policy 1) origin Set.empty [peer (1::Int) c status fl g]
     low=196608
     high=393216
     boundaries = [one "boundary-whole-block-overshoot" f ready initialPeerFetchInFlight,
                   observe "boundary-zero-request-limit" 0 FetchModeBulkSync ((policy 1) {D.maxInFlightReqsPerPeer=0}) origin Set.empty [peer (1::Int) small ready initialPeerFetchInFlight g],
                   one "boundary-below-high" small ready (flight 1 (high-1) []),
                   one "boundary-at-high" small ready (flight 1 high []),
                   one "boundary-above-high" small ready (flight 1 (high+1) []),
                   one "boundary-ready-low" small ready (flight 1 low []),
                   one "boundary-busy-low" small PeerFetchStatusBusy (flight 1 low []),
                   one "boundary-request-below-limit" small ready (flight 2 0 []),
                   one "boundary-request-at-limit" small ready (flight 3 0 []),
                   one "boundary-shutdown" small PeerFetchStatusShutdown initialPeerFetchInFlight,
                   one "boundary-starting" small PeerFetchStatusStarting initialPeerFetchInFlight,
                   one "boundary-aberrant" small PeerFetchStatusAberrant initialPeerFetchInFlight,
                   one "boundary-inflight-this-peer" small ready (flight 1 0 (AF.toOldestFirst small)),
                   observe "boundary-already-fetched" 0 FetchModeBulkSync (policy 1) origin (Set.fromList (map blockPoint (AF.toOldestFirst small))) [peer (1::Int) small ready initialPeerFetchInFlight g],
                   observe "boundary-discontiguous-request-limit" 0 FetchModeBulkSync (policy 1) origin (Set.fromList [blockPoint b | (i,b) <- zip [0..] (AF.toOldestFirst small), odd (i::Int)]) [peer (1::Int) small ready (flight 1 0 []) g]]
     alternatives = [observe ("boundary-" ++ label) 0 mode (policy cap) origin Set.empty ps |
                     (label,cap,ps) <- [("overlap",2,[peer (1::Int) small ready initialPeerFetchInFlight g,peer 2 small ready initialPeerFetchInFlight g]),
                       ("other-peer-inflight",2,[peer 1 small ready (flight 1 1 (take 3 (AF.toOldestFirst small))) g,peer 2 small ready initialPeerFetchInFlight g]),
                       ("aberrant-other-inflight",2,[peer 1 small PeerFetchStatusAberrant (flight 1 1 (AF.toOldestFirst small)) g,peer 2 small ready initialPeerFetchInFlight g]),
                       ("concurrency-zero",0,[peer 1 f ready initialPeerFetchInFlight g]),
                       ("global-cap",1,[peer 1 f PeerFetchStatusBusy (flight 1 100 []) g,peer 2 other ready initialPeerFetchInFlight (PeerGSV (Time 0) (ballisticGSV 1 0.000002 (degenerateDistribution 0)) direction)]),
                       ("preferred-handover",1,[peer 1 f ready (flight 1 100 []) (PeerGSV (Time 0) (ballisticGSV 1 0.000002 (degenerateDistribution 0)) direction),peer 2 other ready initialPeerFetchInFlight g]),
                       ("deadline-at-two-seconds",2,[peer 1 f ready initialPeerFetchInFlight (PeerGSV (Time 0) (ballisticGSV 0.3999 0.000002 (degenerateDistribution 0)) (ballisticGSV 0.3999 0.000002 (degenerateDistribution 0)))]),
                       ("deadline-one-picosecond-before",2,[peer 1 f ready initialPeerFetchInFlight (PeerGSV (Time 0) (ballisticGSV 0.399899999999 0.000002 (degenerateDistribution 0)) (ballisticGSV 0.3999 0.000002 (degenerateDistribution 0)))]),
                       ("gsv-five-percent-tie",2,[peer 1 f ready initialPeerFetchInFlight g,peer 2 other ready initialPeerFetchInFlight (PeerGSV (Time 0) (ballisticGSV 0.051 0.000002 (degenerateDistribution 0)) direction)])],
                     mode <- [FetchModeBulkSync,FetchModeDeadline]]
 LBS.writeFile out (LBS.unlines (map encode (generated ++ boundaries ++ alternatives)))

 let samples = [let start = fromRational (toRational startP / 1000000000000)
                    end = fromRational (toRational endP / 1000000000000)
                    fresh = fromSample (Time start) (Time end) 100
                    previous = defaultGSV
                in object ["start" .= startP,"end" .= endP,"previous" .= peerValue previous,"fresh" .= peerValue fresh,"combined" .= peerValue (fresh <> previous),"default" .= peerValue defaultGSV]
               | (startP,endP) <- [(0,1),(1,4),(999999999,1000000002),(1000000000001,1000003000002),(20000000000000,20000123456789),(999999999999999,1000001123456789),(1000000000000000,1000000123456789),(1000000000000001,1000000123456790)] :: [(Integer,Integer)]]
 LBS.writeFile (out ++ ".gsv.jsonl") (LBS.unlines (map encode samples))
