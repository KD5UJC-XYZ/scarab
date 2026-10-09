// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.vrf.PraosLeaderThreshold.Fraction
import scala.util.control.NonFatal

/** Extracts the current nesPd directly from an original DebugNewEpochState reply.
  *
  * Pinned encodings: ledger-shelley 1.19.0.1 LedgerState/Types.hs NewEpochState is [epoch,
  * previousBlocks, currentBlocks, epochState, rewardUpdate, poolDistr, avvm]. ledger-core 1.21.0.0
  * State/PoolDistr.hs encodes [poolMap, totalActiveStake], with poolMap values [relativeStake,
  * absoluteStake, vrfHash]. PV9 rationals use tag30.
  *
  * This is a narrow coherent leadership profile, not full native state validation. Other epoch
  * fields are semantically opaque but must fit the existing bounded CBOR subset:
  * floats/undefined/unassigned simple values anywhere fail closed. PoolDistr copies augmented with
  * governance proposal deposits can retain old fractions; they fail this profile's cross-product
  * check, without asserting native invalidity. No point authentication, historical authority, seed
  * admission or runtime attachment is supplied by this extractor. Original bytes are retained,
  * never reconstructed.
  */
private[lab] object NativeLeadershipSnapshot:
  final class Decoded private[NativeLeadershipSnapshot] (
      val epoch: BigInt,
      val registrations: Map[Bytes, Bytes],
      val stakes: Map[Bytes, Fraction],
      val originalEpoch: Bytes,
      val leadershipBytes: Bytes,
      val digest: Bytes
  )

  private val MaxWord = (BigInt(1) << 64) - 1
  private val MaxBytes = 512 * 1024
  private val Limits = Cbor.Limits(MaxBytes, 48, 200000, MaxBytes)
  private def get[A](value: Either[String, A]): A =
    value.fold(message => throw new IllegalArgumentException(message), identity)
  private def array(node: Node, size: Int): Vector[Node] = node.value match
    case V.Arr(values) if values.size == size => values
    case _ => throw new IllegalArgumentException("native leadership record width/shape")
  private def uint(node: Node): BigInt = node.value match
    case V.UInt(value) if value >= 0 && value <= MaxWord => value
    case _ => throw new IllegalArgumentException("native leadership unsigned Word64 required")
  private def hash(node: Node, width: Int): Bytes = node.value match
    case V.ByteString(bytes) if bytes.size == width =>
      // Both native hash widths use the canonical two-byte definite byte-string header.
      require(
        node.original == Bytes(Vector(0x58.toByte, width.toByte) ++ bytes.value),
        "native leadership canonical hash encoding required"
      )
      bytes
    case _ => throw new IllegalArgumentException("native leadership hash width/shape")
  private def fraction(node: Node): Fraction = node.value match
    case V.Tag(tag, inner) if tag == 30 =>
      val parts = array(inner, 2)
      val numerator = uint(parts(0))
      val denominator = uint(parts(1))
      require(
        denominator > 0 && numerator <= denominator && numerator.gcd(denominator) == 1,
        "native leadership reduced unit rational required"
      )
      get(Fraction.checked(numerator, denominator))
    case _ => throw new IllegalArgumentException("native leadership PV9 rational tag30 required")

  def decodeEpoch(raw: Bytes): Either[String, Decoded] =
    try
      require(
        raw != null && raw.value != null && raw.size > 0 && raw.size <= MaxBytes,
        "native leadership original epoch byte bound"
      )
      // Cbor.decode also enforces full consumption, nesting/item/string bounds.
      val epochFields = array(get(Cbor.decode(raw, Limits)), 7)
      val epoch = uint(epochFields(0))
      val leadership = epochFields(5)
      val distribution = array(leadership, 2)
      val total = uint(distribution(1))
      require(total > 0, "native leadership total must be positive")
      val entries = distribution(0).value match
        case V.Map(values) if values.nonEmpty && values.size <= 4096 => values
        case _ =>
          throw new IllegalArgumentException("native leadership requires 1 through 4096 pools")
      var registrations = Map.empty[Bytes, Bytes]
      var stakes = Map.empty[Bytes, Fraction]
      var accumulated = BigInt(0)
      entries.foreach { (poolNode, stakeNode) =>
        val pool = hash(poolNode, 28)
        require(!registrations.contains(pool), "duplicate native leadership pool")
        val fields = array(stakeNode, 3)
        val share = fraction(fields(0))
        val amount = uint(fields(1))
        val vrf = hash(fields(2), 32)
        require(
          amount <= total && share.numerator * total == amount * share.denominator,
          "native leadership fraction/amount/total mismatch"
        )
        accumulated += amount
        require(accumulated <= total, "native leadership stake sum exceeds total")
        registrations = registrations.updated(pool, vrf)
        stakes = stakes.updated(pool, share)
      }
      require(accumulated == total, "native leadership stake sum differs from total")
      Right(
        new Decoded(
          epoch,
          registrations,
          stakes,
          raw,
          leadership.original,
          ClusterHeaderObservation.sha256(raw)
        )
      )
    catch
      case NonFatal(error) =>
        Left(Option(error.getMessage).getOrElse(error.getClass.getName))
