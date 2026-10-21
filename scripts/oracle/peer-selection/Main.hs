-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE OverloadedStrings #-}
import Control.Monad (forM_)
import Control.Concurrent.Class.MonadSTM.Strict
import Data.Aeson
import qualified Data.ByteString.Lazy.Char8 as B
import qualified Data.Map.Strict as M
import qualified Data.Set as S
import System.Random (mkStdGen)
import Ouroboros.Network.Diffusion.Policies
import Ouroboros.Network.PeerSelection.Governor.Types
import Ouroboros.Network.PeerSelection.PeerMetric
import Ouroboros.Network.PeerSelection.Types (PeerSource(..))

main :: IO ()
main = forM_ [149001..149016] $ \seed ->
  forM_ [0,1,2,5,12] $ \size ->
  forM_ [0..2] $ \pattern ->
  forM_ (S.toList $ S.fromList [0,1,size `div` 2,size,size+1]) $ \n -> do
    let available = S.fromList [0..size-1] :: S.Set Int
        tepid p = case pattern :: Int of { 0 -> False; 1 -> True; _ -> even p }
        failures p = p `mod` 5
        source _ = PeerSourcePeerShare
        score p = if p `mod` 4 == 0 then Nothing else Just (p `mod` 3, if odd p then Just (fromIntegral (p `mod` 3)) else Nothing)
        scores = M.fromList [(p,s) | p <- S.toList available, Just s <- [score p]]
    rng <- newTVarIO (mkStdGen seed)
    draws <- atomically $ addRand rng available (,)
    metrics <- newPeerMetric peerMetricsConfiguration :: IO (PeerMetrics IO Int)
    let policy = simplePeerSelectionPolicy rng metrics
        run pick = atomically $ do
          writeTVar rng (mkStdGen seed)
          pick source failures tepid available n
    promotion <- run $ policyPickColdPeersToPromote policy
    warm <- run $ policyPickWarmPeersToPromote policy
    demote <- run $ policyPickWarmPeersToDemote policy
    forget <- run $ policyPickColdPeersToForget policy
    (hot, traced) <- run $ mkHotDemotionPolicy rng (pure scores)
    B.putStrLn $ encode $ object
      ["seed" .= seed, "size" .= size, "pattern" .= pattern, "n" .= n,
       "draws" .= [[fromIntegral p :: Integer, fromIntegral w] | (p,w) <- M.toList draws],
       "tepid" .= [p | p <- S.toList available, tepid p],
       "failures" .= [[p, failures p] | p <- S.toList available],
       "scores" .= [object ["peer" .= p,"score" .= fmap fst (score p),"joined" .= (fmap (fmap toInteger . snd) (score p))] | p <- S.toList available],
       "promotion" .= S.toList promotion,"warmPromotion" .= S.toList warm,
       "warmDemotion" .= S.toList demote,"forget" .= S.toList forget,
       "hotDemotion" .= S.toList hot,"traced" .= M.toList traced]
