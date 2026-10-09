{-# LANGUAGE OverloadedStrings #-}
-- Draft stdin/stdout shell. Run only under the separately reviewed hard limits.
module Main where
import Kernel
import Control.Exception (evaluate)
import Control.Monad (unless)
import Data.Aeson
import Data.Aeson.Types (Parser, parseEither)
import qualified Data.ByteString as B
import qualified Data.ByteString.Lazy as L
import Data.Char (digitToInt, isHexDigit)
import qualified Data.Text as T
import System.Exit (exitFailure)
import System.IO (stdin, stderr, hPutStrLn)
import System.Timeout (timeout)

hexBytes :: T.Text -> Parser B.ByteString
hexBytes t = do
  let chars = T.unpack t
  unless (even (length chars) && all isHexDigit chars) (fail "invalid hexadecimal bytes")
  pure $ B.pack (go chars)
  where
    go (a:b:xs) = fromIntegral (16 * digitToInt a + digitToInt b) : go xs
    go _ = []

input :: Value -> Parser InputBytes
input = withObject "synthetic input" $ \o -> do
  profile <- o .: "profile"
  unless (profile == ("synthetic-conway-pv9-v3-draft" :: T.Text)) (fail "unsupported profile")
  tx <- o .: "transactionCborHex" >>= hexBytes
  pp <- o .: "parametersCborHex" >>= hexBytes
  script <- o .: "reviewedScriptHex" >>= hexBytes
  values <- o .: "utxo"
  unless (length values <= 256) (fail "too many UTxO entries")
  entries <- traverse (withObject "UTxO entry" $ \e -> (,)
    <$> (e .: "inputCborHex" >>= hexBytes)
    <*> (e .: "outputCborHex" >>= hexBytes)) values
  pure (InputBytes tx pp script entries)

main :: IO ()
main = do
  -- Bounded read before parsing. All output is forced within the timeout.
  raw <- B.hGet stdin (3 * 1024 * 1024 + 1)
  if B.length raw > 3 * 1024 * 1024 then die "input exceeds 3 MiB" else do
    let result = do
          value <- eitherDecodeStrict' raw
          packet <- parseEither input value
          either (Left . T.unpack) Right (exportContext packet)
    completed <- timeout 30000000 $ do
      let encoded = encode $ either (\e -> object ["status" .= ("rejected" :: T.Text), "error" .= e]) id result
      size <- evaluate (L.length encoded)
      pure (encoded, size)
    case completed of
      Nothing -> die "evaluation deadline exceeded"
      Just (_, n) | n > 8 * 1024 * 1024 -> die "output exceeds 8 MiB"
      Just (encoded, _) -> L.putStr encoded
  where die msg = hPutStrLn stderr msg >> exitFailure
