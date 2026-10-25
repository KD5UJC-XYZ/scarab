// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import scala.collection.mutable.{ArrayDeque, ListBuffer}
import scala.util.boundary
import scala.util.boundary.{Label, break}
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
  * walk keeps the work still to do and the values built so far on two heap stacks (ADR 0011).
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

  /** The largest variable index Scalus keeps without narrowing. */
  private val IntIndexLimit = BigInt(Int.MaxValue) + 1
  private val WordGroups = 10
  private val QuantityMin = -(BigInt(1) << 127)
  private val QuantityMax = (BigInt(1) << 127) - 1

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

  // The work still to do, innermost first. `depth` is the number of enclosing lambdas and
  // `checked` whether the ledger's scope check descends here.
  private sealed trait Task
  private final case class Read(depth: Long, checked: Boolean) extends Task
  private final case class Wrap(node: Unary) extends Task
  private case object Join extends Task
  private case object Lift extends Task
  private final case class Fields(depth: Long, count: Count, node: Nary) extends Task
  private final case class Value(ty: DefaultUni) extends Task
  private final case class Elements(element: DefaultUni, count: Count, array: Boolean) extends Task
  private case object Couple extends Task
  private final class Count(var n: Int)

  /** A term built over the one term read before it. */
  private enum Unary:
    case Delay, Force, Lambda

  /** A term built over the terms its list of fields or branches counted. */
  private enum Nary:
    case Constr(tag: Word64)
    case Case

  /** Walks the flat bytes (the program, without its CBOR wrapper). */
  def scan(flat: Array[Byte]): Either[Failure, FlatScan] = decode(flat).map(_.scan)

  /** Decodes the flat bytes (the program, without its CBOR wrapper) into the program Scalus's
    * machine runs, with an explicit stack. The program must end the bytes, behind its filler, as
    * the reference's strict `unflatWith` requires; bytes after the script's CBOR item are a
    * separate matter ([[PlutusScriptBytes]]).
    */
  def decode(flat: Array[Byte]): Either[Failure, Decoded] = boundary:
    Right(new Walker(flat).run())

  /** A version component as the `Int` Scalus holds; a larger one is no version the ledger knows,
    * and stays one (`PlutusAvailability.versionFailure` refuses it as it refuses the original).
    */
  private def saturated(n: BigInt): Int = if n.isValidInt then n.toInt else Int.MaxValue

  private final class Walker(bytes: Array[Byte])(using Label[Either[Failure, Decoded]]):
    private var position = 0L // in bits
    private var malformed: Option[ValueFault] = None
    private var typeHeader = 0
    private var constrFields = 0
    private var open: Option[BigInt] = None
    private var variableOrdinal = 0
    private var wideVariables = Set.empty[Int]
    private var constrCaseAllowed = false
    private val tasks = ArrayDeque.empty[Task]
    // The values built so far, last on top. Every task that pops pushed its children's tasks before
    // its own was run, so the children are there.
    private val terms = ArrayDeque.empty[Uplc]
    private val constants = ArrayDeque.empty[Constant]

    private def abort(failure: Failure): Nothing = break(Left(failure))

    private def bit(): Int =
      val at = (position >> 3).toInt
      if position >> 3 >= bytes.length then abort(Failure.Truncated)
      val b = (bytes(at) >> (7 - (position & 7).toInt)) & 1
      position += 1
      b

    private def bits(n: Int): Int =
      var acc = 0
      var i = 0
      while i < n do
        acc = (acc << 1) | bit()
        i += 1
      acc

    /** A flat natural: groups of seven bits, least significant first, each behind a continue bit.
      */
    private def natural(): BigInt =
      val chunks = Array.newBuilder[Byte]
      var more = true
      while more do
        val group = bits(8)
        chunks += (group & 0x7f).toByte
        more = (group & 0x80) != 0
      // Construct BigInt once: extending one per seven-bit group allocates quadratically for a
      // long untrusted natural. Pack little-endian groups into its big-endian magnitude instead.
      val groups = chunks.result()
      val magnitude = new Array[Byte](((groups.length.toLong * 7 + 7) / 8).toInt)
      groups.indices.foreach { index =>
        val offset = index.toLong * 7
        val at = magnitude.length - 1 - (offset / 8).toInt
        val shift = (offset % 8).toInt
        val group = groups(index) & 0x7f
        magnitude(at) = (magnitude(at) | (group << shift)).toByte
        if shift > 1 && at > 0 then
          magnitude(at - 1) = (magnitude(at - 1) | (group >>> (8 - shift))).toByte
      }
      BigInt(1, magnitude)

    /** A flat `Word64`, which the reference reads as at most ten groups of seven bits: the tenth
      * holds bit 63 alone and does not continue, so a padded form of eleven groups is refused even
      * when its value is small, as is a tenth group of bit 64 or above.
      */
    private def word64(): BigInt =
      var value = BigInt(0)
      var index = 0
      var more = true
      while more do
        val group = bits(8)
        if index == WordGroups - 1 then
          if (group & 0x80) != 0 then abort(Failure.WordTooLong)
          if group > 1 then abort(Failure.WordOverflow)
        value = value | (BigInt(group & 0x7f) << (7 * index))
        more = (group & 0x80) != 0
        index += 1
      value

    private def integer(): BigInt =
      val n = natural()
      if n.testBit(0) then -((n + 1) >> 1) else n >> 1

    /** A flat byte string: padding up to a byte boundary, then chunks of up to 255 bytes. */
    private def byteString(): Array[Byte] =
      while bit() == 0 do ()
      val out = Array.newBuilder[Byte]
      var length = bits(8)
      while length != 0 do
        if (position >> 3) + length > bytes.length then abort(Failure.Truncated)
        val from = (position >> 3).toInt
        out ++= bytes.slice(from, from + length)
        position += 8L * length
        length = bits(8)
      out.result()

    private def utf8(raw: Array[Byte]): String =
      val decoder = StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
      try decoder.decode(ByteBuffer.wrap(raw)).toString
      catch case _: CharacterCodingException => abort(Failure.InvalidUtf8)

    private def data(raw: Array[Byte]): scalus.uplc.builtin.Data =
      PlutusData
        .decodePrefix(Bytes.fromArray(raw))
        .fold(error => abort(Failure.MalformedData(error)), ScalusEvaluator.toData)

    private def compare(a: Array[Byte], b: Array[Byte]): Int =
      val n = math.min(a.length, b.length)
      var i = 0
      while i < n && a(i) == b(i) do i += 1
      if i < n then (a(i) & 0xff) - (b(i) & 0xff) else a.length - b.length

    private def fault(f: ValueFault): Unit = if malformed.isEmpty then malformed = Some(f)

    /** A `value` constant, as `Flat Value` reads it and `buildValueWith` checks it; the entries as
      * they were written.
      */
    private def valueConstant(): List[(ByteString, List[(ByteString, BigInt)])] =
      val entries = ListBuffer.empty[(ByteString, List[(ByteString, BigInt)])]
      var previousCurrency: Option[Array[Byte]] = None
      while bit() == 1 do
        val currency = byteString()
        if currency.length > 32 then fault(ValueFault.KeyTooLong)
        previousCurrency.foreach(p =>
          if compare(p, currency) >= 0 then fault(ValueFault.CurrenciesNotAscending)
        )
        previousCurrency = Some(currency)
        var previousToken: Option[Array[Byte]] = None
        val tokens = ListBuffer.empty[(ByteString, BigInt)]
        while bit() == 1 do
          val token = byteString()
          val quantity = integer()
          if token.length > 32 then fault(ValueFault.KeyTooLong)
          previousToken.foreach(p =>
            if compare(p, token) >= 0 then fault(ValueFault.TokensNotAscending)
          )
          previousToken = Some(token)
          if quantity < QuantityMin || quantity > QuantityMax then
            fault(ValueFault.QuantityOutOfRange)
          else if quantity == 0 then fault(ValueFault.ZeroQuantity)
          tokens += ByteString.unsafeFromArray(token) -> quantity
        if tokens.isEmpty then fault(ValueFault.EmptyInnerMap)
        entries += ByteString.unsafeFromArray(currency) -> tokens.toList
      entries.toList

    /** The tags of a constant's type, built into a tree from the end (a stack, not recursion). */
    private def constantType(): DefaultUni =
      val tags = Vector.newBuilder[Int]
      while bit() == 1 do tags += bits(4)
      val all = tags.result()
      typeHeader = math.max(typeHeader, all.length)
      val built = ArrayDeque.empty[DefaultUni]
      all.reverseIterator.foreach {
        case 7 =>
          if built.size < 2 then abort(Failure.IllFormedType)
          val function = built.removeLast()
          val argument = built.removeLast()
          built.append(DefaultUni.Apply(function, argument))
        case 0  => built.append(DefaultUni.Integer)
        case 1  => built.append(DefaultUni.ByteString)
        case 2  => built.append(DefaultUni.String)
        case 3  => built.append(DefaultUni.Unit)
        case 4  => built.append(DefaultUni.Bool)
        case 5  => built.append(DefaultUni.ProtoList)
        case 6  => built.append(DefaultUni.ProtoPair)
        case 8  => built.append(DefaultUni.Data)
        case 9  => built.append(DefaultUni.BLS12_381_G1_Element)
        case 10 => built.append(DefaultUni.BLS12_381_G2_Element)
        case 11 => built.append(DefaultUni.BLS12_381_MlResult)
        case 12 => built.append(DefaultUni.ProtoArray)
        case 13 => built.append(DefaultUni.BuiltinValue)
        case t  => abort(Failure.BadTypeTag(t))
      }
      if built.size != 1 then abort(Failure.IllFormedType)
      built.head

    private def variable(index: BigInt, depth: Long, checked: Boolean): Uplc =
      val scopeViolation = checked && (index <= 0 || index > BigInt(depth))
      if open.isEmpty && scopeViolation then open = Some(index)
      val wide = index >= IntIndexLimit
      if wide then wideVariables += variableOrdinal
      variableOrdinal += 1
      // Scalus's name for the variable, of the index narrowed as its decoder narrows it; a wide
      // index is kept open, which Scalus's lookupVarName refuses before indexing the environment
      // (Cek.scala:1465-1478 in the pinned 1.3.0 sources).
      Uplc.Var(NamedDeBruijn(s"i${index.toInt}", if wide then -1 else index.toInt))

    private def popTerms(n: Int): List[Uplc] =
      var out = List.empty[Uplc]
      var i = 0
      while i < n do
        out = terms.removeLast() :: out
        i += 1
      out

    private def popConstants(n: Int): List[Constant] =
      var out = List.empty[Constant]
      var i = 0
      while i < n do
        out = constants.removeLast() :: out
        i += 1
      out

    def run(): Decoded =
      val version = (natural(), natural(), natural())
      constrCaseAllowed = summon[Ordering[(BigInt, BigInt, BigInt)]].gteq(version, (1, 1, 0))
      tasks.append(Read(0L, checked = true))
      while tasks.nonEmpty do step(tasks.removeLast())
      filler()
      val program = ScarabProgramView.program(
        (saturated(version._1), saturated(version._2), saturated(version._3)),
        terms.removeLast()
      )
      Decoded(FlatScan(malformed, typeHeader, constrFields, open, wideVariables), program)

    /** `unflatWith` reads the program with `postAlignedDecoder` (`PlutusCore/Flat/Filler.hs`
      * 77-81): zero or more 0 bits and a 1, and then `strictDecoder` requires the end of the input
      * exactly there. A filler longer than it needs to be is accepted, as the reference accepts it.
      */
    private def filler(): Unit =
      val end = bytes.length.toLong * 8
      var one = false
      while !one do
        if position >= end then abort(Failure.UnterminatedFiller)
        one = bit() == 1
      if position != end then abort(Failure.TooMuchSpace(end - position))

    private def step(task: Task): Unit = task match
      case Read(depth, checked) =>
        bits(4) match
          case 0 => terms.append(variable(word64(), depth, checked))
          case 1 =>
            tasks.append(Wrap(Unary.Delay))
            tasks.append(Read(depth, checked))
          case 5 =>
            tasks.append(Wrap(Unary.Force))
            tasks.append(Read(depth, checked))
          case 2 =>
            tasks.append(Wrap(Unary.Lambda))
            tasks.append(Read(depth + 1, checked))
          case 3 =>
            tasks.append(Join)
            tasks.append(Read(depth, checked))
            tasks.append(Read(depth, checked))
          case 4 =>
            val ty = constantType()
            tasks.append(Lift)
            tasks.append(Value(ty))
          case 6 => terms.append(Uplc.Error())
          case 7 =>
            val tag = bits(7)
            terms.append(Uplc.Builtin(builtins.getOrElse(tag, abort(Failure.BadBuiltin(tag)))))
          case 8 =>
            if !constrCaseAllowed then abort(Failure.ConstrCaseBefore110)
            val tag = word64()
            tasks.append(Fields(depth, Count(0), Nary.Constr(Word64(tag.toLong))))
          case 9 =>
            if !constrCaseAllowed then abort(Failure.ConstrCaseBefore110)
            tasks.append(Fields(depth, Count(0), Nary.Case))
            tasks.append(Read(depth, checked = false))
          case tag => abort(Failure.BadTermTag(tag))
      case Wrap(node) =>
        val body = terms.removeLast()
        terms.append(node match
          case Unary.Delay  => Uplc.Delay(body)
          case Unary.Force  => Uplc.Force(body)
          case Unary.Lambda => Uplc.LamAbs("i0", body))
      case Join =>
        val argument = terms.removeLast()
        val function = terms.removeLast()
        terms.append(Uplc.Apply(function, argument))
      case Lift => terms.append(Uplc.Const(constants.removeLast()))
      case Fields(depth, count, node) =>
        if bit() == 1 then
          count.n += 1
          tasks.append(task)
          tasks.append(Read(depth, checked = false))
        else
          node match
            case Nary.Constr(tag) =>
              constrFields = math.max(constrFields, count.n)
              terms.append(Uplc.Constr(tag, popTerms(count.n)))
            case Nary.Case =>
              val branches = popTerms(count.n)
              val scrutinee = terms.removeLast()
              terms.append(Uplc.Case(scrutinee, branches))
      case Elements(element, count, array) =>
        if bit() == 1 then
          count.n += 1
          tasks.append(task)
          tasks.append(Value(element))
        else
          val items = popConstants(count.n)
          constants.append(
            if array then Constant.Array(element, items.toIndexedSeq)
            else Constant.List(element, items)
          )
      case Couple =>
        val second = constants.removeLast()
        val first = constants.removeLast()
        constants.append(Constant.Pair(first, second))
      case Value(ty) =>
        ty match
          case DefaultUni.Integer => constants.append(Constant.Integer(integer()))
          case DefaultUni.ByteString =>
            constants.append(Constant.ByteString(ByteString.unsafeFromArray(byteString())))
          case DefaultUni.String => constants.append(Constant.String(utf8(byteString())))
          case DefaultUni.Unit   => constants.append(Constant.Unit)
          case DefaultUni.Bool   => constants.append(Constant.Bool(bit() == 1))
          case DefaultUni.Data   => constants.append(Constant.Data(data(byteString())))
          case DefaultUni.BuiltinValue =>
            constants.append(Constant.BuiltinValue(BuiltinValue.fromEntryList(valueConstant())))
          case DefaultUni.BLS12_381_G1_Element => abort(Failure.NoFlatConstant(9))
          case DefaultUni.BLS12_381_G2_Element => abort(Failure.NoFlatConstant(10))
          case DefaultUni.BLS12_381_MlResult   => abort(Failure.NoFlatConstant(11))
          case DefaultUni.Apply(DefaultUni.ProtoList, element) =>
            tasks.append(Elements(element, Count(0), array = false))
          case DefaultUni.Apply(DefaultUni.ProtoArray, element) =>
            tasks.append(Elements(element, Count(0), array = true))
          case DefaultUni.Apply(DefaultUni.Apply(DefaultUni.ProtoPair, first), second) =>
            tasks.append(Couple)
            tasks.append(Value(second))
            tasks.append(Value(first))
          case _ => abort(Failure.IllFormedType)
