{-# LANGUAGE DataKinds #-}
{-# LANGUAGE TypeApplications #-}
{-# LANGUAGE ImportQualifiedPost #-}
-- SPDX-License-Identifier: Apache-2.0
module Main where
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Conway.Core
import Cardano.Ledger.BaseTypes
import Cardano.Ledger.Shelley.API.Mempool (ApplyTx(..))
import Cardano.Ledger.Shelley.LedgerState (UTxOState(..))
import Cardano.Ledger.Shelley.Rules (UtxoEnv(..))
import Cardano.Ledger.Shelley.UTxO (UTxO(..))
import Cardano.Ledger.TxIn (TxIn(..), TxIx(..))
import Data.Set qualified as Set
import Data.Map.Strict qualified as Map
import Lens.Micro
import Test.Cardano.Ledger.Api.DebugTools (readCBOR, readCBORNoShare, readCBORAnnotated, writeCBOR)
import Test.Cardano.Ledger.Shelley.Utils (runSTS)
import Test.Cardano.Ledger.Core.Utils (testGlobals)

main :: IO ()
main = do
  let source = "/probe/observations-lawful/utxo-274101"
      target = "/probe/standalone-utxo-missing-collateral"
      version = eraProtVerLow @ConwayEra
  env <- readCBOR version (source <> "/environment.cbor") :: IO (UtxoEnv ConwayEra)
  st <- readCBORNoShare version (source <> "/state.cbor") :: IO (UTxOState ConwayEra)
  old <- readCBORAnnotated version (source <> "/signal.cbor") :: IO (Tx TopTx ConwayEra)
  let missing = TxIn (txIdTx old) (TxIx 65535)
      UTxO entries = utxosUtxo st
      tx = old & bodyTxL . collateralInputsTxBodyL .~ Set.singleton missing
      sig = mkStAnnTx (epochInfo testGlobals) (systemStart testGlobals) (uePParams env) (utxosUtxo st) mempty tx
  print (Map.notMember missing entries)
  print (old ^. witsTxL . rdmrsTxWitsL)
  writeCBOR version (target <> "/environment.cbor") env
  writeCBOR version (target <> "/state.cbor") st
  writeCBOR version (target <> "/signal.cbor") tx
  case runSTS @"UTXO" @ConwayEra testGlobals env st sig of
    Left failures -> do
      print failures
      writeCBOR version (target <> "/native-failures.cbor") failures
    Right _ -> putStrLn "UNEXPECTED ACCEPTANCE"
