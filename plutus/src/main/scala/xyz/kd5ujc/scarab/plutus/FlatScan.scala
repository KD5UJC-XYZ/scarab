// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import scala.collection.mutable.ArrayDeque
import scala.util.control.ControlThrowable

/** What one pass over the flat bytes of a script sees and the decoded term no longer holds.
  *
  * Scalus decodes the flat program into a term and drops information on the way: a `value` constant
  * is rebuilt as a normalised map (unsorted keys, repeated keys, empty inner maps and zero
  * quantities vanish), and a variable index of 64 bits is narrowed to an `Int`. The reference
  * checks the original: `buildValueWith` rejects the malformed value when it decodes
  * (`PlutusCore/Value.hs` 637-694), `checkScope` sees the whole index
  * (`UntypedPlutusCore/Check/Scope.hs` 28-45) and, from protocol 11, the decoder refuses a constant
  * whose type header is longer than 32 tags and a `constr` with more than 1024 fields
  * (`PlutusLedgerApi/Common/Versions.hs` `maxBoundsByPV`, `SerialisedScript.hs`
  * `scriptCBORDecoder`). So the bytes are walked once more, after Scalus accepted them, with an
  * explicit stack (the nesting comes from the script).
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
  *   preorder variable ordinals whose original Word64 cannot fit Scalus's positive Int index;
  *   [[WideIndices]] keeps them explicitly open in the internal VM view without narrowing them
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

  /** Why the bytes could not be walked although Scalus decoded them: a disagreement to be
    * investigated, never a verdict on the script's merits.
    */
  enum Failure:
    case Truncated
    case BadTermTag(tag: Int)
    case BadTypeTag(tag: Int)
    case NoFlatConstant(tag: Int)
    case IllFormedType
    case WordOverflow

    def message: String = this match
      case Truncated         => "flat input ends inside a term"
      case BadTermTag(t)     => s"unknown term tag $t"
      case BadTypeTag(t)     => s"unknown type tag $t"
      case NoFlatConstant(t) => s"constant type tag $t has no flat encoding"
      case IllFormedType     => "ill-formed constant type"
      case WordOverflow      => "natural does not fit 64 bits"

  /** The largest variable index Scalus keeps without narrowing. */
  private val IntIndexLimit = BigInt(Int.MaxValue) + 1
  private val Word64Limit = BigInt(1) << 64
  private val QuantityMin = -(BigInt(1) << 127)
  private val QuantityMax = (BigInt(1) << 127) - 1

  private final class Abort(val failure: Failure) extends ControlThrowable

  /** A constant's type: tags 0 to 4, 8 to 13 are atoms, `Apply` (tag 7) builds the rest. */
  private sealed trait Ty
  private final case class Atom(tag: Int) extends Ty
  private final case class App(function: Ty, argument: Ty) extends Ty

  // The work still to do, innermost first. `depth` is the number of enclosing lambdas and
  // `checked` whether the ledger's scope check descends here.
  private sealed trait Task
  private final case class Term(depth: Long, checked: Boolean) extends Task
  private final case class Fields(depth: Long, count: Count, isConstr: Boolean) extends Task
  private final case class Value(ty: Ty) extends Task
  private final case class Elements(element: Ty) extends Task
  private final class Count(var n: Int)

  /** Walks the flat bytes (the program, without its CBOR wrapper). */
  def scan(flat: Array[Byte]): Either[Failure, FlatScan] =
    try Right(new Walker(flat).run())
    catch case a: Abort => Left(a.failure)

  private final class Walker(bytes: Array[Byte]):
    private var position = 0L // in bits
    private var malformed: Option[ValueFault] = None
    private var typeHeader = 0
    private var constrFields = 0
    private var open: Option[BigInt] = None
    private var variableOrdinal = 0
    private var wideVariables = Set.empty[Int]
    private val tasks = ArrayDeque.empty[Task]

    private def abort(failure: Failure): Nothing = throw Abort(failure)

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
      var acc = BigInt(0)
      var shift = 0
      var more = true
      while more do
        val group = bits(8)
        acc = acc | (BigInt(group & 0x7f) << shift)
        shift += 7
        more = (group & 0x80) != 0
      acc

    private def word64(): BigInt =
      val n = natural()
      if n >= Word64Limit then abort(Failure.WordOverflow) else n

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

    private def compare(a: Array[Byte], b: Array[Byte]): Int =
      val n = math.min(a.length, b.length)
      var i = 0
      while i < n && a(i) == b(i) do i += 1
      if i < n then (a(i) & 0xff) - (b(i) & 0xff) else a.length - b.length

    private def fault(f: ValueFault): Unit = if malformed.isEmpty then malformed = Some(f)

    /** A `value` constant, as `Flat Value` reads it and `buildValueWith` checks it. */
    private def valueConstant(): Unit =
      var previousCurrency: Option[Array[Byte]] = None
      while bit() == 1 do
        val currency = byteString()
        if currency.length > 32 then fault(ValueFault.KeyTooLong)
        previousCurrency.foreach(p =>
          if compare(p, currency) >= 0 then fault(ValueFault.CurrenciesNotAscending)
        )
        previousCurrency = Some(currency)
        var previousToken: Option[Array[Byte]] = None
        var tokens = 0
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
          tokens += 1
        if tokens == 0 then fault(ValueFault.EmptyInnerMap)

    /** The tags of a constant's type, built into a tree from the end (a stack, not recursion). */
    private def constantType(): Ty =
      val tags = Vector.newBuilder[Int]
      while bit() == 1 do tags += bits(4)
      val all = tags.result()
      typeHeader = math.max(typeHeader, all.length)
      val built = ArrayDeque.empty[Ty]
      all.reverseIterator.foreach {
        case 7 =>
          if built.size < 2 then abort(Failure.IllFormedType)
          val function = built.removeLast()
          val argument = built.removeLast()
          built.append(App(function, argument))
        case t if t <= 6 || (t >= 8 && t <= 13) => built.append(Atom(t))
        case t                                  => abort(Failure.BadTypeTag(t))
      }
      if built.size != 1 then abort(Failure.IllFormedType)
      built.head

    private def variable(index: BigInt, depth: Long, checked: Boolean): Unit =
      val scopeViolation = checked && (index <= 0 || index > BigInt(depth))
      if open.isEmpty && scopeViolation then open = Some(index)
      if index >= IntIndexLimit then wideVariables += variableOrdinal
      variableOrdinal += 1

    def run(): FlatScan =
      natural(); natural(); natural() // the program version
      tasks.append(Term(0L, checked = true))
      while tasks.nonEmpty do step(tasks.removeLast())
      FlatScan(malformed, typeHeader, constrFields, open, wideVariables)

    private def step(task: Task): Unit = task match
      case Term(depth, checked) =>
        bits(4) match
          case 0     => variable(word64(), depth, checked)
          case 1 | 5 => tasks.append(Term(depth, checked))
          case 2     => tasks.append(Term(depth + 1, checked))
          case 3 =>
            tasks.append(Term(depth, checked))
            tasks.append(Term(depth, checked))
          case 4 =>
            val ty = constantType()
            tasks.append(Value(ty))
          case 6 => ()
          case 7 => bits(7)
          case 8 =>
            word64() // the constructor tag
            tasks.append(Fields(depth, Count(0), isConstr = true))
          case 9 =>
            tasks.append(Fields(depth, Count(0), isConstr = false))
            tasks.append(Term(depth, checked = false))
          case tag => abort(Failure.BadTermTag(tag))
      case Fields(depth, count, isConstr) =>
        if bit() == 1 then
          count.n += 1
          tasks.append(Fields(depth, count, isConstr))
          tasks.append(Term(depth, checked = false))
        else if isConstr then constrFields = math.max(constrFields, count.n)
      case Elements(element) =>
        if bit() == 1 then
          tasks.append(Elements(element))
          tasks.append(Value(element))
      case Value(ty) =>
        ty match
          case Atom(0)                     => integer()
          case Atom(1) | Atom(2) | Atom(8) => byteString()
          case Atom(3)                     => ()
          case Atom(4)                     => bit()
          case Atom(13)                    => valueConstant()
          case Atom(t @ (9 | 10 | 11))     => abort(Failure.NoFlatConstant(t))
          case App(Atom(5), element)       => tasks.append(Elements(element))
          case App(Atom(12), element)      => tasks.append(Elements(element))
          case App(App(Atom(6), first), second) =>
            tasks.append(Value(second))
            tasks.append(Value(first))
          case _ => abort(Failure.IllFormedType)
