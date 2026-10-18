// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto

import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Encoder, Value}
import org.bouncycastle.crypto.digests.Blake2bDigest

/** A byte-first hash: serialization is an explicit, separate operation. */
trait Hasher:
  def hash(bytes: Bytes): Bytes
  final def encoded[A](value: A, encoder: Encoder[A]): Either[encoder.Error, Bytes] =
    encoder.encode(value).map(hash)

object Blake2b:
  val hash256: Hasher = implementation(256)
  val hash224: Hasher = implementation(224)
  private def implementation(bits: Int): Hasher = new Hasher:
    // A digest is reused per thread (doFinal resets it); creating one costs more than hashing a
    // key hash or a transaction id.
    private val digests = new ThreadLocal[Blake2bDigest]:
      override def initialValue(): Blake2bDigest = new Blake2bDigest(bits)

    def hash(bytes: Bytes): Bytes =
      val digest = digests.get()
      digest.update(bytes.unsafeArray, bytes.unsafeOffset, bytes.size)
      val output = new Array[Byte](bits / 8)
      digest.doFinal(output, 0)
      Bytes.wrap(output)

/** Only structural extraction and transaction-id hashing, never ledger validity. */
object TransactionId:
  enum Error:
    case Syntax(cause: Cbor.Error)
    case ExpectedBodyMap(actual: Value)
    case ExpectedEnvelope(actual: Value)

  def bodyBytes(transaction: Bytes): Either[Error, Bytes] =
    Cbor.decode(transaction).left.map(Error.Syntax.apply).flatMap { node =>
      node.value match
        case Value.Arr(items) if items.size == 4 =>
          items.head.value match
            case Value.Map(_) => Right(items.head.original)
            case actual => Left(Error.ExpectedBodyMap(actual))
        case actual => Left(Error.ExpectedEnvelope(actual))
    }

  def fromBody(body: Bytes): Either[Error, Bytes] =
    Cbor.decode(body).left.map(Error.Syntax.apply).flatMap { node =>
      node.value match
        case Value.Map(_) => Right(Blake2b.hash256.hash(node.original))
        case actual => Left(Error.ExpectedBodyMap(actual))
    }

  def fromEnvelope(transaction: Bytes): Either[Error, Bytes] =
    bodyBytes(transaction).flatMap(fromBody)
