{-# LANGUAGE NamedFieldPuns #-}
{-# LANGUAGE OverloadedStrings #-}
-- SPDX-License-Identifier: Apache-2.0
module Main where
import Shared
import Ouroboros.Network.DeltaQ
import Data.Aeson (Value,object,(.=),encode)
import qualified Data.ByteString.Lazy.Char8 as LBS
import qualified Data.ByteString.Char8 as BS
import qualified Data.Set as Set
import qualified Data.Sequence as Seq
import Data.IORef
import Control.Monad (forM)
import Control.Tracer (nullTracer)
import Control.Monad.Class.MonadTime.SI (getMonotonicTime,Time(..),addTime)
import System.Environment (getArgs)
import Test.QuickCheck (arbitrary,vectorOf)
import Test.QuickCheck.Gen (unGen)
import Test.QuickCheck.Random (mkQCGen)
import Ouroboros.Network.Block
import qualified Ouroboros.Network.AnchoredFragment as AF
import qualified Ouroboros.Network.BlockFetch.Decision as D
import Ouroboros.Network.BlockFetch.Decision.Genesis
import Ouroboros.Network.BlockFetch.ClientState
import Ouroboros.Network.BlockFetch.ConsensusInterface
import Ouroboros.Network.Mock.ChainGenerators (TestChainFork(..))
import Ouroboros.Network.Mock.ConcreteBlock
import Test.Ouroboros.Network.BlockFetch (PeerGSVT(..))

type Info = (PeerFetchStatus Block,PeerFetchInFlight Block,PeerGSV,Int,())
observeGenesis :: String -> Int -> D.FetchDecisionPolicy Block -> Frag -> Set.Set (Point Block) -> [Int] -> [(Frag,Info)] -> ChainSelStarvation -> Time -> IO Value
observeGenesis label seed config current fetched inputOrder cs starvation start = do
 let maxFetched=foldr max NoMaxSlotNo [MaxSlotNo(blockSlot b)|(fragment,_)<-cs,b<-AF.toOldestFirst fragment,blockPoint b `Set.member` fetched]
     comparison=ChainComparison {plausibleCandidateChain = \a b -> AF.length b>AF.length a,compareCandidateChains=compareChain}
     order=PeersOrder (if null inputOrder then Nothing else Just(head inputOrder)) (Seq.fromList inputOrder) start
 state<-newIORef order
 demoted<-newIORef []
 sample<-getMonotonicTime
 ds<-fetchDecisionsGenesisM nullTracer config comparison current (`Set.member` fetched) maxFetched starvation (order,writeIORef state,\p -> modifyIORef' demoted (++[p])) cs
 after<-readIORef state
 demotions<-readIORef demoted
 let time (Time t)=pico t
     changedStart=peersOrderStart after/=start
     now=if changedStart then time(peersOrderStart after) else time sample
     valueState s=object["order".= foldr(:)[] (peersOrderAll s),"current".=peersOrderCurrent s,"start".=time(peersOrderStart s)]
     staged=D.selectForkSuffixes current $ D.filterPlausibleCandidates (plausibleCandidateChain comparison) current cs
     candidate (d,(status,flight,g,p,_))=object["peer".=p,"preference".=either(const 0)(AF.length . D.getFullCandidate)d,"fragments".=either(const [])(\s->[fragmentValue(D.getChainSuffix s)])d,"gsv".=peerValue g,"status".=(statusName status::String),"requests".=peerFetchReqsInFlight flight,"bytes".=(fromIntegral(peerFetchBytesInFlight flight)::Integer),"blocks".= map pointKey(Set.toList(peerFetchBlocksInFlight flight)),"refusal".= either(Just . reason)(const Nothing)d]
     decision(d,(_,_,_,p,_))=case d of Left e->object["peer".=p,"refusal".=reason e];Right(FetchRequest fs)->object["peer".=p,"fragments".=map fragmentValue fs]
 pure$object["label".=label,"seed".=seed,"now".=now,"grace".=pico(D.bulkSyncGracePeriod config),"before".=valueState order,"after".=valueState after,"starvation".=case starvation of ChainSelStarvationOngoing->object["ongoing".=True];ChainSelStarvationEndedAt t->object["ended".=time t],"fetched".=map pointKey(Set.toList fetched),"candidates".=map candidate staged,"decisions".=map decision ds,"demotions".=demotions,"maxRequests".=D.maxInFlightReqsPerPeer config]

main=do
 [out]<-getArgs
 Time now<-getMonotonicTime
 let start=Time(now-20)
     starved=ChainSelStarvationEndedAt(Time now)
     fed=ChainSelStarvationEndedAt(Time(now-30))
     cfg=(policy 1){D.bulkSyncGracePeriod=10}
     generated=[let { (TestChainFork common a b,gs)=unGen ((,) <$> arbitrary <*> vectorOf 4 arbitrary)(mkQCGen seed)(20+ordinal `mod` 20)
                  ; peers=[(c,(PeerFetchStatusReady Set.empty IsIdle,initialPeerFetchInFlight,g,p,()))|(c,PeerGSVT g,p)<-zip3(map fromChain[a,b,a,b])gs[1..4::Int]] }
                in observeGenesis ("generated-"++show ordinal) seed cfg(fromChain common)Set.empty(if even ordinal then [] else [2,1,4,3])peers(if ordinal `mod`3==0 then starved else fed)start
               |ordinal<-[0..127::Int],let seed=330001+ordinal]
 rows<-sequence generated
 let chain=mkAnchoredFragmentSimple[BlockBody(BS.pack(show n))|n<-[1..8::Int]]
     origin=AF.Empty AF.AnchorGenesis
     direction=ballisticGSV 0.05 0.000002(degenerateDistribution 0)
     timing=PeerGSV(Time 0)direction direction
     blocks=AF.toOldestFirst chain
     flight=initialPeerFetchInFlight{peerFetchReqsInFlight=1,peerFetchBlocksInFlight=Set.singleton(blockPoint(head blocks)),peerFetchMaxSlotNo=MaxSlotNo(blockSlot(head blocks))}
     peer p s f=(chain,(s,f,timing,p,()))
     ready=PeerFetchStatusReady Set.empty IsIdle
     cases=[("starvation-rotates",cfg,[1,2],starved,Set.empty,[peer 1 ready flight,peer 2 ready initialPeerFetchInFlight]),("fed-preserves-current",cfg,[1,2],fed,Set.empty,[peer 1 ready flight,peer 2 ready initialPeerFetchInFlight]),("all-fetched-keeps-order",cfg,[1,2],starved,Set.fromList(map blockPoint blocks),[peer 1 ready flight,peer 2 ready initialPeerFetchInFlight]),("chosen-busy",cfg,[1,2],fed,Set.empty,[peer 1 PeerFetchStatusBusy flight,peer 2 ready initialPeerFetchInFlight]),("zero-request-limit",cfg{D.maxInFlightReqsPerPeer=0},[],fed,Set.empty,[peer 1 ready initialPeerFetchInFlight]),("disconnect-align",cfg,[9,1,2],fed,Set.empty,[peer 1 ready initialPeerFetchInFlight,peer 2 ready initialPeerFetchInFlight]),("own-flight",cfg,[1,2],fed,Set.empty,[peer 1 ready flight,peer 2 ready initialPeerFetchInFlight])]
 boundaries<-forM cases $ \(label,c,order,st,fetched,peers)->observeGenesis label 0 c origin fetched order peers st start
 LBS.writeFile out(LBS.unlines(map encode(rows++boundaries)))
