-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE PackageImports #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

import Cardano.Ledger.BaseTypes (
  EpochInterval (..),
  EpochNo (..),
  Globals (..),
  Network (..),
  knownNonZeroBounded,
  mkActiveSlotCoeff,
  BoundedRational (..),
 )
import Cardano.Ledger.Binary (decCBOR, decNoShareCBOR, decodeFullDecoder, natVersion, serialize')
import Cardano.Ledger.Coin (Coin (..))
import Cardano.Ledger.Dijkstra (DijkstraEra)
import Cardano.Ledger.Dijkstra.Rules (maxKeyAgeEpochs)
import Cardano.Ledger.State (
  SetSnapShot (..),
  SnapShot,
  SnapShots (..),
  mkGoSnapShot,
  mkMarkSnapShot,
  mkSetSnapShot,
 )
import Cardano.Slotting.EpochInfo (fixedEpochInfo)
import Cardano.Slotting.Slot (EpochSize (..))
import Cardano.Slotting.Time (SystemStart (..), mkSlotLength)
import Data.Time.Clock.POSIX (posixSecondsToUTCTime)
import Data.Aeson (FromJSON (..), Value, eitherDecode, encode, object, withObject, (.:), (.:?), (.!=), (.=))
import qualified Data.ByteString as BS
import qualified "base16-bytestring" Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy.Char8 as LBS
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import Data.Ratio ((%))
import Data.Word (Word16, Word32, Word64)
import System.Exit (die)

-- A committee case decodes snapshots; a rotation case also takes the new mark snapshot and SNAP's
-- epoch, committee size, key age and fee; a key-age case takes the KES parameters.
data Case
  = Committee Text.Text Text.Text
  | Rotate Text.Text Text.Text Text.Text Word64 Word16 Word32 Integer
  | KesAge Text.Text Word64 Word64 Word64

instance FromJSON Case where
  parseJSON = withObject "case" $ \o -> do
    name <- o .: "name"
    kind <- o .:? "kind" .!= ("committee" :: Text.Text)
    case kind of
      "rotate" ->
        Rotate name <$> o .: "cbor" <*> o .: "mark" <*> o .: "epoch" <*> o .: "size" <*> o .: "age" <*> o .: "fee"
      "kesAge" -> KesAge name <$> o .: "maxKesEvolutions" <*> o .: "slotsPerKesPeriod" <*> o .: "epochSize"
      _ -> Committee name <$> o .: "cbor"

hex :: BS.ByteString -> Text.Text
hex = Text.decodeUtf8 . Hex.encode

unhex :: Text.Text -> IO BS.ByteString
unhex = either die pure . Hex.decode . Text.encodeUtf8

decodeSnapShots :: BS.ByteString -> Either String (SnapShots DijkstraEra)
decodeSnapShots raw = either (Left . show) Right $
  decodeFullDecoder (natVersion @12) "SnapShots" decCBOR (LBS.fromStrict raw)

-- The unchanged decoder of the epoch state's snapshots, whose mark snapshot memoizes its Leios
-- committee (mkMarkSnapShot), and the unchanged rotation of that mark into the set position
-- (mkSetSnapShot), which SNAP performs at the next epoch boundary.
committee :: BS.ByteString -> Value
committee raw = case decodeSnapShots raw of
  Left err -> object ["accepted" .= False, "failure" .= err]
  Right snapshots ->
    let set = mkSetSnapShot (ssStakeMark snapshots)
     in object
          [ "accepted" .= True
          , "set" .= hex (serialize' (natVersion @12) set)
          , "committee" .= hex (serialize' (natVersion @12) (ssLeiosCommittee set))
          ]

-- SNAP's result as snapTransition builds it (Shelley/Rules/Snap.hs:96-102 and
-- Dijkstra/Rules/Snap.hs:138-146): the new mark, the old mark as set, the old set as go, the fee.
rotate :: BS.ByteString -> BS.ByteString -> Word64 -> Word16 -> Word32 -> Integer -> Value
rotate raw markRaw epoch size age fee =
  case (decodeSnapShots raw, decodeFullDecoder (natVersion @12) "SnapShot" decNoShareCBOR (LBS.fromStrict markRaw)) of
    (Left err, _) -> object ["accepted" .= False, "failure" .= err]
    (_, Left err) -> object ["accepted" .= False, "failure" .= show err]
    (Right old, Right (mark :: SnapShot)) ->
      let rotated =
            SnapShots
              { ssStakeMark = mkMarkSnapShot mark (EpochNo epoch) size (EpochInterval age)
              , ssStakeSet = mkSetSnapShot (ssStakeMark old)
              , ssStakeGo = mkGoSnapShot (ssStakeSet old)
              , ssFee = Coin fee
              } :: SnapShots DijkstraEra
       in object ["accepted" .= True, "snapshots" .= hex (serialize' (natVersion @12) rotated)]

run :: Case -> IO Value
run (Committee name raw) = do
  bytes <- unhex raw
  pure $ object ["name" .= name, "reference" .= committee bytes]
run (Rotate name raw markRaw epoch size age fee) = do
  bytes <- unhex raw
  markBytes <- unhex markRaw
  pure $ object ["name" .= name, "reference" .= rotate bytes markBytes epoch size age fee]
-- maxKeyAgeEpochs as SNAP calls it (Dijkstra/Rules/Snap.hs:121, 157-166): Globals whose KES fields
-- and fixed epoch size are the case's; the other fields are not read.
run (KesAge name evolutions slotsPerPeriod epochSize) =
  pure $
    object
      [ "name" .= name
      , "reference" .= object ["maxKeyAge" .= unEpochInterval (maxKeyAgeEpochs globals (EpochNo 0))]
      ]
  where
    globals =
      Globals
        { epochInfo = fixedEpochInfo (EpochSize epochSize) (mkSlotLength 1)
        , slotsPerKESPeriod = slotsPerPeriod
        , stabilityWindow = 0
        , randomnessStabilisationWindow = 0
        , securityParameter = knownNonZeroBounded @1
        , maxKESEvo = evolutions
        , quorum = 5
        , maxLovelaceSupply = 0
        , activeSlotCoeff = mkActiveSlotCoeff (maybe (error "1/2 is a unit interval") id (boundRational (1 % 2)))
        , networkId = Testnet
        , systemStart = SystemStart (posixSecondsToUTCTime 0)
        }

main :: IO ()
main = LBS.getContents >>= mapM_ (\line -> either die run (eitherDecode line) >>= LBS.putStrLn . encode) . LBS.lines
