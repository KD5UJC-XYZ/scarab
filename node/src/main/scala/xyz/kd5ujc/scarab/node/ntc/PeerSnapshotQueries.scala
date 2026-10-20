// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.data.NonEmptyVector
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{Ipv4, Ipv6, LedgerRelay}
import xyz.kd5ujc.scarab.network.selection.{LedgerPeerStake, LedgerPool}
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** `encodeLedgerPeerSnapshot`, network 1.2.0.0 c45735a, `LedgerPeers/Type.hs:309-456`. The old
  * query keeps its V2 result even on a V23 transport; SRV filtering is solely a result encoding
  * decision before V22, after accumulating the big-pool quota.
  */
private[ntc] object PeerSnapshotQueries:
  /** A stored relay decoding failure invalidates the answer; omitting that pool would change stake
    * weights and the native 90% cutoff. Unusable but well-formed relays remain filtered by the
    * normal ledger-peer projection.
    */
  def answer(
      env: QueryEnv,
      facts: ConwayFacts,
      at: Point,
      kind: Option[LedgerPeerKind],
      supportsSrv: Boolean
  ): Either[Unanswered, Bytes] =
    for
      supply <- xyz.kd5ujc.scarab.node.LedgerPeersSource.of(facts.l).leftMap(Unanswered.Refused(_))
      _ <- NonEmptyVector.fromVector(supply.skipped) match
        case None        => Right(())
        case Some(pools) => Left(Unanswered.StoredRelays(pools))
      _ <- Either.cond(
        kind.isEmpty || (env.networkMagic >= 0 && env.networkMagic <= 0xffffffffL),
        (),
        Unanswered.Unwritable
      )
    yield encode(supply.pools, at, env.networkMagic, kind, supportsSrv)

  private def fraction(w: CborOut, r: Rational): Unit =
    w.array(2).integer(r.numerator).integer(r.denominator)

  private def relays(w: CborOut, values: Vector[LedgerRelay]): Unit =
    w.indefiniteArray
    values.foreach {
      case LedgerRelay.Domain(name, port) =>
        w.array(3)
          .uint(0)
          .uint(port.toInt)
          .bytes(Bytes.fromArray(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
      case LedgerRelay.Srv(name) =>
        w.array(2)
          .uint(3)
          .bytes(Bytes.fromArray(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
      case LedgerRelay.Address(ip, port) =>
        val (tag, groups) = ip match
          case v4: Ipv4 => (1, v4.octets.value.map(b => BigInt(b & 255)))
          case v6: Ipv6 =>
            val bytes = v6.octets.value
            (
              2,
              bytes.grouped(2).map(_.foldLeft(BigInt(0))((n, b) => (n << 8) + (b & 255))).toVector
            )
        w.array(3).uint(tag).uint(port.toInt).indefiniteArray
        groups.foreach(w.uint)
        w.break
    }
    w.break

  def encode(
      pools: Vector[LedgerPool[LedgerRelay]],
      at: Point,
      magic: Long,
      kind: Option[LedgerPeerKind],
      supportsSrv: Boolean
  ): Bytes =
    val accumulated = LedgerPeerStake.accumulated(pools)
    CborOut.build { w =>
      kind match
        case None =>
          w.array(2).uint(1).array(2)
          at match
            case Point.Origin         => w.array(1).uint(0)
            case Point.Block(slot, _) => w.array(2).uint(1).uint(slot.value)
          w.indefiniteArray
          accumulated.foreach { (acc, pool) =>
            val supported = pool.relays.toVector.filter(r => supportsSrv || !r.skipped)
            if supported.nonEmpty then
              w.array(2)
              fraction(w, acc.share)
              w.array(2)
              fraction(w, pool.stake.share)
              relays(w, supported)
          }
          w.break
        case Some(which) =>
          w.array(2).uint(if which == LedgerPeerKind.All then 3 else 2).array(3)
          at match
            case Point.Origin            => w.array(1).uint(0)
            case Point.Block(slot, hash) => w.array(3).uint(1).uint(slot.value).bytes(hash)
          w.uint(magic).indefiniteArray
          if which == LedgerPeerKind.All then
            pools.foreach { pool =>
              w.array(2)
              fraction(w, pool.stake.share)
              relays(w, pool.relays.toVector)
            }
          else
            accumulated.foreach { (acc, pool) =>
              w.array(3)
              fraction(w, acc.share)
              fraction(w, pool.stake.share)
              relays(w, pool.relays.toVector)
            }
          w.break
    }
