-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE OverloadedStrings #-}

-- | Byron signature verdicts of the unchanged released reference libraries.
--
-- Reads rows @id \\t public_key_hex \\t signature_hex \\t message_hex@ on stdin (the column order of
-- @fixtures/witness/vectors.tsv@) and writes @id \\t vkey \\t redeem@ for each, where @vkey@ is
-- 'verifySignatureRaw' of cardano-crypto-wrapper 1.7.0.0 (block, certificate, proposal, vote and
-- ordinary transaction witnesses) and @redeem@ is 'verifyRedeemSigRaw' (AVVM redeem witnesses).
-- A verdict is @accept@, @reject@, or @size@ when the bytes cannot be given to the API at all (the
-- Byron decoders refuse such a key or signature before any verification).
module Main (main) where

import Cardano.Crypto.Raw (Raw)
import Cardano.Crypto.Signing (
  RedeemSignature (..),
  RedeemVerificationKey (..),
  Signature (..),
  VerificationKey (..),
  verifyRedeemSigRaw,
  verifySignatureRaw,
 )
import qualified Cardano.Crypto.Wallet as CC
import Crypto.Error (CryptoFailable (..))
import qualified Crypto.PubKey.Ed25519 as Ed25519
import qualified Data.ByteString as BS
import qualified Data.ByteString.Base16 as B16
import qualified Data.ByteString.Char8 as BC
import System.Exit (exitFailure)
import System.IO (hPutStrLn, stderr)

data Verdict = Accept | Reject | Size

render :: Verdict -> BS.ByteString
render Accept = "accept"
render Reject = "reject"
render Size = "size"

verdict :: Bool -> Verdict
verdict ok = if ok then Accept else Reject

-- | An extended Byron key is the Ed25519 key then a chain code; verification ignores the chain
-- code (cardano-crypto 1.3.0 @Cardano/Crypto/Wallet.hs@ @verify@), so any 32 bytes serve.
vkeyVerdict :: BS.ByteString -> BS.ByteString -> BS.ByteString -> Verdict
vkeyVerdict key sig msg =
  case (CC.xpub (key <> BS.replicate 32 0), CC.xsignature sig) of
    (Right xpub, Right xsig) | BS.length key == 32 ->
      verdict (verifySignatureRaw (VerificationKey xpub) msg (Signature xsig :: Signature Raw))
    _ -> Size

redeemVerdict :: BS.ByteString -> BS.ByteString -> BS.ByteString -> Verdict
redeemVerdict key sig msg =
  case (Ed25519.publicKey key, Ed25519.signature sig) of
    (CryptoPassed k, CryptoPassed s) ->
      verdict (verifyRedeemSigRaw (RedeemVerificationKey k) msg (RedeemSignature s :: RedeemSignature Raw))
    _ -> Size

row :: BS.ByteString -> Either String BS.ByteString
row line = case BC.split '\t' line of
  [ident, keyHex, sigHex, msgHex] -> do
    key <- B16.decode keyHex
    sig <- B16.decode sigHex
    msg <- B16.decode msgHex
    pure $
      BS.intercalate
        "\t"
        [ident, render (vkeyVerdict key sig msg), render (redeemVerdict key sig msg)]
  _ -> Left ("malformed row: " <> BC.unpack line)

main :: IO ()
main = do
  input <- BS.getContents
  case traverse row (filter (not . BS.null) (BC.lines input)) of
    Left err -> hPutStrLn stderr err >> exitFailure
    Right out -> BC.putStr (BC.unlines out)
