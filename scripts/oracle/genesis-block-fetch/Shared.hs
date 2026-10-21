{-# LANGUAGE NamedFieldPuns #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE TypeFamilies #-}
-- SPDX-License-Identifier: Apache-2.0
-- Observes pinned native functions. No scheduler implementation is copied here.
module Shared where
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
import Ouroboros.Network.BlockFetch.ConsensusInterface (PraosFetchMode(..), FetchMode(..))
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
 D.FetchDeclineConcurrencyLimit GenesisFetchMode n -> object ["tag" .= ("GenesisConcurrency"::String),"limit" .= n]
 D.FetchDeclineConcurrencyLimit _ n -> object ["tag" .= ("Concurrency"::String),"limit" .= n]
statusName s = case s of PeerFetchStatusShutdown -> "Shutdown"; PeerFetchStatusStarting -> "Starting"; PeerFetchStatusAberrant -> "Aberrant"; PeerFetchStatusBusy -> "Busy"; _ -> "Ready"
policy n = D.FetchDecisionPolicy {D.maxInFlightReqsPerPeer=3,D.maxConcurrencyBulkSync=n,D.maxConcurrencyDeadline=n,D.decisionLoopIntervalGenesis=0.01,D.decisionLoopIntervalPraos=0.01,D.peerSalt=mkUnsafeSalt 148,D.bulkSyncGracePeriod=0,D.blockFetchSize=size}
fromChain :: Chain.Chain Block -> Frag
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
