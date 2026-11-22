// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.IO
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import io.circe.parser
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Value}
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.leios.EbClosureVerdict
import xyz.kd5ujc.scarab.consensus.state.{MirrorState, MirrorStep}
import xyz.kd5ujc.scarab.ledger.{Block, Header, NodeError, StorageError, WitnessCheck}
import xyz.kd5ujc.scarab.ledger.era.{IsPhase2Valid, Tx, TxLevel}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraCorpus, DijkstraTx}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  ConwayUtxosPredFailure,
  DijkstraClosure,
  DijkstraLedgerPredFailure,
  DijkstraMempoolPredFailure,
  DijkstraUtxoPredFailure,
  DijkstraUtxowPredFailure,
  LeiosClosureDefect
}
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, NewEpochState, SlotNo}
import xyz.kd5ujc.scarab.node.mempool.{DijkstraPoolEnv, DijkstraRead, MempoolEnv, Rejection}
import xyz.kd5ujc.scarab.plutus.PlutusBackend
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint, Era, LeiosTxHash, ReferenceBuild}
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word64
import xyz.kd5ujc.scarab.storage.MemoryUtxoStore
import xyz.kd5ujc.scarab.ledger.{EpochGeometry, TestGeometry, Utxo}
import xyz.kd5ujc.scarab.ledger.era.{
  BothLevels,
  ConwayEraAccounts,
  CostModels,
  EraCertState,
  EraPParams,
  EraStake,
  EraTxBody,
  GovAction,
  GovRelation,
  Identity,
  PParams,
  Proposals,
  StoredTxOut,
  UTxO
}
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.state.{
  Accounts,
  Build,
  Coin,
  CompactCoin,
  DState,
  EpochNo,
  UtxoTables
}
import xyz.kd5ujc.scarab.node.mempool.DijkstraState
import xyz.kd5ujc.scarab.plutus.PlutusLanguage

/** The closure check a voter runs before it votes (`validateEbClosure`, ouroboros-consensus
  * 2416493b `LeiosVoting.hs:446-565`), on the devnet's recorded Dijkstra transaction
  * (`fixtures/dijkstra-devnet`, the transaction of its block 37) as an endorser block's closure on
  * the state before that block, its tip the announcing block: it applies, ticked to the tip's own
  * slot; a closure that spends its inputs twice, an altered transaction and a transaction whose
  * validity flag its scripts contradict (`Intervene`) are refused with the ledger's refusal, as the
  * reference's `EbClosureInvalid`; a missing or undecodable closure is no verdict
  * (`EbClosureUnreadable`).
  */
class LeiosClosureCheckSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  private given Raise[IO, StorageError] with
    def functor = cats.Functor[IO]
    def raise[E <: StorageError, A](error: E): IO[A] = IO(fail(error.message))

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.message), identity)

  private val devnet = Path.of("fixtures", "dijkstra-devnet")

  private lazy val setup: NodeSetup =
    NodeSetup
      .fromDirectory(ReferenceBuild.LeiosB004fe0c)(devnet.resolve("genesis"))
      .fold(e => fail(e.message), identity)

  private def geometry = setup.praos.geometry

  private lazy val chain: Vector[(Header, Bytes)] =
    Files
      .readAllLines(devnet.resolve("blocks.tsv"))
      .asScala
      .toVector
      .drop(1)
      .filter(_.nonEmpty)
      .map(_.split('\t'))
      .take(37)
      .map { r =>
        val raw = hex(r(4))
        Block.decode(raw, geometry.byron.epochSlots).fold(e => fail(e.message), _.header) -> raw
      }

  /** The state before block 37: block 36 its tip, the announcing block here. */
  private lazy val announcing: IO[MirrorState] =
    val rules = new MirrorRules[IO](
      setup.praos,
      setup.globals,
      setup.byronRules,
      setup.handOver,
      setup.byronHistory,
      PlutusBackend.default
    )
    chain.foldLeftM(setup.mirrorStart) { case (state, (header, raw)) =>
      Handle
        .allow[NodeError](rules(state, header, raw))
        .rescue(e => IO(fail(s"block ${header.blockNo}: ${e.message}")))
    }

  private def tip: BlockRef =
    val h = chain.last._1
    BlockRef(h.slot, h.hash, h.blockNo)

  private def nesOf(state: MirrorState): NewEpochState[Dijkstra] =
    MirrorStep
      .nesOf(state)
      .flatMap(MirrorDijkstra.inDijkstra)
      .getOrElse(fail("not in Dijkstra"))

  /** The endorser block's point: the announcing block's slot (`announcementLeiosPoint`). */
  private def point(hash: Int): EbPoint =
    EbPoint(
      Word64.fromUnsigned(tip.slot.toLong),
      EbHash.fromBytes(Bytes(Vector.fill(32)(hash.toByte)))
    )

  private def envOf(state: MirrorState): DijkstraPoolEnv =
    DijkstraPoolEnv
      .of(
        MempoolEnv(
          setup.globals,
          geometry,
          PlutusBackend.default.evaluator,
          PlutusBackend.default.decoder
        ),
        tip.slot
      )
      .copy(clock = setup.txClock.at(state, Era.Dijkstra, tip.slot))

  /** The wallet's submitted transaction (`[body, wits, aux / null]`). */
  private lazy val submitted: Bytes =
    val json = parser.parse(Files.readString(devnet.resolve("transaction.json")))
    hex(json.flatMap(_.hcursor.get[String]("cborHex")).fold(e => fail(e.getMessage), identity))

  /** The transaction as an endorser block holds it, `[body, wits, aux / null, valid]`
    * (`decodeDijkstraTopTxInBlock`, the flag last), with the validity flag `valid`.
    */
  private def inBlock(valid: Boolean): Bytes =
    DijkstraCorpus.node(submitted).value match
      case Value.Arr(items) if items.sizeIs == 3 =>
        val parts = items.map(_.original)
        Bytes.fromArray(Array(0x84.toByte)) ++ parts(0) ++ parts(1) ++ parts(2) ++
          Bytes.fromArray(Array((if valid then 0xf5 else 0xf4).toByte))
      case other => fail(s"not a transaction: $other")

  private lazy val tx: Tx[TxLevel.Top, Dijkstra] =
    DijkstraRead.submitted(submitted).fold(e => fail(e.toString), _.tx)

  private def entry(t: Tx[TxLevel.Top, Dijkstra]): (LeiosTxHash, Tx[TxLevel.Top, Dijkstra]) =
    LeiosTxHash.fromBytes(t.id) -> t

  private def checked(
      closure: Vector[Tx[TxLevel.Top, Dijkstra]]
  ): IO[EbClosureVerdict[Rejection, Nothing]] =
    announcing.map { s =>
      val at = SlotNo.fromBigInt(tip.slot).fold(e => fail(e.toString), identity)
      val state =
        LeiosClosureCheck.ticked(envOf(s).rules, nesOf(s), at).fold(e => fail(e.toString), identity)
      LeiosClosureCheck.applyClosure(closure.map(entry), state, envOf(s))
    }

  private def refused(verdict: EbClosureVerdict[Rejection, ?]) = verdict match
    case EbClosureVerdict.Invalid(Rejection.DijkstraRefused(id, failures)) =>
      (id, failures.toChain.toVector)
    case other => fail(s"not a refusal of the rules: $other")

  test(
    "a closure of a transaction that applies is valid on the announcing state ticked to its slot"
  ) {
    checked(Vector(tx)).map(v => assertEquals(v, EbClosureVerdict.Valid(0, 1)))
  }

  test("a closure that spends its inputs twice is refused at its second transaction") {
    checked(Vector(tx, tx)).map(v =>
      assertEquals(
        refused(v),
        (tx.id, Vector(DijkstraMempoolPredFailure.AllInputsAreSpent[Dijkstra]()))
      )
    )
  }

  test("an altered transaction is refused by the ledger, the closure with it") {
    // the fee one lovelace more: the body's hash and its balance change, the witness does not
    val fee = hex("021a0002990d")
    val at = submitted.value.indexOfSlice(fee.value)
    assert(at > 0, "the fee field")
    val altered = DijkstraRead
      .submitted(submitted.take(at) ++ hex("021a0002990e") ++ submitted.drop(at + fee.size))
      .fold(e => fail(e.toString), _.tx)
    checked(Vector(altered)).map { v =>
      val (id, failures) = refused(v)
      assertEquals(id, altered.id)
      assert(
        failures.exists {
          case DijkstraMempoolPredFailure.LedgerFailure(
                DijkstraLedgerPredFailure.DijkstraUtxowFailure(
                  DijkstraUtxowPredFailure.InvalidWitnessesUTXOW(_)
                )
              ) =>
            true
          case _ => false
        },
        s"$failures"
      )
    }
  }

  test("Intervene: a flag the scripts contradict is refused, not applied as collateral-only") {
    // the transaction runs no script, so a declared invalid flag mismatches its evaluation
    val flipped = DijkstraTx.withIsValid(tx, IsPhase2Valid.Phase2Invalid)
    checked(Vector(flipped)).map { v =>
      val (_, failures) = refused(v)
      assert(
        failures.exists {
          case DijkstraMempoolPredFailure.LedgerFailure(
                DijkstraLedgerPredFailure.DijkstraUtxowFailure(
                  DijkstraUtxowPredFailure.UtxoFailure(
                    DijkstraUtxoPredFailure.UtxosFailure(
                      ConwayUtxosPredFailure.ValidationTagMismatch(_, _)
                    )
                  )
                )
              ) =>
            true
          case _ => false
        },
        s"$failures"
      )
    }
  }

  test("the closure is read from the store, decoded as an endorser block holds it, and applied") {
    val p = point(7)
    val stored = Vector(LeiosTxHash.fromBytes(tx.id) -> inBlock(valid = true))
    assertEquals(DijkstraClosure.deserializeTxForLeios(stored.head._2).map(_.id), Right(tx.id))
    announcing.flatMap { s =>
      val at = SlotNo.fromBigInt(tip.slot).fold(e => fail(e.toString), identity)
      val state =
        LeiosClosureCheck.ticked(envOf(s).rules, nesOf(s), at).fold(e => fail(e.toString), identity)
      def lookup(answer: Option[Vector[(LeiosTxHash, Bytes)]]) =
        LeiosTxCache
          .create[IO]
          .flatMap(cache =>
            LeiosClosureCheck.validateEbClosure[IO](
              h => IO(assertEquals(h, p.hash)).as(answer),
              None,
              cache,
              (_: Vector[WitnessCheck]) => IO.unit,
              p,
              state,
              envOf(s)
            )
          )
      val garbled = Vector(LeiosTxHash.fromBytes(tx.id) -> hex("8200"))
      (lookup(Some(stored)), lookup(None), lookup(Some(garbled))).tupled.map {
        (valid, missing, undecodable) =>
          assertEquals(valid, EbClosureVerdict.Valid(0, 1))
          assertEquals(missing, EbClosureVerdict.Unreadable(LeiosClosureDefect.Missing(p.hash)))
          undecodable match
            case EbClosureVerdict.Unreadable(LeiosClosureDefect.TxUndecodable(eb, txh, _)) =>
              assertEquals((eb, txh), (p.hash, garbled.head._1))
            case other => fail(s"not undecodable: $other")
      }
    }
  }

  test("the outputs a closure spends are read from the store when the state does not hold them") {
    val p = point(8)
    val stored = Vector(LeiosTxHash.fromBytes(tx.id) -> inBlock(valid = true))
    announcing.flatMap { s =>
      val at = SlotNo.fromBigInt(tip.slot).fold(e => fail(e.toString), identity)
      val state =
        LeiosClosureCheck.ticked(envOf(s).rules, nesOf(s), at).fold(e => fail(e.toString), identity)
      val inputs = MirrorDijkstra.inputs(Vector(tx))
      val held = state.utxo
      val spent = inputs.flatMap(i => held.get(i).map(i -> _)).toMap
      assert(spent.nonEmpty, "the transaction's inputs are in the state")
      // the state without the spent outputs, which only the store holds; an absent key is no entry
      val stripped = held.copy(diff = held.diff -- inputs, resident = held.resident -- inputs)
      for
        store <- MemoryUtxoStore[IO]
        _ <- store.write(spent.view.mapValues(Some(_)).toMap, 0L)
        cache <- LeiosTxCache.create[IO]
        withStore <- LeiosClosureCheck.validateEbClosure[IO](
          _ => IO.pure(Some(stored)),
          Some(store),
          cache,
          (_: Vector[WitnessCheck]) => IO.unit,
          p,
          state.withUtxo(stripped),
          envOf(s)
        )
        fresh <- LeiosTxCache.create[IO]
        without <- LeiosClosureCheck.validateEbClosure[IO](
          _ => IO.pure(Some(stored)),
          None,
          fresh,
          (_: Vector[WitnessCheck]) => IO.unit,
          p,
          state.withUtxo(stripped),
          envOf(s)
        )
      yield
        assertEquals(withStore, EbClosureVerdict.Valid(0, 1))
        // without the store the state holds none of them: MEMPOOL's own refusal
        assertEquals(
          refused(without),
          (tx.id, Vector(DijkstraMempoolPredFailure.AllInputsAreSpent[Dijkstra]()))
        )
    }
  }

  test("an empty closure is valid, as `goValidate` 's `[]` is") {
    checked(Vector.empty).map(v => assertEquals(v, EbClosureVerdict.Valid(0, 0)))
  }

  // ---- the testnet's parameter change in a closure -----------------------------------------------

  /** The testnet's endorser-block transaction `e57ccb64…` of block 69322
    * (`fixtures/reference/leios-testnet-chain-contexts`, recorded with the outputs it spends): a
    * parameter change of keys 47 and 48, whose parent is the testnet's first parameter change.
    */
  private lazy val proposalCase: io.circe.Json =
    DijkstraCorpus.chainContextCases
      .find(c => DijkstraCorpus.str(c, "name").startsWith("e57ccb64"))
      .getOrElse(fail("no e57ccb64 in the chain contexts"))

  private lazy val chainFixture: io.circe.Json =
    DijkstraCorpus.json("fixtures/reference/leios-testnet-chain-contexts/cases.json")

  private def field[A: io.circe.Decoder](j: io.circe.Json, name: String): A =
    j.hcursor.get[A](name).fold(e => fail(e.message), identity)

  private lazy val proposalTx: Tx[TxLevel.Top, Dijkstra] =
    DijkstraTx
      .inBlock(DijkstraCorpus.node(DijkstraCorpus.hex(DijkstraCorpus.str(proposalCase, "bytes"))))
      .fold(e => fail(e.toString), identity)

  /** The testnet's parameters of its genesis, with the cost models it ran (the fixture's), as the
    * ledger's own chain suite reads them (`DijkstraLedgerSuite`).
    */
  private lazy val testnetStored: xyz.kd5ujc.scarab.ledger.ProtocolParams =
    def genesis(era: String) =
      DijkstraCorpus.json(s"fixtures/leios/testnet/config/$era-genesis.json")
    xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraFixture.genesis.upgrade(
      xyz.kd5ujc.scarab.ledger.ProtocolParams
        .fromGenesis(genesis("shelley"), genesis("alonzo"), Some(genesis("conway")))
        .fold(e => fail(e.toString), identity)
    )

  private lazy val testnetPp: PParams[Dijkstra] =
    val languages = PlutusLanguage.values.map(l => s"Plutus$l" -> l).toMap
    val models = field[Map[String, Vector[BigInt]]](chainFixture, "costModels")
      .map((name, values) => languages.getOrElse(name, fail(name)).id -> values)
    PParams
      .lensHKD[Dijkstra]
      .andThen(EraPParams.dijkstra.hkdCostModelsL[Identity])
      .replace(CostModels.from(models).fold(e => fail(e.toString), identity))(
        DijkstraCorpus.era(testnetStored)
      )

  private lazy val testnetGeometry: EpochGeometry =
    EpochGeometry
      .checked(
        java.time.Instant.parse(DijkstraCorpus.str(chainFixture, "systemStart")),
        (field[BigInt](chainFixture, "slotLength") * 1000).toLong,
        field[BigInt](chainFixture, "epochSize").toLong,
        TestGeometry.k(2),
        TestGeometry.f("0.5")
      )
      .fold(e => fail(e.message), identity)

  private lazy val proposalSlot: BigInt = field[BigInt](proposalCase, "slot")

  private lazy val proposalEnv: DijkstraPoolEnv =
    DijkstraPoolEnv.of(
      MempoolEnv(
        setup.globals,
        testnetGeometry,
        PlutusBackend.default.evaluator,
        PlutusBackend.default.decoder
      ),
      proposalSlot
    )

  /** The least state the transaction needs, as the chain suite seeds it: the testnet's genesis
    * state with its parameters, its constitution (whose guardrails script the proposal names), the
    * proposals rooted at the proposal's parent, as when that change was enacted, the proposal's
    * return account registered with 2 ada, and the recorded outputs.
    */
  private lazy val proposalState: DijkstraState =
    val bodies = EraTxBody.dijkstra
    val procedures = bodies.proposalProcedures[TxLevel.Top](proposalTx.body)(using BothLevels.Top)
    val parent = procedures.toVector.flatMap(_.pProcGovAction match
      case GovAction.ParameterChange(parent, _, _) => parent
      case _                                       => None)
    val returns = procedures.toVector.map(p =>
      Convert.accountAddress(p.pProcReturnAddr).fold(e => fail(e.toString), _.id.credential)
    )
    val outputs = field[Vector[(String, String)]](proposalCase, "utxo").map { (i, o) =>
      val in = xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraTxIn
        .decode(DijkstraCorpus.node(DijkstraCorpus.hex(i)))
        .fold(e => fail(e.toString), DijkstraCorpus.txIn)
      in -> xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTxOut
        .decode(DijkstraCorpus.node(DijkstraCorpus.hex(o)))
        .fold(e => fail(e.toString), identity)
    }
    val nes = Build.dijkstra(EpochNo.fromUnsigned(0L), testnetStored)
    val ls = nes.es.ls
    val c = EraCertState.dijkstra
    given ConwayEraAccounts[Dijkstra] = c.accountsOf
    val accountsL = c.certDStateL.andThen(DState.accounts[Accounts[Dijkstra]])
    val deposit = CompactCoin.fromCoin(Coin(2000000)).fold(e => fail(e.toString), identity)
    val cert = returns.foldLeft(ls.cert)((st, cred) =>
      accountsL
        .modify(ConwayEraAccounts.registerConwayAccount[Dijkstra](cred, deposit, None, _))(st)
    )
    val utxo = UTxO[Dijkstra](outputs.toMap)
    val conway = DijkstraCorpus
      .json("fixtures/leios/testnet/config/conway-genesis.json")
      .hcursor
      .downField("constitution")
    val constitution = xyz.kd5ujc.scarab.ledger.Constitution(
      ls.utxo.gov.cgsConstitution.anchor,
      Some(DijkstraCorpus.hex(conway.get[String]("script").fold(e => fail(e.message), identity)))
    )
    val seeded = ls.copy[Dijkstra](
      cert = cert,
      utxo = ls.utxo.copy[Dijkstra](
        utxo = UtxoTables(
          Utxo(outputs.map((i, o) => i -> StoredTxOut.dijkstra.toStored(o)).toMap)
        ),
        instantStake = EraStake.dijkstra
          .addInstantStake(utxo, ls.utxo.instantStake)
          .fold(e => fail(e.toString), identity),
        gov = ls.utxo.gov.copy(
          cgsCurPParams = testnetPp,
          cgsConstitution = constitution,
          cgsProposals =
            Proposals.empty[Dijkstra](GovRelation[Option](parent.headOption, None, None, None))
        )
      )
    )
    DijkstraState.of(nes.copy[Dijkstra](es = nes.es.copy[Dijkstra](ls = seeded)))

  private def heldProposal(state: DijkstraState): Boolean =
    Proposals
      .proposalsActionsMap(state.ls.utxo.gov.cgsProposals)
      .keys
      .exists(_.txId == proposalTx.id)

  test("a closure with the testnet's parameter change of keys 47 and 48 is valid") {
    assert(!heldProposal(proposalState), "the proposal is not held before")
    assertEquals(
      LeiosClosureCheck.applyClosure(Vector(entry(proposalTx)), proposalState, proposalEnv),
      EbClosureVerdict.Valid(0, 1)
    )
  }

  test("the state the closure's next transaction meets holds the accepted proposal") {
    // the closure is folded over this step: each transaction applies to the state the one before
    // it left, its governance state's proposals included (`goValidate`, `LeiosVoting.hs:504-542` at ouroboros-consensus 2416493b)
    val after = proposalState
      .applyIntervening(proposalTx, proposalEnv)
      .fold(r => fail(r.message), identity)
    assert(heldProposal(after), "the proposal is held after")
    // and a second application on it is MEMPOOL's refusal of spent inputs, not a governance one
    assertEquals(
      refused(
        LeiosClosureCheck
          .applyClosure(Vector(entry(proposalTx), entry(proposalTx)), proposalState, proposalEnv)
      ),
      (proposalTx.id, Vector(DijkstraMempoolPredFailure.AllInputsAreSpent[Dijkstra]()))
    )
  }

  test("the voter casts its vote on the closure with the parameter change") {
    import xyz.kd5ujc.scarab.consensus.leios.{
      AddVoteResult,
      LeiosVoting,
      LeiosVotingDefect,
      LeiosVotingEnv,
      SelectionView,
      TipCommittee,
      VotingKey
    }
    import xyz.kd5ujc.scarab.crypto.bls.{Committee, RbHash, Seat, Vote, Weight}
    import xyz.kd5ujc.scarab.crypto.bls.BlsFixtures.{key, rbHash, right}
    // a throwaway key made here, the committee's one seat
    val k = key(4711)
    val committee = TipCommittee(
      right(
        Committee.honoured(Vector(Seat(Weight(xyz.kd5ujc.scarab.codec.Rational(1)), Some(k._2))))
      ),
      Weight(xyz.kd5ujc.scarab.codec.Rational(3, 4))
    )
    val announcing: RbHash = rbHash(47)
    val p = EbPoint(Word64.fromUnsigned(proposalSlot.toLong), EbHash.fromBytes(rbHash(48).bytes))
    val fixed = org.scalacheck.rng.Seed(47L)
    val praos = xyz.kd5ujc.scarab.consensus.state.StateGen.praosState
      .pureApply(org.scalacheck.Gen.Parameters.default, fixed)
      .copy(lastSlot =
        xyz.kd5ujc.scarab.ledger.state.WithOrigin.At(SlotNo.fromUnsigned(proposalSlot.toLong))
      )
    val announced = xyz.kd5ujc.scarab.consensus.state.StateGen.announcedBy
      .pureApply(org.scalacheck.Gen.Parameters.default, fixed)
    val chainDep = xyz.kd5ujc.scarab.consensus.state.ChainDepState.Praos2(
      xyz.kd5ujc.scarab.consensus.Praos2.State(
        praos,
        Some(announced.copy(announcement = announced.announcement.copy(hash = p.hash)))
      )
    )
    for
      votes <- LeiosVotes.create[IO](IO.pure(Right(Some(committee))))
      env = new LeiosVotingEnv[IO, DijkstraState, Rejection, LeiosClosureDefect]:
        def now: IO[scala.concurrent.duration.FiniteDuration] =
          IO.pure(scala.concurrent.duration.Duration.Zero)
        def selection: IO[Option[SelectionView[DijkstraState]]] =
          IO.pure(Some(SelectionView(chainDep, Some(announcing), Some(committee), proposalState)))
        def validateClosure(point: EbPoint, ledger: DijkstraState) =
          IO.pure(LeiosClosureCheck.applyClosure(Vector(entry(proposalTx)), ledger, proposalEnv))
        def addVote(vote: Vote): IO[AddVoteResult] =
          Handle
            .allow[LeiosVotesDefect](votes.add(vote))
            .rescue(e => IO.raiseError(new AssertionError(e.toString)))
      voted <- Handle
        .allow[LeiosVotingDefect](
          LeiosVoting.vote(
            env,
            Vector(right(VotingKey.of(k._1))),
            p,
            scala.concurrent.duration.Duration(1, "s")
          )
        )
        .rescue(e => IO.raiseError(new AssertionError(e.toString)))
      cert <- votes.certificate(announcing)
    yield
      voted match
        case Right(answers) =>
          assert(answers.forall(_._2.isInstanceOf[AddVoteResult.Added]), s"$answers")
        case Left(reason) => fail(s"no vote: $reason")
      assert(cert.isDefined, "the one seat's vote makes the certificate")
  }
