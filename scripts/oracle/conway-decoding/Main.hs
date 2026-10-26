-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE AllowAmbiguousTypes #-}
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE DeriveGeneric #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

import Cardano.Ledger.Address (Withdrawals (..), serialiseAccountAddress)
import Cardano.Ledger.Allegra (AllegraEra)
import Cardano.Ledger.Alonzo (AlonzoEra)
import Cardano.Ledger.Babbage (BabbageEra)
import qualified Cardano.Ledger.Binary as Binary
import Cardano.Ledger.Coin (Coin (..))
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Core (EraTxBody, Tx, TopTx, TxBody, TxWits, withdrawalsTxBodyL)
import Cardano.Ledger.Mary (MaryEra)
import Cardano.Ledger.Shelley (ShelleyEra)
import Data.Aeson (FromJSON, Value, eitherDecode, encode, object, (.=))
import qualified Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy as LBS
import qualified Data.Map.Strict as Map
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import GHC.Generics (Generic)
import Lens.Micro ((^.))
import System.Environment (getArgs)
import System.Exit (die)

-- | One original encoding: `kind` is "body", "witnesses" or "transaction" (Conway only); `era` is
-- an era name, decoded at its `eraProtVerLow` (Shelley 2, Allegra 3, Mary 4, Alonzo 5, Babbage 7,
-- Conway 9). With `withdrawals` set, an accepted body also reports the decoded withdrawals.
data Case = Case
  { name :: String
  , era :: String
  , kind :: String
  , bytes :: Text.Text
  , withdrawals :: Maybe Bool
  }
  deriving (Generic)

instance FromJSON Case

verdict :: Either Binary.DecoderError a -> Value
verdict (Right _) = object ["accepted" .= True]
verdict (Left failure) = object ["accepted" .= False, "failure" .= show failure]

decodeAs :: forall a. Binary.DecCBOR (Binary.Annotator a) => Binary.Version -> String -> LBS.ByteString -> Value
decodeAs version label raw = verdict (Binary.decodeFullAnnotator version (Text.pack label) Binary.decCBOR raw :: Either Binary.DecoderError a)

-- | The verdict on a body and, when it is accepted, its withdrawals as decoded: each reward account
-- (hex of its 29 bytes) with its amount, in the map's order.
bodyWithdrawals :: forall era. EraTxBody era => Binary.Version -> String -> LBS.ByteString -> Value
bodyWithdrawals version label raw =
  case Binary.decodeFullAnnotator version (Text.pack label) Binary.decCBOR raw :: Either Binary.DecoderError (TxBody TopTx era) of
    Left failure -> verdict (Left failure :: Either Binary.DecoderError ())
    Right body ->
      object
        [ "accepted" .= True
        , "withdrawals" .= [ (Text.decodeUtf8 (Hex.encode (serialiseAccountAddress k)), unCoin v)
                           | (k, v) <- Map.toList (unWithdrawals (body ^. withdrawalsTxBodyL)) ]
        ]

runCase :: Case -> IO Value
runCase c = do
  raw <- either die (pure . LBS.fromStrict) (Hex.decode (Text.encodeUtf8 (bytes c)))
  let body_ = kind c == "body"
      wits_ = kind c == "witnesses"
      wdrl = body_ && withdrawals c == Just True
  result <- case (era c, kind c) of
    ("conway", _) | wdrl -> pure (bodyWithdrawals @ConwayEra (Binary.natVersion @9) "Conway TxBody" raw)
    ("babbage", _) | wdrl -> pure (bodyWithdrawals @BabbageEra (Binary.natVersion @7) "Babbage TxBody" raw)
    ("alonzo", _) | wdrl -> pure (bodyWithdrawals @AlonzoEra (Binary.natVersion @5) "Alonzo TxBody" raw)
    ("mary", _) | wdrl -> pure (bodyWithdrawals @MaryEra (Binary.natVersion @4) "Mary TxBody" raw)
    ("allegra", _) | wdrl -> pure (bodyWithdrawals @AllegraEra (Binary.natVersion @3) "Allegra TxBody" raw)
    ("shelley", _) | wdrl -> pure (bodyWithdrawals @ShelleyEra (Binary.natVersion @2) "Shelley TxBody" raw)
    ("conway", "transaction") -> pure (decodeAs @(Tx TopTx ConwayEra) (Binary.natVersion @9) "Conway Tx" raw)
    ("conway", _) | body_ -> pure (decodeAs @(TxBody TopTx ConwayEra) (Binary.natVersion @9) "Conway TxBody" raw)
    ("conway", _) | wits_ -> pure (decodeAs @(TxWits ConwayEra) (Binary.natVersion @9) "Conway TxWits" raw)
    ("babbage", _) | body_ -> pure (decodeAs @(TxBody TopTx BabbageEra) (Binary.natVersion @7) "Babbage TxBody" raw)
    ("babbage", _) | wits_ -> pure (decodeAs @(TxWits BabbageEra) (Binary.natVersion @7) "Babbage TxWits" raw)
    ("alonzo", _) | body_ -> pure (decodeAs @(TxBody TopTx AlonzoEra) (Binary.natVersion @5) "Alonzo TxBody" raw)
    ("alonzo", _) | wits_ -> pure (decodeAs @(TxWits AlonzoEra) (Binary.natVersion @5) "Alonzo TxWits" raw)
    ("mary", _) | body_ -> pure (decodeAs @(TxBody TopTx MaryEra) (Binary.natVersion @4) "Mary TxBody" raw)
    ("mary", _) | wits_ -> pure (decodeAs @(TxWits MaryEra) (Binary.natVersion @4) "Mary TxWits" raw)
    ("allegra", _) | body_ -> pure (decodeAs @(TxBody TopTx AllegraEra) (Binary.natVersion @3) "Allegra TxBody" raw)
    ("allegra", _) | wits_ -> pure (decodeAs @(TxWits AllegraEra) (Binary.natVersion @3) "Allegra TxWits" raw)
    ("shelley", _) | body_ -> pure (decodeAs @(TxBody TopTx ShelleyEra) (Binary.natVersion @2) "Shelley TxBody" raw)
    ("shelley", _) | wits_ -> pure (decodeAs @(TxWits ShelleyEra) (Binary.natVersion @2) "Shelley TxWits" raw)
    other -> die ("unsupported case " <> show other)
  pure (object ["name" .= name c, "era" .= era c, "kind" .= kind c, "result" .= result])

main :: IO ()
main = do
  args <- getArgs
  cases <- case args of
    [p] -> (LBS.readFile p >>= either die pure . eitherDecode) :: IO [Case]
    _ -> die "one input JSON path required"
  traverse runCase cases >>= LBS.putStr . encode
