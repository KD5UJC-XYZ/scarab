// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import xyz.kd5ujc.scarab.ledger.TestGeometry
import cats.effect.{IO, Ref}
import cats.mtl.{Handle, Raise, Stateful}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import java.time.Instant
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning
import xyz.kd5ujc.scarab.consensus.Anchor
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.VolatileChain
import xyz.kd5ujc.scarab.ledger.EpochGeometry
import xyz.kd5ujc.scarab.ledger.Header
import xyz.kd5ujc.scarab.ledger.NodeError
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, EraHistory, MirrorState}
import xyz.kd5ujc.scarab.ledger.state.{
  BlocksMade,
  Conway,
  MirrorEnv,
  NewEpochState,
  PoolDistr,
  StakePool
}
import xyz.kd5ujc.scarab.node.Follower
import xyz.kd5ujc.scarab.node.MirrorRules
import xyz.kd5ujc.scarab.node.RefStateful
import xyz.kd5ujc.scarab.node.mempool.MempoolLedger
import xyz.kd5ujc.scarab.node.read.ConwayView
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.node.read.TxClock
import xyz.kd5ujc.scarab.ledger.Nonce
import xyz.kd5ujc.scarab.consensus.PraosConfig
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
    securityParam = TestGeometry.k(5),
    activeSlotsCoeff = TestGeometry.f("0.5")
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

  /** The state of `funds` outputs of the test kit in which the pool has all the stake of the
    * epoch's distribution; it is not registered and is in no snapshot.
    */
  def ledgerWith(p: Pool, funds: Int): NewEpochState[Conway] =
    TxKit
      .native(funds)
      .copy(pd =
        PoolDistr.of(
          ForgedChain.snapshotOf(Vector((p.stake.credential, p.creds, BigInt(1000000))))
        )
      )

  /** The transactions' time translation, placed by this configuration's era history. */
  given TxClock[MirrorState] = TxClock.of(geometry, mirrorEnv.byron, config.forks)

  /** What the mirror reads of this configuration. */
  lazy val mirrorEnv: ConsensusMirrorEnv = ConsensusMirrorEnv(
    MirrorEnv(globals.network, geometry, globals.updateQuorum),
    config.forks,
    EraHistory.ByronParams(
      geometry.k * 10,
      BigInt(20000),
      geometry.k
    )
  )

  /** The chain's state at the origin, with `ledger` as its Conway state. */
  def mirrorState(ledger: NewEpochState[Conway]): MirrorState =
    ForgedChain.genesisOf(mirrorEnv, ledger)

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
      ledger: NewEpochState[Conway],
      forecastWindow: BigInt = 1000000,
      live: Boolean = false,
      offsetMillis: Long = 0,
      source: Option[HotKeySource[IO]] = None,
      ruleFailure: Option[BlockFailure] = None,
      verdicts: xyz.kd5ujc.scarab.ledger.Verdicts = xyz.kd5ujc.scarab.ledger.Verdicts.Direct,
      chainFailure: Option[ChainError] = None,
      storageFailure: Option[StorageError] = None,
      clock: Option[IO[Instant]] = None,
      unbounded: Boolean = false
  )(using Handle[IO, NodeError]): IO[Harness] =
    for
      start <- IO.pure(mirrorState(ledger))
      ref <- Ref.of[IO, VolatileChain[MirrorState]](
        VolatileChain.genesis(geometry.k.toInt, start)
      )
      given Stateful[IO, VolatileChain[MirrorState]] = RefStateful(ref)
      realRules = MirrorRules[IO](
        config.copy(verdicts = verdicts),
        globals,
        None,
        None,
        mirrorEnv.byron
      )
      rules = new xyz.kd5ujc.scarab.consensus.BlockRules[IO, MirrorState]:
        def apply(state: MirrorState, header: Header, raw: Bytes)(using
            Raise[IO, ValidationError],
            Raise[IO, StorageError],
            Raise[IO, ChainError]
        ): IO[MirrorState] =
          chainFailure.fold(
            ruleFailure.fold(realRules(state, header, raw))(failure =>
              ValidationError(header.point, failure).raise[IO, MirrorState]
            )
          )(_.raise[IO, MirrorState])
      mempoolBase <- cats.mtl
        .Raise[IO, NodeError]
        .fromEither(
          xyz.kd5ujc.scarab.node.read
            .ConwayView(start, None)
            .traverse(MempoolLedger.tick(_, Some(BigInt(4999)), MempoolEnv(globals, geometry)))
            .left
            .map(ChainError.MempoolTick(BigInt(4999) + 1, _))
        )
      mempool <- Mempool.create[IO](
        mempoolBase,
        4999,
        MempoolEnv(globals, geometry),
        None,
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
          )(using Raise[IO, StorageError], Raise[IO, ChainError]) =
            storageFailure.fold(raws.update(_.updated(ref.hash, raw)))(_.raise[IO, Unit])
          def rolledBack(to: xyz.kd5ujc.scarab.protocol.ChainSync.Point)(using
              Raise[IO, StorageError],
              Raise[IO, ChainError]
          ) = IO.unit),
        onTip = Some(
          Follower.OnTip((c, _) =>
            cats.mtl
              .Raise[IO, NodeError]
              .fromEither(
                MempoolLedger
                  .at(c.state, c.tipRef, MempoolEnv(globals, geometry))
                  .left
                  .map(ChainError.MempoolTick(c.tipRef.fold(BigInt(0))(_.slot + 1), _))
              )
              .flatMap(
                mempool.sync(_, c.tipRef.fold(BigInt(0))(_.slot), Set.empty, c.tipRef.map(_.hash))
              )
              .void
          )
        )
      )
      seen <- Ref.of[IO, Vector[ForgeEvent]](Vector.empty)
      // `live` drives the real loop on the test clock from slot 5000; otherwise steps are manual.
      forger <- Forger.create[IO, MirrorState](
        config,
        globals,
        p.creds,
        follower,
        mempool,
        e => seen.update(_ :+ e),
        forecastWindow = Some(forecastWindow),
        now = clock.orElse(
          Some(
            if live then IO.realTimeInstant.map(_.plusSeconds(5000).plusMillis(offsetMillis))
            else IO.pure(Instant.EPOCH)
          )
        ),
        sleep = if live then None else Some(_ => IO.unit),
        // a live loop over a chain left at genesis while the clock is thousands of slots on: the
        // era history at that tip would not translate the slot, so such tests lift the horizon
        horizon = Option.when(unbounded)((_, _) => true),
        source = source
      )
    yield new Harness(follower, forger, ref, mempool, seen, raws)

  def withHandle[A](f: Handle[IO, NodeError] ?=> IO[A]): IO[A] =
    Handle.allow[NodeError](f).rescue(e => IO.raiseError(new RuntimeException(e.message)))

  def currentHot(keys: HotKeys[IO]): IO[PoolCredentials] =
    Forger
      .scoped(keys.current)
      .flatMap(
        _.fold(
          fatal => IO.raiseError(new AssertionError(fatal.message)),
          IO.pure
        )
      )

  def forgedIn(h: Harness): IO[Vector[ForgeEvent.Forged]] =
    h.seen.map(_.collect { case f: ForgeEvent.Forged => f })

  /** The loop's traces among the events, in order. */
  def tracesIn(h: Harness): IO[Vector[ForgeTrace]] =
    h.seen.map(_.collect { case ForgeEvent.Loop(t) => t })

  /** The names of the loop's traces of `slot`, in order: the constructors of `TraceForgeEvent`. */
  def namesIn(h: Harness, slot: BigInt): IO[Vector[String]] =
    tracesIn(h).map(_.filter(_.slot == slot).map(_.productPrefix))
