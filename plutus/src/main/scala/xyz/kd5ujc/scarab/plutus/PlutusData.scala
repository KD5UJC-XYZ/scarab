// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import xyz.kd5ujc.scarab.codec.{ByteStringChunks, Bytes, Cbor, Node, Tree, TreeFold, Value}
import xyz.kd5ujc.scarab.codec.Tree.{treeEquals, treeHash, treeString}

/** Plutus `Data`: the one universal value passed to scripts. This is the node's own type; the
  * evaluator interpreters translate it to their VM's representation.
  */
enum PlutusData:
  case Constr(tag: BigInt, fields: Vector[PlutusData])

  /** An association list: keys may repeat and the order is significant. */
  case Map(entries: Vector[(PlutusData, PlutusData)])
  case List(items: Vector[PlutusData])
  case I(value: BigInt)
  case B(value: Bytes)

  // Structural and stack-safe (the generated ones recurse once per level of nesting); the string
  // is truncated on purpose (see `Tree.render`).
  override def equals(that: Any): Boolean = that match
    case o: PlutusData => this.treeEquals(o)
    case _             => false
  override def hashCode(): Int = this.treeHash
  override def toString: String = this.treeString

object PlutusData:
  enum DecodeError:
    case Syntax(cause: Cbor.Error)
    case ConstructorFields(actual: Value)
    case BigNumberBytes(actual: Value)
    case ByteStringChunkLimit
    case ConstructorIndex(actual: Value)
    case ConstructorEnvelope(actual: Value)
    case UnexpectedTag(tag: BigInt)

    /** Tag 2 or 3 behind a head wider than `0xc2` or `0xc3`: cborg's `peekTokenType` reads a big
      * number only from those bytes, and the reference refuses the item as an unrecognised tag
      * (`PlutusCore/Data.hs` `decodeData`, Plutus 507cab8c).
      */
    case BigNumberWideHead(tag: BigInt)
    case UnexpectedItem(actual: Value)

  given Tree[PlutusData] with
    def children(d: PlutusData): IndexedSeq[PlutusData] = d match
      case Constr(_, fs) => fs
      case Map(kvs)      => TreeFold.flatten(kvs)
      case List(xs)      => xs
      case _             => Vector.empty
    def sameLabel(x: PlutusData, y: PlutusData): Boolean = (x, y) match
      case (Constr(a, _), Constr(b, _)) => a == b
      case (Map(_), Map(_))             => true
      case (List(_), List(_))           => true
      case (I(a), I(b))                 => a == b
      case (B(a), B(b))                 => a == b
      case _                            => false
    def labelHash(d: PlutusData): Int = d match
      case Constr(a, _) => a.hashCode
      case Map(_)       => 1
      case List(_)      => 2
      case I(a)         => 41 + a.hashCode
      case B(a)         => a.hashCode
    def name(d: PlutusData): String = d match
      case Constr(a, _) => s"Constr($a)"
      case Map(_)       => "Map"
      case List(_)      => "List"
      case I(a)         => s"I($a)"
      case B(a)         => s"B(${Tree.shown(a)})"

  val unit: PlutusData = Constr(0, Vector.empty)

  def constr(tag: Int, fields: PlutusData*): PlutusData = Constr(tag, fields.toVector)
  def bytes(b: Bytes): PlutusData = B(b)
  def int(i: BigInt): PlutusData = I(i)

  /** Haskell `Maybe`: `Just x = Constr 0 [x]`, `Nothing = Constr 1 []`. */
  def maybe(x: Option[PlutusData]): PlutusData = x.fold(constr(1))(constr(0, _))

  /** Haskell `Bool`: `False = Constr 0 []`, `True = Constr 1 []`. */
  def bool(b: Boolean): PlutusData = constr(if b then 1 else 0)

  /** Decoding limits are generous: datums are user-controlled, but a transaction is at most a few
    * tens of kilobytes. There is no depth limit of its own: a level takes a byte, so the input size
    * and the item count bound it.
    */
  private val limits =
    Cbor.Limits(
      maxInputBytes = 1 << 24,
      maxItems = 1 << 22,
      maxStringBytes = 1 << 24
    )

  def decode(bytes: Bytes): Either[DecodeError, PlutusData] =
    Cbor.decode(bytes, limits).left.map(DecodeError.Syntax.apply).flatMap(fromNode)

  /** The `data` constant of a flat script: the first item of `bytes`. Flat reads it through
    * `FlatViaSerialise` (`Codec/Extras/FlatViaSerialise.hs` 32-39, Plutus 507cab8c), whose
    * `deserialiseOrFail` drops whatever follows the item (`Codec/Serialise.hs` 134-143, serialise
    * 0.2.6.1); a datum or redeemer, which the ledger decodes as a whole, uses [[decode]].
    */
  def decodePrefix(bytes: Bytes): Either[DecodeError, PlutusData] =
    Cbor.decodePrefix(bytes, limits).left.map(DecodeError.Syntax.apply).flatMap(fromNode)

  /** `plutus_data` of the Conway CDDL: constructors in tags 121 to 127, 1280 to 1400 and 102,
    * integers (with tag 2 and 3 big numbers), byte strings, lists and maps. Nesting is unlimited,
    * as in the reference, and walked on the heap. Each original definite byte-string chunk is at
    * most 64 bytes, including big-number payloads; joined indefinite strings can be longer
    * (`PlutusCore/Data.hs:232-265`, Plutus 507cab8c).
    */
  def fromNode(n: Node): Either[DecodeError, PlutusData] =
    TreeFold.foldE[Node, PlutusData, DecodeError](n)(open)

  private type Opened = Either[DecodeError, TreeFold.Step[Node, PlutusData, DecodeError]]

  private def leaf(d: PlutusData): Opened = Right(TreeFold.Step.Leaf(d))

  private def boundedBytes(n: Node)(data: => PlutusData): Opened =
    if ByteStringChunks.within(n, 64) then leaf(data)
    else Left(DecodeError.ByteStringChunkLimit)

  private def constrOf(index: BigInt, fs: Node): Opened = fs.value match
    case Value.Arr(xs) => Right(TreeFold.Step.Branch(xs, ds => Right(Constr(index, ds))))
    case actual        => Left(DecodeError.ConstructorFields(actual))

  private def open(n: Node): Opened = n.value match
    case Value.UInt(v)       => leaf(I(v))
    case Value.NInt(v)       => leaf(I(v))
    case Value.ByteString(b) => boundedBytes(n)(B(b))
    case Value.Arr(xs)       => Right(TreeFold.Step.Branch(xs, ds => Right(List(ds))))
    case Value.Map(kvs) =>
      Right(
        TreeFold.Step.Branch(
          TreeFold.flatten(kvs),
          ds => Right(Map(TreeFold.pairs(ds)))
        )
      )
    case Value.Tag(t, _) if (t == 2 || t == 3) && (n.original(0) & 0xff) != 0xc0 + t.toInt =>
      Left(DecodeError.BigNumberWideHead(t))
    case Value.Tag(t, inner) if t == 2 || t == 3 =>
      inner.value match
        case Value.ByteString(b) =>
          boundedBytes(inner) {
            val magnitude = BigInt(1, b.toArray)
            I(if t == 2 then magnitude else -1 - magnitude)
          }
        case actual => Left(DecodeError.BigNumberBytes(actual))
    case Value.Tag(t, inner) if t >= 121 && t <= 127   => constrOf(t - 121, inner)
    case Value.Tag(t, inner) if t >= 1280 && t <= 1400 => constrOf(t - 1280 + 7, inner)
    case Value.Tag(t, inner) if t == 102 =>
      inner.value match
        case Value.Arr(Vector(ix, fs)) =>
          ix.value match
            case Value.UInt(i) => constrOf(i, fs)
            case actual        => Left(DecodeError.ConstructorIndex(actual))
        case actual => Left(DecodeError.ConstructorEnvelope(actual))
    case Value.Tag(t, _) => Left(DecodeError.UnexpectedTag(t))
    case other           => Left(DecodeError.UnexpectedItem(other))
