{-# LANGUAGE OverloadedStrings #-}
-- Deliberately small JSON language for these object/string/unsigned-integer envelopes.
-- Duplicate keys are checked AFTER JSON escape decoding. Decimal/exponent numeric
-- tokens, arrays, null and nonfinite constants are outside this contract.
module StrictJson (decodeObject, fields, valueAt, unsigned, stringAt, hexBytes, hexText,
                   readInput, writeOutput, require, digest) where
import Control.Applicative ((<|>))
import Control.Monad (unless, when)
import qualified Crypto.Hash as Hash
import Data.Aeson (Value(..), eitherDecodeStrict', encode)
import qualified Data.Aeson.Key as K
import qualified Data.Aeson.KeyMap as KM
import qualified Data.Attoparsec.ByteString.Char8 as A
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BC
import qualified Data.ByteString.Lazy as LBS
import Data.Char (digitToInt)
import Data.List (sort)
import qualified Data.Set as Set
import qualified Data.Text as T
import Data.Scientific (floatingOrInteger)
import Numeric (showHex)
import System.Exit (die)
import System.IO (stdin, stdout)

decodeObject :: BS.ByteString -> Either String (KM.KeyMap Value)
decodeObject = A.parseOnly (space *> obj 0 <* space <* A.endOfInput)
 where
  space = A.skipWhile (`elem` [' ', '\t', '\r', '\n'])
  token p = p <* space
  quoted = do
    (raw, _) <- A.match $ do
      _ <- A.char '"'
      _ <- A.scan False $ \escaped c ->
        if escaped then Just False else if c == '\\' then Just True
        else if c == '"' then Nothing else Just False
      A.char '"'
    either fail pure (eitherDecodeStrict' raw :: Either String T.Text)
  obj depth = do
    when (depth > (4 :: Int)) (fail "JSON nesting limit")
    _ <- token (A.char '{')
    pairs <- pair depth `A.sepBy` token (A.char ',')
    _ <- token (A.char '}')
    let keys = map fst pairs
    unless (Set.size (Set.fromList keys) == length keys) (fail "duplicate JSON field")
    pure (KM.fromList pairs)
  pair depth = do
    k <- token quoted
    _ <- token (A.char ':')
    v <- token (val (depth+1))
    pure (K.fromText k,v)
  val depth = (Object <$> obj depth) <|> (String <$> quoted)
    <|> (A.string "true" *> pure (Bool True))
    <|> (A.string "false" *> pure (Bool False))
    <|> do
      digits <- A.takeWhile1 (\c -> c >= '0' && c <= '9')
      when (BS.length digits > 20 || (BS.length digits > 1 && BS.head digits == 48))
        (fail "invalid unsigned integer token")
      pure (Number (fromInteger (read (BC.unpack digits))))

require :: Bool -> String -> Either String ()
require good message = if good then Right () else Left message
fields :: [T.Text] -> KM.KeyMap Value -> Either String ()
fields names obj = require (sort names == sort (map K.toText (KM.keys obj))) "unexpected JSON fields"
valueAt :: T.Text -> KM.KeyMap Value -> Either String Value
valueAt k obj = maybe (Left ("missing field: " ++ T.unpack k)) Right (KM.lookup (K.fromText k) obj)
unsigned :: T.Text -> Integer -> Integer -> KM.KeyMap Value -> Either String Integer
unsigned key lo hi obj = do
  v <- valueAt key obj
  case v of
    Number n -> case floatingOrInteger n :: Either Double Integer of
      Right i | i >= lo && i <= hi -> Right i
      _ -> Left ("invalid bounded integer: " ++ T.unpack key)
    _ -> Left ("invalid integer type: " ++ T.unpack key)
stringAt :: T.Text -> KM.KeyMap Value -> Either String T.Text
stringAt key obj = do
  v <- valueAt key obj
  case v of String s -> Right s; _ -> Left ("invalid string: " ++ T.unpack key)
hexBytes :: Int -> T.Text -> Either String BS.ByteString
hexBytes cap s = do
  require (not (T.null s) && T.length s <= 2*cap && even (T.length s)
           && T.all (`elem` ("0123456789abcdef" :: String)) s) "invalid bounded lowercase hex"
  pure (BS.pack (go (T.unpack s)))
 where
  go (a:b:rest) = fromIntegral (16*digitToInt a+digitToInt b) : go rest
  go _ = []
hexText :: BS.ByteString -> T.Text
hexText = T.pack . concatMap (\b -> let s = showHex b "" in if length s == 1 then '0':s else s) . BS.unpack
digest :: BS.ByteString -> String
digest bytes = show (Hash.hash bytes :: Hash.Digest Hash.SHA256)
readInput :: Int -> IO BS.ByteString
readInput cap = do
  bytes <- BS.hGet stdin (cap+1)
  if BS.length bytes > cap then die "input limit" else pure bytes
writeOutput :: Value -> IO ()
writeOutput value = do
  let bytes = encode value
  if LBS.length bytes > 64*1024*1024 then die "output limit" else LBS.hPut stdout bytes
