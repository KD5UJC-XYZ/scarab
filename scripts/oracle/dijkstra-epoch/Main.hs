-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE LambdaCase #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE PackageImports #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TupleSections #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

import Cardano.Crypto.Hash (hashFromBytes, hashToBytes)
import Cardano.Crypto.Leios (LeiosCommittee (..), LeiosSeat (..))
import Cardano.Ledger.Alonzo.Genesis (AlonzoGenesis)
import Cardano.Ledger.Address (AccountAddress (..), AccountId (..))
import Cardano.Ledger.BaseTypes (
  EpochInterval (..),
  addEpochInterval,
  EpochNo (..),
  Globals (..),
  Network,
  NonNegativeInterval,
  StrictMaybe (..),
  boundRational,
  epochInfoPure,
  unNonZero,
  unboundRational,
 )
import Cardano.Ledger.Allegra (AllegraEra)
import Cardano.Ledger.Alonzo (AlonzoEra)
import Cardano.Ledger.Babbage (BabbageEra)
import Cardano.Ledger.Binary (serialize')
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Conway.Genesis (ConwayGenesis)
import Cardano.Ledger.Compactible (fromCompact)
import Cardano.Ledger.Conway.Governance (
  Constitution,
  GovAction (..),
  GovActionId (..),
  GovActionPurpose (..),
  GovActionIx (..),
  GovActionState (..),
  GovPurposeId (..),
  ProposalProcedure (..),
  PulsingSnapshot (..),
  RatifyState (..),
  Vote (..),
  cgsConstitutionL,
  cgsDRepPulsingStateL,
  cgsFuturePParamsL,
  cgsProposalsL,
  constitutionAnchorL,
  constitutionGuardrailsScriptHashL,
  extractDRepPulsingState,
  finishDRepPulser,
  grCommitteeL,
  grConstitutionL,
  grHardForkL,
  grPParamUpdateL,
  pRootsL,
  prRootL,
  proposalsAddAction,
  proposalsIds,
 )
import Cardano.Ledger.Conway.PParams (ppGovActionDepositL, ppGovActionLifetimeL)
import Cardano.Ledger.Conway.Rules (ConwayEpochEvent (..), ConwayNewEpochEvent (..), updateDormantDRepExpiry)
import Cardano.Ledger.Conway.State (
  ConwayAccountState,
  certVStateL,
  dRepDelegationAccountStateL,
  vsCommitteeStateL,
  vsDRepsL,
 )
import Cardano.Ledger.Credential (Credential (..))
import Cardano.Ledger.DRep (DRep (..))
import Cardano.Ledger.Dijkstra.Genesis (DijkstraGenesis)
import Cardano.Ledger.Dijkstra.PParams (
  ppLeiosCommitteeSizeL,
  ppMaxEndorserBlockExUnitsL,
  ppMaxPledgeLeverageL,
  ppMaxRefScriptSizePerEndorserBlockL,
  ppuMaxEndorserBlockExUnitsL,
  ppuMaxRefScriptSizePerEndorserBlockL,
 )
import Cardano.Ledger.Core (emptyPParamsUpdate)
import Cardano.Ledger.Hashes (extractHash, unsafeMakeSafeHash)
import Cardano.Ledger.Plutus.ExUnits (ExUnits (..), OrdExUnits (..))
import Cardano.Ledger.TxIn (TxId (..))
import Data.Foldable (toList)
import Cardano.Ledger.Genesis (NoGenesis (..))
import Cardano.Ledger.Mary (MaryEra)
import Cardano.Ledger.Shelley (ShelleyEra)
import Cardano.Ledger.Shelley.API.ByronTranslation (translateToShelleyLedgerStateFromUtxo)
import Cardano.Ledger.Shelley.Transition (
  EraTransition (..),
  createInitialState,
  mkShelleyTransitionConfig,
 )
import Cardano.Ledger.Shelley.Translation (toFromByronTranslationContext)
import qualified Cardano.Chain.UTxO as Byron
import Data.Ratio (denominator, numerator)
import Data.Word (Word16, Word32)
import qualified Data.Set as Set
import Numeric.Natural (Natural)
import System.FS.API.Types (MountPoint (..))
import System.FS.IO (ioHasFS)
import Cardano.Ledger.Binary (DecoderError, getVersion64)
import qualified Cardano.Ledger.Binary.Plain as Plain
import Cardano.Ledger.Coin (Coin (..))
import Cardano.Ledger.Core (MaxPledgeLeverage (..), eraProtVerLow, translateEra')
import Cardano.Ledger.Dijkstra (DijkstraEra)
import Cardano.Ledger.Dijkstra.Rules (maxKeyAgeEpochs)
import Cardano.Ledger.Hashes (
  KeyHash (..),
  KeyRole (DRepRole, StakePool, Staking),
  KeyRoleVRF (StakePoolVRF),
  ScriptHash (..),
  VRFVerKeyHash (..),
 )
import Cardano.Ledger.Shelley.API (ApplyTick (..), applyTickNoEvents)
import Cardano.Ledger.Shelley.Rules (ShelleyTickEvent (..))
import Cardano.Ledger.Shelley.Genesis (ShelleyGenesis (..), fromNominalDiffTimeMicro, mkShelleyGlobals)
import Cardano.Ledger.Shelley.LedgerState (
  NewEpochState (..),
  PulsingRewUpdate (..),
  curPParamsEpochStateL,
  esLStateL,
  newEpochStateGovStateL,
  prevPParamsEpochStateL,
  esSnapshotsL,
  lsCertStateL,
  lsUTxOStateL,
  nesEsL,
  utxosDepositedL,
 )
import Cardano.Ledger.State (
  ChainAccountState (..),
  FuturePParams (..),
  MarkSnapShot (..),
  UTxO (..),
  accountsL,
  accountsMapL,
  authorizedHotCommitteeCredentials,
  certDStateL,
  sumBalancesAccounts,
  utxoL,
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
  stakePoolDelegationAccountStateL,
  stakePoolStateToStakePoolParams,
 )
import Cardano.Slotting.EpochInfo (epochInfoFirst, epochInfoSize)
import Cardano.Slotting.EpochInfo.Impl (fixedEpochInfo)
import Cardano.Slotting.Slot (EpochSize (..), SlotNo (..))
import Cardano.Slotting.Time (mkSlotLength)
import Control.Exception (SomeException, displayException, evaluate, try)
import Control.Monad (unless)
import Control.State.Transition (SingEP (EPReturn))
import Data.Aeson (FromJSON, Value (Null), eitherDecodeFileStrict, encode, object, (.=))
import qualified Data.Aeson.Key as Key
import Data.Aeson.Types (Pair)
import qualified Data.ByteString as BS
import qualified "base16-bytestring" Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy as LBS
import Data.Functor.Identity (runIdentity)
import Data.List (scanl', sort)
import Data.Maybe (fromMaybe, listToMaybe)
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
  | -- The current and previous protocol parameters' pledge leverage cap (`ppMaxPledgeLeverageL`, CIP-50).
    PledgeCap NonNegativeInterval
  | -- Definite future protocol parameters (`cgsFuturePParamsL`, `DefinitePParamsUpdate`): the current ones with
    -- another Leios committee size (`ppLeiosCommitteeSizeL`).
    FutureCommitteeSize Word16
  | -- An action added to the proposals as GOV adds one (`proposalsAddAction`,
    -- `eras/conway/impl/src/Cardano/Ledger/Conway/Governance/Proposals.hs:297-336`), its deposit added to the
    -- deposit pot (`utxosDepositedL`), as GOV and LEDGER's deposits leave the pot equal to the obligations, and the
    -- dormant epochs added to the DReps' expiries as the CERTS of a transaction with a proposal adds them
    -- (`updateDormantDRepExpiry`, `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Certs.hs:285-307`), in the
    -- action's epoch.
    Propose (GovActionState DijkstraEra)

change :: Network -> NewEpochState DijkstraEra -> Change -> NewEpochState DijkstraEra
change _ nes (ShareVrf q vrf) = nes & pStateL . psStakePoolsL %~ Map.adjust (spsVrfL .~ vrf) q
change network nes (Reregister p vrf) =
  nes
    & pStateL . psFutureStakePoolParamsL
      %~ Map.alter (const (fmap (\sps -> stakePoolStateToStakePoolParams network p sps & sppVrfL .~ vrf) own)) p
  where
    own = Map.lookup p (nes ^. pStateL . psStakePoolsL)
change _ nes Populate = nes & pStateL %~ populateVRFKeyHashes
change _ nes (Retire p e) = nes & pStateL . psRetiringL %~ Map.insert p e
change _ nes (PledgeCap l) =
  nes
    & nesEsL . curPParamsEpochStateL . ppMaxPledgeLeverageL .~ MaxPledgeLeverage (SJust l)
    & nesEsL . prevPParamsEpochStateL . ppMaxPledgeLeverageL .~ MaxPledgeLeverage (SJust l)
change _ nes (FutureCommitteeSize n) =
  nes
    & newEpochStateGovStateL . cgsFuturePParamsL
      .~ DefinitePParamsUpdate (nes ^. nesEsL . curPParamsEpochStateL & ppLeiosCommitteeSizeL .~ n)
change _ nes (Propose gas) =
  case proposalsAddAction gas (nes ^. newEpochStateGovStateL . cgsProposalsL) of
    Nothing -> error "Propose: the action's parent is not its purpose's root"
    Just ps ->
      nes
        & newEpochStateGovStateL . cgsProposalsL .~ ps
        & nesEsL . esLStateL . lsUTxOStateL . utxosDepositedL %~ (<> pProcDeposit (gasProposalProcedure gas))
        & nesEsL . esLStateL . lsCertStateL . certVStateL %~ updateDormantDRepExpiry (gasProposedIn gas)

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
describe _ (PledgeCap l) =
  object
    [ "change" .= ("setMaxPledgeLeverage" :: Text)
    , "numerator" .= numerator (unboundRational l)
    , "denominator" .= denominator (unboundRational l)
    ]
describe _ (FutureCommitteeSize n) =
  object ["change" .= ("setFutureLeiosCommitteeSize" :: Text), "leiosCommitteeSize" .= n]
describe _ (Propose gas) =
  object
    [ "change" .= ("propose" :: Text)
    , "id" .= govActionIdText (gasId gas)
    , "proposal" .= hex (serialize' (eraProtVerLow @DijkstraEra) (gasProposalProcedure gas))
    , "proposedIn" .= unEpochNo (gasProposedIn gas)
    , "expiresAfter" .= unEpochNo (gasExpiresAfter gas)
    , "committeeVotes" .= Map.size (gasCommitteeVotes gas)
    , "drepVotes" .= Map.size (gasDRepVotes gas)
    , "stakePoolVotes" .= Map.size (gasStakePoolVotes gas)
    ]

govActionIdText :: GovActionId -> Text
govActionIdText (GovActionId (TxId h) (GovActionIx ix)) = hex (originalBytesOfSafeHash h) <> "#" <> T.pack (show ix)
  where
    originalBytesOfSafeHash = hashToBytes . extractHash

-- The pledge leverage cap of the `pledge-cap` derivation: 1, which binds for a pool whose pledge is below its
-- stake (`maxPool'`, `libs/cardano-ledger-core/src/Cardano/Ledger/State/SnapShots.hs:141-163`).
pledgeCap :: Maybe NonNegativeInterval
pledgeCap = boundRational 1

-- The Leios committee size the `committee-size` derivation's future parameters take.
futureCommitteeSize :: Word16
futureCommitteeSize = 2

-- The values the `ratify` derivation's parameter change gives keys 47 and 48 (`ppuMaxEndorserBlockExUnitsL`,
-- `ppuMaxRefScriptSizePerEndorserBlockL`, cardano-ledger 9df9f98c `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/
-- PParams.hs:980-1006, 1473-1479`): the memory and steps of an endorser block's scripts, and its reference scripts'
-- size.
ratifyEndorserLimits :: (Natural, Natural, Word32)
ratifyEndorserLimits = (140000000, 100000000000, 2097152)

-- Who votes on an action a derivation proposes: every authorized hot committee credential
-- (`authorizedHotCommitteeCredentials`), every registered DRep and every registered pool yes, so the committee,
-- the DReps and the pools each accept it if they hold stake and members enough; or no one.
data Voters = AllYes | NoVotes

-- An action a derivation proposes, built with the ledger's own types and lenses as GOV builds one
-- (`mkGovActionState`, `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Gov.hs:401-418`): the action `action`,
-- with the governance action deposit of the current parameters, returned to the account of `returnCredential`,
-- under the constitution's anchor, with the votes of `voters`, proposed in the state's epoch and expiring after
-- `expiresAfter`; the id is of a transaction of 32 bytes of 0xaa, at index `ix`.
actionState ::
  Network ->
  NewEpochState DijkstraEra ->
  Word16 ->
  Credential Staking ->
  Voters ->
  EpochNo ->
  GovAction DijkstraEra ->
  Maybe (GovActionState DijkstraEra)
actionState network nes ix returnCredential voters expiresAfter action = do
  txHash <- hashFromBytes (BS.replicate 32 0xaa)
  let pp = nes ^. nesEsL . curPParamsEpochStateL
      vState = nes ^. nesEsL . esLStateL . lsCertStateL . certVStateL
      constitution = nes ^. newEpochStateGovStateL . cgsConstitutionL
      votes :: Ord k => Set.Set k -> Map.Map k Vote
      votes = case voters of
        AllYes -> Map.fromSet (const VoteYes)
        NoVotes -> const Map.empty
  pure
    GovActionState
      { gasId = GovActionId (TxId (unsafeMakeSafeHash txHash)) (GovActionIx ix)
      , gasCommitteeVotes = votes (authorizedHotCommitteeCredentials (vState ^. vsCommitteeStateL))
      , gasDRepVotes = votes (Map.keysSet (vState ^. vsDRepsL))
      , gasStakePoolVotes = votes (Map.keysSet (nes ^. pStateL . psStakePoolsL))
      , gasProposalProcedure =
          ProposalProcedure
            { pProcDeposit = pp ^. ppGovActionDepositL
            , pProcReturnAddr = AccountAddress network (AccountId returnCredential)
            , pProcGovAction = action
            , pProcAnchor = constitution ^. constitutionAnchorL
            }
      , gasProposedIn = nesEL nes
      , gasExpiresAfter = expiresAfter
      }

-- The last epoch an action proposed in the state's epoch can be voted in: the epoch and the action lifetime of the
-- current parameters.
lifetimeEnd :: NewEpochState DijkstraEra -> EpochNo
lifetimeEnd nes = addEpochInterval (nesEL nes) (nes ^. nesEsL . curPParamsEpochStateL . ppGovActionLifetimeL)

-- The `ratify` derivation's change of keys 47 and 48 (Network group, Security group, `Dijkstra/PParams.hs:233-299`)
-- to `ratifyEndorserLimits`, with the parent `parent` and the constitution's guardrails script.
endorserChange ::
  NewEpochState DijkstraEra -> StrictMaybe (GovPurposeId 'PParamUpdatePurpose) -> GovAction DijkstraEra
endorserChange nes parent = ParameterChange parent update guardrails
  where
    (mem, steps, refScriptSize) = ratifyEndorserLimits
    update =
      emptyPParamsUpdate
        & ppuMaxEndorserBlockExUnitsL .~ SJust (OrdExUnits (ExUnits mem steps))
        & ppuMaxRefScriptSizePerEndorserBlockL .~ SJust refScriptSize
    guardrails = nes ^. newEpochStateGovStateL . cgsConstitutionL . constitutionGuardrailsScriptHashL

-- The root of the parameter changes.
pparamRoot :: NewEpochState DijkstraEra -> StrictMaybe (GovPurposeId 'PParamUpdatePurpose)
pparamRoot nes = nes ^. newEpochStateGovStateL . cgsProposalsL . pRootsL . grPParamUpdateL . prRootL

-- The registered accounts by credential.
accountsOf :: NewEpochState DijkstraEra -> Map.Map (Credential Staking) (ConwayAccountState DijkstraEra)
accountsOf nes = nes ^. nesEsL . esLStateL . lsCertStateL . certDStateL . accountsL . accountsMapL

-- The first registered account in key order.
firstAccount :: NewEpochState DijkstraEra -> Maybe (Credential Staking)
firstAccount nes = fst <$> Map.lookupMin (accountsOf nes)

-- The action of the `ratify` derivation: the change of keys 47 and 48 (`endorserChange`) whose parent is the root
-- of its purpose, returned to the first registered account in key order, and voted yes by everyone
-- (`AllYes`); proposed in the state's epoch and expiring after the action lifetime, the id's index 0.
ratifyAction :: Network -> NewEpochState DijkstraEra -> Maybe (GovActionState DijkstraEra)
ratifyAction network nes = do
  returnCredential <- firstAccount nes
  actionState network nes 0 returnCredential AllYes (lifetimeEnd nes) (endorserChange nes (pparamRoot nes))

-- The account the `deposit-credit` derivation returns its action's deposit to: the first registered account in
-- key order that delegates to a registered pool and to a registered DRep of a credential, with that pool and
-- that DRep, whose entries in the pulser's distributions the derivations report.
data Delegated = Delegated (Credential Staking) (KeyHash StakePool) (Credential DRepRole)

delegatedAccount :: NewEpochState DijkstraEra -> Maybe Delegated
delegatedAccount nes =
  listToMaybe
    [ Delegated cred pool drep
    | (cred, account) <- Map.toAscList (accountsOf nes)
    , Just pool <- [account ^. stakePoolDelegationAccountStateL]
    , Map.member pool (nes ^. pStateL . psStakePoolsL)
    , Just (DRepCredential drep) <- [account ^. dRepDelegationAccountStateL]
    , Map.member drep (nes ^. nesEsL . esLStateL . lsCertStateL . certVStateL . vsDRepsL)
    ]

-- The actions of the `remove-branches` derivation, in the order they are proposed, each returned to the first
-- registered account in key order as `ratify`'s is, the ids' indices 0 to 6: A, `ratify`'s action; B, the same
-- change with the same parent, A's competing sibling; C, the same change whose parent is B; D, the constitution
-- proposed anew (`NewConstitution`) with the root of the constitutions as its parent, expiring after the state's
-- epoch, so the pulser of the next epoch finds it expired (`gasExpiresAfter < reCurrentEpoch`,
-- `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Ratify.hs:336-358`); E, the constitution whose parent is D;
-- F, the change whose parent is A; and G, an information action. No one votes on B to G. The pulser of the first
-- boundary enacts A and expires D, and the second boundary's `proposalsApplyEnactment`
-- (`Governance/Proposals.hs:485-556`) removes D with its descendant E as expired, makes A the root, removes its
-- sibling B with B's descendant C, and keeps F, now the root's child, and G.
removeBranchesActions :: Network -> NewEpochState DijkstraEra -> Maybe [GovActionState DijkstraEra]
removeBranchesActions network nes = do
  first <- firstAccount nes
  let act ix voters expiry = actionState network nes ix first voters expiry
      later = lifetimeEnd nes
      constitution :: Constitution DijkstraEra
      constitution = nes ^. newEpochStateGovStateL . cgsConstitutionL
      constitutionRoot = nes ^. newEpochStateGovStateL . cgsProposalsL . pRootsL . grConstitutionL . prRootL
      purposeOf :: GovActionState DijkstraEra -> StrictMaybe (GovPurposeId p)
      purposeOf = SJust . GovPurposeId . gasId
  a <- act 0 AllYes later (endorserChange nes (pparamRoot nes))
  b <- act 1 NoVotes later (endorserChange nes (pparamRoot nes))
  c <- act 2 NoVotes later (endorserChange nes (purposeOf b))
  d <- act 3 NoVotes (nesEL nes) (NewConstitution constitutionRoot constitution)
  e <- act 4 NoVotes later (NewConstitution (purposeOf d) constitution)
  f <- act 5 NoVotes later (endorserChange nes (purposeOf a))
  g <- act 6 NoVotes later InfoAction
  pure [a, b, c, d, e, f, g]

-- The action of the `deposit-credit` derivation: `ratify`'s, its deposit returned to the delegated account
-- (`delegatedAccount`) instead, so `computeDRepDistr` (`Governance/DRepPulser.hs:200-242`) credits the deposit to
-- both that account's DRep and its pool.
depositCreditAction :: Network -> NewEpochState DijkstraEra -> Delegated -> Maybe (GovActionState DijkstraEra)
depositCreditAction network nes (Delegated cred _ _) =
  actionState network nes 0 cred AllYes (lifetimeEnd nes) (endorserChange nes (pparamRoot nes))

-- What a state of the `ratify` derivation holds of its governance, read with the ledger's own accessors: the ids
-- of the proposals in order, of the actions the pulser's ratification enacts (`extractDRepPulsingState`, which
-- completes a running pulser), the root of the parameter changes, and keys 47 and 48 of the current parameters.
govFacts :: NewEpochState DijkstraEra -> Value
govFacts nes =
  object
    [ "proposals" .= map govActionIdText (toList (proposalsIds props))
    , "enacted" .= map (govActionIdText . gasId) (toList (rsEnacted ratified))
    , "pparamUpdateRoot" .= case props ^. pRootsL . grPParamUpdateL . prRootL of
        SNothing -> Nothing
        SJust (GovPurposeId gai) -> Just (govActionIdText gai)
    , "maxEndorserBlockExUnits" .= [exUnitsMem limits, exUnitsSteps limits]
    , "maxRefScriptSizePerEndorserBlock" .= (pp ^. ppMaxRefScriptSizePerEndorserBlockL)
    ]
  where
    gs = nes ^. newEpochStateGovStateL
    props = gs ^. cgsProposalsL
    ratified = extractDRepPulsingState (gs ^. cgsDRepPulsingStateL)
    pp = nes ^. nesEsL . curPParamsEpochStateL
    limits = unOrdExUnits (pp ^. ppMaxEndorserBlockExUnitsL)

-- What a state of the `remove-branches` and `deposit-credit` derivations holds of its governance, read with the
-- ledger's own accessors: the ids of the proposals in order; those of the actions the pulser's ratification enacts
-- and of those it finds expired (`finishDRepPulser`, which completes a running pulser,
-- `Governance/DRepPulser.hs:388-430`); the root of each purpose; and the entries of the delegated account's DRep and
-- pool in the pulser's DRep distribution and pool distribution (`psDRepDistr`, `psPoolDistr`, `:98-102`), or null.
proposalFacts :: Delegated -> NewEpochState DijkstraEra -> Value
proposalFacts (Delegated _ pool drep) nes =
  object
    [ "proposals" .= map govActionIdText (toList (proposalsIds props))
    , "enacted" .= map (govActionIdText . gasId) (toList (rsEnacted ratified))
    , "expired" .= map govActionIdText (Set.toAscList (rsExpired ratified))
    , "roots"
        .= object
          [ "pparamUpdate" .= rootText (roots ^. grPParamUpdateL . prRootL)
          , "hardFork" .= rootText (roots ^. grHardForkL . prRootL)
          , "committee" .= rootText (roots ^. grCommitteeL . prRootL)
          , "constitution" .= rootText (roots ^. grConstitutionL . prRootL)
          ]
    , "drepStake" .= fmap (unCoin . fromCompact) (Map.lookup (DRepCredential drep) (psDRepDistr snapshot))
    , "poolStake" .= fmap (unCoin . fromCompact) (Map.lookup pool (psPoolDistr snapshot))
    ]
  where
    gs = nes ^. newEpochStateGovStateL
    props = gs ^. cgsProposalsL
    roots = props ^. pRootsL
    (snapshot, ratified) = finishDRepPulser (gs ^. cgsDRepPulsingStateL)

rootText :: StrictMaybe (GovPurposeId p) -> Maybe Text
rootText SNothing = Nothing
rootText (SJust (GovPurposeId gai)) = Just (govActionIdText gai)

credentialJson :: Credential r -> Value
credentialJson (KeyHashObj (KeyHash h)) = object ["keyHash" .= hex (hashToBytes h)]
credentialJson (ScriptHashObj (ScriptHash h)) = object ["scriptHash" .= hex (hashToBytes h)]

delegatedJson :: Delegated -> Value
delegatedJson (Delegated cred pool drep) =
  object ["account" .= credentialJson cred, "pool" .= poolId pool, "drep" .= credentialJson drep]

-- The actions Dijkstra's EPOCH removed at a boundary, as its `GovInfoEvent` reports them
-- (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/Epoch.hs:197-202`): the ids of those enacted, of those removed
-- as competing with an enacted one, and of those removed as expired, each with its descendants, in order; and the
-- unclaimed deposits by id. Null for a tick with no such event, which crosses no boundary.
removals :: [ShelleyTickEvent DijkstraEra] -> Either String Value
removals events =
  case [(e, r, x, u) | TickNewEpochEvent (EpochEvent (GovInfoEvent e r x u)) <- events] of
    [] -> Right Null
    [(e, r, x, u)] ->
      Right $
        object
          [ "enacted" .= ids e
          , "removedDueToEnactment" .= ids r
          , "expired" .= ids x
          , "unclaimed" .= object [Key.fromText (govActionIdText i) .= unCoin c | (i, c) <- Map.toAscList u]
          ]
    _ -> Left "a tick with more than one GovInfoEvent"
  where
    ids = sort . map (govActionIdText . gasId) . Set.toList

-- What the states of a derivation report of their governance: nothing; the `ratify` derivation's facts
-- (`govFacts`); or the facts of the proposals (`proposalFacts`) of `remove-branches` or `deposit-credit`, with the
-- delegated account whose DRep's and pool's entries are watched, and each tick's removals.
data ProposalCase = RemoveBranches | DepositCredit

data Report = NoGovernance | Ratification | ProposalReport ProposalCase Delegated

governanceValue :: Report -> NewEpochState DijkstraEra -> Maybe Value
governanceValue NoGovernance _ = Nothing
governanceValue Ratification nes = Just (govFacts nes)
governanceValue (ProposalReport _ d) nes = Just (proposalFacts d nes)

governanceOf :: Report -> NewEpochState DijkstraEra -> [Pair]
governanceOf report nes = ["governance" .= v | Just v <- [governanceValue report nes]]

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
derivation "pledge-cap" _ _ _ = (\l -> ([PledgeCap l], [], [])) <$> pledgeCap
derivation "committee-size" _ _ _ = Just ([FutureCommitteeSize futureCommitteeSize], [], [])
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
    , "accountBalances"
        .= unCoin (sumBalancesAccounts (nes ^. nesEsL . esLStateL . lsCertStateL . certDStateL . accountsL))
    , "leiosCommitteeSize" .= (pp ^. ppLeiosCommitteeSizeL)
    , "maxPledgeLeverage" .= case unMaxPledgeLeverage (pp ^. ppMaxPledgeLeverageL) of
        SNothing -> Nothing
        SJust l -> Just [numerator (unboundRational l), denominator (unboundRational l)]
    , "futurePParams" .= futureKind (nes ^. newEpochStateGovStateL . cgsFuturePParamsL)
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
    pp = nes ^. nesEsL . curPParamsEpochStateL
    futureKind :: FuturePParams DijkstraEra -> Text
    futureKind NoPParamsUpdate = "none"
    futureKind (DefinitePParamsUpdate _) = "definite"
    futureKind (PotentialPParamsUpdate _) = "potential"
    snapshots = nes ^. nesEsL . esSnapshotsL
    mark = ssStakeMark snapshots

-- One tick of a path: the state ticked from, the slot and the state reached. The pools retired are those
-- registered before the tick and not after; the reward update is applied when the tick enters a new epoch
-- (Conway's NEWEPOCH, which Dijkstra's is, cardano-ledger 9df9f98c
-- `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/NewEpoch.hs:153-184`)
-- with one in the state ticked from.
tick ::
  Report ->
  [(Text, VRFVerKeyHash StakePoolVRF)] ->
  NewEpochState DijkstraEra ->
  (SlotNo, NewEpochState DijkstraEra, BS.ByteString) ->
  Maybe Value ->
  Value
tick report watched before (slot, after, bytes) removed =
  object $
    [ "slot" .= unSlotNo slot
    , "state" .= hex bytes
    , "facts" .= facts after
    , "vrfCounts" .= vrfCounts watched after
    , "crossedBoundary" .= crossed
    , "rewardUpdateApplied" .= (crossed && rewardUpdate (nesRu before) /= "absent")
    , "poolsRetired" .= Map.size (Map.difference (pools before) (pools after))
    ]
      ++ governanceOf report after
      ++ ["removals" .= r | Just r <- [removed]]
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
-- the input's pool state ([[Change]]); the paths then start from the derived state. The `remove-branches` and
-- `deposit-credit` derivations also report each tick's removals, from the events of the same TICK (`applyTick`
-- with its events returned, whose state `applyTickNoEvents` is, `Shelley/API/Validation.hs:55-76, 186-193`),
-- refused unless that state is the one reached; and `deposit-credit` reports, for comparison, the governance of
-- `ratify`'s derived state, whose action's deposit returns to an account with no delegation, at the first boundary.
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
      delegated c =
        maybe
          (die "no account delegates to a registered pool and a registered DRep of a credential")
          (pure . ProposalReport c)
          (delegatedAccount input)
  -- The derivations that add governance actions; their states also report their governance.
  report <- case derive of
    "ratify" -> pure Ratification
    "remove-branches" -> delegated RemoveBranches
    "deposit-credit" -> delegated DepositCredit
    _ -> pure NoGovernance
  case report of
    ProposalReport DepositCredit (Delegated cred _ _)
      | Just cred == firstAccount input -> die "the delegated account is the first registered account"
    _ -> pure ()
  new <- maybe (die "no VRF key hash of 32 bytes") (pure . VRFVerKeyHash) (hashFromBytes newVrfBytes)
  (start, watched, derived) <-
    if derive == "none"
      then pure (input, [], Null)
      else do
        (changes, roles, vrfs) <-
          maybe (die ("no derivation " <> T.unpack derive <> " of this state")) pure $
            case report of
              Ratification -> proposed . pure <$> ratifyAction network input
              ProposalReport RemoveBranches _ -> proposed <$> removeBranchesActions network input
              ProposalReport DepositCredit d -> proposed . pure <$> depositCreditAction network input d
              NoGovernance -> derivation derive epoch new (input ^. pStateL)
        unless (Map.notMember new (input ^. pStateL . psVRFKeyHashesL)) $
          die "the new VRF key hash is registered"
        let state = foldl' (change network) input changes
        stateBytes <- evaluate (encoded state)
        pure
          ( state
          , vrfs
          , object $
              [ "name" .= derive
              , "pools" .= object [Key.fromText role .= poolId pool | (role, pool) <- roles]
              , "changes" .= map (describe network) changes
              , "state" .= hex stateBytes
              , "facts" .= facts state
              , "vrfCounts" .= vrfCounts vrfs state
              ]
                ++ governanceOf report state
          )
  control <- case report of
    ProposalReport DepositCredit d -> do
      gas <- maybe (die "no ratify action of this state") pure (ratifyAction network input)
      let slot = firstSlot (succ epoch)
          boundary = applyTickNoEvents globals (change network input (Propose gas)) slot
      pure ["control" .= object ["slot" .= unSlotNo slot, "governance" .= proposalFacts d boundary]]
    _ -> pure []
  outcome <- try $ traverse (path report start watched globals) paths
  ticked <- either (\(e :: SomeException) -> die ("TICK: " <> displayException e)) pure outcome
  pure . object $
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
      , "inputGovernance" .= fromMaybe Null (governanceValue report input)
      , "derivation" .= derived
      , "paths" .= ticked
      ]
      ++ (case report of
            ProposalReport _ d -> ["delegated" .= delegatedJson d]
            _ -> [])
      ++ control
  where
    proposed gass = (map Propose gass, [], [])
    path report input watched globals (TickPath name slots) = do
      let states = drop 1 (scanl' (applyTickNoEvents globals) input slots)
      written <- traverse (evaluate . encoded) states
      let reached = zip3 slots states written
          befores = input : states
      removed <- case report of
        ProposalReport _ _ -> traverse (fmap Just . tickRemovals globals) (zip befores reached)
        _ -> pure (map (const Nothing) reached)
      pure $ object ["name" .= name, "ticks" .= zipWith3 (tick report watched) befores reached removed]
    tickRemovals globals (before, (slot, _, reachedBytes)) = do
      let (after, events) = applyTick EPReturn globals before slot
      unless (encoded after == reachedBytes) $ die "TICK with its events reaches another state"
      either die pure (removals events)

-- A path argument: `name=slot,slot,...`.
tickPath :: String -> Maybe TickPath
tickPath arg = case break (== '=') arg of
  (name, '=' : slots) | not (null name) -> TickPath (T.pack name) . map SlotNo <$> traverse readMaybe (split slots)
  _ -> Nothing
  where
    split s = case break (== ',') s of
      (a, ',' : rest) -> a : split rest
      (a, _) -> [a]

-- The initial state of a chain that is in Dijkstra from its genesis, as the node makes it (ouroboros-consensus
-- 2416493b `Ouroboros/Consensus/Cardano/Node.hs:846-892`, `mkInitExtLedgerStateCardano`): the hard-fork combinator
-- translates Byron's state into Shelley (`translateToShelleyLedgerState`, `Cardano/CanHardFork.hs:351-379`) and
-- through every era with that era's `translateEra'` and translation context (`:550-737`), and the era the chain is
-- then in, Dijkstra, gets its `injectIntoTestState` (`Node.hs:879-889`): Conway's injection,
-- `populateVRFKeyHashes` and `seatInitialLeiosCommittee` (cardano-ledger 9df9f98c
-- `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Transition.hs:59-101`). Byron's state is its genesis UTxO, which
-- run.py requires to be empty (no AVVM or non-AVVM balance), so the translation starts from the empty Byron UTxO
-- (`translateToShelleyLedgerStateFromUtxo`, `Shelley/API/ByronTranslation.hs:89-103`). The transition
-- configuration is composed as cardano-ledger-api's `mkLatestTransitionConfig` composes it. For comparison, the
-- injection is also applied to `createInitialState` of that configuration, as the Leios genesis harness does for
-- Conway (`scripts/oracle/leios-genesis`). Each state is written without its UTxO, as the node's ledger snapshot
-- holds it, and the UTxO beside it.
genesisStates :: ShelleyGenesis -> AlonzoGenesis -> ConwayGenesis -> DijkstraGenesis -> IO Value
genesisStates sg ag cg dg = do
  outcome <- try $ do
    translatedBytes <- evaluate (encoded dijkstra)
    fromByron <- injectIntoTestState hasFS tc dijkstra
    direct <- injectIntoTestState hasFS tc =<< evaluate (createInitialState tc)
    fromByronBytes <- evaluate (encoded (fromByron & utxoL .~ UTxO mempty))
    directBytes <- evaluate (encoded (direct & utxoL .~ UTxO mempty))
    pure (translatedBytes, (fromByron, fromByronBytes), (direct, directBytes))
  either (\(e :: SomeException) -> die ("genesis: " <> displayException e)) (pure . result) outcome
  where
    hasFS = ioHasFS (MountPoint "/nonexistent")
    tc :: TransitionConfig DijkstraEra
    tc =
      mkTransitionConfig dg $
        mkTransitionConfig cg $
          mkTransitionConfig (NoGenesis :: NoGenesis BabbageEra) $
            mkTransitionConfig ag $
              mkTransitionConfig (NoGenesis :: NoGenesis MaryEra) $
                mkTransitionConfig (NoGenesis :: NoGenesis AllegraEra) $
                  mkShelleyTransitionConfig sg
    shelley :: NewEpochState ShelleyEra
    shelley = translateToShelleyLedgerStateFromUtxo (toFromByronTranslationContext sg) (EpochNo 0) Byron.empty
    allegra = translateEra' NoGenesis shelley :: NewEpochState AllegraEra
    mary = translateEra' NoGenesis allegra :: NewEpochState MaryEra
    alonzo = translateEra' ag mary :: NewEpochState AlonzoEra
    babbage = translateEra' NoGenesis alonzo :: NewEpochState BabbageEra
    conway = translateEra' cg babbage :: NewEpochState ConwayEra
    dijkstra = translateEra' dg conway :: NewEpochState DijkstraEra
    utxoBytes :: NewEpochState DijkstraEra -> BS.ByteString
    utxoBytes nes = serialize' (eraProtVerLow @DijkstraEra) (nes ^. utxoL)
    one (nes, bytes) =
      object
        [ "state" .= hex bytes
        , "utxo" .= hex (utxoBytes nes)
        , "facts" .= facts nes
        ]
    result (translatedBytes, fromByron, direct) =
      object
        [ "version" .= getVersion64 (eraProtVerLow @DijkstraEra)
        , "translated" .= hex translatedBytes
        , "fromByron" .= one fromByron
        , "createInitialState" .= one direct
        , "equal" .= (snd fromByron == snd direct && utxoBytes (fst fromByron) == utxoBytes (fst direct))
        ]

main :: IO ()
main =
  getArgs >>= \case
    ["genesis", s, a, c, d] -> do
      sg <- readGenesis s
      ag <- readGenesis a
      cg <- readGenesis c
      dg <- readGenesis d
      LBS.putStr . encode =<< genesisStates sg ag cg dg
    args -> ticks args
  where
    readGenesis :: FromJSON g => FilePath -> IO g
    readGenesis f = either (die . ((f <> ": ") <>)) pure =<< eitherDecodeFileStrict f

ticks :: [String] -> IO ()
ticks args = do
  (genesis, file, derive, paths) <- case args of
    g : f : d : ps@(_ : _) -> maybe (die "a path is name=slot,slot,...") (pure . (g,f,T.pack d,)) (traverse tickPath ps)
    _ ->
      die
        "arguments: the Shelley genesis file, the NewEpochState's file, a derivation (none, retire, \
        \reregister-new-vrf, both, both-sharer, pledge-cap, committee-size, ratify, remove-branches or \
        \deposit-credit), and one or more \
        \name=slot,slot,...; or genesis and the Shelley, Alonzo, Conway and Dijkstra genesis files"
  sg <- either (die . ("Shelley genesis: " <>)) pure =<< eitherDecodeFileStrict genesis
  bytes <- BS.readFile file
  result <- run sg bytes derive paths
  LBS.putStr (encode result)
