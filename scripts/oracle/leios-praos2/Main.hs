-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE DeriveGeneric #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

import Cardano.Crypto.Init (cryptoInit)
import qualified Cardano.Crypto.Hash as Hash
import qualified Cardano.Crypto.DSIGN as DSIGN
import qualified Cardano.Crypto.KES as KES
import Cardano.Crypto.Seed (mkSeedFromBytes)
import Cardano.Ledger.BaseTypes (Nonce (..), StrictMaybe (..), Milliseconds32 (..), ProtVer (..), Version, activeSlotVal, natVersion)
import Cardano.Ledger.BaseTypes.NonZero (unNonZero, nonZeroOr)
import qualified Cardano.Ledger.Binary as Binary
import Cardano.Ledger.Block (EbReferencesAnnouncement (..))
import Cardano.Ledger.Coin (Coin (..), CompactForm (..), knownNonZeroCoin)
import Cardano.Ledger.Dijkstra (DijkstraEra)
import Cardano.Ledger.Dijkstra.Genesis (DijkstraGenesis)
import Cardano.Ledger.Hashes (extractHash, SafeToHash (originalBytes))
import Cardano.Ledger.Keys (DSIGN, VKey (..), KeyHash, KeyRole (StakePool), KeyRoleVRF (StakePoolVRF), VRFVerKeyHash)
import Cardano.Ledger.MemoBytes (getMemoRawType)
import qualified Cardano.Ledger.Shelley.API as SL
import Cardano.Ledger.State (PoolDistr (..), IndividualPoolStake (..), emptyLeiosCommittee)
import Cardano.Protocol.Crypto (StandardCrypto, KES)
import qualified Cardano.Protocol.Leios.BlockHeader as Leios
import Cardano.Protocol.TPraos.OCert (KESPeriod (..), OCert (..), OCertSignable (..))
import Cardano.Slotting.Slot (SlotNo (..))
import Cardano.Slotting.EpochInfo (fixedEpochInfo)
import Cardano.Slotting.Time (mkSlotLength)
import Control.Monad (foldM)
import Control.Monad.Except (runExcept, withExcept)
import Data.Aeson (FromJSON (..), eitherDecode, encode, object, (.=), Value, toJSON, withText)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy as LBS
import qualified Data.Map.Strict as Map
import Data.Maybe (fromMaybe)
import Data.Proxy (Proxy (..))
import Data.Ratio ((%))
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import Data.Word (Word16, Word32, Word64)
import GHC.Generics (Generic)
import Ouroboros.Consensus.Block (Header, blockSlot, WithOrigin (..), BlockNo (..), ChainHash (..))
import Ouroboros.Consensus.Config (TopLevelConfig, SecurityParam (..), CheckpointsMap (..), configConsensus, emptyCheckpointsMap, mkTopLevelConfig)
import Ouroboros.Consensus.HeaderValidation (HeaderState (..), HeaderError (..), HeaderEnvelopeError (..), genesisHeaderState, tickHeaderState, validateHeader, validateHeaderProtocol)
import qualified Ouroboros.Consensus.HeaderValidation as HV
import qualified Ouroboros.Consensus.Leios.Types as Timing
import Ouroboros.Consensus.Protocol.Abstract (reupdateChainDepState)
import Ouroboros.Consensus.Protocol.PolyPraos (AnnouncedBy (..), PolyPraosState (..), PraosParams (..))
import qualified Ouroboros.Consensus.Protocol.PolyPraos as PP
import Ouroboros.Consensus.Protocol.Praos (ConsensusConfig (PraosConfig))
import Ouroboros.Consensus.Protocol.Praos.Common (MaxMajorProtVer (..))
import Ouroboros.Consensus.Protocol.Praos2 (Praos2, ConsensusConfig (LeiosConfig), LeiosOnly (Praos2HasLeios))
import qualified Ouroboros.Consensus.Protocol.Praos.Views as Views
import Ouroboros.Consensus.Shelley.HFEras ()
import Ouroboros.Consensus.Shelley.Ledger (ShelleyBlock, ShelleyHash (..), shelleyHeaderRaw, mkShelleyHeader, mkShelleyLedgerConfig, mkShelleyBlockConfig, CodecConfig (ShelleyCodecConfig), StorageConfig (ShelleyStorageConfig))
import Ouroboros.Consensus.Shelley.Protocol.Abstract (protocolHeaderView)
import qualified Ouroboros.Consensus.Shelley.Protocol.EnvelopeChecks as Envelope
import Ouroboros.Consensus.Shelley.Protocol.Praos ()
import System.Environment (getArgs)
import System.Exit (die)

type Protocol = Praos2 StandardCrypto
type Block = ShelleyBlock Protocol DijkstraEra

-- Ledger forecasts are explicit oracle inputs. This harness validates headers; it does not
-- apply Dijkstra blocks or claim that a supplied distribution is the result of ledger replay.
data PoolInput = PoolInput
  { pool :: KeyHash StakePool
  , vrf :: VRFVerKeyHash StakePoolVRF
  , stake :: Word64
  , share :: Maybe Rational
  } deriving (Generic)
instance FromJSON PoolInput

data Input = Input
  { pools :: [PoolInput]
  , headers :: [Text.Text]
  , name :: Maybe String
  , options :: Maybe Options
  } deriving (Generic)
instance FromJSON Input

data Operation = Validate | ProtocolOnly | TickOnly | Reupdate | CertificationSlot
instance FromJSON Operation where
  parseJSON = withText "header operation" $ \text -> case text of
    "validate" -> pure Validate
    "protocol" -> pure ProtocolOnly
    "tick" -> pure TickOnly
    "reupdate" -> pure Reupdate
    "certificationSlot" -> pure CertificationSlot
    _ -> fail "unknown header operation"

-- Options apply only to the last header: its prefix establishes the unmodified native state.
data Options = Options
  { poolOverride :: Maybe [PoolInput]
  , major :: Maybe Version
  , headerLimit :: Maybe Word16
  , bodyLimit :: Maybe Word32
  , ebLimit :: Maybe Word32
  , announcementMillis :: Maybe Word32
  , voteMillis :: Maybe Word32
  , diffusionMillis :: Maybe Word32
  , slotMicros :: Maybe Word64
  , counter :: Maybe Word64
  , epochNonce :: Maybe Nonce
  , announcementHeader :: Maybe Text.Text
  , clearAnnouncement :: Maybe Bool
  , lastSlot :: Maybe Word64
  , originLastSlot :: Maybe Bool
  , tipBlock :: Maybe Word64
  , tipSlot :: Maybe Word64
  , originTip :: Maybe Bool
  , skipTick :: Maybe Bool
  , operation :: Maybe Operation
  , maxMajor :: Maybe Version
  , kesPeriodSlots :: Maybe Word64
  , maxKesEvolutions :: Maybe Word64
  , checkpointHash :: Maybe (Hash.Hash Hash.Blake2b_256 ())
  , checkpointBlock :: Maybe Word64
  } deriving (Generic)
instance FromJSON Options

data InputError = InvalidHex String | InvalidHeader String | MissingAnnouncement | PrefixRefused Int (HeaderError Block)
  deriving (Show)

option :: (Options -> Maybe a) -> Maybe Options -> a -> a
option field opts fallback = fromMaybe fallback (opts >>= field)

poolDistribution :: [PoolInput] -> Either InputError PoolDistr
poolDistribution ps = do
  pure $ PoolDistr
    (Map.fromList [(pool p, IndividualPoolStake (fromMaybe (toInteger (stake p) % totalStake) (share p)) (CompactCoin (stake p)) (vrf p) SNothing) | p <- ps])
    (nonZeroOr (Coin totalStake) (knownNonZeroCoin @1))
  where
    totalStake = max 1 (sum (map (toInteger . stake) ps))

decodeHeader :: Text.Text -> Either InputError (Header Block)
decodeHeader text = do
  bytes <- either (Left . InvalidHex) Right $ Hex.decode $ Text.encodeUtf8 text
  raw <- either (Left . InvalidHeader . show) Right $
    Binary.decodeFullAnnotator (natVersion @12) "Leios header" Binary.decCBOR (LBS.fromStrict bytes)
  pure $ mkShelleyHeader raw

-- Public deterministic test seeds only. The native test-only pure KES API is deliberately used
-- here; this generator never reads, writes or handles operational signing keys.
resign :: (Text.Text, Word64) -> Either InputError Text.Text
resign (template, sequenceNo) = do
  original <- shelleyHeaderRaw <$> decodeHeader template
  let cold = DSIGN.genKeyDSIGN @DSIGN (mkSeedFromBytes (BS.replicate 32 81))
      hot = KES.unsoundPureGenKeyKES @(KES StandardCrypto) (mkSeedFromBytes (BS.replicate 32 82))
      hotVk = KES.unsoundPureDeriveVerKeyKES hot
      certificate :: OCert StandardCrypto
      certificate = OCert hotVk sequenceNo (KESPeriod 0)
        (DSIGN.signedDSIGN () (OCertSignable hotVk sequenceNo (KESPeriod 0)) cold)
      raw = (getMemoRawType (Leios.headerBody original))
        { Leios.hbrVk = VKey (DSIGN.deriveVerKeyDSIGN cold)
        , Leios.hbrOCert = certificate
        }
      body = Leios.mkHeaderBody (Proxy @DijkstraEra) raw
      signed = Leios.mkHeader (Proxy @DijkstraEra) body (KES.unsoundPureSignedKES () 0 body hot)
  pure (Text.decodeUtf8 (Hex.encode (originalBytes signed)))

stateValue :: PolyPraosState Protocol -> Value
stateValue st = object
  [ "lastSlot" .= case praosStateLastSlot st of Origin -> Nothing; NotOrigin s -> Just s
  , "counters" .= praosStateOCertCounters st
  , "evolvingNonce" .= praosStateEvolvingNonce st
  , "candidateNonce" .= praosStateCandidateNonce st
  , "epochNonce" .= praosStateEpochNonce st
  , "previousEpochNonce" .= praosStatePreviousEpochNonce st
  , "labNonce" .= praosStateLabNonce st
  , "lastEpochBlockNonce" .= praosStateLastEpochBlockNonce st
  , "announcement" .= case praosStateLeiosAnnouncement st of
      Praos2HasLeios SNothing -> Nothing
      Praos2HasLeios (SJust (MkAnnouncedBy issuer a)) -> Just $ object
        [ "issuer" .= issuer
        , "hash" .= Hash.hashToTextAsHex (extractHash (ebReferencesAnnouncementHash a))
        , "size" .= ebReferencesAnnouncementSize a
        ]
  ]

failure :: HeaderError Block -> Value
failure err = object ["verdict" .= verdict, "referenceDetail" .= show err]
  where
    tagged tag args = object ["tag" .= (tag :: String), "args" .= (args :: [Value])]
    hashValue (ShelleyHash h) = toJSON (Hash.hashToTextAsHex h)
    oldHash Origin = toJSON (Nothing :: Maybe Text.Text)
    oldHash (NotOrigin h) = hashValue h
    chainHash GenesisHash = toJSON (Nothing :: Maybe Text.Text)
    chainHash (BlockHash h) = hashValue h
    verdict = case err of
      HeaderEnvelopeError e -> case e of
        UnexpectedBlockNo wanted got -> tagged "UnexpectedBlockNo" [toJSON wanted, toJSON got]
        UnexpectedSlotNo wanted got -> tagged "UnexpectedSlotNo" [toJSON wanted, toJSON got]
        UnexpectedPrevHash wanted got -> tagged "UnexpectedPrevHash" [oldHash wanted, chainHash got]
        CheckpointMismatch n wanted got -> tagged "CheckpointMismatch" [toJSON n, hashValue wanted, hashValue got]
        OtherHeaderEnvelopeError other -> case other of
          Envelope.ObsoleteNode v bound -> tagged "ObsoleteNode" [toJSON v, toJSON bound]
          Envelope.HeaderSizeTooLarge n bound -> tagged "HeaderSizeTooLarge" [toJSON n, toJSON bound]
          Envelope.BlockSizeTooLarge n bound -> tagged "BlockSizeTooLarge" [toJSON n, toJSON bound]
      HeaderProtocolError e -> case e of
        PP.LeiosEbTooBig _ n bound -> tagged "LeiosEbTooBig" [toJSON n, toJSON bound]
        PP.LeiosCertWithoutAnnouncement _ -> tagged "LeiosCertWithoutAnnouncement" []
        PP.LeiosCertTooYoung _ a s first -> tagged "LeiosCertTooYoung" [toJSON a, toJSON s, toJSON first]
        PP.KESBeforeStartOCERT (KESPeriod start) (KESPeriod current) -> tagged "KESBeforeStartOCERT" [toJSON start, toJSON current]
        PP.KESAfterEndOCERT (KESPeriod current) (KESPeriod start) bound -> tagged "KESAfterEndOCERT" [toJSON current, toJSON start, toJSON bound]
        PP.CounterTooSmallOCERT old new -> tagged "CounterTooSmallOCERT" [toJSON old, toJSON new]
        PP.CounterOverIncrementedOCERT old new -> tagged "CounterOverIncrementedOCERT" [toJSON old, toJSON new]
        PP.NoCounterForKeyHashOCERT key -> tagged "NoCounterForKeyHashOCERT" [toJSON key]
        PP.InvalidSignatureOCERT n (KESPeriod start) _ -> tagged "InvalidSignatureOCERT" [toJSON n, toJSON start]
        PP.InvalidKesSignatureOCERT current start elapsed bound _ -> tagged "InvalidKesSignatureOCERT" [toJSON current, toJSON start, toJSON elapsed, toJSON bound]
        PP.VRFKeyUnknown key -> tagged "VRFKeyUnknown" [toJSON key]
        PP.VRFKeyWrongVRFKey key registered actual -> tagged "VRFKeyWrongVRFKey" [toJSON key, toJSON (Hash.hashToTextAsHex registered), toJSON (Hash.hashToTextAsHex actual)]
        PP.VRFKeyBadProof slot nonce _ -> tagged "VRFKeyBadProof" [toJSON slot, toJSON nonce]
        PP.VRFLeaderValueTooBig value fraction f -> tagged "VRFLeaderValueTooBig" [toJSON value, toJSON fraction, toJSON (activeSlotVal f)]

overrideState :: Maybe Options -> Header Block -> HeaderState Block -> Either InputError (HeaderState Block)
overrideState opts hdr st = do
  ann <- case opts >>= announcementHeader of
    Nothing -> pure (praosStateLeiosAnnouncement p)
    Just text -> do
      h <- shelleyHeaderRaw <$> decodeHeader text
      let body = Leios.headerBody h
      case Leios.hbEbReferencesAnnouncement body of
        SNothing -> Left MissingAnnouncement
        SJust a -> pure $ Praos2HasLeios (SJust (MkAnnouncedBy (SL.hashKey (Leios.hbVk body)) a))
  let next = p
        { praosStateLastSlot = if option originLastSlot opts False then Origin else maybe (praosStateLastSlot p) (NotOrigin . SlotNo) (opts >>= lastSlot)
        , praosStateOCertCounters = maybe (praosStateOCertCounters p) (\n -> Map.insert issuer n (praosStateOCertCounters p)) (opts >>= counter)
        , praosStateEpochNonce = option epochNonce opts (praosStateEpochNonce p)
        , praosStateLeiosAnnouncement = if option clearAnnouncement opts False then Praos2HasLeios SNothing else ann
        }
      tip = if option originTip opts False then Origin else fmap (\t -> t
        { HV.annTipBlockNo = option (fmap BlockNo . tipBlock) opts (HV.annTipBlockNo t)
        , HV.annTipSlotNo = option (fmap SlotNo . tipSlot) opts (HV.annTipSlotNo t)
        }) (headerStateTip st)
  pure $ HeaderState tip next
  where
    p = headerStateChainDep st
    issuer = SL.hashKey (Leios.hbVk (Leios.headerBody (shelleyHeaderRaw hdr)))

run :: BS.ByteString -> SL.ShelleyGenesis -> DijkstraGenesis -> Input -> Either InputError Value
run genesisBytes genesis dijkstra input = do
  distribution <- poolDistribution (pools input)
  hs <- traverse decodeHeader (headers input)
  let baseView = Views.PraosLedgerView distribution 1100 90112 (ProtVer (natVersion @12) 0)
        (Praos2HasLeios emptyLeiosCommittee) (Praos2HasLeios maxBound)
        (Praos2HasLeios (Milliseconds32 1000)) (Praos2HasLeios (Milliseconds32 4000))
        (Praos2HasLeios (Milliseconds32 7000)) (Praos2HasLeios 40000) (Praos2HasLeios 260000)
      nonce = Nonce (Hash.castHash (Hash.hashWith id genesisBytes))
      initial = genesisHeaderState $ PraosState Origin Map.empty nonce nonce nonce nonce NeutralNonce NeutralNonce (Praos2HasLeios SNothing)
      step (st, results) (index, hdr) = do
        let opts = if index == length hs - 1 then options input else Nothing
        pd <- maybe (pure distribution) poolDistribution (opts >>= poolOverride)
        let lv = baseView
              { Views.plvPoolDistr = pd
              , Views.plvProtocolVersion = ProtVer (option major opts (natVersion @12)) 0
              , Views.plvMaxHeaderSize = option headerLimit opts 1100
              , Views.plvMaxBodySize = option bodyLimit opts 90112
              , Views.plvMaxEbBodySize = Praos2HasLeios (option ebLimit opts 40000)
              , Views.plvAnnouncementPeriodLength = Praos2HasLeios (Milliseconds32 (option announcementMillis opts 1000))
              , Views.plvVotePeriodLength = Praos2HasLeios (Milliseconds32 (option voteMillis opts 4000))
              , Views.plvDiffusionPeriodLength = Praos2HasLeios (Milliseconds32 (option diffusionMillis opts 7000))
              }
            cfg = config opts
        prior <- overrideState opts hdr st
        let ticked = if option skipTick opts False
              then HV.TickedHeaderState (headerStateTip prior) (PP.TickedPraosState (headerStateChainDep prior) lv)
              else tickHeaderState (configConsensus cfg) lv (blockSlot hdr) prior
            accept next = pure (next, object ["index" .= index, "accepted" .= True, "state" .= stateValue (headerStateChainDep next)] : results)
            checked action = case runExcept action of
              Left err -> if index < length hs - 1
                then Left (PrefixRefused index err)
                else pure (st, object ["index" .= index, "accepted" .= False, "failure" .= failure err] : results)
              Right next -> accept next
        case option operation opts Validate of
          Validate -> checked (validateHeader cfg lv hdr ticked)
          ProtocolOnly -> checked (withExcept HeaderProtocolError (validateHeaderProtocol cfg hdr ticked))
          TickOnly -> accept (prior {headerStateChainDep = PP.tickedPraosStateChainDepState (HV.tickedHeaderStateChainDep ticked)})
          Reupdate -> accept (prior {headerStateChainDep = reupdateChainDepState (configConsensus cfg) (protocolHeaderView (shelleyHeaderRaw hdr)) (blockSlot hdr) (HV.tickedHeaderStateChainDep ticked)})
          CertificationSlot -> pure (prior, object
            [ "index" .= index
            , "earliest" .= Timing.minCertificationSlot
                (mkSlotLength (maybe (SL.fromNominalDiffTimeMicro (SL.sgSlotLength genesis)) (\us -> fromIntegral us / 1000000) (opts >>= slotMicros)))
                (Milliseconds32 (option announcementMillis opts 1000))
                (Milliseconds32 (option voteMillis opts 4000))
                (Milliseconds32 (option diffusionMillis opts 7000))
                (SlotNo (option lastSlot opts 0))
            ] : results)
  (_, results) <- foldM step (initial, []) (zip [0 :: Int ..] hs)
  pure $ case name input of
    Nothing -> object ["headers" .= reverse results]
    Just label -> object ["name" .= label, "result" .= take 1 results]
  where
    ei opts = fixedEpochInfo (SL.sgEpochLength genesis) (mkSlotLength (maybe (SL.fromNominalDiffTimeMicro (SL.sgSlotLength genesis)) (\us -> fromIntegral us / 1000000) (opts >>= slotMicros)))
    params opts = PraosParams
      (option kesPeriodSlots opts (SL.sgSlotsPerKESPeriod genesis))
      (SL.sgActiveSlotCoeff genesis)
      (SecurityParam (SL.sgSecurityParam genesis))
      (option maxKesEvolutions opts (SL.sgMaxKESEvolutions genesis))
      (MaxMajorProtVer (option maxMajor opts (natVersion @12)))
      (SL.computeRandomnessStabilisationWindow (unNonZero (SL.sgSecurityParam genesis)) (SL.sgActiveSlotCoeff genesis))
    config :: Maybe Options -> TopLevelConfig Block
    config opts = mkTopLevelConfig (LeiosConfig (PraosConfig (params opts) (ei opts)))
      (mkShelleyLedgerConfig genesis dijkstra (ei opts))
      (mkShelleyBlockConfig (ProtVer (natVersion @12) 0) genesis [])
      ShelleyCodecConfig
      (ShelleyStorageConfig (SL.sgSlotsPerKESPeriod genesis) (SecurityParam (SL.sgSecurityParam genesis)))
      (maybe emptyCheckpointsMap (\h -> CheckpointsMap (Map.singleton (BlockNo (option checkpointBlock opts 0)) (ShelleyHash (Hash.castHash h)))) (opts >>= checkpointHash))

main :: IO ()
main = do
  cryptoInit
  args <- getArgs
  case args of
    ["--sign", inputPath] -> do
      templates <- LBS.readFile inputPath >>= either die pure . eitherDecode @[(Text.Text, Word64)]
      either (die . show) (LBS.putStr . encode) (traverse resign templates)
    [shelleyPath, dijkstraPath, inputPath] -> execute False shelleyPath dijkstraPath inputPath
    [shelleyPath, dijkstraPath, inputPath, "--batch"] -> execute True shelleyPath dijkstraPath inputPath
    _ -> die "expected Shelley genesis, Dijkstra genesis and input JSON paths, optionally --batch"
  where
    execute batch shelleyPath dijkstraPath inputPath = do
      genesisBytes <- BS.readFile shelleyPath
      genesis <- either die pure $ eitherDecode $ LBS.fromStrict genesisBytes
      dijkstra <- LBS.readFile dijkstraPath >>= either die pure . eitherDecode
      if batch then do
        inputs <- LBS.readFile inputPath >>= either die pure . eitherDecode @[Input]
        either (die . show) (LBS.putStr . encode) (traverse (run genesisBytes genesis dijkstra) inputs)
      else do
        input <- LBS.readFile inputPath >>= either die pure . eitherDecode
        either (die . show) (LBS.putStr . encode) (run genesisBytes genesis dijkstra input)
