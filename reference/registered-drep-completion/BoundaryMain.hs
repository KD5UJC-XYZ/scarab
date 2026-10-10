-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds, TypeApplications, OverloadedStrings, RecordWildCards #-}
module BoundaryMain where

import qualified Main as R
import Cardano.Ledger.Address (AccountId(..))
import Cardano.Ledger.BaseTypes
import Cardano.Ledger.Coin
import Cardano.Ledger.Compactible (fromCompact)
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Conway.Governance
import qualified Cardano.Ledger.Conway.Rules as Conway
import Cardano.Ledger.Core hiding (Value)
import Cardano.Ledger.Shelley.LedgerState
import qualified Cardano.Ledger.Shelley.Rules as Shelley
import Cardano.Ledger.State
import Cardano.Slotting.Slot (EpochNo(..), EpochSize(..), SlotNo(..))
import Control.Monad (unless)
import Control.Monad.Trans.Reader (runReader)
import Control.State.Transition.Extended (applySTS, TRC(..))
import Data.Aeson
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BC
import qualified Data.ByteString.Lazy.Char8 as BL
import Data.Default (def)
import qualified Data.Map.Strict as Map
import Data.Ratio ((%))
import qualified Data.Set as Set
import qualified Data.VMap as VM
import Lens.Micro
import System.Environment (getArgs)
import System.IO (withBinaryFile,IOMode(ReadMode))

canonicalInput :: BS.ByteString
canonicalInput = BC.pack "{\"cases\":[{\"id\":\"early\",\"oldRewardPhase\":\"Complete\",\"slot\":\"500\"},{\"id\":\"start-edge\",\"oldRewardPhase\":\"Complete\",\"slot\":\"600\"},{\"id\":\"start\",\"oldRewardPhase\":\"Complete\",\"slot\":\"601\"},{\"id\":\"force-edge\",\"oldRewardPhase\":\"Complete\",\"slot\":\"700\"},{\"id\":\"late\",\"oldRewardPhase\":\"Complete\",\"slot\":\"701\"},{\"id\":\"old-pulsing\",\"oldRewardPhase\":\"Pulsing\",\"slot\":\"500\"},{\"id\":\"old-absent\",\"oldRewardPhase\":\"Absent\",\"slot\":\"500\"}],\"profile\":\"reward-balances-only-2200-v1\",\"schema\":\"synthetic-boundary-cases-v1\"}\n"
inputHash :: String
inputHash = "b3dc3877984319078273937888395c51354dd293442005c9830c10ff3d36fc06"

data Case = Case { cid :: String, slot :: Integer, oldPhase :: String }
instance FromJSON Case where
  parseJSON = withObject "case" $ \o -> Case <$> o .: "id" <*> (read <$> o .: "slot") <*> o .: "oldRewardPhase"
newtype Input = Input [Case]
instance FromJSON Input where parseJSON = withObject "input" (\o -> Input <$> o .: "cases")

profile = R.Case "boundary" 1000 1 1 False False 100 False []
globals = (R.globals profile) { maxLovelaceSupply = 2200 }
-- Current money is entirely reserves 900 + fees 1100 + registered reward balances 200.
-- Empty UTxO and empty incremental UTxO stake are therefore consistent.
rows = R.activeRows ++ [(R.cred 7,0,2)]
pool i = (def :: StakePoolState)
  { spsCost = Coin (if i == 1 then 7 else 0)
  , spsMargin = R.unit (if i == 1 then 1%3 else 0)
  , spsAccountId = AccountId (R.cred 7)
  , spsOwners = Set.fromList (map R.key (if i == 1 then [3,6] else [5]))
  , spsDelegators = Set.fromList [who | (who,_,p) <- rows,p==i]
  }
currentAccounts = R.accounts (map (\(who,_,_) -> who) rows)
  & accountsMapL %~ (\m -> foldr (\(who,amount,p) -> Map.adjust
       ((balanceAccountStateL .~ R.cc amount) . (stakePoolDelegationAccountStateL .~ Just (R.key p))) who) m rows)

historical which = mkSnapShot active $ VM.fromList
  [(R.key i,mkStakePoolSnapShot active total (pool i)) | i <- [1,2]]
  where
    entries = [(who,amount + (if which=="mark" && p==1 then 10 else if which=="set" && p==2 then 20 else 0),p)
      | (who,amount,p) <- R.activeRows]
    active = ActiveStake $ VM.fromList [(who,StakeWithDelegation (R.nz amount) (R.key p)) | (who,amount,p) <- entries]
    total = Coin (sum [amount | (_,amount,_) <- entries]) `nonZeroOr` error "positive historical stake"

beforeES :: EpochState ConwayEra
beforeES = seeded & esLStateL . lsUTxOStateL . utxosGovStateL %~ initializeGov
  where
    mark=historical "mark"
    seeded=R.esFor profile
      & chainAccountStateL . casReservesL .~ Coin 900
      & esLStateL . lsUTxOStateL . utxosFeesL .~ Coin 1100
      & esLStateL . lsCertStateL . certDStateL . accountsL .~ currentAccounts
      & esLStateL . lsCertStateL . certPStateL . psStakePoolsL .~ Map.fromList [(R.key i,pool i) | i <- [1,2]]
      & esSnapshotsL .~ SnapShots mark (calculatePoolDistr mark) (historical "set") (historical "go") (Coin 1000)
    initializeGov :: ConwayGovState ConwayEra -> ConwayGovState ConwayEra
    initializeGov gs = gs
      & cgsFuturePParamsL .~ NoPParamsUpdate
      & cgsDRepPulsingStateL .~ DRComplete def (def {rsEnactState=mkEnactState @ConwayEra gs})

startWith fees es = startStep (EpochSize 500) (R.blockMap profile)
  (es & esSnapshotsL %~ (\ss -> ss {ssFee=Coin fees}))
  (Coin 2200) (activeSlotCoeff globals) (knownNonZeroBounded @1)
oldUpdate phase = case phase of
  "Complete" -> SJust $ fst $ runReader (completeStep (startWith 200 beforeES)) globals
  "Pulsing" -> SJust $ fst $ runReader (pulseStep (startWith 200 beforeES)) globals
  "Absent" -> SNothing
  _ -> error "unadmitted phase"
predecessor c = NewEpochState (EpochNo 0) (R.blockMap profile)
  (BlocksMade (Map.fromList [(R.key 1,3),(R.key 2,1)])) beforeES (oldUpdate (oldPhase c))
  (calculatePoolDistr (historical "go")) def

stakeRows active = [object ["credential" .= R.credText who,"pool" .= R.keyText (swdDelegation swd),
  "stake" .= R.coinText (fromCompact (unNonZero (swdStake swd)))] | (who,swd) <- VM.toList (unActiveStake active)]
poolDistrJSON pd = object ["total" .= R.coinText (unNonZero (pdTotalActiveStake pd)),
  "pools" .= [object ["pool" .= R.keyText k,"stake" .= R.coinText (fromCompact (individualTotalPoolStake v)),
    "fraction" .= R.ratioJSON (individualPoolStake v)] | (k,v) <- Map.toAscList (unPoolDistr pd)]]
snapshotJSON ss = object ["active" .= stakeRows (ssActiveStake ss),
  "total" .= R.coinText (unNonZero (ssTotalActiveStake ss)),"distribution" .= poolDistrJSON (calculatePoolDistr ss)]
snapshotsJSON ss = object ["mark" .= snapshotJSON (ssStakeMark ss),"set" .= snapshotJSON (ssStakeSet ss),
  "go" .= snapshotJSON (ssStakeGo ss),"fees" .= R.coinText (ssFee ss)]
countsJSON (BlocksMade m) = [object ["pool" .= R.keyText p,"blocks" .= R.dec n] | (p,n) <- Map.toAscList m]
stateJSON nes = object ["epoch" .= R.dec epoch,"previousCounts" .= countsJSON (nesBprev nes),
  "currentCounts" .= countsJSON (nesBcur nes),"leadership" .= poolDistrJSON (nesPd nes),
  "snapshots" .= snapshotsJSON (esSnapshots es),"accounts" .=
    [object ["credential" .= R.credText who,"balance" .= R.coinText (fromCompact (a ^. balanceAccountStateL)),
      "deposit" .= R.coinText (fromCompact (a ^. depositAccountStateL)),
      "pool" .= fmap R.keyText (a ^. stakePoolDelegationAccountStateL)]
    | (who,a) <- Map.toAscList (es ^. esLStateL . lsCertStateL . certDStateL . accountsL . accountsMapL)],
  "pots" .= object ["treasury" .= R.coinText (es ^. chainAccountStateL . casTreasuryL),
    "reserves" .= R.coinText (es ^. chainAccountStateL . casReservesL),
    "fees" .= R.coinText (es ^. esLStateL . lsUTxOStateL . utxosFeesL),
    "deposits" .= R.coinText (es ^. esLStateL . lsUTxOStateL . utxosDepositedL),
    "donations" .= R.coinText (es ^. esLStateL . lsUTxOStateL . utxosDonationL)],
  "reward" .= R.row ("rupd" :: String) (nesRu nes)]
  where es=nesEs nes; EpochNo epoch=nesEL nes

runNative action = either (Left . show) Right (runReader action globals)
runCase c = do
  let old=predecessor c; at=SlotNo (fromInteger (slot c))
  boundary <- runNative $ applySTS @(Conway.NEWEPOCH ConwayEra) (TRC ((),old,EpochNo 1))
  ticked <- runNative $ applySTS @(Shelley.TICK ConwayEra) (TRC ((),old,at))
  pre <- runNative $ applySTS @(Shelley.RUPD ConwayEra) (TRC (Shelley.RupdEnv (nesBprev old) (nesEs old),nesRu boundary,at))
  post <- runNative $ applySTS @(Shelley.RUPD ConwayEra) (TRC (Shelley.RupdEnv (nesBprev boundary) (nesEs boundary),nesRu boundary,at))
  unless (R.row ("rupd" :: String) pre == R.row ("rupd" :: String) (nesRu ticked)) $ Left "TICK pre-input projection mismatch"
  unless (stateJSON (ticked {nesRu=nesRu boundary}) == stateJSON boundary) $ Left "TICK boundary projection mismatch"
  unless ((nesEs boundary ^. curPParamsEpochStateL)==(beforeES ^. curPParamsEpochStateL) &&
          (nesEs boundary ^. prevPParamsEpochStateL)==(beforeES ^. prevPParamsEpochStateL)) $ Left "unexpected parameter rollover"
  unless (slot c <= 600 || R.row ("rupd" :: String) pre /= R.row ("rupd" :: String) post) $ Left "diagnostic failed to distinguish old/new inputs"
  pure $ object ["id" .= cid c,"slot" .= R.dec (slot c),"before" .= stateJSON old,
    "preTickInitial" .= R.initialJSON (startWith 1000 beforeES),"boundary" .= stateJSON boundary,
    "tick" .= stateJSON ticked,"freshPreTickRupd" .= R.row ("rupd" :: String) pre,
    "postBoundaryDiagnostic" .= R.row ("rupd" :: String) post]

main :: IO ()
main = do
  args <- getArgs
  input <- case args of [path] -> withBinaryFile path ReadMode (\h -> BS.hGet h 65537); _ -> fail "usage: synthetic-reward-diff CASES.json"
  unless (input==canonicalInput) $ fail "exact canonical boundary input required"
  Input cases <- either fail pure (eitherDecodeStrict' input)
  results <- either fail pure (traverse runCase cases)
  BL.putStrLn $ encode $ object ["schema" .= ("synthetic-boundary-result-v1" :: String),
    "inputSha256" .= inputHash,"producer" .= ("native" :: String),"cases" .= results]
