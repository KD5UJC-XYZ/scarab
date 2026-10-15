// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.bls

import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}

/** A seat: its weight and, if the pool registered a key with a valid proof of possession, the key.
  * A seat without a key is *keyless*: it counts toward the committee but cannot sign.
  */
final case class Seat(weight: Weight, key: Option[VerificationKey])

/** A pool's candidacy for a committee: identity (for the tie-break), stake, optional registered
  * key.
  */
final case class Candidate(
    poolId: Bytes,
    stake: Weight,
    registered: Option[(VerificationKey, ProofOfPossession)]
)

/** The ordered seats of an epoch's voting committee. Order is the voter indexing. */
final class Committee private (val seats: Vector[Seat]):
  def size: Int = seats.size

  /** Sum of all seat weights. */
  lazy val totalWeight: Weight = seats.foldLeft(Weight.zero)((a, s) => a + s.weight)

  def seat(id: SeatId): Option[Seat] = seats.lift(id.index)

object Committee:
  /** Seats in the given order. A key whose proof of possession fails is dropped (keyless seat), as
    * `mkLeiosCommittee` does; each proof is checked once, here.
    */
  def fromSeats(
      seats: Vector[(Weight, Option[(VerificationKey, ProofOfPossession)])]
  ): Either[BlsError, Committee] =
    if seats.size > SeatId.maxIndex + 1 then Left(BlsError.CommitteeTooLarge(seats.size))
    else
      Right(
        new Committee(seats.map { case (w, reg) =>
          Seat(
            w,
            reg.flatMap { case (vk, pop) => MinSig.verifyPossession(vk, pop).toOption.map(_ => vk) }
          )
        })
      )

  /** CIP-164 seating: the top `size` candidates by stake descending, ties by pool id ascending
    * (unsigned byte order); position is the seat index.
    */
  def select(candidates: Vector[Candidate], size: Int): Either[BlsError, Committee] =
    val ordered = candidates
      .sortWith { (a, b) =>
        val c = Ordering[Weight].compare(b.stake, a.stake)
        if c != 0 then c < 0 else a.poolId.compareTo(b.poolId) < 0
      }
      .take(math.max(size, 0))
    fromSeats(ordered.map(c => (c.stake, c.registered)))

/** A vote: `[announcing_rb_hash, voter_id, vote_signature]` (the Blueprint and CIP wire form). */
final case class Vote(announcingRbHash: RbHash, voter: SeatId, signature: Signature):
  def encode: Bytes =
    Cbor
      .encode(
        Value.Arr(
          Vector(
            Wire.node(Value.ByteString(announcingRbHash.bytes)),
            Wire.node(Value.UInt(BigInt(voter.index))),
            Wire.node(Value.ByteString(signature.bytes))
          )
        )
      )
      .toOption
      .getOrElse(Bytes.empty) // unreachable: fixed small shape, no limit can be hit

object Vote:
  def sign(sk: SecretKey, voter: SeatId, announcingRbHash: RbHash): Either[BlsError, Vote] =
    MinSig.sign(sk, announcingRbHash.bytes).map(Vote(announcingRbHash, voter, _))

  /** The seat's key must exist; the signature must verify over the announcing hash. Returns the
    * voter's weight (the committee-relative meaning of a valid vote).
    */
  def verify(committee: Committee, vote: Vote): Either[BlsError, Weight] =
    committee.seat(vote.voter) match
      case None                => Left(BlsError.SignerNotInCommittee(vote.voter))
      case Some(Seat(_, None)) => Left(BlsError.SeatHasNoKey(vote.voter))
      case Some(Seat(w, Some(vk))) =>
        MinSig.verify(vk, vote.announcingRbHash.bytes, vote.signature).map(_ => w)

  /** Strict decode: canonical (shortest-form, definite-length) CBOR, exactly three fields. */
  def decode(bytes: Bytes): Either[BlsError, Vote] =
    for
      fields <- Wire.array(bytes, 3)
      hashBytes <- Wire.bytes(fields, 0)
      voter <- Wire.unsigned(fields, 1)
      sigBytes <- Wire.bytes(fields, 2)
      hash <- RbHash
        .fromBytes(hashBytes)
        .toRight(BlsError.MalformedCbor(CborFault.HashNotThirtyTwoBytes(hashBytes.size)))
      seat <-
        if voter > BigInt(SeatId.maxIndex) then
          Left(BlsError.MalformedCbor(CborFault.SeatAbove16Bits))
        else SeatId.fromInt(voter.toInt)
      sig <- Signature.fromBytes(sigBytes)
    yield Vote(hash, seat, sig)

/** `[signers : bytes .size (0 .. 8192), signature : bytes .size 48]` of the Dijkstra block body. */
final case class Certificate(signers: Signers, signature: Signature):
  def encode: Bytes =
    Cbor
      .encode(
        Value.Arr(
          Vector(
            Wire.node(Value.ByteString(signers.bytes)),
            Wire.node(Value.ByteString(signature.bytes))
          )
        )
      )
      .toOption
      .getOrElse(Bytes.empty) // unreachable: bounded shape

object Certificate:
  /** Aggregate the votes (one signature per seat, all over the same hash). Every seat must be in
    * the committee and keyed. The bitfield is `ceil(seats / 8)` bytes.
    */
  def aggregate(
      committee: Committee,
      votes: Map[SeatId, Signature]
  ): Either[BlsError, Certificate] =
    if votes.isEmpty then Left(BlsError.NoSigners)
    else
      val ordered = votes.toVector.sortBy(_._1.index)
      for
        _ <- ordered
          .collectFirst {
            case (s, _) if committee.seat(s).isEmpty => BlsError.SignerNotInCommittee(s)
            case (s, _) if committee.seat(s).exists(_.key.isEmpty) => BlsError.SeatHasNoKey(s)
          }
          .toLeft(())
        signers <- Signers.of(committee.size, votes.keySet)
        sig <- MinSig.aggregateSignatures(ordered.map(_._2))
      yield Certificate(signers, sig)

  /** Verify a certificate over `message` (the announcing RB hash) against a committee: bitfield
    * shape, keyless signers, weight at least `required`, then one aggregate pairing check. Returns
    * the signers' total weight. Same order of checks as `verifyLeiosCert` in cardano-base, plus the
    * strict padding rule (see docs/leios.md).
    */
  def verify(
      committee: Committee,
      required: Weight,
      message: RbHash,
      cert: Certificate
  ): Either[BlsError, Weight] =
    for
      members <- cert.signers.members(committee.size)
      _ <- Either.cond(members.nonEmpty, (), BlsError.NoSigners)
      seats = members.map(m => (m, committee.seats(m.index)))
      keyless = seats.collect { case (m, Seat(_, None)) => m }
      _ <- Either.cond(keyless.isEmpty, (), BlsError.SignerWithoutKey(keyless))
      weight = seats.foldLeft(Weight.zero)((a, s) => a + s._2.weight)
      _ <- Either.cond(
        Ordering[Weight].gteq(weight, required),
        (),
        BlsError.InsufficientWeight(weight, required)
      )
      keys = seats.flatMap(_._2.key)
      _ <- MinSig.verifyAggregate(keys, message.bytes, cert.signature)
    yield weight

  /** Strict decode: canonical CBOR, two fields, bitfield at most 8192 bytes, valid signature point.
    */
  def decode(bytes: Bytes): Either[BlsError, Certificate] =
    for
      fields <- Wire.array(bytes, 2)
      signerBytes <- Wire.bytes(fields, 0)
      sigBytes <- Wire.bytes(fields, 1)
      signers <- Signers
        .fromBytes(signerBytes)
        .toRight(BlsError.MalformedCbor(CborFault.SignersTooLong(signerBytes.size)))
      sig <- Signature.fromBytes(sigBytes)
    yield Certificate(signers, sig)

/** Strict, canonical CBOR plumbing for the two wire shapes. */
private object Wire:
  def node(v: Value): Node = Node(v, Bytes.empty)

  /** The fields of a definite array of `arity` items, rejecting anything not in shortest form. */
  def array(bytes: Bytes, arity: Int): Either[BlsError, Vector[Value]] =
    for
      n <- Cbor.decode(bytes).left.map(_ => BlsError.MalformedCbor(CborFault.Syntax))
      canonical <- Cbor.encode(n.value).left.map(_ => BlsError.MalformedCbor(CborFault.Syntax))
      _ <- Either.cond(canonical == bytes, (), BlsError.MalformedCbor(CborFault.Syntax))
      items <- n.value match
        case Value.Arr(xs) => Right(xs.map(_.value))
        case _             => Left(BlsError.MalformedCbor(CborFault.NotAnArray))
      _ <- Either.cond(
        items.size == arity,
        (),
        BlsError.MalformedCbor(CborFault.WrongArity(arity, items.size))
      )
    yield items

  def bytes(fields: Vector[Value], i: Int): Either[BlsError, Bytes] = fields(i) match
    case Value.ByteString(b) => Right(b)
    case _                   => Left(BlsError.MalformedCbor(CborFault.FieldNotBytes(i)))

  def unsigned(fields: Vector[Value], i: Int): Either[BlsError, BigInt] = fields(i) match
    case Value.UInt(n) => Right(n)
    case _             => Left(BlsError.MalformedCbor(CborFault.FieldNotUnsigned(i)))
