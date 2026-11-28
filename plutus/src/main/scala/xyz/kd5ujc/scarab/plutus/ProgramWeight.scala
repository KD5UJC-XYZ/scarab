// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{Constant, PlutusData, Program, Term, TermF, Value}

/** A number of bytes of heap, what a [[BoundedCache]] bounds. Sums saturate at [[Weight.unbounded]]
  * and differences at zero.
  */
private[plutus] opaque type Weight = Long

private[plutus] object Weight:
  val zero: Weight = 0L

  /** More than any heap: the weight bound of a cache bounded by entries alone. */
  val unbounded: Weight = Long.MaxValue

  /** `n` bytes, none if `n` is negative. */
  def bytes(n: Long): Weight = math.max(n, 0L)

  def mebibytes(n: Int): Weight = bytes(n.toLong << 20)

  extension (w: Weight)
    def +(other: Weight): Weight = if w > Long.MaxValue - other then Long.MaxValue else w + other
    def -(other: Weight): Weight = math.max(w - other, 0L)
    def *(n: Int): Weight =
      if n <= 0 then 0L else if w > Long.MaxValue / n then Long.MaxValue else w * n
    def exceeds(other: Weight): Boolean = w > other
    def toLong: Long = w

/** The weight of a decoded program: an estimate of the heap its terms and constants hold that is
  * never below it. It counts every term, every constant and every node of a `data` value, each at
  * the most a node of its kind holds with its type and its slot in a vector (a list of `Data` holds
  * the values and one of pairs of `Data` the entries, so their elements are `data` nodes and
  * entries, not constants); every non-empty vector's own objects; every tag of a constant's type,
  * which the decoder builds once and every list and array of the constant shares; the program's
  * version; and every integer, byte string and string at the size of its representation. A node the
  * decoder shares (`()`, a constant of units and pairs repeated in a list) is counted at each place
  * it occurs, so the estimate only errs upwards. The sizes assume what the JVM uses below a 32 GiB
  * heap: 12-byte object headers, 4-byte references and objects aligned to 8 bytes (the default of
  * `ObjectAlignmentInBytes`; a larger alignment makes a term hold more than its weight).
  * `ProgramCacheHeapMain` (module `tools`) measures the heap of the shapes `AmplifyingScripts`
  * (tests) makes and of recorded scripts against their weight (`docs/performance.md` section 59).
  *
  * Terms, constants and `data` nest as deep as their script makes them, so each is walked with its
  * pending nodes on a list on the heap. The walk of the terms is written out, not `Schemes.cata`:
  * counting the terms with the fold took eight to seventeen times as long as this walk takes to
  * weigh the whole program, on the recorded Leios testnet scripts and on chains of `lam` and
  * `constr` terms (`docs/performance.md` section 59), and a program is weighed on every miss of the
  * cache. A walk stops once its weight is beyond `limit`. Every node it visits adds to the weight,
  * so a program too heavy to keep is walked only for about as many nodes as fit in the limit,
  * besides the children of the last vector it reads and the type of the last constant, which the
  * script's bytes bound.
  */
private[plutus] object ProgramWeight:
  /** A term's `Fix` node: its header, its layer and its functor, 24 bytes. A term is that node and
    * its layer, whose size follows its kind: [[Single]], [[Double]], [[Leaf]].
    */
  private val Leaf = Weight.bytes(24)

  /** A term whose layer holds one reference (`lam`, `force`, `delay`, `builtin`, a constant): its
    * node and a 16-byte layer.
    */
  private val Single = Weight.bytes(40)

  /** A term whose layer holds two references or a 64-bit field (an application, a variable,
    * `constr`, `case`): its node and a 24-byte layer.
    */
  private val Double = Weight.bytes(48)

  /** A constant: the object, the type a list, array or pair constant makes for itself, its slot. */
  private val Node = Weight.bytes(64)

  /** A tag of a constant's type: a node of the type the decoder builds. */
  private val Tag = Weight.bytes(16)

  /** A slot of a vector of children. */
  private val Slot = Weight.bytes(8)

  /** A non-empty vector's own objects. */
  private val VectorNode = Weight.bytes(48)

  /** The objects around the bytes of an integer, a byte string or a string. */
  private val Boxed = Weight.bytes(96)

  /** A `value` constant's value, empty or not: the object and its two maps, of the entries and of
    * the inner maps' sizes.
    */
  private val ValueNode = Weight.bytes(128)

  /** An entry of a `value` constant, or a currency's map: its key, its quantity and its tree node.
    */
  private val ValueEntry = Weight.bytes(384)

  /** A BLS12-381 element or pairing result, which no script's constant decodes into. */
  private val Point = Weight.bytes(1024)

  /** A cache entry beside its program: the key with its script's bytes, the map's nodes and the
    * entry.
    */
  private val Entry = Weight.bytes(512)

  /** What an entry of the program cache holds: its script's bytes and the program decoded from
    * them, weighed up to `limit`.
    */
  def entry(script: Bytes, program: Program, limit: Weight): Weight =
    val key = Entry + Weight.bytes(script.size.toLong)
    key + of(program, limit - key)

  def of(program: Program): Weight = of(program, Weight.unbounded)

  /** The weight of `program`, or a weight beyond `limit` if it is beyond it. */
  def of(program: Program, limit: Weight): Weight =
    val version = Node + integer(program.version.major) + integer(program.version.minor) +
      integer(program.version.patch)
    terms(program.term :: Nil, version, limit)

  /** The weight of `pending` and their subterms, added to `total`, until it is beyond `limit`. */
  @tailrec private def terms(pending: List[Term], total: Weight, limit: Weight): Weight =
    if total.exceeds(limit) then total
    else
      pending match
        case Nil => total
        case term :: rest =>
          term.unfix match
            case TermF.Var(_)       => terms(rest, total + Double, limit)
            case TermF.Builtin(_)   => terms(rest, total + Single, limit)
            case TermF.Error        => terms(rest, total + Leaf, limit)
            case TermF.LamAbs(body) => terms(body :: rest, total + Single, limit)
            case TermF.Apply(function, arg) =>
              terms(function :: arg :: rest, total + Double, limit)
            case TermF.Force(body) => terms(body :: rest, total + Single, limit)
            case TermF.Delay(body) => terms(body :: rest, total + Single, limit)
            case TermF.Constant(value) =>
              terms(rest, total + Single + constant(value, limit - total), limit)
            case TermF.Constr(_, fields) =>
              terms(pushed(fields, rest), total + Double + slots(fields.size), limit)
            case TermF.Case(scrutinee, cases) =>
              terms(scrutinee :: pushed(cases, rest), total + Double + slots(cases.size), limit)

  /** `items` in front of `rest`, in some order. */
  private def pushed[A](items: Vector[A], rest: List[A]): List[A] =
    items.foldLeft(rest)((more, item) => item :: more)

  /** A vector of `n` children, without the children. */
  private def slots(n: Int): Weight = if n == 0 then Weight.zero else VectorNode + Slot * n

  private def integer(n: BigInt): Weight = Boxed + Weight.bytes(n.bitLength.toLong / 8 + 1)

  private def bytes(b: Bytes): Weight = Boxed + Weight.bytes(b.size.toLong)

  /** The weight of a constant, its type, elements and components included. */
  def constant(value: Constant): Weight = constant(value, Weight.unbounded)

  /** The weight of a constant, or a weight beyond `limit` if it is beyond it. */
  private def constant(value: Constant, limit: Weight): Weight =
    @tailrec def go(pending: List[Constant], total: Weight): Weight =
      if total.exceeds(limit) then total
      else
        pending match
          case Nil => total
          case c :: rest =>
            c match
              case list: Constant.List.OfConstants =>
                go(pushed(list.constants, rest), total + Node + slots(list.constants.size))
              // a list of `Data` holds the values themselves, and one of pairs of `Data` the
              // entries: no constant is made for an element
              case list: Constant.List.OfData =>
                go(rest, data(pushed(list.data, Nil), total + Node + slots(list.data.size), limit))
              case list: Constant.List.OfDataPairs =>
                val entries = list.entries.size
                go(
                  rest,
                  data(pairs(list.entries), total + Node + slots(entries) + Node * entries, limit)
                )
              case array: Constant.Array =>
                go(pushed(array.items, rest), total + Node + slots(array.items.size))
              case Constant.Pair(first, second) => go(first :: second :: rest, total + Node)
              case Constant.Integer(n)          => go(rest, total + Node + integer(n))
              case Constant.ByteString(b)       => go(rest, total + Node + bytes(b))
              case Constant.String(text) =>
                go(rest, total + Node + Boxed + Weight.bytes(2L * text.string.length))
              case _: Constant.Unit.type            => go(rest, total + Node)
              case Constant.Bool(_)                 => go(rest, total + Node)
              case Constant.Data(d)                 => go(rest, data(d :: Nil, total + Node, limit))
              case Constant.Value(v)                => go(rest, total + Node + tokens(v))
              case Constant.Bls12_381_G1_Element(_) => go(rest, total + Point)
              case Constant.Bls12_381_G2_Element(_) => go(rest, total + Point)
              case Constant.Bls12_381_MlResult(_)   => go(rest, total + Point)
    go(value :: Nil, Tag * value.typeOf.size)

  private def tokens(v: Value): Weight =
    ValueNode + ValueEntry * v.totalSize + ValueEntry * v.entries.size

  /** The keys and values of `entries`, in some order. */
  private def pairs(entries: Vector[(PlutusData, PlutusData)]): List[PlutusData] =
    entries.foldLeft(List.empty[PlutusData]) { case (more, (k, v)) => k :: v :: more }

  /** The weight of `pending` and their nodes added to `total`, or a weight beyond `limit` if it is
    * beyond it.
    */
  @tailrec private def data(pending: List[PlutusData], total: Weight, limit: Weight): Weight =
    if total.exceeds(limit) then total
    else
      pending match
        case Nil => total
        case node :: rest =>
          node match
            case PlutusData.Constr(tag, fields) =>
              data(pushed(fields, rest), total + Node + integer(tag) + slots(fields.size), limit)
            case PlutusData.Map(entries) =>
              data(
                entries.foldLeft(rest) { case (more, (k, v)) => k :: v :: more },
                total + Node + slots(entries.size) + Node * entries.size,
                limit
              )
            case PlutusData.List(items) =>
              data(pushed(items, rest), total + Node + slots(items.size), limit)
            case PlutusData.I(n) => data(rest, total + Node + integer(n), limit)
            case PlutusData.B(b) => data(rest, total + Node + bytes(b), limit)
