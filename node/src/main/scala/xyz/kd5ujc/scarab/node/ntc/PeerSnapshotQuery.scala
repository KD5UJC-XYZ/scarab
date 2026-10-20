// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.data.NonEmptyVector
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{Ipv4, Ipv6, LedgerRelay}
import xyz.kd5ujc.scarab.network.selection.{AccPoolStake, LedgerPeerStake, LedgerPool}
import xyz.kd5ujc.scarab.node.LedgerPeersSource
import xyz.kd5ujc.scarab.protocol.NtcVersion
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** `GetLedgerPeerSnapshot`: consensus `Shelley/Ledger/Query.hs:477–510` at 82ecba3 and
  * ouroboros-network 1.2.0.0 `LedgerPeers/Type.hs:309–355,395–455`. Ordinary snapshots retain the
  * ledger's shares; big snapshots normalize the usable pools and apply the 90% cutoff.
  */
private[ntc] object PeerSnapshotQuery:
  def answer(
      env: QueryEnv,
      point: Point,
      facts: ConwayFacts,
      kind: Int,
      legacy: Boolean,
      version: NtcVersion
  ): Either[Unanswered, Bytes] =
    if !legacy && kind != 0 && kind != 1 then Left(Unanswered.Unsupported)
    else
      LedgerPeersSource.of(facts.l).leftMap(Unanswered.Refused(_)).flatMap { available =>
        if available.skipped.nonEmpty then Left(Unanswered.Unwritable)
        else if legacy then Right(legacySnapshot(point, available.pools, version.number >= 22))
        else
          env.shelleyGenesis.hcursor
            .get[BigInt]("networkMagic")
            .leftMap(_ => Unanswered.Unwritable)
            .flatMap(magic =>
              Either.cond(magic >= 0 && magic.bitLength <= 32, magic, Unanswered.Unwritable)
            )
            .map { magic =>
              CborOut.build { w =>
                w.array(2).uint(if kind == 0 then 3 else 2).array(3)
                snapshotPoint(w, point)
                w.uint(magic)
                if kind == 0 then allPools(w, available.pools)
                else bigPools(w, LedgerPeerStake.accumulated(available.pools))
              }
            }
      }

  private def snapshotPoint(w: CborOut, point: Point): Unit = point match
    case Point.Origin            => w.array(1).uint(0)
    case Point.Block(slot, hash) => w.array(3).uint(1).uint(slot.value).bytes(hash)

  /** Cardano.Binary's `ToCBOR (Ratio a)` is an untagged pair. The ledger's `Wire.ratio`
    * deliberately has a different contract (tag 30); it cannot encode these network ratios.
    */
  private def ratio(w: CborOut, value: Rational): Unit =
    w.array(2).int(value.numerator).integer(value.denominator)

  private def allPools(w: CborOut, pools: Vector[LedgerPool[LedgerRelay]]): Unit =
    w.indefiniteArray
    pools.foreach { pool =>
      w.array(2)
      ratio(w, pool.stake.share)
      relays(w, pool.relays)
    }
    w.break

  private def bigPools(w: CborOut, pools: Vector[(AccPoolStake, LedgerPool[LedgerRelay])]): Unit =
    w.indefiniteArray
    pools.foreach { (accumulated, pool) =>
      w.array(3)
      ratio(w, accumulated.share)
      ratio(w, pool.stake.share)
      relays(w, pool.relays)
    }
    w.break

  private def legacySnapshot(
      point: Point,
      pools: Vector[LedgerPool[LedgerRelay]],
      srv: Boolean
  ): Bytes =
    val accumulated = LedgerPeerStake.accumulated(pools)
    val encodable = accumulated.flatMap { (share, pool) =>
      NonEmptyVector
        .fromVector(pool.relays.toVector.filter(r => srv || !r.skipped))
        .map(rs => share -> pool.copy(relays = rs))
    }
    CborOut.build { w =>
      w.array(2).uint(1).array(2)
      point match
        case Point.Origin         => w.array(1).uint(0)
        case Point.Block(slot, _) => w.array(2).uint(1).uint(slot.value)
      w.indefiniteArray
      encodable.foreach { (accumulated, pool) =>
        w.array(2)
        ratio(w, accumulated.share)
        w.array(2)
        ratio(w, pool.stake.share)
        relays(w, pool.relays)
      }
      w.break
    }

  private def domain(w: CborOut, name: String): Unit =
    w.bytes(Bytes.fromArray(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)))

  private def relays(w: CborOut, relays: NonEmptyVector[LedgerRelay]): Unit =
    w.indefiniteArray
    relays.toVector.foreach {
      case LedgerRelay.Domain(name, port) =>
        w.array(3).uint(0).uint(port.toInt)
        domain(w, name)
      case LedgerRelay.Address(ip: Ipv4, port) =>
        w.array(3).uint(1).uint(port.toInt).indefiniteArray
        ip.octets.toArray.foreach(b => w.uint(b & 255))
        w.break
      case LedgerRelay.Address(ip: Ipv6, port) =>
        w.array(3).uint(2).uint(port.toInt).indefiniteArray
        val octets = ip.octets
        (0 until 8).foreach(i => w.uint(((octets(2 * i) & 255) << 8) | (octets(2 * i + 1) & 255)))
        w.break
      case LedgerRelay.Srv(name) =>
        w.array(2).uint(3)
        domain(w, name)
    }
    w.break
