-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds, TypeApplications, OverloadedStrings #-}
module AuditedRolesMain where

import qualified GovernanceMain as G
import qualified BoundaryMain as B
import qualified Main as R
import Cardano.Ledger.BaseTypes
import Cardano.Ledger.Binary (decodeFull', natVersion, serialize)
import Cardano.Ledger.Coin
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Conway.Core (ppCostModelsL)
import Cardano.Ledger.Conway.Governance
import Cardano.Ledger.Conway.Governance.DRepPulser (finishDRepPulser)
import qualified Cardano.Ledger.Conway.Rules as Conway
import Cardano.Ledger.Core hiding (Value)
import Cardano.Ledger.Shelley.LedgerState
import Cardano.Ledger.State
import Cardano.Slotting.Slot (EpochNo(..))
import Control.Monad (unless)
import Control.Monad.Trans.Reader (runReader)
import Control.State.Transition.Extended (applySTS, TRC(..))
import Data.Aeson
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy.Char8 as BL
import Data.Default (def)
import Lens.Micro
import System.Environment (getArgs)
import System.IO (withBinaryFile,IOMode(ReadMode))

-- No arbitrary input cases, epoch states, updates or external actions are accepted.
data Mode = ExactCurrent | CostsOnly | NonCostRejected deriving (Eq)
cases :: [(String,Mode)]
cases = [("exact-current",ExactCurrent),("previous-costs-only",CostsOnly),("reject-non-cost-change",NonCostRejected)]
encodePP :: PParams ConwayEra -> String
encodePP = R.hex . BL.toStrict . serialize (natVersion @9)
roleProjection :: PParams ConwayEra -> PParams ConwayEra -> EnactState ConwayEra -> Value
roleProjection cur prev en = object ["outerCurrent" .= encodePP cur,"outerPrevious" .= encodePP prev,
  "completedCurrent" .= encodePP (ensCurPParams en),"completedPrevious" .= encodePP (ensPrevPParams en)]
costsOnly :: PParams ConwayEra -> PParams ConwayEra -> Bool
costsOnly a b = (a & ppCostModelsL .~ (b ^. ppCostModelsL)) == b

runCase :: PParams ConwayEra -> PParams ConwayEra -> (String,Mode) -> Either String Value
runCase previous current (name,mode) = do
  let outer = if mode == NonCostRejected then current & ppMinFeeAL %~ (\(Coin n) -> Coin (n+1)) else current
      completedCurrent = if mode == ExactCurrent then outer else previous
      base = G.seed (G.Case name 0 False "none" "authorized" 44 44 "NoUpdate" Nothing)
      gs0 = G.gov base & cgsCurPParamsL .~ outer & cgsPrevPParamsL .~ previous & cgsFuturePParamsL .~ NoPParamsUpdate
      oldEnact = (mkEnactState @ConwayEra gs0) {ensCurPParams=completedCurrent,ensPrevPParams=previous}
      gs = gs0 & cgsDRepPulsingStateL .~ DRComplete def (def {rsEnactState=oldEnact})
      before = base & esLStateL . lsUTxOStateL . utxosGovStateL .~ gs
      admitted = completedCurrent == outer || (completedCurrent == previous && costsOnly previous outer)
  G.checkEmpty before
  if not admitted then
    Right $ object ["id" .= name,"status" .= ("profile-rejected" :: String),
      "reason" .= ("non-cost-parameter-difference" :: String),"nativeSTSExecuted" .= False]
  else do
    unless (mode /= NonCostRejected) $ Left "negative profile case unexpectedly admitted"
    let old = (B.predecessor (B.Case name 500 "Absent")) {nesEs=before}
    next <- either (Left . show) Right $ runReader
      (applySTS @(Conway.NEWEPOCH ConwayEra) (TRC ((),old,EpochNo 1))) B.globals
    let after=nesEs next; afterGov=G.gov after
        (_,rs)=finishDRepPulser (cgsDRepPulsingState afterGov)
        afterEnact=rsEnactState rs
    G.checkEmpty after
    unless (cgsCurPParams afterGov == outer && cgsPrevPParams afterGov == outer &&
      ensCurPParams afterEnact == outer && ensPrevPParams afterEnact == outer) $
      Left "whole parameter selection or normalized completed roles mismatch"
    unless (cgsFuturePParams afterGov == PotentialPParamsUpdate Nothing) $ Left "future parameters mismatch"
    Right $ object ["id" .= name,"status" .= ("accepted" :: String),"nativeSTSExecuted" .= True,
      "before" .= roleProjection outer previous oldEnact,
      "after" .= roleProjection (cgsCurPParams afterGov) (cgsPrevPParams afterGov) afterEnact,
      "afterFuture" .= ("PotentialNone" :: String),"wholeParameterEqualityChecked" .= True]

readParameters :: FilePath -> IO (PParams ConwayEra)
readParameters path = do
  raw <- withBinaryFile path ReadMode (\h -> BS.hGet h 65537)
  unless (BS.length raw > 0 && BS.length raw <= 65536) $ fail "parameter input bounds"
  value <- either (fail . show) pure (decodeFull' (natVersion @9) raw)
  unless (BL.toStrict (serialize (natVersion @9) value) == raw) $ fail "native original roundtrip bytes differ"
  pure value
main :: IO ()
main = do
  args <- getArgs
  directory <- case args of [x] -> pure x; _ -> fail "usage: audited-governance-roles-diff PACKET_DIRECTORY"
  previous <- readParameters (directory ++ "/previous-parameters.cbor")
  current <- readParameters (directory ++ "/current-parameters.cbor")
  unless (previous /= current && costsOnly previous current) $ fail "distinct costs-only audited roles required"
  results <- either fail pure (traverse (runCase previous current) cases)
  BL.putStrLn $ encode $ object ["schema" .= ("audited-governance-roles-result-v1" :: String),
    "producer" .= ("native-newepoch-normalized-governance" :: String),"cases" .= results]
