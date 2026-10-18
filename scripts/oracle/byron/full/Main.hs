-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE DeriveGeneric #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

import qualified Cardano.Chain.Block as Block
import qualified Cardano.Chain.Byron.API as API
import qualified Cardano.Chain.Common as Common
import qualified Cardano.Chain.Delegation as Delegation
import qualified Cardano.Chain.Genesis as Genesis
import qualified Cardano.Chain.MempoolPayload as Mempool
import qualified Cardano.Chain.Slotting as Slot
import qualified Cardano.Chain.Ssc as Ssc
import qualified Cardano.Chain.Update.Proposal as Proposal
import qualified Cardano.Chain.Update as Update
import qualified Cardano.Chain.Update.Validation.Interface as Interface
import qualified Cardano.Chain.Update.Validation.Registration as Registration
import qualified Cardano.Chain.UTxO as UTxO
import qualified Cardano.Chain.ValidationMode as Validation
import qualified Cardano.Crypto as Crypto
import qualified Cardano.Ledger.Binary as Binary
import qualified Codec.CBOR.Write as CBOR
import Control.Monad (forM, unless)
import Control.Monad.Except (runExceptT)
import Data.Aeson (FromJSON, ToJSON, eitherDecode, encode, object, (.=))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy as LBS
import Data.Functor.Identity (runIdentity)
import Data.List.NonEmpty (NonEmpty ((:|)))
import qualified Data.Map.Strict as Map
import qualified Data.Set as Set
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import Data.Time (UTCTime (..), Day (..))
import qualified Data.Vector as Vector
import Data.Word (Word16, Word8)
import GHC.Generics (Generic)
import System.Directory (createDirectoryIfMissing)
import System.Environment (getArgs)
import System.Exit (die)
import System.FilePath ((</>))
import qualified Text.JSON.Canonical as Canonical

type Version = (Word16, Word16, Word8)

data Case = Case
  { name :: String
  , adopted :: Version
  , proposal :: Text.Text
  , block :: Text.Text
  } deriving (Show, Generic)
instance ToJSON Case
instance FromJSON Case

data Verdict = Accepted | InvalidVersion | InvalidSignature | Unauthorised | DecodeFailure | TransactionFailure | OtherFailure | NotRun
  deriving (Eq, Show, Generic)
instance ToJSON Verdict

data Result = Result { verdict :: Verdict, diagnostic :: String } deriving (Show, Generic)
instance ToJSON Result

data Outcome = Outcome
  { caseName :: String
  , proposalDecode :: Result
  , proposalSignature :: Maybe Bool
  , registration :: Result
  , registeredVersions :: [Version]
  , admission :: Result
  , admissionVersions :: [Version]
  , transactionAdmission :: Result
  , blockDecode :: Result
  , headerSignature :: Maybe Bool
  , certificateSignature :: Maybe Bool
  , ledgerBlock :: Result
  , compositeBlock :: Result
  , blockVersions :: [Version]
  , inputSpent :: Maybe Bool
  , outputPresent :: Maybe Bool
  } deriving (Show, Generic)
instance ToJSON Outcome

pm :: Crypto.ProtocolMagicId
pm = Crypto.ProtocolMagicId 4242

magic :: Binary.Annotated Crypto.ProtocolMagicId BS.ByteString
magic = Binary.Annotated pm (Binary.serialize' Binary.byronProtVer pm)

version :: Version -> Update.ProtocolVersion
version (major, minor, alt) = Update.ProtocolVersion major minor alt

components :: Update.ProtocolVersion -> Version
components (Update.ProtocolVersion major minor alt) = (major, minor, alt)

hex :: BS.ByteString -> Text.Text
hex = Text.decodeUtf8 . Hex.encode

data InputError = InvalidHex String deriving (Show)

raw :: Text.Text -> Either InputError BS.ByteString
raw = either (Left . InvalidHex) Right . Hex.decode . Text.encodeUtf8

serialize :: Binary.EncCBOR a => a -> BS.ByteString
serialize = Binary.serialize' Binary.byronProtVer

success :: Result
success = Result Accepted ""

notRun :: Result
notRun = Result NotRun ""

regError :: Registration.Error -> Result
regError e = Result category (show e)
  where
    category = case e of
      Registration.InvalidProtocolVersion {} -> InvalidVersion
      Registration.InvalidSignature -> InvalidSignature
      Registration.InvalidProposer {} -> Unauthorised
      _ -> OtherFailure

ifaceError :: Interface.Error -> Result
ifaceError e = case e of
  Interface.Registration inner -> regError inner
  _ -> Result OtherFailure (show e)

mempoolError :: API.ApplyMempoolPayloadErr -> Result
mempoolError e = case e of
  API.MempoolUpdateProposalErr inner -> ifaceError inner
  API.MempoolTxErr {} -> Result TransactionFailure (show e)
  _ -> Result OtherFailure (show e)

blockError :: Block.ChainValidationError -> Result
blockError e = case e of
  Block.ChainValidationUpdateError _ inner -> ifaceError inner
  Block.ChainValidationUTxOValidationError {} -> Result TransactionFailure (show e)
  _ -> Result OtherFailure (show e)

mode :: Validation.ValidationMode
mode = Validation.fromBlockValidationMode Block.BlockValidation

parameters :: Update.ProtocolParameters
parameters = Update.ProtocolParameters
  { Update.ppScriptVersion = 0
  , Update.ppSlotDuration = 1000
  , Update.ppMaxBlockSize = 2000000
  , Update.ppMaxHeaderSize = 2000000
  , Update.ppMaxTxSize = 4096
  , Update.ppMaxProposalSize = 700
  , Update.ppMpcThd = Common.rationalToLovelacePortion 0.02
  , Update.ppHeavyDelThd = Common.rationalToLovelacePortion 0.0003
  , Update.ppUpdateVoteThd = Common.rationalToLovelacePortion 0.001
  , Update.ppUpdateProposalThd = Common.rationalToLovelacePortion 0.1
  , Update.ppUpdateProposalTTL = 10000
  , Update.ppSoftforkRule = Update.SoftforkRule
      (Common.rationalToLovelacePortion 0.9)
      (Common.rationalToLovelacePortion 0.6)
      (Common.rationalToLovelacePortion 0.05)
  , Update.ppTxFeePolicy = Common.TxFeePolicyTxSizeLinear (Common.TxSizeLinear (Common.mkKnownLovelace @155381) 43.946)
  , Update.ppUnlockStakeEpoch = Slot.EpochNumber maxBound
  }

software :: Update.SoftwareVersion
software = Update.SoftwareVersion (Update.ApplicationName "cardano-sl") 1

readConfig :: FilePath -> IO Genesis.Config
readConfig directory = do
  (gd, gh) <- runExceptT (Genesis.readGenesisData (directory </> "genesis.json")) >>= either (die . show) pure
  pure $ Genesis.Config gd gh Crypto.RequiresMagic UTxO.defaultUTxOConfiguration

initial :: Genesis.Config -> Version -> IO Block.ChainValidationState
initial cfg adoptedVersion = do
  st <- either (die . show) pure (Block.initialChainValidationState cfg)
  pure st { Block.cvsUpdateState = (Block.cvsUpdateState st) { Interface.adoptedProtocolVersion = version adoptedVersion } }

versions :: Map.Map k Registration.ProtocolUpdateProposal -> [Version]
versions = map (components . Registration.pupProtocolVersion) . Map.elems

execute :: Genesis.Config -> Case -> IO Outcome
execute cfg c = do
  st <- initial cfg (adopted c)
  pbytes <- either (die . show) pure (raw (proposal c))
  bbytes <- either (die . show) pure (raw (block c))
  let p = Binary.decodeFullAnnotatedBytes Binary.byronProtVer "Proposal" Binary.decCBOR (LBS.fromStrict pbytes)
      b = Binary.decodeFullAnnotatedBytes Binary.byronProtVer "Block" (Block.decCBORABlockOrBoundary (Genesis.configEpochSlots cfg)) (LBS.fromStrict bbytes)
      env = Registration.Environment magic 1 (version (adopted c)) (Genesis.configProtocolParameters cfg) Map.empty (API.getDelegationMap st)
      r = p >>= \value -> Right (Registration.registerProposal env (Registration.State Map.empty Map.empty) value :: Either Registration.Error Registration.State)
      adm = p >>= \value -> Right (API.applyMempoolPayload mode cfg 1 (Mempool.MempoolUpdateProposal value) st :: Either API.ApplyMempoolPayloadErr Block.ChainValidationState)
      rResult = either (\e -> Result DecodeFailure (show e)) (either regError (const success)) r
      aResult = either (\e -> Result DecodeFailure (show e)) (either mempoolError (const success)) adm
      rVersions = either (const []) (either (const []) (versions . Registration.rsProtocolUpdateProposals)) r
      aVersions = either (const []) (either (const []) (versions . Interface.registeredProtocolUpdateProposals . Block.cvsUpdateState)) adm
      pSig = either (const Nothing) (\value -> Just $ Crypto.verifySignatureDecoded magic Crypto.SignUSProposal (Update.issuer value) (Update.recoverProposalSignedBytes (Update.aBody value)) (Proposal.signature value)) p
      base = Outcome (name c) (either (\e -> Result DecodeFailure (show e)) (const success) p) pSig rResult rVersions aResult aVersions notRun (either (\e -> Result DecodeFailure (show e)) (const success) b) Nothing Nothing notRun notRun [] Nothing Nothing
  case b of
    Left _ -> pure base
    Right (Block.ABOBBoundary _) -> die "expected a complete main block"
    Right (Block.ABOBBlock blk) -> do
      tx <- case UTxO.aUnTxPayload (Block.bodyTxPayload (Block.blockBody blk)) of
        [value] -> pure value
        _ -> die "fixture must retain exactly one ordinary signed transaction"
      unless (Just pbytes == (Binary.recoverBytes <$> Update.payloadProposal (Block.bodyUpdatePayload (Block.blockBody blk))))
        (die "standalone proposal must be the exact original proposal bytes inside the complete block")
      let hdr = Block.blockHeader blk
          Block.ABlockSignature cert sig = Block.headerSignature hdr
          hSig = Crypto.verifySignatureDecoded magic (Crypto.SignBlock (Delegation.issuerVK cert)) (Delegation.delegateVK cert) (Block.recoverSignedBytes (Genesis.configEpochSlots cfg) hdr) sig
          certSig = Delegation.isValid magic cert
          txs = UTxO.aUnTxPayload (Block.bodyTxPayload (Block.blockBody blk))
          txResult = API.applyMempoolPayload mode cfg 1 (Mempool.MempoolTx tx) st :: Either API.ApplyMempoolPayloadErr Block.ChainValidationState
          result = API.validateBlock cfg mode blk (Block.blockHashAnnotated blk) st :: Either Block.ChainValidationError Block.ChainValidationState
          sourceInputs = map fst (UTxO.toList (Block.cvsUtxo st))
          consumed after = all (\input -> not (UTxO.member input (Block.cvsUtxo after))) sourceInputs
          created after = UTxO.member (UTxO.TxInUtxo (Crypto.hashDecoded (UTxO.aTaTx tx)) 0) (Block.cvsUtxo after)
      unless (not (null sourceInputs) && length txs == 1) (die "fixture must contain one funded signed transaction")
      pure base
        { transactionAdmission = either mempoolError (const success) txResult
        , headerSignature = Just hSig
        , certificateSignature = Just certSig
        , ledgerBlock = either blockError (const success) result
        , compositeBlock = if hSig && certSig then either blockError (const success) result else Result InvalidSignature "header/certificate signature gate refused"
        , blockVersions = either (const []) (versions . Interface.registeredProtocolUpdateProposals . Block.cvsUpdateState) result
        , inputSpent = either (const Nothing) (Just . consumed) result
        , outputPresent = either (const Nothing) (Just . created) result
        }

-- Keys exist only in this generator process. Replay uses retained public signed bytes.
generate :: FilePath -> IO ()
generate directory = do
  createDirectoryIfMissing True directory
  (gvk, gsk) <- Crypto.keyGen
  (dvk, dsk) <- Crypto.keyGen
  (hvk, hsk) <- Crypto.keyGen
  (_, osk) <- Crypto.keyGen
  let cert = Delegation.signCertificate pm dvk 0 (Crypto.noPassSafeSigner gsk)
      addr = Common.makeVerKeyAddress (Common.NetworkTestnet 4242) hvk
      gd = Genesis.GenesisData
        (Genesis.GenesisKeyHashes (Set.singleton (Common.hashKey gvk)))
        (Genesis.UnsafeGenesisDelegation (Map.singleton (Common.hashKey gvk) cert))
        (UTCTime (ModifiedJulianDay 60000) 0)
        (Genesis.GenesisNonAvvmBalances (Map.singleton addr (Common.mkKnownLovelace @10000000)))
        parameters (Common.BlockCount 10) pm (Genesis.GenesisAvvmBalances Map.empty)
  LBS.writeFile (directory </> "genesis.json") (Canonical.renderCanonicalJSON (runIdentity (Canonical.toJSON gd)))
  cfg <- readConfig directory
  st <- initial cfg (0,0,0)
  input <- case UTxO.toList (Block.cvsUtxo st) of
    [(key, _)] -> pure key
    _ -> die "expected one genesis input"
  let tx = UTxO.UnsafeTx (input :| []) (UTxO.TxOut addr (Common.mkKnownLovelace @9000000) :| []) (Common.mkAttributes ())
      txSig = Crypto.sign pm Crypto.SignTx hsk (UTxO.TxSigData (Crypto.serializeCborHash tx))
      txAux = UTxO.mkTxAux tx (Vector.singleton (UTxO.VKWitness hvk txSig))
      encodeBlock p pv signer = CBOR.toStrictByteString $ Binary.toPlainEncoding Binary.byronProtVer $
        Block.encCBORABlockOrBoundary pm (Genesis.configEpochSlots cfg) $ Block.ABOBBlock $
          Block.mkBlock pm pv software (Left (Genesis.configGenesisHash cfg)) (Genesis.configEpochSlots cfg) 1 signer cert $
            Block.ABody (UTxO.mkTxPayload [txAux]) Ssc.SscPayload (Delegation.unsafePayload []) (Update.payload (Just p) [])
      proposalBody pv = Update.ProposalBody (version pv) (Update.ProtocolParametersUpdate Nothing Nothing Nothing Nothing Nothing Nothing Nothing Nothing Nothing Nothing Nothing Nothing Nothing Nothing) software Map.empty
      signed pv = Update.signProposal pm (proposalBody pv) (Crypto.noPassSafeSigner dsk)
      build n old new p = Case n old (hex (serialize p)) (hex (encodeBlock p (version new) dsk))
      transitions =
        [ ((65534,0,0),(65535,0,0)), ((0,65534,0),(0,65535,0)), ((0,0,0),(1,0,255))
        , ((0,0,255),(0,1,0)), ((0,65535,255),(1,0,0)), ((65535,65534,0),(65535,65535,255))
        , ((0,0,0),(0,0,1)), ((0,0,0),(2,0,0)), ((0,0,0),(1,1,0))
        , ((0,65535,0),(0,0,1)), ((65535,0,0),(0,0,0)) ]
      normal = [build ("transition-" ++ show i) old new (signed new) | (i,(old,new)) <- zip [0::Int ..] transitions]
      badSig = Update.unsafeProposal (proposalBody (1,0,0)) dvk (Update.signatureForProposal pm (proposalBody (1,0,0)) (Crypto.noPassSafeSigner osk))
      unauthorised = Update.signProposal pm (proposalBody (1,0,0)) (Crypto.noPassSafeSigner osk)
      extra = [build "invalid-proposal-signature" (0,0,0) (1,0,0) badSig, build "unauthorised-proposer" (0,0,0) (1,0,0) unauthorised]
      badHeader = Case "invalid-header-signature" (0,0,0) (hex (serialize (signed (1,0,0)))) (hex (encodeBlock (signed (1,0,0)) (version (1,0,0)) osk))
      cases = normal ++ extra ++ [badHeader]
  LBS.writeFile (directory </> "cases.json") (encode cases)

replay :: FilePath -> IO ()
replay directory = do
  cfg <- readConfig directory
  unless (Genesis.configProtocolMagicId cfg == pm) (die "fixture protocol magic differs from the harness")
  cases <- LBS.readFile (directory </> "cases.json") >>= either die pure . eitherDecode @[Case]
  results <- forM cases (execute cfg)
  LBS.putStr (encode (object ["schema" .= (1::Int), "genesisHash" .= show (Genesis.configGenesisHash cfg), "results" .= results]))

main :: IO ()
main = do
  args <- getArgs
  case args of
    ["generate", directory] -> generate directory
    ["replay", directory] -> replay directory
    _ -> die "usage: scarab-byron-registration (generate|replay) DIRECTORY"
