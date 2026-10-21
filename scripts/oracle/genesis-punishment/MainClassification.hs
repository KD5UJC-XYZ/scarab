{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE TypeApplications #-}
{-# LANGUAGE OverloadedStrings #-}
-- SPDX-License-Identifier: Apache-2.0
module Main where
import qualified Control.Concurrent as C
import qualified Control.Exception as E
import Ouroboros.Consensus.Ledger.Tables (convertMapKind)
import Data.Aeson
import qualified Data.ByteString.Lazy.Char8 as BL
import System.Environment
import Ouroboros.Consensus.Util.IOLike (atomically)
import Control.ResourceRegistry (withRegistry)
import Ouroboros.Consensus.Storage.ChainDB.API as API
import qualified Ouroboros.Consensus.Storage.ChainDB.Impl as DB
import Ouroboros.Consensus.Storage.ChainDB.API.Types.InvalidBlockPunishment as P
import Test.Util.ChainDB
import Test.Ouroboros.Storage.TestBlock
import Cardano.Ledger.BaseTypes (knownNonZeroBounded)
import Ouroboros.Consensus.Block (blockSlot, blockHash, SlotNo(..))
import Ouroboros.Consensus.Config.SecurityParam (SecurityParam(..))
import qualified Ouroboros.Consensus.Storage.ImmutableDB as Imm

-- The opaque punishment is produced inside its real target thread. No copied
-- classification logic or custom constructor stands in for ChainDB's branch.
marker :: String -> C.MVar String -> IO (P.InvalidBlockPunishment IO, C.ThreadId)
marker label observed = do
 ready <- C.newEmptyMVar
 tid <- C.forkIO $ E.catch
  (do p <- P.mkPunishThisThread; C.putMVar ready p; C.threadDelay maxBound)
  (\(err :: E.SomeException) -> case E.fromException err :: Maybe E.AsyncException of
    Just E.ThreadKilled -> pure ()
    _ -> C.putMVar observed label)
 p <- C.takeMVar ready
 pure (p,tid)
observeAdd :: ChainDB IO TestBlock -> TestBlock -> IO Value
observeAdd db block = do
 observed <- C.newEmptyMVar
 (itself,a) <- marker "BlockItself" observed
 (prefix,b) <- marker "BlockPrefix" observed
 result <- API.addBlock db (P.branch $ \i -> case i of P.BlockItself -> itself; P.BlockPrefix -> prefix) block
 -- throwTo synchronizes delivery; give the target handler a turn to record it.
 C.threadDelay 10000
 punishment <- C.tryTakeMVar observed
 C.killThread a; C.killThread b
 chain <- atomically $ API.getCurrentChain db
 pure $ object ["added" .= show block,"result" .= show result,"punishment" .= punishment,"chain" .= show chain]
pointJSON :: TestBlock -> Value
pointJSON block=let SlotNo slot=blockSlot block in object ["slot" .= slot,"hash" .= show (blockHash block)]
runCase :: String -> [TestBlock] -> TestBlock -> Maybe TestBlock -> IO Value
runCase label blocks invalid lastValid = withRegistry $ \registry -> do
 dbs <- emptyNodeDBs
 let cfg=mkTestConfig (SecurityParam $ knownNonZeroBounded @4) (Imm.ChunkSize True 100)
     args=fromMinimalChainDbArgs $ MinimalChainDbArgs cfg (Imm.simpleChunkInfo 100) (convertMapKind testInitExtLedger) registry dbs
 DB.withDB args $ \db -> do
  steps <- mapM (observeAdd db) blocks
  pure $ object ["label" .= (label::String),"steps" .= steps, "classification" .= object ["added" .= pointJSON (last blocks),"invalid" .= pointJSON invalid,"lastValid" .= fmap pointJSON lastValid]]
main = do
 [output] <- getArgs
 let body fork valid=TestBody fork valid Nothing
     a=firstBlock 0 $ body 0 True
     b=mkNextBlock a 1 $ body 0 True
     c=mkNextBlock b 2 $ body 0 True
     x=firstBlock 0 $ body 1 True
     y=mkNextBlock x 1 $ body 1 False
     z=mkNextBlock y 2 $ body 1 True
     w=mkNextBlock z 3 $ body 1 True
     ebb=firstEBB (const True) $ body 0 True
     invalidSame=mkNextBlock ebb 0 $ body 0 False
     invalidLater=mkNextBlock ebb 1 $ body 0 False
 rows <- sequence [runCase "invalid-added-block" [firstBlock 0 $ body 0 False] (firstBlock 0 $ body 0 False) Nothing,
                   runCase "invalid-later-suffix" [invalidLater,ebb] invalidLater (Just ebb),
                   runCase "invalid-prefix-of-preferred-fork" [a,b,c,x,y,z,w] y (Just x),
                   runCase "same-slot-valid-EBB-before-invalid-suffix" [invalidSame,ebb] invalidSame (Just ebb)]
 BL.writeFile output $ BL.unlines $ map encode rows
