-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds, TypeApplications, OverloadedStrings, RecordWildCards #-}
module RegisteredDRepDiagnostic where
import qualified Main as R
import qualified BoundaryMain as B
import qualified GovernanceMain as G
import Cardano.Ledger.BaseTypes
import Cardano.Ledger.Coin
import Cardano.Ledger.Compactible (fromCompact)
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Conway.Governance
import Cardano.Ledger.Conway.Governance.DRepPulser
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
import Control.State.Transition.Extended (applySTS,TRC(..))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BC
import Data.Default (def)
import Data.List (intercalate)
import qualified Data.Map.Strict as Map
import qualified Data.Set as Set
import Lens.Micro
import System.Environment (getArgs)
import System.IO (withBinaryFile,IOMode(ReadMode))

registered = [(3,5,95,Just (DRepKeyHash (R.key 64))),(4,3,20,Just (DRepKeyHash (R.key 64))),
              (5,0,0,Just (DRepKeyHash (R.key 65))),(6,11,7,Just (DRepKeyHash (R.key 66)))]
special = [(3,5,95,Just DRepAlwaysAbstain),(4,3,20,Just DRepAlwaysNoConfidence),
           (5,0,0,Nothing),(6,11,7,Just (DRepKeyHash (R.key 67)))]
specs = [("registered-three",1,registered),("expired-three",10,registered),
         ("special-and-unregistered",10,special),("zero-entry",10,[(3,0,0,Just (DRepKeyHash (R.key 64)))])]
voteText (DRepKeyHash k) = "key-"++R.keyText k
voteText (DRepScriptHash _) = error "unused script target"
voteText DRepAlwaysAbstain = "abstain"
voteText DRepAlwaysNoConfidence = "no-confidence"
runCase (name,epoch,rows) = do
  let initial = G.seed (G.Case "base" 7 False "none" "authorized" 44 44 "NoUpdate" Nothing)
      old=(B.predecessor (B.Case "base" 500 "Absent")){nesEs=initial}
  next <- either (Left . show) Right $ runReader (applySTS @(Conway.NEWEPOCH ConwayEra) (TRC ((),old,EpochNo 1))) B.globals
  original <- case cgsDRepPulsingState (G.gov (nesEs next)) of
    DRPulsing p -> Right p
    _ -> Left "genuine fresh pulser required"
  let accounts = R.accounts [R.cred who | (who,_,_,_)<-rows]
        & accountsMapL %~ (\m -> foldr (\(who,reward,_,v) -> Map.adjust
          ((balanceAccountStateL .~ R.cc reward) . (depositAccountStateL .~ R.cc 2) . (dRepDelegationAccountStateL .~ v)) (R.cred who)) m rows)
      registry = Map.fromList [(KeyHashObj (R.key n),DRepState (EpochNo expiry) SNothing (R.cc 5)
        (Set.fromList [R.cred who | (who,_,_,Just v)<-rows,v==DRepKeyHash (R.key n)])) | (n,expiry)<-[(64,0),(65,1),(66,8)]]
      instant = (def :: InstantStake ConwayEra) & instantStakeCredentialsL .~ Map.fromList [(R.cred who,R.cc stake) | (who,_,stake,_)<-rows,stake>0]
      capture = original { dpAccounts=accounts,dpInstantStake=instant,dpDRepState=registry,
        dpCurrentEpoch=EpochNo epoch,dpEnactState=(dpEnactState original){ensTreasury=Coin 1234} }
      (snap,ratify)=finishDRepPulser (DRPulsing capture)
  unless (rsEnactState ratify == (dpEnactState capture){ensTreasury=Coin 0} &&
    null (rsEnacted ratify) && Set.null (rsExpired ratify) && not (rsDelayed ratify) &&
    psDRepState snap == registry && psPoolDistr snap == Map.map individualTotalPoolStake (unPoolDistr (dpStakePoolDistr capture))) $ Left "native completion preservation"
  pure $ intercalate "|" [name,show epoch,"1234","0",
    intercalate "," [voteText v++":"++R.coinText (fromCompact coin) | (v,coin)<-Map.toAscList (psDRepDistr snap)],
    intercalate "," [R.credText c++":"++G.epochText (drepExpiry d) | (c,d)<-Map.toAscList (psDRepState snap)]]
main = do
  args<-getArgs
  input<-case args of
    [path]->withBinaryFile path ReadMode (\h->BS.hGet h 65)
    _->fail "usage: synthetic-reward-diff cases.txt"
  unless (input==BC.pack "registered-drep-completion-cases-v1\n") $ fail "exact finite input required"
  output<-either fail pure (traverse runCase specs)
  putStrLn "registered-drep-completion-native-v1"
  mapM_ putStrLn output
