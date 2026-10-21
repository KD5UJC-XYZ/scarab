// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

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
import xyz.kd5ujc.scarab.consensus.BootstrapState
import xyz.kd5ujc.scarab.consensus.VolatileChain
import xyz.kd5ujc.scarab.ledger.Block
import xyz.kd5ujc.scarab.ledger.EpochGeometry
import xyz.kd5ujc.scarab.ledger.Header
import xyz.kd5ujc.scarab.ledger.{NodeError, ValidationError}
import xyz.kd5ujc.scarab.node.Follower
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, EraHistory, MirrorState}
import xyz.kd5ujc.scarab.ledger.state.MirrorEnv
import xyz.kd5ujc.scarab.node.MirrorRules
import xyz.kd5ujc.scarab.node.RefStateful
import xyz.kd5ujc.scarab.legacy.{FlatNodeState, LedgerState, Mirroring, Snapshot, Snapshots}
import xyz.kd5ujc.scarab.node.mempool.MempoolLedger
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

object ForgedChain:
  val geometry: EpochGeometry =
    EpochGeometry(
      Instant.EPOCH,
      1000,
      epochLength = 1000,
      securityParam = 5,
      activeSlotsCoeff = 0.5
    )
  val config: PraosConfig = PraosConfig(
    geometry,
    slotsPerKesPeriod = 100,
    maxKesEvolutions = 62,
    activeSlotsCoeff = Leader.Fraction.checked(1, 2).toOption.get
  )
  val globals: LedgerGlobals =
    LedgerGlobals(TestLedger.Supply, 1000, Rational(1, 2), network = 0, updateQuorum = 2)
  val epochNonce: Nonce = Nonce.Hash(Blake2b.hash256.hash(Bytes(Vector(7))))

  /** What the mirror reads of this configuration. */
  val mirrorEnv: ConsensusMirrorEnv = ConsensusMirrorEnv(
    MirrorEnv(globals.network, geometry, globals.updateQuorum),
    config.forks,
    EraHistory.ByronParams(
      BigInt(geometry.securityParam) * 10,
      BigInt(20000),
      BigInt(geometry.securityParam)
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

  private def ledgerWith(p: Pool, epoch: BigInt): LedgerState = ledgerWith(Vector(p), epoch)

  private def ledgerWith(pools: Vector[Pool], epoch: BigInt): LedgerState =
    val amount = BigInt(1000000) / pools.size
    val registrations = pools.map { p =>
      val cred = p.stake.credential
      val params = PoolParams(
        p.creds.poolId,
        p.creds.vrfKeyHash,
        0,
        0,
        Rational.zero,
        RewardAccount(0, cred),
        Set.empty,
        Bytes.empty,
        None
      )
      (cred, p.creds.poolId, p.creds.poolId -> params)
    }
    val dist = Snapshot(
      registrations.map((cred, _, _) => cred -> amount).toMap,
      registrations.map((cred, poolId, _) => cred -> poolId).toMap,
      registrations.map((_, _, params) => params).toMap
    )
    TxKit
      .flat(1)
      .copy(
        epoch = epoch,
        poolDistribution = dist,
        snapshots = Snapshots(dist, dist, dist, 0)
      )

  private def nodeState(ledger: LedgerState): FlatNodeState =
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
      ProtocolExtras(ledger.epoch, Nonce.Neutral)
    )
    FlatNodeState(praos, ledger, BootstrapState.initial(lite))

  /** Forge in the given slots (the pool leads about half of them) starting from a ledger in
    * `epoch`, and return the blocks with the state after each.
    */
  def forge(slots: Range, epoch: BigInt = 5, seed: Int = 1): IO[ForgedChain] =
    val p = pool(kesStart = (slots.head / 100).toInt - 1, seed)
    val flatGenesis = nodeState(ledgerWith(p, epoch))
    val env = mirrorEnv
    // the chain the pool forges on is the mirror of the fixture's flat state; the blocks are then
    // applied again, one by one, to give the states the header-rule suites read
    val start = Mirroring
      .toMirror(env, None, flatGenesis)
      .fold(e => throw new AssertionError(e.toString), _.value)
    forgeFrom(start, p, slots, None)

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
    val flatGenesis = nodeState(ledgerWith(Vector(leftPool, rightPool), epoch = 5))
    val start = Mirroring
      .toMirror(mirrorEnv, None, flatGenesis)
      .fold(e => throw new AssertionError(e.toString), _.value)
    for
      prefix <- forgeFrom(start, leftPool, prefixSlots, None)
      left <- forgeFrom(start, leftPool, branchSlots, Some(prefix))
      right <- forgeFrom(start, rightPool, branchSlots, Some(prefix))
      commonLength = prefix.headers.size
      leftLimit = commonLength + geometry.securityParam
      rightLimit = commonLength + (geometry.securityParam - 2).max(1)
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
    val initial = prior.fold(VolatileChain.genesis(geometry.securityParam, genesis)) { chain =>
      chain.headers
        .zip(chain.states)
        .foldLeft(
          VolatileChain.genesis(geometry.securityParam, chain.genesis)
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
                    .flatMap(mempool.sync(_, tip.slot, Set.empty))
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
            IO.pure(true),
            maxTipAge = Some(BigInt(1000000)),
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
