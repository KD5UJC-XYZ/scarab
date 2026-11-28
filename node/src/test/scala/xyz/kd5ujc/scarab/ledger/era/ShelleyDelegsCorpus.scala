// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Block, LedgerFailure, Tx as FlatTx, Utxo}
import xyz.kd5ujc.scarab.ledger as node
import xyz.kd5ujc.scarab.ledger.era.EraDelegsRun.{DelegsEra, Outcome}
import xyz.kd5ujc.scarab.ledger.era.babbage.BabbageTx
import xyz.kd5ujc.scarab.ledger.era.rules.DelegsEnv
import xyz.kd5ujc.scarab.ledger.rules.{Convert, Globals, RuleT, Sts, Tick}
import xyz.kd5ujc.scarab.ledger.state.{
  Babbage,
  Build,
  Coin,
  Credential,
  DeltaCoin,
  EpochNo,
  EraTag,
  NewEpochState,
  ShelleyBasedEra,
  SlotNo,
  Staking,
  TxIx,
  UtxoTables
}
import xyz.kd5ujc.scarab.consensus.state.{MirrorState, MirrorStep}
import xyz.kd5ujc.scarab.ledger.rules.EraNes
import xyz.kd5ujc.scarab.node.{MirrorSteps, NodeSetup}
import xyz.kd5ujc.scarab.node.mempool.{LocalSubmission, Rejection}
import xyz.kd5ujc.scarab.node.ntc.ReferenceRefusalSuite
import xyz.kd5ujc.scarab.node.ntc.ReferenceRefusalSuite.{Family, RefusalEra}
import xyz.kd5ujc.scarab.protocol.{Era as ProtocolEra, ReferenceBuild, StateLayout}
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, UtxoCodecs}

/** Shelley's DELEGS on the era types of `E` (cardano-ledger f649f975 `Shelley/Rules/Delegs.hs`)
  * against the flat `ShelleyDelegs` and the reference's recorded verdicts, on the recorded data:
  *
  *   - the era-walking captures (`fixtures/eras` and `fixtures/chain-dep`, each replayed from
  *     genesis by the node's rules), whose blocks of the era hold no certificate (counted): on
  *     every state of the era, the certificates its accounts, pools and genesis delegations make
  *     (each delegated again, registered again, unregistered, each pool registered again and
  *     retired, a genesis key delegated to its own pair and to another's, a MIR certificate from
  *     each pot and a transfer), as one transaction and each alone, the two rules giving the same
  *     failures in order or the same certificate state after;
  *   - the refusal corpora's cases of the era with certificates, each on the reference's state
  *     ticked to its slot: the same judgment both ways; a case refused by DELEG and POOL alone is
  *     the reference's refusal, byte for byte, and one they do not refuse is accepted by DELEGS.
  */
abstract class ShelleyDelegsCorpus[E <: EraTag](
    name: String,
    protocolEra: ProtocolEra,
    refusalEra: RefusalEra
)(using d: DelegsEra[E], sbe: ShelleyBasedEra[E])
    extends FunSuite:
  /** The certificates of a transaction of the era, read by the era's decoder at the 11.1 layout. */
  protected def certificatesOf(envelope: Bytes): Vector[TxCert[E]]

  /** The parameters in force in `nes`, the node's record. */
  protected def pparamsOf(nes: NewEpochState[E]): node.ProtocolParams

  /** The counts the corpora give, asserted so that a loader that loses a case fails. */
  protected val blocks: Map[String, (Int, Int)]
  protected val states: Map[String, Int]
  protected val alone: Map[String, (Int, Int)]
  protected val refusalCases: Int
  protected val refusedAlone: Int

  private def right[A](what: String)(e: Either[?, A]): A = e.fold(x => fail(s"$what: $x"), identity)
  private val t = d.t

  // ---- the captures

  private final case class Capture(
      globals: Globals,
      states: Vector[(SlotNo, NewEpochState[E])],
      blocks: Int,
      transactions: Int,
      certificates: Int
  )

  private def lines(path: Path): Vector[Bytes] =
    Files
      .readAllLines(path)
      .asScala
      .toVector
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(l => right(s"$path")(Bytes.fromHex(l)))

  /** The capture in `dir` replayed from genesis, the state after each block of the era kept. */
  private def capture(dir: String): Capture =
    val setup =
      right(dir)(NodeSetup.fromDirectory(ReferenceBuild.Release11_1)(Path.of(dir, "genesis")))
    val raws = lines(Path.of(dir, "blocks.hex"))
    val (_, kept) =
      raws.zipWithIndex.foldLeft((setup.mirrorStart, Vector.empty[(Block, MirrorState)])) {
        case ((state, acc), (raw, i)) =>
          val block = right(s"$dir block $i")(Block.decode(raw, setup.geometry.byron.epochSlots))
          val next = right(s"$dir block $i")(MirrorSteps.block(setup, state, block.header, raw))
          (next, if block.era == protocolEra then acc :+ (block -> next) else acc)
      }
    val txs = kept.flatMap {
      case (b: Block.Shelley, _) =>
        b.transactions.map(parts => right("transaction")(FlatTx.fromBlock(parts, protocolEra)))
      case _ => Vector.empty
    }
    Capture(
      Globals(setup.globals, setup.geometry),
      kept.map((b, m) =>
        right("slot")(SlotNo.fromBigInt(b.header.slot)) -> (MirrorStep.nesOf(m) match
          case Some(i: EraNes.InEra[e]) =>
            NewEpochState.retype(i.era, sbe)(i.nes).getOrElse(fail(s"$dir: not $name"))
          case other => fail(s"$dir: $other"))
      ),
      kept.size,
      txs.size,
      txs.map(_.body.certificates.size).sum
    )

  private lazy val captures: Map[String, Capture] =
    Vector("fixtures/eras", "fixtures/chain-dep").map(dir => dir -> capture(dir)).toMap

  /** The certificates a state's accounts, pools and genesis delegations make. */
  private def made(globals: Globals, nes: NewEpochState[E], epoch: EpochNo): Vector[TxCert[E]] =
    val cert = d.certState(nes.es.ls.cert)
    val accounts = cert.dState.accounts.states.toVector
    val pools = cert.pState.stakePools.toVector
    val firstPool = pools.headOption.map(_._1)
    val unknown = Credential.KeyHashObj[Staking](
      right("key")(
        xyz.kd5ujc.scarab.ledger.state.KeyHash
          .fromBytes[Staking](Bytes.fromArray(Array.fill(28)(0xee.toByte)))
      )
    )
    val delegations = accounts.flatMap { (c, a) =>
      a.pool.orElse(firstPool).map(p => t.mkDelegStakeTxCert(c, p)).toVector
    } ++ firstPool.map(p => t.mkDelegStakeTxCert(unknown, p))
    val again = accounts.map((c, _) => t.mkRegTxCert(c))
    val unregistered = accounts.map((c, _) => t.mkUnRegTxCert(c))
    val poolCerts = pools.flatMap { (id, p) =>
      val params = Build.stakePoolParams(
        node.PoolParams(
          id.bytes,
          p.vrf.bytes,
          p.pledge.value,
          p.cost.value,
          p.margin,
          node.RewardAccount(globals.networkId.wire, Convert.back(p.accountId)),
          p.owners.map(_.bytes).toSet,
          p.relays,
          None
        )
      )
      Vector(
        t.mkRegPoolTxCert(params),
        t.mkRetirePoolTxCert(id, EpochNo.fromUnsigned(epoch.word.bits + 1))
      )
    }
    val genesis = cert.dState.genDelegs.toVector
    val genesisCerts = genesis.take(2).zip(genesis.drop(1).take(1) ++ genesis.take(1)).flatMap {
      case ((g, own), (_, other)) =>
        Vector(own, other).map(pair =>
          t.mkGenesisDelegTxCert(
            GenesisDelegCert(
              g,
              right("delegate")(xyz.kd5ujc.scarab.ledger.state.KeyHash.fromBytes(pair.delegate)),
              right("vrf")(xyz.kd5ujc.scarab.ledger.state.VRFVerKeyHash.fromBytes(pair.vrf))
            )
          )
        )
    }
    val mirs = accounts.headOption.toVector.flatMap((c, _) =>
      Vector(
        t.mkMirTxCert(
          MIRCert(MIRPot.ReservesMIR, MIRTarget.StakeAddressesMIR(Map(c -> DeltaCoin(1))))
        ),
        t.mkMirTxCert(
          MIRCert(MIRPot.TreasuryMIR, MIRTarget.StakeAddressesMIR(Map(c -> DeltaCoin(2))))
        ),
        t.mkMirTxCert(MIRCert(MIRPot.TreasuryMIR, MIRTarget.SendToOppositePotMIR(Coin(BigInt(3)))))
      )
    )
    delegations ++ poolCerts ++ genesisCerts ++ mirs ++ again ++ unregistered

  private def judge(
      globals: Globals,
      nes: NewEpochState[E],
      slot: SlotNo,
      certs: Vector[TxCert[E]]
  ): Outcome =
    val epoch = right("epoch")(EpochNo.fromBigInt(globals.epochOf(slot.toBigInt)))
    val cert = d.certState(nes.es.ls.cert)
    val b = EraDelegsRun.both[E](
      globals,
      slot,
      epoch,
      TxIx.minBound,
      pparamsOf(nes),
      nes.es.chainAccount,
      cert,
      certs
    )
    assertEquals(b.era, b.flat)
    b.told.foreach(told =>
      assertEquals(
        told,
        EraDelegsRun.expectedTold[E](epoch, cert.pState.stakePools.keySet.map(_.bytes).toSet, certs)
      )
    )
    b.era

  test(s"$name: the captures' blocks of the era hold no certificate") {
    assertEquals(
      captures.view.mapValues(c => (c.blocks, c.transactions)).toMap,
      blocks
    )
    assertEquals(captures.values.map(_.certificates).sum, 0)
  }

  test(
    s"$name: on every state of the captures, the certificates its state makes, together and each alone"
  ) {
    captures.toVector.sortBy(_._1).foreach { (dir, c) =>
      assertEquals(c.states.size, states(dir), dir)
      val tallies = c.states.map { (slot, nes) =>
        val epoch = right("epoch")(EpochNo.fromBigInt(c.globals.epochOf(slot.toBigInt)))
        val certs = made(c.globals, nes, epoch)
        assert(certs.nonEmpty, s"$dir $slot")
        judge(c.globals, nes, slot, certs)
        certs.map(cert => judge(c.globals, nes, slot, Vector(cert)).isInstanceOf[Outcome.Accepted])
      }
      val each = tallies.flatten
      assertEquals((each.count(identity), each.count(!_)), alone(dir), dir)
    }
  }

  // ---- the refusal corpora

  private final case class Recorded(
      label: String,
      families: Vector[Family],
      reason: Bytes,
      id: Bytes,
      globals: Globals,
      ticked: NewEpochState[E],
      slot: SlotNo,
      certificates: Vector[TxCert[E]]
  )

  private lazy val recorded: Vector[Recorded] =
    val loaded = right("the refusal corpus")(
      ReferenceRefusalSuite.load(Path.of("fixtures/reference/refusals"))
    )
    loaded.corpora.filter(_.era == refusalEra).flatMap { c =>
      val setup = right(s"${c.path} genesis")(
        NodeSetup.fromFiles(c.build)(name => c.genesis.get(name).map(_.toArray))
      )
      val globals = Globals(setup.globals, setup.geometry)
      val codecs = right(s"${c.path} layout")(ReferenceRefusalSuite.stateCodecs(c.build))
      c.cases.flatMap { rc =>
        val what = s"${c.path}/${rc.label}"
        val bytes = right(s"$what item")(LocalSubmission.unwrap(rc.item)).raw
        val certs = certificatesOf(bytes)
        Option.when(certs.nonEmpty) {
          val nes = right(s"$what state")(
            codecs.newEpochState[E].decodeBytes(c.states(rc.state), HaskellState.limits)
          )
          val known = right(s"$what UTxO")(UtxoCodecs.utxo.decodeBytes(c.utxos(rc.utxo)))
          val funded = NewEpochState
            .ledger[E]
            .modify(ls => ls.copy[E](utxo = ls.utxo.copy(utxo = UtxoTables(Utxo.fromMap(known)))))(
              nes
            )
          val slot = right("slot")(SlotNo.fromBigInt(rc.tipSlot + 1))
          val ticked = right(s"$what tick")(Sts(Tick.sts[E])(globals, (), funded, slot))
          val flat = right(s"$what flat")(FlatTx.fromEnvelope(bytes, protocolEra))
          assertEquals(flat.body.certificates.size, certs.size, what)
          Recorded(what, rc.families, rc.reason, flat.body.id, globals, ticked, slot, certs)
        }
      }
    }

  private val certificateFamilies: Set[Family] = Set(Family.DELEGS, Family.DELEG, Family.POOL)

  test(
    s"$name: the refusal corpora's cases with certificates: the same judgment both ways, the reference's verdict"
  ) {
    assertEquals(recorded.size, refusalCases)
    recorded.foreach { r =>
      val outcome = judge(r.globals, r.ticked, r.slot, r.certificates)
      val refusedHere = r.families.count(certificateFamilies)
      outcome match
        case Outcome.Refused(fs) => assertEquals(fs.size, refusedHere, r.label)
        case Outcome.Accepted(_) => assertEquals(refusedHere, 0, r.label)
    }
  }

  test(
    s"$name: the cases refused by DELEG and POOL alone are the reference's refusals, byte for byte"
  ) {
    val only = recorded.filter(_.families.forall(certificateFamilies))
    assertEquals(only.size, refusedAlone)
    only.foreach { r =>
      val epoch = right("epoch")(EpochNo.fromBigInt(r.globals.epochOf(r.slot.toBigInt)))
      val env = DelegsEnv[E](
        r.slot,
        epoch,
        TxIx.minBound,
        d.pparams(pparamsOf(r.ticked)),
        d.tx(),
        r.ticked.es.chainAccount
      )
      // one level below LEDGER, which reports them
      val failures = EraDelegsRun.era[E](
        r.globals,
        env,
        r.ticked.es.ls.cert,
        r.certificates,
        RuleT.Policy.Accumulate,
        depth = 1
      ) match
        case Right(Left(fs)) => fs.map(EraDelegsRun.flatOf[E])
        case other           => fail(s"${r.label}: $other")
      assertEquals(
        LocalSubmission.reasonItem(
          Rejection.Ledger(LedgerFailure.Rejected(r.id, failures)),
          Some(protocolEra)
        ),
        Right(r.reason),
        r.label
      )
    }
  }

/** Shelley's DELEGS on Babbage's era types against the flat rule and the reference's verdicts. */
class BabbageDelegsCorpusSuite
    extends ShelleyDelegsCorpus[Babbage]("Babbage", ProtocolEra.Babbage, RefusalEra.Babbage)(using
      DelegsEra.babbage,
      ShelleyBasedEra.SBabbage
    ):
  private val t = EraTx.babbage

  protected def certificatesOf(envelope: Bytes): Vector[TxCert[Babbage]] =
    val tx = BabbageTx
      .fromEnvelope(envelope, StateLayout.Node11_1)
      .fold(e => fail(s"Babbage's decoder: $e"), identity)
    t.bodyOf.certs[TxLevel.Top](t.body[TxLevel.Top](tx)(using t.bodyOf.top))(using t.bodyOf.top)

  protected def pparamsOf(nes: NewEpochState[Babbage]): node.ProtocolParams =
    nes.es.ls.utxo.gov.curPParams

  protected val blocks: Map[String, (Int, Int)] =
    Map("fixtures/eras" -> (188, 2), "fixtures/chain-dep" -> (196, 2))
  protected val states: Map[String, Int] = Map("fixtures/eras" -> 188, "fixtures/chain-dep" -> 196)
  protected val alone: Map[String, (Int, Int)] =
    Map("fixtures/eras" -> (2557, 1767), "fixtures/chain-dep" -> (2645, 1863))
  protected val refusalCases: Int = 9
  protected val refusedAlone: Int = 7
