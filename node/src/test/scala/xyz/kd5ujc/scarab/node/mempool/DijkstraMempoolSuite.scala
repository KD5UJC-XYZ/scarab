// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.data.{NonEmptyChain, NonEmptyVector}
import cats.effect.IO
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import io.circe.parser
import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Value}
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.state.{MirrorState, MirrorStep}
import xyz.kd5ujc.scarab.ledger.{
  Block,
  ConwayGenesis,
  Credential,
  GovActionId,
  RewardAccount,
  ForgeError,
  ForgeProtocolProblem,
  Header,
  LeiosBlock,
  NodeError,
  ProtocolParams,
  StorageError
}
import xyz.kd5ujc.scarab.ledger.{TxIn, TxOut}
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraTxIn as FlatTxIn
import xyz.kd5ujc.scarab.ledger.era.{
  BothLevels,
  ConwayGovState as EraConwayGovState,
  EraTx,
  GovStateRecord,
  Proposals as EraProposals,
  IsPhase2Valid,
  PParams,
  StoredTxOut,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  ConwayUtxosPredFailure,
  DijkstraLedgerPredFailure,
  DijkstraMempoolPredFailure,
  DijkstraUtxoPredFailure,
  DijkstraUtxowPredFailure,
  TagMismatchDescription
}
import xyz.kd5ujc.scarab.ledger.rules.{EraNes, Globals, Hfc}
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayProposals
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, NewEpochState, ShelleyBasedEra, SlotNo}
import xyz.kd5ujc.scarab.ledger.era.Mismatch
import xyz.kd5ujc.scarab.storage.haskell.state.{LeiosStateCodecs, Versions}
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.TxMeasure
import xyz.kd5ujc.scarab.network.LocalTxSubmissionServer
import xyz.kd5ujc.scarab.node.assertions.Assertions
import xyz.kd5ujc.scarab.node.forge.{BlockBuilder, DijkstraSnapshots, ForgedLeiosEb}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.node.read.ConwayView
import xyz.kd5ujc.scarab.node.{MirrorDijkstra, MirrorRules, NodeSetup}
import xyz.kd5ujc.scarab.plutus.{ExUnits, PlutusBackend}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.protocol.{Era, LeiosFetch, ReferenceBuild}

/** The pool's Dijkstra ledger under the Leios build (#54): the devnet's recorded Dijkstra
  * transaction (`fixtures/dijkstra-devnet/transaction.json`, the transaction of its block 37)
  * admitted against the state the reference's chain had before that block, the reference's refusals
  * of a duplicate and of an altered transaction, the measures' limits and the capacity at their
  * boundaries, `guardLeiosWireLimit`, the release builds' pool, revalidation at the next tip, the
  * forger's snapshot of the pool through the node's [[DijkstraSnapshots.ofPool]], and the
  * transaction as LocalTxSubmission, TxSubmission2 and LocalTxMonitor take and show it.
  *
  * The public testnet's state (`fixtures/leios/state/testnet-2168639`) is not used: its UTxO set is
  * not published, so none of its transactions' inputs are in it.
  */
class DijkstraMempoolSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  private given Assertions[IO] = Assertions.noop[IO]
  private given Raise[IO, StorageError] with
    def functor = cats.Functor[IO]
    def raise[E <: StorageError, A](error: E): IO[A] = IO(fail(error.message))

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.message), identity)

  private val devnet = Path.of("fixtures", "dijkstra-devnet")

  private lazy val setup: NodeSetup =
    NodeSetup
      .fromDirectory(ReferenceBuild.Leios)(devnet.resolve("genesis"))
      .fold(e => fail(e.message), identity)

  private def geometry = setup.praos.geometry

  private def transition = setup.praos.forks.transition(geometry.k)

  /** The devnet chain up to its block 37, which holds its one Dijkstra transaction. */
  private lazy val chain: Vector[(Header, Bytes)] =
    Files
      .readAllLines(devnet.resolve("blocks.tsv"))
      .asScala
      .toVector
      .drop(1)
      .filter(_.nonEmpty)
      .map(_.split('\t'))
      .take(38)
      .map { r =>
        val raw = hex(r(4))
        Block.decode(raw, geometry.byron.epochSlots).fold(e => fail(e.message), _.header) -> raw
      }

  /** The states after each block, the start first: `states(i)` is the state before block `i`. */
  private lazy val states: IO[Vector[MirrorState]] =
    val rules = new MirrorRules[IO](
      setup.praos,
      setup.globals,
      setup.byronRules,
      setup.handOver,
      setup.byronHistory,
      PlutusBackend.default
    )
    chain.foldLeftM(Vector(setup.mirrorStart)) { case (applied, (header, raw)) =>
      Handle
        .allow[NodeError](rules(applied.last, header, raw).map(applied :+ _))
        .rescue(e => IO(fail(s"block ${applied.size - 1}: ${e.message}")))
    }

  private def tipOf(i: Int): BlockRef =
    val h = chain(i)._1
    BlockRef(h.slot, h.hash, h.blockNo)

  /** The transaction the devnet's wallet submitted, in its mempool form. */
  private lazy val submitted: Bytes =
    val json = parser.parse(Files.readString(devnet.resolve("transaction.json")))
    hex(json.flatMap(_.hcursor.get[String]("cborHex")).fold(e => fail(e.getMessage), identity))

  /** Block 37's transaction, as the block holds it. */
  private lazy val inBlock =
    LeiosBlock.decode(chain(37)._2).fold(e => fail(e.message), _.transactions) match
      case Vector(t) => t
      case other     => fail(s"not one transaction: ${other.size}")

  private def envOf(state: MirrorState, tip: BlockRef): MempoolEnv =
    MempoolEnv(
      setup.globals,
      geometry,
      PlutusBackend.default.evaluator,
      PlutusBackend.default.decoder,
      clock = Some(era => setup.txClock.at(state, era, tip.slot + 1))
    )

  /** The pool's ledger of the chain whose tip is block `i - 1`, of `states(i)`. */
  private def ledgerOf(state: MirrorState, tip: BlockRef): DijkstraLedger =
    PoolLedger.at(state, Some(tip), envOf(state, tip), transition) match
      case Right(Right(d: DijkstraLedger)) => d
      case other                           => fail(s"not a Dijkstra ledger: $other")

  private def nesOf(state: MirrorState): NewEpochState[Dijkstra] =
    MirrorStep
      .nesOf(state)
      .flatMap(MirrorDijkstra.inDijkstra)
      .getOrElse(fail("not in Dijkstra"))

  /** The ledger of `state` 's tip ticked to the slot after it, its parameters in force changed by
    * `f`, under `build`.
    */
  private def withParams(state: MirrorState, tip: BlockRef, build: ReferenceBuild)(
      f: ProtocolParams => ProtocolParams
  ) =
    // the era's parameters changed through the node's flat record of them
    def changedParams(pp: PParams[Dijkstra]): PParams[Dijkstra] =
      GovStateRecord.dijkstra
        .pparams(f(GovStateRecord.view(pp)))
        .fold(e => fail(s"the changed parameters: $e"), identity)
    withGov(state, tip, build)(gov =>
      gov.copy(
        cgsCurPParams = changedParams(gov.cgsCurPParams),
        cgsPrevPParams = changedParams(gov.cgsPrevPParams)
      )
    )

  /** The ledger of `state` 's tip ticked to the slot after it, its governance state changed by `g`,
    * under `build`.
    */
  private def withGov(state: MirrorState, tip: BlockRef, build: ReferenceBuild)(
      g: EraConwayGovState[Dijkstra] => EraConwayGovState[Dijkstra]
  ) =
    val nes = nesOf(state)
    val ls = nes.es.ls
    val changed = nes.copy[Dijkstra](es =
      nes.es
        .copy[Dijkstra](ls = ls.copy[Dijkstra](utxo = ls.utxo.copy[Dijkstra](gov = g(ls.utxo.gov))))
    )
    PoolLedger.ticked(
      EraNes.InEra(ShelleyBasedEra.SDijkstra, changed),
      MirrorStep.afterVotingOf(state),
      Some(tip.slot),
      envOf(state, tip).copy(globals = setup.globals.copy(referenceBuild = build)),
      transition
    )

  private def pool(ledger: PoolLedger, tip: BlockRef, capacity: Option[Long] = None) =
    MempoolState.empty(Right(ledger), tip.slot, capacity.map(TxKit.word32), Some(tip.hash))

  private val dijkstra = Submission(Era.Dijkstra.index, _: Bytes)

  private def refusedOf(r: Rejection) = r match
    case Rejection.DijkstraRefused(_, failures) => failures.toChain.toVector
    case other                                  => fail(s"not Dijkstra's refusal: $other")

  private def refusals(r: Either[Rejection, (MempoolState, Entry)]) = r match
    case Left(Rejection.DijkstraRefused(_, failures)) => failures.toChain.toVector
    case other                                        => fail(s"not Dijkstra's refusal: $other")

  test("the devnet's Dijkstra transaction is admitted on the state before its block, as recorded") {
    states.map { s =>
      val tip = tipOf(36)
      val env = envOf(s(37), tip)
      val (held, entry) = pool(ledgerOf(s(37), tip), tip)
        .add(dijkstra(submitted), env, Source.Local)
        .fold(r => fail(r.message), identity)
      assertEquals(entry.era, Era.Dijkstra)
      assertEquals(entry.id, inBlock.id)
      // held and relayed in its mempool form, `[body, wits, aux / null]`
      assertEquals(entry.raw, submitted)
      // `txMeasure`: its size plus the per-transaction overhead, no units, no reference script
      assertEquals(Capacity.measure(entry), TxMeasure(submitted.size + 4L, 0, 0, 0L))
      assertEquals(held.used, Capacity.measure(entry))
      // LocalTxMonitor lists it in Dijkstra
      assertEquals(
        LocalMonitor.snapshot(held).txs.map(_.tx),
        Vector(LocalMonitor.wrapTx(Era.Dijkstra, submitted))
      )
      // a peer's is applied with its flag valid (`DoNotIntervene`), and is admitted the same
      val (_, remote) = pool(ledgerOf(s(37), tip), tip)
        .add(dijkstra(submitted), env, Source.Remote)
        .fold(r => fail(r.message), identity)
      remote.held match
        case Held.InDijkstra(h) => assertEquals(h.read.isValid, IsPhase2Valid.Phase2Valid)
        case other              => fail(s"not a Dijkstra transaction: $other")
    }
  }

  test("admitted on the last Conway tip, ticked into Dijkstra by the hard-fork combinator") {
    states.map { s =>
      // block 32's state: its next slot is Dijkstra's, so its pool is Dijkstra's, of a Conway tip
      val tip = tipOf(32)
      assertEquals(MirrorStep.nesOf(s(33)).map(_.toEra), Some(Era.Conway))
      val (_, entry) = pool(ledgerOf(s(33), tip), tip)
        .add(dijkstra(submitted), envOf(s(33), tip), Source.Local)
        .fold(r => fail(r.message), identity)
      assertEquals((entry.era, entry.id), (Era.Dijkstra, inBlock.id))
    }
  }

  test(
    "a duplicate, and the transaction once its block is applied, are refused: all inputs spent"
  ) {
    states.map { s =>
      val tip = tipOf(36)
      val env = envOf(s(37), tip)
      val (held, _) = pool(ledgerOf(s(37), tip), tip)
        .add(dijkstra(submitted), env, Source.Local)
        .fold(r => fail(r.message), identity)
      // MEMPOOL's own check, the only failure (`whenFailureFreeDefault`)
      assertEquals(
        refusals(held.add(dijkstra(submitted), env, Source.Local)),
        Vector(DijkstraMempoolPredFailure.AllInputsAreSpent[Dijkstra]())
      )
      val after = tipOf(37)
      assertEquals(
        refusals(
          pool(ledgerOf(s(38), after), after)
            .add(dijkstra(submitted), envOf(s(38), after), Source.Local)
        ),
        Vector(DijkstraMempoolPredFailure.AllInputsAreSpent[Dijkstra]())
      )
    }
  }

  test("an altered transaction is refused by LEDGER: the witness and the balance, in UTXOW") {
    states.map { s =>
      val tip = tipOf(36)
      // the fee one lovelace more: the body's hash and its balance change, the witness does not
      val fee = hex("021a0002990d")
      val at = submitted.value.indexOfSlice(fee.value)
      assert(at > 0, "the fee field")
      val altered = submitted.take(at) ++ hex("021a0002990e") ++ submitted.drop(at + fee.size)
      val failures = refusals(
        pool(ledgerOf(s(37), tip), tip).add(dijkstra(altered), envOf(s(37), tip), Source.Local)
      )
      def utxow(f: DijkstraMempoolPredFailure[Dijkstra]) = f match
        case DijkstraMempoolPredFailure.LedgerFailure(
              DijkstraLedgerPredFailure.DijkstraUtxowFailure(u)
            ) =>
          Some(u)
        case _ => None
      val inUtxow = failures.flatMap(utxow)
      assertEquals(inUtxow.size, failures.size, s"$failures")
      assert(
        inUtxow.exists {
          case DijkstraUtxowPredFailure.InvalidWitnessesUTXOW(_) => true
          case _                                                 => false
        },
        s"$failures"
      )
      assert(
        inUtxow.exists {
          case DijkstraUtxowPredFailure.UtxoFailure(
                DijkstraUtxoPredFailure.ValueNotConservedUTxO(_)
              ) =>
            true
          case _ => false
        },
        s"$failures"
      )
    }
  }

  test("maxTxSize at its boundary: the size admitted, a byte less Dijkstra's MaxTxSizeUTxO alone") {
    states.map { s =>
      val tip = tipOf(36)
      val env = envOf(s(37), tip)
      val size = BigInt(submitted.size)
      def at(limit: BigInt) =
        withParams(s(37), tip, ReferenceBuild.Leios)(_.copy(maxTxSize = limit)) match
          case Right(Right(l)) => pool(l, tip).add(dijkstra(submitted), env, Source.Local)
          case other           => fail(s"no ledger: $other")
      assert(at(size).isRight, s"${at(size)}")
      assertEquals(
        refusals(at(size - 1)),
        Vector(
          InjectMempoolFailureOf.utxo(
            DijkstraUtxoPredFailure.MaxTxSizeUTxO[Dijkstra](Mismatch(size, size - 1))
          )
        )
      )
    }
  }

  /** The public testnet's chain transactions with the outputs their inputs spend
    * (`fixtures/reference/leios-testnet-chain-contexts`): each in its mempool form, its block
    * form's parts with the flag left out, and those outputs in the node's record.
    */
  private lazy val testnet: Vector[(DijkstraRead, Vector[(TxIn, TxOut)])] =
    import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraCorpus, DijkstraTxOut}
    DijkstraCorpus.chainContextCases.map { c =>
      val parts =
        DijkstraCorpus.node(DijkstraCorpus.hex(DijkstraCorpus.str(c, "bytes"))).value match
          case Value.Arr(items) => items.take(3).map(_.original)
          case other            => fail(s"not a transaction: $other")
      val read = DijkstraRead
        .submitted(parts.foldLeft(Bytes.fromArray(Array(0x83.toByte)))(_ ++ _))
        .fold(e => fail(e.toString), identity)
      val outputs = c.hcursor
        .get[Vector[(String, String)]]("utxo")
        .fold(e => fail(e.message), identity)
        .map { (i, o) =>
          val in = FlatTxIn
            .decode(DijkstraCorpus.node(DijkstraCorpus.hex(i)))
            .fold(e => fail(e.toString), x => TxIn(x.id, BigInt(x.index)))
          val out = DijkstraTxOut
            .decode(DijkstraCorpus.node(DijkstraCorpus.hex(o)))
            .fold(e => fail(e.toString), StoredTxOut.dijkstra.toStored)
          in -> out
        }
      (read, outputs)
    }

  private def unitsOf(read: DijkstraRead): ExUnits =
    EraTx.dijkstra.getTotalExUnits[TxLevel.Top](read.tx)(using BothLevels.Top)

  test("maxTxExUnits at its boundary, and both limits refused together, the size first") {
    states.map { s =>
      val tip = tipOf(36)
      val read = testnet.map(_._1).find(r => unitsOf(r).mem > 0).getOrElse(fail("no redeemer"))
      val units = unitsOf(read)
      val size = BigInt(read.size)
      def at(maxTxSize: BigInt, maxTxExUnits: ExUnits) =
        withParams(s(37), tip, ReferenceBuild.Leios)(
          _.copy(maxTxSize = maxTxSize, maxTxExUnits = maxTxExUnits)
        ) match
          case Right(Right(l: DijkstraLedger)) => l.phase1(read)
          case other                           => fail(s"no Dijkstra ledger: $other")
      assertEquals(at(size, units), Right(TxMeasure(read.size + 4L, units.mem, units.steps, 0L)))
      val under = ExUnits(units.mem - 1, units.steps)
      val tooMuch = InjectMempoolFailureOf.utxo(
        DijkstraUtxoPredFailure.ExUnitsTooBigUTxO[Dijkstra](Mismatch(units, under))
      )
      assertEquals(at(size, under).left.map(refusedOf), Left(Vector(tooMuch)))
      assertEquals(
        at(size - 1, under).left.map(refusedOf),
        Left(
          Vector(
            InjectMempoolFailureOf.utxo(
              DijkstraUtxoPredFailure.MaxTxSizeUTxO[Dijkstra](Mismatch(size, size - 1))
            ),
            tooMuch
          )
        )
      )
    }
  }

  test(
    "maxRefScriptSizePerTx at its boundary, over the reference scripts of the outputs it reads"
  ) {
    states.map { s =>
      val tip = tipOf(36)
      def at(limit: BigInt, read: DijkstraRead, outputs: Vector[(TxIn, TxOut)]) =
        withParams(s(37), tip, ReferenceBuild.Leios)(
          _.copy(maxRefScriptSizePerTx = limit)
        ) match
          case Right(Right(l: DijkstraLedger)) => l.withUtxo(l.utxo ++ outputs).phase2(read)
          case other                           => fail(s"no Dijkstra ledger: $other")
      val (read, outputs, size) = testnet.iterator
        .map((r, o) => (r, o, at(BigInt(1) << 24, r, o).fold(e => fail(e.message), identity)))
        .find(_._3 > 0)
        .getOrElse(fail("no transaction reads a reference script"))
      assertEquals(at(BigInt(size), read, outputs), Right(size))
      assertEquals(
        at(BigInt(size - 1), read, outputs).left.map(refusedOf),
        Left(
          Vector(
            DijkstraMempoolPredFailure.LedgerFailure[Dijkstra](
              DijkstraLedgerPredFailure.DijkstraTxRefScriptsSizeTooBig[Dijkstra](
                Mismatch(size, size - 1)
              )
            )
          )
        )
      )
    }
  }

  test(
    "the capacity is a ranking block and an endorser block's reservation; a byte short is full"
  ) {
    states.map { s =>
      val tip = tipOf(36)
      val env = envOf(s(37), tip)
      val ledger = ledgerOf(s(37), tip)
      // `computeMempoolCapacity`: two units without an override
      assertEquals(
        pool(ledger, tip).capacity,
        Capacity.ofDijkstra(ledger.params, None).fold(e => fail(e.toString), identity)
      )
      val needed = submitted.size + 4L
      // one unit whose bytes are exactly the transaction's measure, or a byte fewer: the ranking
      // block's bytes and the endorser block's split between them
      def unit(bytes: Long) =
        withParams(s(37), tip, ReferenceBuild.Leios)(
          _.copy(
            maxBlockBodySize = BigInt(bytes / 2 + Capacity.FixedBlockBodyOverhead),
            maxEndorserBlockTxsSize = BigInt(bytes - bytes / 2)
          )
        ) match
          case Right(Right(l)) => pool(l, tip, Some(1L))
          case other           => fail(s"no ledger: $other")
      assertEquals(unit(needed).capacity.bytes, needed)
      assert(unit(needed).add(dijkstra(submitted), env, Source.Local).isRight)
      unit(needed - 1).add(dijkstra(submitted), env, Source.Local) match
        case Left(full: Rejection.Full) => assertEquals(full.needed.bytes, needed)
        case other                      => fail(s"not full: $other")
    }
  }

  test("guardLeiosWireLimit: references the LeiosFetch codec cannot carry stop the node") {
    states.map { s =>
      val tip = tipOf(36)
      def at(size: BigInt) =
        withParams(s(37), tip, ReferenceBuild.Leios)(
          _.copy(maxEndorserBlockReferencesSize = size)
        )
      assert(at(BigInt(LeiosFetch.MaxEbBytes)).exists(_.isRight))
      at(BigInt(LeiosFetch.MaxEbBytes) + 1) match
        case Left(failure @ PoolLedger.Failure.WireLimit(over)) =>
          assertEquals(over.parameter, BigInt(LeiosFetch.MaxEbBytes) + 1)
          assertEquals[NodeError, NodeError](
            failure.stop(tip.slot + 1),
            ForgeError.UnsupportedProtocol(
              ForgeProtocolProblem.EndorserBlockReferencesOverWire(
                BigInt(LeiosFetch.MaxEbBytes) + 1,
                LeiosFetch.MaxEbBytes
              )
            )
          )
        case other => fail(s"not the wire limit: $other")
    }
  }

  test("the release builds keep no Dijkstra ledger, and do not read a Dijkstra transaction") {
    states.map { s =>
      val tip = tipOf(36)
      assertEquals(
        withParams(s(37), tip, ReferenceBuild.Release11_2)(identity),
        Right(Left(ConwayView.Unavailable.InEarlier(Era.Dijkstra)))
      )
      assertEquals(
        MempoolState.read(dijkstra(submitted), ReferenceBuild.Release11_2),
        Right(MempoolState.Read.Foreign(Era.Dijkstra.index))
      )
    }
  }

  /** The state's proposals with a proposal of window G's change of keys 47 and 48 (the endorser
    * block's execution units and its reference-script size limit, parameters Dijkstra adds), read
    * by the state's codec of a proposal, attached to the state's root of parameter changes.
    */
  private def withKeys47And48(gov: EraConwayGovState[Dijkstra], epoch: BigInt) =
    val v12 = Versions.eraLow(ShelleyBasedEra.SDijkstra)
    def written[A](e: Either[?, A]): A = e.fold(x => fail(x.toString), identity)
    val account = written(
      LeiosStateCodecs.rewardAccount.write(RewardAccount(0, Credential.Key(hex("0d" * 28))))
    )
    val anchor = written(LeiosStateCodecs.anchor(using v12).write(ConwayGenesis.noAnchor))
    val update = "a2182f821a80befc001b0000008bb2c9700018301a00b71b00"
    val raw = hex("841b000000174876e800" + account.hex + "8400f6" + update + "f6" + anchor.hex)
    val proposal = written(LeiosStateCodecs.dijkstraProposal(using v12).decodeBytes(raw))
    val gas = written(
      ConwayProposals.actionOf[Dijkstra](
        GovActionId(hex("47" * 32), BigInt(0)),
        Map.empty,
        Map.empty,
        Map.empty,
        proposal,
        epoch,
        epoch + 6
      )
    )
    val added = EraProposals
      .proposalsAddAction(gas, gov.cgsProposals)
      .getOrElse(fail("the proposal attaches to the state's root"))
    (gov.copy(cgsProposals = added), gas)

  // the state's governance state is the era's record (#826), so a state whose proposals update
  // parameters Dijkstra adds judges a transaction, and the pool keeps them through it
  test("a state holding a proposal of keys 47 and 48 admits and revalidates a transaction") {
    states.map { s =>
      val tip = tipOf(36)
      val env = envOf(s(37), tip)
      val epoch = nesOf(s(37)).epoch.toBigInt
      val ledger = withGov(s(37), tip, ReferenceBuild.Leios)(withKeys47And48(_, epoch)._1)
      val l = ledger match
        case Right(Right(l: DijkstraLedger)) => l
        case other                           => fail(s"no Dijkstra ledger: $other")
      val (_, gas) = withKeys47And48(nesOf(s(37)).es.ls.utxo.gov, epoch)
      val (held, entry) =
        pool(l, tip)
          .add(dijkstra(submitted), env, Source.Local)
          .fold(r => fail(r.message), identity)
      assertEquals(entry.id, inBlock.id)
      // revalidation at the same tip keeps it, as `reapplyTx` does
      val (same, none) = held.sync(Right(l), tip.slot, env)
      assertEquals(none, Vector.empty)
      assertEquals(same.entries.map(_.ticket), Vector(entry.ticket))
      // the pool's ledger after the transaction, and after revalidation, still holds the proposal
      Vector(held, same).foreach(_.current match
        case Right(d: DijkstraLedger) =>
          assertEquals(
            EraProposals.proposalsLookupId(gas.gasId, d.state.ls.utxo.gov.cgsProposals),
            Some(gas)
          )
        case other => fail(s"no Dijkstra ledger: $other"))
    }
  }

  test("revalidation: kept at the same tip with its ticket, dropped once its block is applied") {
    states.map { s =>
      val tip = tipOf(36)
      val env = envOf(s(37), tip)
      val (held, entry) = pool(ledgerOf(s(37), tip), tip)
        .add(dijkstra(submitted), env, Source.Local)
        .fold(r => fail(r.message), identity)
      // `reapplyTx`, at the same protocol version: the annotation of its admission, kept
      val (same, none) = held.sync(Right(ledgerOf(s(37), tip)), tip.slot, env)
      assertEquals(none, Vector.empty)
      assertEquals(same.entries.map(_.ticket), Vector(entry.ticket))
      val after = tipOf(37)
      val (next, dropped) =
        held.sync(Right(ledgerOf(s(38), after)), after.slot, envOf(s(38), after))
      assertEquals(next.entries, Vector.empty)
      dropped match
        case Vector((e, Rejection.DijkstraRefused(_, failures))) =>
          assertEquals(e.id, entry.id)
          assertEquals(
            failures,
            NonEmptyChain.one(DijkstraMempoolPredFailure.AllInputsAreSpent[Dijkstra]())
          )
        case other => fail(s"not dropped as spent: $other")
    }
  }

  test("the Leios index: the forged endorser block's key, kept, then gone with the transaction") {
    states.map { s =>
      val tip = tipOf(36)
      val env = envOf(s(37), tip)
      val empty = pool(ledgerOf(s(37), tip), tip)
      val (held, entry) =
        empty.add(dijkstra(submitted), env, Source.Local).fold(r => fail(r.message), identity)
      val flat = entry.held match
        case Held.InDijkstra(admitted) => admitted.read.flat
        case other                     => fail(s"not held in Dijkstra: $other")
      // the key and bytes are the ones the forger lists and writes (`forgeLeiosEb`), which the
      // reference's index keys by (`leiosTxHashOfGenTx`)
      val forged = ForgedLeiosEb.forge(tip.slot + 1, NonEmptyVector.one(flat))
      val listed = forged.closure.toVector.toMap
      assertEquals(empty.leiosTxIndex, Map.empty)
      assertEquals(held.leiosTxIndex, listed)
      assertEquals(forged.body.transactions.map(_._1).toVector, held.leiosTxIndex.keys.toVector)
      // revalidated at the same tip: kept, with its key
      val (same, _) = held.sync(Right(ledgerOf(s(37), tip)), tip.slot, env)
      assertEquals(same.leiosTxIndex, listed)
      // removed, or dropped once its block is applied: gone from the index with the entry
      assertEquals(held.remove(Set(entry.id), env)._1.leiosTxIndex, Map.empty)
      val after = tipOf(37)
      val (next, _) = held.sync(Right(ledgerOf(s(38), after)), after.slot, envOf(s(38), after))
      assertEquals(next.leiosTxIndex, Map.empty)
    }
  }

  test(
    "the Leios index is the entries' keys after any sequence of adds, removals and revalidations"
  ) {
    enum Step:
      case Add, Remove, Revalidate, Advance
    states.map { s =>
      val tip = tipOf(36)
      val after = tipOf(37)
      val env = envOf(s(37), tip)
      val id = inBlock.id
      def step(state: MempoolState, op: Step): MempoolState = op match
        case Step.Add    => state.add(dijkstra(submitted), env, Source.Local).fold(_ => state, _._1)
        case Step.Remove => state.remove(Set(id), env)._1
        // back to the tip before the transaction's block, where it applies
        case Step.Revalidate => state.sync(Right(ledgerOf(s(37), tip)), tip.slot, env)._1
        // to the tip of its block, where it is spent and dropped
        case Step.Advance =>
          state.sync(Right(ledgerOf(s(38), after)), after.slot, envOf(s(38), after))._1
      val steps = Gen.listOfN(12, Gen.oneOf(Step.values.toVector))
      val start = pool(ledgerOf(s(37), tip), tip)
      val visited = (0L until 64L).toVector.flatMap { seed =>
        val ops = steps.apply(Gen.Parameters.default, Seed(seed)).getOrElse(fail("no sample"))
        ops.scanLeft(start)(step).map(ops -> _)
      }
      visited.foreach { (ops, state) =>
        assertEquals(state.leiosTxIndex, state.entries.flatMap(_.leiosTx).toMap, s"after $ops")
        assertEquals(state.leiosTxIndex.size, state.entries.count(_.era == Era.Dijkstra))
      }
      // the sequences reach both a pool that holds the transaction and one that does not
      assertEquals(visited.map(_._2.leiosTxIndex.size).toSet, Set(0, 1))
    }
  }

  test(
    "a Conway transaction reaches Dijkstra by redecoding: submitted, and held across the crossing"
  ) {
    states.map { s =>
      val conway = Submission(Era.Conway.index, submitted)
      // submitted in Conway to the Dijkstra pool: read in Conway, written in Conway and read again
      // in Dijkstra (`matchTx`, ouroboros-consensus 2416493b `HardFork/Combinator/InjectTxs.hs`)
      val tip = tipOf(36)
      val (_, entry) = pool(ledgerOf(s(37), tip), tip)
        .add(conway, envOf(s(37), tip), Source.Local)
        .fold(r => fail(r.message), identity)
      assertEquals((entry.era, entry.id), (Era.Dijkstra, inBlock.id))
      // held by the Conway pool of the last tip whose next slot is in Conway, then revalidated in
      // Dijkstra
      val (last, before, conwayPool) = (1 to 36).reverseIterator
        .map(i => (i, tipOf(i - 1)))
        .collectFirst(Function.unlift { (i, t) =>
          PoolLedger.at(s(i), Some(t), envOf(s(i), t), transition) match
            case Right(Right(l: MempoolLedger)) => Some((i, t, l))
            case _                              => None
        })
        .getOrElse(fail("no Conway ledger"))
      val (held, first) = pool(conwayPool, before)
        .add(conway, envOf(s(last), before), Source.Local)
        .fold(r => fail(r.message), identity)
      assertEquals(first.era, Era.Conway)
      val (moved, dropped) = held.sync(Right(ledgerOf(s(37), tip)), tip.slot, envOf(s(37), tip))
      assertEquals(dropped, Vector.empty)
      assertEquals(
        moved.entries.map(e => (e.era, e.id, e.ticket)),
        Vector((Era.Dijkstra, inBlock.id, first.ticket))
      )
      // a Conway transaction has no key an endorser block lists; brought into Dijkstra, it has
      assertEquals(held.leiosTxIndex, Map.empty)
      assertEquals(moved.leiosTxIndex.keySet, moved.entries.flatMap(_.leiosTx.map(_._1)).toSet)
      assertEquals(moved.leiosTxIndex.size, 1)
    }
  }

  test(
    "the forger's snapshot of the pool: a ranking block with the transaction, or an endorser block"
  ) {
    for
      s <- states
      tip = tipOf(36)
      env = envOf(s(37), tip)
      mempool <- Mempool.create[IO](
        Right(ledgerOf(s(37), tip)),
        tip.slot,
        env,
        None,
        MempoolEvents.none[IO]
      )
      handler = LocalSubmission.handler(mempool, ReferenceBuild.Leios)(f => IO(fail(s"fatal: $f")))
      // LocalTxSubmission takes it: `[7, #6.24(tx)]`
      answer <- handler(LocalMonitor.wrapTx(Era.Dijkstra, submitted))
      _ = assertEquals(answer, LocalTxSubmissionServer.Response.Accepted)
      slot = chain(37)._1.slot
      ticked = Hfc
        .tick(Globals(setup.globals, geometry), transition)(
          MirrorStep.nesOf(s(37)).getOrElse(fail("no ledger")),
          MirrorStep.afterVotingOf(s(37)),
          SlotNo.fromBigInt(slot).fold(e => fail(e.toString), identity)
        )
        .map(MirrorDijkstra.inDijkstra)
        .fold(e => fail(e.toString), _.getOrElse(fail("not in Dijkstra")))
      snapshot <- DijkstraSnapshots
        .ofPool[IO](mempool, None, IO.pure(env))
        .forSlot(ticked, slot, setup.txClock.at(s(37), Era.Dijkstra, slot))
    yield
      assertEquals(snapshot.withheld, Map.empty)
      assertEquals(snapshot.held.map(_.tx.id), Vector(inBlock.id))
      // the block form the forger writes is the one block 37 holds
      assertEquals(snapshot.held.map(_.tx.forBlockInclusion), Vector(inBlock.forBlockInclusion))
      assertEquals(snapshot.held.map(_.measure), Vector(TxMeasure(submitted.size + 4L, 0, 0, 0L)))
      val params = ledgerOf(s(37), tip).params
      val eb = Capacity.endorserBlockCapacity(params).fold(e => fail(e.toString), identity)
      val ranking = BlockBuilder.partition(snapshot, Capacity.dijkstraBlockUnit(params), eb)
      assertEquals(ranking.ranking.transactions.map(_.id), Vector(inBlock.id))
      assertEquals(ranking.endorsing(slot), None)
      // the body the forger builds of the snapshot is block 37's: its hash the header commits to
      val recorded = LeiosBlock.decode(chain(37)._2).fold(e => fail(e.message), identity)
      assertEquals(
        Blake2b.hash256.hash(
          xyz.kd5ujc.scarab.ledger.LeiosBlockBody(ranking.ranking.transactions, None, None).encode
        ),
        recorded.bodyHash
      )
      // a ranking block that takes nothing leaves it to the endorser block it announces
      val endorsing = BlockBuilder.partition(snapshot, TxMeasure.zero, eb)
      assertEquals(endorsing.ranking.transactions, Vector.empty)
      assertEquals(endorsing.endorsedTransactions.map(_.id), Vector(inBlock.id))
      assert(endorsing.endorsing(slot).isDefined)
  }

  test("TxSubmission2's Leios reader reads a Dijkstra transaction; LocalTxSubmission writes none") {
    states.map { s =>
      val leios = RelayedTransactions.of(ReferenceBuild.Leios)
      val body = inBlock.body.raw
      assertEquals(leios.read(submitted, Era.Dijkstra), Right(body))
      assertEquals(leios.body(submitted, Era.Dijkstra), Right(body))
      assert(leios.read(submitted.drop(1), Era.Dijkstra).isLeft)
      // another era's body is read as the release builds read it
      assertEquals(
        leios.body(submitted, Era.Conway),
        RelayedTransactions.body(submitted, Era.Conway)
      )
      // Dijkstra's refusal has no encoding here: no reply (#796)
      val tip = tipOf(37)
      val refused = pool(ledgerOf(s(38), tip), tip)
        .add(dijkstra(submitted), envOf(s(38), tip), Source.Local)
        .fold(identity, _ => fail("admitted"))
      LocalSubmission.response(Refused(refused, Some(Era.Dijkstra))) match
        case LocalTxSubmissionServer.Response.ServiceUnavailable(
              LocalTxSubmissionServer.Unavailable.Presentation(
                LocalSubmission.DijkstraNoEncoding.Refused(_)
              )
            ) =>
          ()
        case other => fail(s"not without a reply: $other")
    }
  }

  test("isIncorrectClaimedFlag: UTXOS's tag mismatch alone, whatever the flag it names") {
    val mismatch = DijkstraMempoolPredFailure.LedgerFailure[Dijkstra](
      DijkstraLedgerPredFailure.DijkstraUtxowFailure[Dijkstra](
        DijkstraUtxowPredFailure.UtxoFailure[Dijkstra](
          DijkstraUtxoPredFailure.UtxosFailure[Dijkstra](
            ConwayUtxosPredFailure.ValidationTagMismatch[Dijkstra](
              IsPhase2Valid.Phase2Invalid,
              TagMismatchDescription.PassedUnexpectedly
            )
          )
        )
      )
    )
    assert(DijkstraState.isIncorrectClaimedFlag(NonEmptyChain.one(mismatch)))
    assert(
      !DijkstraState.isIncorrectClaimedFlag(
        NonEmptyChain(mismatch, DijkstraMempoolPredFailure.AllInputsAreSpent[Dijkstra]())
      )
    )
  }

  test("the phase-two flag set on both forms: the id kept, the block form's last byte the flag") {
    val read = DijkstraRead.submitted(submitted).fold(e => fail(e.toString), identity)
    val invalid = read.withIsValid(IsPhase2Valid.Phase2Invalid)
    assertEquals(invalid.id, read.id)
    assertEquals(invalid.isValid, IsPhase2Valid.Phase2Invalid)
    assertEquals(invalid.flat.isValid, false)
    val (valid, flipped) = (read.flat.forBlockInclusion, invalid.flat.forBlockInclusion)
    assertEquals(flipped.take(flipped.size - 1), valid.take(valid.size - 1))
    assertEquals(
      (valid.drop(valid.size - 1), flipped.drop(flipped.size - 1)),
      (hex("f5"), hex("f4"))
    )
    // the mempool form omits the flag
    assertEquals(invalid.raw, read.raw)
  }

/** Dijkstra's injection of UTXO's failures into MEMPOOL's, for the assertions. */
private object InjectMempoolFailureOf:
  def utxo(f: DijkstraUtxoPredFailure[Dijkstra]): DijkstraMempoolPredFailure[Dijkstra] =
    xyz.kd5ujc.scarab.ledger.era.rules.InjectMempoolFailure.dijkstraUtxo.injectFailure(f)
