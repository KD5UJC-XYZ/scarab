// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.byron

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Value}
import xyz.kd5ujc.scarab.ledger.{CborRead, LedgerDecodeError}

import ByronInteger.decode as integer

/** Deprecated Byron components still have a wire grammar. Mirror cardano-ledger f649f975
  * Block/Boundary.hs:73–83, Block/Block.hs:327–334,446–451 and Ssc.hs:76–95,193–253;
  * Binary/Decoding/Drop.hs distinguishes indefinite lists, definite maps and tagged sets. Ordinary
  * transaction, delegation and update values use their DecCBOR grammar. This checks wire structure,
  * not ledger admission, and adds no SSC proof check (Block/Validation.hs:307–328).
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
      _ <- list(fields(0), "transaction payload")(transaction)
      _ <- ssc(fields(1))
      _ <- list(fields(2), "delegation payload")(delegation)
      _ <- update(fields(3))
    yield ()

  // Ordinary Byron values use their actual DecCBOR instances, not the permissive SSC droppers:
  // UTxO/Tx.hs:110–113,171–177,214–217; TxAux.hs:108–115; TxWitness.hs:118–128.
  private def transaction(node: Node): Result[Unit] =
    for
      aux <- tuple(node, 2, "TxAux")
      tx <- tuple(aux(0), 3, "transaction")
      _ <- nonempty(tx(0), "transaction inputs")(input)
      _ <- nonempty(tx(1), "transaction outputs")(output)
      _ <- attributes(tx(2), "transaction attributes")
      witnesses <- definite(aux(1), "witness vector") *> CborRead.array(aux(1), "witness vector")
      _ <- witnesses.traverse_(witness)
    yield ()

  private def input(node: Node): Result[Unit] =
    for
      fields <- tuple(node, 2, "input")
      tag <- word(fields(0), 8, "input tag")
      _ <- Either.cond(tag == 0, (), LedgerDecodeError.UnknownTag("input", tag))
      inner <- nested(fields(1), "input")
      value <- tuple(inner, 2, "input reference")
      _ <- fixedBytes(value(0), 32, "input hash")
      _ <- word(value(1), 16, "input index")
    yield ()

  private def witness(node: Node): Result[Unit] =
    for
      fields <- tuple(node, 2, "witness")
      tag <- word(fields(0), 8, "witness tag")
      keySize <- tag match
        case n if n == 0 => Right(64)
        case n if n == 2 => Right(32)
        case n           => Left(LedgerDecodeError.UnknownTag("witness", n))
      inner <- nested(fields(1), "witness")
      value <- tuple(inner, 2, "witness value")
      _ <- fixedBytes(value(0), keySize, "witness key")
      _ <- fixedBytes(value(1), 64, "witness signature")
    yield ()

  private def output(node: Node): Result[Unit] =
    for
      fields <- tuple(node, 2, "output")
      _ <- address(fields(0))
      amount <- word(fields(1), 64, "lovelace")
      _ <- lovelace(amount)
    yield ()

  private def address(node: Node): Result[Unit] =
    for
      outer <- tuple(node, 2, "address")
      inner <- nested(outer(0), "address")
      _ <- word(outer(1), 32, "address CRC")
      fields <- tuple(inner, 3, "address payload")
      _ <- fixedBytes(fields(0), 28, "address root")
      attrs <- attributes(fields(1), "address attributes")
      _ <- attrs.traverse_ { (key, value) =>
        if key == 1 || key == 2 then
          CborRead
            .bytes(value, "address attribute")
            .flatMap(CborRead.decode(_, ByronCbor.BodyLimits))
            .flatMap { payload =>
              if key == 1 then canonicalBytes(payload, "derivation path")
              else canonicalWord(payload, 32, "network magic").void
            }
        else Right(())
      }
      tag <- canonicalWord(fields(2), 8, "address type")
      _ <- Either.cond(tag == 0 || tag == 2, (), LedgerDecodeError.UnknownTag("address", tag))
      _ <- ByronAddress.decode(node.original).void
    yield ()

  private def delegation(node: Node): Result[Unit] =
    for
      fields <- tuple(node, 4, "delegation certificate")
      _ <- word(fields(0), 64, "delegation epoch")
      _ <- fields.drop(1).traverse_(fixedBytes(_, 64, "delegation key/signature"))
    yield ()

  // Update/Payload.hs:93–98, Proposal.hs:157–172 and Vote.hs:198–209.
  private def update(node: Node): Result[Unit] =
    for
      fields <- tuple(node, 2, "update payload")
      _ <- maybe(fields(0), "update proposal")(proposal)
      _ <- list(fields(1), "update votes")(vote)
    yield ()

  private def proposal(node: Node): Result[Unit] =
    for
      fields <- tuple(node, 7, "update proposal")
      version <- tuple(fields(0), 3, "protocol version")
      _ <- word(version(0), 16, "protocol major") *> word(version(1), 16, "protocol minor") *> word(
        version(2),
        8,
        "protocol alternative"
      )
      _ <- parameters(fields(1))
      software <- tuple(fields(2), 2, "software version")
      _ <- text(software(0), "application name") *> word(software(1), 32, "software number")
      metadata <- map(fields(3), "installer metadata")
      names <- metadata.traverse { (key, value) =>
        for
          name <- text(key, "system tag")
          hashes <- tuple(value, 4, "installer hashes")
          _ <- bytes(hashes(0)) *> fixedBytes(hashes(1), 32, "installer hash") *> bytes(
            hashes(2)
          ) *> bytes(hashes(3))
        yield name.codePoints().toArray.toVector
      }
      _ <- ascending(names, fields(3), "installer metadata")((a, b) =>
        a.zip(b).collectFirst { case (x, y) if x != y => x < y }.getOrElse(a.size < b.size)
      )
      _ <- emptyAttributes(fields(4), "proposal attributes")
      _ <- fixedBytes(fields(5), 64, "proposal issuer") *> fixedBytes(
        fields(6),
        64,
        "proposal signature"
      )
    yield ()

  private def vote(node: Node): Result[Unit] =
    for
      fields <- tuple(node, 4, "update vote")
      _ <- fixedBytes(fields(0), 64, "voter") *> fixedBytes(fields(1), 32, "proposal ID")
      _ <- CborRead.bool(fields(2), "vote decision")
      _ <- fixedBytes(fields(3), 64, "vote signature")
    yield ()

  private[byron] def parameters(node: Node): Result[Unit] =
    tuple(node, 14, "protocol parameters").flatMap(_.zipWithIndex.traverse_ { (value, index) =>
      maybe(value, "parameter update") { item =>
        index match
          case 0 => word(item, 16, "script version").void
          case i if i >= 1 && i <= 5 =>
            integer(item, "natural parameter").flatMap(n =>
              Either.cond(
                n >= 0,
                (),
                LedgerDecodeError.Expected(
                  "natural parameter",
                  LedgerDecodeError.Shape.Unsigned,
                  item.value
                )
              )
            )
          case i if i >= 6 && i <= 9 => portion(item)
          case 10 | 13               => word(item, 64, "slot/epoch parameter").void
          case 11 => tuple(item, 3, "softfork rule").flatMap(_.traverse_(portion))
          case _  => feePolicy(item)
      }
    })

  private def feePolicy(node: Node): Result[Unit] =
    for
      fields <- tuple(node, 2, "fee policy")
      tag <- word(fields(0), 8, "fee policy tag")
      _ <- Either.cond(tag == 0, (), LedgerDecodeError.UnknownTag("fee policy", tag))
      inner <- nested(fields(1), "linear fee")
      coefficients <- tuple(inner, 2, "linear fee")
      a <- integer(coefficients(0), "fee summand Nano")
      b <- integer(coefficients(1), "fee multiplier Nano")
      _ <- TxFeePolicy.fromNano(a, b)
    yield ()

  private def portion(node: Node): Result[Unit] =
    word(node, 64, "lovelace portion").flatMap(n =>
      Either.cond(
        n <= BigInt(10).pow(15),
        (),
        LedgerDecodeError.NumberOutOfRange("lovelace portion", n, 0, BigInt(10).pow(15))
      )
    )

  private def lovelace(value: BigInt): Result[Unit] =
    Either.cond(
      value <= ByronLedger.MaxLovelace,
      (),
      LedgerDecodeError.NumberOutOfRange("lovelace", value, 0, ByronLedger.MaxLovelace)
    )

  private def maybe(node: Node, context: String)(element: Node => Result[Unit]): Result[Unit] =
    (definite(node, context) *> CborRead.array(node, context)).flatMap {
      case Vector()      => Right(())
      case Vector(value) => element(value)
      case values        => Left(LedgerDecodeError.AtMostOne(context, values.size))
    }

  private def attributes(node: Node, context: String): Result[Vector[(BigInt, Node)]] =
    for
      entries <- map(node, context)
      values <- entries.traverse { (key, value) =>
        (word(key, 8, context), bytes(value)).mapN((k, _) => k -> value)
      }
      _ <- ascending(values.map(_._1), node, context)(_ < _)
    yield values

  private def emptyAttributes(node: Node, context: String): Result[Unit] =
    map(node, context).flatMap(values =>
      Either.cond(
        values.isEmpty,
        (),
        LedgerDecodeError.NumberOutOfRange(context, BigInt(values.size), 0, 0)
      )
    )

  private def ascending[A](keys: Vector[A], node: Node, context: String)(
      less: (A, A) => Boolean
  ): Result[Unit] =
    Either.cond(
      keys.zip(keys.drop(1)).forall((a, b) => less(a, b)),
      (),
      LedgerDecodeError.Expected(
        s"$context (ascending unique keys)",
        LedgerDecodeError.Shape.Map,
        node.value
      )
    )

  private def nested(node: Node, context: String): Result[Node] = node.value match
    case Value.Tag(tag, inner) if tag == 24 =>
      bytes(inner) *> CborRead
        .bytes(inner, context)
        .flatMap(CborRead.decode(_, ByronCbor.BodyLimits))
    case _ => Left(LedgerDecodeError.ExpectedTag(context, 24, node.value))

  private def fixedBytes(node: Node, size: Int, context: String): Result[Unit] =
    bytes(node) *> CborRead.bytesN(node, size, context).void

  private def text(node: Node, context: String): Result[String] =
    definite(node, context) *> CborRead.text(node, context)

  private def canonicalWord(node: Node, bits: Int, context: String): Result[BigInt] =
    word(node, bits, context).flatMap(n =>
      Either.cond(
        node.original == ByronCbor.uint(n),
        n,
        LedgerDecodeError.Expected(
          s"$context (canonical)",
          LedgerDecodeError.Shape.Unsigned,
          node.value
        )
      )
    )

  private def canonicalBytes(node: Node, context: String): Result[Unit] =
    bytes(node) *> CborRead.bytes(node, context).flatMap { value =>
      val size = value.size
      val expected =
        if size < 24 then size else if size <= 255 then 24 else if size <= 65535 then 25 else 26
      Either.cond(
        (node.original(0) & 31) == expected,
        (),
        LedgerDecodeError
          .Expected(s"$context (canonical)", LedgerDecodeError.Shape.Bytes, node.value)
      )
    }

  private def nonempty(node: Node, context: String)(element: Node => Result[Unit]): Result[Unit] =
    CborRead
      .array(node, context)
      .flatMap(values =>
        Either.cond(values.nonEmpty, (), LedgerDecodeError.EmptyInputsOrOutputs) *> list(
          node,
          context
        )(element)
      )

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
    definite(node, "Byron bytes") *> CborRead.bytes(node, "Byron bytes").void

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
