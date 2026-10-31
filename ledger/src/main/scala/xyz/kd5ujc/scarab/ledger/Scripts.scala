// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import scala.annotation.tailrec
import cats.syntax.show.*
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Tree, TreeFold, Value}
import xyz.kd5ujc.scarab.codec.Tree.{treeEquals, treeHash, treeString}

/** Allegra timelock script; Shelley multi-signature scripts are the subset without the two time
  * constructors.
  */
enum NativeScript:
  case Sig(keyHash: Bytes)
  case AllOf(scripts: Vector[NativeScript])
  case AnyOf(scripts: Vector[NativeScript])
  case NOfK(required: BigInt, scripts: Vector[NativeScript])
  case InvalidBefore(slot: BigInt)
  case InvalidHereafter(slot: BigInt)

  // Structural and stack-safe (the generated ones recurse once per level of nesting); the string
  // is truncated on purpose (see `Tree.render`).
  override def equals(that: Any): Boolean = that match
    case o: NativeScript => this.treeEquals(o)
    case _               => false
  override def hashCode(): Int = this.treeHash
  override def toString: String = this.treeString

  /** `evalTimelock`: `signers` are the key hashes of the verified vkey witnesses and `[start, end)`
    * the transaction's validity interval (`invalid_before`, `invalid_hereafter`).
    */
  def eval(signers: Set[Bytes], start: Option[BigInt], end: Option[BigInt]): Boolean =
    // The scripts nest as deep as the transaction is long (a preprod transaction has 5,388
    // levels), so the walk keeps its stack on the heap: the open combinators, innermost first.
    enum Kind:
      case All
      case Any
      case AtLeast(required: BigInt)
    // an open combinator: the children still to evaluate and how many of the evaluated were true
    final case class Open(kind: Kind, rest: List[NativeScript], count: Int)
    enum State:
      case Visit(script: NativeScript, stack: List[Open])
      // a finished script's value, for the combinator that opened it
      case Deliver(value: Boolean, stack: List[Open])
      case Advance(top: Open, below: List[Open])
    @tailrec def go(state: State): Boolean = state match
      case State.Visit(script, stack) =>
        script match
          case Sig(h)                 => go(State.Deliver(signers.contains(h), stack))
          case InvalidBefore(lock)    => go(State.Deliver(start.exists(lock <= _), stack))
          case InvalidHereafter(lock) => go(State.Deliver(end.exists(_ <= lock), stack))
          case AllOf(ss)              => go(State.Advance(Open(Kind.All, ss.toList, 0), stack))
          case AnyOf(ss)              => go(State.Advance(Open(Kind.Any, ss.toList, 0), stack))
          case NOfK(n, ss) => go(State.Advance(Open(Kind.AtLeast(n), ss.toList, 0), stack))
      case State.Deliver(value, Nil)          => value
      case State.Deliver(value, top :: below) =>
        // a false child settles `AllOf` and a true one `AnyOf`; `NOfK` counts every child
        top.kind match
          case Kind.All if !value => go(State.Deliver(false, below))
          case Kind.Any if value  => go(State.Deliver(true, below))
          case Kind.AtLeast(_) =>
            go(State.Advance(top.copy(count = top.count + (if value then 1 else 0)), below))
          case Kind.All | Kind.Any => go(State.Advance(top, below))
      case State.Advance(top, below) =>
        top.rest match
          case child :: rest => go(State.Visit(child, top.copy(rest = rest) :: below))
          case Nil =>
            val value = top.kind match
              case Kind.All        => true
              case Kind.Any        => false
              case Kind.AtLeast(n) => BigInt(top.count) >= n
            go(State.Deliver(value, below))
    go(State.Visit(this, Nil))

object NativeScript:
  given Tree[NativeScript] with
    def children(s: NativeScript): IndexedSeq[NativeScript] = s match
      case AllOf(ss)   => ss
      case AnyOf(ss)   => ss
      case NOfK(_, ss) => ss
      case _           => Vector.empty
    def sameLabel(x: NativeScript, y: NativeScript): Boolean = (x, y) match
      case (Sig(a), Sig(b))                           => a == b
      case (AllOf(_), AllOf(_))                       => true
      case (AnyOf(_), AnyOf(_))                       => true
      case (NOfK(a, _), NOfK(b, _))                   => a == b
      case (InvalidBefore(a), InvalidBefore(b))       => a == b
      case (InvalidHereafter(a), InvalidHereafter(b)) => a == b
      case _                                          => false
    def labelHash(s: NativeScript): Int = s match
      case Sig(a)              => a.hashCode
      case AllOf(_)            => 1
      case AnyOf(_)            => 2
      case NOfK(a, _)          => 43 + a.hashCode
      case InvalidBefore(a)    => 47 + a.hashCode
      case InvalidHereafter(a) => 53 + a.hashCode
    def name(s: NativeScript): String = s match
      case Sig(a)              => s"Sig(${Tree.shown(a)})"
      case AllOf(_)            => "AllOf"
      case AnyOf(_)            => "AnyOf"
      case NOfK(a, _)          => s"NOfK($a)"
      case InvalidBefore(a)    => s"InvalidBefore($a)"
      case InvalidHereafter(a) => s"InvalidHereafter($a)"

  /** Decode with the nesting on the heap, so a script as deep as a block can hold decodes (see
    * [[NativeScript.eval]]); the reference has no limit on the depth of a native script.
    */
  def decode(n: Node)(using ByteStrings): Either[DecodeError, NativeScript] =
    TreeFold.foldE[Node, NativeScript, DecodeError](n)(open)

  /** Why bytes are not a native script. */
  enum DecodeError:
    /** The shape is wrong: a tag, an arity, a type, or a key hash of the wrong length. */
    case Malformed(cause: LedgerDecodeError)

    /** `n` of `script_n_of_k` is an `Int` in the reference: 64-bit signed, negative allowed. */
    case RequiredOutOfRange(value: BigInt)

    /** A slot is a `Word64` in the reference. */
    case SlotOutOfRange(what: DecodeSite, value: BigInt)

    def message: String = this match
      case Malformed(r)            => r.message
      case RequiredOutOfRange(v)   => s"script n: $v does not fit a signed 64-bit integer"
      case SlotOutOfRange(what, v) => s"${what.show}: $v does not fit an unsigned 64-bit integer"

  private val MinInt64 = -(BigInt(1) << 63)
  private val MaxInt64 = (BigInt(1) << 63) - 1
  private val MaxWord64 = (BigInt(1) << 64) - 1

  private def malformed[A](r: Either[LedgerDecodeError, A]): Either[DecodeError, A] =
    r.left.map(DecodeError.Malformed(_))

  private def slot(n: Node, what: DecodeSite): Either[DecodeError, BigInt] =
    malformed(CborRead.uint(n, what)).flatMap(v =>
      Either.cond(v <= MaxWord64, v, DecodeError.SlotOutOfRange(what, v))
    )

  private def required(n: Node): Either[DecodeError, BigInt] =
    malformed(CborRead.int(n, DecodeField.ScriptN)).flatMap(v =>
      Either.cond(v >= MinInt64 && v <= MaxInt64, v, DecodeError.RequiredOutOfRange(v))
    )

  private def open(n: Node)(using
      ByteStrings
  ): Either[DecodeError, TreeFold.Step[Node, NativeScript, DecodeError]] =
    import TreeFold.Step
    malformed(CborRead.array(n, DecodeField.NativeScript)).flatMap { xs =>
      malformed(
        xs.headOption
          .toRight(LedgerDecodeError.Empty(DecodeField.NativeScript))
          .flatMap(CborRead.uint(_, DecodeField.ScriptTag))
      ).flatMap { tag =>
        def list(i: Int): Either[DecodeError, Vector[Node]] =
          malformed(
            xs.lift(i)
              .toRight(
                LedgerDecodeError.MissingField(DecodeField.NativeScript, DecodeSite.Index(i))
              )
              .flatMap(CborRead.array(_, DecodeField.Scripts))
          )
        val boundedTag = if tag <= Int.MaxValue then tag.toInt else -1
        (boundedTag, xs.size) match
          case (0, 2) =>
            malformed(CborRead.bytesN(xs(1), 28, DecodeField.ScriptKeyHash)).map(h =>
              Step.Leaf(Sig(h))
            )
          case (1, 2) => list(1).map(Step.Branch(_, ss => Right(AllOf(ss))))
          case (2, 2) => list(1).map(Step.Branch(_, ss => Right(AnyOf(ss))))
          case (3, 3) =>
            required(xs(1)).flatMap(k => list(2).map(Step.Branch(_, ss => Right(NOfK(k, ss)))))
          case (4, 2) =>
            slot(xs(1), DecodeField.InvalidBefore).map(s => Step.Leaf(InvalidBefore(s)))
          case (5, 2) =>
            slot(xs(1), DecodeField.InvalidHereafter).map(s => Step.Leaf(InvalidHereafter(s)))
          case (_, k) =>
            Left(
              DecodeError.Malformed(LedgerDecodeError.TaggedArity(DecodeField.NativeScript, tag, k))
            )
      }
    }

enum ScriptKind(val tag: Int):
  case Native extends ScriptKind(0)
  case PlutusV1 extends ScriptKind(1)
  case PlutusV2 extends ScriptKind(2)
  case PlutusV3 extends ScriptKind(3)

  def isPlutus: Boolean = this != Native

/** A script with its hash. The hash is `blake2b-224(tag ‖ payload)`, where the payload is the
  * original encoding of a native script or the bytes of a Plutus script. `plutus` holds the bytes
  * of a Plutus script when they were kept (witness scripts; outputs keep only the hash of their
  * reference script and re-read the bytes from their encoding on demand).
  */
final case class Script(
    kind: ScriptKind,
    hash: Bytes,
    native: Option[NativeScript],
    plutus: Option[Bytes] = None
)

object Script:
  private def hashOf(kind: ScriptKind, payload: Bytes): Bytes =
    Blake2b.hash224.hash(Bytes(kind.tag.toByte +: payload.value))

  def native(n: Node)(using ByteStrings): Either[LedgerDecodeError, Script] =
    NativeScript
      .decode(n)
      .left
      .map(LedgerDecodeError.NativeScript(_))
      .map(s => Script(ScriptKind.Native, hashOf(ScriptKind.Native, n.original), Some(s)))

  def plutus(kind: ScriptKind, bytes: Bytes): Script =
    Script(kind, hashOf(kind, bytes), None, Some(bytes))

  /** `script = [0, native_script] / [1, plutus_v1] / [2, plutus_v2] / [3, plutus_v3]`. */
  def decode(n: Node)(using ByteStrings): Either[LedgerDecodeError, Script] =
    CborRead.arrayN(n, 2, DecodeField.Script).flatMap { xs =>
      CborRead.uint(xs(0), DecodeField.ScriptType).flatMap {
        case t if t == 0 => native(xs(1))
        case t if t == 1 =>
          CborRead.bytes(xs(1), DecodeField.PlutusV1).map(plutus(ScriptKind.PlutusV1, _))
        case t if t == 2 =>
          CborRead.bytes(xs(1), DecodeField.PlutusV2).map(plutus(ScriptKind.PlutusV2, _))
        case t if t == 3 =>
          CborRead.bytes(xs(1), DecodeField.PlutusV3).map(plutus(ScriptKind.PlutusV3, _))
        case t => Left(LedgerDecodeError.UnknownTag(DecodeField.ScriptType, t))
      }
    }

  /** `#6.24(bytes .cbor script)` as stored in an output's reference-script field; the Plutus bytes
    * are dropped so the ledger state keeps one copy (the output's own encoding).
    */
  def decodeWrapped(n: Node)(using ByteStrings): Either[LedgerDecodeError, Script] =
    decodeWrappedWithBytes(n).map(_.copy(plutus = None))

  /** As [[decodeWrapped]], keeping the Plutus bytes. */
  def decodeWrappedWithBytes(n: Node)(using ByteStrings): Either[LedgerDecodeError, Script] =
    n.value match
      case Value.Tag(t, inner) if t == 24 =>
        CborRead
          .bytes(inner, DecodeField.ReferenceScript)
          .flatMap(CborRead.decode(_))
          .flatMap(decode)
      case _ => Left(LedgerDecodeError.ExpectedTag(DecodeField.ReferenceScript, 24, n.value))
