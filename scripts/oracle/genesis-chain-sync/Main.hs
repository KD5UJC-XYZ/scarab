{-# LANGUAGE NamedFieldPuns #-}
{-# LANGUAGE FlexibleContexts #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE TypeFamilies #-}
-- SPDX-License-Identifier: Apache-2.0
module Main where
import Control.Monad (forM,forM_,foldM)
import Control.Tracer (nullTracer)
import Data.Aeson (Value,object,(.=),encode)
import qualified Data.ByteString.Lazy.Char8 as LBS
import qualified Data.Map.Strict as Map
import Data.Maybe (isJust)
import qualified Data.Maybe.Strict as SM
import Data.Foldable (toList)
import Data.IORef
import System.Environment (getArgs)
import Test.QuickCheck (arbitrary,vectorOf)
import Test.QuickCheck.Gen (unGen)
import Test.QuickCheck.Random (mkQCGen)
import Ouroboros.Consensus.Block
import Ouroboros.Consensus.BlockchainTime (RelativeTime(..))
import Ouroboros.Consensus.HeaderValidation (HeaderWithTime(..))
import qualified Ouroboros.Consensus.HeaderStateHistory as HSH
import Ouroboros.Consensus.MiniProtocol.ChainSync.Client.State
import qualified Ouroboros.Consensus.MiniProtocol.ChainSync.Client.Jumping as J
import qualified Ouroboros.Consensus.Node.GsmState as GSM
import Ouroboros.Consensus.Util.IOLike
import qualified Ouroboros.Network.AnchoredFragment as AF
import qualified Ouroboros.Network.Mock.Chain as Chain
import Test.Util.TestBlock

point p=case p of GenesisPoint -> object["slot".=(-1::Int),"hash".=("origin"::String)]; BlockPoint (SlotNo s) h -> object["slot".=s,"hash".=show h]
slotValue Origin=(-1::Integer)
slotValue (NotOrigin(SlotNo s))=fromIntegral s
infoValue :: JumpInfo TestBlock -> Value
infoValue info=object["anchor".=point(AF.anchorPoint(jTheirFragment info)),"headers".=map (point.blockPoint)(AF.toOldestFirst(jTheirFragment info))]
-- Native JumpInfo carries an actual lawful header-state history, not an unevaluated placeholder.
info :: Int -> JumpInfo TestBlock
info n=let blocks=take n(iterate successorBlock(firstBlock 0)); chain=Chain.fromOldestFirst blocks
       in JumpInfo{jMostRecentIntersection=GenesisPoint,jOurFragment=AF.Empty AF.AnchorGenesis,jTheirFragment=AF.fromOldestFirst AF.AnchorGenesis[HeaderWithTime(getHeader b)(RelativeTime(fromIntegral(unSlotNo(blockSlot b))))|b<-blocks],jTheirHeaderStateHistory=HSH.fromChain singleNodeTestConfig testInitExtLedger chain}
roleValue :: Maybe (JumpInfo TestBlock) -> ChainSyncJumpingState IO TestBlock -> Value
roleValue pending r=case r of
 Dynamo initial lastSlot -> object["tag".=("Dynamo"::String),"starting".=case initial of DynamoStarted->Nothing;DynamoStarting j->Just(infoValue j),"last".=slotValue lastSlot]
 Objector initial good bad -> object["tag".=("Objector"::String),"starting".=(case initial of Starting->True;Started->False),"good".=infoValue good,"bad".=point bad]
 Disengaged initial -> object["tag".=("Disengaged"::String),"restart".=(case initial of Disengaging->True;DisengagedDone->False)]
 Jumper _ state -> object["tag".=("Jumper"::String),"pending".=(infoValue <$> pending),"state".=case state of Happy init good->object["tag".=("Happy"::String),"fresh".=(case init of FreshJumper->True;StartedJumper->False),"good".=(infoValue <$> good)];LookingForIntersection good bad->object["tag".=("Looking"::String),"good".=infoValue good,"bad".=infoValue bad];FoundIntersection init good bad->object["tag".=("Found"::String),"starting".=(case init of Starting->True;Started->False),"good".=infoValue good,"bad".=point bad]]
type Context=J.ContextWith Int (ChainSyncClientHandle IO TestBlock) IO Int TestBlock
data Event=Register Int Bool|Update Int Int|Forward Int Int|Backward Int Int|Await Int|Next Int|Answer Int Bool|Remove Int|Rotate Int deriving Show
instructionValue instruction=case instruction of J.RunNormally->object["tag".=("RunNormally"::String)];J.Restart->object["tag".=("Restart"::String)];J.JumpInstruction jump->jumpValue jump
jumpValue jump=case jump of J.JumpTo j->object["tag".=("Jump"::String),"goodPoint".=False,"info".=infoValue j];J.JumpToGoodPoint j->object["tag".=("Jump"::String),"goodPoint".=True,"info".=infoValue j]
runTrace :: String -> [Event] -> IO Value
runTrace label events=do
 handles<-atomically newChainSyncClientHandleCollection
 context<-atomically$J.makeContext handles(SlotNo 4)nullTracer
 contexts<-newIORef(Map.empty::Map.Map Int Context)
 pending<-newIORef Map.empty
 let snapshot=atomically$do
       peers<-cschcSeq handles
       forM(toList peers)$ \(pid,h)->do
        r<-readTVar(cschJumping h)
        j<-case r of Jumper v _->readTVar v;_->pure Nothing
        candidate<-csCandidate <$> readTVar(cschState h)
        latest<-readTVar(cschJumpInfo h)
        pure$object["id".=pid,"role".=roleValue j r,"info".=(infoValue <$> latest),"tip".=point(AF.headPoint candidate)]
     withPeer pid f=readIORef contexts >>= maybe(pure Nothing)f . Map.lookup pid
     process event=do
       outcome<-case event of
        Register pid caught->do
         c<-atomically$do
          cs<-newTVar ChainSyncState{csCandidate=AF.Empty AF.AnchorGenesis,csIdling=False,csLatestSlot=SM.SNothing}
          ji<-newTVar Nothing
          (c,_)<-J.registerClient(if caught then GSM.CaughtUp else GSM.Syncing)context pid cs(\jump->ChainSyncClientHandle{cschGDDKill=pure(),cschOnGsmStateChanged = \_ _->pure(),cschState=cs,cschJumping=jump,cschJumpInfo=ji})
          pure c
         modifyIORef' contexts(Map.insert pid c)
         pure Nothing
        Update pid n->withPeer pid$ \c->atomically(do { modifyTVar (cschState(J.handle c)) (\s->s{csCandidate=jTheirFragment(info n)}); J.jgUpdateJumpInfo(J.mkJumping c)(info n) })>>pure Nothing
        Forward pid n->withPeer pid$ \c->J.jgOnRollForward(J.mkJumping c)(castPoint(AF.headPoint(jTheirFragment(info n))))>>pure Nothing
        Backward pid n->withPeer pid$ \c->J.jgOnRollBackward(J.mkJumping c)(if n==0 then Origin else NotOrigin(SlotNo(fromIntegral n)))>>pure Nothing
        Await pid->withPeer pid$ \c->J.jgOnAwaitReply(J.mkJumping c)>>pure Nothing
        Remove pid->do
         result<-withPeer pid$ \c->atomically(J.unregisterClient c)>>pure Nothing
         modifyIORef' contexts(Map.delete pid)
         pure result
        Rotate pid->J.rotateDynamo nullTracer handles pid>>pure Nothing
        Next pid->withPeer pid$ \c->do
         r<-atomically$readTVar(cschJumping(J.handle c))
         wait<-case r of Jumper v _->atomically$fmap(not.isJust)(readTVar v);_->pure False
         if wait then pure$Just$object["tag".=("Wait"::String)] else do
          instruction<-J.jgNextInstruction(J.mkJumping c)
          case instruction of J.JumpInstruction jump->modifyIORef' pending(Map.insert pid jump);_->pure()
          pure$Just$instructionValue instruction
        Answer pid accepted->withPeer pid$ \c->do
         saved<-Map.lookup pid <$> readIORef pending
         case saved of
          Nothing->pure Nothing
          Just jump->do
           J.jgProcessJumpResult(J.mkJumping c)(if accepted then J.AcceptedJump jump else J.RejectedJump jump)
           modifyIORef' pending(Map.delete pid)
           pure$Just$object["answered".=jumpValue jump,"accepted".=accepted]
       peers<-snapshot
       pure$object["event".=show event,"outcome".=outcome,"peers".=peers]
 rows<-mapM process events
 pure$object["label".=label,"steps".=rows]
main=do
 [out]<-getArgs
 let common=[Register 1 False,Update 1 8,Register 2 False,Next 2]
     cases=[("caught-up",[Register 1 True,Register 2 True,Next 1,Next 2]),("accepted",common++[Answer 2 True,Forward 1 9,Next 2,Answer 2 True]),("bisect-reject",common++[Answer 2 False,Next 2,Answer 2 False,Next 2,Answer 2 False,Next 2,Answer 2 False,Next 2,Answer 2 True,Remove 1,Next 2]),("good-point-reject",common++[Answer 2 False,Next 2,Answer 2 False,Next 2,Answer 2 False,Next 2,Answer 2 False,Next 2,Answer 2 False,Next 2]),("rotation",common++[Register 3 False,Rotate 1,Next 2,Answer 2 True,Await 2,Next 3]),("rollback",common++[Answer 2 True,Forward 1 9,Backward 1 1,Next 2]),("fresh-response",common++[Register 3 False,Rotate 1,Answer 2 True,Next 3])]
 let objectorSteps=[Answer 2 False,Next 2,Answer 2 True,Next 2,Answer 2 False,Next 2,Answer 2 True,Next 2,Answer 2 True]
     objector=common++[Register 3 False]++objectorSteps
     extra=[("new-jump-while-response-in-flight",common++[Update 1 16,Forward 1 17,Answer 2 True,Next 2,Answer 2 True]),
            ("earlier-objector-and-backfill",objector++[Next 3,Answer 3 False,Next 3,Answer 3 False,Next 3,Answer 3 False,Next 3,Answer 3 False,Next 3,Answer 3 True,Remove 3,Next 2]),
            ("objector-agrees-disengages",objector++[Forward 2 6,Next 2]),
            ("objector-rolls-back-disengages",objector++[Backward 2 5,Next 2]),
            ("prefer-started-objector",[Register 1 False,Update 1 8,Register 3 False,Register 2 False,Next 2]++objectorSteps++[Remove 1,Next 2])]
 let generated=[let { ns=unGen (vectorOf 3 arbitrary) (mkQCGen (331001+ordinal)) 30 :: [Int]
                  ; n=4+abs(head ns `rem` 60)
                  ; accepted=even(ns!!1) }
                in ("generated-"++show ordinal,[Register 1 False,Update 1 n,Register 2 False,Register 3 False,Next 2,Answer 2 accepted,Forward 1 (n+1),Rotate 1,Next 2,Answer 2 True,Remove 1,Next 2,Await 2,Next 3])
               |ordinal<-[0..63::Int]]
 rows<-mapM(uncurry runTrace)(cases++extra++generated)
 LBS.writeFile out(LBS.unlines(map encode rows))
