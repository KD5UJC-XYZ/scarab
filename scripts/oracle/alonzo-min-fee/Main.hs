-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TypeApplications #-}

-- | The minimum fee of the first transaction of the cardano-node issue 4228 block, computed by the
-- released ledger as the upstream golden "Alonzo Block" computes it
-- (`eras/alonzo/test-suite/test/Test/Cardano/Ledger/Alonzo/Golden.hs:230-268`, cardano-ledger
-- f649f975): the block read with `decodeFullAnnotator` at `eraProtVerHigh @AlonzoEra`, and
-- `alonzoMinFeeTx` under `emptyPParams` with the golden's three parameters. The golden decodes the
-- whole block with its `BHeader`; that package is not in this environment, so the header is skipped
-- as one CBOR term and the block body that follows it is decoded with the same decoder at the same
-- version (`DecCBOR (Block h era)` reads the header and then the body).
module Main (main) where

import qualified Cardano.Crypto.Hash as Hash
import Cardano.Ledger.Alonzo (AlonzoEra)
import Cardano.Ledger.Alonzo.Core
import Cardano.Ledger.Alonzo.Tx (alonzoMinFeeTx, totExUnits)
import Cardano.Ledger.BaseTypes (boundRational)
import Cardano.Ledger.Binary (decCBOR, decodeFullAnnotator)
import Cardano.Ledger.Coin (Coin (..), CompactForm (CompactCoin))
import Cardano.Ledger.Plutus.ExUnits (ExUnits (..), Prices (..))
import Cardano.Ledger.TxIn (TxId (..))
import qualified Codec.CBOR.Decoding as Cbor
import qualified Codec.CBOR.Read as Cbor
import qualified Codec.CBOR.Term as Cbor
import Data.Aeson (encode, object, (.=))
import qualified Data.ByteString.Base16.Lazy as B16L
import qualified Data.ByteString.Lazy as BSL
import qualified Data.ByteString.Lazy.Char8 as BSL8
import Data.Foldable (toList)
import Data.Maybe (fromJust)
import Lens.Micro ((&), (.~), (^.))
import System.Environment (getArgs)
import System.Exit (die)

-- | The bytes of a block after its header: the five-item list's head and its first item skipped.
afterHeader :: BSL.ByteString -> Either String BSL.ByteString
afterHeader raw =
  either (Left . show) (Right . fst) $
    Cbor.deserialiseFromBytes (Cbor.decodeListLenOf 5 >> () <$ Cbor.decodeTerm) raw

main :: IO ()
main = do
  args <- getArgs
  path <- case args of
    [p] -> pure p
    _ -> die "the path of the hex block file is required"
  hex <- BSL8.filter (/= '\n') <$> BSL.readFile path
  raw <- either die pure (B16L.decode hex)
  body <- either die pure (afterHeader raw)
  blockBody <-
    either (die . show) pure $
      decodeFullAnnotator (eraProtVerHigh @AlonzoEra) "BlockBody" decCBOR body
  firstTx <- case toList ((blockBody :: BlockBody AlonzoEra) ^. txSeqBlockBodyL) of
    tx : _ -> pure (tx :: Tx TopTx AlonzoEra)
    [] -> die "the block has no transaction"
  -- the parameters of the golden, as it writes them
  let priceMem = fromJust $ boundRational 0.0577
      priceSteps = fromJust $ boundRational 0.0000721
      pricesParam = Prices priceMem priceSteps
      pp =
        emptyPParams
          & ppTxFeePerByteL .~ CoinPerByte (CompactCoin 44)
          & ppTxFeeFixedL .~ Coin 155381
          & ppPricesL .~ pricesParam
      ExUnits mem steps = totExUnits firstTx
      TxId txid = txIdTxBody (firstTx ^. bodyTxL)
  BSL8.putStrLn . encode $
    object
      [ "transactions" .= length (toList (blockBody ^. txSeqBlockBodyL))
      , "id" .= Hash.hashToTextAsHex (extractHash txid)
      , "fee" .= unCoin (firstTx ^. bodyTxL . feeTxBodyL)
      , "size" .= (firstTx ^. sizeTxF)
      , "exUnits" .= [mem, steps]
      , "minFee" .= unCoin (alonzoMinFeeTx pp firstTx)
      ]
