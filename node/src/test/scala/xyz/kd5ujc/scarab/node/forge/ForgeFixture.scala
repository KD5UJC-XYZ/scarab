// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.effect.{IO, Ref}
import cats.mtl.{Handle, Stateful}
import java.time.Instant
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning
import xyz.kd5ujc.scarab.consensus.Anchor
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.BootstrapState
import xyz.kd5ujc.scarab.consensus.NodeState
import xyz.kd5ujc.scarab.consensus.VolatileChain
import xyz.kd5ujc.scarab.ledger.EpochGeometry
import xyz.kd5ujc.scarab.ledger.Header
import xyz.kd5ujc.scarab.ledger.NodeError
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, EraHistory, MirrorState}
import xyz.kd5ujc.scarab.ledger.state.{BlocksMade, MirrorEnv, StakePool}
import xyz.kd5ujc.scarab.node.Follower
import xyz.kd5ujc.scarab.node.MirrorRules
import xyz.kd5ujc.scarab.node.MirrorRun
import xyz.kd5ujc.scarab.node.RefStateful
import xyz.kd5ujc.scarab.node.mempool.MempoolLedger
import xyz.kd5ujc.scarab.node.read.ConwayView
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.ledger.Nonce
import xyz.kd5ujc.scarab.consensus.PraosConfig
import xyz.kd5ujc.scarab.consensus.state.{ChainDepState, PraosState, ProtocolExtras, ProtocolState}
import xyz.kd5ujc.scarab.ledger.state.{BlockIssuer, KeyHash, Sorted, WithOrigin, Word64}
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.TestLedger.Key
import xyz.kd5ujc.scarab.node.mempool.{Mempool, MempoolEnv, MempoolEvents, TxKit}
import xyz.kd5ujc.scarab.crypto.vrf.{Draft03Prover, PraosLeaderThreshold as Leader}

/** The pieces of the forger tests: a pool with all the stake, a chain with the real block rules, a
  * forger over them driven by hand or on the test clock.
  */
trait ForgeFixture:
  val geometry = EpochGeometry(
    Instant.EPOCH,
    1000,
    epochLength = 1000,
    securityParam = 5,
    activeSlotsCoeff = 0.5
  )
  val slotsPerKes = BigInt(100)
  val config = PraosConfig(
    geometry,
    slotsPerKes,
    maxKesEvolutions = 62,
    activeSlotsCoeff = Leader.Fraction.checked(1, 2).toOption.get
  )
  val globals =
    LedgerGlobals(TestLedger.Supply, 1000, Rational(1, 2), network = 0, updateQuorum = 2)
  val epochNonce = Nonce.Hash(Blake2b.hash256.hash(Bytes(Vector(7))))

  def hex(b: Array[Byte]) = Bytes.fromArray(b)

  /** A pool with all the stake, its keys and an operational certificate starting at `kesStart`. */
  final case class Pool(creds: PoolCredentials, cold: Key, stake: Key):
    def id: Bytes = creds.poolId

  def pool(kesStart: Int, seed: Int = 1): Pool =
    val cold = TestLedger.key()
    val kes = Sum6KesSigning.generate(hex(Array.fill(32)(seed.toByte))).toOption.get
    val vrf =
      Draft03Prover.SigningKey.fromSeed(hex(Array.fill(32)((seed + 50).toByte))).toOption.get
    def be8(n: Long) = Array.tabulate[Byte](8)(i => (n >> (8 * (7 - i))).toByte)
    val signable = Bytes.fromArray(kes.verificationKey.toArray ++ be8(0) ++ be8(kesStart))
    val oc = Header.OpCert(kes.verificationKey, 0, kesStart, cold.sign(signable))
    Pool(PoolCredentials(vrf, kes, oc, cold.vkey), cold, TestLedger.key())

  def ledgerWith(p: Pool, funds: Int): LedgerState =
    val base = TxKit.flat(funds)
    val cred = p.stake.credential
    val params = PoolParams(
      p.id,
      p.creds.vrfKeyHash,
      0,
      0,
      Rational.zero,
      RewardAccount(0, cred),
      Set.empty,
      Bytes.empty,
      None
    )
    base.copy(
      poolDistribution = Snapshot(
        Map(cred -> BigInt(1000000)),
        Map(cred -> p.id),
        Map(p.id -> params)
      )
    )

  def nodeState(ledger: LedgerState): NodeState =
    val lite = LiteLedger(
      0,
      LiteParams((10, 0), Rational.zero, Nonce.Neutral, 65536, 16384, 1100),
      Map.empty,
      Vector.empty,
      Map.empty,
      Map.empty,
      Map.empty,
      2
    )
    val praos = ProtocolState(
      ChainDepState.Praos(
        PraosState(
          WithOrigin.Origin,
          Sorted.empty[KeyHash[BlockIssuer], Word64],
          Nonce.Neutral,
          Nonce.Neutral,
          epochNonce,
          Nonce.Neutral,
          Nonce.Neutral,
          Nonce.Neutral
        )
      ),
      ProtocolExtras(5, Nonce.Neutral)
    )
    NodeState(praos, ledger, BootstrapState.initial(lite))

  /** What the mirror reads of this configuration. */
  lazy val mirrorEnv: ConsensusMirrorEnv = ConsensusMirrorEnv(
    MirrorEnv(globals.network, geometry, globals.updateQuorum),
    config.forks,
    EraHistory.ByronParams(
      BigInt(geometry.securityParam) * 10,
      BigInt(20000),
      BigInt(geometry.securityParam)
    )
  )

  /** The chain's state at the origin: the node's state through the mirror, once, at the start. */
  def mirrorState(ledger: LedgerState): MirrorState =
    MirrorRun
      .run(mirrorEnv)(_.toMirror(None, nodeState(ledger)))
      .fold(e => throw new AssertionError(e.toString), _.value)

  /** The blocks the pool made in the epoch of the tip, in the state's own count. */
  def blocksMade(c: VolatileChain[MirrorState], id: Bytes): Option[BigInt] =
    ConwayView(c.state, c.tipRef).toOption.flatMap { nes =>
      xyz.kd5ujc.scarab.ledger.state.KeyHash
        .fromBytes[StakePool](id)
        .toOption
        .flatMap(k => BlocksMade.iso.get(nes.bcur).get(k))
        .map(_.toBigInt)
    }

  final class Harness(
      val follower: Follower[IO, MirrorState],
      val forger: Forger[IO, MirrorState],
      val ref: Ref[IO, VolatileChain[MirrorState]],
      val mempool: Mempool[IO],
      val events: Ref[IO, Vector[ForgeEvent]],
      val raws: Ref[IO, Map[Bytes, Bytes]]
  ):
    def chain: IO[VolatileChain[MirrorState]] = ref.get
    def seen: IO[Vector[ForgeEvent]] = events.get

  def harness(
      p: Pool,
      ledger: LedgerState,
      connected: Boolean = true,
      maxTipAge: BigInt = 1000000,
      live: Boolean = false,
      ready: Option[IO[Boolean]] = None,
      offsetMillis: Long = 0,
      source: Option[HotKeySource[IO]] = None
  )(using Handle[IO, NodeError]): IO[Harness] =
    for
      start <- IO.pure(mirrorState(ledger))
      ref <- Ref.of[IO, VolatileChain[MirrorState]](
        VolatileChain.genesis(geometry.securityParam, start)
      )
      given Stateful[IO, VolatileChain[MirrorState]] = RefStateful(ref)
      rules = MirrorRules[IO](config, globals, None, None, mirrorEnv.byron)
      mempool <- Mempool.create[IO](
        MempoolLedger.at(start, None).toOption,
        4999,
        MempoolEnv(globals, geometry),
        1000000,
        MempoolEvents.none[IO]
      )
      raws <- Ref.of[IO, Map[Bytes, Bytes]](Map.empty)
      follower <- Follower.create[IO, MirrorState](
        rules,
        _ => IO.unit,
        geometry,
        persistence = Some(new xyz.kd5ujc.scarab.storage.ChainPersistence[IO, MirrorState]:
          def extended(
              ref: BlockRef,
              raw: Bytes,
              immutable: Vector[BlockRef],
              anchor: xyz.kd5ujc.scarab.consensus.Anchor[MirrorState]
          ) = raws.update(_.updated(ref.hash, raw))
          def rolledBack(to: xyz.kd5ujc.scarab.protocol.ChainSync.Point) = IO.unit),
        onTip = Some((c, _) =>
          mempool.sync(MempoolLedger.at(c.state, c.tipRef), c.tipRef.get.slot, Set.empty).void
        )
      )
      seen <- Ref.of[IO, Vector[ForgeEvent]](Vector.empty)
      forger <- Forger.create[IO, MirrorState](
        config,
        globals,
        p.creds,
        follower,
        mempool,
        e => seen.update(_ :+ e),
        ready.getOrElse(IO.pure(connected)),
        maxTipAge = Some(maxTipAge),
        // `live`: the real loop on the (test) clock, starting at slot 5000; else steps are driven by hand
        now = Some(
          if live then IO.realTimeInstant.map(_.plusSeconds(5000).plusMillis(offsetMillis))
          else IO.pure(Instant.EPOCH)
        ),
        sleep = if live then None else Some(_ => IO.unit),
        source = source
      )
    yield new Harness(follower, forger, ref, mempool, seen, raws)

  def withHandle[A](f: Handle[IO, NodeError] ?=> IO[A]): IO[A] =
    Handle.allow[NodeError](f).rescue(e => IO.raiseError(new RuntimeException(e.message)))

  def forgedIn(h: Harness): IO[Vector[ForgeEvent.Forged]] =
    h.seen.map(_.collect { case f: ForgeEvent.Forged => f })
