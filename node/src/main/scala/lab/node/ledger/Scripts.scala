// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

import cats.syntax.all.*
import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value}

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

  /** `evalTimelock`: `signers` are the key hashes of the verified vkey witnesses and `[start, end)`
    * the transaction's validity interval (`invalid_before`, `invalid_hereafter`).
    */
  def eval(signers: Set[Bytes], start: Option[BigInt], end: Option[BigInt]): Boolean = this match
    case Sig(h)                 => signers.contains(h)
    case AllOf(ss)              => ss.forall(_.eval(signers, start, end))
    case AnyOf(ss)              => ss.exists(_.eval(signers, start, end))
    case NOfK(n, ss)            => BigInt(ss.count(_.eval(signers, start, end))) >= n
    case InvalidBefore(lock)    => start.exists(lock <= _)
    case InvalidHereafter(lock) => end.exists(_ <= lock)

object NativeScript:
  def decode(n: Node): Either[String, NativeScript] =
    CborRead.array(n, "native script").flatMap { xs =>
      xs.headOption
        .toRight("native script: empty")
        .flatMap(CborRead.uint(_, "script tag"))
        .flatMap { tag =>
          def list(i: Int) =
            xs.lift(i)
              .toRight("native script: short")
              .flatMap(CborRead.array(_, "scripts"))
              .flatMap(_.traverse(decode))
          (tag.toInt, xs.size) match
            case (0, 2) => CborRead.bytesN(xs(1), 28, "script key hash").map(Sig(_))
            case (1, 2) => list(1).map(AllOf(_))
            case (2, 2) => list(1).map(AnyOf(_))
            case (3, 3) => (CborRead.uint(xs(1), "script n"), list(2)).mapN(NOfK(_, _))
            case (4, 2) => CborRead.uint(xs(1), "invalid before").map(InvalidBefore(_))
            case (5, 2) => CborRead.uint(xs(1), "invalid hereafter").map(InvalidHereafter(_))
            case (t, k) => Left(s"native script tag $t with $k elements")
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
