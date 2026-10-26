{-# LANGUAGE BangPatterns #-}
{-# LANGUAGE OverloadedStrings #-}
-- SPDX-License-Identifier: Apache-2.0
module Main where
import qualified Data.Aeson as A
import qualified Data.Aeson.Key as K
import qualified Data.Aeson.KeyMap as KM
import qualified Data.Scientific as Sci
import qualified Data.Vector as V
import qualified Data.Text as T
import Data.Aeson ((.=), (.:))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy.Char8 as BL
import Data.JsonStream.Parser (ParseOutput(..), runParser, objectItems, value)
import Data.Text (Text)
import qualified Data.Text.Encoding as Text
import qualified Data.ByteString.Base16 as Hex

data Case = Case Text BS.ByteString Int
instance A.FromJSON Case where
  parseJSON = A.withObject "Case" $ \o -> do
    name <- o .: "name"
    rawHex <- o .: "inputHex"
    chunkSize <- o .: "chunkSize"
    raw <- either fail pure (Hex.decode (Text.encodeUtf8 rawHex))
    if chunkSize > 0 then pure $ Case name raw chunkSize else fail "chunkSize must be positive"

data Work = Visit A.Value | Key Text
flatten :: A.Value -> [A.Value]
flatten rootValue = go [Visit rootValue] [] where
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

steps :: Int -> BS.ByteString -> A.Value
steps chunkSize = go 3 [] [] (runParser (objectItems value))
  where
    go :: Int -> [Int] -> [(Text,A.Value)] -> ParseOutput (Text,A.Value) -> BS.ByteString -> A.Value
    go budget feeds pairs result input = case result of
      ParseYield pair next -> go budget feeds (pair:pairs) next input
      ParseFailed err -> finish feeds pairs ["status" .= ("failed"::Text),"error" .= err]
      ParseDone rest -> finish feeds pairs ["status" .= ("done"::Text),"remaining" .= BS.length rest,"remainingHex" .= Text.decodeUtf8 (Hex.encode rest)]
      ParseNeedData cont
        | BS.null input && budget == 0 -> finish feeds pairs ["status" .= ("needDataAfterThreeEmptyFeeds"::Text)]
        | otherwise -> let (chunk,rest) = BS.splitAt chunkSize input
                           nextBudget = if BS.null input then budget-1 else budget
                       in go nextBudget (BS.length chunk:feeds) pairs (cont chunk) rest
    finish feeds pairs fields = A.object (["feeds" .= reverse feeds,"pairs" .= reverse pairs,"pairEvents" .= map (\(key,v) -> A.object ["key" .= key,"events" .= flatten v]) (reverse pairs)] ++ fields)

main :: IO ()
main = BL.getContents >>= mapM_ one . filter (not . BL.null) . BL.lines
  where
    one line = case A.eitherDecode line of
      Left err -> BL.putStrLn $ A.encode $ A.object ["inputError" .= err]
      Right (Case name raw chunkSize) ->
        BL.putStrLn $ A.encode $ A.object ["name" .= name,"trace" .= steps chunkSize raw]
