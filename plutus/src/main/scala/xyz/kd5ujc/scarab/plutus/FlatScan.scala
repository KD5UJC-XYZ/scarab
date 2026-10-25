// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import scala.annotation.tailrec
import scalus.cardano.ledger.Word64
import scalus.serialization.flat.{EncoderState, Flat}
import scalus.uplc.builtin.{BuiltinValue, ByteString}
import scalus.uplc.{
  Constant,
  DeBruijnedProgram,
  DefaultFun,
  DefaultUni,
  NamedDeBruijn,
  ScarabProgramView,
  Term as Uplc
}
import xyz.kd5ujc.scarab.codec.Bytes

/** What one pass over the flat bytes of a script sees that the decoded term no longer holds.
  *
  * The same pass builds the term the machine runs ([[FlatScan.decode]]); Scalus's own flat decoder
  * is not used. It recurses once per level of the term and of a `data` constant (and reads the
  * constant through borer, which stops at 1000 levels), where the reference has no depth limit. The
  * walk is a `@tailrec` loop over immutable frames, each holding the parts of its term already
  * built, so its stack is on the heap (ADR 0011). Its readers are pure functions of the bit
  * position; `docs/performance.md` ("Flat script decoding") measures this form against the
  * imperative walker it replaced.
  *
  * A `value` constant is built normalised, as Scalus holds it (unsorted keys, repeated keys, empty
  * inner maps and zero quantities vanish), and a variable index of 64 bits does not fit Scalus's
  * `Int`. The reference checks the original: `buildValueWith` rejects the malformed value when it
  * decodes (`PlutusCore/Value.hs` 637-694), `checkScope` sees the whole index
  * (`UntypedPlutusCore/Check/Scope.hs` 28-45) and, from protocol 11, the decoder refuses a constant
  * whose type header is longer than 32 tags and a `constr` with more than 1024 fields
  * (`PlutusLedgerApi/Common/Versions.hs` `maxBoundsByPV`, `SerialisedScript.hs`
  * `scriptCBORDecoder`). So the walk records those as it goes.
  *
  * @param malformedValue
  *   the first `value` constant the reference refuses to decode
  * @param maxTypeHeader
  *   the longest type header of a constant, in tags (`defaultUniSize`; always odd)
  * @param maxConstrFields
  *   the most fields of any `constr`
  * @param openVariable
  *   the first variable the ledger's scope check refuses (an index of 0 or above the number of
  *   enclosing lambdas). The reference checks neither `constr` fields nor `case` scrutinee and
  *   branches.
  * @param wideVariables
  *   preorder variable ordinals whose original Word64 cannot fit Scalus's positive Int index; the
  *   decoded term keeps them explicitly open (index -1) without narrowing them
  */
final case class FlatScan(
    malformedValue: Option[FlatScan.ValueFault],
    maxTypeHeader: Int,
    maxConstrFields: Int,
    openVariable: Option[BigInt],
    wideVariables: Set[Int]
)

object FlatScan:
  /** Why `buildValueWith`, `K` or `Quantity` refuse a `value` constant. */
  enum ValueFault:
    case KeyTooLong
    case CurrenciesNotAscending
    case TokensNotAscending
    case EmptyInnerMap
    case ZeroQuantity
    case QuantityOutOfRange

    def message: String = this match
      case KeyTooLong             => "key longer than 32 bytes"
      case CurrenciesNotAscending => "currency symbols not strictly ascending"
      case TokensNotAscending     => "token names not strictly ascending"
      case EmptyInnerMap          => "empty inner map"
      case ZeroQuantity           => "zero quantity"
      case QuantityOutOfRange     => "quantity out of signed 128-bit integer bounds"

  /** Why the reference's flat decoder refuses the bytes (`UntypedPlutusCore/Core/Instance/Flat.hs`
    * and `PlutusCore/FlatInstances.hs`, Plutus 507cab8c).
    */
  enum Failure:
    case Truncated
    case BadTermTag(tag: Int)
    case BadTypeTag(tag: Int)
    case NoFlatConstant(tag: Int)
    case IllFormedType
    case WordOverflow

    /** A `Word64` of more than ten seven-bit groups (`dWord64`, `PlutusCore/Flat/Decoder/Strict.hs`
      * in the pinned Plutus, "Unexpected extra byte in unsigned integer"): the tenth group may hold
      * only bit 63 and may not continue, however small the value.
      */
    case WordTooLong
    case ConstrCaseBefore110

    /** A builtin tag with no builtin (`PlutusCore/Default/Builtins.hs` `decodeBuiltin`: tags 0 to
      * 100 in Plutus 1.70.0.0, the same table as Scalus 1.3.0's encoder).
      */
    case BadBuiltin(tag: Int)

    /** A `string` constant that is not UTF-8 (`dUTF8`, `PlutusCore/Flat/Decoder/Strict.hs`
      * 293-297).
      */
    case InvalidUtf8

    /** A `data` constant whose first CBOR item `decodeData` refuses. */
    case MalformedData(cause: PlutusData.DecodeError)

    /** The input ends before the filler after the program has its final 1 bit (the generated
      * `Filler` decoder of `PlutusCore/Flat/Filler.hs` 27-35 meets the end: `notEnoughSpace`).
      */
    case UnterminatedFiller

    /** Bits remain after the filler: it did not end on the last bit of the last byte
      * (`strictDecoder`, `PlutusCore/Flat/Decoder/Run.hs` 19-24: `tooMuchSpace`).
      */
    case TooMuchSpace(bits: Long)

    def message: String = this match
      case Truncated           => "flat input ends inside a term"
      case BadTermTag(t)       => s"unknown term tag $t"
      case BadTypeTag(t)       => s"unknown type tag $t"
      case NoFlatConstant(t)   => s"constant type tag $t has no flat encoding"
      case IllFormedType       => "ill-formed constant type"
      case WordOverflow        => "natural does not fit 64 bits"
      case WordTooLong         => "natural of more than ten groups of seven bits"
      case ConstrCaseBefore110 => "UPLC program version below 1.1.0 cannot carry constr/case"
      case BadBuiltin(t)       => s"unknown builtin tag $t"
      case InvalidUtf8         => "string constant is not UTF-8"
      case MalformedData(c)    => s"data constant does not decode: $c"
      case UnterminatedFiller  => "flat input ends inside the filler after the program"
      case TooMuchSpace(n)     => s"$n bits remain after the program's filler"

  /** The program the bytes encode, and what the walk saw on the way. */
  final case class Decoded(scan: FlatScan, program: DeBruijnedProgram)

  private type Result[A] = Either[Failure, A]

  /** A value read and the bit position after it. */
  private final case class Got[+A](value: A, at: Long)

  /** The largest variable index Scalus keeps without narrowing. */
  private val IntIndexLimit = BigInt(Int.MaxValue) + 1
  private val WordGroups = 10
  private val QuantityMin = -(BigInt(1) << 127)
  private val QuantityMax = (BigInt(1) << 127) - 1
  private val Version110 = (BigInt(1), BigInt(1), BigInt(0))
  private val Set1: Result[Boolean] = Right(true)
  private val Clear: Result[Boolean] = Right(false)
  private val Missing: Result[Nothing] = Left(Failure.Truncated)

  /** Every result of a read of up to eight bits, built once: a read allocates nothing. */
  private val Small: IArray[Result[Int]] = IArray.tabulate(256)(Right(_))

  /** The 7-bit builtin tags, as Scalus's own encoder writes them (it names the builtins the machine
    * runs).
    */
  private val builtins: Map[Int, DefaultFun] =
    val flat = summon[Flat[DefaultFun]]
    DefaultFun.values.iterator.map { fun =>
      val state = EncoderState(1)
      flat.encode(fun, state)
      ((state.result.headOption.getOrElse(0.toByte) & 0xff) >>> 1) -> fun
    }.toMap

  /** What the walk has seen so far: the fields of the [[FlatScan]] it returns. */
  private final case class Facts(
      malformed: Option[ValueFault],
      typeHeader: Int,
      constrFields: Int,
      open: Option[BigInt],
      wide: Set[Int]
  )

  private object Facts:
    val empty: Facts = Facts(None, 0, 0, None, Set.empty)

  private enum Unary:
    case Delay, Force, Lambda

  private enum Nary:
    case Constr(tag: Word64)
    case Case(scrutinee: Uplc)

  /** What the term walk does next. `depth` is the number of enclosing lambdas and `checked` whether
    * the ledger's scope check descends here.
    */
  private enum Next:
    case Read(depth: Long, checked: Boolean)
    case Give(term: Uplc)

    /** Reads the bit that says whether another field or branch follows. */
    case More(depth: Long, node: Nary, fields: List[Uplc], count: Int)

  /** The terms waiting for the term read next, innermost first: each frame holds the frames `below`
    * it, so the stack is its own list. Fields are held last first.
    */
  private enum Frames:
    case Done
    case Wrap(node: Unary, below: Frames)
    case Function(depth: Long, checked: Boolean, below: Frames)
    case Argument(function: Uplc, below: Frames)
    case Scrutinee(depth: Long, below: Frames)
    case Fields(depth: Long, node: Nary, fields: List[Uplc], count: Int, below: Frames)

  /** What the constant walk does next. */
  private enum Want:
    case Read(ty: DefaultUni)
    case Give(constant: Constant)
    case More(element: DefaultUni, array: Boolean, items: List[Constant])

  /** A constant waiting for the constant read next. Items are held last first. */
  private enum Part:
    case Elements(element: DefaultUni, array: Boolean, items: List[Constant])
    case First(second: DefaultUni)
    case Second(first: Constant)

  private final case class Walked(term: Uplc, facts: Facts)
  private final case class ConstantRead(constant: Constant, fault: Option[ValueFault])
  private final case class ValueRead(
      entries: List[(ByteString, List[(ByteString, BigInt)])],
      fault: Option[ValueFault]
  )
  private final case class TokensRead(tokens: List[(ByteString, BigInt)], fault: Option[ValueFault])

  /** Walks the flat bytes (the program, without its CBOR wrapper). */
  def scan(flat: Array[Byte]): Either[Failure, FlatScan] = decode(flat).map(_.scan)

  /** Decodes the flat bytes (the program, without its CBOR wrapper) into the program Scalus's
    * machine runs, on a heap stack. The program must end the bytes, behind its filler, as the
    * reference's strict `unflatWith` requires; bytes after the script's CBOR item are a separate
    * matter ([[PlutusScriptBytes]]).
    */
  def decode(flat: Array[Byte]): Either[Failure, Decoded] =
    val input = Input(flat)
    for
      major <- input.natural(0L)
      minor <- input.natural(major.at)
      patch <- input.natural(minor.at)
      version = (major.value, minor.value, patch.value)
      allowed = summon[Ordering[(BigInt, BigInt, BigInt)]].gteq(version, Version110)
      walked <- input.walk(
        patch.at,
        Next.Read(0L, checked = true),
        Frames.Done,
        Facts.empty,
        0,
        allowed
      )
      _ <- input.filler(walked.at)
    yield
      val facts = walked.value.facts
      Decoded(
        FlatScan(facts.malformed, facts.typeHeader, facts.constrFields, facts.open, facts.wide),
        ScarabProgramView.program(
          (saturated(version._1), saturated(version._2), saturated(version._3)),
          walked.value.term
        )
      )

  /** A version component as the `Int` Scalus holds; a larger one is no version the ledger knows,
    * and stays one (`PlutusAvailability.versionFailure` refuses it as it refuses the original).
    */
  private def saturated(n: BigInt): Int = if n.isValidInt then n.toInt else Int.MaxValue

  private def compare(a: Array[Byte], b: Array[Byte]): Int =
    @tailrec def from(i: Int): Int =
      if i < a.length && i < b.length && a(i) == b(i) then from(i + 1) else i
    val i = from(0)
    if i < a.length && i < b.length then (a(i) & 0xff) - (b(i) & 0xff) else a.length - b.length

  private def utf8(raw: Array[Byte]): Result[String] =
    val decoder = StandardCharsets.UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    try Right(decoder.decode(ByteBuffer.wrap(raw)).toString)
    catch case _: CharacterCodingException => Left(Failure.InvalidUtf8)

  private def data(raw: Array[Byte]): Result[scalus.uplc.builtin.Data] =
    PlutusData
      .decodePrefix(Bytes.fromArray(raw))
      .left
      .map(Failure.MalformedData(_))
      .map(ScalusEvaluator.toData)

  private def leafType(tag: Int): Result[DefaultUni] = tag match
    case 0  => Right(DefaultUni.Integer)
    case 1  => Right(DefaultUni.ByteString)
    case 2  => Right(DefaultUni.String)
    case 3  => Right(DefaultUni.Unit)
    case 4  => Right(DefaultUni.Bool)
    case 5  => Right(DefaultUni.ProtoList)
    case 6  => Right(DefaultUni.ProtoPair)
    case 8  => Right(DefaultUni.Data)
    case 9  => Right(DefaultUni.BLS12_381_G1_Element)
    case 10 => Right(DefaultUni.BLS12_381_G2_Element)
    case 11 => Right(DefaultUni.BLS12_381_MlResult)
    case 12 => Right(DefaultUni.ProtoArray)
    case 13 => Right(DefaultUni.BuiltinValue)
    case t  => Left(Failure.BadTypeTag(t))

  /** The type a constant's tags describe, built from the last tag (`tags` is last first). */
  @tailrec private def buildType(tags: List[Int], built: List[DefaultUni]): Result[DefaultUni] =
    tags match
      case Nil =>
        built match
          case single :: Nil => Right(single)
          case _             => Left(Failure.IllFormedType)
      case 7 :: rest =>
        built match
          case function :: argument :: below =>
            buildType(rest, DefaultUni.Apply(function, argument) :: below)
          case _ => Left(Failure.IllFormedType)
      case tag :: rest =>
        leafType(tag) match
          case Left(failure) => Left(failure)
          case Right(leaf)   => buildType(rest, leaf :: built)

  /** A `value` entry's faults in the order `buildValueWith` meets them; the first one counts. */
  private def keyFault(
      key: Array[Byte],
      previous: Option[Array[Byte]],
      notAscending: ValueFault
  ): Option[ValueFault] =
    Option
      .when(key.length > 32)(ValueFault.KeyTooLong)
      .orElse(previous.filter(compare(_, key) >= 0).map(_ => notAscending))

  private def quantityFault(quantity: BigInt): Option[ValueFault] =
    if quantity < QuantityMin || quantity > QuantityMax then Some(ValueFault.QuantityOutOfRange)
    else Option.when(quantity == 0)(ValueFault.ZeroQuantity)

  /** The facts after a variable: the first index the scope check refuses, and the wide ones. */
  private def variableFacts(
      index: BigInt,
      depth: Long,
      checked: Boolean,
      ordinal: Int,
      facts: Facts
  ): Facts =
    val scopeViolation = checked && (index <= 0 || index > BigInt(depth))
    val wide = index >= IntIndexLimit
    if (scopeViolation && facts.open.isEmpty) || wide then
      facts.copy(
        open = facts.open.orElse(Option.when(scopeViolation)(index)),
        wide = if wide then facts.wide + ordinal else facts.wide
      )
    else facts

  /** Scalus's name for the variable, of the index narrowed as its decoder narrows it; a wide index
    * is kept open, which Scalus's lookupVarName refuses before indexing the environment
    * (Cek.scala:1465-1478 in the pinned 1.3.0 sources).
    */
  private def variableTerm(index: BigInt): Uplc =
    val wide = index >= IntIndexLimit
    Uplc.Var(NamedDeBruijn(s"i${index.toInt}", if wide then -1 else index.toInt))

  private final class Input(bytes: Array[Byte]):
    private val end = bytes.length.toLong * 8

    private def bitAt(at: Long): Int = (bytes((at >> 3).toInt) >> (7 - (at & 7).toInt)) & 1

    @tailrec private def gather(at: Long, n: Int, acc: Int): Int =
      if n == 0 then acc else gather(at + 1, n - 1, (acc << 1) | bitAt(at))

    /** `n` (at most eight) bits from `at`, or `Truncated` if the input ends before the last. */
    def bits(at: Long, n: Int): Result[Int] =
      if at + n > end then Missing else Small(gather(at, n, 0))

    def flag(at: Long): Result[Boolean] =
      if at >= end then Missing else if bitAt(at) == 1 then Set1 else Clear

    /** A flat natural: groups of seven bits, least significant first, each behind a continue bit.
      * The groups are packed into the big-endian magnitude at the end, so a long natural costs
      * linear time.
      */
    def natural(at: Long): Result[Got[BigInt]] = groups(at, Nil, 0)

    @tailrec private def groups(at: Long, read: List[Int], count: Int): Result[Got[BigInt]] =
      bits(at, 8) match
        case Left(failure) => Left(failure)
        case Right(group) =>
          val next = (group & 0x7f) :: read
          if (group & 0x80) != 0 then groups(at + 8, next, count + 1)
          else Right(Got(magnitude(next.toArray, count + 1), at + 8))

    /** `mostSignificantFirst` holds `n` seven-bit groups; byte `k` (from the least significant)
      * takes its bits from groups `8k / 7` and the one after it.
      */
    private def magnitude(mostSignificantFirst: Array[Int], n: Int): BigInt =
      val length = ((n.toLong * 7 + 7) / 8).toInt
      def group(g: Long): Int = if g < n then mostSignificantFirst(n - 1 - g.toInt) else 0
      BigInt(
        1,
        Array.tabulate(length) { i =>
          val bit = (length - 1 - i).toLong * 8
          val shift = (bit % 7).toInt
          ((group(bit / 7) >>> shift) | (group(bit / 7 + 1) << (7 - shift))).toByte
        }
      )

    /** A flat `Word64`, which the reference reads as at most ten groups of seven bits: the tenth
      * holds bit 63 alone and does not continue, so a padded form of eleven groups is refused even
      * when its value is small, as is a tenth group of bit 64 or above.
      */
    @tailrec def word64(at: Long, index: Int = 0, value: BigInt = 0): Result[Got[BigInt]] =
      bits(at, 8) match
        case Left(failure) => Left(failure)
        case Right(group) if index == WordGroups - 1 && (group & 0x80) != 0 =>
          Left(Failure.WordTooLong)
        case Right(group) if index == WordGroups - 1 && group > 1 => Left(Failure.WordOverflow)
        case Right(group) =>
          val next = value | (BigInt(group & 0x7f) << (7 * index))
          if (group & 0x80) != 0 then word64(at + 8, index + 1, next) else Right(Got(next, at + 8))

    def integer(at: Long): Result[Got[BigInt]] =
      natural(at).map { case Got(n, after) =>
        Got(if n.testBit(0) then -((n + 1) >> 1) else n >> 1, after)
      }

    /** A flat byte string: padding up to a byte boundary, then chunks of up to 255 bytes. */
    def byteString(at: Long): Result[Got[Array[Byte]]] =
      padding(at).flatMap(chunks(_, Nil))

    @tailrec private def padding(at: Long): Result[Long] = flag(at) match
      case Left(failure) => Left(failure)
      case Right(true)   => Right(at + 1)
      case Right(false)  => padding(at + 1)

    @tailrec private def chunks(at: Long, read: List[Array[Byte]]): Result[Got[Array[Byte]]] =
      bits(at, 8) match
        case Left(failure) => Left(failure)
        case Right(0)      => Right(Got(Array.concat(read.reverse*), at + 8))
        case Right(length) =>
          val from = at + 8
          if (from >> 3) + length > bytes.length then Left(Failure.Truncated)
          else
            val start = (from >> 3).toInt
            chunks(from + 8L * length, bytes.slice(start, start + length) :: read)

    /** A constant's type tags, last first, and how many there are. */
    @tailrec private def typeTags(
        at: Long,
        tags: List[Int],
        count: Int
    ): Result[Got[(List[Int], Int)]] =
      flag(at) match
        case Left(failure) => Left(failure)
        case Right(false)  => Right(Got((tags, count), at + 1))
        case Right(true) =>
          bits(at + 1, 4) match
            case Left(failure) => Left(failure)
            case Right(tag)    => typeTags(at + 5, tag :: tags, count + 1)

    /** A `value` constant, as `Flat Value` reads it and `buildValueWith` checks it; the entries as
      * they were written.
      */
    @tailrec private def currencies(
        at: Long,
        previous: Option[Array[Byte]],
        entries: List[(ByteString, List[(ByteString, BigInt)])],
        fault: Option[ValueFault]
    ): Result[Got[ValueRead]] =
      flag(at) match
        case Left(failure) => Left(failure)
        case Right(false)  => Right(Got(ValueRead(entries.reverse, fault), at + 1))
        case Right(true) =>
          byteString(at + 1).flatMap { case Got(currency, after) =>
            tokens(
              after,
              None,
              Nil,
              fault.orElse(keyFault(currency, previous, ValueFault.CurrenciesNotAscending))
            )
              .map(_ -> currency)
          } match
            case Left(failure) => Left(failure)
            case Right((Got(TokensRead(read, seen), after), currency)) =>
              currencies(
                after,
                Some(currency),
                (ByteString.unsafeFromArray(currency) -> read) :: entries,
                seen.orElse(Option.when(read.isEmpty)(ValueFault.EmptyInnerMap))
              )

    @tailrec private def tokens(
        at: Long,
        previous: Option[Array[Byte]],
        read: List[(ByteString, BigInt)],
        fault: Option[ValueFault]
    ): Result[Got[TokensRead]] =
      flag(at) match
        case Left(failure) => Left(failure)
        case Right(false)  => Right(Got(TokensRead(read.reverse, fault), at + 1))
        case Right(true) =>
          byteString(at + 1).flatMap { case Got(token, after) =>
            integer(after).map(quantity => (token, quantity))
          } match
            case Left(failure) => Left(failure)
            case Right((token, Got(quantity, after))) =>
              tokens(
                after,
                Some(token),
                (ByteString.unsafeFromArray(token) -> quantity) :: read,
                fault
                  .orElse(keyFault(token, previous, ValueFault.TokensNotAscending))
                  .orElse(quantityFault(quantity))
              )

    /** The value of a constant of type `ty`, nested lists and pairs on a list of parts. */
    @tailrec private def constant(
        at: Long,
        want: Want,
        parts: List[Part],
        fault: Option[ValueFault]
    ): Result[Got[ConstantRead]] =
      want match
        case Want.Give(c) =>
          parts match
            case Nil => Right(Got(ConstantRead(c, fault), at))
            case Part.Elements(element, array, items) :: rest =>
              constant(at, Want.More(element, array, c :: items), rest, fault)
            case Part.First(second) :: rest =>
              constant(at, Want.Read(second), Part.Second(c) :: rest, fault)
            case Part.Second(first) :: rest =>
              constant(at, Want.Give(Constant.Pair(first, c)), rest, fault)
        case Want.More(element, array, items) =>
          flag(at) match
            case Left(failure) => Left(failure)
            case Right(true) =>
              constant(
                at + 1,
                Want.Read(element),
                Part.Elements(element, array, items) :: parts,
                fault
              )
            case Right(false) =>
              val built =
                if array then Constant.Array(element, items.reverse.toIndexedSeq)
                else Constant.List(element, items.reverse)
              constant(at + 1, Want.Give(built), parts, fault)
        case Want.Read(ty) =>
          ty match
            case DefaultUni.Integer =>
              integer(at) match
                case Left(failure) => Left(failure)
                case Right(Got(n, after)) =>
                  constant(after, Want.Give(Constant.Integer(n)), parts, fault)
            case DefaultUni.ByteString =>
              byteString(at) match
                case Left(failure) => Left(failure)
                case Right(Got(raw, after)) =>
                  constant(
                    after,
                    Want.Give(Constant.ByteString(ByteString.unsafeFromArray(raw))),
                    parts,
                    fault
                  )
            case DefaultUni.String =>
              byteString(at).flatMap(got => utf8(got.value).map(Got(_, got.at))) match
                case Left(failure) => Left(failure)
                case Right(Got(s, after)) =>
                  constant(after, Want.Give(Constant.String(s)), parts, fault)
            case DefaultUni.Unit => constant(at, Want.Give(Constant.Unit), parts, fault)
            case DefaultUni.Bool =>
              flag(at) match
                case Left(failure) => Left(failure)
                case Right(b)      => constant(at + 1, Want.Give(Constant.Bool(b)), parts, fault)
            case DefaultUni.Data =>
              byteString(at).flatMap(got => data(got.value).map(Got(_, got.at))) match
                case Left(failure) => Left(failure)
                case Right(Got(d, after)) =>
                  constant(after, Want.Give(Constant.Data(d)), parts, fault)
            case DefaultUni.BuiltinValue =>
              currencies(at, None, Nil, None) match
                case Left(failure) => Left(failure)
                case Right(Got(ValueRead(entries, seen), after)) =>
                  constant(
                    after,
                    Want.Give(Constant.BuiltinValue(BuiltinValue.fromEntryList(entries))),
                    parts,
                    fault.orElse(seen)
                  )
            case DefaultUni.BLS12_381_G1_Element => Left(Failure.NoFlatConstant(9))
            case DefaultUni.BLS12_381_G2_Element => Left(Failure.NoFlatConstant(10))
            case DefaultUni.BLS12_381_MlResult   => Left(Failure.NoFlatConstant(11))
            case DefaultUni.Apply(DefaultUni.ProtoList, element) =>
              constant(at, Want.More(element, array = false, Nil), parts, fault)
            case DefaultUni.Apply(DefaultUni.ProtoArray, element) =>
              constant(at, Want.More(element, array = true, Nil), parts, fault)
            case DefaultUni.Apply(DefaultUni.Apply(DefaultUni.ProtoPair, first), second) =>
              constant(at, Want.Read(first), Part.First(second) :: parts, fault)
            case _ => Left(Failure.IllFormedType)

    /** A constant term: its type, then its value. */
    private def constantTerm(at: Long, facts: Facts): Result[Got[Walked]] =
      typeTags(at, Nil, 0).flatMap { case Got((tags, count), afterTags) =>
        buildType(tags, Nil).flatMap(ty => constant(afterTags, Want.Read(ty), Nil, None)).map {
          case Got(ConstantRead(c, fault), after) =>
            val updated =
              if count > facts.typeHeader || (fault.isDefined && facts.malformed.isEmpty) then
                facts.copy(
                  typeHeader = math.max(facts.typeHeader, count),
                  malformed = facts.malformed.orElse(fault)
                )
              else facts
            Got(Walked(Uplc.Const(c), updated), after)
        }
      }

    /** The term walk. `ordinal` is the preorder ordinal of the next variable. */
    @tailrec final def walk(
        at: Long,
        next: Next,
        frames: Frames,
        facts: Facts,
        ordinal: Int,
        constrCaseAllowed: Boolean
    ): Result[Got[Walked]] =
      next match
        case Next.Give(term) =>
          frames match
            case Frames.Done => Right(Got(Walked(term, facts), at))
            case Frames.Wrap(node, rest) =>
              val wrapped = node match
                case Unary.Delay  => Uplc.Delay(term)
                case Unary.Force  => Uplc.Force(term)
                case Unary.Lambda => Uplc.LamAbs("i0", term)
              walk(at, Next.Give(wrapped), rest, facts, ordinal, constrCaseAllowed)
            case Frames.Function(depth, checked, rest) =>
              walk(
                at,
                Next.Read(depth, checked),
                Frames.Argument(term, rest),
                facts,
                ordinal,
                constrCaseAllowed
              )
            case Frames.Argument(function, rest) =>
              walk(
                at,
                Next.Give(Uplc.Apply(function, term)),
                rest,
                facts,
                ordinal,
                constrCaseAllowed
              )
            case Frames.Scrutinee(depth, rest) =>
              walk(
                at,
                Next.More(depth, Nary.Case(term), Nil, 0),
                rest,
                facts,
                ordinal,
                constrCaseAllowed
              )
            case Frames.Fields(depth, node, fields, count, rest) =>
              walk(
                at,
                Next.More(depth, node, term :: fields, count + 1),
                rest,
                facts,
                ordinal,
                constrCaseAllowed
              )
        case Next.More(depth, node, fields, count) =>
          flag(at) match
            case Left(failure) => Left(failure)
            case Right(true) =>
              walk(
                at + 1,
                Next.Read(depth, checked = false),
                Frames.Fields(depth, node, fields, count, frames),
                facts,
                ordinal,
                constrCaseAllowed
              )
            case Right(false) =>
              node match
                case Nary.Constr(tag) =>
                  val updated =
                    if count > facts.constrFields then facts.copy(constrFields = count) else facts
                  walk(
                    at + 1,
                    Next.Give(Uplc.Constr(tag, fields.reverse)),
                    frames,
                    updated,
                    ordinal,
                    constrCaseAllowed
                  )
                case Nary.Case(scrutinee) =>
                  walk(
                    at + 1,
                    Next.Give(Uplc.Case(scrutinee, fields.reverse)),
                    frames,
                    facts,
                    ordinal,
                    constrCaseAllowed
                  )
        case Next.Read(depth, checked) =>
          val body = at + 4
          bits(at, 4) match
            case Left(failure) => Left(failure)
            case Right(0) =>
              word64(body) match
                case Left(failure) => Left(failure)
                case Right(Got(index, after)) =>
                  walk(
                    after,
                    Next.Give(variableTerm(index)),
                    frames,
                    variableFacts(index, depth, checked, ordinal, facts),
                    ordinal + 1,
                    constrCaseAllowed
                  )
            case Right(1) =>
              walk(body, next, Frames.Wrap(Unary.Delay, frames), facts, ordinal, constrCaseAllowed)
            case Right(5) =>
              walk(body, next, Frames.Wrap(Unary.Force, frames), facts, ordinal, constrCaseAllowed)
            case Right(2) =>
              walk(
                body,
                Next.Read(depth + 1, checked),
                Frames.Wrap(Unary.Lambda, frames),
                facts,
                ordinal,
                constrCaseAllowed
              )
            case Right(3) =>
              walk(
                body,
                next,
                Frames.Function(depth, checked, frames),
                facts,
                ordinal,
                constrCaseAllowed
              )
            case Right(4) =>
              constantTerm(body, facts) match
                case Left(failure) => Left(failure)
                case Right(Got(Walked(term, updated), after)) =>
                  walk(after, Next.Give(term), frames, updated, ordinal, constrCaseAllowed)
            case Right(6) =>
              walk(body, Next.Give(Uplc.Error()), frames, facts, ordinal, constrCaseAllowed)
            case Right(7) =>
              bits(body, 7).flatMap(tag => builtins.get(tag).toRight(Failure.BadBuiltin(tag))) match
                case Left(failure) => Left(failure)
                case Right(fun) =>
                  walk(
                    body + 7,
                    Next.Give(Uplc.Builtin(fun)),
                    frames,
                    facts,
                    ordinal,
                    constrCaseAllowed
                  )
            case Right(8) if !constrCaseAllowed => Left(Failure.ConstrCaseBefore110)
            case Right(8) =>
              word64(body) match
                case Left(failure) => Left(failure)
                case Right(Got(tag, after)) =>
                  walk(
                    after,
                    Next.More(depth, Nary.Constr(Word64(tag.toLong)), Nil, 0),
                    frames,
                    facts,
                    ordinal,
                    constrCaseAllowed
                  )
            case Right(9) if !constrCaseAllowed => Left(Failure.ConstrCaseBefore110)
            case Right(9) =>
              walk(
                body,
                Next.Read(depth, checked = false),
                Frames.Scrutinee(depth, frames),
                facts,
                ordinal,
                constrCaseAllowed
              )
            case Right(tag) => Left(Failure.BadTermTag(tag))

    /** `unflatWith` reads the program with `postAlignedDecoder` (`PlutusCore/Flat/Filler.hs`
      * 77-81): zero or more 0 bits and a 1, and then `strictDecoder` requires the end of the input
      * exactly there (`PlutusCore/Flat/Decoder/Run.hs` 19-24). A filler longer than it needs to be
      * is accepted, as the reference accepts it.
      */
    @tailrec def filler(at: Long): Result[Unit] =
      if at >= end then Left(Failure.UnterminatedFiller)
      else if bitAt(at) == 0 then filler(at + 1)
      else if at + 1 != end then Left(Failure.TooMuchSpace(end - at - 1))
      else Right(())
