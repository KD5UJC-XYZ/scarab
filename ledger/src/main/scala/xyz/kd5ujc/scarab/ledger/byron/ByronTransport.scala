// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.byron

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Value}
import xyz.kd5ujc.scarab.ledger.{CborRead, LedgerDecodeError}

/** Deprecated Byron components still have a wire grammar. Mirror cardano-ledger f649f975
  * Block/Boundary.hs:73–83, Block/Block.hs:327–334,446–451 and Ssc.hs:76–95,193–253;
  * Binary/Decoding/Drop.hs distinguishes indefinite lists, definite maps and tagged sets.
  * Transaction, delegation and update contents are decoded by ByronBody; this does not add an SSC
  * proof check (Block/Validation.hs:307–328 deliberately checks the other three proofs only).
  */
object ByronTransport:
  private type Result[A] = Either[LedgerDecodeError, A]

  def check(body: Bytes, extra: Bytes, boundary: Boolean): Either[LedgerDecodeError, Unit] =
    for
      decoded <- CborRead.decode(body, ByronCbor.BodyLimits)
      _ <-
        if boundary then list(decoded, "boundary body")(bytes)
        else main(decoded)
      trailing <- CborRead.decode(extra, ByronCbor.BodyLimits)
      fields <- tuple(trailing, 1, "extra body data")
      attrs <- map(fields(0), "extra attributes")
      _ <-
        if boundary then
          attrs.traverse_ { (key, value) =>
            word(key, 8, "extra attribute key") *> bytes(value)
          }
        else
          Either.cond(
            attrs.isEmpty,
            (),
            LedgerDecodeError.NumberOutOfRange(
              "main extra attribute count",
              BigInt(attrs.size),
              0,
              0
            )
          )
    yield ()

  private def main(node: Node): Result[Unit] =
    for
      fields <- tuple(node, 4, "body")
      _ <- list(fields(0), "transaction payload")(_ => Right(()))
      _ <- ssc(fields(1))
      _ <- list(fields(2), "delegation payload")(_ => Right(()))
    yield ()

  private def ssc(node: Node): Result[Unit] =
    for
      fields <- definite(node, "SSC payload") *> CborRead.array(node, "SSC payload")
      tag <- fields.headOption
        .toRight(LedgerDecodeError.Empty("SSC payload"))
        .flatMap(word(_, 8, "SSC tag"))
      size <- tag match
        case t if t >= 0 && t <= 2 => Right(3)
        case t if t == 3           => Right(2)
        case t                     => Left(LedgerDecodeError.UnknownTag("SSC payload", t))
      _ <- Either.cond(
        fields.size == size,
        (),
        LedgerDecodeError.ArrayLength("SSC payload", size, fields.size)
      )
      _ <- tag.toInt match
        case 0 => set(fields(1), "commitments")(signedCommitment) *> certificates(fields(2))
        case 1 => byteMap(fields(1), "openings")(bytes) *> certificates(fields(2))
        case 2 =>
          byteMap(fields(1), "shares")(n =>
            byteMap(n, "inner shares")(v => list(v, "shares")(bytes))
          ) *> certificates(fields(2))
        case _ => certificates(fields(1))
    yield ()

  private def signedCommitment(node: Node): Result[Unit] =
    for
      fields <- tuple(node, 3, "signed commitment")
      _ <- bytes(fields(0))
      commitment <- tuple(fields(1), 2, "commitment")
      _ <- byteMap(commitment(0), "commitment shares")(list(_, "encrypted shares")(bytes))
      proof <- tuple(commitment(1), 4, "secret proof")
      _ <- proof.take(3).traverse_(bytes)
      _ <- list(proof(3), "secret commitments")(bytes)
      _ <- bytes(fields(2))
    yield ()

  private def certificates(node: Node): Result[Unit] =
    set(node, "VSS certificates") { certificate =>
      for
        fields <- tuple(certificate, 4, "VSS certificate")
        _ <- bytes(fields(0))
        _ <- word(fields(1), 64, "VSS epoch")
        _ <- bytes(fields(2)) *> bytes(fields(3))
      yield ()
    }

  private def byteMap(node: Node, context: String)(value: Node => Result[Unit]): Result[Unit] =
    map(node, context).flatMap(_.traverse_ { (key, item) => bytes(key) *> value(item) })

  private def set(node: Node, context: String)(element: Node => Result[Unit]): Result[Unit] =
    node.value match
      // The native dropSet discards decodeTag, without requiring tag 258.
      case Value.Tag(_, inner) =>
        (definite(inner, context) *> CborRead.array(inner, context)).flatMap(_.traverse_(element))
      case _ =>
        Left(
          LedgerDecodeError.Expected(
            s"$context (tagged array)",
            LedgerDecodeError.Shape.Array,
            node.value
          )
        )

  private def list(node: Node, context: String)(element: Node => Result[Unit]): Result[Unit] =
    CborRead.array(node, context).flatMap { values =>
      Either.cond(
        node.original.size > 0 && node.original(0) == 0x9f.toByte,
        (),
        LedgerDecodeError
          .Expected(s"$context (indefinite)", LedgerDecodeError.Shape.Array, node.value)
      ) *>
        values.traverse_(element)
    }

  private def tuple(node: Node, size: Int, context: String): Result[Vector[Node]] =
    definite(node, context) *> CborRead.arrayN(node, size, context)

  private def map(node: Node, context: String): Result[Vector[(Node, Node)]] =
    definite(node, context) *> CborRead.map(node, context)

  private def bytes(node: Node): Result[Unit] =
    definite(node, "SSC/boundary bytes") *> CborRead.bytes(node, "SSC/boundary bytes").void

  private def word(node: Node, bits: Int, context: String): Result[BigInt] =
    CborRead.uint(node, context).flatMap { value =>
      val maximum = (BigInt(1) << bits) - 1
      Either.cond(
        value <= maximum,
        value,
        LedgerDecodeError.NumberOutOfRange(context, value, 0, maximum)
      )
    }

  private def definite(node: Node, context: String): Result[Unit] =
    Either.cond(
      node.original.size > 0 && (node.original(0) & 31) != 31,
      (),
      LedgerDecodeError.IndefiniteLength(context)
    )
