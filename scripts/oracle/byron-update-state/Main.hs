-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ScopedTypeVariables #-}

-- | The encoding of Byron's update-interface state, `UPI.State`, as the reference answers
-- `GetUpdateInterfaceState` with it: `encodeByronResult` is its `ToCBOR` instance, `toByronCBOR`
-- (`Ouroboros/Consensus/Byron/Ledger/Ledger.hs:563-565`, ouroboros-consensus 82ecba32). Each case
-- describes a state field by field; the harness builds it with the released types and writes the
-- encoding, and decodes that again (`decodeFull'` at `byronProtVer`) to check it reads back as the
-- state it was built from.
--
-- With `register`, each case is instead a software update proposal, signed with a throwaway key
-- derived from a fixed seed, which `Registration.registerProposal` registers or refuses
-- (`Cardano/Chain/Update/Validation/Registration.hs:331-391, 553-585`, cardano-ledger f649f975): the
-- harness writes the proposal's bytes and the verdict.
module Main (main) where

import Cardano.Chain.Common (
  KeyHash (..),
  hashKey,
  LovelacePortion,
  TxFeePolicy (..),
  TxSizeLinear (..),
  mkLovelace,
  rationalToLovelacePortion,
 )
import Cardano.Chain.Slotting (EpochNumber (..), SlotNumber (..))
import qualified Cardano.Chain.Delegation as Delegation
import Cardano.Chain.Update (
  ApplicationName (..),
  ApplicationNameError (..),
  InstallerHash (..),
  ProposalBody (ProposalBody),
  ProtocolParametersUpdate (..),
  SoftwareVersionError (..),
  SystemTagError (..),
  ProtocolParameters (..),
  ProtocolVersion (..),
  SoftforkRule (..),
  SoftwareVersion (..),
  SystemTag (..),
 )
import Cardano.Chain.Update.Validation.Endorsement (CandidateProtocolUpdate (..), Endorsement (..))
import qualified Cardano.Chain.Update.Validation.Interface as UPI
import qualified Cardano.Chain.Update.Validation.Registration as Registration
import Cardano.Chain.Update.Proposal (signProposal)
import Cardano.Crypto.Hashing (AbstractHash, HashAlgorithm, abstractHashFromBytes, hashRaw)
import Cardano.Crypto.ProtocolMagic (ProtocolMagicId (..))
import Cardano.Crypto.Signing (deterministicKeyGen, noPassSafeSigner)
import Cardano.Ledger.Binary (
  Annotated (..),
  DecCBOR (..),
  DecoderError,
  ToCBOR (..),
  byronProtVer,
  decodeFull',
  decodeFullAnnotatedBytes,
  serialize',
 )
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

-- | The protocol magic of the registration cases.
magicId :: ProtocolMagicId
magicId = ProtocolMagicId 4242

-- | A software update proposal signed by a delegate of the one genesis key, registered against
-- the case's adopted state: its bytes and the verdict of `registerProposal`.
register :: Value -> Value
register = \v -> case parseEither describe v of
  Left err -> object ["failure" .= err]
  Right (name, body, env, st) ->
    let proposal = signProposal magicId body (noPassSafeSigner signer)
        bytes = serialize' byronProtVer proposal
        verdict =
          decodeFullAnnotatedBytes byronProtVer "Proposal" decCBOR (LBS.fromStrict bytes)
            >>= \annotated -> Right (Registration.registerProposal env st annotated)
     in object $
          ["name" .= name, "proposal" .= TE.decodeUtf8 (Base16.encode bytes)] ++ case verdict of
            Left err -> ["accepted" .= False, "rule" .= ("DecodeFailure" :: T.Text), "failure" .= show err]
            Right (Left err) -> ["accepted" .= False, "failure" .= show err] ++ refusal err
            Right (Right (Registration.State _ sups)) ->
              [ "accepted" .= True
              , "registered"
                  .= [ object ["name" .= unApplicationName n, "number" .= k, "tags" .= map getSystemTag (Map.keys m)]
                     | Registration.SoftwareUpdateProposal (SoftwareVersion n k) m <- Map.elems sups
                     ]
              ]
  where
    (verification, signer) = deterministicKeyGen (BS.replicate 32 0x2a)
    (genesis, _) = deterministicKeyGen (BS.replicate 32 0x2b)
    refusal err = case err of
      Registration.SystemTagError (SystemTagTooLong t) -> ["rule" .= ("SystemTagTooLong" :: T.Text), "text" .= t]
      Registration.SystemTagError (SystemTagNotAscii t) -> ["rule" .= ("SystemTagNotAscii" :: T.Text), "text" .= t]
      Registration.SoftwareVersionError (SoftwareVersionApplicationNameError (ApplicationNameTooLong t)) ->
        ["rule" .= ("ApplicationNameTooLong" :: T.Text), "text" .= t]
      Registration.SoftwareVersionError (SoftwareVersionApplicationNameError (ApplicationNameNotAscii t)) ->
        ["rule" .= ("ApplicationNameNotAscii" :: T.Text), "text" .= t]
      Registration.DuplicateSoftwareVersion _ -> ["rule" .= ("DuplicateSoftwareVersion" :: T.Text)]
      Registration.InvalidSoftwareVersion _ _ -> ["rule" .= ("InvalidSoftwareVersion" :: T.Text)]
      Registration.NullUpdateProposal -> ["rule" .= ("NullUpdateProposal" :: T.Text)]
      _ -> ["rule" .= ("Other" :: T.Text)]
    describe = withObject "registration" $ \o -> do
      name <- o .: "name" :: Parser T.Text
      tags <- o .: "tags" :: Parser [T.Text]
      software <- SoftwareVersion <$> (ApplicationName <$> o .: "app") <*> (o .: "number" >>= word)
      adopted <- o .: "adoptedParameters" >>= parameters
      proposed <- o .: "version" >>= version
      apps <- o .: "appVersions" >>= traverse app
      sups <- o .: "registered" >>= traverse sup
      let nothing = ProtocolParametersUpdate Nothing Nothing Nothing Nothing Nothing Nothing Nothing Nothing Nothing Nothing Nothing Nothing Nothing Nothing
          meta = Map.fromList [(SystemTag t, InstallerHash (hashRaw (LBS.fromStrict (TE.encodeUtf8 t)))) | t <- tags]
          body = ProposalBody proposed nothing software meta
          delegation = Delegation.fromList [(hashKey genesis, hashKey verification)]
          env =
            Registration.Environment
              (Annotated magicId (serialize' byronProtVer magicId))
              (SlotNumber 1)
              (ProtocolVersion 0 0 0)
              adopted
              (Map.fromList apps)
              delegation
      pure (name, body, env, Registration.State Map.empty (Map.fromList (zipWith upId [1 :: Int ..] sups)))
    app = withObject "appVersion" $ \a -> do
      n <- ApplicationName <$> a .: "name"
      v <- Registration.ApplicationVersion <$> (a .: "number" >>= word) <*> pure (SlotNumber 0) <*> pure Map.empty
      pure (n, v)
    sup = withObject "registered" $ \a ->
      SoftwareVersion <$> (ApplicationName <$> a .: "name") <*> (a .: "number" >>= word)
    upId i sv =
      ( hashRawUpId i
      , Registration.SoftwareUpdateProposal sv Map.empty
      )
    hashRawUpId i = case abstractHashFromBytes (BS.replicate 32 (fromIntegral i)) of
      Just h -> h
      Nothing -> error "a 32-byte id"

main :: IO ()
main = do
  args <- getArgs
  case args of
    [p] -> run' p run
    ["register", p] -> run' p register
    _ -> die "usage: scarab-byron-update-state [register] INPUT.json"
  where
    run' p f = do
      cases <- (LBS.readFile p >>= either die pure . eitherDecode) :: IO [Value]
      LBS.putStr (encode (map f cases))
