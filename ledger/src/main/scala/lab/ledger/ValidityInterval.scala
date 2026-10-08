// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value as V}

/** Conway PV9 interval predicate. No signature, consensus, or ledger-validity claim. */
object ValidityInterval:
  val ProfileId = "conway-pv9-cluster-validity-interval-v1"
  val MaxSlot: BigInt = (BigInt(1) << 64) - 1
  final class Interval private[ValidityInterval] (
      val lower: Option[BigInt],
      val upper: Option[BigInt],
      val originalBody: Bytes,
      val originalTransaction: Bytes
  ):
    val transactionId: Bytes = Blake2b.hash256.hash(originalBody)
  final case class Receipt(interval: Interval, slot: BigInt, satisfied: Boolean):
    val fullLedgerValidated = false
    val profileId = ProfileId

  private def bound(node: Option[Node]): Either[String, Option[BigInt]] = node match
    case None => Right(None)
    case Some(n) =>
      n.value match
        case V.UInt(value) if value >= 0 && value <= MaxSlot => Right(Some(value))
        case _ => Left("validity bound must be uint64, not null/tagged/negative")

  /** Explicit closed transfer syntax: fields 0/1/2 with optional 3 and 8 only. Ordinary transfer
    * decoders keep their original field whitelist.
    */
  def decode(original: Bytes): Either[String, Interval] =
    for
      root <- Cbor.decode(original, Cbor.Limits(1048576, 16, 65536, 1048576))
      envelope <- root.value match
        case V.Arr(Vector(body, witness, valid, aux))
            if valid.value == V.Bool(true) && aux.value == V.Null =>
          witness.value match
            case V.Map(_) => Right(body)
            case _        => Left("witness map required")
        case _ => Left("four-field phase2-valid transfer envelope without auxiliary data required")
      fields <- envelope.value match
        case V.Map(xs) =>
          xs.foldLeft[Either[String, Map[BigInt, Node]]](Right(Map.empty)) {
            case (acc, (key, value)) =>
              acc.flatMap { seen =>
                key.value match
                  case V.UInt(k) if !seen.contains(k) => Right(seen.updated(k, value))
                  case _                              => Left("duplicate or non-uint body key")
              }
          }
        case _ => Left("transaction body map required")
      _ <- Either.cond(
        Set[BigInt](0, 1, 2).subsetOf(fields.keySet) &&
          (fields.keySet -- Set[BigInt](0, 1, 2, 3, 8)).isEmpty,
        (),
        "unsupported interval transfer body fields"
      )
      lower <- bound(fields.get(8))
      upper <- bound(fields.get(3))
    yield new Interval(lower, upper, envelope.original, root.original)

  def atSlot(interval: Interval, slot: BigInt): Either[String, Receipt] =
    Either.cond(
      slot >= 0 && slot <= MaxSlot,
      Receipt(interval, slot, interval.lower.forall(_ <= slot) && interval.upper.forall(slot < _)),
      "ledger slot must be uint64"
    )

  def check(original: Bytes, slot: BigInt): Either[String, Receipt] =
    decode(original).flatMap(atSlot(_, slot))
