-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ScopedTypeVariables #-}

-- | The encoding of Byron's update-interface state, `UPI.State`, as the reference answers
-- `GetUpdateInterfaceState` with it: `encodeByronResult` is its `ToCBOR` instance, `toByronCBOR`
-- (`Ouroboros/Consensus/Byron/Ledger/Ledger.hs:563-565`, ouroboros-consensus 82ecba32). Each case
-- describes a state field by field; the harness builds it with the released types and writes the
-- encoding, and decodes that again (`decodeFull'` at `byronProtVer`) to check it reads back as the
-- state it was built from.
module Main (main) where

import Cardano.Chain.Common (
  KeyHash (..),
  LovelacePortion,
  TxFeePolicy (..),
  TxSizeLinear (..),
  mkLovelace,
  rationalToLovelacePortion,
 )
import Cardano.Chain.Slotting (EpochNumber (..), SlotNumber (..))
import Cardano.Chain.Update (
  ApplicationName (..),
  InstallerHash (..),
  ProtocolParameters (..),
  ProtocolVersion (..),
  SoftforkRule (..),
  SoftwareVersion (..),
  SystemTag (..),
 )
import Cardano.Chain.Update.Validation.Endorsement (CandidateProtocolUpdate (..), Endorsement (..))
import qualified Cardano.Chain.Update.Validation.Interface as UPI
import qualified Cardano.Chain.Update.Validation.Registration as Registration
import Cardano.Crypto.Hashing (AbstractHash, HashAlgorithm, abstractHashFromBytes)
import Cardano.Ledger.Binary (DecoderError, ToCBOR (..), byronProtVer, decodeFull')
import qualified Codec.CBOR.Write as CBOR
import Data.Aeson
import Data.Aeson.Types (Parser, parseEither)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Base16 as Base16
import qualified Data.ByteString.Lazy as LBS
import qualified Data.Map.Strict as Map
import Data.Ratio ((%))
import qualified Data.Set as Set
import qualified Data.Text as T
import qualified Data.Text.Encoding as TE
import Numeric.Natural (Natural)
import System.Environment (getArgs)
import System.Exit (die)

-- | A bounded word from a JSON integer, refused outside the type's range.
word :: forall a. (Integral a, Bounded a) => Integer -> Parser a
word n
  | n < toInteger (minBound :: a) || n > toInteger (maxBound :: a) = fail ("out of range: " <> show n)
  | otherwise = pure (fromInteger n)

natural :: Integer -> Parser Natural
natural n
  | n < 0 = fail ("negative natural: " <> show n)
  | otherwise = pure (fromInteger n)

hexBytes :: T.Text -> Parser BS.ByteString
hexBytes t = either fail pure (Base16.decode (TE.encodeUtf8 t))

hash :: HashAlgorithm algo => T.Text -> Parser (AbstractHash algo a)
hash t = hexBytes t >>= maybe (fail ("hash of the wrong length: " <> T.unpack t)) pure . abstractHashFromBytes

version :: Value -> Parser ProtocolVersion
version v = do
  [a, b, c] <- parseJSON v :: Parser [Integer]
  ProtocolVersion <$> word a <*> word b <*> word c

portion :: Integer -> Parser LovelacePortion
portion n
  | n < 0 || n > 1000000000000000 = fail ("portion out of range: " <> show n)
  | otherwise = pure (rationalToLovelacePortion (n % 1000000000000000))

parameters :: Value -> Parser ProtocolParameters
parameters = withObject "parameters" $ \o -> do
  [i, m, d] <- o .: "softforkRule" :: Parser [Integer]
  summand <- o .: "summand" >>= word
  lovelace <- either (fail . show) pure (mkLovelace summand)
  multiplier <- o .: "multiplier"
  ProtocolParameters
    <$> (o .: "scriptVersion" >>= word)
    <*> (o .: "slotDuration" >>= natural)
    <*> (o .: "maxBlockSize" >>= natural)
    <*> (o .: "maxHeaderSize" >>= natural)
    <*> (o .: "maxTxSize" >>= natural)
    <*> (o .: "maxProposalSize" >>= natural)
    <*> (o .: "mpcThd" >>= portion)
    <*> (o .: "heavyDelThd" >>= portion)
    <*> (o .: "updateVoteThd" >>= portion)
    <*> (o .: "updateProposalThd" >>= portion)
    <*> (SlotNumber <$> (o .: "updateProposalTtl" >>= word))
    <*> (SoftforkRule <$> portion i <*> portion m <*> portion d)
    <*> pure (TxFeePolicyTxSizeLinear (TxSizeLinear lovelace (multiplier % 1000000000)))
    <*> (EpochNumber <$> (o .: "unlockStakeEpoch" >>= word))

metadata :: Value -> Parser Registration.Metadata
metadata v = do
  entries <- parseJSON v :: Parser [Object]
  Map.fromList
    <$> traverse (\e -> (,) <$> (SystemTag <$> e .: "tag") <*> (InstallerHash <$> (e .: "hash" >>= hash))) entries

state :: Value -> Parser UPI.State
state = withObject "state" $ \o -> do
  candidates <- o .: "candidates" >>= traverse candidate
  apps <- o .: "appVersions" >>= traverse app
  protocol <- o .: "protocolProposals" >>= traverse protocolProposal
  software <- o .: "softwareProposals" >>= traverse softwareProposal
  confirmed <- o .: "confirmed" >>= traverse slotOf
  votes <- o .: "votes" >>= traverse vote
  endorsements <- o .: "endorsements" >>= traverse endorsement
  registered <- o .: "registrationSlots" >>= traverse slotOf
  -- `currentEpoch`: only `initialState` writes it, with 0 (`Interface.hs:225-238`)
  UPI.State 0
    <$> (o .: "adoptedVersion" >>= version)
    <*> (o .: "adoptedParameters" >>= parameters)
    <*> pure candidates
    <*> pure (Map.fromList apps)
    <*> pure (Map.fromList protocol)
    <*> pure (Map.fromList software)
    <*> pure (Map.fromList confirmed)
    <*> pure (Map.fromList votes)
    <*> pure (Set.fromList endorsements)
    <*> pure (Map.fromList registered)
  where
    candidate = withObject "candidate" $ \c ->
      CandidateProtocolUpdate
        <$> (SlotNumber <$> (c .: "slot" >>= word))
        <*> (c .: "version" >>= version)
        <*> (c .: "parameters" >>= parameters)
    app = withObject "appVersion" $ \a -> do
      name <- ApplicationName <$> a .: "name"
      v <- Registration.ApplicationVersion <$> (a .: "number" >>= word) <*> (SlotNumber <$> (a .: "slot" >>= word)) <*> (a .: "metadata" >>= metadata)
      pure (name, v)
    protocolProposal = withObject "protocolProposal" $ \p -> do
      upId <- p .: "id" >>= hash
      v <- Registration.ProtocolUpdateProposal <$> (p .: "version" >>= version) <*> (p .: "parameters" >>= parameters)
      pure (upId, v)
    softwareProposal = withObject "softwareProposal" $ \p -> do
      upId <- p .: "id" >>= hash
      sv <- SoftwareVersion <$> (ApplicationName <$> p .: "name") <*> (p .: "number" >>= word)
      v <- Registration.SoftwareUpdateProposal sv <$> (p .: "metadata" >>= metadata)
      pure (upId, v)
    slotOf = withObject "slot" $ \s -> (,) <$> (s .: "id" >>= hash) <*> (SlotNumber <$> (s .: "slot" >>= word))
    vote = withObject "vote" $ \v -> do
      upId <- v .: "id" >>= hash
      voters <- v .: "voters" >>= traverse (fmap KeyHash . hash)
      pure (upId, Set.fromList voters)
    endorsement = withObject "endorsement" $ \e ->
      Endorsement <$> (e .: "version" >>= version) <*> (KeyHash <$> (e .: "key" >>= hash))

run :: Value -> Value
run = \v -> case parseEither describe v of
  Left err -> object ["failure" .= err]
  Right (name, st) ->
    let bytes = CBOR.toStrictByteString (toCBOR st)
        readBack = decodeFull' byronProtVer bytes :: Either DecoderError UPI.State
     in object
          [ "name" .= name
          , "cbor" .= TE.decodeUtf8 (Base16.encode bytes)
          , "readsBack" .= (readBack == Right st)
          ]
  where
    describe = withObject "case" $ \o -> (,) <$> (o .: "name" :: Parser T.Text) <*> (o .: "state" >>= state)

main :: IO ()
main = do
  args <- getArgs
  cases <- case args of
    [p] -> (LBS.readFile p >>= either die pure . eitherDecode) :: IO [Value]
    _ -> die "one input JSON path required"
  LBS.putStr (encode (map run cases))
