-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE AllowAmbiguousTypes #-}
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE DeriveGeneric #-}
{-# LANGUAGE FlexibleContexts #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TypeApplications #-}
-- The UTxO answer of the node-to-client queries (`GetUTxOWhole`, `GetUTxOByAddress`,
-- `GetUTxOByTxIn`) in each Shelley-based era: each entry is read with the era's own decoders at the
-- era's lowest protocol version, and the map is written with `toCBOR (UTxO era)`, as
-- `encodeShelleyResult` writes it (ouroboros-consensus `Shelley/Ledger/Query.hs`).
module Main (main) where

import Cardano.Ledger.Allegra (AllegraEra)
import Cardano.Ledger.Alonzo (AlonzoEra)
import Cardano.Ledger.Babbage (BabbageEra)
import qualified Cardano.Ledger.Binary as Binary
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Core (Era, TxOut, eraProtVerLow)
import Cardano.Ledger.Mary (MaryEra)
import Cardano.Ledger.Shelley (ShelleyEra)
import Cardano.Ledger.State (UTxO (..))
import Cardano.Ledger.TxIn (TxIn)
import Codec.CBOR.Write (toStrictByteString)
import Data.Aeson (FromJSON, Value, eitherDecode, encode, object, (.=))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy as LBS
import qualified Data.Map.Strict as Map
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import GHC.Generics (Generic)
import System.Environment (getArgs)
import System.Exit (die)

data Case = Case {name :: String, era :: String, entries :: [(Text.Text, Text.Text)]}
  deriving (Generic)

instance FromJSON Case

hex :: Text.Text -> Either String BS.ByteString
hex = Hex.decode . Text.encodeUtf8

answer ::
  forall era.
  (Era era, Binary.DecCBOR (TxOut era), Binary.EncCBOR (TxOut era)) =>
  [(Text.Text, Text.Text)] ->
  Either String BS.ByteString
answer es = do
  m <- traverse entry es
  pure (toStrictByteString (Binary.toCBOR (UTxO @era (Map.fromList m))))
  where
    v = eraProtVerLow @era
    entry (i, o) = do
      ib <- hex i
      ob <- hex o
      input <- either show' Right (Binary.decodeFull' v ib) :: Either String TxIn
      output <- either show' Right (Binary.decodeFull' v ob) :: Either String (TxOut era)
      pure (input, output)
    show' e = Left (show e)

runCase :: Case -> Value
runCase c =
  let r = case era c of
        "shelley" -> answer @ShelleyEra (entries c)
        "allegra" -> answer @AllegraEra (entries c)
        "mary" -> answer @MaryEra (entries c)
        "alonzo" -> answer @AlonzoEra (entries c)
        "babbage" -> answer @BabbageEra (entries c)
        "conway" -> answer @ConwayEra (entries c)
        other -> Left ("unknown era " <> other)
   in case r of
        Right bytes ->
          object (common <> ["answer" .= Text.decodeUtf8 (Hex.encode bytes)])
        Left why -> object (common <> ["refused" .= why])
  where
    common = ["name" .= name c, "era" .= era c, "entries" .= entries c]

main :: IO ()
main = do
  args <- getArgs
  path <- case args of
    [p] -> pure p
    _ -> die "usage: scarab-utxo-answers CASES.json"
  raw <- LBS.readFile path
  cases <- either die pure (eitherDecode raw) :: IO [Case]
  mapM_ (LBS.putStr . (<> "\n") . encode . runCase) cases
