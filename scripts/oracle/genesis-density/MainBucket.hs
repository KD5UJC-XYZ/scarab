{-# LANGUAGE NamedFieldPuns #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE RankNTypes #-}
-- SPDX-License-Identifier: Apache-2.0
module Main where
import Control.Monad (forM_, void)
import Control.Monad.IOSim (runSimOrThrow)
import Data.Aeson
import qualified Data.ByteString.Lazy.Char8 as BL
import Data.Ratio
import Data.Time.Clock (diffTimeToPicoseconds)
import Ouroboros.Consensus.Util.IOLike
import Ouroboros.Consensus.Util.LeakyBucket (State(..),Config(..))
import qualified Ouroboros.Consensus.Util.LeakyBucket as LB
import Test.Util.Orphans.IOLike ()
import System.Environment

data Event = Fill Integer Rational | Pause Integer Bool | Configure Integer Rational Rational Rational Bool | Wait Integer deriving Show
picos :: Event -> Integer
picos (Fill t _) = t
picos (Pause t _) = t
picos (Configure t _ _ _ _) = t
picos (Wait t) = t
ratioJSON :: Rational -> Value
ratioJSON r = object ["numerator" .= numerator r,"denominator" .= denominator r]
eventJSON :: Event -> Value
eventJSON event = case event of
 Fill t amount -> object ["tag" .= ("fill" :: String),"timePicos" .= t,"amount" .= ratioJSON amount]
 Pause t paused -> object ["tag" .= ("pause" :: String),"timePicos" .= t,"paused" .= paused]
 Configure t level capacity rate overflow -> object ["tag" .= ("configure" :: String),"timePicos" .= t,"level" .= ratioJSON level,"capacity" .= ratioJSON capacity,"rate" .= ratioJSON rate,"fillOnOverflow" .= overflow]
 Wait t -> object ["tag" .= ("wait" :: String),"timePicos" .= t]
observe :: String -> Rational -> Rational -> Bool -> [Event] -> Value
observe label capacity rate overflow events = runSimOrThrow $ do
  emptied <- newTVarIO ([] :: [Integer])
  let emptyAction = do
        Time now <- getMonotonicTime
        atomically $ modifyTVar emptied (++ [diffTimeToPicoseconds now])
      cfg = LB.Config capacity rate overflow emptyAction
  (st,()) <- LB.runAgainstBucket cfg $ \handlers -> forM_ events $ \event -> do
    Time now <- getMonotonicTime
    threadDelay $ LB.secondsRationalToDiffTime ((picos event - diffTimeToPicoseconds now) % 1000000000000)
    case event of
      Fill _ amount -> void $ LB.fill' handlers amount
      Pause _ paused -> LB.setPaused' handlers paused
      Configure _ level cap r oflow -> LB.updateConfig' handlers $ \_ -> (level,LB.Config cap r oflow emptyAction)
      Wait _ -> pure ()
  emptyTimes <- atomically $ readTVar emptied
  let LB.State{level,time=Time time,paused,configGeneration,config=LB.Config{capacity=cap,rate=r,fillOnOverflow=oflow}} = st
  pure $ object ["label" .= label,"capacity" .= ratioJSON capacity,"rate" .= ratioJSON rate,"fillOnOverflow" .= overflow,
    "events" .= map eventJSON events,"state" .= object ["level" .= ratioJSON level,"timePicos" .= diffTimeToPicoseconds time,
      "paused" .= paused,"generation" .= configGeneration,"capacity" .= ratioJSON cap,"rate" .= ratioJSON r,"fillOnOverflow" .= oflow],"emptyTimesPicos" .= emptyTimes]
scenarios :: [(String,Rational,Rational,Bool,[Event])]
scenarios =
 [ ("timer-persists-fill-pause",3%2000000,1,True,[Pause 500000 True,Pause 600000 False,Wait 1600000])
 , ("default-refill",100000,500,True,[Wait 1000000000000,Fill 1000000000000 1,Wait 2000000000000])
 , ("overflow-fill",10,1,True,[Wait 1000000000000,Fill 1000000000000 5])
 , ("overflow-ignore",10,1,False,[Wait 1000000000000,Fill 1000000000000 5])
 , ("pause-resume",10,1,True,[Pause 1000000000000 True,Wait 20000000000000,Fill 20000000000000 1,Pause 21000000000000 True,Pause 22000000000000 False,Wait 23000000000000])
 , ("configure-paused",10,1,True,[Pause 1000000000000 True,Configure 2000000000000 20 20 2 False,Wait 3000000000000,Pause 4000000000000 False,Wait 5000000000000])
 , ("picosecond-leak",10,3,True,[Wait 1,Fill 1 (1%1000000000000),Wait 2])
 , ("zero-rate",10,0,True,[Wait 10000000000000,Fill 10000000000000 (-2)])
 , ("negative-rate",10,-1,True,[Fill 0 (-5),Wait 1000000000000])
 , ("dummy-disabled",0,0,True,[Wait 10000000000000])
 , ("empty-boundary",1,1,True,[Wait 1000001000000])
 , ("submicrosecond-positive",1,1,True,[Configure 0 (1%10000000) 1 1 True,Wait 1000000])
 ]
main :: IO ()
main = do
 [output] <- getArgs
 let rows = [observe (label++"-prefix-"++show n) cap rate overflow (take n events) | (label,cap,rate,overflow,events)<-scenarios,n<-[0..length events]]
 BL.writeFile output $ BL.unlines $ map encode rows
