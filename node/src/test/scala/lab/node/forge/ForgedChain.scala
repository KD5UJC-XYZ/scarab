// SPDX-License-Identifier: Apache-2.0
package lab.node.forge

import cats.effect.{IO, Ref}
import cats.mtl.{Handle, Stateful}
import cats.syntax.all.*
import java.time.Instant
import lab.Blake2b
import lab.cbor.Bytes
import lab.kes.Sum6KesSigning
import lab.node.*
import lab.node.consensus.{Nonce, PraosConfig, PraosState}
import lab.node.ledger.*
import lab.node.ledger.TestLedger.Key
import lab.node.mempool.{Mempool, MempoolEnv, MempoolEvents, TxKit}
import lab.vrf.{Draft03Prover, PraosLeaderThreshold as Leader}

/** A chain of blocks with valid Praos headers, forged by a pool with all the stake against the real
  * block rules, for tests of header validation: `states(i)` is the node state after `raws(i)`,
  * `genesis` the state before the first block.
  */
final case class ForgedChain(
    config: PraosConfig,
    globals: LedgerGlobals,
    genesis: NodeState,
    headers: Vector[Header.Shelley],
    raws: Vector[Bytes],
    states: Vector[NodeState]
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

  private def ledgerWith(p: Pool, epoch: BigInt): LedgerState =
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
    val dist = Snapshot(
      Map(cred -> BigInt(1000000)),
      Map(cred -> p.creds.poolId),
      Map(p.creds.poolId -> params)
    )
    TxKit
      .ledger(1)
      .copy(
        epoch = epoch,
        poolDistribution = dist,
        snapshots = Snapshots(dist, dist, dist, 0)
      )

  private def nodeState(ledger: LedgerState): NodeState =
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
    val praos = PraosState(
      epoch = ledger.epoch,
      Nonce.Neutral,
      Nonce.Neutral,
      epochNonce,
      Nonce.Neutral,
      Nonce.Neutral,
      Map.empty
    )
    NodeState(praos, ledger, BootstrapState.initial(lite))

  /** Forge in the given slots (the pool leads about half of them) starting from a ledger in
    * `epoch`, and return the blocks with the state after each.
    */
  def forge(slots: Range, epoch: BigInt = 5, seed: Int = 1): IO[ForgedChain] =
    val p = pool(kesStart = (slots.head / 100).toInt - 1, seed)
    val genesis = nodeState(ledgerWith(p, epoch))
    Handle
      .allow[NodeError] {
        for
          ref <- Ref.of[IO, VolatileChain[NodeState]](
            VolatileChain.genesis(geometry.securityParam, genesis)
          )
          given Stateful[IO, VolatileChain[NodeState]] = RefStateful(ref)
          rules = NodeRules[IO](config, globals)
          mempool <- Mempool.create[IO](
            genesis.ledger,
            slots.head.toLong - 1,
            MempoolEnv(globals, geometry),
            1000000,
            MempoolEvents.none[IO]
          )
          raws <- Ref.of[IO, Vector[Bytes]](Vector.empty)
          follower <- Follower.create[IO, NodeState](
            rules,
            _ => IO.unit,
            geometry,
            persistence = Some(new lab.node.storage.ChainPersistence[IO, NodeState]:
              def extended(
                  ref: BlockRef,
                  raw: Bytes,
                  immutable: Vector[BlockRef],
                  anchor: lab.node.Anchor[NodeState]
              ) = raws.update(_ :+ raw)
              def rolledBack(to: lab.network.ChainSync.Point) = IO.unit),
            onTip = Some((c, _) => mempool.sync(c.state.ledger, c.tipRef.get.slot, Set.empty).void)
          )
          forger <- Forger.create[IO](
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
          all <- raws.get
        yield
          val decoded =
            all.map(r => Block.decodeShelley(r).toOption.get.asInstanceOf[Block.Shelley])
          val states = decoded
            .scanLeft(genesis)((s, b) =>
              NodeRules
                .applyBlock(s, b.header, all(decoded.indexOf(b)), config, globals)
                .toOption
                .get
            )
            .tail
          ForgedChain(config, globals, genesis, decoded.map(_.header), all, states)
      }
      .rescue(e => IO.raiseError(new RuntimeException(e.message)))
