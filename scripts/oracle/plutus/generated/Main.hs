-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE OverloadedStrings #-}
module Main where

import Codec.Serialise (deserialiseOrFail)
import Control.Monad.Except (runExceptT)
import Control.Monad.Writer.Strict (runWriter)
import Data.Aeson (FromJSON(..), Value, eitherDecode, encode, object, withObject, (.:), (.:?), (.=), (.!=))
import qualified Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Char8 as BS
import qualified Data.ByteString.Lazy as LBS
import qualified Data.ByteString.Lazy.Char8 as BL
import qualified Data.ByteString.Short as SBS
import Data.Foldable (traverse_)
import Data.Int (Int64)
import qualified Data.Map.Lazy as Map
import System.Environment (getArgs)
import PlutusCore.Data (Data)
import PlutusCore.Evaluation.Machine.ExBudget (ExRestrictingBudget(..), minusExBudget)
import PlutusCore.MkPlc (mkConstant, mkIterAppNoAnn)
import qualified PlutusCore.Crypto.BLS12_381.G1 as G1
import qualified PlutusCore.Crypto.BLS12_381.G2 as G2
import qualified PlutusLedgerApi.Common as C
import qualified PlutusLedgerApi.V1 as V1
import qualified PlutusLedgerApi.V2 as V2
import qualified PlutusLedgerApi.V3 as V3
import qualified UntypedPlutusCore as UPLC
import PlutusCore.Evaluation.Machine.Exception (ErrorWithCause(..), EvaluationError(..), MachineError(..))
import UntypedPlutusCore.Evaluation.Machine.Cek (CekUserError(..), CekReport(..), CekResult(..), RestrictingSt(..), restricting)

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

-- The costing oracle's request, with two optional fields: the script's arguments (CBOR hex `Data`,
-- applied in order as `mkTermToEvaluate` applies them) and whether the logs are kept (`Verbose`).
data Request = Request
  { requestId :: String
  , requestLanguage :: Language
  , requestProtocol :: Int
  , requestScript :: String
  , requestCostModel :: [Int64]
  , requestCpu :: Integer
  , requestMemory :: Integer
  , requestArguments :: [String]
  , requestVerbose :: Bool
  }
instance FromJSON Request where
  parseJSON = withObject "oracle request" $ \o -> Request
    <$> o .: "id" <*> o .: "language" <*> o .: "protocol" <*> o .: "script"
    <*> o .: "costModel" <*> o .:? "cpu" .!= 1152921504606846976 <*> o .:? "memory" .!= 1152921504606846976
    <*> o .:? "arguments" .!= [] <*> o .:? "verbose" .!= False

stage :: C.EvaluationError -> String
stage (C.DeBruijnError _) = "scope"
stage (C.CodecError _) = "setup"
stage (C.CekError (ErrorWithCause (OperationalError (CekOutOfExError _)) _)) = "budget"
stage _ = "evaluation"

-- The error's class and the name of its constructor: the machine's `MachineError` (structural) or
-- `CekUserError` (operational), or the ledger's `EvaluationError` when the machine did not fail.
classify :: C.EvaluationError -> (Maybe String, String)
classify (C.CekError (ErrorWithCause (StructuralError err) _)) = (Just "structural", machine err)
  where
    machine :: MachineError fun -> String
    machine NonPolymorphicInstantiationMachineError = "NonPolymorphicInstantiationMachineError"
    machine NonWrapUnwrappedMachineError = "NonWrapUnwrappedMachineError"
    machine NonFunctionalApplicationMachineError = "NonFunctionalApplicationMachineError"
    machine OpenTermEvaluatedMachineError = "OpenTermEvaluatedMachineError"
    machine (UnliftingMachineError _) = "UnliftingMachineError"
    machine BuiltinTermArgumentExpectedMachineError = "BuiltinTermArgumentExpectedMachineError"
    machine UnexpectedBuiltinTermArgumentMachineError = "UnexpectedBuiltinTermArgumentMachineError"
    machine NonConstrScrutinizedMachineError = "NonConstrScrutinizedMachineError"
    machine (MissingCaseBranchMachineError _) = "MissingCaseBranchMachineError"
    machine (PanicMachineError _) = "PanicMachineError"
classify (C.CekError (ErrorWithCause (OperationalError err) _)) = (Just "operational", user err)
  where
    user (CekCaseBuiltinError _) = "CekCaseBuiltinError"
    user (CekOutOfExError _) = "CekOutOfExError"
    user CekEvaluationFailure = "CekEvaluationFailure"
classify (C.DeBruijnError _) = (Nothing, "DeBruijnError")
classify (C.CodecError _) = (Nothing, "CodecError")
classify C.CostModelParameterMismatch = (Nothing, "CostModelParameterMismatch")
classify C.InvalidReturnValue = (Nothing, "InvalidReturnValue")

units :: C.ExBudget -> Value
units (C.ExBudget (C.ExCPU cpu) (C.ExMemory memory)) =
  object ["cpu" .= toInteger (C.unSatInt cpu), "memory" .= toInteger (C.unSatInt memory)]

decodeData :: String -> Either String Data
decodeData hex = do
  bytes <- Hex.decode (BS.pack hex)
  either (Left . show) Right (deserialiseOrFail (LBS.fromStrict bytes))

run :: C.EvaluationContext -> Request -> Value
run context request =
  let fields = ["id" .= requestId request, "language" .= languageId language, "protocol" .= requestProtocol request]
      language = requestLanguage request
      result admitted status detail extra = object (fields ++ ["reference" .= object
        (["admitted" .= admitted, "stage" .= (status :: String), "detail" .= (detail :: String)] ++ extra)])
      toolError err = object (fields ++ ["toolError" .= (err :: String)])
      ll = case language of {PlutusV1 -> C.PlutusV1; PlutusV2 -> C.PlutusV2; PlutusV3 -> C.PlutusV3}
      pv = C.MajorProtocolVersion (requestProtocol request)
      mode = if requestVerbose request then C.Verbose else C.Quiet
  in case (Hex.decode (BS.pack (requestScript request)), traverse decodeData (requestArguments request)) of
       (Left err, _) -> toolError err
       (_, Left err) -> toolError err
       (Right bytes, Right arguments) -> case C.deserialiseScript ll pv (SBS.toShort bytes) of
         Left err -> result False "decode" (show (err :: C.ScriptDecodeError)) []
         Right decoded ->
           let budget = C.ExBudget (C.ExCPU (fromInteger (requestCpu request))) (C.ExMemory (fromInteger (requestMemory request)))
               (logs, verdict) = C.evaluateScriptRestricting ll pv mode context budget decoded arguments
               -- The budget the machine spent, also when it fails: the same restricting run through
               -- `evaluateTerm`, which reports the final state of the budget whatever the result. The
               -- term is the one `mkTermToEvaluate` builds once its version and scope checks pass.
               C.ScriptNamedDeBruijn (UPLC.Program _ _ term) = C.deserialisedScript decoded
               applied = mkIterAppNoAnn term (fmap (mkConstant ()) arguments)
               CekReport machine (RestrictingSt (ExRestrictingBudget final)) _ =
                 C.evaluateTerm (restricting (ExRestrictingBudget budget)) pv C.Quiet context applied
               spent = budget `minusExBudget` final
               ran = ["spent" .= units spent, "logs" .= logs]
               failed err = let (errorClass, constructor) = classify err
                            in maybe [] (\c -> ["class" .= c]) errorClass ++ ["error" .= constructor]
           in case (verdict, machine) of
                (Right used, CekFailure _) -> toolError ("evaluateTerm failed where the API spent " ++ show used)
                (Right used, _)
                  | used /= spent -> toolError ("evaluateTerm spent " ++ show spent ++ ", the API " ++ show used)
                  | otherwise -> result True "success" (show used) ran
                (Left err@(C.CekError _), CekFailure again)
                  | classify err /= classify (C.CekError again) -> toolError ("evaluateTerm failed with " ++ show again)
                  | otherwise -> result True (stage err) (show err) (ran ++ failed err)
                (Left (C.CekError _), _) -> toolError "evaluateTerm succeeded where the API failed"
                (Left err@C.InvalidReturnValue, CekFailure again) -> toolError ("evaluateTerm failed with " ++ show again ++ " where the API ran to " ++ show err)
                (Left err@C.InvalidReturnValue, _) -> result True (stage err) (show err) (ran ++ failed err)
                (Left err, _) -> result True (stage err) (show err) (["logs" .= logs] ++ failed err)

main :: IO ()
main = do
  args <- getArgs
  if args == ["--hash-vectors"] then traverse_ vector ["", "41", "90", "4100", concat (replicate 51 "1234567890")]
  else do
    input <- BL.getContents
    let requests = map eitherDecode (BL.lines input)
        key request = (languageId (requestLanguage request), requestProtocol request, requestCostModel request)
        prepare request =
          let makeContext = case requestLanguage request of {PlutusV1 -> V1.mkEvaluationContext; PlutusV2 -> V2.mkEvaluationContext; PlutusV3 -> V3.mkEvaluationContext}
          in fst (runWriter (runExceptT (makeContext (requestCostModel request))))
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
