-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE DeriveGeneric #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

import Cardano.Ledger.Alonzo (AlonzoEra)
import Cardano.Ledger.Alonzo.Core (ppMaxCollateralInputsL, collateralInputsTxBodyL)
import qualified Cardano.Ledger.Alonzo.Rules as Alonzo
import Cardano.Ledger.Babbage (BabbageEra)
import Cardano.Ledger.Babbage.Core (allSizedOutputsTxBodyF)
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.BaseTypes (Network(..))
import qualified Cardano.Ledger.Binary as Binary
import Cardano.Ledger.Core hiding (Value)
import Cardano.Ledger.Coin (Coin(..))
import Cardano.Ledger.State (sumAllCoin)
import Cardano.Ledger.TxIn (TxIn)
import qualified Cardano.Ledger.Shelley.Rules as Shelley
import Cardano.Ledger.Rules.ValidationMode (Test)
import Data.Aeson (FromJSON, Value, eitherDecode, encode, object, (.=))
import qualified Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy as LBS
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import qualified Data.Map.Strict as Map
import qualified Data.Sequence.Strict as Seq
import qualified Data.Set as Set
import GHC.Generics (Generic)
import Lens.Micro ((&), (.~), (^.))
import System.Environment (getArgs)
import System.Exit (die)
import Validation (Validation(..))

data Case = Case { name :: String, protocolMajor :: Int, network :: Int, body :: Text.Text } deriving (Generic)
instance FromJSON Case

result :: Show f => Test f -> Value
result (Success ()) = object ["accepted" .= True]
result (Failure errs) = object ["accepted" .= False, "failure" .= show errs]

conwayCollateral :: Set.Set TxIn -> Value
conwayCollateral inputs =
  let b = mkBasicTxBody & collateralInputsTxBodyL .~ inputs :: TxBody TopTx ConwayEra
      pp = emptyPParams & ppMaxCollateralInputsL .~ 3 :: PParams ConwayEra
  in result (Alonzo.validateTooManyCollateralInputs pp b)

runCase :: Case -> IO Value
runCase c = do
  bytes <- either die pure (Hex.decode (Text.encodeUtf8 (body c)))
  let net = if network c == 0 then Testnet else Mainnet
  (attrs, addresses, coll, count, balance, conway) <- case protocolMajor c of
    5 -> do
      b <- either (die . show) pure (Binary.decodeFullAnnotator (Binary.natVersion @5) "Alonzo TxBody" Binary.decCBOR (LBS.fromStrict bytes)) :: IO (TxBody TopTx AlonzoEra)
      let pp = emptyPParams & ppMaxCollateralInputsL .~ 3 :: PParams AlonzoEra
          outputs = b ^. outputsTxBodyL
          inputs = b ^. collateralInputsTxBodyL
      output <- maybe (die "one output required") pure (Seq.lookup 0 outputs)
      let Coin coin = sumAllCoin (Map.fromSet (const output) inputs)
      pure (result (Shelley.validateOutputBootAddrAttrsTooBig outputs), result (Shelley.validateWrongNetwork net outputs), result (Alonzo.validateTooManyCollateralInputs pp b), Set.size inputs, coin, conwayCollateral inputs)
    7 -> do
      b <- either (die . show) pure (Binary.decodeFullAnnotator (Binary.natVersion @7) "Babbage TxBody" Binary.decCBOR (LBS.fromStrict bytes)) :: IO (TxBody TopTx BabbageEra)
      let pp = emptyPParams & ppMaxCollateralInputsL .~ 3 :: PParams BabbageEra
          outputs = fmap Binary.sizedValue (b ^. allSizedOutputsTxBodyF)
          inputs = b ^. collateralInputsTxBodyL
      output <- maybe (die "one output required") pure (Seq.lookup 0 outputs)
      let Coin coin = sumAllCoin (Map.fromSet (const output) inputs)
      pure (result (Shelley.validateOutputBootAddrAttrsTooBig outputs), result (Shelley.validateWrongNetwork net outputs), result (Alonzo.validateTooManyCollateralInputs pp b), Set.size inputs, coin, conwayCollateral inputs)
    _ -> die "unsupported era"
  pure (object ["name" .= name c, "protocolMajor" .= protocolMajor c, "network" .= network c, "body" .= body c, "attributes" .= attrs, "addresses" .= addresses, "collateral" .= coll, "collateralCount" .= count, "collateralCoin" .= balance, "conwayCollateral" .= conway])

main :: IO ()
main = do
  args <- getArgs
  cases <- case args of
    [p] -> (LBS.readFile p >>= either die pure . eitherDecode) :: IO [Case]
    _ -> die "one input JSON path required"
  traverse runCase cases >>= LBS.putStr . encode
