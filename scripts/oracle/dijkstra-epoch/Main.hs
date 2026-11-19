-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE PackageImports #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TupleSections #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

import Cardano.Crypto.Hash (hashFromBytes, hashToBytes)
import Cardano.Crypto.Leios (LeiosCommittee (..), LeiosSeat (..))
import Cardano.Ledger.BaseTypes (
  EpochInterval (..),
  EpochNo (..),
  Globals (..),
  Network,
  StrictMaybe (..),
  epochInfoPure,
  unNonZero,
 )
import Cardano.Ledger.Binary (DecoderError, getVersion64)
import qualified Cardano.Ledger.Binary.Plain as Plain
import Cardano.Ledger.Coin (Coin (..))
import Cardano.Ledger.Core (eraProtVerLow)
import Cardano.Ledger.Dijkstra (DijkstraEra)
import Cardano.Ledger.Dijkstra.Rules (maxKeyAgeEpochs)
import Cardano.Ledger.Hashes (KeyHash (..), KeyRole (StakePool), KeyRoleVRF (StakePoolVRF), VRFVerKeyHash (..))
import Cardano.Ledger.Shelley.API (applyTickNoEvents)
import Cardano.Ledger.Shelley.Genesis (ShelleyGenesis (..), fromNominalDiffTimeMicro, mkShelleyGlobals)
import Cardano.Ledger.Shelley.LedgerState (
  NewEpochState (..),
  PulsingRewUpdate (..),
  esLStateL,
  esSnapshotsL,
  lsCertStateL,
  lsUTxOStateL,
  nesEsL,
  utxosDepositedL,
 )
import Cardano.Ledger.State (
  ChainAccountState (..),
  MarkSnapShot (..),
  SetSnapShot (..),
  PState,
  SnapShots (..),
  StakePoolState (..),
  certPStateL,
  chainAccountStateL,
  populateVRFKeyHashes,
  psFutureStakePoolParamsL,
  psRetiringL,
  psStakePoolsL,
  psVRFKeyHashesL,
  sppVrfL,
  spsVrfL,
  stakePoolStateToStakePoolParams,
 )
import Cardano.Slotting.EpochInfo (epochInfoFirst, epochInfoSize)
import Cardano.Slotting.EpochInfo.Impl (fixedEpochInfo)
import Cardano.Slotting.Slot (EpochSize (..), SlotNo (..))
import Cardano.Slotting.Time (mkSlotLength)
import Control.Exception (SomeException, displayException, evaluate, try)
import Control.Monad (unless)
import Data.Aeson (Value (Null), eitherDecodeFileStrict, encode, object, (.=))
import qualified Data.Aeson.Key as Key
import Data.Aeson.Types (Pair)
import qualified Data.ByteString as BS
import qualified "base16-bytestring" Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy as LBS
import Data.Functor.Identity (runIdentity)
import Data.List (scanl')
import qualified Data.Map.Strict as Map
import Data.Text (Text)
import qualified Data.Text as T
import qualified Data.Text.Encoding as T
import Lens.Micro (Lens', (%~), (&), (.~), (^.))
import System.Environment (getArgs)
import System.Exit (die)
import Text.Read (readMaybe)

-- A sequence of ticks: its name and the slots the input is ticked to, each from the state of the one before.
data TickPath = TickPath Text [SlotNo]

hex :: BS.ByteString -> Text
hex = T.decodeUtf8 . Hex.encode

poolId :: KeyHash StakePool -> Text
poolId = hex . hashToBytes . unKeyHash

vrfHex :: VRFVerKeyHash StakePoolVRF -> Text
vrfHex = hex . hashToBytes . unVRFVerKeyHash

pStateL :: Lens' (NewEpochState DijkstraEra) (PState DijkstraEra)
pStateL = nesEsL . esLStateL . lsCertStateL . certPStateL

-- A change made to the input's pool state before the ticks, with the ledger's own lenses and functions
-- (cardano-ledger 9df9f98c `libs/cardano-ledger-core/src/Cardano/Ledger/State/CertState.hs:490-538`,
-- `State/StakePool.hs:187-188, 325-343, 573-574`). No ledger rule makes the derived state: the Dijkstra POOL rule
-- refuses a registration whose VRF key hash another pool holds
-- (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/Pool.hs:252-254`), so a shared hash is not one a Dijkstra
-- registration makes; `populateVRFKeyHashes`, which the translation into Dijkstra runs
-- (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Translation.hs:116`), counts one if the state holds it.
data Change
  = -- The pool's active VRF key hash set to another (`psStakePoolsL`, `spsVrfL`).
    ShareVrf (KeyHash StakePool) (VRFVerKeyHash StakePoolVRF)
  | -- The pool's future parameters: its own, `stakePoolStateToStakePoolParams` of its state with the network of the
    -- Shelley genesis, and another VRF key hash (`psFutureStakePoolParamsL`, `sppVrfL`).
    Reregister (KeyHash StakePool) (VRFVerKeyHash StakePoolVRF)
  | -- The VRF key hash counts recomputed from the pools (`populateVRFKeyHashes`).
    Populate
  | -- The pool's retirement at an epoch (`psRetiringL`).
    Retire (KeyHash StakePool) EpochNo

change :: Network -> PState DijkstraEra -> Change -> PState DijkstraEra
change _ ps (ShareVrf q vrf) = ps & psStakePoolsL %~ Map.adjust (spsVrfL .~ vrf) q
change network ps (Reregister p vrf) =
  ps
    & psFutureStakePoolParamsL
      %~ Map.alter (const (fmap (\sps -> stakePoolStateToStakePoolParams network p sps & sppVrfL .~ vrf) own)) p
  where
    own = Map.lookup p (ps ^. psStakePoolsL)
change _ ps Populate = populateVRFKeyHashes ps
change _ ps (Retire p e) = ps & psRetiringL %~ Map.insert p e

describe :: Network -> Change -> Value
describe _ (ShareVrf q vrf) = object ["change" .= ("setActiveVrf" :: Text), "pool" .= poolId q, "vrf" .= vrfHex vrf]
describe network (Reregister p vrf) =
  object
    [ "change" .= ("setFutureParams" :: Text)
    , "pool" .= poolId p
    , "network" .= show network
    , "vrf" .= vrfHex vrf
    ]
describe _ Populate = object ["change" .= ("populateVRFKeyHashes" :: Text)]
describe _ (Retire p e) = object ["change" .= ("retire" :: Text), "pool" .= poolId p, "epoch" .= unEpochNo e]

-- The VRF key hash every derived state is given as its future one: 32 bytes of 0xee, registered to no pool.
newVrfBytes :: BS.ByteString
newVrfBytes = BS.replicate 32 0xee

-- The changes of a derivation, the pools they name and the VRF key hashes whose counts the ticks report, by role.
-- P, Q and R are the registered pools in key order.
derivation ::
  Text ->
  EpochNo ->
  VRFVerKeyHash StakePoolVRF ->
  PState DijkstraEra ->
  Maybe ([Change], [(Text, KeyHash StakePool)], [(Text, VRFVerKeyHash StakePoolVRF)])
derivation name epoch new ps = case (name, Map.toAscList (ps ^. psStakePoolsL)) of
  ("retire", (p, sp) : _) -> Just ([Retire p next], [("P", p)], [("P", spsVrf sp)])
  ("reregister-new-vrf", (p, sp) : (q, sq) : _) ->
    Just (reregister p sp q, [("P", p), ("Q", q)], shared sp sq)
  ("both", (p, sp) : (q, sq) : (r, sr) : _) ->
    Just (reregister p sp q ++ [Retire r next], [("P", p), ("Q", q), ("R", r)], shared sp sq ++ [("R", spsVrf sr)])
  ("both-sharer", (p, sp) : (q, sq) : _) ->
    Just (reregister p sp q ++ [Retire q next], [("P", p), ("Q", q)], shared sp sq)
  _ -> Nothing
  where
    next = succ epoch
    reregister p sp q = [ShareVrf q (spsVrf sp), Reregister p new, Populate]
    shared sp sq = [("P", spsVrf sp), ("new", new), ("Q", spsVrf sq)]

vrfCounts :: [(Text, VRFVerKeyHash StakePoolVRF)] -> NewEpochState DijkstraEra -> Value
vrfCounts watched nes =
  object
    [ Key.fromText role .= object ["vrf" .= vrfHex vrf, "count" .= maybe 0 unNonZero (Map.lookup vrf counts)]
    | (role, vrf) <- watched
    ]
  where
    counts = nes ^. pStateL . psVRFKeyHashesL

-- The globals of a chain whose eras all have the Shelley genesis's epoch size, which is what the hard-fork
-- combinator's epoch information answers for the Leios devnet and testnet (both configurations start Shelley at
-- epoch 0, so Byron has no epoch, and every Shelley-based era takes the Shelley genesis's epoch length), built as
-- consensus builds them (ouroboros-consensus 2416493b `Shelley/Ledger/Ledger.hs:226-239`, `mkShelleyLedgerConfig`):
-- `mkShelleyGlobals` of the Shelley genesis, whose `maxKESEvolutions` and `slotsPerKESPeriod` Dijkstra's SNAP
-- turns into the Leios committee's key age.
globalsOf :: ShelleyGenesis -> Globals
globalsOf sg =
  mkShelleyGlobals
    sg
    (fixedEpochInfo (sgEpochLength sg) (mkSlotLength (fromNominalDiffTimeMicro (sgSlotLength sg))))

encoded :: NewEpochState DijkstraEra -> BS.ByteString
encoded = Plain.serialize'

rewardUpdate :: StrictMaybe PulsingRewUpdate -> Text
rewardUpdate SNothing = "absent"
rewardUpdate (SJust (Pulsing _ _)) = "pulsing"
rewardUpdate (SJust (Complete _)) = "complete"

seats :: LeiosCommittee -> [Pair]
seats committee =
  [ "seats" .= length (leiosCommitteeSeats committee)
  , "keyedSeats" .= length (filter keyed (foldr (:) [] (leiosCommitteeSeats committee)))
  ]
  where
    keyed seat = case seatVKey seat of
      SJust _ -> True
      SNothing -> False

-- What a state holds that the epoch boundary changes, read with the ledger's own accessors.
facts :: NewEpochState DijkstraEra -> Value
facts nes =
  object
    [ "epoch" .= unEpochNo (nesEL nes)
    , "rewardUpdate" .= rewardUpdate (nesRu nes)
    , "treasury" .= unCoin (casTreasury accounts)
    , "reserves" .= unCoin (casReserves accounts)
    , "pools" .= Map.size (pState ^. psStakePoolsL)
    , "poolsRetiring" .= Map.size (pState ^. psRetiringL)
    , "futurePools" .= Map.size (pState ^. psFutureStakePoolParamsL)
    , "vrfKeyHashes" .= Map.size (pState ^. psVRFKeyHashesL)
    , "deposited" .= unCoin (nes ^. nesEsL . esLStateL . lsUTxOStateL . utxosDepositedL)
    , "fees" .= unCoin (ssFee snapshots)
    , "mark"
        .= object
          ( [ "epoch" .= unEpochNo (msEpochNo mark)
            , "committeeSize" .= msLeiosCommitteeSize mark
            , "maxKeyAge" .= unEpochInterval (msLeiosMaxKeyAge mark)
            ]
              ++ seats (msLeiosCommittee mark)
          )
    , "set" .= object (seats (ssLeiosCommittee (ssStakeSet snapshots)))
    ]
  where
    accounts = nes ^. chainAccountStateL
    pState = nes ^. pStateL
    snapshots = nes ^. nesEsL . esSnapshotsL
    mark = ssStakeMark snapshots

-- One tick of a path: the state ticked from, the slot and the state reached. The pools retired are those
-- registered before the tick and not after; the reward update is applied when the tick enters a new epoch
-- (Conway's NEWEPOCH, which Dijkstra's is, cardano-ledger 9df9f98c
-- `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/NewEpoch.hs:153-184`)
-- with one in the state ticked from.
tick ::
  [(Text, VRFVerKeyHash StakePoolVRF)] ->
  NewEpochState DijkstraEra ->
  (SlotNo, NewEpochState DijkstraEra, BS.ByteString) ->
  Value
tick watched before (slot, after, bytes) =
  object
    [ "slot" .= unSlotNo slot
    , "state" .= hex bytes
    , "facts" .= facts after
    , "vrfCounts" .= vrfCounts watched after
    , "crossedBoundary" .= crossed
    , "rewardUpdateApplied" .= (crossed && rewardUpdate (nesRu before) /= "absent")
    , "poolsRetired" .= Map.size (Map.difference (pools before) (pools after))
    ]
  where
    crossed = nesEL after /= nesEL before
    pools nes = nes ^. pStateL . psStakePoolsL

-- The input decoded with `fromCBOR` (Dijkstra's `fromEraCBOR`, cardano-ledger 9df9f98c
-- `Shelley/LedgerState/Types.hs:423-433`), as consensus reads it (ouroboros-consensus 2416493b
-- `Shelley/Ledger/Ledger.hs:918`), and refused unless `toCBOR` gives its bytes back; then each path's slots in
-- turn with Dijkstra's unchanged TICK (`applyTickNoEvents`), as consensus ticks a ledger state
-- (`applyChainTickLedgerResult`, `Shelley/Ledger/Ledger.hs:586-624`): the state as the snapshot holds it, whose
-- UTxO is in the tables and not in the state, and which consensus ticks without them (`:607-609`). Each state
-- reached is written with `toCBOR`, the encoding the snapshot uses. A derivation other than `none` first changes
-- the input's pool state ([[Change]]); the paths then start from the derived state.
run :: ShelleyGenesis -> BS.ByteString -> Text -> [TickPath] -> IO Value
run sg bytes derive paths = do
  input <-
    either (\e -> die ("NewEpochState: " <> show e)) pure $
      (Plain.decodeFull' bytes :: Either DecoderError (NewEpochState DijkstraEra))
  unless (encoded input == bytes) $
    die "the NewEpochState does not encode back to its own bytes"
  let globals = globalsOf sg
      ei = epochInfoPure globals
      epoch = nesEL input
      firstSlot e = runIdentity (epochInfoFirst ei e)
      network = sgNetworkId sg
  new <- maybe (die "no VRF key hash of 32 bytes") (pure . VRFVerKeyHash) (hashFromBytes newVrfBytes)
  (start, watched, derived) <-
    if derive == "none"
      then pure (input, [], Null)
      else do
        (changes, roles, vrfs) <-
          maybe (die ("no derivation " <> T.unpack derive <> " of this state")) pure $
            derivation derive epoch new (input ^. pStateL)
        unless (Map.notMember new (input ^. pStateL . psVRFKeyHashesL)) $
          die "the new VRF key hash is registered"
        let state = input & pStateL %~ (\ps -> foldl' (change network) ps changes)
        stateBytes <- evaluate (encoded state)
        pure
          ( state
          , vrfs
          , object
              [ "name" .= derive
              , "pools" .= object [Key.fromText role .= poolId pool | (role, pool) <- roles]
              , "changes" .= map (describe network) changes
              , "state" .= hex stateBytes
              , "facts" .= facts state
              , "vrfCounts" .= vrfCounts vrfs state
              ]
          )
  outcome <- try $ traverse (path start watched globals) paths
  ticked <- either (\(e :: SomeException) -> die ("TICK: " <> displayException e)) pure outcome
  pure $
    object
      [ "version" .= getVersion64 (eraProtVerLow @DijkstraEra)
      , "roundTrip" .= True
      , "globals"
          .= object
            [ "epochSize" .= unEpochSize (runIdentity (epochInfoSize ei epoch))
            , "securityParameter" .= securityParameter globals
            , "stabilityWindow" .= stabilityWindow globals
            , "randomnessStabilisationWindow" .= randomnessStabilisationWindow globals
            , "maxKESEvo" .= maxKESEvo globals
            , "slotsPerKESPeriod" .= slotsPerKESPeriod globals
            , "maxKeyAge" .= unEpochInterval (maxKeyAgeEpochs globals epoch)
            , "epochFirstSlot" .= unSlotNo (firstSlot epoch)
            , "nextEpochFirstSlot" .= unSlotNo (firstSlot (succ epoch))
            , "epochAfterFirstSlot" .= unSlotNo (firstSlot (succ (succ epoch)))
            ]
      , "input" .= facts input
      , "derivation" .= derived
      , "paths" .= ticked
      ]
  where
    path input watched globals (TickPath name slots) = do
      let states = drop 1 (scanl' (applyTickNoEvents globals) input slots)
      written <- traverse (evaluate . encoded) states
      let reached = zip3 slots states written
          befores = input : states
      pure $ object ["name" .= name, "ticks" .= zipWith (tick watched) befores reached]

-- A path argument: `name=slot,slot,...`.
tickPath :: String -> Maybe TickPath
tickPath arg = case break (== '=') arg of
  (name, '=' : slots) | not (null name) -> TickPath (T.pack name) . map SlotNo <$> traverse readMaybe (split slots)
  _ -> Nothing
  where
    split s = case break (== ',') s of
      (a, ',' : rest) -> a : split rest
      (a, _) -> [a]

main :: IO ()
main = do
  args <- getArgs
  (genesis, file, derive, paths) <- case args of
    g : f : d : ps@(_ : _) -> maybe (die "a path is name=slot,slot,...") (pure . (g,f,T.pack d,)) (traverse tickPath ps)
    _ ->
      die
        "arguments: the Shelley genesis file, the NewEpochState's file, a derivation (none, retire, \
        \reregister-new-vrf, both or both-sharer), and one or more name=slot,slot,..."
  sg <- either (die . ("Shelley genesis: " <>)) pure =<< eitherDecodeFileStrict genesis
  bytes <- BS.readFile file
  result <- run sg bytes derive paths
  LBS.putStr (encode result)
