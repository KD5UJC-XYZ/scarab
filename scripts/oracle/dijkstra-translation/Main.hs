-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

import Cardano.Ledger.Babbage.TxOut (referenceScriptTxOutL)
import Cardano.Ledger.Binary (decodeFull', serialize')
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.Ledger.Core (
  EraScript,
  EraTxOut (..),
  Script,
  TxOut,
  eraProtVerLow,
  hashScript,
 )
import Cardano.Ledger.Dijkstra (DijkstraEra)
import Cardano.Ledger.Hashes (SafeToHash (originalBytes), ScriptHash (..))
import Cardano.Ledger.BaseTypes (strictMaybeToMaybe)
import Cardano.Crypto.Hash.Class (hashToBytes)
import Data.Aeson (FromJSON (..), Value, eitherDecode, encode, object, withObject, (.:), (.=))
import Data.Aeson.Types (Parser)
import qualified Data.ByteString as BS
import Data.Char (digitToInt, isHexDigit)
import Numeric (showHex)
import qualified Data.ByteString.Lazy as LBS
import Data.Text (Text)
import qualified Data.Text as T
import Lens.Micro ((^.))
import System.Environment (getArgs)
import System.Exit (die)

-- One measurement: a Conway output (its CBOR) to upgrade into Dijkstra.
data Case = Output String BS.ByteString

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
  parseJSON = withObject "case" $ \o -> do
    Output <$> o .: "name" <*> (o .: "txOut" >>= unhex)

-- The script's hash and the bytes it is hashed over (`hashScript`, cardano-ledger 9df9f98c
-- `Core.hs:615-620`).
script :: forall era. EraScript era => Script era -> Value
script s =
  object
    [ "hash" .= hex (let ScriptHash h = hashScript @era s in hashToBytes h)
    , "bytes" .= hex (originalBytes s)
    ]

-- `upgradeTxOut` of Dijkstra (`Dijkstra/TxOut.hs:33`, `upgradeBabbageTxOut`), as the translation
-- of the UTxO (`Dijkstra/Translation.hs:225-227`) and of the ledger tables (consensus 2416493b
-- `Cardano/CanHardFork.hs:757-765`) apply it.
output :: BS.ByteString -> Value
output raw = case decodeFull' (eraProtVerLow @ConwayEra) raw of
  Left err -> object ["accepted" .= False, "failure" .= show err]
  Right (conway :: TxOut ConwayEra) ->
    let dijkstra = upgradeTxOut conway :: TxOut DijkstraEra
     in object
          [ "accepted" .= True
          , "conwayTxOut" .= hex (serialize' (eraProtVerLow @ConwayEra) conway)
          , "conwayScript" .= fmap (script @ConwayEra) (strictMaybeToMaybe (conway ^. referenceScriptTxOutL))
          , "dijkstraTxOut" .= hex (serialize' (eraProtVerLow @DijkstraEra) dijkstra)
          , "dijkstraScript" .= fmap (script @DijkstraEra) (strictMaybeToMaybe (dijkstra ^. referenceScriptTxOutL))
          ]

run :: Case -> Value
run (Output name raw) = object ["name" .= name, "reference" .= output raw]

main :: IO ()
main = do
  args <- getArgs
  cases <- case args of
    [p] -> (LBS.readFile p >>= either die pure . eitherDecode) :: IO [Case]
    _ -> die "one input JSON path required"
  LBS.putStr (encode (map run cases))
