// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.mtl.Ask
import java.nio.file.{Files as JFiles, Path}
import munit.CatsEffectSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, EraLedgerState, MirrorState}
import xyz.kd5ujc.scarab.ledger.rules.{Convert, StakeDistribution}
import xyz.kd5ujc.scarab.ledger.state.{
  CompactCoin,
  FlatView,
  KeyHash,
  LedgerState,
  NewEpochState,
  PState,
  Shelley,
  ShelleyBasedEra,
  SnapShot,
  Sorted,
  StakePool,
  VocabError
}
import xyz.kd5ujc.scarab.ledger.{Block, Certificate, Credential, PoolParams, RewardAccount, Tx}
import xyz.kd5ujc.scarab.node.{MirrorSteps, NodeSetup}
import xyz.kd5ujc.scarab.protocol.Era

/** B27: the mainnet pool `ff5181ba` registered in transaction 49be5965c31a60c3 (block at slot
  * 4,495,760, protocol version 2) with a reward account on network 0, which the rules accept before
  * protocol version 5 (B22, `MainnetPoolNetworkSuite`). The node refused every snapshot of a state
  * that held it. The reference keeps a registered pool's account as its credential only
  * (`StakePoolState.spsAccountId`, `mkStakePoolState`, `State/StakePool.hs:138-159, 256-269`; the
  * same in `StakePoolSnapShot`, `State/SnapShots.hs:156-181`) and reads it back on the node's
  * network (`stakePoolStateToStakePoolParams`, `State/StakePool.hs:275-292`); the future parameters
  * of a re-registration are `StakePoolParams`, whose account keeps its network byte.
  *
  * The registration is put into the Shelley state of the eras capture, on mainnet: registered, in
  * the `mark` snapshot and re-registered. The V5 snapshot of that state is written with the full
  * read-back check and comes back as the mirror normalises it.
  */
class MainnetPoolNetworkSnapshotSuite extends CatsEffectSuite:
  private val Mainnet = 1
  private val EpochSlots = BigInt(21600)

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.message), identity)

  /** The registration of pool `ff5181ba`, as the mainnet block holds it. */
  private val registration: PoolParams =
    val block =
      Block.decode(
        hex(JFiles.readString(Path.of("fixtures/mainnet/shelley-pool-network-block.hex")).trim),
        EpochSlots
      ) match
        case Right(b: Block.Shelley) => b
        case other                   => fail(s"unexpected $other")
    block.transactions
      .map(Tx.fromBlock(_, block.era).fold(e => fail(e.message), identity))
      .find(_.body.id.hex.startsWith("49be5965c31a60c3"))
      .flatMap(_.body.certificates.collectFirst { case Certificate.RegisterPool(p) => p })
      .getOrElse(fail("no pool registration in transaction 49be5965c31a60c3"))

  private val fixtures = Path.of("fixtures/eras")
  private val setup: NodeSetup =
    NodeSetup.fromDirectory(fixtures.resolve("genesis")).fold(e => fail(e.message), identity)

  /** The capture's configuration, on mainnet. */
  private val env: ConsensusMirrorEnv =
    val e = setup.mirrorEnv()
    e.copy(ledger = e.ledger.copy(network = Mainnet))

  private given Ask[IO, ConsensusMirrorEnv] = Ask.const(env)
  private val format: SnapshotFormat[IO, MirrorState] = SnapshotV7.format[IO](SnapshotCheck.Full)

  /** The state after the first Shelley block of the capture. */
  private lazy val shelley: StateSnapshot[MirrorState] =
    val raws = JFiles
      .readAllLines(fixtures.resolve("blocks.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(hex)
    val blocks =
      raws.map(
        Block.decode(_, setup.geometry.byron.epochSlots).fold(e => fail(e.message), identity)
      )
    val first = blocks.indexWhere(_.era == Era.Shelley)
    assert(first >= 0, "the capture has no Shelley block")
    val state = (0 to first).foldLeft(setup.mirrorStart) { (s, i) =>
      MirrorSteps
        .block(setup, s, blocks(i).header, raws(i))
        .fold(f => fail(s"block $i rejected by ${f.rule.name}: ${f.reason}"), identity)
    }
    val h = blocks(first).header
    StateSnapshot(
      Some(BlockRef(h.slot, h.hash, h.blockNo, h.isBoundary)),
      state,
      CheckpointBinding.Unbacked
    )

  private def vocab[A](e: Either[VocabError, A]): A = e.fold(v => fail(s"$v"), identity)

  /** The Shelley new-epoch state of a mirror. */
  private def shelleyState(m: MirrorState): NewEpochState[Shelley] =
    m.ext.ledgerState.state match
      case EraLedgerState.InShelleyEra(ShelleyBasedEra.SShelley, state) => state.nes
      case other => fail(s"not a Shelley state: ${other.era}")

  /** The mirror with its Shelley new-epoch state changed by `f`. */
  private def editShelley(m: MirrorState)(
      f: NewEpochState[Shelley] => NewEpochState[Shelley]
  ): MirrorState =
    m.ext.ledgerState.state match
      case EraLedgerState.InShelleyEra(ShelleyBasedEra.SShelley, state) =>
        val next =
          EraLedgerState.InShelleyEra(ShelleyBasedEra.SShelley, state.copy(nes = f(state.nes)))
        m.copy(ext = m.ext.copy(ledgerState = m.ext.ledgerState.withState(next)))
      case other => fail(s"not a Shelley state: ${other.era}")

  private val poolId: KeyHash[StakePool] = vocab(
    KeyHash.fromBytes[StakePool](registration.operator)
  )

  /** The state with the pool registered (with the deposit in force and no delegators), in the
    * `mark` snapshot (no stake) and re-registered.
    */
  private lazy val withPool: StateSnapshot[MirrorState] =
    shelley.copy(state = editShelley(shelley.state) { nes =>
      val ls = nes.es.ls
      val deposit = vocab(
        CompactCoin.fromBigInt(nes.es.ls.utxo.gov.curPParams.poolDeposit)
      )
      val state = vocab(Convert.poolState(registration, deposit, Sorted.emptySet))
      val pools = LedgerState.pState[Shelley].get(ls)
      val registered = pools.copy(
        stakePools = pools.stakePools.updated(poolId, state),
        future = pools.future.updated(poolId, registration)
      )
      val mark = nes.es.snapshots.mark
      val inMark = vocab(
        StakeDistribution.poolSnapshot(mark.activeStake, mark.activeStake.total, state)
      )
      nes.copy(es =
        nes.es.copy(
          ls = LedgerState.pState[Shelley].replace(registered)(ls),
          snapshots = nes.es.snapshots
            .copy(mark = SnapShot.pools.modify(_.updated(poolId, inMark))(mark))
        )
      )
    })

  private lazy val written: IO[Array[Byte]] =
    format.encode(withPool).flatMap(r => IO(r.fold(e => fail(s"refused: $e"), identity)))

  /** The account as `AccountAddress` writes it: `bytes(29)`, header and credential hash. */
  private def account(network: Int): Bytes =
    hex(f"581de$network%x") ++ registration.rewardAccount.credential.hash

  private def occurrences(in: Array[Byte], of: Bytes): Int =
    in.indices.count(i =>
      in.length - i >= of.size && of.value.indices.forall(j => in(i + j) == of.value(j))
    )

  test("the fixture's pool has a key reward account on network 0, the chain is mainnet") {
    assert(registration.operator.hex.startsWith("ff5181ba"), registration.operator.hex)
    assertEquals(registration.rewardAccount.network, 0)
    assert(registration.rewardAccount.credential.isInstanceOf[Credential.Key])
    assertEquals(env.ledger.network, Mainnet)
  }

  test("the state with the pool is written, read back in full, and comes back as normalised") {
    written.flatMap(bytes =>
      format.decode(bytes).map { r =>
        val back = r.fold(e => fail(e.message), identity)
        assertEquals(back.anchor, withPool.anchor)
        val nes = shelleyState(back.state)
        val pools: PState = LedgerState.pState[Shelley].get(nes.es.ls)
        // registered: the credential, read back on the node's network
        assertEquals(
          FlatView.pools(Mainnet, pools.stakePools).get(registration.operator).map(_.params),
          Some(registration.registeredOn(Mainnet))
        )
        // in the snapshot: the credential too, and the pool's parameters without its owners, relays
        // and metadata, as the reference's `StakePoolSnapShot` holds them
        val inMark = nes.es.snapshots.mark.pools.get(poolId)
        assertEquals(
          inMark.map(p =>
            PoolParams(
              registration.operator,
              p.vrf.bytes,
              p.pledge.value,
              p.cost.value,
              p.margin,
              RewardAccount(Mainnet, Convert.back(p.accountId)),
              p.selfDelegatedOwners.iterator.map(_.bytes).toSet,
              Bytes.empty,
              None
            )
          ),
          Some(
            registration
              .registeredOn(Mainnet)
              .copy(owners = Set.empty, relays = Bytes.empty, metadata = None)
          )
        )
        // the future parameters: as registered, network 0
        assertEquals(pools.future.get(poolId), Some(registration))
      }
    )
  }

  test("the Haskell state holds the future parameters' account with network byte 0, once") {
    written.map { bytes =>
      assertEquals(occurrences(bytes, account(0)), 1)
      assertEquals(occurrences(bytes, account(1)), 0)
    }
  }
