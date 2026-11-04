-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DeriveGeneric #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

-- The leader check of cardano-ledger f649f975: `ln'`, `splitLn`, `findE` and `taylorExpCmp` from
-- the installed non-integral package, `mkActiveSlotCoeff` and `activeSlotLog` from
-- cardano-ledger-core. `checkLeaderNatValue` lives in cardano-protocol, which the image does not
-- hold; `comparison` below is its body (`Cardano/Protocol/TPraos/BlockHeader.hs:380-402`) with
-- `BoundedNatural` unfolded to its two naturals. `unActiveSlotLog` is not exported; the E34 units of
-- `activeSlotLog` equal it exactly (`BaseTypes.hs:754-755`).

import Cardano.Ledger.BaseTypes (ActiveSlotCoeff, FixedPoint, PositiveUnitInterval, activeSlotLog,
  activeSlotVal, boundRational, mkActiveSlotCoeff)
import Cardano.Ledger.BaseTypes.NonZero (nonZero, (%.))
import Cardano.Ledger.NonIntegral (CompareResult (..), splitLn, taylorExpCmp)
import Data.Aeson (FromJSON (..), Value, eitherDecode, encode, object, withText, (.=))
import qualified Data.ByteString.Lazy.Char8 as LBS
import Data.Fixed (Fixed (..))
import Data.Ratio ((%))
import qualified Data.Text as Text
import GHC.Generics (Generic)
import Numeric.Natural (Natural)
import System.Exit (die)

newtype Decimal = Decimal Integer
instance FromJSON Decimal where
  parseJSON = withText "decimal integer" $ \t -> case reads (Text.unpack t) of
    [(n, "")] -> pure (Decimal n)
    _ -> fail "invalid decimal integer"

data Case = Case
  { name :: String
  , activeNumerator :: Decimal
  , activeDenominator :: Decimal
  , stakeNumerator :: Decimal
  , stakeDenominator :: Decimal
  , value :: Decimal
  , range :: Decimal
  }
  deriving Generic
instance FromJSON Case

raw :: FixedPoint -> Integer
raw (MkFixed r) = r

comparison :: ActiveSlotCoeff -> Natural -> Natural -> Rational -> (String, Maybe Int, Bool)
comparison f certNatMax certNat σ =
  if activeSlotVal f == maxBound
    then ("one", Nothing, True)
    else case taylorExpCmp 3 recip_q x of
      ABOVE _ n -> ("ABOVE", Just n, False)
      BELOW _ n -> ("BELOW", Just n, True)
      MaxReached n -> ("MaxReached", Just n, False)
  where
    c, recip_q, x :: FixedPoint
    c = activeSlotLog f
    recip_q =
      case nonZero . toInteger $ certNatMax - certNat of
        Just d -> fromRational (toInteger certNatMax %. d)
        Nothing -> fromIntegral @Natural @FixedPoint certNatMax
    x = -fromRational σ * c

runCase :: Case -> Either String Value
runCase (Case label (Decimal an) (Decimal ad) (Decimal sn) (Decimal sd) (Decimal v) (Decimal r)) = do
  interval <- maybe (Left (label ++ ": not a PositiveUnitInterval")) Right
    (boundRational (an % ad) :: Maybe PositiveUnitInterval)
  let f = mkActiveSlotCoeff interval
      one = interval == maxBound
      (n, x') = splitLn ((1 :: FixedPoint) - fromRational (an % ad))
      (outcome, steps, leader) = comparison f (fromInteger r) (fromInteger v) (sn % sd)
  pure $ object
    [ "name" .= label
    , "activeNumerator" .= show an, "activeDenominator" .= show ad
    , "stakeNumerator" .= show sn, "stakeDenominator" .= show sd
    , "value" .= show v, "range" .= show r
    , "splitExponent" .= (if one then Nothing else Just (show n))
    , "splitRemainder" .= (if one then Nothing else Just (show (raw x')))
    , "activeSlotLog" .= show (raw (activeSlotLog f))
    , "outcome" .= outcome
    , "steps" .= steps
    , "leader" .= leader
    ]

main :: IO ()
main = do
  input <- LBS.getContents
  mapM_ (\line -> either die (LBS.putStrLn . encode) (eitherDecode line >>= runCase))
    (filter (not . LBS.null) (LBS.lines input))
