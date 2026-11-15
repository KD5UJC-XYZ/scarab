// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.conformance

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.{
  ChainError,
  LedgerDecodeError,
  NativeScript,
  PoolRelayError,
  VotingProcedures
}
import xyz.kd5ujc.scarab.models.runtime.ConstraintViolation
import xyz.kd5ujc.scarab.plutus.PlutusData

/** Why Scarab refuses a sample of a rule: the typed cause of the production decoder, or the form
  * the rule's type takes when Scarab's decoder reads more forms than the rule's type does.
  */
enum Refusal:
  case Ledger(cause: LedgerDecodeError)
  case Chain(cause: ChainError)
  case Native(cause: NativeScript.DecodeError)
  case Voting(cause: VotingProcedures.DecodeError)
  case Data(cause: PlutusData.DecodeError)
  case Relay(cause: PoolRelayError)
  case Form(expected: Shape)
  case Defect(cause: Throwable)

  def message: String = this match
    case Ledger(cause)   => cause.message
    case Chain(cause)    => cause.message
    case Native(cause)   => cause.message
    case Voting(cause)   => cause.message
    case Data(cause)     => cause.toString
    case Relay(cause)    => cause.toString
    case Form(expected)  => s"the rule's type is ${expected.render} only"
    case Defect(failure) => s"the decoder raised ${failure.getClass.getName}: ${failure.getMessage}"

/** A form that a rule's type takes, where Scarab reads the rule with a decoder of a wider type. */
enum Shape:
  case Map, Array, Tag259, Integer, ByteString, Present

  def render: String = this match
    case Map        => "a map"
    case Array      => "an array"
    case Tag259     => "a map under tag 259"
    case Integer    => "a Plutus integer"
    case ByteString => "Plutus bytes"
    case Present    => "the decoded part"

/** Why Scarab does not give a re-encoding of a sample it decoded. */
enum Unwritten:
  /** Scarab has no production encoder of the decoded value. */
  case NoEncoder

  /** The decoded value does not hold the bytes it keeps elsewhere. */
  case NotKept

  /** The item is not where a parent's production encoding puts it. */
  case NotFound(path: List[Step])

  /** The bytes a native script is hashed over are not its encoding. */
  case HashMismatch

  /** The production encoder refuses the decoded value (`Codec.check`): it cannot write it as the
    * reference would.
    */
  case Refused(cause: ConstraintViolation)

  case Syntax(cause: Cbor.Error)
  case Defect(cause: Throwable)

  def message: String = this match
    case NoEncoder      => "decoded; Scarab has no production encoder of this value"
    case NotKept        => "decoded; the value does not keep its original bytes"
    case NotFound(path) => s"the parent's production encoding has no item at ${path.mkString("/")}"
    case HashMismatch   => "the script hash is not over the bytes kept"
    case Refused(cause) => s"the production encoder refuses the decoded value: ${cause.render}"
    case Syntax(cause)  => s"the production encoding does not read as CBOR: $cause"
    case Defect(failure) =>
      s"the encoder raised ${failure.getClass.getName}: ${failure.getMessage}"

/** A step into an item: an array index, a map key, or the first key or value of a map. */
enum Step:
  case Index(i: Int)
  case Key(k: Int)
  case FirstKey
  case FirstValue

  override def toString: String = this match
    case Index(i)   => i.toString
    case Key(k)     => s"{$k}"
    case FirstKey   => "{key}"
    case FirstValue => "{value}"

object Step:
  private val limits = Cbor.Limits.Unbounded

  /** The original bytes of the item at `path` in the one item `bytes`. */
  def at(bytes: Bytes, path: List[Step]): Either[Unwritten, Bytes] =
    Cbor.decode(bytes, limits).leftMap(Unwritten.Syntax(_)).flatMap { root =>
      path
        .foldLeftM(root)((n, step) => into(n, step).toRight(Unwritten.NotFound(path)))
        .map(_.original)
    }

  private def into(n: Node, step: Step): Option[Node] = (n.value, step) match
    case (CborValue.Arr(xs), Index(i)) => xs.lift(i)
    case (CborValue.Map(kvs), Key(k)) =>
      kvs.collectFirst { case (key, v) if key.value == CborValue.UInt(BigInt(k)) => v }
    case (CborValue.Map(kvs), FirstKey)   => kvs.headOption.map(_._1)
    case (CborValue.Map(kvs), FirstValue) => kvs.headOption.map(_._2)
    case (CborValue.Tag(_, inner), _)     => into(inner, step)
    case _                                => None

/** How a rule reaches Scarab's decoder: the decoder takes the sample itself, or a minimal parent
  * that holds it (as the dataset reads `%multiasset<positive_coin>` inside `[0, sample]`).
  */
enum Reach:
  case Direct
  case Embedded(parent: String)

  def render: String = this match
    case Direct           => "direct"
    case Embedded(parent) => s"embedded in $parent"

/** Where the re-encoding of a decoded sample comes from. */
enum Writing:
  /** A production encoder, from the decoded fields. */
  case Encoder(name: String)

  /** The original bytes that the decoded value, or the production parent of it, keeps. */
  case Kept(name: String)

  /** A scalar or byte string written by a production codec. */
  case Leaf(name: String)

  /** No production encoder; a decoded valid sample is reported as an encode failure. */
  case Absent

  def render: String = this match
    case Encoder(name) => s"encoder: $name"
    case Kept(name)    => s"kept bytes: $name"
    case Leaf(name)    => s"scalar codec: $name"
    case Absent        => "none"

/** Why a rule of the dataset is not run through a decoder of Scarab. */
enum Unmapped:
  /** Scarab decodes no value of the rule's type. */
  case NoDecoder

  /** Scarab reads the rule only inside a parent that this harness does not build around a sample.
    */
  case NotEmbedded(parent: String)

  def message: String = this match
    case NoDecoder => "Scarab has no production decoder of this rule"
    case NotEmbedded(parent) =>
      s"Scarab reads this rule only inside $parent, which this harness does not build around a sample"

/** One rule of the dataset, as Scarab decodes it and writes it again. */
enum DatasetRule:
  /** @param decoder
    *   the production entry point, for the report's table
    * @param run
    *   the sample's refusal, or the re-encoding of the decoded value
    */
  case Mapped(
      decoder: String,
      reach: Reach,
      writing: Writing,
      run: Bytes => Either[Refusal, Either[Unwritten, Bytes]]
  )
  case Unmapped(why: xyz.kd5ujc.scarab.ledger.conformance.Unmapped)

object DatasetRule:
  /** A rule read by `decode` and written again by `write`. */
  def apply[A](decoder: String, reach: Reach, writing: Writing)(
      decode: Bytes => Either[Refusal, A]
  )(write: A => Either[Unwritten, Bytes]): DatasetRule =
    Mapped(decoder, reach, writing, bytes => decode(bytes).map(write))

  /** A rule that Scarab decodes and has no encoder of. */
  def unwritten[A](decoder: String, reach: Reach)(
      decode: Bytes => Either[Refusal, A]
  ): DatasetRule =
    apply(decoder, reach, Writing.Absent)(decode)(_ => Left(Unwritten.NoEncoder))
