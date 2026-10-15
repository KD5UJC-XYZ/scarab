// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Tree, TreeFold, Value}
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
    // levels), so the walk keeps its stack on the heap.
    final class Open(val script: NativeScript, val children: Vector[NativeScript]):
      var next = 0
      var count = 0
    val stack = new java.util.ArrayDeque[Open]
    var cur: NativeScript = this
    var value: Option[Boolean] = None
    while true do
      if value.isEmpty then
        cur match
          case Sig(h)                 => value = Some(signers.contains(h))
          case InvalidBefore(lock)    => value = Some(start.exists(lock <= _))
          case InvalidHereafter(lock) => value = Some(end.exists(_ <= lock))
          case AllOf(ss)              => stack.push(Open(cur, ss))
          case AnyOf(ss)              => stack.push(Open(cur, ss))
          case NOfK(_, ss)            => stack.push(Open(cur, ss))
      // A finished child is folded into its parent; a finished parent is a finished child.
      var settled = false
      while !settled do
        val top = stack.peek()
        if top == null then
          if value.isDefined then return value.get
          settled = true
        else
          value.foreach { v =>
            top.script match
              case AllOf(_) => value = if v then None else Some(false)
              case AnyOf(_) => value = if v then Some(true) else None
              case NOfK(_, _) =>
                if v then top.count += 1
                value = None
              case _ => ()
          }
          val decided = value.isDefined
          if decided then stack.pop()
          else if top.next < top.children.size then
            cur = top.children(top.next)
            top.next += 1
            settled = true
          else
            stack.pop()
            value = Some(top.script match
              case AllOf(_)   => true
              case AnyOf(_)   => false
              case NOfK(n, _) => BigInt(top.count) >= n
              case _          => false)
    false

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
  def decode(n: Node): Either[DecodeError, NativeScript] =
    TreeFold.foldE[Node, NativeScript, DecodeError](n)(open)

  /** Why bytes are not a native script. */
  enum DecodeError:
    /** The shape is wrong: a tag, an arity, a type, or a key hash of the wrong length. */
    case Malformed(reason: String)

    /** `n` of `script_n_of_k` is an `Int` in the reference: 64-bit signed, negative allowed. */
    case RequiredOutOfRange(value: BigInt)

    /** A slot is a `Word64` in the reference. */
    case SlotOutOfRange(what: String, value: BigInt)

    def message: String = this match
      case Malformed(r)            => r
      case RequiredOutOfRange(v)   => s"script n: $v does not fit a signed 64-bit integer"
      case SlotOutOfRange(what, v) => s"$what: $v does not fit an unsigned 64-bit integer"

  private val MinInt64 = -(BigInt(1) << 63)
  private val MaxInt64 = (BigInt(1) << 63) - 1
  private val MaxWord64 = (BigInt(1) << 64) - 1

  private def malformed[A](r: Either[String, A]): Either[DecodeError, A] =
    r.left.map(DecodeError.Malformed(_))

  private def slot(n: Node, what: String): Either[DecodeError, BigInt] =
    malformed(CborRead.uint(n, what)).flatMap(v =>
      Either.cond(v <= MaxWord64, v, DecodeError.SlotOutOfRange(what, v))
    )

  private def required(n: Node): Either[DecodeError, BigInt] =
    malformed(CborRead.int(n, "script n")).flatMap(v =>
      Either.cond(v >= MinInt64 && v <= MaxInt64, v, DecodeError.RequiredOutOfRange(v))
    )

  private def open(n: Node): Either[DecodeError, TreeFold.Step[Node, NativeScript, DecodeError]] =
    import TreeFold.Step
    malformed(CborRead.array(n, "native script")).flatMap { xs =>
      malformed(
        xs.headOption.toRight("native script: empty").flatMap(CborRead.uint(_, "script tag"))
      ).flatMap { tag =>
        def list(i: Int): Either[DecodeError, Vector[Node]] =
          malformed(
            xs.lift(i).toRight("native script: short").flatMap(CborRead.array(_, "scripts"))
          )
        (tag.toInt, xs.size) match
          case (0, 2) =>
            malformed(CborRead.bytesN(xs(1), 28, "script key hash")).map(h => Step.Leaf(Sig(h)))
          case (1, 2) => list(1).map(Step.Branch(_, ss => Right(AllOf(ss))))
          case (2, 2) => list(1).map(Step.Branch(_, ss => Right(AnyOf(ss))))
          case (3, 3) =>
            required(xs(1)).flatMap(k => list(2).map(Step.Branch(_, ss => Right(NOfK(k, ss)))))
          case (4, 2) => slot(xs(1), "invalid before").map(s => Step.Leaf(InvalidBefore(s)))
          case (5, 2) => slot(xs(1), "invalid hereafter").map(s => Step.Leaf(InvalidHereafter(s)))
          case (t, k) => Left(DecodeError.Malformed(s"native script tag $t with $k elements"))
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

  def native(n: Node): Either[String, Script] =
    NativeScript
      .decode(n)
      .left
      .map(_.message)
      .map(s => Script(ScriptKind.Native, hashOf(ScriptKind.Native, n.original), Some(s)))

  def plutus(kind: ScriptKind, bytes: Bytes): Script =
    Script(kind, hashOf(kind, bytes), None, Some(bytes))

  /** `script = [0, native_script] / [1, plutus_v1] / [2, plutus_v2] / [3, plutus_v3]`. */
  def decode(n: Node): Either[String, Script] =
    CborRead.arrayN(n, 2, "script").flatMap { xs =>
      CborRead.uint(xs(0), "script type").flatMap {
        case t if t == 0 => native(xs(1))
        case t if t == 1 => CborRead.bytes(xs(1), "plutus v1").map(plutus(ScriptKind.PlutusV1, _))
        case t if t == 2 => CborRead.bytes(xs(1), "plutus v2").map(plutus(ScriptKind.PlutusV2, _))
        case t if t == 3 => CborRead.bytes(xs(1), "plutus v3").map(plutus(ScriptKind.PlutusV3, _))
        case t           => Left(s"script type $t")
      }
    }

  /** `#6.24(bytes .cbor script)` as stored in an output's reference-script field; the Plutus bytes
    * are dropped so the ledger state keeps one copy (the output's own encoding).
    */
  def decodeWrapped(n: Node): Either[String, Script] =
    decodeWrappedWithBytes(n).map(_.copy(plutus = None))

  /** As [[decodeWrapped]], keeping the Plutus bytes. */
  def decodeWrappedWithBytes(n: Node): Either[String, Script] = n.value match
    case Value.Tag(t, inner) if t == 24 =>
      CborRead.bytes(inner, "reference script").flatMap(Cbor.decode(_)).flatMap(decode)
    case _ => Left("reference script: expected tag 24")
