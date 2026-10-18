-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DeriveGeneric #-}
module Main where

import Data.Word (Word16, Word8)
import GHC.Generics (Generic)
import System.Exit (die)
import Text.Read (readMaybe)

-- Unchanged ledger f649f97 ProtocolVersion.hs:27-33.
-- | Communication protocol version
data ProtocolVersion = ProtocolVersion
  { pvMajor :: !Word16
  , pvMinor :: !Word16
  , pvAlt :: !Word8
  }
  deriving (Eq, Generic, Ord)

-- Unchanged ledger f649f97 Validation/Registration.hs:489-497.
pvCanFollow :: ProtocolVersion -> ProtocolVersion -> Bool
pvCanFollow newPV adoptedPV = adoptedPV < newPV && isNextVersion
  where
    ProtocolVersion newMajor newMinor _ = newPV
    ProtocolVersion adoptedMajor adoptedMinor _ = adoptedPV
    isNextVersion = case newMajor - adoptedMajor of
      0 -> newMinor == adoptedMinor + 1
      1 -> newMinor == 0
      _ -> False


type Components = (Word16, Word16, Word8)

toVersion :: Components -> ProtocolVersion
toVersion (major, minor, alt) = ProtocolVersion major minor alt

verdict :: (Components, Components) -> String
verdict (adopted, proposed) =
  if pvCanFollow (toVersion proposed) (toVersion adopted) then "true" else "false"

main :: IO ()
main = do
  input <- getContents
  case traverse readMaybe (lines input) of
    Nothing -> die "Invalid trusted fixture tuple"
    Just cases -> mapM_ (putStrLn . verdict) cases
