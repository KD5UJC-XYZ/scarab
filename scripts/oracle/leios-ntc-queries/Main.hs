-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE AllowAmbiguousTypes #-}
{-# LANGUAGE FlexibleContexts #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

import Cardano.Crypto.Hash (hashFromBytes, hashToBytes)
import Cardano.Crypto.Leios (LeiosCommittee (..))
import Cardano.Ledger.Api.State.Query (
  QueryResultLeiosCommittee (..),
  QueryResultStakePoolState (..),
  queryLeiosCommittee,
  queryStakePoolState,
 )
import Cardano.Ledger.BaseTypes (EpochNo (..), Network (Testnet), StrictMaybe (..))
import Cardano.Ledger.Binary (DecCBOR, DecoderError, EncCBOR, getVersion64, serialize')
import qualified Cardano.Ledger.Binary.Plain as Plain
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Core (EraTxOut, eraProtVerLow)
import Cardano.Ledger.Dijkstra (DijkstraEra)
import Cardano.Ledger.Hashes (KeyHash (..), KeyRole (StakePool))
import Cardano.Ledger.Shelley.LedgerState (
  NewEpochState,
  StashedAVVMAddresses,
  esLStateL,
  lsCertStateL,
  nesELL,
  nesEsL,
 )
import Cardano.Ledger.State (
  EraCertState,
  EraGov,
  EraStake,
  PState,
  certPStateL,
  psFutureStakePoolParamsL,
  psRetiringL,
  psStakePoolsL,
  stakePoolStateToStakePoolParams,
 )
import Control.Monad (unless, when)
import Data.Aeson (Value, encode, object, (.=))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as LBS
import qualified Data.Map.Strict as Map
import Data.Set (Set)
import qualified Data.Set as Set
import Data.Text (Text)
import Lens.Micro (Lens', (%~), (&), (^.))
import qualified Data.Text as T
import Numeric (showHex)
import System.Environment (getArgs)
import System.Exit (die)

hex :: BS.ByteString -> Text
hex = T.pack . concatMap (\w -> (if w < 16 then ('0' :) else id) (showHex w "")) . BS.unpack

poolId :: KeyHash StakePool -> Text
poolId = hex . hashToBytes . unKeyHash

present :: StrictMaybe a -> Bool
present SNothing = False
present (SJust _) = True

seats :: LeiosCommittee -> Int
seats = length . leiosCommitteeSeats

-- The answers of the two queries on one ledger state, as ouroboros-consensus 2416493b answers and
-- writes them (`ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Ledger/Query.hs`):
-- `GetStakePoolState mset b` is `SL.queryStakePoolState st mset b` (`:467-468`) and `GetLeiosCommittee`
-- is `SL.queryLeiosCommittee st` (`:469-470`), where `st` is the `NewEpochState`, each written with
-- `LC.toEraCBOR @era` (`:969-970`): `encCBOR` at the era's lowest version. The state is read with
-- `fromCBOR` (`fromEraCBOR @era`, cardano-ledger 9df9f98c `Shelley/LedgerState/Types.hs:423-433`), as
-- consensus reads it (`Shelley/Ledger/Ledger.hs:918`), and refused unless `toCBOR` gives its bytes back.
answers ::
  forall era.
  ( EraTxOut era
  , EraGov era
  , EraStake era
  , EraCertState era
  , DecCBOR (StashedAVVMAddresses era)
  , EncCBOR (StashedAVVMAddresses era)
  ) =>
  BS.ByteString ->
  IO Value
answers bytes = do
  nes <-
    either (\e -> die ("NewEpochState: " <> show e)) pure $
      (Plain.decodeFull' bytes :: Either DecoderError (NewEpochState era))
  unless (Plain.serialize' nes == bytes) $
    die "the NewEpochState does not encode back to its own bytes"
  -- A pool id no pool is registered under: 28 bytes of 0xff.
  unregistered <-
    maybe (die "no key hash of 28 bytes") (pure . KeyHash) (hashFromBytes (BS.replicate 28 0xff))
  let encoded :: EncCBOR a => a -> Text
      encoded = hex . serialize' (eraProtVerLow @era)
      everyPool = queryStakePoolState nes Nothing True
      registered = Map.keys everyPool
      selected = Set.fromList (take 2 registered ++ [unregistered])
      stakePoolState :: Maybe (Set (KeyHash StakePool)) -> Bool -> Value
      stakePoolState mset b =
        object
          [ "query" .= ("GetStakePoolState" :: Text)
          , "tag" .= (40 :: Int)
          , "poolIds" .= fmap (map poolId . Set.toAscList) mset
          , "includeDelegators" .= b
          , "result" .= encoded (queryStakePoolState nes mset b)
          ]
      committee = queryLeiosCommittee nes
      -- The derived state: the state with its smallest registered pool P retiring at the next epoch and with
      -- future parameters, P's own as `stakePoolStateToStakePoolParams` makes them
      -- (`libs/cardano-ledger-core/src/Cardano/Ledger/State/StakePool.hs:325-343`) with the network the
      -- inputs' Shelley genesis files name (`networkId` Testnet), set through the ledger's own lenses
      -- (`State/CertState.hs:490-497`). No ledger rule makes it; it exercises the answer's two fields
      -- `qrspsRetiring` and `qrspsFutureStakePoolParams`, which no captured state holds.
      pState :: Lens' (NewEpochState era) (PState era)
      pState = nesEsL . esLStateL . lsCertStateL . certPStateL
      retiringIn = EpochNo (unEpochNo (nes ^. nesELL) + 1)
      derivedCases = case Map.lookupMin (nes ^. pState . psStakePoolsL) of
        Nothing -> []
        Just (p, sps) ->
          let derived =
                nes
                  & pState . psRetiringL %~ Map.insert p retiringIn
                  & pState . psFutureStakePoolParamsL %~ Map.insert p (stakePoolStateToStakePoolParams Testnet p sps)
              derivedCase b =
                object
                  [ "query" .= ("GetStakePoolState" :: Text)
                  , "tag" .= (40 :: Int)
                  , "derived" .= ("retiring+future" :: Text)
                  , "retiringEpoch" .= unEpochNo retiringIn
                  , "network" .= ("Testnet" :: Text)
                  , "poolIds" .= [poolId p]
                  , "includeDelegators" .= b
                  , "result" .= encoded (queryStakePoolState derived (Just (Set.singleton p)) b)
                  ]
           in [derivedCase True, derivedCase False]
      count field = Map.size (Map.filter (present . field) everyPool)
  when (Map.member unregistered everyPool) $
    die "the pool id of 28 bytes of 0xff is registered"
  pure $
    object
      [ "version" .= getVersion64 (eraProtVerLow @era)
      , "roundTrip" .= True
      , "pools" .= Map.size everyPool
      , "poolsWithBlsKey" .= count qrspsBlsKey
      , "poolsRetiring" .= count qrspsRetiring
      , "poolsWithFutureParams" .= count qrspsFutureStakePoolParams
      , "poolsWithMetadata" .= count qrspsMetadata
      , "delegators" .= sum (map qrspsNumDelegators (Map.elems everyPool))
      , "currentCommitteeSeats" .= seats (qrlcCurrentLeiosCommittee committee)
      , "futureCommitteeSeats" .= case qrlcFutureLeiosCommittee committee of
          SNothing -> Nothing
          SJust future -> Just (seats future)
      , "cases"
          .= ( [stakePoolState Nothing True, stakePoolState Nothing False]
                 ++ ( if length registered >= 2
                        then [stakePoolState (Just selected) False, stakePoolState (Just selected) True]
                        else []
                    )
                 ++ [ stakePoolState (Just Set.empty) True
                    , object
                        [ "query" .= ("GetLeiosCommittee" :: Text)
                        , "tag" .= (41 :: Int)
                        , "result" .= encoded committee
                        ]
                    ]
                 ++ derivedCases
             )
      ]

main :: IO ()
main = do
  args <- getArgs
  (era, path) <- case args of
    [e, p] -> pure (e, p)
    _ -> die "two arguments required: the era (conway or dijkstra) and the NewEpochState's file"
  bytes <- BS.readFile path
  result <- case era of
    "conway" -> answers @ConwayEra bytes
    "dijkstra" -> answers @DijkstraEra bytes
    _ -> die ("unknown era: " <> era)
  LBS.putStr (encode result)
