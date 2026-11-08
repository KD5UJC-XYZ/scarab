-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE DeriveGeneric #-}
{-# LANGUAGE LambdaCase #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE PackageImports #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TypeApplications #-}

-- The verdicts of the Dijkstra rules of cardano-ledger 9df9f98c (the revision cardano-node
-- b004fe0c pins, the build of the public Leios testnet) on the cases `run.py` builds, each rule
-- applied on its own by `applySTS` with every check run (`ValidateAll`), as the small-steps
-- interpreter applies a rule (`libs/small-steps/src/Control/State/Transition/Extended.hs`):
--
--   kind "bbody": BBODY (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/Bbody.hs`) over a
--     block, `[8, block]` as BlockFetch carries it, on the default ledger state, with the
--     parameters of the case and a chain account of zero;
--   kind "certs": CERTS (Conway's, with Dijkstra's CERT, POOL and GOVCERT) over the certificates of
--     the case on the default certificate state, in the current epoch of the case;
--   kind "gov": CERTS over `setup` first, which must pass, then GOV
--     (`Dijkstra/Rules/Gov.hs`, Conway's transition) over the votes and proposals of the case on
--     the certificate state CERTS left and the default store of proposals, for a transaction whose
--     id is 32 bytes of 6.
--
-- A verdict is the rule's acceptance, or each predicate failure of its refusal as its `EncCBOR`
-- at version 12 and as `show` renders it, in the order `applySTS` returns them.
module Main (main) where

import Cardano.Binary (DecoderError (..), enforceSize)
import qualified Cardano.Crypto.Hash as Hash
import Cardano.Ledger.BaseTypes (BlocksMade (..), EpochInterval (..), Globals (..), Network (Testnet), ProtVer (..), StrictMaybe (..), boundRational, mkActiveSlotCoeff, mkVersion, natVersion, unsafeNonZero)
import qualified Cardano.Ledger.Binary as Binary
import qualified Cardano.Ledger.Binary.Plain as Plain
import Cardano.Ledger.Block (Block (..), LeiosBbodySignal (..))
import Cardano.Ledger.Alonzo.PParams (ppMaxBlockExUnitsL)
import Cardano.Ledger.Coin (Coin (..))
import Cardano.Ledger.Conway.Governance (ProposalProcedure, VotingProcedures (..))
import Cardano.Ledger.Conway.PParams (ppDRepDepositL, ppGovActionDepositL, ppGovActionLifetimeL)
import qualified Cardano.Ledger.Conway.Rules as Conway
import Cardano.Ledger.Core (PParams, TxCert, emptyPParams, eraDecoder, ppEMaxL, ppKeyDepositL, ppMinPoolCostL, ppPoolDepositL, ppProtocolVersionL)
import Cardano.Ledger.Dijkstra (DijkstraEra)
import Cardano.Ledger.Dijkstra.Era (BBODY, GOV)
import Cardano.Ledger.Dijkstra.PParams (ppMaxRefScriptSizePerBlockL)
import Cardano.Ledger.Hashes (unsafeMakeSafeHash)
import Cardano.Ledger.Plutus.ExUnits (ExUnits (..))
import Cardano.Ledger.Shelley.LedgerState (LedgerState)
import qualified Cardano.Ledger.Shelley.Rules as Shelley
import Cardano.Ledger.State (CertState, ChainAccountState (..))
import Cardano.Ledger.TxIn (TxId (..))
import Cardano.Protocol.Crypto (StandardCrypto)
import qualified Cardano.Protocol.Leios.BlockHeader as Leios
import Cardano.Slotting.EpochInfo (fixedEpochInfo)
import Cardano.Slotting.Slot (EpochNo (..), EpochSize (..))
import Cardano.Slotting.Time (SystemStart (..), mkSlotLength)
import qualified Codec.CBOR.Decoding as CBOR
import Control.Monad.Reader (runReader)
import Control.State.Transition.Extended (TRC (..), applySTS)
import Data.Aeson (FromJSON, Value, eitherDecode, encode, object, (.=))
import Data.Aeson.Types (Pair)
import Data.List.NonEmpty (NonEmpty)
import qualified Data.ByteString as BS
import qualified "base16-bytestring" Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy as LBS
import Data.Default (def)
import Data.Foldable (toList)
import qualified Data.Map.Strict as Map
import qualified Data.OSet.Strict as OSet
import qualified Data.Sequence as Seq
import qualified Data.Sequence.Strict as SSeq
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import Data.Time.Clock.POSIX (posixSecondsToUTCTime)
import Data.Word (Word32, Word64)
import GHC.Generics (Generic)
import Lens.Micro ((&), (.~))
import Numeric.Natural (Natural)
import System.Environment (getArgs)
import System.Exit (die)

data Params = Params
  { major :: Natural
  , maxBlockExUnits :: (Natural, Natural)
  , maxRefScriptSizePerBlock :: Word32
  , keyDeposit :: Integer
  , poolDeposit :: Integer
  , drepDeposit :: Integer
  , govActionDeposit :: Integer
  , govActionLifetime :: Word32
  , eMax :: Word32
  , minPoolCost :: Integer
  }
  deriving (Generic)

instance FromJSON Params

data Case = Case
  { name :: String
  , kind :: String
  , params :: Params
  , epoch :: Word64
  , block :: Maybe Text.Text
  , certs :: Maybe [Text.Text]
  , setup :: Maybe [Text.Text]
  , votes :: Maybe Text.Text
  , proposals :: Maybe Text.Text
  }
  deriving (Generic)

instance FromJSON Case

type LeiosHeader = Leios.Header StandardCrypto

globals :: Globals
globals =
  Globals
    (fixedEpochInfo (EpochSize 100) (mkSlotLength 1))
    100
    100
    100
    (unsafeNonZero 5)
    62
    5
    45000000000000000
    (mkActiveSlotCoeff (maybe (error "f") id (boundRational 0.05)))
    Testnet
    (SystemStart (posixSecondsToUTCTime 0))

pparams :: Params -> PParams DijkstraEra
pparams p =
  emptyPParams
    & ppProtocolVersionL .~ ProtVer (maybe (error "major") id (mkVersion (major p))) 0
    & ppMaxBlockExUnitsL .~ ExUnits (fst (maxBlockExUnits p)) (snd (maxBlockExUnits p))
    & ppMaxRefScriptSizePerBlockL .~ maxRefScriptSizePerBlock p
    & ppKeyDepositL .~ Coin (keyDeposit p)
    & ppPoolDepositL .~ Coin (poolDeposit p)
    & ppDRepDepositL .~ Coin (drepDeposit p)
    & ppGovActionDepositL .~ Coin (govActionDeposit p)
    & ppGovActionLifetimeL .~ EpochInterval (govActionLifetime p)
    & ppEMaxL .~ EpochInterval (eMax p)
    & ppMinPoolCostL .~ Coin (minPoolCost p)

hexBytes :: BS.ByteString -> Text.Text
hexBytes = Text.decodeUtf8 . Hex.encode

unhex :: Text.Text -> BS.ByteString
unhex = either error id . Hex.decode . Text.encodeUtf8

-- | A value of the era at decoder version 12, the whole input read.
decode12 :: Binary.DecCBOR a => Text.Text -> a
decode12 t = either (error . show) id (Binary.decodeFull' (natVersion @12) (unhex t))

-- | `decodeDiskHfcBlock` for tag 8 (ouroboros-consensus 2416493b,
-- `Ouroboros/Consensus/Cardano/Node.hs:185-198`), as the Leios testnet oracle reads it.
dijkstraBlock :: Text.Text -> Block LeiosHeader DijkstraEra
dijkstraBlock t = either (error . show) id (Plain.decodeFullDecoder "CardanoBlock" decoder raw >>= ($ raw))
  where
    raw = LBS.fromStrict (unhex t)
    decoder :: Plain.Decoder s (LBS.ByteString -> Either DecoderError (Block LeiosHeader DijkstraEra))
    decoder = do
      enforceSize "CardanoBlock" 2
      CBOR.decodeWord >>= \case
        8 -> eraDecoder @DijkstraEra ((. Binary.Full) . Binary.runAnnotator <$> Binary.decCBOR)
        n -> Plain.cborError (DecoderErrorUnknownTag "CardanoBlock" (fromIntegral n))

-- | A rule's verdict: accepted, with what `accepted` reports of the state, or each failure.
verdict :: (Binary.EncCBOR f, Show f) => (s -> [Pair]) -> Either (NonEmpty f) s -> Value
verdict accepted = \case
  Right s -> object (("accepted" .= True) : accepted s)
  Left fs ->
    object
      [ "accepted" .= False
      , "failures" .= map (hexBytes . Binary.serialize' (natVersion @12)) (toList fs)
      , "show" .= map show (toList fs)
      ]

certsEnv :: Case -> Conway.CertsEnv DijkstraEra
certsEnv c = Conway.CertsEnv (pparams (params c)) (EpochNo (epoch c)) SNothing Map.empty

runCerts :: Case -> [Text.Text] -> CertState DijkstraEra -> Either (NonEmpty (Conway.ConwayCertsPredFailure DijkstraEra)) (CertState DijkstraEra)
runCerts c cs st =
  runReader
    (applySTS @(Conway.CERTS DijkstraEra) (TRC (certsEnv c, st, Seq.fromList (map decode12 cs :: [TxCert DijkstraEra]))))
    globals

txId :: TxId
txId = TxId (unsafeMakeSafeHash (maybe (error "hash") id (Hash.hashFromBytes (BS.replicate 32 6))))

runCase :: Case -> Value
runCase c = object ["name" .= name c, "kind" .= kind c, "reference" .= result]
  where
    pp = pparams (params c)
    result = case kind c of
      "bbody" ->
        verdict (\(Shelley.BbodyState _ (BlocksMade made)) -> ["blocksMade" .= Map.elems made]) $
          runReader
            ( applySTS @(BBODY DijkstraEra)
                ( TRC
                    ( Shelley.BbodyEnv pp (ChainAccountState (Coin 0) (Coin 0))
                    , Shelley.BbodyState (def :: LedgerState DijkstraEra) (BlocksMade Map.empty)
                    , LeiosBbodySignal (dijkstraBlock (maybe (error "block") id (block c)))
                    )
                )
            )
            globals
      "certs" -> verdict (const []) (runCerts c (maybe [] id (certs c)) def)
      "gov" ->
        let certState = either (error . show) id (runCerts c (maybe [] id (setup c)) def)
            signal =
              Conway.GovSignal
                (maybe (VotingProcedures Map.empty) decode12 (votes c))
                (maybe OSet.empty decode12 (proposals c) :: OSet.OSet (ProposalProcedure DijkstraEra))
                SSeq.empty
         in verdict (const []) $
              runReader
                ( applySTS @(GOV DijkstraEra)
                    (TRC (Conway.GovEnv txId (EpochNo (epoch c)) pp SNothing certState SNothing, def, signal))
                )
                globals
      other -> error ("unsupported kind " <> other)

main :: IO ()
main = do
  args <- getArgs
  cases <- case args of
    [p] -> (LBS.readFile p >>= either die pure . eitherDecode) :: IO [Case]
    _ -> die "one input JSON path required"
  LBS.putStr (encode (map runCase cases))
