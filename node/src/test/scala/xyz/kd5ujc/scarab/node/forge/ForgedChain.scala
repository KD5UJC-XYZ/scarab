// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import xyz.kd5ujc.scarab.ledger.TestGeometry
import xyz.kd5ujc.scarab.ledger.ChainError
import cats.effect.{IO, Ref}
import cats.mtl.{Handle, Raise, Stateful}
import cats.syntax.all.*
import java.time.Instant
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning
import xyz.kd5ujc.scarab.consensus.Anchor
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.{BootstrapState, ByronState}
import xyz.kd5ujc.scarab.consensus.VolatileChain
import xyz.kd5ujc.scarab.ledger.Block
import xyz.kd5ujc.scarab.ledger.EpochGeometry
import xyz.kd5ujc.scarab.ledger.Header
import xyz.kd5ujc.scarab.ledger.{NodeError, ValidationError}
import xyz.kd5ujc.scarab.node.Follower
import xyz.kd5ujc.scarab.consensus.ConsensusExtras
import xyz.kd5ujc.scarab.consensus.state.{
  ConsensusMirrorEnv,
  EraHistory,
  GenesisMirror,
  MirrorState
}
import xyz.kd5ujc.scarab.ledger.state.MirrorEnv
import xyz.kd5ujc.scarab.node.MirrorRules
import xyz.kd5ujc.scarab.node.RefStateful
import xyz.kd5ujc.scarab.node.mempool.MempoolLedger
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.ledger.Nonce
import xyz.kd5ujc.scarab.consensus.PraosConfig
import xyz.kd5ujc.scarab.consensus.state.{ChainDepState, PraosState, ProtocolExtras, ProtocolState}
import xyz.kd5ujc.scarab.ledger.rules.{Convert, EraNes, StakeDistribution}
import xyz.kd5ujc.scarab.ledger.state.{
  ActiveStake,
  BlockIssuer,
  CompactCoin,
  Conway,
  EpochNo,
  KeyHash,
  NewEpochState,
  NonZero,
  PoolDistr,
  ShelleyBasedEra,
  SnapShot,
  SnapShots,
  Sorted,
  StakePool,
  StakeWithDelegation,
  VocabError,
  WithOrigin,
  Word64
}
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.TestLedger.Key
import xyz.kd5ujc.scarab.node.mempool.{Mempool, MempoolEnv, MempoolEvents, TxKit}
import xyz.kd5ujc.scarab.crypto.vrf.{Draft03Prover, PraosLeaderThreshold as Leader}

/** A chain of blocks with valid Praos headers, forged by a pool with all the stake against the real
  * block rules, for tests of header validation: `states(i)` is the node state after `raws(i)`,
  * `genesis` the state before the first block.
  */
final case class ForgedChain(
    config: PraosConfig,
    globals: LedgerGlobals,
    genesis: MirrorState,
    headers: Vector[Header.Shelley],
    raws: Vector[Bytes],
    states: Vector[MirrorState]
)

object ForgedChain extends ForgedChainSetup(ForgedChainSetup.defaultGeometry)

object ForgedChainSetup:
  /** One-second slots, epochs of 1000 slots, k = 5 and f = 1/2. */
  val defaultGeometry: EpochGeometry =
    EpochGeometry(
      Instant.EPOCH,
      1000,
      epochLength = 1000,
      securityParam = TestGeometry.k(5),
      activeSlotsCoeff = TestGeometry.f("0.5")
    )

/** Forging under `geometry`; [[ForgedChain]] is the default geometry's. */
class ForgedChainSetup(val geometry: EpochGeometry):
  val config: PraosConfig = PraosConfig(
    geometry,
    slotsPerKesPeriod = 100,
    maxKesEvolutions = 62,
    activeSlotsCoeff = Leader.Fraction.checked(1, 2).toOption.get
  )
  val globals: LedgerGlobals =
    LedgerGlobals(
      TestLedger.Supply,
      geometry.epochLength,
      Rational(1, 2),
      network = 0,
      updateQuorum = 2
    )
  val epochNonce: Nonce = Nonce.Hash(Blake2b.hash256.hash(Bytes(Vector(7))))

  /** What the mirror reads of this configuration. */
  val mirrorEnv: ConsensusMirrorEnv = ConsensusMirrorEnv(
    MirrorEnv(globals.network, geometry, globals.updateQuorum),
    config.forks,
    EraHistory.ByronParams(
      geometry.k * 10,
      BigInt(20000),
      geometry.k
    )
  )

  /** The block rules of the forged chain's configuration: no Byron prefix. */
  def rules(using
      Raise[IO, ValidationError],
      Raise[IO, xyz.kd5ujc.scarab.ledger.StorageError]
  ): MirrorRules[IO] =
    MirrorRules[IO](config, globals, None, None, mirrorEnv.byron)

  private def hex(b: Array[Byte]) = Bytes.fromArray(b)

  private final case class Pool(creds: PoolCredentials, stake: Key)

  private def pool(kesStart: Int, seed: Int): Pool =
    val cold = TestLedger.key()
    val kes = Sum6KesSigning.generate(hex(Array.fill(32)(seed.toByte))).toOption.get
    val vrf =
      Draft03Prover.SigningKey.fromSeed(hex(Array.fill(32)((seed + 50).toByte))).toOption.get
    def be8(n: Long) = Array.tabulate[Byte](8)(i => (n >> (8 * (7 - i))).toByte)
    val signable = Bytes.fromArray(kes.verificationKey.toArray ++ be8(0) ++ be8(kesStart))
    val oc = Header.OpCert(kes.verificationKey, 0, kesStart, cold.sign(signable))
    Pool(PoolCredentials(vrf, kes, oc, cold.vkey), TestLedger.key())

  private def ledgerWith(p: Pool, epoch: BigInt): NewEpochState[Conway] =
    ledgerWith(Vector(p), epoch)

  /** The state of one output of the test kit in `epoch`, whose pools share the stake equally in
    * each of the three snapshots and in the distribution of the epoch. The pools are not
    * registered: the leader check reads the distribution only.
    */
  private def ledgerWith(pools: Vector[Pool], epoch: BigInt): NewEpochState[Conway] =
    val amount = BigInt(1000000) / pools.size
    val dist = snapshotOf(pools.map(p => (p.stake.credential, p.creds, amount)))
    val nes = TxKit.native(1)
    nes.copy(
      epoch = vocab(EpochNo.fromBigInt(epoch)),
      es = nes.es.copy(snapshots = SnapShots(dist, dist, dist, nes.es.snapshots.fee)),
      pd = PoolDistr.of(dist)
    )

  private def vocab[A](e: Either[VocabError, A]): A =
    e.fold(v => throw new AssertionError(s"$v"), identity)

  /** The stake snapshot in which each credential delegates its amount to its pool, of which it is
    * the only delegator and the reward account (no pledge, cost, margin or owners).
    */
  def snapshotOf(entries: Vector[(Credential, PoolCredentials, BigInt)]): SnapShot =
    val deposit = vocab(CompactCoin.fromBigInt(0))
    val delegated = entries.map { (cred, creds, amount) =>
      val params =
        PoolParams(
          creds.poolId,
          creds.vrfKeyHash,
          0,
          0,
          Rational.zero,
          RewardAccount(0, cred),
          Set.empty,
          Bytes.empty,
          None
        )
      val staking = vocab(Convert.credential[xyz.kd5ujc.scarab.ledger.state.Staking](cred))
      val id = vocab(KeyHash.fromBytes[StakePool](creds.poolId))
      val stake = vocab(CompactCoin.fromBigInt(amount).flatMap(NonZero.from))
      val pool = vocab(Convert.poolState(params, deposit, Sorted.set(List(staking))))
      (staking -> StakeWithDelegation(stake, id), id -> pool)
    }
    StakeDistribution.snapshot(
      ActiveStake(Sorted.map(delegated.map(_._1))),
      Sorted.map(delegated.map(_._2))
    )

  /** The chain's state at the origin: `ledger` in Conway, its epoch nonce [[epochNonce]]. */
  def genesisOf(env: ConsensusMirrorEnv, ledger: NewEpochState[Conway]): MirrorState =
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
      ProtocolExtras(ledger.epoch.toBigInt, Nonce.Neutral)
    )
    GenesisMirror
      .of(
        env,
        EraNes.InEra(ShelleyBasedEra.SConway, ledger),
        praos.dep,
        ConsensusExtras(BootstrapState.initial(lite), ByronState.initial, praos.extras)
      )
      .fold(e => throw new AssertionError(e.toString), identity)

  /** Forge in the given slots (the pool leads about half of them) starting from a ledger in
    * `epoch`, and return the blocks with the state after each.
    */
  def forge(slots: Range, epoch: BigInt = 5, seed: Int = 1): IO[ForgedChain] =
    val p = pool(kesStart = ((slots.head / 100).toInt - 1).max(0), seed)
    // the blocks are applied again, one by one, to give the states the header-rule suites read
    forgeFrom(genesisOf(mirrorEnv, ledgerWith(p, epoch)), p, slots, None)

  /** Distinct signed forks from one pool and one genesis, for the diffusion tentative-header
    * criterion's repeated issuer/opcert identity at the same height.
    */
  def sameIssuerForks(leftSlots: Range, rightSlots: Range): IO[(ForgedChain, ForgedChain)] =
    val p = pool(kesStart = (leftSlots.head / 100).toInt - 1, seed = 1)
    val start = genesisOf(mirrorEnv, ledgerWith(p, epoch = 5))
    (forgeFrom(start, p, leftSlots, None), forgeFrom(start, p, rightSlots, None)).tupled

  /** Two valid chains with a shared generated prefix and independently forged suffixes. Both pool
    * identities are registered in the same genesis ledger, so either branch can be checked by the
    * real mirror rules. The bounded suffix is useful for chain-selection tests that must switch
    * within the retained `k` window.
    */
  def competingForks(
      prefixSlots: Range,
      branchSlots: Range,
      leftSeed: Int,
      rightSeed: Int
  ): IO[(ForgedChain, ForgedChain)] =
    val kesStart = (prefixSlots.head / 100).toInt - 1
    val leftPool = pool(kesStart, leftSeed)
    val rightPool = pool(kesStart, rightSeed)
    val start = genesisOf(mirrorEnv, ledgerWith(Vector(leftPool, rightPool), epoch = 5))
    for
      prefix <- forgeFrom(start, leftPool, prefixSlots, None)
      left <- forgeFrom(start, leftPool, branchSlots, Some(prefix))
      right <- forgeFrom(start, rightPool, branchSlots, Some(prefix))
      commonLength = prefix.headers.size
      leftLimit = commonLength + geometry.k.toInt
      rightLimit = commonLength + (geometry.k.toInt - 2).max(1)
      boundedLeft = left.copy(
        headers = left.headers.take(leftLimit),
        raws = left.raws.take(leftLimit),
        states = left.states.take(leftLimit)
      )
      boundedRight = right.copy(
        headers = right.headers.take(rightLimit),
        raws = right.raws.take(rightLimit),
        states = right.states.take(rightLimit)
      )
    yield (boundedLeft, boundedRight)

  private def forgeFrom(
      genesis: MirrorState,
      p: Pool,
      slots: Range,
      prior: Option[ForgedChain]
  ): IO[ForgedChain] =
    val initial = prior.fold(VolatileChain.genesis(geometry.k.toInt, genesis)) { chain =>
      chain.headers
        .zip(chain.states)
        .foldLeft(
          VolatileChain.genesis(geometry.k.toInt, chain.genesis)
        ) { case (current, (header, state)) =>
          current.extend(header, state).toOption.get._1
        }
    }
    Handle
      .allow[NodeError] {
        for
          ref <- Ref.of[IO, VolatileChain[MirrorState]](
            initial
          )
          given Stateful[IO, VolatileChain[MirrorState]] = RefStateful(ref)
          rules = MirrorRules[IO](config, globals, None, None, mirrorEnv.byron)
          mempoolBase <- cats.mtl
            .Raise[IO, NodeError]
            .fromEither(
              xyz.kd5ujc.scarab.node.read
                .ConwayView(initial.state, initial.tipRef)
                .traverse(
                  MempoolLedger.tick(
                    _,
                    Some(initial.tipRef.fold(BigInt(slots.head.toLong - 1))(_.slot)),
                    MempoolEnv(globals, geometry)
                  )
                )
                .left
                .map(
                  ChainError
                    .MempoolTick(initial.tipRef.fold(BigInt(slots.head.toLong - 1))(_.slot) + 1, _)
                )
            )
          mempool <- Mempool.create[IO](
            mempoolBase,
            initial.tipRef.fold(slots.head.toLong - 1)(_.slot.toLong),
            MempoolEnv(globals, geometry),
            None,
            MempoolEvents.none[IO]
          )
          raws <- Ref.of[IO, Vector[Bytes]](Vector.empty)
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
              )(using
                  cats.mtl.Raise[IO, xyz.kd5ujc.scarab.ledger.StorageError],
                  cats.mtl.Raise[IO, xyz.kd5ujc.scarab.ledger.ChainError]
              ) = raws.update(_ :+ raw)
              def rolledBack(to: xyz.kd5ujc.scarab.protocol.ChainSync.Point)(using
                  cats.mtl.Raise[IO, xyz.kd5ujc.scarab.ledger.StorageError],
                  cats.mtl.Raise[IO, xyz.kd5ujc.scarab.ledger.ChainError]
              ) = IO.unit),
            onTip = Some(
              Follower.OnTip((c, _) =>
                c.tipRef.traverse_(tip =>
                  cats.mtl
                    .Raise[IO, xyz.kd5ujc.scarab.ledger.NodeError]
                    .fromEither(
                      MempoolLedger
                        .at(c.state, Some(tip), MempoolEnv(globals, geometry))
                        .left
                        .map(xyz.kd5ujc.scarab.ledger.ChainError.MempoolTick(tip.slot + 1, _))
                    )
                    .flatMap(
                      mempool.sync(_, tip.slot, Set.empty, Some(tip.hash))(using
                        summon,
                        Mempool.raisedAt[IO](c.tip)
                      )
                    )
                    .void
                )
              )
            )
          )
          forger <- Forger.create[IO, MirrorState](
            config,
            globals,
            p.creds,
            follower,
            mempool,
            _ => IO.unit,
            forecastWindow = Some(BigInt(1000000)),
            now = Some(IO.pure(Instant.EPOCH)),
            sleep = Some(_ => IO.unit)
          )
          _ <- slots.toList.traverse_(s => forger.step(s))
          appended <- raws.get
        yield
          val decoded =
            appended.map(r => Block.decodeShelley(r).toOption.get.asInstanceOf[Block.Shelley])
          val states = decoded
            .scanLeft(initial.state)((s, b) =>
              MirrorRules
                .applyBlock(
                  s,
                  b.header,
                  appended(decoded.indexOf(b)),
                  config,
                  globals,
                  None,
                  None,
                  mirrorEnv.byron
                )
                .toOption
                .get
            )
            .tail
          ForgedChain(
            config,
            globals,
            prior.fold(genesis)(_.genesis),
            prior.fold(Vector.empty[Header.Shelley])(_.headers) ++ decoded.map(_.header),
            prior.fold(Vector.empty[Bytes])(_.raws) ++ appended,
            prior.fold(Vector.empty[MirrorState])(_.states) ++ states
          )
      }
      .rescue(e => IO.raiseError(new RuntimeException(e.message)))
