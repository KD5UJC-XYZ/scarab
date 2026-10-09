-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds, TypeApplications, OverloadedStrings, RecordWildCards, GADTs #-}
module GovernanceMain where

import Cardano.Crypto.Hash.Class (hashFromBytes)
import Cardano.Ledger.Hashes (unsafeMakeSafeHash)
import Data.Maybe (fromJust)
import qualified Main as R
import qualified BoundaryMain as B
import Cardano.Ledger.BaseTypes
import Cardano.Ledger.Binary (natVersion)
import Cardano.Ledger.Coin
import Cardano.Ledger.Compactible (fromCompact)
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Conway.Governance
import Cardano.Ledger.Conway.Governance.DRepPulser (finishDRepPulser, DRepPulser(..))
import qualified Cardano.Ledger.Conway.Rules as Conway
import Cardano.Ledger.Conway.State
import Cardano.Ledger.Core hiding (Value)
import Cardano.Ledger.Credential (Credential(..))
import Cardano.Ledger.DRep
import Cardano.Ledger.Shelley.LedgerState
import Cardano.Ledger.State
import Cardano.Slotting.Slot (EpochNo(..))
import Control.Monad (unless)
import Control.Monad.Trans.Reader (runReader)
import Control.State.Transition.Extended (applySTS, TRC(..))
import Data.Aeson
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BC
import qualified Data.ByteString.Lazy.Char8 as BL
import Data.Default (def)
import Data.Foldable (toList)
import qualified Data.Map.Strict as Map
import Data.Ratio ((%))
import qualified Data.Set as Set
import Lens.Micro hiding (strict)
import System.Environment (getArgs)
import System.IO (withBinaryFile,IOMode(ReadMode))

data Case = Case { cid :: String, dormant :: Integer, registered :: Bool, voteMode :: String,
  committeeMode :: String, previousFee :: Integer, currentFee :: Integer, future :: String,
  rejection :: Maybe String }
instance FromJSON Case where
  parseJSON = withObject "case" $ \o -> Case <$> o .: "id" <*> (read <$> o .: "dormant")
    <*> o .: "registered" <*> o .: "voteMode" <*> o .: "committeeMode"
    <*> (read <$> o .: "previousFee") <*> (read <$> o .: "currentFee") <*> o .: "future" <*> o .: "reject"
newtype Input = Input [Case]
instance FromJSON Input where parseJSON = withObject "input" (\o -> Input <$> o .: "cases")

-- Finite canonical bytes are inserted from the reviewed cases file, never inferred from input.
canonicalInput :: BS.ByteString
canonicalInput = BC.pack "{\"cases\":[{\"committeeMode\":\"authorized\",\"currentFee\":\"44\",\"dormant\":\"0\",\"future\":\"NoUpdate\",\"id\":\"baseline\",\"previousFee\":\"44\",\"registered\":false,\"reject\":null,\"voteMode\":\"none\"},{\"committeeMode\":\"authorized\",\"currentFee\":\"44\",\"dormant\":\"9\",\"future\":\"NoUpdate\",\"id\":\"dormant-only\",\"previousFee\":\"44\",\"registered\":false,\"reject\":null,\"voteMode\":\"none\"},{\"committeeMode\":\"authorized\",\"currentFee\":\"44\",\"dormant\":\"0\",\"future\":\"NoUpdate\",\"id\":\"registry-only\",\"previousFee\":\"44\",\"registered\":true,\"reject\":null,\"voteMode\":\"none\"},{\"committeeMode\":\"authorized\",\"currentFee\":\"44\",\"dormant\":\"0\",\"future\":\"NoUpdate\",\"id\":\"credential\",\"previousFee\":\"44\",\"registered\":true,\"reject\":null,\"voteMode\":\"credential\"},{\"committeeMode\":\"authorized\",\"currentFee\":\"44\",\"dormant\":\"0\",\"future\":\"NoUpdate\",\"id\":\"abstain\",\"previousFee\":\"44\",\"registered\":false,\"reject\":null,\"voteMode\":\"abstain\"},{\"committeeMode\":\"authorized\",\"currentFee\":\"44\",\"dormant\":\"0\",\"future\":\"NoUpdate\",\"id\":\"no-confidence\",\"previousFee\":\"44\",\"registered\":false,\"reject\":null,\"voteMode\":\"no-confidence\"},{\"committeeMode\":\"resigned\",\"currentFee\":\"44\",\"dormant\":\"0\",\"future\":\"NoUpdate\",\"id\":\"resigned\",\"previousFee\":\"44\",\"registered\":false,\"reject\":null,\"voteMode\":\"none\"},{\"committeeMode\":\"expired\",\"currentFee\":\"44\",\"dormant\":\"0\",\"future\":\"NoUpdate\",\"id\":\"expired\",\"previousFee\":\"44\",\"registered\":false,\"reject\":null,\"voteMode\":\"none\"},{\"committeeMode\":\"orphan\",\"currentFee\":\"44\",\"dormant\":\"0\",\"future\":\"NoUpdate\",\"id\":\"orphan\",\"previousFee\":\"44\",\"registered\":false,\"reject\":null,\"voteMode\":\"none\"},{\"committeeMode\":\"authorized\",\"currentFee\":\"44\",\"dormant\":\"0\",\"future\":\"NoUpdate\",\"id\":\"previous-only\",\"previousFee\":\"45\",\"registered\":false,\"reject\":null,\"voteMode\":\"none\"},{\"committeeMode\":\"authorized\",\"currentFee\":\"44\",\"dormant\":\"0\",\"future\":\"PotentialNone\",\"id\":\"potential-only\",\"previousFee\":\"44\",\"registered\":false,\"reject\":null,\"voteMode\":\"none\"},{\"committeeMode\":\"orphan\",\"currentFee\":\"44\",\"dormant\":\"4\",\"future\":\"PotentialNone\",\"id\":\"combined\",\"previousFee\":\"45\",\"registered\":true,\"reject\":null,\"voteMode\":\"credential\"},{\"committeeMode\":\"authorized\",\"currentFee\":\"44\",\"dormant\":\"0\",\"future\":\"NoUpdate\",\"id\":\"reject-proposals\",\"previousFee\":\"44\",\"registered\":false,\"reject\":\"proposals\",\"voteMode\":\"none\"},{\"committeeMode\":\"authorized\",\"currentFee\":\"44\",\"dormant\":\"0\",\"future\":\"NoUpdate\",\"id\":\"reject-actions\",\"previousFee\":\"44\",\"registered\":false,\"reject\":\"actions\",\"voteMode\":\"none\"},{\"committeeMode\":\"authorized\",\"currentFee\":\"44\",\"dormant\":\"0\",\"future\":\"NoUpdate\",\"id\":\"reject-parameters\",\"previousFee\":\"44\",\"registered\":false,\"reject\":\"parameters\",\"voteMode\":\"none\"}],\"profile\":\"boundary-2200-empty-governance-v1\",\"schema\":\"empty-governance-cases-v1\"}\n"
inputHash :: String
inputHash = "2f94d8b6d04ad338e41cff69961a39c121619305730b533b9626f4d6c2349b64"

vote c = case voteMode c of
  "none" -> Nothing
  "credential" -> Just (DRepKeyHash (R.key 64))
  "abstain" -> Just DRepAlwaysAbstain
  "no-confidence" -> Just DRepAlwaysNoConfidence
  _ -> error "unadmitted vote mode"
epochText (EpochNo n) = R.dec n
strict f SNothing = Null
strict f (SJust a) = f a
voteJSON (DRepKeyHash k) = toJSON ("key:" ++ R.keyText k)
voteJSON (DRepScriptHash h) = toJSON (R.credText (ScriptHashObj h))
voteJSON DRepAlwaysAbstain = toJSON ("always-abstain" :: String)
voteJSON DRepAlwaysNoConfidence = toJSON ("always-no-confidence" :: String)
anchorJSON Anchor{..} = object ["url" .= anchorUrl,"hash" .= anchorDataHash]
constitutionJSON Constitution{..} = object ["url" .= anchorUrl constitutionAnchor,
  "hash" .= anchorDataHash constitutionAnchor,"script" .= strict toJSON constitutionGuardrailsScriptHash]
committeeJSON SNothing = Null
committeeJSON (SJust Committee{..}) = object ["members" .=
  [object ["credential" .= R.credText c,"expiry" .= epochText e] | (c,e) <- Map.toAscList committeeMembers],
  "threshold" .= R.ratioJSON (unboundRational committeeThreshold)]
authorizationsJSON (CommitteeState m) = [case a of
  CommitteeHotCredential hot -> object ["credential" .= R.credText c,"kind" .= ("hot" :: String),"hot" .= R.credText hot,"anchor" .= Null]
  CommitteeMemberResigned a -> object ["credential" .= R.credText c,"kind" .= ("resigned" :: String),"hot" .= Null,"anchor" .= strict anchorJSON a]
  | (c,a) <- Map.toAscList m]
drepsJSON m = [object ["credential" .= R.credText c,"expiry" .= epochText drepExpiry,
  "anchor" .= strict anchorJSON drepAnchor,"deposit" .= R.coinText (fromCompact drepDeposit),
  "delegators" .= map R.credText (Set.toAscList drepDelegs)] | (c,DRepState{..}) <- Map.toAscList m]
pvJSON pp
  | pp ^. ppProtocolVersionL == ProtVer (natVersion @9) 0 = object ["major" .= ("9" :: String),"minor" .= ("0" :: String)]
  | otherwise = error "unadmitted protocol version"
paramsJSON cur prev = ["currentFee" .= R.coinText (cur ^. ppMinFeeAL),
  "previousFee" .= R.coinText (prev ^. ppMinFeeAL),"currentPV" .= pvJSON cur,"previousPV" .= pvJSON prev]
futureText NoPParamsUpdate = "NoUpdate" :: String
futureText (PotentialPParamsUpdate Nothing) = "PotentialNone"
futureText _ = error "pending parameter update outside profile"
rootsJSON = object ["parameters" .= Null,"hardFork" .= Null,"committee" .= Null,"constitution" .= Null]
enactJSON EnactState{..} = object ["committee" .= committeeJSON ensCommittee,
  "constitution" .= constitutionJSON ensConstitution,"parameters" .= object (paramsJSON ensCurPParams ensPrevPParams),
  "treasury" .= R.coinText ensTreasury,"withdrawals" .= ([] :: [Value]),"roots" .= rootsJSON]
completedJSON pulsing = object ["snapshot" .= object ["proposals" .= ([] :: [Value]),
  "drepDistribution" .= [object ["vote" .= voteJSON v,"coin" .= R.coinText (fromCompact n)] | (v,n) <- Map.toAscList (psDRepDistr snap)],
  "dreps" .= drepsJSON (psDRepState snap),
  "pools" .= [object ["pool" .= R.keyText p,"stake" .= R.coinText (fromCompact n)] | (p,n) <- Map.toAscList (psPoolDistr snap)]],
  "ratify" .= object ["enacted" .= ([] :: [Value]),"expired" .= ([] :: [Value]),"delayed" .= rsDelayed rs,"enact" .= enactJSON (rsEnactState rs)]]
  where snap = fst (finishDRepPulser pulsing); rs = extractDRepPulsingState pulsing

seed :: Case -> EpochState ConwayEra
seed c = B.beforeES
  & chainAccountStateL . casTreasuryL .~ Coin 17
  & chainAccountStateL . casReservesL .~ Coin (883 - if registered c then 5 else 0)
  & esLStateL . lsUTxOStateL . utxosDepositedL .~ Coin (if registered c then 5 else 0)
  & esLStateL . lsCertStateL . certDStateL . accountsL . accountsMapL %~ Map.map (dRepDelegationAccountStateL .~ vote c)
  & esLStateL . lsCertStateL . certVStateL . vsDRepsL .~ registry
  & esLStateL . lsCertStateL . certVStateL . vsNumDormantEpochsL .~ EpochNo (fromInteger (dormant c))
  & esLStateL . lsCertStateL . certVStateL . vsCommitteeStateL .~ auth
  & esLStateL . lsUTxOStateL . utxosGovStateL %~ initialize
  where
    registry = if registered c then Map.singleton (KeyHashObj (R.key 64))
      (DRepState (EpochNo 3) SNothing (R.cc 5)
        (if voteMode c == "credential" then Set.fromList [who | (who,_,_) <- B.rows] else Set.empty)) else Map.empty
    members = Map.singleton (KeyHashObj (R.key 62)) (EpochNo (if committeeMode c == "expired" then 0 else 2))
    authorization = if committeeMode c == "resigned" then CommitteeMemberResigned SNothing else CommitteeHotCredential (KeyHashObj (R.key 68))
    auth = CommitteeState $ Map.fromList ([(KeyHashObj (R.key 62),authorization)] ++
      [(KeyHashObj (R.key 65),CommitteeHotCredential (KeyHashObj (R.key 69))) | committeeMode c == "orphan"])
    initialize :: ConwayGovState ConwayEra -> ConwayGovState ConwayEra
    initialize gs = completed
      where
        changed = gs & cgsCommitteeL .~ SJust (Committee members (R.unit (1%2)))
          & cgsConstitutionL .~ ((def :: Constitution ConwayEra) {constitutionAnchor = def {anchorDataHash = unsafeMakeSafeHash (fromJust (hashFromBytes (BS.replicate 32 0)))}})
          & cgsCurPParamsL . ppMinFeeAL .~ Coin (currentFee c)
          & cgsPrevPParamsL . ppMinFeeAL .~ Coin (previousFee c)
          & cgsFuturePParamsL .~ (if future c == "NoUpdate" then NoPParamsUpdate else PotentialPParamsUpdate Nothing)
        completed = changed & cgsDRepPulsingStateL .~ DRComplete def (def {rsEnactState = mkEnactState @ConwayEra changed})

gov es = es ^. esLStateL . lsUTxOStateL . utxosGovStateL
project epoch es = object ["epoch" .= epochText epoch,"dormant" .= epochText (vsNumDormantEpochs vs),
  "dreps" .= drepsJSON (vsDReps vs),"accounts" .=
    [object ["credential" .= R.credText who,"rewards" .= R.coinText (fromCompact (a ^. balanceAccountStateL)),
      "deposit" .= R.coinText (fromCompact (a ^. depositAccountStateL)),
      "pool" .= fmap R.keyText (a ^. stakePoolDelegationAccountStateL),"vote" .= fmap voteJSON (a ^. dRepDelegationAccountStateL)]
      | (who,a) <- Map.toAscList (cs ^. certDStateL . accountsL . accountsMapL)],
  "committee" .= committeeJSON (cgsCommittee gs),"committeeState" .= authorizationsJSON (vsCommitteeState vs),
  "constitution" .= constitutionJSON (cgsConstitution gs),
  "parameters" .= object (paramsJSON (cgsCurPParams gs) (cgsPrevPParams gs) ++ ["future" .= futureText (cgsFuturePParams gs)]),
  "roots" .= rootsJSON,"pots" .= object ["treasury" .= R.coinText (es ^. chainAccountStateL . casTreasuryL),
    "reserves" .= R.coinText (es ^. chainAccountStateL . casReservesL),"fees" .= R.coinText (utxosFees us),
    "deposits" .= R.coinText (utxosDeposited us),"donations" .= R.coinText (us ^. utxosDonationL)],
  "completed" .= completedJSON (cgsDRepPulsingState gs)]
  where gs=gov es; ls=esLState es; cs=lsCertState ls; vs=cs ^. certVStateL; us=lsUTxOState ls

-- Never turn pending input into an apparently empty native case. Negative cases exercise only
-- this finite supplied-profile gate, not a native STS rejection claim.
profile c = case rejection c of
  Just r | r `elem` ["proposals","actions","parameters"] -> Left r
  Just _ -> error "unadmitted rejection mode"
  Nothing -> Right ()
checkEmpty es = do
  let gs=gov es; p=cgsDRepPulsingState gs; (snap,rs)=finishDRepPulser p; en=rsEnactState rs
  unless (null (toList (psProposals snap)) && null (toList (rsEnacted rs)) && Set.null (rsExpired rs)
    && not (rsDelayed rs) && Map.null (ensWithdrawals en) && ensTreasury en == Coin 0
    && ensPrevGovActionIds en == def && govStatePrevGovActionIds gs == def) $ Left "nonempty normalized governance"
runCase c = case profile c of
  Left reason -> Right $ object ["id" .= cid c,"status" .= ("profile-rejected" :: String),"reason" .= reason]
  Right () -> do
    let before=seed c
        old=(B.predecessor (B.Case "empty-governance" 500 "Absent")) {nesEs=before}
    checkEmpty before
    next <- either (Left . show) Right $ runReader
      (applySTS @(Conway.NEWEPOCH ConwayEra) (TRC ((),old,EpochNo 1))) B.globals
    let after=nesEs next; oldGov=gov before; newGov=gov after
    checkEmpty after
    unless (cgsCurPParams newGov == cgsCurPParams oldGov && cgsPrevPParams newGov == cgsCurPParams oldGov) $
      Left "whole protocol parameter object rollover mismatch"
    unless (cgsCommittee newGov == cgsCommittee oldGov && cgsConstitution newGov == cgsConstitution oldGov) $
      Left "committee/constitution unexpectedly changed"
    diagnostic <- case cgsDRepPulsingState newGov of
      DRPulsing DRepPulser{..} -> Right $ object ["freshPulseSize" .= R.dec dpPulseSize,
        "freshSeedTreasury" .= R.coinText (ensTreasury dpEnactState),
        "newMarkPools" .= [object ["pool" .= R.keyText p,"stake" .= R.coinText (fromCompact (individualTotalPoolStake v))]
          | (p,v) <- Map.toAscList (unPoolDistr dpStakePoolDistr)]]
      _ -> Left "expected newly initialized DRep pulser before normalized projection"
    Right $ object ["id" .= cid c,"status" .= ("accepted" :: String),
      "before" .= project (EpochNo 0) before,"after" .= project (EpochNo 1) after,"diagnostic" .= diagnostic]
runCases = traverse runCase
main :: IO ()
main = do
  args <- getArgs
  input <- case args of
    [path] -> withBinaryFile path ReadMode (\h -> BS.hGet h 65537)
    _ -> fail "usage: empty-governance-diff CASES.json"
  unless (input == canonicalInput) $ fail "exact canonical governance input required"
  Input cases <- either fail pure (eitherDecodeStrict' input)
  results <- either fail pure (runCases cases)
  BL.putStrLn $ encode $ object ["schema" .= ("synthetic-empty-governance-result-v1" :: String),
    "inputSha256" .= inputHash,"producer" .= ("native" :: String),"cases" .= results]
