// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus.ndc

import cats.syntax.all.*
import scala.annotation.tailrec
import scalus.cardano.ledger.Word64 as SWord64
import scalus.uplc.{Constant as SConstant, DefaultUni as SUni, NamedDeBruijn, Term as STerm}
import scalus.uplc.builtin.{BuiltinValue, ByteString as SByteString, Data}
import scalus.uplc.builtin.bls12_381.{G1Element as SG1, G2Element as SG2}
import xyz.kd5ujc.scarab.codec.{Bytes, TreeFold}
import xyz.kd5ujc.scarab.plutus.ScalusEvaluator
import xyz.kd5ujc.scarab.uplc.{
  Constant,
  DeBruijn,
  DefaultUni,
  PlutusData,
  ProgramVersion,
  Schemes,
  Term,
  TermF,
  Text,
  Value,
  Word64
}
import xyz.kd5ujc.scarab.uplc.bls.{G1Element, G2Element}

/** Why a term does not convert between the `uplc` module and Scalus. */
enum ConversionError:
  case UnknownBuiltin(name: String)
  case IndexOutOfRange(index: BigInt)
  case VersionOutOfRange(version: String)
  case IllTyped(detail: String)
  case InvalidValue(detail: String)
  case InvalidPoint(error: Throwable)

  /** A constant the other representation cannot be built from: a Miller-loop result, which has no
    * literal and whose value Scalus does not expose.
    */
  case NoCounterpart(constant: String)

  def message: String = this match
    case UnknownBuiltin(name)    => s"no builtin named $name"
    case IndexOutOfRange(index)  => s"variable index $index is out of range"
    case VersionOutOfRange(v)    => s"program version $v is out of range"
    case IllTyped(detail)        => s"ill-typed constant: $detail"
    case InvalidValue(detail)    => s"invalid value constant: $detail"
    case InvalidPoint(error)     => s"invalid BLS12-381 point: $error"
    case NoCounterpart(constant) => s"no Scalus counterpart of $constant"

/** Terms of the `uplc` module and of Scalus, each as the other: the harness reads text with the
  * `uplc` module's parser for both backends, and prints both backends' results with its printer.
  * Terms are walked on the heap ([[Schemes]]); `data` through [[TreeFold]]. Constants other than
  * `data` nest only as deep as their type.
  */
object ScalusTerms:
  private type Converted[A] = Either[ConversionError, A]

  /** A program version as Scalus holds it. */
  def version(v: ProgramVersion): Converted[(Int, Int, Int)] =
    (v.major, v.minor, v.patch) match
      case (a, b, c) if a.isValidInt && b.isValidInt && c.isValidInt =>
        Right((a.toInt, b.toInt, c.toInt))
      case _ => Left(ConversionError.VersionOutOfRange(v.show))

  /** A Scalus program version as the `uplc` module holds it. */
  def ownVersion(v: (Int, Int, Int)): Converted[ProgramVersion] =
    ProgramVersion
      .of(v._1, v._2, v._3)
      .toRight(ConversionError.VersionOutOfRange(s"${v._1}.${v._2}.${v._3}"))

  /** The Scalus term of a term of the `uplc` module; binders are named `i`. */
  def toScalus(term: Term): Converted[STerm] =
    Schemes.cataM[TermF, Converted, STerm](term) {
      case TermF.Var(i) =>
        i.index.toIntOption
          .map(n => STerm.Var(NamedDeBruijn("i", n)))
          .toRight(ConversionError.IndexOutOfRange(i.index.toBigInt))
      case TermF.LamAbs(body)    => Right(STerm.LamAbs("i", body))
      case TermF.Apply(f, a)     => Right(STerm.Apply(f, a))
      case TermF.Force(t)        => Right(STerm.Force(t))
      case TermF.Delay(t)        => Right(STerm.Delay(t))
      case TermF.Constant(c)     => toScalus(c).map(STerm.Const(_))
      case TermF.Builtin(f)      => scalusBuiltin(f.toString).map(STerm.Builtin(_))
      case TermF.Error           => Right(STerm.Error())
      case TermF.Constr(tag, fs) => Right(STerm.Constr(SWord64(tag.bits), fs.toList))
      case TermF.Case(s, bs)     => Right(STerm.Case(s, bs.toList))
    }

  /** The term of the `uplc` module of a Scalus de Bruijn term. */
  def fromScalus(term: STerm): Converted[Term] =
    Schemes.anaM[TermF, Converted, STerm](term) {
      case STerm.Var(name, _) =>
        DeBruijn
          .fromInt(name.index)
          .map(TermF.Var(_))
          .toRight(ConversionError.IndexOutOfRange(BigInt(name.index)))
      case STerm.LamAbs(_, body, _) => Right(TermF.LamAbs(body))
      case STerm.Apply(f, a, _)     => Right(TermF.Apply(f, a))
      case STerm.Force(t, _)        => Right(TermF.Force(t))
      case STerm.Delay(t, _)        => Right(TermF.Delay(t))
      case STerm.Const(c, _)        => fromScalus(c).map(TermF.Constant(_))
      case STerm.Builtin(f, _) =>
        ScalusEvaluator
          .builtinOf(f)
          .map(TermF.Builtin(_))
          .toRight(ConversionError.UnknownBuiltin(f.toString))
      case STerm.Error(_)           => Right(TermF.Error)
      case STerm.Constr(tag, fs, _) => Right(TermF.Constr(Word64.fromBits(tag.value), fs.toVector))
      case STerm.Case(s, bs, _)     => Right(TermF.Case(s, bs.toVector))
    }

  /** Whether a Scalus term holds a BLS12-381 point or Miller-loop result, which the reference's
    * Flat cannot write and Scalus's writes as a call of `uncompress` (another program, with another
    * cost).
    */
  def holdsBls(term: STerm): Boolean =
    @tailrec def go(terms: List[STerm], constants: List[SConstant]): Boolean = constants match
      case c :: rest =>
        c match
          case _: SConstant.BLS12_381_G1_Element | _: SConstant.BLS12_381_G2_Element |
              _: SConstant.BLS12_381_MlResult =>
            true
          case SConstant.List(_, items)  => go(terms, items ++ rest)
          case SConstant.Array(_, items) => go(terms, items.toList ++ rest)
          case SConstant.Pair(a, b)      => go(terms, a :: b :: rest)
          case _                         => go(terms, rest)
      case Nil =>
        terms match
          case Nil => false
          case t :: rest =>
            t match
              case STerm.Const(c, _)      => go(rest, List(c))
              case STerm.LamAbs(_, b, _)  => go(b :: rest, Nil)
              case STerm.Apply(f, a, _)   => go(f :: a :: rest, Nil)
              case STerm.Force(x, _)      => go(x :: rest, Nil)
              case STerm.Delay(x, _)      => go(x :: rest, Nil)
              case STerm.Constr(_, fs, _) => go(fs ++ rest, Nil)
              case STerm.Case(x, bs, _)   => go(x :: bs ++ rest, Nil)
              case _                      => go(rest, Nil)
    go(List(term), Nil)

  private val scalusBuiltins: Map[String, scalus.uplc.DefaultFun] =
    scalus.uplc.DefaultFun.values.iterator.map(f => f.toString -> f).toMap

  private def scalusBuiltin(name: String): Converted[scalus.uplc.DefaultFun] =
    scalusBuiltins.get(name).toRight(ConversionError.UnknownBuiltin(name))

  private def uni(u: DefaultUni): SUni = u match
    case DefaultUni.Integer              => SUni.Integer
    case DefaultUni.ByteString           => SUni.ByteString
    case DefaultUni.String               => SUni.String
    case DefaultUni.Unit                 => SUni.Unit
    case DefaultUni.Bool                 => SUni.Bool
    case DefaultUni.Data                 => SUni.Data
    case DefaultUni.Bls12_381_G1_Element => SUni.BLS12_381_G1_Element
    case DefaultUni.Bls12_381_G2_Element => SUni.BLS12_381_G2_Element
    case DefaultUni.Bls12_381_MlResult   => SUni.BLS12_381_MlResult
    case DefaultUni.Value                => SUni.BuiltinValue
    case DefaultUni.List(e)              => SUni.Apply(SUni.ProtoList, uni(e))
    case DefaultUni.Array(e)             => SUni.Apply(SUni.ProtoArray, uni(e))
    case DefaultUni.Pair(a, b)           => SUni.Apply(SUni.Apply(SUni.ProtoPair, uni(a)), uni(b))

  private def ownUni(u: SUni): Converted[DefaultUni] = u match
    case SUni.Integer                   => Right(DefaultUni.Integer)
    case SUni.ByteString                => Right(DefaultUni.ByteString)
    case SUni.String                    => Right(DefaultUni.String)
    case SUni.Unit                      => Right(DefaultUni.Unit)
    case SUni.Bool                      => Right(DefaultUni.Bool)
    case SUni.Data                      => Right(DefaultUni.Data)
    case SUni.BLS12_381_G1_Element      => Right(DefaultUni.Bls12_381_G1_Element)
    case SUni.BLS12_381_G2_Element      => Right(DefaultUni.Bls12_381_G2_Element)
    case SUni.BLS12_381_MlResult        => Right(DefaultUni.Bls12_381_MlResult)
    case SUni.BuiltinValue              => Right(DefaultUni.Value)
    case SUni.Apply(SUni.ProtoList, e)  => ownUni(e).map(DefaultUni.List(_))
    case SUni.Apply(SUni.ProtoArray, e) => ownUni(e).map(DefaultUni.Array(_))
    case SUni.Apply(SUni.Apply(SUni.ProtoPair, a), b) =>
      (ownUni(a), ownUni(b)).mapN(DefaultUni.Pair(_, _))
    case other => Left(ConversionError.IllTyped(other.toString))

  private def sbytes(b: Bytes): SByteString = SByteString.fromArray(b.toArray)
  private def bytes(b: SByteString): Bytes = Bytes.fromArray(b.bytes)

  private def point[A](make: => A): Converted[A] =
    Either.catchNonFatal(make).leftMap(ConversionError.InvalidPoint(_))

  private def toScalus(c: Constant): Converted[SConstant] = c match
    case Constant.Integer(n)    => Right(SConstant.Integer(n))
    case Constant.ByteString(b) => Right(SConstant.ByteString(sbytes(b)))
    case Constant.String(t)     => Right(SConstant.String(t.string))
    case Constant.Unit          => Right(SConstant.Unit)
    case Constant.Bool(b)       => Right(SConstant.Bool(b))
    case Constant.Data(d)       => Right(SConstant.Data(ScalusEvaluator.toData(d)))
    case Constant.Bls12_381_G1_Element(e) =>
      point(SG1(sbytes(e.compressed))).map(SConstant.BLS12_381_G1_Element(_))
    case Constant.Bls12_381_G2_Element(e) =>
      point(SG2(sbytes(e.compressed))).map(SConstant.BLS12_381_G2_Element(_))
    case Constant.Bls12_381_MlResult(_) => Left(ConversionError.NoCounterpart("bls12_381_mlresult"))
    case Constant.Value(v) =>
      Right(
        SConstant.BuiltinValue(
          BuiltinValue.fromEntryList(
            v.toList.toList.map((currency, tokens) =>
              sbytes(currency.bytes) -> tokens.toList.map((token, q) =>
                sbytes(token.bytes) -> q.amount
              )
            )
          )
        )
      )
    case l: Constant.List =>
      l.items.toList.traverse(toScalus).map(SConstant.List(uni(l.elementType), _))
    case a: Constant.Array =>
      a.items.traverse(toScalus).map(SConstant.Array(uni(a.elementType), _))
    case Constant.Pair(a, b) => (toScalus(a), toScalus(b)).mapN(SConstant.Pair(_, _))

  private def fromScalus(c: SConstant): Converted[Constant] = c match
    case SConstant.Integer(n)    => Right(Constant.Integer(n))
    case SConstant.ByteString(b) => Right(Constant.ByteString(bytes(b)))
    case SConstant.String(s)     => Right(Constant.String(Text.fromString(s)))
    case SConstant.Unit          => Right(Constant.Unit)
    case SConstant.Bool(b)       => Right(Constant.Bool(b))
    case SConstant.Data(d)       => Right(Constant.Data(plutusData(d)))
    case SConstant.BLS12_381_G1_Element(e) =>
      Right(
        Constant.Bls12_381_G1_Element(G1Element.fromCompressed(bytes(e.toCompressedByteString)))
      )
    case SConstant.BLS12_381_G2_Element(e) =>
      Right(
        Constant.Bls12_381_G2_Element(G2Element.fromCompressed(bytes(e.toCompressedByteString)))
      )
    // Scalus keeps the field element of a Miller-loop result private to its package
    case SConstant.BLS12_381_MlResult(_) =>
      Left(ConversionError.NoCounterpart("bls12_381_mlresult"))
    case SConstant.BuiltinValue(v) =>
      Value
        .decoded(
          BuiltinValue
            .toEntryList(v)
            .map((cur, tokens) => bytes(cur) -> tokens.map((t, q) => bytes(t) -> q))
        )
        .map(Constant.Value(_))
        .leftMap(e => ConversionError.InvalidValue(e.toString))
    case SConstant.List(t, items) =>
      for
        elementType <- ownUni(t)
        converted <- items.toVector.traverse(fromScalus)
        list <- Constant.List
          .of(elementType, converted)
          .leftMap(e => ConversionError.IllTyped(e.toString))
      yield list
    case SConstant.Array(t, items) =>
      for
        elementType <- ownUni(t)
        converted <- items.toVector.traverse(fromScalus)
        array <- Constant.Array
          .of(elementType, converted)
          .leftMap(e => ConversionError.IllTyped(e.toString))
      yield array
    case SConstant.Pair(a, b) => (fromScalus(a), fromScalus(b)).mapN(Constant.Pair(_, _))

  /** Scalus's `Data` as the `uplc` module's, walked on the heap. */
  private def plutusData(root: Data): PlutusData =
    import TreeFold.Step
    TreeFold.fold[Data, PlutusData](root) {
      case Data.Constr(tag, fields) =>
        Step.Branch(fields.toScalaList, ds => Right(PlutusData.Constr(tag, ds)))
      case Data.Map(entries) =>
        Step.Branch(
          TreeFold.flatten(entries.toScalaList.toVector),
          ds => Right(PlutusData.Map(TreeFold.pairs(ds)))
        )
      case Data.List(items) => Step.Branch(items.toScalaList, ds => Right(PlutusData.List(ds)))
      case Data.I(v)        => Step.Leaf(PlutusData.I(v))
      case Data.B(b)        => Step.Leaf(PlutusData.B(bytes(b)))
    }
