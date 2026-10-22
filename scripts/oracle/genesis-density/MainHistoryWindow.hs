{-# LANGUAGE DataKinds #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE TypeOperators #-}
-- SPDX-License-Identifier: Apache-2.0
-- Observer of the actual production immutable-ledger summary reconstruction and query.
module Main where
import Data.Aeson
import qualified Data.ByteString.Lazy.Char8 as BL
import Data.SOP.BasicFunctors (K(..))
import Data.SOP.Counting (Exactly(..))
import Data.SOP.Telescope (Telescope(..))
import Ouroboros.Consensus.Block
import Ouroboros.Consensus.HardFork.Combinator.State.Infra (reconstructSummary)
import Ouroboros.Consensus.HardFork.Combinator.State.Types
import Ouroboros.Consensus.HardFork.History
import Cardano.Slotting.Time (mkSlotLength)
import System.Environment

type Eras = '[(),(),()]
params :: EraParams
params = EraParams (EpochSize 100) (mkSlotLength 1) (StandardSafeZone 20) (GenesisWindow 20) NoPerasEnabled
paramsShelley :: EraParams
paramsShelley = EraParams (EpochSize 200) (mkSlotLength 1) (StandardSafeZone 60) (GenesisWindow 60) NoPerasEnabled
shape :: Shape Eras
shape = Shape $ ExactlyCons params $ ExactlyCons paramsShelley $ ExactlyCons paramsShelley ExactlyNil
bound :: Bound
bound = initBound{boundSlot=SlotNo 300,boundEpoch=EpochNo 3}
state :: Bool -> HardForkState (K ()) Eras
state False = HardForkState $ TZ $ Current initBound (K ())
state True = HardForkState $ TS (K $ Past initBound bound) $ TZ $ Current bound (K ())
observe :: Bool -> Integer -> Maybe Integer -> Integer -> Value
observe past tip transition slot = object
 ["past" .= past,"tipSlot" .= tip,"transitionEpoch" .= transition,"slot" .= slot,
  "window" .= either (const (Nothing :: Maybe Integer)) (Just . fromIntegral . unGenesisWindow) result,
  "completeOutcomeRendering" .= show result]
 where
  info = maybe (TransitionUnknown $ if tip < 0 then Origin else NotOrigin $ SlotNo $ fromIntegral tip) (TransitionKnown . EpochNo . fromIntegral) transition
  result = runQuery (qryFromExpr $ slotToGenesisWindow $ SlotNo $ fromIntegral slot) (reconstructSummary shape info (state past))
main :: IO ()
main = do
 [output] <- getArgs
 let scenarios=[(False,-1,Nothing),(False,79,Nothing),(False,80,Nothing),(False,0,Just 2),(False,0,Just 0),(True,300,Nothing),(True,439,Nothing),(True,440,Nothing),(True,300,Just 4),(True,300,Just 3)]
     slots=[0,1,19,20,79,80,99,100,101,199,200,201,299,300,301,399,400,499,500,599,600,699,700,799,800]
 BL.writeFile output $ BL.unlines [encode $ observe p t e s | (p,t,e)<-scenarios,s<-slots]
