// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import java.nio.charset.StandardCharsets
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Tree, TreeFold, Value}
import xyz.kd5ujc.scarab.codec.Tree.{treeEquals, treeHash, treeString}

/** A transaction metadatum: `Metadatum` of cardano-ledger-core
  * (`libs/cardano-ledger-core/src/Cardano/Ledger/Metadata.hs:60-66`, cardano-ledger f649f975),
  * whose `Map`, `List`, `I`, `B` and `S` are [[Entries]], [[Items]], [[Num]], [[Bin]] and [[Str]].
  * A map is the list of its pairs in the order read, a repeated key kept, as the reference holds
  * it. The transaction keeps its auxiliary data as the bytes it was read from; this is the value a
  * metadatum of them decodes to.
  */
enum Metadatum:
  case Entries(pairs: Vector[(Metadatum, Metadatum)])
  case Items(items: Vector[Metadatum])
  case Num(value: BigInt)
  case Bin(bytes: Bytes)
  case Str(text: String)

  // Structural and stack-safe: a metadatum nests as deep as the transaction is long.
  override def equals(that: Any): Boolean = that match
    case o: Metadatum => this.treeEquals(o)
    case _            => false
  override def hashCode(): Int = this.treeHash
  override def toString: String = this.treeString

object Metadatum:
  given Tree[Metadatum] with
    def children(m: Metadatum): IndexedSeq[Metadatum] = m match
      case Entries(pairs) => TreeFold.flatten(pairs)
      case Items(items)   => items
      case _              => Vector.empty
    def sameLabel(x: Metadatum, y: Metadatum): Boolean = (x, y) match
      case (Entries(_), Entries(_)) => true
      case (Items(_), Items(_))     => true
      case (Num(a), Num(b))         => a == b
      case (Bin(a), Bin(b))         => a == b
      case (Str(a), Str(b))         => a == b
      case _                        => false
    def labelHash(m: Metadatum): Int = m match
      case Entries(_) => 1
      case Items(_)   => 2
      case Num(a)     => 41 + a.hashCode
      case Bin(a)     => 43 + a.hashCode
      case Str(a)     => 47 + a.hashCode
    def name(m: Metadatum): String = m match
      case Entries(_) => "Map"
      case Items(_)   => "List"
      case Num(a)     => s"I($a)"
      case Bin(a)     => s"B(${a.hex.take(16)}...(${a.size} bytes))"
      case Str(a)     => s"S(${a.take(16)})"

  /** The most bytes a byte or text string of a metadatum holds from decoder version 3. */
  val MaxStringBytes: Int = 64

  /** `decodeMetadatum` (`Metadata.hs:153-222`, f649f975) at `version`: an integer within the native
    * range (a bignum is refused, as its tag is), a byte or text string, definite or chunked, of at
    * most [[MaxStringBytes]] from version 3, and a list or map of either length form.
    */
  def decode(n: Node, version: DecoderVersion): Either[LedgerDecodeError, Metadatum] =
    read(n, version.atLeast(3))

  /** [[decode]], the string bound applied when `bounded`: the reader of the metadata of auxiliary
    * data ([[AuxiliaryScripts]]), which knows the decoder version as a number.
    */
  private[ledger] def read(root: Node, bounded: Boolean): Either[LedgerDecodeError, Metadatum] =
    TreeFold.foldE[Node, Metadatum, LedgerDecodeError](root) { node =>
      import TreeFold.Step
      def sized(actual: Int, leaf: => Metadatum) =
        Either.cond(
          !bounded || actual <= MaxStringBytes,
          Step.Leaf(leaf),
          LedgerDecodeError.NumberOutOfRange(
            DecodeField.MetadataByteLength,
            actual,
            0,
            MaxStringBytes
          )
        )
      node.value match
        case Value.UInt(v)       => Right(Step.Leaf(Num(v)))
        case Value.NInt(v)       => Right(Step.Leaf(Num(v)))
        case Value.ByteString(b) => sized(b.size, Bin(b))
        case Value.Text(s)       => sized(s.getBytes(StandardCharsets.UTF_8).length, Str(s))
        case Value.Arr(children) =>
          Right(Step.Branch(children, items => Right(Items(items))))
        case Value.Map(pairs) =>
          Right(
            Step.Branch(TreeFold.flatten(pairs), kvs => Right(Entries(TreeFold.pairs(kvs))))
          )
        case other =>
          Left(
            LedgerDecodeError.Expected(
              DecodeField.MetadataValue,
              LedgerDecodeError.Shape.Metadatum,
              other
            )
          )
    }
