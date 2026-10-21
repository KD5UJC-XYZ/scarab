-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE AllowAmbiguousTypes #-}
{-# LANGUAGE FlexibleContexts #-}
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE DeriveGeneric #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

import Cardano.Ledger.Shelley (ShelleyEra)
import Cardano.Ledger.Allegra (AllegraEra)
import Cardano.Ledger.Mary (MaryEra)
import Cardano.Ledger.BaseTypes (Network(..))
import qualified Cardano.Ledger.Binary as Binary
import Cardano.Ledger.Core hiding (Value)
import qualified Cardano.Ledger.Shelley.Rules as Shelley
import Cardano.Ledger.Rules.ValidationMode (Test)
import Data.Aeson (FromJSON, Value, eitherDecode, encode, object, (.=))
import qualified Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy as LBS
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import Data.Foldable (toList)
import GHC.Generics (Generic)
import Lens.Micro ((^.))
import System.Environment (getArgs)
import System.Exit (die)
import Validation (Validation(..))

data Case = Case { name :: String, protocolMajor :: Int, network :: Int, body :: Text.Text } deriving Generic
instance FromJSON Case

result :: (Show f, Binary.EncCBOR f) => Binary.Version -> Test f -> Value
result _ (Success ()) = object ["accepted" .= True]
result version (Failure errs) = object ["accepted" .= False, "failures" .= show errs, "failureCbor" .= map (Text.decodeUtf8 . Hex.encode . Binary.serialize' version) (toList errs)]

runEra :: forall e. (EraTxBody e, Binary.DecCBOR (Binary.Annotator (TxBody TopTx e)), Show (EraRuleFailure "PPUP" e), Binary.EncCBOR (EraRuleFailure "PPUP" e)) => Binary.Version -> Case -> IO Value
runEra version c = do
  bytes <- either die pure (Hex.decode (Text.encodeUtf8 (body c)))
  b <- either (die . show) pure (Binary.decodeFullAnnotator version "TxBody" Binary.decCBOR (LBS.fromStrict bytes)) :: IO (TxBody TopTx e)
  let net = if network c == 0 then Testnet else Mainnet
      outputs = b ^. outputsTxBodyL
  pure (object ["name" .= name c, "protocolMajor" .= protocolMajor c, "network" .= network c, "body" .= body c, "attributes" .= result version (Shelley.validateOutputBootAddrAttrsTooBig outputs), "addresses" .= result version (Shelley.validateWrongNetwork net outputs)])

runCase :: Case -> IO Value
runCase c = case protocolMajor c of
  2 -> runEra @ShelleyEra (Binary.natVersion @2) c
  3 -> runEra @AllegraEra (Binary.natVersion @3) c
  4 -> runEra @MaryEra (Binary.natVersion @4) c
  _ -> die "expected Shelley, Allegra or Mary"

main :: IO ()
main = do
  args <- getArgs
  cases <- case args of
    [p] -> (LBS.readFile p >>= either die pure . eitherDecode) :: IO [Case]
    _ -> die "one input JSON path required"
  traverse runCase cases >>= LBS.putStr . encode
