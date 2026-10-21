-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE OverloadedStrings #-}
module Main where
import qualified Codec.CBOR.Write as W
import qualified Data.ByteString.Base16 as H
import qualified Data.ByteString.Char8 as B
import qualified Data.ByteString.Short as S
import Data.List.NonEmpty (NonEmpty(..))
import qualified Data.IP as IP
import Data.Ratio
import Ouroboros.Network.Block
import Ouroboros.Network.Magic
import Ouroboros.Network.Point (Block(..), WithOrigin(..))
import Ouroboros.Network.PeerSelection.LedgerPeers.Utils (accumulateBigLedgerStake)
import Ouroboros.Network.PeerSelection.LedgerPeers.Type
import Ouroboros.Network.PeerSelection.RelayAccessPoint
main :: IO ()
main = mapM_ (B.putStrLn . H.encode . W.toStrictByteString)
 [ encodeLedgerPeerSnapshot LedgerPeerSnapshotDoesntSupportSRV legacy
 , encodeLedgerPeerSnapshot LedgerPeerSnapshotSupportsSRV legacy
 , encodeLedgerPeerSnapshot LedgerPeerSnapshotSupportsSRV modern
 , encodeLedgerPeerSnapshot LedgerPeerSnapshotSupportsSRV allPools
 , encodeLedgerPeerSnapshot LedgerPeerSnapshotDoesntSupportSRV (LedgerPeerSnapshotV2 (Origin, []))
 , encodeLedgerPeerSnapshot LedgerPeerSnapshotSupportsSRV (LedgerBigPeerSnapshotV23 (Point Origin) (NetworkMagic 42) [])
 , encodeLedgerPeerSnapshot LedgerPeerSnapshotSupportsSRV (LedgerAllPeerSnapshotV23 (Point Origin) (NetworkMagic 42) [])
 , encodeLedgerPeerSnapshot LedgerPeerSnapshotSupportsSRV (LedgerAllPeerSnapshotV23 point (NetworkMagic 42) ipPools)
 , encodeLedgerPeerSnapshot LedgerPeerSnapshotSupportsSRV (LedgerBigPeerSnapshotV23 point (NetworkMagic 42) (accumulateBigLedgerStake ipPools))
 , encodeLedgerPeerSnapshot LedgerPeerSnapshotSupportsSRV (LedgerAllPeerSnapshotV23 point (NetworkMagic 42) crossing)
 , encodeLedgerPeerSnapshot LedgerPeerSnapshotSupportsSRV (LedgerBigPeerSnapshotV23 point (NetworkMagic 42) (accumulateBigLedgerStake crossing))
 ]
 where
  mixed = LedgerRelayAccessDomain "relay.example" 3001 :| [LedgerRelayAccessSRVDomain "srv.example"]
  srv = LedgerRelayAccessSRVDomain "only.example" :| []
  pools = accumulateBigLedgerStake [(PoolStake (3%5), mixed), (PoolStake (3%10), srv), (PoolStake (1%10), mixed)]
  crossing = [(PoolStake (3%5), mixed), (PoolStake (31%100), srv), (PoolStake (9%100), mixed)]
  ipPools = [(PoolStake 1, LedgerRelayAccessAddress (IP.IPv4 (IP.toIPv4 [192,0,2,1])) 3001 :|
               [LedgerRelayAccessAddress (IP.IPv6 (IP.toIPv6 [0x2001,0xdb8,0,0,0,0,0,1])) 3002])]
  point = Point (At (Block (SlotNo 42) (RawBlockHash (S.pack (replicate 32 1)))))
  legacy = LedgerPeerSnapshotV2 (At (SlotNo 42), pools)
  modern = LedgerBigPeerSnapshotV23 point (NetworkMagic 42) pools
  allPools = LedgerAllPeerSnapshotV23 point (NetworkMagic 42) [(s,r) | (_,(s,r)) <- pools]
