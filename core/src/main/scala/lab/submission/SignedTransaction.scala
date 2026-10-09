// SPDX-License-Identifier: Apache-2.0
package lab.submission

import java.security.MessageDigest
import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value}

/** Structural identity only, not signature verification or transaction admission. Exact witness and
  * auxiliary spans plus the validity flag are retained for later profile gates and inclusion
  * comparisons. Future body/witness keys are allowed; the bounded core CBOR subset still applies.
  */
final class SignedTransaction private (
    val original: Bytes,
    val originalBody: Bytes,
    val originalWitnesses: Bytes,
    val isValid: Boolean,
    val originalAuxiliary: Bytes
):
  val transactionId: Bytes = Blake2b.hash256.hash(originalBody)
  val envelopeSHA256: Bytes = Bytes.fromArray(
    MessageDigest.getInstance("SHA-256").digest(original.toArray)
  )
  val byteSize: Int = original.size

object SignedTransaction:
  val MaxBytes = 65536
  enum Error:
    case InputLimit
    case DecodeRejected(detail: String)
    case MalformedShape(detail: String)

  private def uniqueMap(node: Node, label: String): Either[Error, Unit] = node.value match
    case Value.Map(fields) =>
      fields
        .foldLeft[Either[Error, Set[BigInt]]](Right(Set.empty)) { case (acc, (key, _)) =>
          acc.flatMap { seen =>
            key.value match
              case Value.UInt(n) if !seen.contains(n) => Right(seen + n)
              case _ => Left(Error.MalformedShape(s"$label requires unique unsigned keys"))
          }
        }
        .map(_ => ())
    case _ => Left(Error.MalformedShape(s"$label map required"))

  def checked(original: Bytes): Either[Error, SignedTransaction] =
    if original == null || original.value == null then
      Left(Error.MalformedShape("original transaction required"))
    else if original.size > MaxBytes then Left(Error.InputLimit)
    else
      Cbor
        .decode(original, Cbor.Limits(MaxBytes, 32, 16384, MaxBytes))
        .left
        .map(Error.DecodeRejected.apply)
        .flatMap { root =>
          root.value match
            case Value.Arr(Vector(body, witnesses, validity, auxiliary)) =>
              for
                _ <- uniqueMap(body, "body")
                _ <- uniqueMap(witnesses, "witness")
                valid <- validity.value match
                  case Value.Bool(value) => Right(value)
                  case _ => Left(Error.MalformedShape("Boolean validity flag required"))
              yield new SignedTransaction(
                root.original,
                body.original,
                witnesses.original,
                valid,
                auxiliary.original
              )
            case _ => Left(Error.MalformedShape("four-element transaction array required"))
        }
