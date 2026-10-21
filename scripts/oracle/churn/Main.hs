-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE NamedFieldPuns #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ScopedTypeVariables #-}
module Main where
import Control.Concurrent.Class.MonadSTM.Strict
import Control.Monad.Class.MonadFork (forkIO)
import Control.Monad.Class.MonadTimer.SI (threadDelay)
import Control.Monad.IOSim (IOSim, runSimOrThrow)
import Control.Tracer (Tracer, mkTracer, nullTracer)
import Control.Monad (forM_, void)
import Data.Aeson
import Data.ByteString.Lazy.Char8 qualified as B
import Data.Ratio (numerator, denominator)
import System.Random (StdGen, mkStdGen, randomR)
import Cardano.Network.ConsensusMode
import Cardano.Network.LedgerStateJudgement
import Cardano.Network.LedgerPeerConsensusInterface qualified as Cardano
import Cardano.Network.PeerSelection.Bootstrap
import Cardano.Network.PeerSelection.Churn qualified as Cardano
import Ouroboros.Network.Diffusion.Types (NoExtraPeers, ViewExtraPeers(..))
import Ouroboros.Network.BlockFetch (FetchMode(..), PraosFetchMode(..))
import Ouroboros.Network.PeerSelection.Churn (PeerChurnArgs(..))
import Ouroboros.Network.PeerSelection.Governor.Types hiding (targets)
import Ouroboros.Network.PeerSelection.LedgerPeers.Type qualified as Ledger
import Ouroboros.Network.PeerSelection.State.LocalRootPeers (HotValency(..))

values :: PeerSelectionTargets -> [Int]
values t = [targetNumberOfRootPeers t,targetNumberOfKnownPeers t,targetNumberOfEstablishedPeers t,targetNumberOfActivePeers t,targetNumberOfKnownBigLedgerPeers t,targetNumberOfEstablishedBigLedgerPeers t,targetNumberOfActiveBigLedgerPeers t]
targets :: Int -> PeerSelectionTargets
targets n = PeerSelectionTargets (10+n) (80+n) (40+n) (15+n) (30+n) (20+n) (10+n)
basis :: Int -> Bool -> PeerSelectionTargets
basis shape syncing = case shape of
  0 -> targets (if syncing then 100 else 0)
  1 -> if syncing then PeerSelectionTargets 1 1 1 0 0 0 0 else nullPeerSelectionTargets
  _ -> if syncing then PeerSelectionTargets 20 20 10 0 10 5 0 else PeerSelectionTargets 10 10 5 0 5 3 0
nextMode :: Int -> FetchMode -> FetchMode
nextMode phase mode = if phase == 0 then mode else case mode of
  GenesisFetchMode -> GenesisFetchMode
  PraosFetchMode FetchModeBulkSync -> PraosFetchMode FetchModeDeadline
  _ -> PraosFetchMode FetchModeBulkSync
nextBootstrap :: Int -> UseBootstrapPeers -> UseBootstrapPeers
nextBootstrap phase bootstrap = if phase == 0 then bootstrap else case bootstrap of
  DontUseBootstrapPeers -> UseBootstrapPeers []
  _ -> DontUseBootstrapPeers
nextJudgement :: Int -> LedgerStateJudgement -> LedgerStateJudgement
nextJudgement phase judgement = if phase == 0 then judgement else case judgement of
  TooOld -> YoungEnough
  _ -> TooOld

observe :: ConsensusMode -> FetchMode -> UseBootstrapPeers -> LedgerStateJudgement -> Int -> Int -> Int -> Int -> Value
observe consensus mode bootstrap judgement localHot seed shape phase =
  let (initial,after,waits,steps) = runSimOrThrow $ simulation consensus mode bootstrap judgement localHot seed shape phase
      maxFuzz = case (nextMode phase mode,consensus) of { (PraosFetchMode FetchModeDeadline,_) -> 600; (_,GenesisMode) -> 600; _ -> 60 }
      (fuzz,_) = randomR (0,maxFuzz) (mkStdGen seed) :: (Double, StdGen)
      r = toRational fuzz
  in object ["consensus" .= show consensus,"fetch" .= show mode,"bootstrap" .= show bootstrap,"judgement" .= show judgement,"localHot" .= localHot,"seed" .= seed,"shape" .= shape,"phase" .= phase,"afterFetch" .= show (nextMode phase mode),"afterBootstrap" .= show (nextBootstrap phase bootstrap),"afterJudgement" .= show (nextJudgement phase judgement),"deadline" .= values (basis shape False),"sync" .= values (basis shape True),"initial" .= values initial,"afterHead" .= values after,"waitPicos" .= take 2 waits,"steps" .= take 12 steps,"fuzzNumerator" .= numerator r,"fuzzDenominator" .= denominator r]

simulation :: forall s. ConsensusMode -> FetchMode -> UseBootstrapPeers -> LedgerStateJudgement -> Int -> Int -> Int -> Int -> IOSim s (PeerSelectionTargets,PeerSelectionTargets,[Integer],[Value])
simulation consensus mode bootstrap judgement localHot seed shape phase = do
  selected <- newTVarIO (basis shape False)
  applied <- newTVarIO (basis shape False)
  fetchVar <- newTVarIO mode
  bootstrapVar <- newTVarIO bootstrap
  judgementVar <- newTVarIO judgement
  churnMode <- newTVarIO (Cardano.ChurnMode mode)
  waits <- newTVarIO []
  steps <- newTVarIO []
  let tracer :: Tracer (IOSim s) (TracePeerSelection () () (NoExtraPeers Int) Int)
      tracer = mkTracer $ \event -> case event of
        TraceChurnWait wait -> atomically $ modifyTVar waits (++ [numerator (toRational wait * 1000000000000)])
        TraceChurnAction duration action change -> do
          proposal <- atomically $ readTVar selected
          atomically $ modifyTVar steps (++ [object ["action" .= show action,"proposal" .= values proposal,"sane" .= sanePeerSelectionTargets proposal,"change" .= change,"durationPicos" .= numerator (toRational duration * 1000000000000)]])
        TraceChurnTimeout duration action change -> do
          proposal <- atomically $ readTVar selected
          atomically $ modifyTVar steps (++ [object ["action" .= show action,"proposal" .= values proposal,"sane" .= sanePeerSelectionTargets proposal,"change" .= change,"durationPicos" .= numerator (toRational duration * 1000000000000),"timeout" .= True]])
        _ -> pure ()
      counters = do
        proposal <- readTVar selected
        old <- readTVar applied
        let t = if sanePeerSelectionTargets proposal then proposal else old
        writeTVar applied t
        pure $ (emptyPeerSelectionCounters NoExtraView) { numberOfRootPeers = targetNumberOfRootPeers t, numberOfKnownPeers = targetNumberOfKnownPeers t, numberOfEstablishedPeers = targetNumberOfEstablishedPeers t, numberOfActivePeers = targetNumberOfActivePeers t, numberOfKnownBigLedgerPeers = targetNumberOfKnownBigLedgerPeers t, numberOfEstablishedBigLedgerPeers = targetNumberOfEstablishedBigLedgerPeers t, numberOfActiveBigLedgerPeers = targetNumberOfActiveBigLedgerPeers t }
      api = Ledger.LedgerPeersConsensusInterface { Ledger.lpGetLatestSlot = retry, Ledger.lpGetLedgerPeers = pure [], Ledger.lpExtraAPI = Cardano.LedgerPeersConsensusInterface { Cardano.readFetchMode = readTVar fetchVar, Cardano.getLedgerStateJudgement = readTVar judgementVar, Cardano.updateOutboundConnectionsState = \_ -> pure (), Cardano.getImmutableBlockPoint = \_ callback -> callback (pure (Left Cardano.ImmutableBlockPointGenesisPoint)) } }
  _ <- forkIO $ void $ Cardano.peerChurnGovernor PeerChurnArgs { pcaPeerSelectionTracer = tracer, pcaDeadlineInterval = 3300, pcaBulkInterval = 900, pcaPeerRequestTimeout = 5, pcaRng = mkStdGen seed, pcaPeerSelectionVar = selected, pcaReadCounters = counters, getLedgerPeersAPI = api, getLocalRootHotTarget = pure (HotValency localHot), pcaPeerSelectionTargets = basis shape False, getExtraArgs = Cardano.ExtraArguments { Cardano.modeVar = churnMode, Cardano.genesisPeerSelectionTargets = basis shape True, Cardano.readUseBootstrap = readTVar bootstrapVar, Cardano.consensusMode = consensus, Cardano.tracerChurnMode = nullTracer } }
  threadDelay 1
  initial <- atomically $ readTVar selected
  atomically $ do
    writeTVar fetchVar (nextMode phase mode)
    writeTVar bootstrapVar (nextBootstrap phase bootstrap)
    writeTVar judgementVar (nextJudgement phase judgement)
  threadDelay 3
  after <- atomically $ readTVar selected
  threadDelay 8000
  observed <- atomically $ readTVar waits
  observedSteps <- atomically $ readTVar steps
  pure (initial,after,observed,observedSteps)

main :: IO ()
main = forM_ [(PraosMode,PraosFetchMode FetchModeBulkSync),(PraosMode,PraosFetchMode FetchModeDeadline),(GenesisMode,GenesisFetchMode)] $ \(consensus,mode) ->
  forM_ [DontUseBootstrapPeers,UseBootstrapPeers []] $ \bootstrap ->
  forM_ [TooOld,YoungEnough] $ \judgement ->
  forM_ [0,1,8,100] $ \localHot ->
  forM_ [311001..311004] $ \seed ->
  forM_ [0..2] $ \shape ->
  forM_ [0,1] $ \phase -> B.putStrLn $ encode $ observe consensus mode bootstrap judgement localHot seed shape phase
