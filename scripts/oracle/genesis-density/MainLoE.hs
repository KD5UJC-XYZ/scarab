{-# LANGUAGE NamedFieldPuns #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TypeApplications #-}
-- SPDX-License-Identifier: Apache-2.0
-- Runs unchanged LoE conformance generators through the real reference ChainDB and peer simulator.
module Main where
import Cardano.Ledger.BaseTypes (unNonZero)
import Cardano.Slotting.Time (getSlotLength)
import Control.Monad.IOSim (runSimTrace,traceResult,selectTraceEventsDynamic,traceM)
import Control.Exception (throwIO,try,SomeException,displayException,evaluate)
import Control.Monad (when)
import Control.Monad.Class.MonadAsync (AsyncCancelled(..))
import Data.Aeson.Types (parseMaybe)
import System.Exit (exitFailure)
import Control.Tracer (mkTracer)
import Data.Maybe.Strict (StrictMaybe(..))
import Ouroboros.Consensus.Genesis.Governor (TraceGDDEvent(..),GDDDebugInfo(..),DensityBounds(..))
import Test.Consensus.PeerSimulator.Trace (TraceEvent(..))
import Data.Aeson
import qualified Data.ByteString.Lazy.Char8 as BL
import qualified Data.List.NonEmpty as NE
import Data.Time.Clock (diffTimeToPicoseconds)
import Data.Ratio (numerator,denominator)
import Numeric (showHex)
import Ouroboros.Consensus.Block
import Ouroboros.Consensus.Config.SecurityParam
import Ouroboros.Consensus.Util.Condense (condense)
import Ouroboros.Consensus.Util.IOLike (Time(..),fromException,getMonotonicTime)
import Ouroboros.Consensus.MiniProtocol.ChainSync.Client (ChainSyncClientException(..))
import Ouroboros.Network.Driver.Limits (ProtocolLimitFailure(..))
import qualified Ouroboros.Network.AnchoredFragment as AF
import System.Environment
import Test.Consensus.BlockTree (allFragments)
import Test.Consensus.Genesis.Setup
import qualified Test.Consensus.Genesis.Tests.LoE as LoE
import qualified Test.Consensus.Genesis.Tests.DensityDisconnect as DD
import qualified Test.Consensus.Genesis.Tests.LoP as LoP
import qualified Test.Consensus.Genesis.Tests.CSJ as CSJ
import qualified Test.Consensus.Genesis.Tests.Uniform as Uniform
import qualified Test.Consensus.Genesis.Tests.LongRangeAttack as LRA
import Test.Consensus.Genesis.TestSuite
import Test.Consensus.Genesis.TestSuite.SmallKey
import Test.Consensus.PeerSimulator.Config ()
import Test.Consensus.PeerSimulator.Run
import Test.Consensus.PeerSimulator.StateView
import Test.Consensus.PointSchedule
import Test.Consensus.PointSchedule.Peers (Peer(..),PeerId(..),peersList)
import Test.Consensus.PointSchedule.NodeState (NodeState(..))
import Test.Consensus.PointSchedule.SinglePeer
import Test.QuickCheck (quickCheckWithResult,stdArgs,chatty,maxSuccess,isSuccess)
import Test.QuickCheck.Gen (unGen)
import Test.QuickCheck.Random (mkQCGen)
import Test.Util.TestBlock (TestBlock,unTestHash)
import Test.Ouroboros.Consensus.ChainGenerator.Params (Delta(..))

pointJSON :: Point TestBlock -> Value
pointJSON GenesisPoint = Null
pointJSON (BlockPoint slot hash) = object ["slot" .= unSlotNo slot,"hash" .= concatMap fixed (NE.toList $ unTestHash hash)]
 where fixed x=let s=showHex x "" in replicate (16-length s) '0' ++ s
fragmentJSON :: AF.AnchoredFragment (Header TestBlock) -> Value
fragmentJSON f = object ["anchor" .= pointJSON (castPoint $ AF.anchorPoint f),"headers" .= map (pointJSON . castPoint . blockPoint) (AF.toOldestFirst f)]
schedulePointJSON :: SchedulePoint TestBlock -> Value
schedulePointJSON point = case point of
 ScheduleTipPoint block -> object ["tag" .= ("tip"::String),"point" .= blockJSON block]
 ScheduleHeaderPoint block -> object ["tag" .= ("header"::String),"point" .= blockJSON block]
 ScheduleBlockPoint block -> object ["tag" .= ("block"::String),"point" .= blockJSON block]
 where blockJSON Origin=Null;blockJSON (NotOrigin b)=pointJSON $ blockPoint b
nodeStateJSON :: NodeState TestBlock -> Value
nodeStateJSON NodeState{nsTip,nsHeader,nsBlock} = object ["tip" .= blockJSON nsTip,"header" .= blockJSON nsHeader,"block" .= blockJSON nsBlock]
 where blockJSON Origin=Null;blockJSON (NotOrigin b)=pointJSON $ blockPoint b
rationalJSON :: Rational -> Value
rationalJSON value = object ["numerator" .= numerator value,"denominator" .= denominator value]
configJSON :: SchedulerConfig -> Value
configJSON config = object
 ["enableChainSyncTimeouts" .= scEnableChainSyncTimeouts config,"enableBlockFetchTimeouts" .= scEnableBlockFetchTimeouts config,
  "debug" .= scDebug config,"trace" .= scTrace config,"traceState" .= scTraceState config,
  "enableLoE" .= scEnableLoE config,"enableLoP" .= scEnableLoP config,"downtimePicos" .= fmap diffTimeToPicoseconds (scDowntime config),
  "enableChainSelStarvation" .= scEnableChainSelStarvation config,"enableCSJ" .= scEnableCSJ config]
-- Same expected terminal categories checked by upstream runConformanceTest. Rendering is retained
-- separately; the compatibility adapter consumes these closed tags rather than matching text.
data FailureKind = EmptyBucketFailure | DensityTooLowFailure | CandidateTooSparseFailure | TimeoutFailure | CancelledFailure | UnexpectedFailure deriving (Eq,Show)
failureKind :: SomeException -> FailureKind
failureKind exception
 | Just EmptyBucket <- fromException exception = EmptyBucketFailure
 | Just DensityTooLow <- fromException exception = DensityTooLowFailure
 | Just CandidateTooSparse{} <- fromException exception = CandidateTooSparseFailure
 | Just (ExceededTimeLimit _) <- fromException exception = TimeoutFailure
 | Just AsyncCancelled <- fromException exception = CancelledFailure
 | otherwise = UnexpectedFailure
component :: PeerSimulatorComponentResult TestBlock -> String
component result = case result of
 SomeChainSyncClientResult _ -> "chainSyncClient" :: String
 SomeChainSyncServerResult _ -> "chainSyncServer"
 SomeBlockFetchClientResult _ -> "blockFetchClient"
 SomeBlockFetchServerResult _ -> "blockFetchServer"
peerOutcomeJSON :: PeerSimulatorResult TestBlock -> Value
peerOutcomeJSON result@PeerSimulatorResult{psePeerId,pseResult} = object
 ["peer" .= show psePeerId,"component" .= component pseResult,
  "outcome" .= maybe "success" (show . failureKind) (pscrToException pseResult),"completeResultRendering" .= condense result]
latestJSON :: StrictMaybe (WithOrigin SlotNo) -> Value
latestJSON SNothing = object ["tag" .= ("unknown" :: String)]
latestJSON (SJust Origin) = object ["tag" .= ("origin" :: String)]
latestJSON (SJust (NotOrigin slot)) = object ["tag" .= ("at" :: String), "slot" .= unSlotNo slot]
boundsJSON :: (PeerId,DensityBounds TestBlock) -> Value
boundsJSON (peer,DensityBounds{clippedFragment,offersMoreThanK,lowerBound,upperBound,hasBlockAfter,latestSlot,idling}) = object
 ["peer" .= show peer,"clippedFragment" .= fragmentJSON clippedFragment,
  "offersMoreThanK" .= offersMoreThanK,"lowerBound" .= lowerBound,"upperBound" .= upperBound,
  "hasBlockAfter" .= hasBlockAfter,"latestSlot" .= latestJSON (SJust latestSlot),"idling" .= idling]
gddJSON :: Time -> TraceGDDEvent PeerId TestBlock -> Value
gddJSON (Time now) event = case event of
 TraceGDDDisconnected peers -> object ["timePicos" .= diffTimeToPicoseconds now,"tag" .= ("disconnected"::String),"losingPeers" .= map show (NE.toList peers),"completeRendering" .= show event]
 TraceGDDDebug GDDDebugInfo{sgen,curChain,candidates,candidateSuffixes,losingPeers,bounds,loeHead} -> object
  ["timePicos" .= diffTimeToPicoseconds now,"tag" .= ("debug"::String),"genesisWindow" .= unGenesisWindow sgen,
   "current" .= fragmentJSON curChain,"candidates" .= map peerFragment candidates,"candidateSuffixes" .= map peerFragment candidateSuffixes,
   "losingPeers" .= map show losingPeers,"bounds" .= map boundsJSON bounds,"loeHead" .= pointJSON (castPoint $ AF.anchorToPoint loeHead),"completeRendering" .= show event]
 where peerFragment (peer,fragment) = object ["peer" .= show peer,"fragment" .= fragmentJSON fragment]
observe :: String -> Int -> ConformanceTest TestBlock -> Int -> IO Value
observe family ordinal test seed = do
 args <- getProtocolInfoArgs @TestBlock
 let AdjustMaxSize adjustSize = ctAdjustMaxSize test
     generatorSize = adjustSize 25
     input@GenesisTest{gtSecurityParam,gtGenesisWindow,gtBlockTree,gtSchedule=PointSchedule{psSchedule,psStartOrder,psMinEndTime=Time end}} = unGen (ctGenerator test) (mkQCGen seed) generatorSize
     Delta delay = gtDelay input
 let simulation = runSimTrace $ runPointSchedule args (ctSchedulerConfig test) input $ mkTracer $ \event -> case event of
       TraceGenesisDDEvent value -> do
         now <- getMonotonicTime
         traceM (gddJSON now value)
       _ -> pure ()
 state <- either throwIO pure $ traceResult True simulation
 let gddTrace = (selectTraceEventsDynamic simulation :: [Value])
 let expectedExceptions = all (maybe True ((/=UnexpectedFailure) . failureKind) . pscrToException . pseResult) (svPeerSimulatorResults state)
 propertyResult <- quickCheckWithResult stdArgs{chatty=False,maxSuccess=1} (ctProperty test input state)
 pure $ object ["gddTrace" .= gddTrace,"label" .= ("upstream-"++family++"-"++show ordinal++"-seed-"++show seed),"description" .= ctDescription test,"family" .= family,
   "seed" .= seed,"generatorBaseSize" .= (25::Int),"generatorSize" .= generatorSize,"k" .= unNonZero (maxRollbacks gtSecurityParam),"genesisWindow" .= unGenesisWindow gtGenesisWindow,
   "completeInputRendering" .= unlines (prettyGenesisTest prettyPointSchedule input),
   "schedulerConfig" .= configJSON (ctSchedulerConfig test),"forecastRange" .= unForecastRange (gtForecastRange input),"delay" .= delay,
   "slotLengthPicos" .= diffTimeToPicoseconds (realToFrac $ getSlotLength $ gtSlotLength input),
   "extraHonestPeers" .= gtExtraHonestPeers input,
   "lopCapacity" .= rationalJSON (fromInteger $ lbpCapacity $ gtLoPBucketParams input),"lopRate" .= rationalJSON (lbpRate $ gtLoPBucketParams input),
   "csjJumpSize" .= unSlotNo (csjpJumpSize $ gtCSJParams input),
   "chainSyncTimeouts" .= object ["canAwaitPicos" .= fmap diffTimeToPicoseconds (canAwaitTimeout $ gtChainSyncTimeouts input),
     "intersectPicos" .= fmap diffTimeToPicoseconds (intersectTimeout $ gtChainSyncTimeouts input),
     "mustReplyPicos" .= fmap diffTimeToPicoseconds (mustReplyTimeout $ gtChainSyncTimeouts input),
     "idlePicos" .= fmap diffTimeToPicoseconds (idleTimeout $ gtChainSyncTimeouts input)],
   "blockFetchTimeouts" .= object ["busyPicos" .= fmap diffTimeToPicoseconds (busyTimeout $ gtBlockFetchTimeouts input),
     "streamingPicos" .= fmap diffTimeToPicoseconds (streamingTimeout $ gtBlockFetchTimeouts input)],
   "treeFragments" .= map (fragmentJSON . AF.mapAnchoredFragment getHeader) (allFragments gtBlockTree),
   "startOrder" .= map show psStartOrder,"minEndTimePicos" .= diffTimeToPicoseconds end,
   "schedules" .= [object ["peer" .= show name,"events" .= [object ["timePicos" .= diffTimeToPicoseconds t,"event" .= schedulePointJSON p] | (Time t,p)<-value]] | Peer{name,value}<-peersList psSchedule],
   "stateTimeline" .= [object ["timePicos" .= diffTimeToPicoseconds absolute,"durationPicos" .= diffTimeToPicoseconds duration,"peer" .= show name,"state" .= nodeStateJSON value] | ((Time absolute,_),(duration,Peer{name,value})) <- zip (peersStates $ gtSchedule input) (peersStatesRelative $ gtSchedule input)],
   "selectedChain" .= fragmentJSON (svSelectedChain state),"tipBlock" .= maybe Null (pointJSON . blockPoint) (svTipBlock state),
   "peerResults" .= map condense (svPeerSimulatorResults state),"peerOutcomes" .= map peerOutcomeJSON (svPeerSimulatorResults state),
   "ctPropertyPassed" .= isSuccess propertyResult,"expectedExceptionsPassed" .= expectedExceptions,
   "propertyPassed" .= (isSuccess propertyResult && expectedExceptions)]
main :: IO ()
main = do
 [output]<-getArgs
 let cases=[("LoE",ordinal,getTest $ at (LoE.testSuite @TestBlock) key,seed) | (ordinal,key)<-zip [(0::Int)..] (getAllKeys @LoE.TestKey),seed<-[331001..331004]] ++
           [("DensityDisconnect",ordinal,getTest $ at (DD.testSuite @TestBlock) key,seed) | (ordinal,key)<-zip [(0::Int)..] (getAllKeys @DD.TestKey),seed<-[332001..332004]] ++
           [("LoP",ordinal,getTest $ at (LoP.testSuite @TestBlock) key,seed) | (ordinal,key)<-zip [(0::Int)..] (getAllKeys @LoP.TestKey),seed<-[333001..333004]] ++
           [("CSJ",ordinal,getTest $ at (CSJ.testSuite @TestBlock) key,seed) | (ordinal,key)<-zip [(0::Int)..] (getAllKeys @CSJ.TestKey),seed<-[334001..334004]] ++
           [("Uniform",ordinal,getTest $ at (Uniform.testSuite @TestBlock) key,seed) | (ordinal,key)<-zip [(0::Int)..] (getAllKeys @Uniform.TestKey),seed<-[335001..335004]] ++
           [("LongRangeAttack",ordinal,getTest $ at (LRA.testSuite @TestBlock) key,seed) | (ordinal,key)<-zip [(0::Int)..] (getAllKeys @LRA.TestKey),seed<-[336001..336004]]
 rows<-mapM (\(family,ordinal,test,seed)-> do
   result <- try $ do
     row <- observe family ordinal test seed
     _ <- evaluate $ BL.length $ encode row
     pure row
   case result of
     Right row -> pure row
     Left (failure :: SomeException) -> pure $ object
       ["label" .= ("upstream-"++family++"-"++show ordinal++"-seed-"++show seed),"family" .= family,
        "description" .= ctDescription test,"seed" .= seed,"generatorBaseSize" .= (25::Int),
        "generatorSize" .= let AdjustMaxSize f = ctAdjustMaxSize test in f 25,
        "nativeException" .= displayException failure,"propertyPassed" .= False]) cases
 BL.writeFile output $ BL.unlines $ map encode rows
 let successful row = parseMaybe (withObject "outcome" (.: "propertyPassed")) row == Just True
 when (not $ all successful rows) exitFailure
