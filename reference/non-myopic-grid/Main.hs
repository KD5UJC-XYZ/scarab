-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds, TypeApplications, GADTs, OverloadedStrings, RecordWildCards #-}
module Main where

import Cardano.Crypto.Hash.Class (hashFromBytes, hashToBytes)
import Cardano.Ledger.Address (AccountId(..))
import Cardano.Ledger.BaseTypes
import Cardano.Ledger.Binary (natVersion)
import Cardano.Ledger.Coin
import Cardano.Ledger.Compactible (fromCompact)
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Conway.State (registerConwayAccount)
import Cardano.Ledger.Core hiding (Value)
import Cardano.Ledger.Credential (Credential(..))
import Cardano.Ledger.Hashes (ScriptHash(..))
import Cardano.Ledger.Keys (KeyHash(..), KeyRole(..))
import Cardano.Ledger.Rewards (Reward(..), RewardType(..))
import Cardano.Ledger.Shelley.LedgerState
import Cardano.Ledger.Shelley.RewardUpdate
import Cardano.Ledger.Shelley.Rewards (PoolRewardInfo(..), StakeShare(..), leaderRewardToGeneral)
import Cardano.Ledger.Shelley.Rules (RUPD, RupdEnv(..))
import Cardano.Ledger.State
import Cardano.Slotting.EpochInfo (fixedEpochInfo)
import Cardano.Slotting.Slot (EpochSize(..), SlotNo(..))
import Cardano.Slotting.Time (SystemStart(..), mkSlotLength)
import Control.Monad (foldM, unless)
import Control.Monad.Trans.Reader (runReader)
import Control.State.Transition.Extended (applySTS, TRC(..))
import Data.Aeson
import Data.Aeson.Types (Parser)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BC
import qualified Data.ByteString.Lazy.Char8 as BL
import Data.Default (def)
import Data.List (sortOn)
import qualified Data.Map.Strict as Map
import Data.Maybe (fromJust)
import Data.Ratio (numerator, denominator, (%))
import qualified Data.Set as Set
import Data.Time (UTCTime(..), fromGregorian)
import qualified Data.VMap as VM
import Lens.Micro
import Numeric (showHex)
import System.Environment (getArgs)
import System.IO (withBinaryFile, IOMode(ReadMode))

-- Exact canonical input bytes are embedded to make this a finite reference packet.
canonicalInput :: BS.ByteString
canonicalInput = BC.pack "{\"cases\":[{\"actions\":[{\"label\":\"start110\",\"op\":\"start\",\"slot\":\"110\"},{\"label\":\"pulse111\",\"op\":\"pulse\",\"slot\":\"111\"},{\"label\":\"pulse112\",\"op\":\"pulse\",\"slot\":\"112\"},{\"label\":\"pulse113\",\"op\":\"pulse\",\"slot\":\"113\"},{\"label\":\"pulse114\",\"op\":\"pulse\",\"slot\":\"114\"},{\"label\":\"pulse115\",\"op\":\"pulse\",\"slot\":\"115\"}],\"applyRegistration\":false,\"blocksA\":\"1\",\"blocksB\":\"1\",\"empty\":false,\"fees\":\"1000\",\"id\":\"base\",\"omitScriptAtFreeze\":false,\"window\":\"100\"},{\"actions\":[{\"label\":\"start110\",\"op\":\"start\",\"slot\":\"110\"},{\"label\":\"pulse111\",\"op\":\"pulse\",\"slot\":\"111\"}],\"applyRegistration\":false,\"blocksA\":\"0\",\"blocksB\":\"0\",\"empty\":true,\"fees\":\"1000\",\"id\":\"empty\",\"omitScriptAtFreeze\":false,\"window\":\"100\"},{\"actions\":[{\"label\":\"start110\",\"op\":\"start\",\"slot\":\"110\"},{\"label\":\"pulse111\",\"op\":\"pulse\",\"slot\":\"111\"},{\"label\":\"force201\",\"op\":\"force\",\"slot\":\"201\"}],\"applyRegistration\":false,\"blocksA\":\"1\",\"blocksB\":\"1\",\"empty\":false,\"fees\":\"1000\",\"id\":\"partial-force\",\"omitScriptAtFreeze\":false,\"window\":\"100\"},{\"actions\":[{\"label\":\"start110\",\"op\":\"start\",\"slot\":\"110\"},{\"label\":\"force201\",\"op\":\"force\",\"slot\":\"201\"}],\"applyRegistration\":false,\"blocksA\":\"1\",\"blocksB\":\"1\",\"empty\":false,\"fees\":\"1000\",\"id\":\"initial-force\",\"omitScriptAtFreeze\":false,\"window\":\"100\"},{\"actions\":[{\"label\":\"rupdFresh201\",\"op\":\"rupdFresh\",\"slot\":\"201\"}],\"applyRegistration\":false,\"blocksA\":\"1\",\"blocksB\":\"1\",\"empty\":false,\"fees\":\"1000\",\"id\":\"late-start\",\"omitScriptAtFreeze\":false,\"window\":\"100\"},{\"actions\":[{\"label\":\"rupdFresh99\",\"op\":\"rupdFresh\",\"slot\":\"99\"},{\"label\":\"rupdFresh100\",\"op\":\"rupdFresh\",\"slot\":\"100\"},{\"label\":\"rupdFresh101\",\"op\":\"rupdFresh\",\"slot\":\"101\"},{\"label\":\"rupdFresh200\",\"op\":\"rupdFresh\",\"slot\":\"200\"},{\"label\":\"rupdFresh201\",\"op\":\"rupdFresh\",\"slot\":\"201\"}],\"applyRegistration\":false,\"blocksA\":\"1\",\"blocksB\":\"1\",\"empty\":false,\"fees\":\"1000\",\"id\":\"timing-synthetic100\",\"omitScriptAtFreeze\":false,\"window\":\"100\"},{\"actions\":[{\"label\":\"rupdFresh79\",\"op\":\"rupdFresh\",\"slot\":\"79\"},{\"label\":\"rupdFresh80\",\"op\":\"rupdFresh\",\"slot\":\"80\"},{\"label\":\"rupdFresh81\",\"op\":\"rupdFresh\",\"slot\":\"81\"},{\"label\":\"rupdFresh160\",\"op\":\"rupdFresh\",\"slot\":\"160\"},{\"label\":\"rupdFresh161\",\"op\":\"rupdFresh\",\"slot\":\"161\"}],\"applyRegistration\":false,\"blocksA\":\"1\",\"blocksB\":\"1\",\"empty\":false,\"fees\":\"1000\",\"id\":\"timing-derived80\",\"omitScriptAtFreeze\":false,\"window\":\"80\"},{\"actions\":[{\"label\":\"start110\",\"op\":\"start\",\"slot\":\"110\"},{\"label\":\"force201\",\"op\":\"force\",\"slot\":\"201\"}],\"applyRegistration\":false,\"blocksA\":\"1\",\"blocksB\":\"1\",\"empty\":false,\"fees\":\"100\",\"id\":\"tiny100\",\"omitScriptAtFreeze\":false,\"window\":\"100\"},{\"actions\":[{\"label\":\"start110\",\"op\":\"start\",\"slot\":\"110\"},{\"label\":\"force201\",\"op\":\"force\",\"slot\":\"201\"}],\"applyRegistration\":false,\"blocksA\":\"1\",\"blocksB\":\"1\",\"empty\":false,\"fees\":\"10\",\"id\":\"tiny10\",\"omitScriptAtFreeze\":false,\"window\":\"100\"},{\"actions\":[{\"label\":\"start110\",\"op\":\"start\",\"slot\":\"110\"},{\"label\":\"force201\",\"op\":\"force\",\"slot\":\"201\"}],\"applyRegistration\":false,\"blocksA\":\"3\",\"blocksB\":\"1\",\"empty\":false,\"fees\":\"1000\",\"id\":\"uncapped\",\"omitScriptAtFreeze\":false,\"window\":\"100\"},{\"actions\":[{\"label\":\"start110\",\"op\":\"start\",\"slot\":\"110\"},{\"label\":\"pulse111\",\"op\":\"pulse\",\"slot\":\"111\"},{\"label\":\"force201\",\"op\":\"force\",\"slot\":\"201\"}],\"applyRegistration\":true,\"blocksA\":\"1\",\"blocksB\":\"1\",\"empty\":false,\"fees\":\"1000\",\"id\":\"registration\",\"omitScriptAtFreeze\":true,\"window\":\"100\"}],\"profile\":\"five-credentials-two-pools-v1\",\"schema\":\"synthetic-reward-cases-v1\"}\n"

data Action = Action { label :: String, op :: String, slot :: Integer }
instance FromJSON Action where
  parseJSON = withObject "action" $ \o -> Action <$> o .: "label" <*> o .: "op" <*> (read <$> o .: "slot")
data Case = Case { cid :: String, fees :: Integer, blocksA :: Integer, blocksB :: Integer,
  empty :: Bool, omitScriptAtFreeze :: Bool, window :: Integer, applyRegistration :: Bool, actions :: [Action] }
instance FromJSON Case where
  parseJSON = withObject "case" $ \o -> Case <$> o .: "id" <*> (read <$> o .: "fees") <*> (read <$> o .: "blocksA")
    <*> (read <$> o .: "blocksB") <*> o .: "empty" <*> o .: "omitScriptAtFreeze" <*> (read <$> o .: "window")
    <*> o .: "applyRegistration" <*> o .: "actions"

key :: Int -> KeyHash r
key n = KeyHash $ fromJust $ hashFromBytes $ BS.replicate 28 (fromIntegral n)
cred :: Int -> Credential Staking
cred = KeyHashObj . key
script :: Credential Staking
script = ScriptHashObj $ ScriptHash $ fromJust $ hashFromBytes $ BS.replicate 28 3
hex :: BS.ByteString -> String
hex = concatMap (\b -> let s = showHex b "" in if length s == 1 then '0':s else s) . BS.unpack
keyText (KeyHash h) = hex (hashToBytes h)
credText (KeyHashObj h) = "key:" ++ keyText h
credText (ScriptHashObj (ScriptHash h)) = "script:" ++ hex (hashToBytes h)
dec :: Show a => a -> String
dec = show
coinText (Coin n) = dec n
deltaText (DeltaCoin n) = dec n
ratioJSON r = object ["n" .= dec (numerator r), "d" .= dec (denominator r)]
unit r = fromJust (boundRational r)
cc n = compactCoinOrError (Coin n)
nz n = cc n `nonZeroOr` error "nonzero stake required"

activeRows :: [(Credential Staking, Integer, Int)]
activeRows = [(script,40,1),(cred 3,20,1),(cred 4,40,1),(cred 5,25,2),(cred 6,75,2)]

snapshot :: Case -> SnapShot
snapshot c | empty c = emptySnapShot
snapshot _ = mkSnapShot active $ VM.fromList [(key i, mkStakePoolSnapShot active (Coin 200 `nonZeroOr` error "total") (pool i)) | i <- [1,2]]
  where
    active = ActiveStake $ VM.fromList [(who,StakeWithDelegation (nz amount) (key p)) | (who,amount,p) <- activeRows]
    pool i = (def :: StakePoolState)
      { spsCost = Coin (if i == 1 then 7 else 0)
      , spsMargin = unit (if i == 1 then 1%3 else 0)
      , spsAccountId = AccountId (cred 7)
      , spsOwners = Set.fromList (map key (if i == 1 then [3,6] else [5]))
      , spsDelegators = Set.fromList [who | (who,_,p) <- activeRows, p == i]
      }

accounts :: [Credential Staking] -> Accounts ConwayEra
accounts cs = foldr (\c -> registerConwayAccount c (cc 0) Nothing) def cs
esFor :: Case -> EpochState ConwayEra
esFor c = (def :: EpochState ConwayEra)
  & prevPParamsEpochStateL .~ pp
  & curPParamsEpochStateL .~ pp
  & chainAccountStateL . casReservesL .~ Coin 1000
  & esLStateL . lsUTxOStateL . utxosFeesL .~ Coin (fees c)
  & esLStateL . lsCertStateL . certDStateL . accountsL .~ accounts registered
  & esSnapshotsL .~ SnapShots go (calculatePoolDistr go) go go (Coin (fees c))
  where
    go = snapshot c
    registered = [who | (who,_,_) <- activeRows, not (omitScriptAtFreeze c && who == script)]
    pp = (def :: PParams ConwayEra) & ppProtocolVersionL .~ ProtVer (natVersion @9) 0
      & ppRhoL .~ unit 0 & ppTauL .~ unit 0 & ppA0L .~ unit 0 & ppNOptL .~ 1

blockMap c = BlocksMade $ Map.fromList [(key i,fromInteger n) | (i,n) <- [(1,blocksA c),(2,blocksB c)], n > 0]
globals :: Case -> Globals
globals c = Globals
  { epochInfo = fixedEpochInfo (EpochSize 500) (mkSlotLength 1)
  , slotsPerKESPeriod = 1000, stabilityWindow = 60
  , randomnessStabilisationWindow = fromInteger (window c)
  , securityParameter = knownNonZeroBounded @1, maxKESEvo = 60, quorum = 1
  , maxLovelaceSupply = 2000, activeSlotCoeff = mkActiveSlotCoeff (unit (1%20))
  , networkId = Testnet, systemStart = SystemStart (UTCTime (fromGregorian 1970 1 1) 0)
  }


rewardTuple :: Credential Staking -> Reward -> (String,String,String,String)
rewardTuple who Reward{..} = (credText who, kind, keyText rewardPool, coinText rewardAmount)
  where kind = case rewardType of MemberReward -> "member"; LeaderReward -> "leader"
rewardJSON (who,kind,pool,amount) = object ["credential" .= who,"kind" .= kind,"pool" .= pool,"amount" .= amount]
rewardRows m = [rewardJSON (rewardTuple who r) | (who,rs') <- Map.toAscList m,
  r <- sortOn (\x -> (case rewardType x of MemberReward -> (0::Int); LeaderReward -> 1, rewardPool x)) (Set.toList rs')]

poolJSON (pid,PoolRewardInfo{..}) = object
  [ "pool" .= keyText pid, "sigma" .= ratioJSON (unStakeShare poolRelativeStake)
  , "poolPot" .= coinText poolPot, "blocks" .= dec poolBlocks
  , "leader" .= rewardJSON (rewardTuple (unAccountId (spssAccountId poolPs)) (leaderRewardToGeneral poolLeaderReward))
  , "snapshot" .= object
    [ "stake" .= coinText (fromCompact (spssStake poolPs))
    , "ownerStake" .= coinText (spssSelfDelegatedOwnersStake poolPs)
    , "owners" .= map keyText (Set.toAscList (spssSelfDelegatedOwners poolPs))
    , "cost" .= coinText (spssCost poolPs), "margin" .= ratioJSON (unboundRational (spssMargin poolPs))
    , "pledge" .= coinText (spssPledge poolPs), "rewardAccount" .= credText (unAccountId (spssAccountId poolPs)) ] ]

initial c = startStep (EpochSize 500) (blockMap c) (esFor c) (Coin 2000) (activeSlotCoeff (globals c)) (knownNonZeroBounded @1)
initialJSON (Pulsing RewardSnapShot{..} (RSLP chunk FreeVars{..} _ _)) = object
  [ "chunk" .= dec chunk
  , "allocation" .= object ["fees" .= coinText rewFees,"deltaR1" .= coinText rewDeltaR1,
      "deltaT1" .= coinText rewDeltaT1,"rewardPot" .= coinText rewR,"circulation" .= coinText fvTotalStake]
  , "pools" .= map poolJSON (VM.toList fvPoolRewardInfo) ]
initialJSON _ = error "startStep must produce Pulsing"

row name state = object ["label" .= name,"phase" .= phase,"remaining" .= remaining,"members" .= members,"complete" .= final]
  where
    (phase,remaining,members,final) = case state of
      SNothing -> ("Absent" :: String,Null,Null,Null)
      SJust (Complete RewardUpdate{..}) -> ("Complete",Null,Null,
        object ["rewards" .= rewardRows rs,"deltaT" .= deltaText deltaT,"deltaR" .= deltaText deltaR,"deltaF" .= deltaText deltaF])
      SJust (Pulsing _ (RSLP _ _ left RewardAns{..})) -> ("Pulsing",
        toJSON [object ["credential" .= credText who,"pool" .= keyText (swdDelegation stake),
          "stake" .= coinText (fromCompact (unNonZero (swdStake stake)))] | (who,stake) <- VM.toList left],
        toJSON (rewardRows (Map.map Set.singleton accumRewardAns)),Null)

-- Timing probes execute the actual native transition independently from SNothing.
timed c old at = runReader (applySTS @(RUPD ConwayEra) (TRC (RupdEnv (blockMap c) (esFor c),old,SlotNo (fromInteger at)))) (globals c)
runAction c old Action{..} = case op of
  "rupdFresh" -> either (Left . show) Right (timed c SNothing slot)
  "start" -> Right (SJust (initial c))
  "pulse" -> case old of SNothing -> Left "pulse requires a state"; SJust x -> Right (SJust (run (pulseStep x)))
  "force" -> case old of SNothing -> Left "force requires a state"; SJust x -> Right (SJust (run (completeStep x)))
  _ -> Left "unknown operation"
  where run action = fst $ runReader action (globals c)

application :: Case -> StrictMaybe PulsingRewUpdate -> Value
application c _ | not (applyRegistration c) = Null
application c (SJust (Complete ru)) = object
  ["registered" .= rewardRows (frRegistered filtered)
  ,"unregistered" .= rewardRows (Map.restrictKeys (rs ru) (frUnregistered filtered))
  ,"credited" .= [balance who a | (who,a) <- rows, a ^. balanceAccountStateL /= cc 0]
  ,"balances" .= [balance who a | (who,a) <- rows]
  ,"totalUnregistered" .= coinText (frTotalUnregistered filtered)
  ,"pots" .= object ["treasury" .= coinText (out ^. chainAccountStateL . casTreasuryL)
    ,"reserves" .= coinText (out ^. chainAccountStateL . casReservesL)
    ,"fees" .= coinText (out ^. esLStateL . lsUTxOStateL . utxosFeesL)]]
  where
    current = esFor c & esLStateL . lsCertStateL . certDStateL . accountsL .~ accounts [cred 3,cred 5,cred 6,cred 7]
    (out,filtered) = applyRUpdFiltered ru current
    rows = Map.toAscList (out ^. esLStateL . lsCertStateL . certDStateL . accountsL . accountsMapL)
    balance who a = object ["credential" .= credText who,"amount" .= coinText (fromCompact (a ^. balanceAccountStateL))]
application _ _ = error "application case must complete"

runCase :: Case -> Either String Value
runCase c = do
  (lastState,rows) <- foldM step (SNothing,[]) (actions c)
  pure $ object ["id" .= cid c,"initial" .= initialJSON (initial c),"steps" .= rows,"application" .= application c lastState]
  where
    step (old,rows) action = do
      next <- runAction c old action
      pure (next, rows ++ [row (label action) next])

main :: IO ()
main = do
  args <- getArgs
  input <- case args of
    [path] -> withBinaryFile path ReadMode (\h -> BS.hGet h 65537)
    _ -> fail "usage: synthetic-reward-diff CASES.json"
  unless (input == canonicalInput) $ fail "exact canonical input packet required"
  value <- either fail pure (eitherDecodeStrict' input :: Either String Value)
  cases <- either fail pure $ parseEitherCases value
  outputs <- either fail pure $ traverse runCase cases
  BL.putStrLn $ encode $ object ["schema" .= ("synthetic-reward-result-v1" :: String),
    "producer" .= ("native" :: String),"inputSha256" .= ("14d229647bcf67312d5979b4632d137c23177ac3dcf20356963b6cf193e97fb9" :: String),"cases" .= outputs]
  where
    parseEitherCases v = case fromJSON v :: Result Input of Error e -> Left e; Success (Input cs) -> Right cs
newtype Input = Input [Case]
instance FromJSON Input where parseJSON = withObject "input" (\o -> Input <$> o .: "cases")
