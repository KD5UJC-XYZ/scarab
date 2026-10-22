-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE OverloadedStrings #-}
module Main where
import Control.Monad (forM_)
import Control.Tracer (nullTracer)
import Data.Aeson
import Data.ByteString.Lazy.Char8 qualified as B
import Data.Map.Strict qualified as M
import Data.Set qualified as S
import Data.IP (IP(..), toIPv4)
import System.Random (mkStdGen)
import Ouroboros.Network.PeerSelection.PeerSelectionActions (requestPublicRootPeersImpl)
import Ouroboros.Network.PeerSelection.RootPeersDNS
import Ouroboros.Network.PeerSelection.PeerAdvertise
import Ouroboros.Network.PeerSelection.RelayAccessPoint
import Ouroboros.Network.PeerSelection.LedgerPeers.Type
import Ouroboros.Network.PeerSelection.PublicRootPeers qualified as P

main :: IO ()
main = forM_ [True] $ \big ->
  forM_ [0..2] $ \ledgerState ->
  forM_ [0,1,4] $ \requested -> do
    semaphore <- newLedgerAndPublicRootDNSSemaphore
    let dns :: PeerActionsDNS Int () IO
        dns = PeerActionsDNS { paToPeerAddr = \_ port -> fromIntegral port, paDnsActions = DNSActions { dnsResolverResource = \_ -> pure (constantResource (Right ())), dnsAsyncResolverResource = \_ -> pure (constantResource (Right ())), dnsLookupWithTTL = \_ _ _ _ _ -> pure (Right []) } }
        kind = if big then SomeLedgerPeersKind SingBigLedgerPeers else SomeLedgerPeersKind SingAllLedgerPeers
        input = if ledgerState == (0::Int) then Nothing else Just (if ledgerState == 1 then S.empty else S.fromList [1,2], 30)
        configured = M.fromList [(RelayAccessAddress (IPv4 (toIPv4 [127,0,0,1])) 3011,DoAdvertisePeer),(RelayAccessAddress (IPv4 (toIPv4 [127,0,0,1])) 3012,DoNotAdvertisePeer)]
    (result,ttl) <- requestPublicRootPeersImpl nullTracer (pure configured) dns semaphore id (\_ _ -> pure input) kind (mkStdGen 311) requested
    B.putStrLn $ encode $ object ["big" .= big,"ledgerState" .= ledgerState,"requested" .= requested,"ledger" .= S.toList (P.getLedgerPeers result),"bigLedger" .= S.toList (P.getBigLedgerPeers result),"extra" .= [object ["peer" .= peer,"advertise" .= (advertise == DoAdvertisePeer)] | (peer,advertise) <- M.toList (P.getExtraPeers result)],"ttlPicos" .= (floor (toRational ttl * 1000000000000) :: Integer)]
