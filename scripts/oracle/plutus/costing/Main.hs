-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE OverloadedStrings #-}
module Main where

import Control.Monad.Except (runExceptT)
import Control.Monad.Writer.Strict (runWriter)
import Data.Aeson (FromJSON(..), Value, eitherDecode, encode, object, withObject, (.:), (.:?), (.=), (.!=))
import qualified Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Char8 as BS
import qualified Data.ByteString.Lazy.Char8 as BL
import qualified Data.ByteString.Short as SBS
import Data.Foldable (traverse_)
import Data.Int (Int64)
import qualified Data.Map.Lazy as Map
import System.Environment (getArgs)
import qualified PlutusCore.Crypto.BLS12_381.G1 as G1
import qualified PlutusCore.Crypto.BLS12_381.G2 as G2
import qualified PlutusLedgerApi.Common as C
import qualified PlutusLedgerApi.V1 as V1
import qualified PlutusLedgerApi.V2 as V2
import qualified PlutusLedgerApi.V3 as V3
import PlutusCore.Evaluation.Machine.Exception (ErrorWithCause(..), EvaluationError(..))
import UntypedPlutusCore.Evaluation.Machine.Cek (CekUserError(..))

data Language = PlutusV1 | PlutusV2 | PlutusV3
instance FromJSON Language where
  parseJSON value = do
    number <- parseJSON value
    case (number :: Int) of
      1 -> pure PlutusV1
      2 -> pure PlutusV2
      3 -> pure PlutusV3
      _ -> fail "language must be 1, 2 or 3"

languageId :: Language -> Int
languageId PlutusV1 = 1
languageId PlutusV2 = 2
languageId PlutusV3 = 3

data Request = Request String Language Int String [Int64] Integer Integer
instance FromJSON Request where
  parseJSON = withObject "oracle request" $ \o -> Request
    <$> o .: "id" <*> o .: "language" <*> o .: "protocol" <*> o .: "script"
    <*> o .: "costModel" <*> o .:? "cpu" .!= 1152921504606846976 <*> o .:? "memory" .!= 1152921504606846976

stage :: C.EvaluationError -> String
stage (C.DeBruijnError _) = "scope"
stage (C.CodecError _) = "setup"
stage (C.CekError (ErrorWithCause (OperationalError (CekOutOfExError _)) _)) = "budget"
stage _ = "evaluation"

run :: C.EvaluationContext -> Request -> Value
run context (Request ident language protocol script _ cpu memory) =
  let fields = ["id" .= ident, "language" .= languageId language, "protocol" .= protocol]
      result admitted status detail = object (fields ++ ["reference" .= object
        ["admitted" .= admitted, "stage" .= (status :: String), "detail" .= (detail :: String)]])
      ll = case language of {PlutusV1 -> C.PlutusV1; PlutusV2 -> C.PlutusV2; PlutusV3 -> C.PlutusV3}
      pv = C.MajorProtocolVersion protocol
  in case Hex.decode (BS.pack script) of
       Left err -> object (fields ++ ["toolError" .= err])
       Right bytes -> case C.deserialiseScript ll pv (SBS.toShort bytes) of
         Left err -> result False "decode" (show (err :: C.ScriptDecodeError))
         Right decoded ->
             let budget = C.ExBudget (C.ExCPU (fromInteger cpu)) (C.ExMemory (fromInteger memory))
                 (_, verdict) = C.evaluateScriptRestricting ll pv C.Quiet context budget decoded []
             in case verdict of
               Left err -> result True (stage err) (show err)
               Right used -> result True "success" (show used)

main :: IO ()
main = do
  args <- getArgs
  if args == ["--hash-vectors"] then traverse_ vector ["", "41", "90", "4100", concat (replicate 51 "1234567890")]
  else do
    input <- BL.getContents
    let requests = map eitherDecode (BL.lines input)
        key (Request _ language protocol _ params _ _) = (languageId language, protocol, params)
        prepare (Request _ language _ _ params _ _) =
          let makeContext = case language of {PlutusV1 -> V1.mkEvaluationContext; PlutusV2 -> V2.mkEvaluationContext; PlutusV3 -> V3.mkEvaluationContext}
          in fst (runWriter (runExceptT (makeContext params)))
        contexts = Map.fromList [(key request, prepare request) | Right request <- requests]
        one decoded = BL.putStrLn $ encode $ case decoded of
          Left err -> object ["toolError" .= err]
          Right request -> case Map.lookup (key request) contexts of
            Nothing -> object ["toolError" .= ("missing evaluation context" :: String)]
            Just (Left err) -> object ["toolError" .= show err]
            Just (Right context) -> run context request
    traverse_ one requests
  where
    vector dstHex = case Hex.decode (BS.pack dstHex) of
      Left err -> BL.putStrLn (encode (object ["toolError" .= err]))
      Right dst -> do
        let hash1 = fmap (BS.unpack . Hex.encode . G1.compress) (G1.hashToGroup (BS.singleton '?') dst)
            hash2 = fmap (BS.unpack . Hex.encode . G2.compress) (G2.hashToGroup (BS.singleton '?') dst)
        BL.putStrLn (encode (object ["message" .= ("3f" :: String), "dst" .= dstHex, "g1" .= either show id hash1, "g2" .= either show id hash2]))
