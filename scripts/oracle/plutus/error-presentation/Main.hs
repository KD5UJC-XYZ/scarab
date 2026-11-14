-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE GADTs #-}
{-# LANGUAGE LambdaCase #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE PatternSynonyms #-}
{-# LANGUAGE ScopedTypeVariables #-}
module Main (main) where

import Codec.Extras.SerialiseViaFlat qualified as Flat
import Codec.Serialise (deserialiseOrFail, serialise)
import Data.Aeson qualified as J
import Data.Aeson ((.:), (.:?), (.!=), (.=), object, withObject)
import Data.Aeson.Types (Pair, Parser, parseEither)
import Data.ByteString qualified as BS
import Data.ByteString.Base16 qualified as Hex
import Data.ByteString.Lazy qualified as LBS
import Data.ByteString.Short qualified as SBS
import PlutusLedgerApi.V1 qualified as V1
import PlutusLedgerApi.V2 qualified as V2
import PlutusLedgerApi.V3 qualified as V3
import Control.Monad.Except (runExceptT)
import Control.Monad.Writer.Strict (runWriter)
import Data.Foldable (toList)
import Data.List (find)
import Data.Text qualified as T
import Data.Text.Encoding qualified as T
import Data.Word (Word64)
import Data.Vector qualified as Vector
import PlutusCore qualified as P
import PlutusCore.Builtin qualified as BR
import PlutusCore.Crypto.BLS12_381.G1 qualified as G1
import PlutusCore.Crypto.BLS12_381.G2 qualified as G2
import PlutusCore.Crypto.BLS12_381.Pairing qualified as BLS
import PlutusCore.Default
import PlutusCore.DeBruijn qualified as D
import PlutusCore.Evaluation.Machine.ExBudget
import PlutusCore.Evaluation.Machine.Exception qualified as E
import PlutusCore.Pretty (prettyClassic, prettyPlcReadable)
import PlutusCore.Quote (runQuoteT)
import PlutusCore.Value qualified as V
import PlutusLedgerApi.Common qualified as C
import Prettyprinter qualified as PP
import Prettyprinter.Render.String (renderString)
import System.Exit (die)
import UntypedPlutusCore qualified as U
import UntypedPlutusCore.Evaluation.Machine.Cek qualified as K
import UntypedPlutusCore.Parser qualified as UP

type Term = U.Term P.NamedDeBruijn DefaultUni DefaultFun ()

decimal :: J.Value -> Parser Integer
decimal = J.withText "decimal integer" $ \t ->
  case reads (T.unpack t) of
    [(n, "")] -> pure n
    _ -> fail "not a decimal integer"

integer :: J.Object -> J.Key -> Parser Integer
integer o k = o .: k >>= decimal

index :: J.Object -> Parser D.Index
index o = do
  i <- integer o "index"
  if i >= 0 && i <= toInteger (maxBound :: Word64)
    then pure (fromInteger i)
    else fail "native index is outside Word64"

hex :: BS.ByteString -> T.Text
hex = T.decodeUtf8 . Hex.encode

unhex :: T.Text -> Parser BS.ByteString
unhex = either fail pure . Hex.decode . T.encodeUtf8

named :: J.Object -> Parser P.NamedDeBruijn
named o = P.NamedDeBruijn <$> o .:? "name" .!= "i" <*> index o

constant :: J.Value -> Parser (Some (ValueOf DefaultUni))
constant = withObject "native constant" $ \o -> do
  tag <- o .: "tag" :: Parser T.Text
  case tag of
    "BlsMlResult" -> pure $ Some $ ValueOf DefaultUniBLS12_381_MlResult BLS.identityMlResult
    "BlsG1" -> do
      bytes <- o .: "compressed" >>= unhex
      value <- either (fail . show) pure (G1.uncompress bytes)
      pure $ Some $ ValueOf DefaultUniBLS12_381_G1_Element value
    "BlsG2" -> do
      bytes <- o .: "compressed" >>= unhex
      value <- either (fail . show) pure (G2.uncompress bytes)
      pure $ Some $ ValueOf DefaultUniBLS12_381_G2_Element value
    "BlsG1Hash" -> do
      bytes <- o .: "message" >>= unhex
      dst <- o .: "dst" >>= unhex
      value <- either (fail . show) pure (G1.hashToGroup bytes dst)
      pure $ Some $ ValueOf DefaultUniBLS12_381_G1_Element value
    "BlsG2Hash" -> do
      bytes <- o .: "message" >>= unhex
      dst <- o .: "dst" >>= unhex
      value <- either (fail . show) pure (G2.hashToGroup bytes dst)
      pure $ Some $ ValueOf DefaultUniBLS12_381_G2_Element value
    "Value" -> do
      entries <- o .: "entries"
      policies <- traverse (withObject "policy" (\v -> do
        policyBytes <- v .: "policy" >>= unhex
        policy <- maybe (fail "invalid native policy key") pure (V.k policyBytes)
        values <- v .: "tokens"
        tokens <- traverse (withObject "token" (\t -> do
          nameBytes <- t .: "name" >>= unhex
          name <- maybe (fail "invalid native token key") pure (V.k nameBytes)
          amount <- integer t "quantity" >>= maybe (fail "invalid native quantity") pure . V.quantity
          pure (name, amount))) values
        pure (policy, tokens))) entries
      value <- case V.fromList policies of
        BR.BuiltinSuccess result -> pure result
        BR.BuiltinSuccessWithLogs _ result -> pure result
        BR.BuiltinFailure _ cause -> fail (show cause)
      pure $ Some $ ValueOf DefaultUniValue value
    "NativeConstant" -> do
      syntax <- o .: "syntax"
      t <- either (fail . show) pure (runQuoteT (UP.parseTerm syntax))
      case t of
        U.Constant _ c -> pure c
        _ -> fail "native constant syntax did not parse as a constant"
    _ -> fail "unknown native constant input"

term :: J.Value -> Parser Term
term = withObject "term" $ \o -> do
  tag <- o .: "tag" :: Parser T.Text
  case tag of
    "Var" -> U.Var () <$> named o
    "Lam" -> U.LamAbs () <$> named o <*> (o .: "body" >>= term)
    "Apply" -> U.Apply () <$> (o .: "function" >>= term) <*> (o .: "argument" >>= term)
    "Delay" -> U.Delay () <$> (o .: "body" >>= term)
    "Force" -> U.Force () <$> (o .: "body" >>= term)
    "Error" -> pure (U.Error ())
    "Constant" -> U.Constant () <$> (o .: "value" >>= constant)
    "Builtin" -> do
      name <- o .: "name"
      maybe (fail "unknown native builtin") (pure . U.Builtin ())
        (find ((== name) . show) [minBound .. maxBound])
    "Constr" -> U.Constr () . fromIntegral <$> index o <*> (o .: "fields" >>= traverse term)
    "Case" -> U.Case () <$> (o .: "scrutinee" >>= term) <*> (o .: "branches" >>= traverse term)
    _ -> fail "unknown term constructor"

ujson :: DefaultUni a -> J.Value
ujson = \case
  DefaultUniInteger -> tagged "Integer" []
  DefaultUniByteString -> tagged "ByteString" []
  DefaultUniString -> tagged "Text" []
  DefaultUniUnit -> tagged "Unit" []
  DefaultUniBool -> tagged "Bool" []
  DefaultUniProtoList -> tagged "ProtoList" []
  DefaultUniProtoArray -> tagged "ProtoArray" []
  DefaultUniProtoPair -> tagged "ProtoPair" []
  DefaultUniData -> tagged "Data" []
  DefaultUniBLS12_381_G1_Element -> tagged "BlsG1" []
  DefaultUniBLS12_381_G2_Element -> tagged "BlsG2" []
  DefaultUniBLS12_381_MlResult -> tagged "BlsMlResult" []
  DefaultUniValue -> tagged "Value" []
  DefaultUniApply f a -> tagged "Apply" ["function" .= ujson f, "argument" .= ujson a]

tagged :: T.Text -> [Pair] -> J.Value
tagged tag fields = object ("tag" .= tag : fields)

universe :: J.Value -> Parser (SomeTypeIn DefaultUni)
universe = withObject "native universe" $ \o -> do
  tag <- o .: "tag" :: Parser T.Text
  case tag of
    "Integer" -> pure (SomeTypeIn DefaultUniInteger)
    "ByteString" -> pure (SomeTypeIn DefaultUniByteString)
    "Text" -> pure (SomeTypeIn DefaultUniString)
    "Unit" -> pure (SomeTypeIn DefaultUniUnit)
    "Bool" -> pure (SomeTypeIn DefaultUniBool)
    "ProtoList" -> pure (SomeTypeIn DefaultUniProtoList)
    "ProtoArray" -> pure (SomeTypeIn DefaultUniProtoArray)
    "ProtoPair" -> pure (SomeTypeIn DefaultUniProtoPair)
    "Data" -> pure (SomeTypeIn DefaultUniData)
    "BlsG1" -> pure (SomeTypeIn DefaultUniBLS12_381_G1_Element)
    "BlsG2" -> pure (SomeTypeIn DefaultUniBLS12_381_G2_Element)
    "BlsMlResult" -> pure (SomeTypeIn DefaultUniBLS12_381_MlResult)
    "Value" -> pure (SomeTypeIn DefaultUniValue)
    "ListInteger" -> pure (SomeTypeIn (DefaultUniList DefaultUniInteger))
    "PairIntegerText" -> pure (SomeTypeIn (DefaultUniPair DefaultUniInteger DefaultUniString))
    "ArrayData" -> pure (SomeTypeIn (DefaultUniArray DefaultUniData))
    "PartialPair" -> pure (SomeTypeIn (DefaultUniApply DefaultUniProtoPair DefaultUniInteger))
    "NestedListArrayByteString" -> pure (SomeTypeIn (DefaultUniList (DefaultUniArray DefaultUniByteString)))
    _ -> fail "unknown universe input"

cjson :: Some (ValueOf DefaultUni) -> Parser J.Value
cjson (Some (ValueOf u x)) = case u of
  DefaultUniInteger -> pure $ tagged "Integer" ["value" .= show x]
  DefaultUniByteString -> pure $ tagged "ByteString" ["hex" .= hex x]
  DefaultUniString -> pure $ tagged "Text" ["value" .= x]
  DefaultUniUnit -> pure $ tagged "UnitValue" []
  DefaultUniBool -> pure $ tagged "Bool" ["value" .= x]
  DefaultUniData -> pure $ tagged "Data" ["cbor" .= hex (LBS.toStrict (serialise x))]
  DefaultUniList a -> do
    xs <- traverse (cjson . Some . ValueOf a) x
    pure $ tagged "List" ["elementType" .= ujson a, "values" .= xs]
  DefaultUniArray a -> do
    xs <- traverse (cjson . Some . ValueOf a) (toList x)
    pure $ tagged "Array" ["elementType" .= ujson a, "values" .= xs]
  DefaultUniPair a b -> do
    l <- cjson (Some (ValueOf a (fst x)))
    r <- cjson (Some (ValueOf b (snd x)))
    pure $ tagged "Pair" ["left" .= l, "right" .= r]
  DefaultUniBLS12_381_G1_Element -> pure $ tagged "BlsG1" ["compressed" .= hex (G1.compress x)]
  DefaultUniBLS12_381_G2_Element -> pure $ tagged "BlsG2" ["compressed" .= hex (G2.compress x)]
  DefaultUniBLS12_381_MlResult -> pure $ tagged "BlsMlResult" []
  DefaultUniValue -> pure $ tagged "Value" ["entries" .= map (\(p, ts) -> object ["policy" .= hex (V.unK p), "tokens" .= map (\(n, q) -> object ["name" .= hex (V.unK n), "quantity" .= show (V.unQuantity q)]) ts]) (V.toList x)]
  DefaultUniApply _ _ -> fail "unhandled native constant universe application"

tjson :: Term -> Parser J.Value
tjson = \case
  U.Var _ (P.NamedDeBruijn n i) -> pure $ tagged "Var" ["name" .= n, "index" .= show (toInteger i)]
  U.LamAbs _ (P.NamedDeBruijn n i) b -> do
    j <- tjson b
    pure $ tagged "Lam" ["name" .= n, "index" .= show (toInteger i), "body" .= j]
  U.Apply _ f a -> do
    j <- tjson f
    k <- tjson a
    pure $ tagged "Apply" ["function" .= j, "argument" .= k]
  U.Delay _ b -> do
    j <- tjson b
    pure $ tagged "Delay" ["body" .= j]
  U.Force _ b -> do
    j <- tjson b
    pure $ tagged "Force" ["body" .= j]
  U.Error _ -> pure $ tagged "Error" []
  U.Builtin _ f -> pure $ tagged "Builtin" ["name" .= show f]
  U.Constant _ c -> do
    j <- cjson c
    pure $ tagged "Constant" ["value" .= j]
  U.Constr _ i fs -> do
    js <- traverse tjson fs
    pure $ tagged "Constr" ["index" .= show i, "fields" .= js]
  U.Case _ s bs -> do
    j <- tjson s
    ks <- traverse tjson (toList bs)
    pure $ tagged "Case" ["scrutinee" .= j, "branches" .= ks]

machine :: J.Value -> Parser (E.MachineError DefaultFun)
machine = withObject "machine error" $ \o -> do
  tag <- o .: "tag" :: Parser T.Text
  case tag of
    "NonPolymorphicInstantiation" -> pure E.NonPolymorphicInstantiationMachineError
    "NonWrapUnwrapped" -> pure E.NonWrapUnwrappedMachineError
    "NonFunctionalApplication" -> pure E.NonFunctionalApplicationMachineError
    "OpenTermEvaluated" -> pure E.OpenTermEvaluatedMachineError
    "Unlifting" -> E.UnliftingMachineError . E.MkUnliftingError <$> o .: "text"
    "BuiltinTermArgumentExpected" -> pure E.BuiltinTermArgumentExpectedMachineError
    "UnexpectedBuiltinTermArgument" -> pure E.UnexpectedBuiltinTermArgumentMachineError
    "NonConstrScrutinized" -> pure E.NonConstrScrutinizedMachineError
    "MissingCaseBranch" -> E.MissingCaseBranchMachineError . fromIntegral <$> index o
    "Panic" -> E.PanicMachineError <$> o .: "text"
    _ -> fail "unknown machine error"

cek :: J.Value -> Parser (E.EvaluationError (E.MachineError DefaultFun) K.CekUserError)
cek = withObject "CEK error" $ \o -> do
  tag <- o .: "tag" :: Parser T.Text
  case tag of
    "Structural" -> E.StructuralError <$> (o .: "cause" >>= machine)
    "Operational" -> E.OperationalError <$> (o .: "cause" >>= withObject "user error" (\u -> do
      kind <- u .: "tag" :: Parser T.Text
      case kind of
        "EvaluationFailure" -> pure K.CekEvaluationFailure
        "CaseBuiltin" -> K.CekCaseBuiltinError <$> u .: "text"
        "OutOfBudget" -> do
          b <- u .: "remaining"
          cpu <- integer b "cpu"
          memory <- integer b "memory"
          pure $ K.CekOutOfExError $ ExRestrictingBudget $ ExBudget (fromInteger cpu) (fromInteger memory)
        _ -> fail "unknown CEK user error"))
    _ -> fail "unknown CEK category"

language :: Int -> Parser C.PlutusLedgerLanguage
language = \case
  1 -> pure C.PlutusV1
  2 -> pure C.PlutusV2
  3 -> pure C.PlutusV3
  4 -> pure C.PlutusV4
  _ -> fail "invalid language"

decodeError :: J.Value -> Parser C.ScriptDecodeError
decodeError = withObject "script decode error" $ \o -> do
  tag <- o .: "tag" :: Parser T.Text
  case tag of
    "Remainder" -> C.RemainderError . LBS.fromStrict <$> (o .: "hex" >>= unhex)
    "CborDeserialiseError" -> do
      offset <- integer o "offset"
      r <- o .: "reason"
      reason <- withObject "decode reason" (\v -> do
        kind <- v .: "tag" :: Parser T.Text
        case kind of
          "EndOfInput" -> pure Flat.EndOfInput
          "ExpectedBytes" -> pure Flat.ExpectedBytes
          "OtherReason" -> Flat.OtherReason <$> v .: "text"
          _ -> fail "unknown decode reason") r
      pure $ C.CBORDeserialiseError $ Flat.DeserialiseFailureInfo (fromInteger offset) reason
    "LedgerLanguageNotAvailable" -> C.LedgerLanguageNotAvailableError <$> (o .: "language" >>= language) <*> (C.MajorProtocolVersion . fromInteger <$> integer o "introduced") <*> (C.MajorProtocolVersion . fromInteger <$> integer o "current")
    "PlutusCoreLanguageNotAvailable" -> do
      v <- o .: "version"
      ver <- P.Version <$> (fromInteger <$> integer v "major") <*> (fromInteger <$> integer v "minor") <*> (fromInteger <$> integer v "patch")
      C.PlutusCoreLanguageNotAvailableError ver <$> (o .: "language" >>= language) <*> (C.MajorProtocolVersion . fromInteger <$> integer o "current")
    _ -> fail "unknown script decode error"

evaluation :: J.Value -> Parser C.EvaluationError
evaluation = withObject "evaluation error" $ \o -> do
  tag <- o .: "tag" :: Parser T.Text
  case tag of
    "Cek" -> do
      e <- o .: "error"
      E.ErrorWithCause <$> (e .: "error" >>= cek) <*> (e .:? "cause" >>= traverse term) >>= pure . C.CekError
    "DeBruijn" -> C.DeBruijnError <$> (o .: "error" >>= withObject "free variable" (\f -> do
      kind <- f .: "tag" :: Parser T.Text
      case kind of
        "FreeIndex" -> P.FreeIndex <$> index f
        "FreeUnique" -> P.FreeUnique . P.Unique . fromInteger <$> integer f "unique"
        _ -> fail "unknown free variable"))
    "Codec" -> C.CodecError <$> (o .: "error" >>= decodeError)
    "CostModelParameterMismatch" -> pure C.CostModelParameterMismatch
    "InvalidReturnValue" -> pure C.InvalidReturnValue
    _ -> fail "unknown evaluation error"

mjson :: E.MachineError DefaultFun -> J.Value
mjson = \case
  E.NonPolymorphicInstantiationMachineError -> tagged "NonPolymorphicInstantiation" []
  E.NonWrapUnwrappedMachineError -> tagged "NonWrapUnwrapped" []
  E.NonFunctionalApplicationMachineError -> tagged "NonFunctionalApplication" []
  E.OpenTermEvaluatedMachineError -> tagged "OpenTermEvaluated" []
  E.UnliftingMachineError (E.MkUnliftingError text) -> tagged "Unlifting" ["text" .= text]
  E.BuiltinTermArgumentExpectedMachineError -> tagged "BuiltinTermArgumentExpected" []
  E.UnexpectedBuiltinTermArgumentMachineError -> tagged "UnexpectedBuiltinTermArgument" []
  E.NonConstrScrutinizedMachineError -> tagged "NonConstrScrutinized" []
  E.MissingCaseBranchMachineError n -> tagged "MissingCaseBranch" ["index" .= show n]
  E.PanicMachineError text -> tagged "Panic" ["text" .= text]

cerrorJson :: E.EvaluationError (E.MachineError DefaultFun) K.CekUserError -> J.Value
cerrorJson = \case
  E.StructuralError err -> tagged "Structural" ["cause" .= mjson err]
  E.OperationalError err -> tagged "Operational" ["cause" .= case err of
    K.CekEvaluationFailure -> tagged "EvaluationFailure" []
    K.CekCaseBuiltinError text -> tagged "CaseBuiltin" ["text" .= text]
    K.CekOutOfExError (ExRestrictingBudget (ExBudget (C.ExCPU cpu) (C.ExMemory memory))) ->
      tagged "OutOfBudget" ["remaining" .= object ["cpu" .= show cpu, "memory" .= show memory]]]

ljson :: C.PlutusLedgerLanguage -> Int
ljson C.PlutusV1 = 1
ljson C.PlutusV2 = 2
ljson C.PlutusV3 = 3
ljson C.PlutusV4 = 4

sjson :: C.ScriptDecodeError -> J.Value
sjson = \case
  C.CBORDeserialiseError (Flat.DeserialiseFailureInfo offset reason) -> tagged "CborDeserialiseError"
    ["offset" .= show offset, "reason" .= case reason of
      Flat.EndOfInput -> tagged "EndOfInput" []
      Flat.ExpectedBytes -> tagged "ExpectedBytes" []
      Flat.OtherReason text -> tagged "OtherReason" ["text" .= text]]
  C.RemainderError bytes -> tagged "Remainder" ["hex" .= hex (LBS.toStrict bytes)]
  C.LedgerLanguageNotAvailableError ll (C.MajorProtocolVersion introduced) (C.MajorProtocolVersion current) ->
    tagged "LedgerLanguageNotAvailable" ["language" .= ljson ll, "introduced" .= show introduced, "current" .= show current]
  C.PlutusCoreLanguageNotAvailableError (P.Version major minor patch) ll (C.MajorProtocolVersion current) ->
    tagged "PlutusCoreLanguageNotAvailable" ["version" .= object ["major" .= show major, "minor" .= show minor, "patch" .= show patch], "language" .= ljson ll, "current" .= show current]

ejson :: C.EvaluationError -> Parser J.Value
ejson = \case
  C.CekError (E.ErrorWithCause e cause) -> do
    c <- traverse tjson cause
    pure $ tagged "Cek" ["error" .= object ["error" .= cerrorJson e, "cause" .= c]]
  C.DeBruijnError e -> pure $ tagged "DeBruijn" ["error" .= case e of
    P.FreeUnique (P.Unique u) -> tagged "FreeUnique" ["unique" .= show u]
    P.FreeIndex i -> tagged "FreeIndex" ["index" .= show (toInteger i)]]
  C.CodecError e -> pure $ tagged "Codec" ["error" .= sjson e]
  C.CostModelParameterMismatch -> pure $ tagged "CostModelParameterMismatch" []
  C.InvalidReturnValue -> pure $ tagged "InvalidReturnValue" []

program :: J.Object -> Term -> Parser (U.Program U.DeBruijn DefaultUni DefaultFun ())
program o t = do
  v <- o .:? "version" .!= object ["major" .= ("1" :: T.Text), "minor" .= ("0" :: T.Text), "patch" .= ("0" :: T.Text)]
  version <- withObject "version" (\x -> P.Version <$> (fromInteger <$> integer x "major") <*> (fromInteger <$> integer x "minor") <*> (fromInteger <$> integer x "patch")) v
  pure $ U.Program () version (U.termMapNames U.unNameDeBruijn t)

doc :: J.Value -> Parser (PP.Doc ())
doc = withObject "document" $ \o -> do
  tag <- o .: "tag" :: Parser T.Text
  case tag of
    "Text" -> PP.pretty <$> (o .: "value" :: Parser T.Text)
    "Line" -> pure PP.line
    "Linebreak" -> pure PP.line'
    "Hardline" -> pure PP.hardline
    "Concat" -> mconcat <$> (o .: "items" >>= traverse doc)
    "Nest" -> PP.nest <$> o .: "indent" <*> (o .: "doc" >>= doc)
    "Group" -> PP.group <$> (o .: "doc" >>= doc)
    "Align" -> PP.align <$> (o .: "doc" >>= doc)
    "FlatAlt" -> PP.flatAlt <$> (o .: "normal" >>= doc) <*> (o .: "flat" >>= doc)
    _ -> fail "unknown document constructor"

-- Native helper controls retain operands separately from the native Text payload.
helper :: J.Object -> Parser (C.EvaluationError, J.Value)
helper o = do
  category <- o .: "category" :: Parser T.Text
  reason <- o .: "reason"
  case category of
    "Unlifting" -> do
      tag <- reason .: "tag" :: Parser T.Text
      failure <- case tag of
        "NotAConstant" -> pure BR.notAConstant
        "TypeErrorBypassed" -> pure BR.underTypeError
        "TypeMismatch" -> do
          input <- o .: "actual" >>= constant
          expected <- o .: "expected" :: Parser T.Text
          let value = U.Constant () input :: Term
              extract = either pure (const (fail "unlifting control unexpectedly succeeded"))
          case expected of
            "Integer" -> extract (BR.readKnown value :: Either BR.BuiltinError Integer)
            "Text" -> extract (BR.readKnown value :: Either BR.BuiltinError T.Text)
            "ListInteger" -> extract (BR.readKnown value :: Either BR.BuiltinError [Integer])
            _ -> fail "unsupported native readKnown control"
        _ -> fail "unknown native unlifting helper"
      native <- case failure of
        BR.BuiltinUnliftingEvaluationError (BR.MkUnliftingEvaluationError (E.StructuralError e)) -> pure e
        _ -> fail "unlifting helper did not produce structural error"
      let err = C.CekError $ E.ErrorWithCause (E.StructuralError (E.UnliftingMachineError native)) Nothing
      pure (err, tagged "Cek" ["error" .= object ["error" .= tagged "Structural" ["cause" .= tagged "Unlifting" ["reason" .= reason]], "cause" .= J.Null]])
    "CaseBuiltin" -> do
      c <- o .: "constant" >>= constant
      count <- o .: "branches"
      mode <- o .: "mode" :: Parser T.Text
      protocol <- o .:? "protocol" .!= 10
      let branches = Vector.replicate count (U.Error () :: Term)
          result = case mode of
            "unavailable" -> BR.unCaserBuiltin (BR.unavailableCaserBuiltin protocol) c branches
            "noData" -> caseBuiltinNoData c branches
            _ -> BR.caseBuiltin c branches
      text <- case result of
        BR.HeadError e -> pure e
        _ -> fail "case helper control unexpectedly succeeded"
      reasonTag <- reason .: "tag" :: Parser T.Text
      normalized <- case reasonTag of
        "OutOfBounds" -> do
          value <- cjson c
          pure $ tagged "OutOfBounds" ["value" .= value, "branches" .= show (count :: Int)]
        "UnsupportedType" -> case c of
          Some (ValueOf u _) -> pure $ tagged "UnsupportedType" ["universe" .= ujson u]
        _ -> pure (J.Object reason)
      let err = C.CekError $ E.ErrorWithCause (E.OperationalError (K.CekCaseBuiltinError text)) Nothing
      pure (err, tagged "Cek" ["error" .= object ["error" .= tagged "Operational" ["cause" .= tagged "CaseBuiltin" ["reason" .= normalized]], "cause" .= J.Null]])
    _ -> fail "unknown native helper category"

run :: J.Value -> Parser J.Value
run = withObject "observation request" $ \o -> do
  ident <- o .: "id" :: Parser T.Text
  kind <- o .: "kind" :: Parser T.Text
  width <- o .:? "width" .!= 80
  ribbon <- o .:? "ribbon" .!= 1.0
  let opts = PP.LayoutOptions (PP.AvailablePerLine width ribbon)
      rendered subject d extra = object $ ["id" .= ident, "kind" .= kind, "subject" .= subject, "width" .= width, "ribbon" .= ribbon, "ribbonColumns" .= (max 0 (min width (floor (fromIntegral width * ribbon))) :: Int),
        "layoutPretty" .= renderString (PP.layoutPretty opts d), "layoutSmart" .= renderString (PP.layoutSmart opts d), "pretty" .= show d] ++ extra
  case kind of
    "BuiltinCatalog" -> pure $ object ["id" .= ident, "kind" .= kind, "names" .= map show ([minBound .. maxBound] :: [DefaultFun])]
    "Term" -> do
      t <- o .: "term" >>= term
      subject <- tjson t
      pure $ rendered subject (prettyClassic t) ["readable" .= show (prettyPlcReadable t)]
    "EvaluationError" -> do
      raw <- o .: "error"
      e <- evaluation raw
      subject <- ejson e
      pure $ rendered subject (PP.pretty e) ["show" .= show e]
    "Decode" -> do
      ll <- o .: "language" >>= language
      pv <- C.MajorProtocolVersion . fromInteger <$> integer o "protocol"
      script <- o .: "script" >>= unhex
      case C.deserialiseScript ll pv (SBS.toShort script) of
        Left e -> do
          let native = C.CodecError e
          subject <- ejson native
          pure $ rendered subject (PP.pretty native) ["show" .= show native]
        Right _ -> fail "decode failure control unexpectedly succeeded"
    "HelperError" -> do
      (e, diagnostic) <- helper o
      subject <- ejson e
      pure $ rendered subject (PP.pretty e) ["show" .= show e, "diagnosticInput" .= diagnostic]
    "Universe" -> do
      SomeTypeIn u <- o .: "universe" >>= universe
      pure $ rendered (ujson u) (PP.pretty u) ["show" .= show (SomeTypeIn u)]
    "Compile" -> do
      t <- o .: "term" >>= term
      p <- program o t
      subject <- tjson t
      pure $ object ["id" .= ident, "kind" .= kind, "subject" .= subject, "script" .= hex (SBS.fromShort (C.serialiseUPLC p))]
    "Evaluate" -> do
      t <- o .: "term" >>= term
      p <- program o t
      ll <- o .: "language" >>= language
      pv <- C.MajorProtocolVersion . fromInteger <$> integer o "protocol"
      model <- o .: "costModel"
      cpu <- o .:? "cpu" .!= ("1152921504606846976" :: T.Text) >>= decimal . J.String
      memory <- o .:? "memory" .!= ("1152921504606846976" :: T.Text) >>= decimal . J.String
      context <- case ll of
        C.PlutusV1 -> either (fail . show) pure (fst (runWriter (runExceptT (V1.mkEvaluationContext model))))
        C.PlutusV2 -> either (fail . show) pure (fst (runWriter (runExceptT (V2.mkEvaluationContext model))))
        C.PlutusV3 -> either (fail . show) pure (fst (runWriter (runExceptT (V3.mkEvaluationContext model))))
        C.PlutusV4 -> fail "V4 has no registered executable profile"
      argumentHex <- o .:? "arguments" .!= []
      arguments <- traverse (\h -> unhex h >>= either (fail . show) pure . deserialiseOrFail . LBS.fromStrict) argumentHex
      let failure logs e = do
            subject <- ejson e
            pure $ rendered subject (PP.pretty e) ["show" .= show e, "logs" .= (logs :: [T.Text]), "outcome" .= ("failure" :: T.Text)]
      case C.deserialiseScript ll pv (C.serialiseUPLC p) of
        Left e -> failure [] (C.CodecError e)
        Right decoded ->
          case C.evaluateScriptRestricting ll pv C.Verbose context (ExBudget (fromInteger cpu) (fromInteger memory)) decoded arguments of
            (logs, Left e) -> failure logs e
            (logs, Right used) -> pure $ object ["id" .= ident, "kind" .= kind, "outcome" .= ("success" :: T.Text), "budget" .= show used, "logs" .= logs]
    "Document" -> do
      raw <- o .: "document"
      d <- doc raw
      pure $ rendered raw d []
    _ -> fail "unknown observation kind"

main :: IO ()
main = do
  raw <- LBS.getContents
  requests <- either die pure (J.eitherDecode raw :: Either String [J.Value])
  outputs <- either die pure (traverse (parseEither run) requests)
  LBS.putStr (J.encode outputs)
