// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.byron

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Value}
import xyz.kd5ujc.scarab.ledger.{CborRead, DecodeField, DecodeSite, LedgerDecodeError}
import xyz.kd5ujc.scarab.ledger.DecodeSite.Qualifier

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
        if boundary then list(decoded, DecodeField.BoundaryBody)(bytes)
        else main(decoded)
      trailing <- CborRead.decode(extra, ByronCbor.BodyLimits)
      fields <- tuple(trailing, 1, DecodeField.ExtraBodyData)
      attrs <- map(fields(0), DecodeField.ExtraAttributes)
      _ <-
        if boundary then
          attrs.traverse_ { (key, value) =>
            word(key, 8, DecodeField.ExtraAttributeKey) *> bytes(value)
          }
        else
          Either.cond(
            attrs.isEmpty,
            (),
            LedgerDecodeError.NumberOutOfRange(
              DecodeField.MainExtraAttributeCount,
              BigInt(attrs.size),
              0,
              0
            )
          )
    yield ()

  private def main(node: Node): Result[Unit] =
    for
      fields <- tuple(node, 4, DecodeField.Body)
      _ <- list(fields(0), DecodeField.TransactionPayload)(transaction)
      _ <- ssc(fields(1))
      _ <- list(fields(2), DecodeField.DelegationPayload)(delegation)
      _ <- update(fields(3))
    yield ()

  // Ordinary Byron values use their actual DecCBOR instances, not the permissive SSC droppers:
  // UTxO/Tx.hs:110–113,171–177,214–217; TxAux.hs:108–115; TxWitness.hs:118–128.
  private def transaction(node: Node): Result[Unit] =
    for
      aux <- tuple(node, 2, DecodeField.TxAux)
      tx <- tuple(aux(0), 3, DecodeField.Transaction)
      _ <- nonempty(tx(0), DecodeField.TransactionInputs)(input)
      _ <- nonempty(tx(1), DecodeField.TransactionOutputs)(output)
      _ <- attributes(tx(2), DecodeField.TransactionAttributes)
      witnesses <- definite(aux(1), DecodeField.WitnessVector) *> CborRead.array(
        aux(1),
        DecodeField.WitnessVector
      )
      _ <- witnesses.traverse_(witness)
    yield ()

  private def input(node: Node): Result[Unit] =
    for
      fields <- tuple(node, 2, DecodeField.Input)
      tag <- word(fields(0), 8, DecodeField.InputTag)
      _ <- Either.cond(tag == 0, (), LedgerDecodeError.UnknownTag(DecodeField.Input, tag))
      inner <- nested(fields(1), DecodeField.Input)
      value <- tuple(inner, 2, DecodeField.InputReference)
      _ <- fixedBytes(value(0), 32, DecodeField.InputHash)
      _ <- word(value(1), 16, DecodeField.InputIndex)
    yield ()

  private def witness(node: Node): Result[Unit] =
    for
      fields <- tuple(node, 2, DecodeField.Witness)
      tag <- word(fields(0), 8, DecodeField.WitnessTag)
      keySize <- tag match
        case n if n == 0 => Right(64)
        case n if n == 2 => Right(32)
        case n           => Left(LedgerDecodeError.UnknownTag(DecodeField.Witness, n))
      inner <- nested(fields(1), DecodeField.Witness)
      value <- tuple(inner, 2, DecodeField.WitnessValue)
      _ <- fixedBytes(value(0), keySize, DecodeField.WitnessKey)
      _ <- fixedBytes(value(1), 64, DecodeField.WitnessSignature)
    yield ()

  private def output(node: Node): Result[Unit] =
    for
      fields <- tuple(node, 2, DecodeField.Output)
      _ <- address(fields(0))
      amount <- word(fields(1), 64, DecodeField.Lovelace)
      _ <- lovelace(amount)
    yield ()

  /** The address is read as the reference reads it, in full ([[ByronAddress.decode]]). */
  private def address(node: Node): Result[Unit] = ByronAddress.decode(node.original).void

  private def delegation(node: Node): Result[Unit] =
    for
      fields <- tuple(node, 4, DecodeField.DelegationCertificate)
      _ <- word(fields(0), 64, DecodeField.DelegationEpoch)
      _ <- fields.drop(1).traverse_(fixedBytes(_, 64, DecodeField.DelegationKeySignature))
    yield ()

  // Update/Payload.hs:93–98, Proposal.hs:157–172 and Vote.hs:198–209.
  private def update(node: Node): Result[Unit] =
    for
      fields <- tuple(node, 2, DecodeField.UpdatePayload)
      _ <- maybe(fields(0), DecodeField.UpdateProposal)(proposal)
      _ <- list(fields(1), DecodeField.UpdateVotes)(vote)
    yield ()

  private def proposal(node: Node): Result[Unit] =
    for
      fields <- tuple(node, 7, DecodeField.UpdateProposal)
      version <- tuple(fields(0), 3, DecodeField.ProtocolVersion)
      _ <- word(version(0), 16, DecodeField.ProtocolMajor) *> word(
        version(1),
        16,
        DecodeField.ProtocolMinor
      ) *> word(
        version(2),
        8,
        DecodeField.ProtocolAlternative
      )
      _ <- parameters(fields(1))
      software <- tuple(fields(2), 2, DecodeField.SoftwareVersion)
      _ <- text(software(0), DecodeField.ApplicationName) *> word(
        software(1),
        32,
        DecodeField.SoftwareNumber
      )
      _ <- installerMetadata(fields(3))
      _ <- emptyAttributes(fields(4), DecodeField.ProposalAttributes)
      _ <- fixedBytes(fields(5), 64, DecodeField.ProposalIssuer) *> fixedBytes(
        fields(6),
        64,
        DecodeField.ProposalSignature
      )
    yield ()

  /** A proposal's `metadata`, `Map SystemTag InstallerHash` (`Update/Proposal.hs:216`,
    * cardano-ledger f649f975), read at the Byron decoder version: a definite map whose keys
    * (`SystemTag`, a `newtype` over `Text`, `Update/SystemTag.hs:41-65`) are definite text,
    * strictly ascending (`decodeMapSkel`, `Decoder.hs:736-766`), and whose values are an
    * `InstallerHash`: four byte strings, the second a 32-byte hash
    * (`Update/InstallerHash.hs:58-65`).
    */
  private[byron] def installerMetadata(node: Node): Result[Unit] =
    for
      metadata <- map(node, DecodeField.InstallerMetadata)
      names <- metadata.traverse { (key, value) =>
        for
          name <- text(key, DecodeField.SystemTag)
          hashes <- tuple(value, 4, DecodeField.InstallerHashes)
          _ <- bytes(hashes(0)) *> fixedBytes(hashes(1), 32, DecodeField.InstallerHash) *> bytes(
            hashes(2)
          ) *> bytes(hashes(3))
        yield name.codePoints().toArray.toVector
      }
      _ <- ascending(names, node, DecodeField.InstallerMetadata)((a, b) =>
        a.zip(b).collectFirst { case (x, y) if x != y => x < y }.getOrElse(a.size < b.size)
      )
    yield ()

  /** A header's body proof as the reference reads it at decoder version 1. A boundary header's is
    * dropped with `dropBytes` (`Block/Header.hs:620-621`): a definite byte string. A main header's
    * is a `Proof` (`Block/Proof.hs:82-85`): the `TxProof` (`UTxO/TxProof.hs:82-85`: a `Word32`
    * count, the Merkle root and the witnesses hash), the SSC proof (`dropSscProof`,
    * `Ssc.hs:169-188`, whose byte strings are dropped whatever their length), and the delegation
    * and update payload hashes; the Merkle root and the three hashes are 32-byte definite byte
    * strings, the SSC proof's byte strings definite (cardano-ledger f649f975).
    */
  private[ledger] def bodyProof(node: Node, boundary: Boolean): Result[Unit] =
    if boundary then bytes(node)
    else
      for
        fields <- tuple(node, 4, DecodeField.BodyProof)
        tx <- tuple(fields(0), 3, DecodeField.TxProof)
        _ <- word(tx(0), 32, DecodeField.TxCount)
        _ <- fixedBytes(tx(1), 32, DecodeField.TxRoot)
        _ <- fixedBytes(tx(2), 32, DecodeField.WitnessesHash)
        _ <- sscProof(fields(1))
        _ <- fixedBytes(fields(2), 32, DecodeField.DelegationProof)
        _ <- fixedBytes(fields(3), 32, DecodeField.UpdateProof)
      yield ()

  /** `dropSscProof`: a `Word8` tag, then two byte strings for tags 0 to 2 and one for tag 3. */
  private def sscProof(node: Node): Result[Unit] =
    for
      fields <- definite(node, DecodeField.SSCProof) *> CborRead.array(node, DecodeField.SSCProof)
      tag <- fields.headOption
        .toRight(LedgerDecodeError.Empty(DecodeField.SSCProof))
        .flatMap(word(_, 8, DecodeField.SSCTag))
      size <- tag match
        case t if t >= 0 && t <= 2 => Right(3)
        case t if t == 3           => Right(2)
        case t                     => Left(LedgerDecodeError.UnknownTag(DecodeField.SSCProof, t))
      _ <- Either.cond(
        fields.size == size,
        (),
        LedgerDecodeError.ArrayLength(DecodeField.SSCProof, size, fields.size)
      )
      _ <- fields.drop(1).traverse_(bytes)
    yield ()

  private def vote(node: Node): Result[Unit] =
    for
      fields <- tuple(node, 4, DecodeField.UpdateVote)
      _ <- fixedBytes(fields(0), 64, DecodeField.Voter) *> fixedBytes(
        fields(1),
        32,
        DecodeField.ProposalID
      )
      _ <- CborRead.bool(fields(2), DecodeField.VoteDecision)
      _ <- fixedBytes(fields(3), 64, DecodeField.VoteSignature)
    yield ()

  private[byron] def parameters(node: Node): Result[Unit] =
    tuple(node, 14, DecodeField.ProtocolParameters).flatMap(_.zipWithIndex.traverse_ {
      (value, index) =>
        maybe(value, DecodeField.ParameterUpdate) { item =>
          index match
            case 0 => word(item, 16, DecodeField.ProtocolScriptVersion).void
            case i if i >= 1 && i <= 5 =>
              integer(item, DecodeField.NaturalParameter).flatMap(n =>
                Either.cond(
                  n >= 0,
                  (),
                  LedgerDecodeError.Expected(
                    DecodeField.NaturalParameter,
                    LedgerDecodeError.Shape.Unsigned,
                    item.value
                  )
                )
              )
            case i if i >= 6 && i <= 9 => portion(item)
            case 10 | 13               => word(item, 64, DecodeField.SlotEpochParameter).void
            case 11 => tuple(item, 3, DecodeField.SoftforkRule).flatMap(_.traverse_(portion))
            case _  => feePolicy(item)
        }
    })

  private def feePolicy(node: Node): Result[Unit] =
    for
      fields <- tuple(node, 2, DecodeField.FeePolicy)
      tag <- word(fields(0), 8, DecodeField.FeePolicyTag)
      _ <- Either.cond(tag == 0, (), LedgerDecodeError.UnknownTag(DecodeField.FeePolicy, tag))
      inner <- nested(fields(1), DecodeField.LinearFee)
      coefficients <- tuple(inner, 2, DecodeField.LinearFee)
      a <- integer(coefficients(0), DecodeField.FeeSummandNano)
      b <- integer(coefficients(1), DecodeField.FeeMultiplierNano)
      _ <- TxFeePolicy.fromNano(a, b)
    yield ()

  private def portion(node: Node): Result[Unit] =
    word(node, 64, DecodeField.LovelacePortion).flatMap(n =>
      Either.cond(
        n <= BigInt(10).pow(15),
        (),
        LedgerDecodeError.NumberOutOfRange(DecodeField.LovelacePortion, n, 0, BigInt(10).pow(15))
      )
    )

  private def lovelace(value: BigInt): Result[Unit] =
    Either.cond(
      value <= ByronLedger.MaxLovelace,
      (),
      LedgerDecodeError.NumberOutOfRange(DecodeField.Lovelace, value, 0, ByronLedger.MaxLovelace)
    )

  private def maybe(node: Node, context: DecodeSite)(element: Node => Result[Unit]): Result[Unit] =
    (definite(node, context) *> CborRead.array(node, context)).flatMap {
      case Vector()      => Right(())
      case Vector(value) => element(value)
      case values        => Left(LedgerDecodeError.AtMostOne(context, values.size))
    }

  private[byron] def attributes(node: Node, context: DecodeSite): Result[Vector[(BigInt, Node)]] =
    for
      entries <- map(node, context)
      values <- entries.traverse { (key, value) =>
        (word(key, 8, context), bytes(value)).mapN((k, _) => k -> value)
      }
      _ <- ascending(values.map(_._1), node, context)(_ < _)
    yield values

  /** `dropEmptyAttributes` (`Common/Attributes.hs:241-244`): a definite map of no entries. */
  private[byron] def emptyAttributes(node: Node, context: DecodeSite): Result[Unit] =
    map(node, context).flatMap(values =>
      Either.cond(
        values.isEmpty,
        (),
        LedgerDecodeError.NumberOutOfRange(context, BigInt(values.size), 0, 0)
      )
    )

  private def ascending[A](keys: Vector[A], node: Node, context: DecodeSite)(
      less: (A, A) => Boolean
  ): Result[Unit] =
    Either.cond(
      keys.zip(keys.drop(1)).forall((a, b) => less(a, b)),
      (),
      LedgerDecodeError.Expected(
        context / Qualifier.AscendingUniqueKeys,
        LedgerDecodeError.Shape.Map,
        node.value
      )
    )

  private def nested(node: Node, context: DecodeSite): Result[Node] = node.value match
    case Value.Tag(tag, inner) if tag == 24 =>
      bytes(inner) *> CborRead
        .bytes(inner, context)
        .flatMap(CborRead.decode(_, ByronCbor.BodyLimits))
    case _ => Left(LedgerDecodeError.ExpectedTag(context, 24, node.value))

  private def fixedBytes(node: Node, size: Int, context: DecodeSite): Result[Unit] =
    bytes(node) *> CborRead.bytesN(node, size, context).void

  private def text(node: Node, context: DecodeSite): Result[String] =
    definite(node, context) *> CborRead.text(node, context)

  private[byron] def canonicalWord(node: Node, bits: Int, context: DecodeSite): Result[BigInt] =
    word(node, bits, context).flatMap(n =>
      Either.cond(
        node.original == ByronCbor.uint(n),
        n,
        LedgerDecodeError.Expected(
          context / Qualifier.Canonical,
          LedgerDecodeError.Shape.Unsigned,
          node.value
        )
      )
    )

  private[ledger] def canonicalBytes(node: Node, context: DecodeSite): Result[Unit] =
    bytes(node) *> CborRead.bytes(node, context).flatMap { value =>
      val size = value.size
      val expected =
        if size < 24 then size else if size <= 255 then 24 else if size <= 65535 then 25 else 26
      Either.cond(
        (node.original(0) & 31) == expected,
        (),
        LedgerDecodeError
          .Expected(context / Qualifier.Canonical, LedgerDecodeError.Shape.Bytes, node.value)
      )
    }

  private def nonempty(node: Node, context: DecodeSite)(
      element: Node => Result[Unit]
  ): Result[Unit] =
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
      fields <- definite(node, DecodeField.SSCPayload) *> CborRead.array(
        node,
        DecodeField.SSCPayload
      )
      tag <- fields.headOption
        .toRight(LedgerDecodeError.Empty(DecodeField.SSCPayload))
        .flatMap(word(_, 8, DecodeField.SSCTag))
      size <- tag match
        case t if t >= 0 && t <= 2 => Right(3)
        case t if t == 3           => Right(2)
        case t                     => Left(LedgerDecodeError.UnknownTag(DecodeField.SSCPayload, t))
      _ <- Either.cond(
        fields.size == size,
        (),
        LedgerDecodeError.ArrayLength(DecodeField.SSCPayload, size, fields.size)
      )
      _ <- tag.toInt match
        case 0 =>
          set(fields(1), DecodeField.Commitments)(signedCommitment) *> certificates(fields(2))
        case 1 => byteMap(fields(1), DecodeField.Openings)(bytes) *> certificates(fields(2))
        case 2 =>
          byteMap(fields(1), DecodeField.Shares)(n =>
            byteMap(n, DecodeField.InnerShares)(v => list(v, DecodeField.Shares)(bytes))
          ) *> certificates(fields(2))
        case _ => certificates(fields(1))
    yield ()

  private def signedCommitment(node: Node): Result[Unit] =
    for
      fields <- tuple(node, 3, DecodeField.SignedCommitment)
      _ <- bytes(fields(0))
      commitment <- tuple(fields(1), 2, DecodeField.Commitment)
      _ <- byteMap(commitment(0), DecodeField.CommitmentShares)(
        list(_, DecodeField.EncryptedShares)(bytes)
      )
      proof <- tuple(commitment(1), 4, DecodeField.SecretProof)
      _ <- proof.take(3).traverse_(bytes)
      _ <- list(proof(3), DecodeField.SecretCommitments)(bytes)
      _ <- bytes(fields(2))
    yield ()

  private def certificates(node: Node): Result[Unit] =
    set(node, DecodeField.VSSCertificates) { certificate =>
      for
        fields <- tuple(certificate, 4, DecodeField.VSSCertificate)
        _ <- bytes(fields(0))
        _ <- word(fields(1), 64, DecodeField.VSSEpoch)
        _ <- bytes(fields(2)) *> bytes(fields(3))
      yield ()
    }

  private def byteMap(node: Node, context: DecodeSite)(value: Node => Result[Unit]): Result[Unit] =
    map(node, context).flatMap(_.traverse_ { (key, item) => bytes(key) *> value(item) })

  private def set(node: Node, context: DecodeSite)(element: Node => Result[Unit]): Result[Unit] =
    node.value match
      // The native dropSet discards decodeTag, without requiring tag 258.
      case Value.Tag(_, inner) =>
        (definite(inner, context) *> CborRead.array(inner, context)).flatMap(_.traverse_(element))
      case _ =>
        Left(
          LedgerDecodeError.Expected(
            context / Qualifier.TaggedArray,
            LedgerDecodeError.Shape.Array,
            node.value
          )
        )

  private def list(node: Node, context: DecodeSite)(element: Node => Result[Unit]): Result[Unit] =
    CborRead.array(node, context).flatMap { values =>
      Either.cond(
        node.original.size > 0 && node.original(0) == 0x9f.toByte,
        (),
        LedgerDecodeError
          .Expected(context / Qualifier.Indefinite, LedgerDecodeError.Shape.Array, node.value)
      ) *>
        values.traverse_(element)
    }

  private[byron] def tuple(node: Node, size: Int, context: DecodeSite): Result[Vector[Node]] =
    definite(node, context) *> CborRead.arrayN(node, size, context)

  private def map(node: Node, context: DecodeSite): Result[Vector[(Node, Node)]] =
    definite(node, context) *> CborRead.map(node, context)

  private def bytes(node: Node): Result[Unit] =
    definite(node, DecodeField.ByronBytes) *> CborRead.bytes(node, DecodeField.ByronBytes).void

  private[byron] def word(node: Node, bits: Int, context: DecodeSite): Result[BigInt] =
    CborRead.uint(node, context).flatMap { value =>
      val maximum = (BigInt(1) << bits) - 1
      Either.cond(
        value <= maximum,
        value,
        LedgerDecodeError.NumberOutOfRange(context, value, 0, maximum)
      )
    }

  private[byron] def definite(node: Node, context: DecodeSite): Result[Unit] =
    CborRead.definite(node, context)
