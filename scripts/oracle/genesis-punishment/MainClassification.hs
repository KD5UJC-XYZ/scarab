{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE TypeApplications #-}
{-# LANGUAGE OverloadedStrings #-}
-- SPDX-License-Identifier: Apache-2.0
module Main where
import qualified Marker
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

-- Both opaque targets are joined after processing, including the no-punishment case.
observeAdd :: Marker.Observer -> ChainDB IO TestBlock -> TestBlock -> IO Value
observeAdd observer db block = do
 itself <- Marker.start
 prefix <- Marker.start
 result <- API.addBlock db (P.branch $ \i -> case i of
   P.BlockItself -> Marker.punishment itself
   P.BlockPrefix -> Marker.punishment prefix) block
 didItself <- Marker.finish observer itself
 didPrefix <- Marker.finish observer prefix
 observed <- case (didItself,didPrefix) of
  (False,False) -> pure Nothing
  (True,False) -> pure $ Just ("BlockItself"::String)
  (False,True) -> pure $ Just "BlockPrefix"
  (True,True) -> fail "one native addition punished both branches"
 chain <- atomically $ API.getCurrentChain db
 pure $ object ["added" .= show block,"result" .= show result,"punishment" .= observed,"chain" .= show chain]
pointJSON :: TestBlock -> Value
pointJSON block=let SlotNo slot=blockSlot block in object ["slot" .= slot,"hash" .= show (blockHash block)]
runCase :: Marker.Observer -> String -> [TestBlock] -> TestBlock -> Maybe TestBlock -> IO Value
runCase observer label blocks invalid lastValid = withRegistry $ \registry -> do
 dbs <- emptyNodeDBs
 let cfg=mkTestConfig (SecurityParam $ knownNonZeroBounded @4) (Imm.ChunkSize True 100)
     args=fromMinimalChainDbArgs $ MinimalChainDbArgs cfg (Imm.simpleChunkInfo 100) (convertMapKind testInitExtLedger) registry dbs
 DB.withDB args $ \db -> do
  steps <- mapM (observeAdd observer db) blocks
  pure $ object ["label" .= (label::String),"steps" .= steps, "classification" .= object ["added" .= pointJSON (last blocks),"invalid" .= pointJSON invalid,"lastValid" .= fmap pointJSON lastValid]]
main = do
 observer <- Marker.calibrate
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
 rows <- sequence [runCase observer "invalid-added-block" [firstBlock 0 $ body 0 False] (firstBlock 0 $ body 0 False) Nothing,
                   runCase observer "invalid-later-suffix" [invalidLater,ebb] invalidLater (Just ebb),
                   runCase observer "invalid-prefix-of-preferred-fork" [a,b,c,x,y,z,w] y (Just x),
                   runCase observer "same-slot-valid-EBB-before-invalid-suffix" [invalidSame,ebb] invalidSame (Just ebb)]
 BL.writeFile output $ BL.unlines $ map encode rows
