-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE OverloadedStrings #-}
import Data.Aeson (Value, encode, object, (.=))
import qualified Data.ByteString as B
import qualified Data.ByteString.Lazy.Char8 as L
import Data.Char (chr, ord)
import Data.SatInt (fromSatInt)
import qualified Data.Text as T
import qualified Data.Text.Encoding as E
import qualified Data.Text.Internal as I
import PlutusCore.Error (ParserErrorBundle)
import PlutusCore.Evaluation.Machine.CostStream (sumCostStream)
import PlutusCore.Evaluation.Machine.ExMemoryUsage
  (TextCostedByByteLength(..), flattenCostRose, memoryUsage)
import PlutusCore.Parser (conText, parseGen)
import PlutusCore.Quote (runQuoteT)
import Text.Megaparsec (eof)

packCases :: [(String, [Int])]
packCases = [("empty",[]),("ascii",[65]),("two",[162]),("three",[26085]),
  ("supplementary",[128512]),("high-surrogate",[55296]),("low-surrogate",[56320]),
  ("surrogate-codepoints",[55357,56832]),("mixed",[88,55296,90]),("maximum",[1114111]),
  ("seven-bytes",[128512,26085]),("eight-bytes",[128512,26085,65])]

packRow :: (String, [Int]) -> Value
packRow (name,points) =
  let text@(I.Text _ _ bytes) = T.pack (map chr points)
      measure :: Int
      measure = fromSatInt $ sumCostStream $ flattenCostRose $ memoryUsage $ TextCostedByByteLength text
  in object ["kind" .= ("pack" :: String), "id" .= name, "inputCodePoints" .= points,
       "codePoints" .= map ord (T.unpack text), "utf8" .= B.unpack (E.encodeUtf8 text),
       "bytes" .= bytes, "measure" .= measure]

decodeCases :: [(String, [Int])]
decodeCases = [("valid-pair",[240,159,152,128]),("surrogate-utf8",[237,160,128]),
  ("overlong",[192,128]),("truncated",[226,130]),("out-of-range",[244,144,128,128])]

decodeRow :: (String, [Int]) -> Value
decodeRow (name,bytes) = object ["kind" .= ("decode" :: String), "id" .= name,
  "utf8" .= bytes, "codePoints" .= either (const Nothing) (Just . map ord . T.unpack)
    (E.decodeUtf8' (B.pack (map fromIntegral bytes)))]

parserCases :: [(String, String)]
parserCases = [("hex-supplementary","\"\\x1f600\""),("decimal-supplementary","\"\\128512\""),
  ("surrogate","\"\\xD800\""),("separate-surrogates","\"\\xD83D\\xDE00\""),
  ("out-of-range","\"\\x110000\""),("maximum","\"\\x10FFFF\""),
  ("unicode-extension","\"\\u{1f600}\""),("empty-escape","\"A\\&B\""),
  ("multiline-gap","\"A\\\n  \\B\"")]

parserRow :: (String, String) -> Value
parserRow (name,literal) =
  let result = runQuoteT (parseGen (conText <* eof) (T.pack literal)) :: Either ParserErrorBundle T.Text
  in object ["kind" .= ("parse" :: String), "id" .= name, "literal" .= literal,
       "codePoints" .= either (const Nothing) (Just . map ord . T.unpack) result]

main :: IO ()
main = mapM_ (L.putStrLn . encode) (map packRow packCases ++ map decodeRow decodeCases ++ map parserRow parserCases)
