-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ScopedTypeVariables #-}
module Main (main) where

import Cardano.Chain.Common (Address, Lovelace, NetworkMagic (NetworkMainOrStage), lovelaceToInteger, makeRedeemAddress)
import Cardano.Chain.Genesis (GenesisAvvmBalances (..), GenesisNonAvvmBalances (..))
import Cardano.Chain.UTxO (TxIn (..), TxOut (..), fromBalances, toList)
import Cardano.Crypto.Hashing (hashToBytes)
import Cardano.Crypto.Signing (fromCompactRedeemVerificationKey)
import Cardano.Ledger.Binary (byronProtVer, serialize')
import Cardano.Prelude (SchemaError)
import Data.Aeson (FromJSON (..), Value, eitherDecode, encode, object, withObject, (.:), (.=))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Base16 as B16
import qualified Data.ByteString.Lazy as LBS
import qualified Data.Map.Strict as M
import qualified Data.Text as T
import qualified Data.Text.Encoding as TE
import System.Environment (getArgs)
import System.Exit (die)
import Text.JSON.Canonical (JSValue, fromJSField, parseCanonicalJSON)

-- One measurement: a name and the text of a genesis object holding the two balance fields.
data Case = Case String T.Text

instance FromJSON Case where
  parseJSON = withObject "case" $ \o -> Case <$> o .: "name" <*> o .: "text"

hex :: BS.ByteString -> T.Text
hex = TE.decodeUtf8 . B16.encode

-- The genesis UTxO of a list of balances as `genesisUtxo` builds it (UTxO/GenesisUTxO.hs:16-32):
-- `fromBalances` keys each output by the hash of its address encoded again (UTxO/UTxO.hs:121-131).
entries :: [(Address, Lovelace)] -> [Value]
entries balances =
  [ object
      [ "txid" .= hex (hashToBytes txid)
      , "index" .= ix
      , "address" .= hex (serialize' byronProtVer addr)
      , "lovelace" .= show (lovelaceToInteger coin)
      ]
  | (TxInUtxo txid ix, TxOut addr coin) <- toList (fromBalances balances)
  ]

-- `readGenesisData` (Genesis/Data.hs:169-181) parses the file with `parseCanonicalJSON` and reads
-- `nonAvvmBalances` and `avvmDistr` with `fromJSField` (Data.hs:96, 102). The redeem addresses of
-- the AVVM balances are made on a network without a magic (`makeRedeemAddress`, GenesisUTxO.hs:22-28).
measure :: T.Text -> Either String Value
measure text = do
  json :: JSValue <- parseCanonicalJSON (LBS.fromStrict (TE.encodeUtf8 text))
  nonAvvm <- schema (fromJSField json "nonAvvmBalances")
  avvm <- schema (fromJSField json "avvmDistr")
  let redeem = [(makeRedeemAddress NetworkMainOrStage (fromCompactRedeemVerificationKey k), c) | (k, c) <- M.toList (unGenesisAvvmBalances avvm)]
  pure
    ( object
        [ "nonAvvm" .= entries (M.toList (unGenesisNonAvvmBalances nonAvvm))
        , "avvm" .= entries redeem
        ]
    )
  where
    schema :: Either SchemaError a -> Either String a
    schema = either (Left . show) Right

verdict :: Either String Value -> Value
verdict (Right v) = object ["accepted" .= True, "result" .= v]
verdict (Left err) = object ["accepted" .= False, "failure" .= err]

main :: IO ()
main = do
  args <- getArgs
  cases <- case args of
    [p] -> (LBS.readFile p >>= either die pure . eitherDecode) :: IO [Case]
    _ -> die "one input JSON path required"
  LBS.putStr (encode [object ["name" .= n, "reference" .= verdict (measure t)] | Case n t <- cases])
