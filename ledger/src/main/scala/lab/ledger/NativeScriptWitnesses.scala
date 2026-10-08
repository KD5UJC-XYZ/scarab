// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.witness.{PublicKey32, Signature64, VKeyWitness}

/** Standalone bounded witness diagnostics. Does not bind any script to a spending credential,
  * policy, or required-script set and is deliberately not transaction admission.
  */
object NativeScriptWitnesses:
  val ProfileId = "conway-pv9-native-witness-diagnostic-v1"
  val MaxScripts = 32
  final class Inspection private[NativeScriptWitnesses] (
      val interval: ValidityInterval.Interval,
      val verifiedKeys: NativeScript.VerifiedKeys,
      val scripts: Vector[NativeScript.Script],
      val evaluations: Vector[NativeScript.Evaluation]
  ):
    val profileId = ProfileId
    val credentialBound = false
    val fullLedgerValidated = false

  private def collection(node: Node, limit: Int): Either[String, Vector[Node]] =
    val untagged = node.value match
      case V.Tag(tag, inner) if tag == 258 => inner
      case _                               => node
    untagged.value match
      case V.Arr(xs) if xs.nonEmpty && xs.size <= limit => Right(xs)
      case _ =>
        Left("present witness collection must be nonempty, bounded, and optionally tagged 258")

  private def bytes(node: Node): Either[String, Bytes] = node.value match
    case V.ByteString(b) if (node.original.value.head & 31) != 31 => Right(b)
    case _ => Left("definite witness byte string required")

  private def witness(node: Node): Either[String, VKeyWitness] = node.value match
    case V.Arr(Vector(key, sig)) =>
      for
        kb <- bytes(key)
        sb <- bytes(sig)
        k <- PublicKey32.create(kb).left.map(_.toString)
        s <- Signature64.create(sb).left.map(_.toString)
      yield VKeyWitness(k, s)
    case _ => Left("two-field vkey witness required")

  def inspect(original: Bytes): Either[String, Inspection] =
    for
      interval <- ValidityInterval.decode(original)
      root <- Cbor.decode(original, Cbor.Limits(1048576, 40, 65536, 1048576))
      fields <- root.value match
        case V.Arr(Vector(_, map, _, _)) =>
          map.value match
            case V.Map(xs) =>
              xs.foldLeft[Either[String, Map[BigInt, Node]]](Right(Map.empty)) {
                case (acc, (key, value)) =>
                  acc.flatMap { seen =>
                    key.value match
                      case V.UInt(k) if (k == 0 || k == 1) && !seen.contains(k) =>
                        Right(seen.updated(k, value))
                      case _ => Left("duplicate or unsupported native witness map key")
                  }
              }
            case _ => Left("witness map required")
        case _ => Left("transaction envelope required")
      rawKeys <- fields
        .get(0)
        .fold[Either[String, Vector[Node]]](Right(Vector.empty))(
          collection(_, NativeScript.MaxWitnesses)
        )
      witnesses <- rawKeys.foldLeft[Either[String, Vector[VKeyWitness]]](Right(Vector.empty)) {
        (acc, n) =>
          for previous <- acc; next <- witness(n) yield previous :+ next
      }
      keys <- NativeScript.verifyKeys(interval, witnesses)
      rawScripts <- fields
        .get(1)
        .fold[Either[String, Vector[Node]]](Right(Vector.empty))(collection(_, MaxScripts))
      scripts <- rawScripts
        .foldLeft[Either[String, Vector[NativeScript.Script]]](Right(Vector.empty)) { (acc, node) =>
          for
            previous <- acc
            next <- NativeScript.decode(node.original)
            _ <- Either.cond(
              !previous.exists(s => s.hash == next.hash && s.original != next.original),
              (),
              "script hash collision unsupported"
            )
          yield if previous.exists(_.hash == next.hash) then previous else previous :+ next
        }
      evaluations <- scripts.foldLeft[Either[String, Vector[NativeScript.Evaluation]]](
        Right(Vector.empty)
      ) { (acc, script) =>
        for previous <- acc; evaluated <- NativeScript.evaluate(script, keys, interval)
        yield previous :+ evaluated
      }
    yield new Inspection(interval, keys, scripts, evaluations)
