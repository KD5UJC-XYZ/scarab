// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.Eval
import cats.data.EitherT
import cats.syntax.all.*
import io.circe.{Decoder, DecodingFailure, Json}
import scalus.uplc.DefaultFun
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.{PlutusData, ReferenceDiagnostic}

/** Decode the independent observer's canonical structured subjects, retaining native strings only
  * where the native error constructor itself contains Text. This is a fixture/API boundary, never a
  * production provider-message classifier. Recursive decoding is trampolined like rendering.
  */
private[ntc] object ReferencePresentationCases:
  import ReferenceDiagnostic.*
  import ReferenceDocument.{Doc, *}

  enum Kind:
    case Term, Universe, EvaluationError, HelperError, Document, Compile, Evaluate

  enum Outcome:
    case Success, Failure

  private enum Tag:
    case Integer, ByteString, Text, Unit, UnitValue, Bool, ProtoArray, ProtoList, ProtoPair
    case Apply, Data, BlsG1, BlsG2, BlsMlResult, Value, Array, List, Pair
    case Var, Lam, Delay, Force, Constant, Builtin, Error, Constr, Case
    case Cek, DeBruijn, Codec, CostModelParameterMismatch, InvalidReturnValue
    case Structural, Operational, NonPolymorphicInstantiation, NonWrapUnwrapped
    case NonFunctionalApplication, OpenTermEvaluated, Unlifting, BuiltinTermArgumentExpected
    case UnexpectedBuiltinTermArgument, NonConstrScrutinized, MissingCaseBranch, Panic
    case EvaluationFailure, CaseBuiltin, OutOfBudget, FreeUnique, FreeIndex
    case CborDeserialiseError, Remainder, LedgerLanguageNotAvailable, PlutusCoreLanguageNotAvailable
    case EndOfInput, ExpectedBytes, OtherReason
    case NotAConstant, TypeErrorBypassed, TypeMismatch, Unavailable, OutOfBounds
    case ExpectedNonEmptyList, DataUnsupported, NonConstructorData, UnsupportedType
    case Line, Linebreak, Hardline, Concat, Nest, Group, Align, FlatAlt

  enum Error:
    case Field(name: String, cause: DecodingFailure)
    case UnknownTag(value: String)
    case UnexpectedTag(area: String, value: String)
    case Decimal(value: String, cause: NumberFormatException)
    case Hex(cause: Bytes.HexError)
    case Data(cause: PlutusData.DecodeError)
    case Builtin(name: String)
    case Language(number: Int)

  private type Result[A] = EitherT[Eval, Error, A]
  private def pure[A](value: A): Result[A] = EitherT.pure[Eval, Error](value)
  private def lift[A](value: Either[Error, A]): Result[A] = EitherT.fromEither[Eval](value)
  private def defer[A](value: => Result[A]): Result[A] = EitherT(Eval.defer(value.value))
  private def field[A: Decoder](value: Json, name: String): Result[A] =
    lift(value.hcursor.get[A](name).left.map(Error.Field(name, _)))
  private def tag(value: Json): Result[Tag] =
    field[String](value, "tag").flatMap(raw =>
      lift(Tag.values.find(_.toString == raw).toRight(Error.UnknownTag(raw)))
    )
  private def decimal(value: Json, name: String): Result[BigInt] =
    field[String](value, name).flatMap(raw =>
      lift(Either.catchOnly[NumberFormatException](BigInt(raw)).left.map(Error.Decimal(raw, _)))
    )
  private def hex(value: Json, name: String): Result[Bytes] =
    field[String](value, name).flatMap(raw => lift(Bytes.fromHex(raw).left.map(Error.Hex(_))))
  private def child[A](value: Json, name: String)(decode: Json => Result[A]): Result[A] =
    field[Json](value, name).flatMap(json => defer(decode(json)))
  private def children[A](value: Json, name: String)(decode: Json => Result[A]): Result[Vector[A]] =
    field[Vector[Json]](value, name).flatMap(_.traverse(json => defer(decode(json))))
  private def unexpected[A](area: String, value: Tag): Result[A] =
    lift(Left(Error.UnexpectedTag(area, value.toString)))

  def kind(value: Json): Either[Error, Kind] =
    value.hcursor
      .get[String]("kind")
      .left
      .map(Error.Field("kind", _))
      .flatMap(raw => Kind.values.find(_.toString == raw).toRight(Error.UnknownTag(raw)))

  def outcome(value: Json): Either[Error, Outcome] =
    value.hcursor.get[String]("outcome").left.map(Error.Field("outcome", _)).flatMap {
      case "success" => Right(Outcome.Success)
      case "failure" => Right(Outcome.Failure)
      case raw       => Left(Error.UnknownTag(raw))
    }

  def term(value: Json): Either[Error, Term] = termValue(value).value.value
  def universeInput(value: Json): Either[Error, Universe] = universe(value).value.value
  def evaluation(value: Json): Either[Error, EvaluationError] = evaluationValue(value).value.value
  def document(value: Json): Either[Error, Doc] = documentValue(value).value.value

  private def universe(value: Json): Result[Universe] = defer {
    tag(value).flatMap {
      case Tag.Integer     => pure(Universe.Integer)
      case Tag.ByteString  => pure(Universe.ByteString)
      case Tag.Text        => pure(Universe.Text)
      case Tag.Unit        => pure(Universe.Unit)
      case Tag.Bool        => pure(Universe.Bool)
      case Tag.ProtoArray  => pure(Universe.ProtoArray)
      case Tag.ProtoList   => pure(Universe.ProtoList)
      case Tag.ProtoPair   => pure(Universe.ProtoPair)
      case Tag.Data        => pure(Universe.Data)
      case Tag.BlsG1       => pure(Universe.BlsG1)
      case Tag.BlsG2       => pure(Universe.BlsG2)
      case Tag.BlsMlResult => pure(Universe.BlsMlResult)
      case Tag.Value       => pure(Universe.Value)
      case Tag.Apply =>
        (child(value, "function")(universe), child(value, "argument")(universe))
          .mapN(Universe.Apply.apply)
      case other => unexpected("universe", other)
    }
  }

  private def constant(value: Json): Result[Constant] = defer {
    tag(value).flatMap {
      case Tag.Integer    => decimal(value, "value").map(Constant.Integer.apply)
      case Tag.ByteString => hex(value, "hex").map(Constant.ByteString.apply)
      case Tag.Text       => field[String](value, "value").map(Constant.Text.apply)
      case Tag.UnitValue  => pure(Constant.UnitValue)
      case Tag.Bool       => field[Boolean](value, "value").map(Constant.Bool.apply)
      case Tag.Data =>
        hex(value, "cbor")
          .flatMap(bytes => lift(PlutusData.decode(bytes).left.map(Error.Data(_))))
          .map(Constant.Data.apply)
      case Tag.List =>
        (child(value, "elementType")(universe), children(value, "values")(constant))
          .mapN(Constant.List.apply)
      case Tag.Array =>
        (child(value, "elementType")(universe), children(value, "values")(constant))
          .mapN(Constant.Array.apply)
      case Tag.Pair =>
        (child(value, "left")(constant), child(value, "right")(constant)).mapN(Constant.Pair.apply)
      case Tag.BlsG1       => hex(value, "compressed").map(Constant.BlsG1.apply)
      case Tag.BlsG2       => hex(value, "compressed").map(Constant.BlsG2.apply)
      case Tag.BlsMlResult => pure(Constant.BlsMlResult)
      case Tag.Value =>
        children(value, "entries") { entry =>
          (
            hex(entry, "policy"),
            children(entry, "tokens") { token =>
              (hex(token, "name"), decimal(token, "quantity")).tupled
            }
          ).tupled
        }.map(Constant.Value.apply)
      case other => unexpected("constant", other)
    }
  }

  private def termValue(value: Json): Result[Term] = defer {
    tag(value).flatMap {
      case Tag.Var => (field[String](value, "name"), decimal(value, "index")).mapN(Term.Var.apply)
      case Tag.Lam =>
        (field[String](value, "name"), decimal(value, "index"), child(value, "body")(termValue))
          .mapN(Term.Lam.apply)
      case Tag.Apply =>
        (child(value, "function")(termValue), child(value, "argument")(termValue))
          .mapN(Term.Apply.apply)
      case Tag.Delay    => child(value, "body")(termValue).map(Term.Delay.apply)
      case Tag.Force    => child(value, "body")(termValue).map(Term.Force.apply)
      case Tag.Constant => child(value, "value")(constant).map(Term.Constant.apply)
      case Tag.Builtin =>
        field[String](value, "name")
          .flatMap(raw =>
            lift(DefaultFun.values.find(_.toString == raw).toRight(Error.Builtin(raw)))
          )
          .map(Term.Builtin.apply)
      case Tag.Error => pure(Term.Error)
      case Tag.Constr =>
        (decimal(value, "index"), children(value, "fields")(termValue)).mapN(Term.Constr.apply)
      case Tag.Case =>
        (child(value, "scrutinee")(termValue), children(value, "branches")(termValue))
          .mapN(Term.Case.apply)
      case other => unexpected("term", other)
    }
  }

  private def machine(value: Json): Result[MachineError] = defer {
    tag(value).flatMap {
      case Tag.NonPolymorphicInstantiation => pure(MachineError.NonPolymorphicInstantiation)
      case Tag.NonWrapUnwrapped            => pure(MachineError.NonWrapUnwrapped)
      case Tag.NonFunctionalApplication    => pure(MachineError.NonFunctionalApplication)
      case Tag.OpenTermEvaluated           => pure(MachineError.OpenTermEvaluated)
      case Tag.Unlifting =>
        if value.hcursor.downField("reason").succeeded then
          child(value, "reason")(unlifting).map(MachineError.Unlifting.apply)
        else field[String](value, "text").map(text => MachineError.Unlifting(UnliftingError.NativeMessage(text)))
      case Tag.BuiltinTermArgumentExpected   => pure(MachineError.BuiltinTermArgumentExpected)
      case Tag.UnexpectedBuiltinTermArgument => pure(MachineError.UnexpectedBuiltinTermArgument)
      case Tag.NonConstrScrutinized          => pure(MachineError.NonConstrScrutinized)
      case Tag.MissingCaseBranch =>
        decimal(value, "index").map(MachineError.MissingCaseBranch.apply)
      case Tag.Panic => field[String](value, "text").map(MachineError.Panic.apply)
      case other     => unexpected("machine error", other)
    }
  }

  private def unlifting(value: Json): Result[UnliftingError] = defer {
    tag(value).flatMap {
      case Tag.NotAConstant => pure(UnliftingError.NotAConstant)
      case Tag.TypeErrorBypassed => pure(UnliftingError.TypeErrorBypassed)
      case Tag.TypeMismatch =>
        (child(value, "expected")(universe), child(value, "actual")(universe)).mapN(UnliftingError.TypeMismatch.apply)
      case other => unexpected("unlifting error", other)
    }
  }

  private def caseBuiltin(value: Json): Result[CaseBuiltinError] = defer {
    tag(value).flatMap {
      case Tag.Unavailable => decimal(value, "protocol").map(CaseBuiltinError.Unavailable.apply)
      case Tag.OutOfBounds =>
        (child(value, "value")(constant), decimal(value, "branches")).mapN(CaseBuiltinError.OutOfBounds.apply)
      case Tag.ExpectedNonEmptyList => pure(CaseBuiltinError.ExpectedNonEmptyList)
      case Tag.DataUnsupported => pure(CaseBuiltinError.DataUnsupported)
      case Tag.NonConstructorData => pure(CaseBuiltinError.NonConstructorData)
      case Tag.UnsupportedType => child(value, "universe")(universe).map(CaseBuiltinError.UnsupportedType.apply)
      case other => unexpected("builtin case error", other)
    }
  }

  private def cek(value: Json): Result[CekError] = defer {
    tag(value).flatMap {
      case Tag.Structural  => child(value, "cause")(machine).map(CekError.Structural.apply)
      case Tag.Operational => child(value, "cause")(user).map(CekError.Operational.apply)
      case other           => unexpected("CEK category", other)
    }
  }

  private def user(value: Json): Result[CekUserError] = defer {
    tag(value).flatMap {
      case Tag.EvaluationFailure => pure(CekUserError.EvaluationFailure)
      case Tag.CaseBuiltin =>
        if value.hcursor.downField("reason").succeeded then
          child(value, "reason")(caseBuiltin).map(CekUserError.CaseBuiltin.apply)
        else field[String](value, "text").map(text => CekUserError.CaseBuiltin(CaseBuiltinError.NativeMessage(text)))
      case Tag.OutOfBudget =>
        child(value, "remaining")(v => (decimal(v, "cpu"), decimal(v, "memory")).mapN(Budget.apply))
          .map(CekUserError.OutOfBudget.apply)
      case other => unexpected("CEK user error", other)
    }
  }

  private def free(value: Json): Result[FreeVariableError] = defer {
    tag(value).flatMap {
      case Tag.FreeIndex  => decimal(value, "index").map(FreeVariableError.FreeIndex.apply)
      case Tag.FreeUnique => decimal(value, "unique").map(FreeVariableError.FreeUnique.apply)
      case other          => unexpected("free variable", other)
    }
  }

  private def reason(value: Json): Result[DeserialiseFailureReason] = defer {
    tag(value).flatMap {
      case Tag.EndOfInput    => pure(DeserialiseFailureReason.EndOfInput)
      case Tag.ExpectedBytes => pure(DeserialiseFailureReason.ExpectedBytes)
      case Tag.OtherReason =>
        field[String](value, "text").map(DeserialiseFailureReason.OtherReason.apply)
      case other => unexpected("deserialisation reason", other)
    }
  }

  private def language(value: Json): Result[LedgerLanguage] =
    field[Int](value, "language").flatMap(number =>
      lift(LedgerLanguage.values.lift(number - 1).toRight(Error.Language(number)))
    )

  private def version(value: Json): Result[Version] =
    (decimal(value, "major"), decimal(value, "minor"), decimal(value, "patch")).mapN(Version.apply)

  private def decode(value: Json): Result[ScriptDecodeError] = defer {
    tag(value).flatMap {
      case Tag.CborDeserialiseError =>
        (decimal(value, "offset"), child(value, "reason")(reason))
          .mapN(ScriptDecodeError.CborDeserialiseError.apply)
      case Tag.Remainder => hex(value, "hex").map(ScriptDecodeError.Remainder.apply)
      case Tag.LedgerLanguageNotAvailable =>
        (language(value), decimal(value, "introduced"), decimal(value, "current"))
          .mapN(ScriptDecodeError.LedgerLanguageNotAvailable.apply)
      case Tag.PlutusCoreLanguageNotAvailable =>
        (child(value, "version")(version), language(value), decimal(value, "current"))
          .mapN(ScriptDecodeError.PlutusCoreLanguageNotAvailable.apply)
      case other => unexpected("script decode error", other)
    }
  }

  private def evaluationValue(value: Json): Result[EvaluationError] = defer {
    tag(value).flatMap {
      case Tag.Cek =>
        child(value, "error") { wrapped =>
          (
            child(wrapped, "error")(cek),
            field[Option[Json]](wrapped, "cause").flatMap(
              _.traverse(json => defer(termValue(json)))
            )
          ).mapN(ErrorWithCause.apply)
        }.map(EvaluationError.Cek.apply)
      case Tag.DeBruijn => child(value, "error")(free).map(EvaluationError.DeBruijn.apply)
      case Tag.Codec    => child(value, "error")(decode).map(EvaluationError.Codec.apply)
      case Tag.CostModelParameterMismatch => pure(EvaluationError.CostModelParameterMismatch)
      case Tag.InvalidReturnValue         => pure(EvaluationError.InvalidReturnValue)
      case other                          => unexpected("evaluation error", other)
    }
  }

  private def documentValue(value: Json): Result[Doc] = defer {
    tag(value).flatMap {
      case Tag.Text      => field[String](value, "value").map(text)
      case Tag.Line      => pure(line)
      case Tag.Linebreak => pure(lineBreak)
      case Tag.Hardline  => pure(Doc.Line)
      case Tag.Concat    => children(value, "items")(documentValue).map(join(Doc.Empty, _))
      case Tag.Nest =>
        (field[Int](value, "indent"), child(value, "doc")(documentValue))
          .mapN((amount, doc) => doc.nest(amount))
      case Tag.Align => child(value, "doc")(documentValue).map(_.align)
      case Tag.Group => child(value, "doc")(documentValue).map(_.group)
      case Tag.FlatAlt =>
        (child(value, "normal")(documentValue), child(value, "flat")(documentValue))
          .mapN(Doc.Alternative.apply)
      case other => unexpected("document", other)
    }
  }
