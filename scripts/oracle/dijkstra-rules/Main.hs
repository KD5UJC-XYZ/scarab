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
--     block, `[8, block]` as BlockFetch carries it, on the default ledger state with the UTxO set
--     of the case, with the parameters of the case and a chain account of zero;
--   kind "utxow": UTXOW (`Dijkstra/Rules/Utxow.hs`) of a transaction as a block body holds it, on
--     the UTxO set of the case and the default certificate state, at the slot of the case;
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
import Cardano.Ledger.BaseTypes (BlocksMade (..), BoundedRational, EpochInterval (..), nonZero, Globals (..), Network (Testnet), ProtVer (..), StrictMaybe (..), boundRational, mkActiveSlotCoeff, mkVersion, natVersion, unsafeNonZero)
import qualified Cardano.Ledger.Binary as Binary
import qualified Cardano.Ledger.Binary.Plain as Plain
import Cardano.Ledger.Block (Block (..), LeiosBbodySignal (..))
import Cardano.Ledger.Alonzo.PParams (ppCollateralPercentageL, ppMaxBlockExUnitsL, ppMaxCollateralInputsL, ppMaxTxExUnitsL, ppMaxValSizeL, ppPricesL)
import Cardano.Ledger.Babbage.PParams (ppCoinsPerUTxOByteL)
import Cardano.Ledger.Coin (Coin (..), CoinPerByte (..), CompactForm (CompactCoin))
import Cardano.Ledger.Conway.Governance (ProposalProcedure, VotingProcedures (..))
import Cardano.Ledger.Conway.PParams (ppDRepDepositL, ppGovActionDepositL, ppGovActionLifetimeL, ppMinFeeRefScriptCostPerByteL)
import qualified Cardano.Ledger.Conway.Rules as Conway
import Cardano.Ledger.Core (PParams, Tx, TxCert, TxLevel (TopTx), emptyPParams, eraDecoder, ppEMaxL, ppKeyDepositL, ppMaxTxSizeL, ppMinPoolCostL, ppPoolDepositL, ppProtocolVersionL, ppTxFeeFixedL, ppTxFeePerByteL)
import Cardano.Ledger.Dijkstra (DijkstraEra, mkDijkstraStAnnTopTx)
import Cardano.Ledger.Dijkstra.Era (BBODY, GOV, UTXOW)
import Cardano.Ledger.Dijkstra.PParams (ppMaxRefScriptSizePerBlockL, ppRefScriptCostMultiplierL, ppRefScriptCostStrideL)
import Cardano.Ledger.Dijkstra.Rules (UtxoEnv (..))
import Cardano.Ledger.Dijkstra.Tx (Tx (MkDijkstraTx), decodeDijkstraTopTxInBlock)
import Cardano.Ledger.Hashes (unsafeMakeSafeHash)
import Cardano.Ledger.Plutus.ExUnits (ExUnits (..), Prices (..))
import Cardano.Ledger.Shelley.LedgerState (LedgerState (..), smartUTxOState)
import qualified Cardano.Ledger.Shelley.Rules as Shelley
import Cardano.Ledger.State (CertState, ChainAccountState (..), UTxO (..), certPStateL)
import Cardano.Ledger.TxIn (TxId (..))
import Cardano.Protocol.Crypto (StandardCrypto)
import qualified Cardano.Protocol.Leios.BlockHeader as Leios
import Cardano.Slotting.EpochInfo (fixedEpochInfo)
import Cardano.Slotting.Slot (EpochNo (..), EpochSize (..), SlotNo (..))
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
import Data.Ratio ((%))
import Data.Word (Word16, Word32, Word64)
import GHC.Generics (Generic)
import Lens.Micro ((&), (.~), (^.))
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
  , minFeeA :: Integer
  , minFeeB :: Integer
  , maxTxSize :: Word32
  , maxValSize :: Word32
  , coinsPerUTxOByte :: Word64
  , maxTxExUnits :: (Natural, Natural)
  , collateralPercentage :: Word16
  , maxCollateralInputs :: Word16
  , minFeeRefScriptCostPerByte :: Integer
  , refScriptCostStride :: Word32
  , refScriptCostMultiplier :: (Integer, Integer)
  , prices :: ((Integer, Integer), (Integer, Integer))
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
  , utxo :: Maybe [(Text.Text, Text.Text)]
  , tx :: Maybe Text.Text
  , slot :: Maybe Word64
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
    & ppTxFeePerByteL .~ CoinPerByte (CompactCoin (fromInteger (minFeeA p)))
    & ppTxFeeFixedL .~ Coin (minFeeB p)
    & ppMaxTxSizeL .~ maxTxSize p
    & ppMaxValSizeL .~ maxValSize p
    & ppCoinsPerUTxOByteL .~ CoinPerByte (CompactCoin (coinsPerUTxOByte p))
    & ppMaxTxExUnitsL .~ ExUnits (fst (maxTxExUnits p)) (snd (maxTxExUnits p))
    & ppCollateralPercentageL .~ collateralPercentage p
    & ppMaxCollateralInputsL .~ maxCollateralInputs p
    & ppMinFeeRefScriptCostPerByteL .~ bounded (minFeeRefScriptCostPerByte p, 1)
    & ppRefScriptCostStrideL .~ maybe (error "stride") id (nonZero (refScriptCostStride p))
    & ppRefScriptCostMultiplierL .~ bounded (refScriptCostMultiplier p)
    & ppPricesL .~ Prices (bounded (fst (prices p))) (bounded (snd (prices p)))
  where
    bounded :: BoundedRational r => (Integer, Integer) -> r
    bounded (n, d) = maybe (error "ratio") id (boundRational (n % d))

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

-- | The UTxO set of a case: each input and output encoded at version 12.
utxoOf :: Case -> UTxO DijkstraEra
utxoOf c = UTxO (Map.fromList [(decode12 i, decode12 o) | (i, o) <- maybe [] id (utxo c)])

-- | A transaction as a Dijkstra block body holds it (`decodeDijkstraTopTxInBlock`,
-- `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Tx.hs:175-182`).
topTx :: Text.Text -> Tx TopTx DijkstraEra
topTx t =
  MkDijkstraTx $
    either (error . show) id $
      Binary.decodeFullAnnotator (natVersion @12) "DijkstraTx" decodeDijkstraTopTxInBlock (LBS.fromStrict (unhex t))

ledgerOf :: Case -> LedgerState DijkstraEra
ledgerOf c = LedgerState (smartUTxOState (pparams (params c)) (utxoOf c) (Coin 0) (Coin 0) def (Coin 0)) def

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
                    , Shelley.BbodyState (ledgerOf c) (BlocksMade Map.empty)
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
      -- UTXOW (`Dijkstra/Rules/Utxow.hs`) of a transaction on the UTxO set of the case, with the
      -- default certificate state, at the case's slot; the transaction annotated as the ledger
      -- annotates it (`mkDijkstraStAnnTopTx`, `Dijkstra.hs:113-169`)
      "utxow" ->
        let u = utxoOf c
            annotated =
              mkDijkstraStAnnTopTx (epochInfo globals) (systemStart globals) pp u mempty (topTx (maybe (error "tx") id (tx c)))
            certState = def :: CertState DijkstraEra
         in verdict (const []) $
              runReader
                ( applySTS @(UTXOW DijkstraEra)
                    ( TRC
                        ( UtxoEnv (SlotNo (maybe 0 id (slot c))) pp (certState ^. certPStateL) certState u
                        , smartUTxOState pp u (Coin 0) (Coin 0) def (Coin 0)
                        , annotated
                        )
                    )
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
