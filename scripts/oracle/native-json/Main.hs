{-# LANGUAGE BangPatterns #-}
{-# LANGUAGE OverloadedStrings #-}
-- SPDX-License-Identifier: Apache-2.0
module Main where
import qualified Data.Aeson as A
import Data.Aeson ((.=), (.:))
import qualified Data.Aeson.Key as K
import qualified Data.Aeson.KeyMap as KM
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy.Char8 as BL
import qualified Data.ByteString.Base16 as Hex
import qualified Data.Scientific as Sci
import Data.Text (Text)
import qualified Data.Text as T
import qualified Data.Text.Encoding as TE
import qualified Data.Vector as V

data Case = Case Text BS.ByteString
instance A.FromJSON Case where
  parseJSON = A.withObject "Case" $ \o -> do
    name <- o .: "name"
    rawHex <- o .: "inputHex"
    raw <- either fail pure (Hex.decode (TE.encodeUtf8 rawHex))
    pure (Case name raw)

data Work = Visit A.Value | Key Text
flatten :: A.Value -> [A.Value]
flatten value = go [Visit value] [] where
  row = A.Array . V.fromList
  tag = A.String
  go [] !acc = reverse acc
  go (Key key:rest) !acc = go rest (row [tag "key",tag key]:acc)
  go (Visit v:rest) !acc = case v of
    A.Null -> go rest (row [tag "null"]:acc)
    A.Bool b -> go rest (row [tag "bool",A.Bool b]:acc)
    A.String t -> go rest (row [tag "string",tag t]:acc)
    A.Number n -> go rest (row [tag "number",tag (T.pack $ show $ Sci.coefficient n),tag (T.pack $ show $ Sci.base10Exponent n)]:acc)
    A.Array xs -> go (map Visit (V.toList xs) ++ rest) (row [tag "array",A.toJSON (V.length xs)]:acc)
    A.Object fields -> go (concatMap (\(k,x) -> [Key (K.toText k),Visit x]) (KM.toList fields) ++ rest) (row [tag "object",A.toJSON (KM.size fields)]:acc)

main :: IO ()
main = BL.getContents >>= mapM_ one . filter (not . BL.null) . BL.lines where
  one line = case A.eitherDecode line of
    Left err -> BL.putStrLn $ A.encode $ A.object ["inputError" .= err]
    Right (Case name raw) -> BL.putStrLn $ A.encode $ case A.eitherDecodeStrict raw of
      Left err -> A.object ["name" .= name,"accepted" .= False,"error" .= err]
      Right value -> A.object ["name" .= name,"accepted" .= True,"events" .= flatten value]
