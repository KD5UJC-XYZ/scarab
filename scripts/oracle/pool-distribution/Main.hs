-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE PackageImports #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

import Cardano.Ledger.Binary (decodeFull', decodeFullDecoder, decNoShareCBOR, natVersion, serialize')
import Cardano.Ledger.State (IndividualPoolStake, SnapShot, calculatePoolDistr)
import Data.Aeson (FromJSON (..), Value, eitherDecode, encode, object, withObject, (.:), (.=))
import qualified Data.ByteString as BS
import qualified "base16-bytestring" Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy.Char8 as LBS
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import System.Exit (die)

data Case = Case Text.Text Text.Text Text.Text

instance FromJSON Case where
  parseJSON = withObject "case" $ \o -> Case <$> o .: "name" <*> o .: "kind" <*> o .: "cbor"

hex :: BS.ByteString -> Text.Text
hex = Text.decodeUtf8 . Hex.encode

result :: Text.Text -> BS.ByteString -> Value
result "snapshot" raw = case decodeFullDecoder (natVersion @12) "SnapShot" decNoShareCBOR (LBS.fromStrict raw) of
  Left err -> object ["accepted" .= False, "failure" .= show err]
  Right (snapshot :: SnapShot) -> object
    [ "accepted" .= True
    , "cbor" .= hex (serialize' (natVersion @12) (calculatePoolDistr snapshot))
    ]
result _ raw = case decodeFull' (natVersion @12) raw of
  Left err -> object ["accepted" .= False, "failure" .= show err]
  Right (stake :: IndividualPoolStake) -> object
    [ "accepted" .= True, "cbor" .= hex (serialize' (natVersion @12) stake) ]

run :: Case -> IO Value
run (Case name kind raw) = do
  bytes <- either die pure (Hex.decode (Text.encodeUtf8 raw))
  pure $ object ["name" .= name, "reference" .= result kind bytes]

main :: IO ()
main = LBS.getContents >>= mapM_ (\line -> either die run (eitherDecode line) >>= LBS.putStrLn . encode) . LBS.lines
