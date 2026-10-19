-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE TypeApplications #-}
module Main where

import Cardano.Ledger.Binary qualified as Binary
import Cardano.Ledger.CanonicalState.Conway (CanonicalGovActionState, fromGovActionState, mkGovProposalIn, toGovActionState)
import Cardano.Ledger.CanonicalState.Conway.Export ()
import Cardano.Ledger.CanonicalState.Namespace.GovProposals.V0 (GovProposalOut)
import Cardano.SCLS.CBOR.Canonical (getRawDecoder, getRawEncoding)
import Cardano.SCLS.CBOR.Canonical.Decoder (FromCanonicalCBOR (..))
import Cardano.SCLS.CBOR.Canonical.Encoder (ToCanonicalCBOR (..))
import Cardano.SCLS.Versioned (Versioned (..))
import Codec.CBOR.Read qualified as Read
import Codec.CBOR.Write (toStrictByteString)
import Data.ByteString qualified as BS
import Data.ByteString.Base16 qualified as Base16
import Data.ByteString.Char8 qualified as BSC
import Data.ByteString.Lazy qualified as BL
import Data.Proxy (Proxy (..))
import System.Exit (die)

main :: IO ()
main = do
  rows <- lines <$> readFile "/probe/proposal-roundtrip-input.tsv"
  encoded <- case [value | row <- rows, [_, "gov/proposals/v0", value] <- [words row]] of
    [value] -> either (die . show) pure (Base16.decode (BSC.pack value))
    _ -> die "expected one proposal reference vector"
  (remaining, Versioned original) <- either (die . show) pure $
    Read.deserialiseFromBytes
      (getRawDecoder (fromCanonicalCBOR @"gov/proposals/v0" @(GovProposalOut CanonicalGovActionState)))
      (BL.fromStrict encoded)
  if BL.null remaining then pure () else die "trailing input"
  actionId <- either (die . show) pure $
    Binary.decodeFull' (Binary.natVersion @9) (BS.pack [0x82, 0x58, 32] <> BS.replicate 32 0 <> BS.pack [7])
  let (order, ledgerState) = toGovActionState (mkGovProposalIn actionId, original)
      (_, projected) = fromGovActionState order ledgerState
      result = toStrictByteString . getRawEncoding $ toCanonicalCBOR (Proxy @"gov/proposals/v0") projected
  putStrLn ("original\t" <> BSC.unpack (Base16.encode encoded))
  putStrLn ("via-ledger-state\t" <> BSC.unpack (Base16.encode result))
  putStrLn ("byte-equal\t" <> show (encoded == result))
