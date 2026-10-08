// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Encoder, Value}
import org.bouncycastle.crypto.digests.Blake2bDigest

/** A byte-first hash: serialization is an explicit, separate operation. */
trait Hasher:
  def hash(bytes: Bytes): Bytes
  final def encoded[A](value: A, encoder: Encoder[A]): Either[String, Bytes] =
    encoder.encode(value).map(hash)

object Blake2b:
  val hash256: Hasher = implementation(256)
  val hash224: Hasher = implementation(224)
  private def implementation(bits: Int): Hasher = new Hasher:
    def hash(bytes: Bytes): Bytes =
      val digest = new Blake2bDigest(bits)
      val input = bytes.toArray
      digest.update(input, 0, input.length)
      val output = new Array[Byte](bits / 8)
      digest.doFinal(output, 0)
      Bytes(output.toVector)

/** Only structural extraction and transaction-id hashing, never ledger validity. */
object TransactionId:
  def bodyBytes(transaction: Bytes): Either[String, Bytes] =
    Cbor.decode(transaction).flatMap { node =>
      node.value match
        case Value.Arr(items) if items.size == 4 =>
          items.head.value match
            case Value.Map(_) => Right(items.head.original)
            case _            => Left("transaction body must be a CBOR map")
        case _ => Left("expected four-element Conway transaction envelope")
    }

  def fromBody(body: Bytes): Either[String, Bytes] =
    Cbor.decode(body).flatMap { node =>
      node.value match
        case Value.Map(_) => Right(Blake2b.hash256.hash(node.original))
        case _            => Left("transaction body must be a CBOR map")
    }

  def fromEnvelope(transaction: Bytes): Either[String, Bytes] =
    bodyBytes(transaction).flatMap(fromBody)
