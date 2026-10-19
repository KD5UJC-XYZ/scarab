-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE TypeApplications #-}
{-# LANGUAGE OverloadedStrings #-}
module Main where
import Cardano.Ledger.Binary qualified as Binary
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Core (TxOut)
import Cardano.Ledger.Conway.State (utxoL)
import Cardano.Ledger.Shelley.LedgerState
import Cardano.Ledger.TxIn (TxIn)
import Cardano.Ledger.CanonicalState.Conway.Export ()
import Cardano.Ledger.CanonicalState.Conway.Import ()
import Cardano.Ledger.CanonicalState.Import (importNamespaceFromHandle)
import Cardano.SCLS.Internal.Reader (withLatestManifestFrame)
import Cardano.Ledger.CanonicalState.Export
import Cardano.SCLS.Internal.Serializer.Reference.Impl qualified as Reference
import Cardano.SCLS.Internal.Serializer.Dump.Plan (withTimestamp)
import Cardano.Types.SlotNo qualified as SCLS
import Control.Monad (forM)
import Control.Monad.Trans.Resource (runResourceT)
import Data.ByteString qualified as BS
import Data.Map.Strict qualified as Map
import Data.MemPack qualified as MemPack
import Data.MemPack.Error qualified as MemPackError
import Data.Proxy (Proxy(..))
import System.IO (withBinaryFile, IOMode(ReadMode))
import Data.Time (UTCTime(..), fromGregorian)
import System.Exit (die)
import Lens.Micro

main :: IO ()
main = do
  raw <- BS.readFile "/probe/input/nes.cbor"
  nes <- either (die . show) pure (Binary.decodeFull' (Binary.natVersion @9) raw :: Either Binary.DecoderError (NewEpochState ConwayEra))
  entries <- forM [0..2 :: Int] $ \i -> do
    k <- BS.readFile ("/probe/input/utxo-" <> show i <> ".cbor")
    v <- BS.readFile ("/probe/input/utxo-" <> show i <> ".mempack")
    txIn <- either (die . show) pure (Binary.decodeFull' (Binary.natVersion @9) k :: Either Binary.DecoderError TxIn)
    txOut <- either (die . show) pure (MemPack.unpack v :: Either MemPackError.SomeError (TxOut ConwayEra))
    pure (txIn,txOut)
  let es = nes ^. nesEpochStateL
      ls = es ^. esLStateL
      us = ls ^. lsUTxOStateL
      complete = nes & nesEpochStateL . esLStateL . lsUTxOStateL .~ us {utxosUtxo = UTxO (Map.fromList entries)}
      keySizes = Map.fromList [("utxo/v0",34),("blocks/v0",36),("gov/committee/v0",1),("gov/constitution/v0",1),("gov/pparams/v0",4),("gov/proposals/v0",34),("gov/proposals/roots/v0",1),("entities/accounts/v0",29),("entities/dreps/v0",29),("entities/committee/v0",1),("entities/stake_pools/v0",28),("entities/stake_pools/vrf_key_hashes/v0",32)]
      plan = withTimestamp (UTCTime (fromGregorian 2026 10 18) 0) (stateSerializationPlan @ConwayEra complete)
  print ("epoch",complete ^. nesELL,"externalEntries",length entries)
  result <- runResourceT $ Reference.serialize "/probe/reference-conway-4593.scls" (SCLS.SlotNo 4593) keySizes plan
  either (die . show) pure result
  putStrLn "SCLS export completed"
  withLatestManifestFrame print "/probe/reference-conway-4593.scls"
  imported <- withBinaryFile "/probe/reference-conway-4593.scls" ReadMode $ \h ->
    importNamespaceFromHandle @ConwayEra h (Proxy @"utxo/v0") complete
      >>= importNamespaceFromHandle @ConwayEra h (Proxy @"blocks/v0")
      >>= importNamespaceFromHandle @ConwayEra h (Proxy @"gov/committee/v0")
      >>= importNamespaceFromHandle @ConwayEra h (Proxy @"gov/constitution/v0")
      >>= importNamespaceFromHandle @ConwayEra h (Proxy @"gov/pparams/v0")
      >>= importNamespaceFromHandle @ConwayEra h (Proxy @"gov/proposals/roots/v0")
      >>= importNamespaceFromHandle @ConwayEra h (Proxy @"gov/proposals/v0")
      >>= importNamespaceFromHandle @ConwayEra h (Proxy @"entities/accounts/v0")
      >>= importNamespaceFromHandle @ConwayEra h (Proxy @"entities/dreps/v0")
      >>= importNamespaceFromHandle @ConwayEra h (Proxy @"entities/committee/v0")
      >>= importNamespaceFromHandle @ConwayEra h (Proxy @"entities/stake_pools/v0")
      >>= importNamespaceFromHandle @ConwayEra h (Proxy @"entities/stake_pools/vrf_key_hashes/v0")
  let fullBytes = Binary.serialize' (Binary.natVersion @9) . Binary.encCBOR
      sameFull = fullBytes complete == fullBytes imported
  print ("fullNesBytesEqual",sameFull)
  print ("fullNesTypedEqual",complete == imported)
  let residueEqual = (complete & utxoL .~ UTxO Map.empty) == (imported & utxoL .~ UTxO Map.empty)
      outputsOnChainEqual = Map.map (Binary.serialize' (Binary.natVersion @9) . Binary.encCBOR) (unUTxO (complete ^. utxoG)) == Map.map (Binary.serialize' (Binary.natVersion @9) . Binary.encCBOR) (unUTxO (imported ^. utxoG))
  print ("nonUtxoStateTypedEqual",residueEqual)
  print ("utxoOnChainBytesEqual",outputsOnChainEqual)
  BS.writeFile "/probe/input/complete-nes.cbor" (fullBytes complete)
  BS.writeFile "/probe/input/imported-nes.cbor" (fullBytes imported)
  again <- runResourceT $ Reference.serialize "/probe/reexport-conway-4593.scls" (SCLS.SlotNo 4593) keySizes (withTimestamp (UTCTime (fromGregorian 2026 10 18) 0) (stateSerializationPlan @ConwayEra imported))
  either (die . show) pure again
  before <- BS.readFile "/probe/reference-conway-4593.scls"
  after <- BS.readFile "/probe/reexport-conway-4593.scls"
  print ("fullSclsBytesEqual",before == after)
  if residueEqual && outputsOnChainEqual && before == after then pure () else die "reference per-namespace import is not a full inverse for fixture"
