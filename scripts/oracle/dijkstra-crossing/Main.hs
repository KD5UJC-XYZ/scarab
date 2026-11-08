-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

import Cardano.Crypto.Hash.Class (hashToBytes)
import Cardano.Ledger.Alonzo.Tx (AlonzoEraTx (..), IsPhase2Valid (..))
import Cardano.Ledger.BaseTypes (strictMaybeToMaybe)
import Cardano.Ledger.Binary (DecCBOR (..), DecoderError (..), decodeFullAnnotator, serialize')
import qualified Cardano.Ledger.Binary.Plain as Plain
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Core (
  EraTx (..),
  TopTx,
  Tx,
  TranslateEra (..),
  TxAuxData,
  TxBody,
  TxWits,
  eraProtVerLow,
  translateEraThroughCBOR,
  txIdTx,
 )
import Cardano.Ledger.Dijkstra (DijkstraEra)
import Cardano.Ledger.Dijkstra.Genesis (DijkstraGenesis)
import Cardano.Ledger.Hashes (SafeToHash (originalBytes), extractHash)
import Cardano.Ledger.TxIn (TxId (..))
import Control.Monad.Trans.Except (Except, runExcept)
import Data.Aeson (FromJSON (..), Value, eitherDecode, encode, object, withObject, (.:), (.=))
import Data.Aeson.Types (Parser)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as LBS
import Data.Char (digitToInt, isHexDigit)
import Data.Text (Text)
import qualified Data.Text as T
import Lens.Micro ((^.))
import Numeric (showHex)
import System.Environment (getArgs)
import System.Exit (die)

-- One measurement: a whole Conway transaction, its mempool form.
data Case = Case String BS.ByteString

hex :: BS.ByteString -> Text
hex = T.pack . concatMap (\w -> (if w < 16 then ('0' :) else id) (showHex w "")) . BS.unpack

unhex :: Text -> Parser BS.ByteString
unhex t = go (T.unpack t) []
  where
    go [] acc = pure (BS.pack (reverse acc))
    go (a : b : rest) acc
      | isHexDigit a && isHexDigit b = go rest (fromIntegral (digitToInt a * 16 + digitToInt b) : acc)
    go _ _ = fail "not hex"

instance FromJSON Case where
  parseJSON = withObject "case" $ \o -> Case <$> o .: "name" <*> (o .: "tx" >>= unhex)

-- The label a failure names: the part `translateEraThroughCBOR` was given
-- (`Dijkstra/Translation.hs:60-62`), or the whole transaction's.
failedPart :: DecoderError -> Maybe Text
failedPart e = case e of
  DecoderErrorCanonicityViolation l -> Just l
  DecoderErrorCustom l _ -> Just l
  DecoderErrorDeserialiseFailure l _ -> Just l
  DecoderErrorEmptyList l -> Just l
  DecoderErrorLeftover l _ -> Just l
  DecoderErrorSizeMismatch l _ _ -> Just l
  DecoderErrorUnknownTag l _ -> Just l
  DecoderErrorVoid -> Nothing

refused :: DecoderError -> Value
refused e = object ["accepted" .= False, "label" .= failedPart e, "failure" .= show e]

-- A Dijkstra transaction as the comparison reads it: each part's bytes, the flag, the id, and the
-- mempool form it is written in (`toCBORForMempoolSubmission`, `Dijkstra/Tx.hs:500-514`).
dijkstra :: Tx TopTx DijkstraEra -> Value
dijkstra tx =
  let TxId h = txIdTx tx
   in object
        [ "accepted" .= True
        , "body" .= hex (originalBytes (tx ^. bodyTxL))
        , "witnesses" .= hex (originalBytes (tx ^. witsTxL))
        , "auxiliaryData" .= fmap (hex . originalBytes) (strictMaybeToMaybe (tx ^. auxDataTxL))
        , "isValid" .= (tx ^. isPhase2ValidTxL == Phase2Valid)
        , "id" .= hex (hashToBytes (extractHash h))
        , "mempool" .= hex (serialize' (eraProtVerLow @DijkstraEra) tx)
        ]

-- `TranslateEra DijkstraEra (Tx TopTx)` (`Dijkstra/Translation.hs:53-68`) ignores its context
-- (`_ctxt`), so none is built; it would fail loudly if it were read.
context :: DijkstraGenesis
context = error "the transaction translation does not read its context"

-- Each part carried alone as `translateEraThroughCBOR` carries it (`Dijkstra/Translation.hs:60-62`,
-- `Core/Translation.hs:115-123`): a failure of an annotator is labelled "Annotator", not by the
-- part, so the parts are measured one by one as well.
parts :: Tx TopTx ConwayEra -> Value
parts tx =
  object
    [ "body" .= outcome (translateEraThroughCBOR @DijkstraEra "TxBody" (tx ^. bodyTxL) :: Except DecoderError (TxBody TopTx DijkstraEra))
    , "witnesses" .= outcome (translateEraThroughCBOR @DijkstraEra "TxWits" (tx ^. witsTxL) :: Except DecoderError (TxWits DijkstraEra))
    , "auxiliaryData" .= fmap (\a -> outcome (translateEraThroughCBOR @DijkstraEra "TxAuxData" a :: Except DecoderError (TxAuxData DijkstraEra))) (strictMaybeToMaybe (tx ^. auxDataTxL))
    ]
  where
    outcome :: Except DecoderError a -> Value
    outcome = either refused (const (object ["accepted" .= True])) . runExcept

-- What the hard-fork mempool of ouroboros-consensus 2416493b does with a held Conway transaction
-- at the crossing (`HardFork/Combinator/InjectTxs.hs`, `rematchValidatedTxs`): the transaction
-- written by Conway's `toCBOR` and read whole by Dijkstra's decoder at its version
-- (`Shelley/Ledger/Mempool.hs:299-309`).
rematch :: Tx TopTx ConwayEra -> (BS.ByteString, Value)
rematch tx =
  let bytes = Plain.serialize' tx
   in ( bytes
      , either refused dijkstra $
          decodeFullAnnotator (eraProtVerLow @DijkstraEra) "Tx" decCBOR (LBS.fromStrict bytes)
      )

run :: Case -> Value
run (Case name raw) =
  object
    [ "name" .= name
    , "reference" .= case decodeFullAnnotator (eraProtVerLow @ConwayEra) "Tx" decCBOR (LBS.fromStrict raw) of
        Left e -> object ["conway" .= refused e]
        Right (conway :: Tx TopTx ConwayEra) ->
          let (written, rematched) = rematch conway
           in object
                [ "conway" .= object ["accepted" .= True, "toCbor" .= hex written]
                , "parts" .= parts conway
                , "translateEra" .= either refused dijkstra (runExcept (translateEra @DijkstraEra context conway))
                , "rematch" .= rematched
                ]
    ]

main :: IO ()
main = do
  args <- getArgs
  cases <- case args of
    [p] -> (LBS.readFile p >>= either die pure . eitherDecode) :: IO [Case]
    _ -> die "one input JSON path required"
  LBS.putStr (encode (map run cases))
