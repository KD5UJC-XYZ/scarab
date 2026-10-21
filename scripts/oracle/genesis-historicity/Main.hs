{-# LANGUAGE FlexibleContexts #-}
{-# LANGUAGE NamedFieldPuns #-}
{-# LANGUAGE OverloadedStrings #-}
-- SPDX-License-Identifier: Apache-2.0
module Main where
import Control.Exception (toException)
import Data.Aeson (Value,object,(.=),encode)
import qualified Data.ByteString.Lazy.Char8 as LBS
import Data.IORef
import Data.Proxy (Proxy(..))
import System.Environment (getArgs)
import Ouroboros.Consensus.Block
import Ouroboros.Consensus.BlockchainTime (RelativeTime(..),SystemTime(..))
import qualified Ouroboros.Consensus.HeaderStateHistory as HSH
import qualified Ouroboros.Consensus.MiniProtocol.ChainSync.Client.HistoricityCheck as HC
import qualified Ouroboros.Consensus.MiniProtocol.ChainSync.Client as CS
import Ouroboros.Consensus.Node.RethrowPolicy (consensusRethrowPolicy)
import qualified Ouroboros.Consensus.Node.GsmState as GSM
import Ouroboros.Network.RethrowPolicy
import qualified Ouroboros.Network.Mock.Chain as Chain
import Test.Util.TestBlock

picos t=round(toRational t*1000000000000)::Integer
point p=case p of GenesisPoint->object["slot".=(-1::Int),"hash".=("origin"::String)];BlockPoint(SlotNo slot)hash->object["slot".=slot,"hash".=show hash]
phaseName phase=case phase of GSM.PreSyncing->"PreSyncing";GSM.Syncing->"Syncing";GSM.CaughtUp->"CaughtUp"
messageName message=case message of HC.HistoricalMsgRollBackward->"RollBackward";HC.HistoricalMsgAwaitReply->"AwaitReply"
command exception=show(runRethrowPolicy(consensusRethrowPolicy(Proxy::Proxy TestBlock))OutboundError(toException exception))
-- Both a genesis state and a real applied header state exercise existential point attribution.
header origin slotTime = let { chain=if origin then Chain.Genesis else Chain.fromOldestFirst[firstBlock 0]
                          ; snapshot=HSH.current(HSH.fromChain singleNodeTestConfig testInitExtLedger chain) }
                        in snapshot{HSH.hswtSlotTime=RelativeTime slotTime}
observe origin phase message age=do
 reads<-newIORef(0::Int)
 let slotTime=100
     arrival=slotTime+fromRational(fromIntegral age/1000000000000)
     cutoff=HC.HistoricityCutoff 133200
     system=SystemTime{systemTimeCurrent=modifyIORef' reads(+1)>>pure(RelativeTime arrival),systemTimeWait=pure()}
     checker=HC.mkCheck system(pure phase)cutoff
 result<-HC.judgeMessageHistoricity checker message(header origin slotTime)
 clocks<-readIORef reads
 let failure=case result of Right()->Nothing;Left e@(HC.HistoricityException msg at (RelativeTime slot) (RelativeTime arrived) (HC.HistoricityCutoff limit))->Just$object["message".=(messageName msg::String),"point".=point at,"slotTime".=picos slot,"arrivalTime".=picos arrived,"cutoff".=picos limit,"command".=command(CS.HistoricityError e)]
 pure$object["origin".=origin,"phase".=(phaseName phase::String),"message".=(messageName message::String),"age".=age,"slotTime".=picos slotTime,"arrivalTime".=picos arrival,"cutoff".=picos(133200::Rational),"point".=point(headerStatePointFor origin),"clockReads".=clocks,"failure".=failure]
 where
  headerStatePointFor True=GenesisPoint
  headerStatePointFor False=blockPoint(firstBlock 0)
main=do
 [out]<-getArgs
 let cutoff=133200*1000000000000::Integer
 rows<-sequence[observe origin phase message age|origin<-[True,False],phase<-[GSM.PreSyncing,GSM.Syncing,GSM.CaughtUp],message<-[HC.HistoricalMsgRollBackward,HC.HistoricalMsgAwaitReply],age<-[-1,0,cutoff-1,cutoff,cutoff+1,cutoff*2]]
 let errors=[object["error".=(name::String),"outbound".=command exception,"inbound".=show(runRethrowPolicy(consensusRethrowPolicy(Proxy::Proxy TestBlock))InboundError(toException exception))]|(name,exception)<-[("LoPExpired",CS.EmptyBucket),("DensityTooLow",CS.DensityTooLow),("InvalidJumpResponse",CS.InvalidJumpResponse)]]
 LBS.writeFile out(LBS.unlines(map encode rows))
 LBS.writeFile(out++".errors.jsonl")(LBS.unlines(map encode errors))
