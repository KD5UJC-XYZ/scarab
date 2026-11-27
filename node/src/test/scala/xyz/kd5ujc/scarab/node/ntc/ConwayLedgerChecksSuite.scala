// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.syntax.all.*
import java.nio.file.Path
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{Horizon, Tx, TxEnv, TxIn, TxOut}
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoStAnnTx,
  EraLedgerRun,
  EraTx,
  EraTxBody,
  EraUTxO,
  Network,
  PParams,
  TxLevel,
  UTxO
}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayPParams, ConwayTx, ConwayUTxO}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  ConwayGovPredFailure,
  ConwayLedgerPredFailure,
  ConwayUtxo,
  LedgerEnv,
  LedgerPredFailure,
  LedgersEnv,
  ShelleyLedgersPredFailure,
  UtxoContext
}
import xyz.kd5ujc.scarab.ledger.rules.Globals
import xyz.kd5ujc.scarab.ledger.state.{
  ChainAccountState,
  Coin,
  Conway,
  EpochNo,
  LedgerState,
  NewEpochState,
  ShelleyBasedEra,
  TxIx,
  UtxoTables
}
import xyz.kd5ujc.scarab.ledger.Utxo
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.node.mempool.{ConwayTicked, LocalSubmission, MempoolEnv}
import xyz.kd5ujc.scarab.protocol.{Era, ReferenceBuild, StateLayout}
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, UtxoCodecs}

/** Conway's LEDGER on the era types (`ConwayLedger.conwayLedgerTransition`), and LEDGERS
  * (`ShelleyLedgers.ledgersTransition`) over it, on every transaction of the recorded Conway
  * refusal corpora, from the state the mempool judges it in, at the recorded protocol version and
  * at 9, 10 and 11, under each build and validation policy: the flat LEDGER's failures in the same
  * order, or the same ledger state and counters, as recorded before the flat LEDGER was deleted
  * (`fixtures/conway-flat-refusals`), on the corpora it judged. Where the reference refused a
  * transaction for LEDGER, the era rule's failures, one level below MEMPOOL, encode to the
  * reference's refusal byte for byte. The corpora recorded for the era rules alone
  * ([[xyz.kd5ujc.scarab.node.ntc.ReferenceRefusalSuite.EraOnly]]) have no recording of the flat
  * rules; the era rules' own assertions keep them, and `ReferenceRefusalSuite` binds the era rules
  * to them.
  */
class ConwayLedgerChecksSuite extends FunSuite:
  import ReferenceRefusalSuite.*

  private given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway

  /** One Conway case as both paths see it: its transaction, and the state the mempool judges it in.
    */
  private final case class Seen(
      label: String,
      bytes: Bytes,
      globals: Globals,
      network: Int,
      netId: Network,
      flat: Tx,
      typed: ConwayTx,
      stored: Map[TxIn, TxOut],
      utxo: UTxO[Conway],
      pp: PParams[Conway],
      ledger: ConwayTicked,
      slot: BigInt,
      build: ReferenceBuild,
      families: Vector[Family],
      reason: Bytes,
      afterAdmitted: Boolean
  )

  private def right[E, A](what: String)(e: Either[E, A]): A =
    e.fold(x => fail(s"$what: $x"), identity)

  private val seen: Vector[Seen] =
    val loaded = right("the refusal corpus")(load(Path.of("fixtures/reference/refusals")))
    loaded.corpora.filter(_.era == RefusalEra.Conway).flatMap { c =>
      val setup = right(s"${c.path} genesis")(
        NodeSetup.fromFiles(c.build)(name => c.genesis.get(name).map(_.toArray))
      )
      val codecs = right(s"${c.path} layout")(stateCodecs(c.build))
      val env = MempoolEnv(
        setup.globals,
        setup.geometry,
        xyz.kd5ujc.scarab.plutus.ScarabEvaluator.default,
        xyz.kd5ujc.scarab.plutus.ScarabEvaluator.defaultDecoder
      )
      val netId = setup.globals.network
      val network = netId.wire
      c.cases.map { rc =>
        val what = s"${c.path}/${rc.label}"
        val bytes: Bytes = right(s"$what item")(LocalSubmission.unwrap(rc.item)).raw
        val nes = right(s"$what state")(
          codecs.newEpochState[Conway].decodeBytes(c.states(rc.state), HaskellState.limits)
        )
        val stored: Map[TxIn, TxOut] =
          right(s"$what UTxO")(UtxoCodecs.utxo.decodeBytes(c.utxos(rc.utxo)))
        val withUtxo = NewEpochState
          .ledger[Conway]
          .modify(ls =>
            ls.copy[Conway](utxo = ls.utxo.copy(utxo = UtxoTables(Utxo.fromMap(stored))))
          )(nes)
        val ledger = right(s"$what tick")(ConwayTicked.ticked(withUtxo, Some(rc.tipSlot), env))
        Seen(
          what,
          bytes,
          env.rules,
          network,
          netId,
          right(s"$what flat")(Tx.fromEnvelope(bytes, Era.Conway)),
          right(s"$what typed")(ConwayTx.fromEnvelope(bytes, StateLayout.Node11_1)),
          stored,
          right(s"$what typed UTxO")(ConwayUTxO.fromStored(stored)),
          PParams[Conway](
            right(s"$what typed parameters")(ConwayPParams.fromStored(ledger.params))
          ),
          ledger,
          rc.tipSlot + 1,
          c.build,
          rc.families,
          rc.reason,
          rc.before.nonEmpty
        )
      }
    }

  /** `s` with both forms of the parameters at major version `major`. */
  private def atVersion(s: Seen, major: Int): Seen =
    val flatPp = s.ledger.params.copy(protocolMajor = BigInt(major))
    val ls = s.ledger.ls
    s.copy(
      label = s"${s.label}@v$major",
      pp = PParams[Conway](right("edited parameters")(ConwayPParams.fromStored(flatPp))),
      ledger = s.ledger.copy(ls =
        ls.copy[Conway](utxo = ls.utxo.copy(gov = ls.utxo.gov.copy(curPParams = flatPp)))
      )
    )

  private def versions(s: Seen): Vector[Seen] = s +: Vector(9, 10, 11).map(atVersion(s, _))

  /** One clock for both paths' script contexts: the corpus records no era history, and the
    * comparison is of the two rules, not of the scripts' time.
    */
  private val clock = xyz.kd5ujc.scarab.ledger.PosixClock.Total(slot => slot * 1000)

  /** An era history of 1-second slots and 100-slot epochs at a tip `tip`. */
  private def historyAt(tip: BigInt): Horizon =
    val params = Horizon.Params(100, 1000, 30, 30)
    Horizon
      .History(Vector.empty, Horizon.Edge(0, 0, 0), params, Some(params))
      .at(Horizon.Transition.Unknown(Some(tip)))

  private def globalsOf(s: Seen, build: ReferenceBuild): Globals =
    s.globals.copy(ledger = s.globals.ledger.copy(referenceBuild = build))

  private def stAnnTx(s: Seen): AlonzoStAnnTx[Conway] =
    AlonzoStAnnTx.mkAlonzoStAnnTx[Conway](clock.posix, s.pp, s.utxo, Map.empty, s.typed)

  private def context(s: Seen, build: ReferenceBuild, validation: TxEnv.Validation) =
    UtxoContext(
      s.netId,
      xyz.kd5ujc.scarab.ledger.SystemStart(java.time.Instant.EPOCH),
      historyAt(s.slot),
      validation,
      ConwayUtxo.injectShelleyFailure(build)
    )

  /** LEDGER's environment of `s`: its slot and epoch, the first index, its parameters and the
    * chain's accounts, of which LEDGER reads the treasury alone (the mempool's ledger keeps no
    * reserves).
    */
  private def ledgerEnv(s: Seen): LedgerEnv[Conway] =
    LedgerEnv[Conway](
      s.slot,
      Some(right("epoch")(EpochNo.fromBigInt(s.ledger.epoch))),
      TxIx.txIxFromIntegral(0).getOrElse(fail("index 0")),
      s.pp,
      ChainAccountState(s.ledger.treasury, Coin.zero)
    )

  /** The era-typed LEDGER on `s`, `depth` levels below the root. */
  private def eraLedger(
      s: Seen,
      build: ReferenceBuild,
      validation: TxEnv.Validation,
      depth: Int = 0
  ) =
    EraLedgerRun.ledger(
      globalsOf(s, build),
      ledgerEnv(s),
      stAnnTx(s),
      context(s, build, validation),
      s.ledger.ls,
      xyz.kd5ujc.scarab.plutus.ScarabEvaluator.default,
      xyz.kd5ujc.scarab.ledger.Verdicts.Direct,
      xyz.kd5ujc.scarab.plutus.ScarabEvaluator.defaultDecoder,
      depth
    )

  test("the corpus has Conway cases, each read by both paths"):
    assert(clue(seen.size) > 40)

  /** The cases the flat rules judged before they were deleted, which the recordings of their
    * judgments and of their encoder's bytes hold (`fixtures/conway-flat-refusals`): the corpora
    * recorded for the era rules alone aside.
    */
  private lazy val flatSeen: Vector[Seen] = seen.filterNot(s => eraOnly(s.label))

  /** The judgment's cell of the recording (`ledger-judgments.jsonl`) of the era-typed LEDGER on `s`
    * at the root: its refusal's bytes, or the state it leaves, in the Haskell state's encoding of
    * the case's layout, with the counters of the scripts it ran (`EraLedgerRun.counters`).
    */
  private def rootCell(s: Seen, build: ReferenceBuild, validation: TxEnv.Validation): String =
    val what = s"${s.label} under ${build.name}, $validation"
    eraLedger(s, build, validation) match
      case Left(defect)    => fail(s"$what: era defect $defect")
      case Right(Left(fs)) => FlatRecordings.refused(fs.toChain.toVector)
      case Right(Right((state, events))) =>
        val (succeeded, failed, scripts) = EraLedgerRun
          .counters(
            EraTx.conway.isValid(s.typed) == xyz.kd5ujc.scarab.ledger.era.IsPhase2Valid.Phase2Valid,
            events
          )
          .getOrElse(fail(s"$what: events $events"))
        FlatRecordings.admitted(state, succeeded, failed, scripts, FlatRecordings.layoutOf(s.build))

  test("conwayLedgerTransition gives the flat LEDGER's recorded judgments on every Conway case"):
    // the flat LEDGER, before it was deleted, judged each case at the root at its recorded protocol
    // version and at 9, 10 and 11, under each build and policy, and the era LEDGER gave the same
    // failures in the same order, or the same ledger state and counters (where the 11.2 and Leios
    // builds refuse a withdrawal in LEDGER below version 11, the flat failures with CERTS's refusal
    // of it replaced by LEDGER's, `MovedWithdrawals.expected`, since deleted); the era LEDGER's judgments were
    // recorded (`fixtures/conway-flat-refusals`): 1,872 refusals and 144 admissions
    val recording = FlatRecordings.judgments("ledger-checks/")
    val judged = flatSeen.map { s0 =>
      val cells = for
        s <- versions(s0)
        build <- ReferenceBuild.values.toVector
        validation <- TxEnv.Validation.values.toVector
      yield rootCell(s, build, validation)
      val id = s"ledger-checks/${s0.label}"
      assertEquals(cells, recording.getOrElse(id, fail(s"no recording of $id")), s0.label)
      cells
    }
    assertEquals(
      recording.keySet,
      flatSeen.map(s => s"ledger-checks/${s.label}").toSet,
      "the cases"
    )
    val cells = judged.flatten
    assertEquals(cells.count(_.startsWith("R:")), 1872, "the refusals compared")
    assertEquals(cells.count(_.startsWith("A:")), 144, "the admissions compared")
    // a case with three failures among them
    assert(
      seen.exists(s =>
        eraLedger(s, s.build, TxEnv.Validation.All) match
          case Right(Left(fs)) => fs.length >= 3
          case _               => false
      ),
      "no case with three failures"
    )

  test(
    "below version 11 the 11.2 and Leios builds refuse a withdrawal in LEDGER, on the moved withdrawals' cases alone"
  ):
    // `hardforkConwayMoveWithdrawalsAndDRepChecksToLedgerRule` at those builds' pins; the flat
    // rules refused these in CERTS (`MovedWithdrawals`)
    def ledgersOwn(fs: Vector[LedgerPredFailure[Conway]]): Option[MovedWithdrawals.Kind] =
      fs.collectFirst {
        case ConwayLedgerPredFailure.ConwayWithdrawalsMissingAccounts(_) =>
          MovedWithdrawals.Kind.Missing
        case ConwayLedgerPredFailure.ConwayIncompleteWithdrawals(_) =>
          MovedWithdrawals.Kind.Incomplete
      }
    // of the corpora the flat rules judged, the difference being theirs
    def moved(builds: Vector[ReferenceBuild]) =
      for
        s0 <- flatSeen
        s <- versions(s0)
        if s.ledger.params.protocolMajor < 11
        build <- builds
        validation <- TxEnv.Validation.values.toVector
        kind <- eraLedger(s, build, validation) match
          case Right(Left(fs)) => ledgersOwn(fs.toChain.toVector)
          case _               => None
      yield s0.label -> kind
    val found = moved(Vector(ReferenceBuild.Release11_2, ReferenceBuild.Leios))
    assertEquals(found.toMap, MovedWithdrawals.Cases, "the cases of the moved withdrawals")
    // each at its recorded version (below 11), 9 and 10, under two builds and two policies
    assertEquals(found.size, MovedWithdrawals.Cases.size * 3 * 2 * 2, "their judgments")
    assertEquals(moved(Vector(ReferenceBuild.Release11_1)), Vector.empty, "under 11.1")

  test("the recording of the flat LEDGER's judgments holds those of its five suites alone"):
    // each suite holds its own prefix to the judgments it makes; no other id is there
    val prefixes = Map(
      "ledger-checks/" -> 84,
      "gov-after-certs/" -> 1,
      "era-utxo-ledger/" -> 140,
      "preview-blocks/" -> 10,
      "preview-rules/" -> 22
    )
    assertEquals(
      prefixes.keys.toVector.map(p => p -> FlatRecordings.judgments(p).size).toMap,
      prefixes
    )
    assertEquals(FlatRecordings.ledgerJudgments.size, prefixes.values.sum, "every id")

  /** The cases the mempool refuses before or beside LEDGER: its measure's (`txMeasureAlonzo`,
    * `Shelley/Ledger/Mempool.hs:590-612`, ouroboros-consensus 82ecba32) and MEMPOOL's own
    * (`ConwayMempoolFailure`, `Conway/Rules/Mempool.hs`, cardano-ledger f649f975). Their recorded
    * refusal is not LEDGER's, so the byte comparison leaves them out, by name.
    */
  private val NotLedgers: Set[String] = Set(
    "conway-devnet/tx-too-large",
    "conway-devnet/tx-too-large-spent-inputs",
    "conway-devnet/utxo-mint-units-too-big",
    "conway-devnet/all-inputs-spent",
    "conway-constructors/mempool-inputs-empty",
    "conway-mempool/mempool-unelected-committee-voter",
    "conway-mempool/mempool-unelected-voters-with-ledger-failures"
  )

  test("conwayLedgerTransition's refusal is the reference's, byte for byte, on every LEDGER case"):
    // LEDGER one level down, under MEMPOOL, where the reference reports its failures in their
    // order; every other case is LEDGER's, the combined ones among them
    // and a case the reference judged after admitting items first is replayed by
    // `ReferenceRefusalSuite`, which admits them too
    val (left, compared) =
      seen.filterNot(_.afterAdmitted).partition(s => NotLedgers.contains(s.label))
    assertEquals(left.map(_.label).toSet, NotLedgers, "the cases left out are cases")
    assertEquals(
      seen
        .filter(s =>
          xyz.kd5ujc.scarab.node.mempool.Capacity.phase1(s.flat, s.ledger.params).isLeft ||
            s.families.contains(Family.MEMPOOL)
        )
        .map(_.label)
        .toSet,
      NotLedgers,
      "the measure and MEMPOOL refuse other cases than those named"
    )
    Vector("conway-devnet/certs-gov-utxow", "conway-devnet/gov-proposal-wrong-deposit").foreach(l =>
      assert(compared.exists(_.label == l), s"$l is not compared")
    )
    compared.foreach { s =>
      val era = eraLedger(s, s.build, TxEnv.Validation.All, depth = 1) match
        case Right(Left(fs)) => fs.toChain.toVector
        case other           => fail(s"${s.label}: $other")
      // the era failures written by their own encoder
      assertEquals(ConwayFailureCborCheck.direct(era).hex, s.reason.hex, s.label)
    }

  test(
    "the era failures' bytes are those the flat encoder wrote, on every case, version, build, policy and depth"
  ):
    // the flat rules' encoder, before it was deleted, wrote each refusal of these judgments, and
    // the era encoder wrote the same bytes (`fixtures/conway-flat-refusals`): 3,744 refusals, of
    // the corpora recorded before it was deleted
    val recorded = FlatRecordings.ledgerChecks
    assertEquals(recorded.keySet, flatSeen.map(_.label).toSet, "the cases recorded")
    val cells = flatSeen.flatMap { s0 =>
      val judged = for
        s <- versions(s0)
        build <- ReferenceBuild.values.toVector
        validation <- TxEnv.Validation.values.toVector
        depth <- Vector(0, 1)
      yield eraLedger(s, build, validation, depth) match
        case Right(Left(fs)) =>
          FlatRecordings.digest(ConwayFailureCborCheck.direct(fs.toChain.toVector))
        case Right(Right(_)) => "-"
        case Left(defect)    => fail(s"${s.label} under ${build.name}: defect $defect")
      assertEquals(judged, recorded(s0.label), s0.label)
      judged
    }
    assertEquals(cells.count(_ != "-"), 3744, "the refusals compared")
    assertEquals(cells.size, flatSeen.size * 48, "the judgments compared")

  /** The cases that register a new pool. The era POOL writes a new pool's state in the layout of
    * the build whose rules it follows: 11.2's layout is not implemented
    * (`ReferenceBuildNotYet.Layout`), and the Leios build's is not that of the recorded state,
    * 11.1's (`LedgerFailure.PoolParamsLayout`). Under those two builds the era LEDGER reaches no
    * verdict on them, a defect each, named here; under 11.1, the corpora's own, it refuses them
    * (`ReferenceRefusalSuite` compares the refusals with the reference's).
    */
  private val NewPools: Set[String] = Set(
    "conway-constructors/pool-reward-account-wrong-network",
    "conway-constructors/pool-metadata-hash-too-big",
    "conway-constructors-v11/pool-vrf-key-registered-v11",
    "conway-constructors-v11/pool-cost-too-low-v11"
  )

  test("a new pool under the 11.2 and Leios builds' rules is a defect of the pool's layout, named"):
    val registering = seen.filter(s => NewPools.contains(s.label))
    assertEquals(registering.map(_.label).toSet, NewPools)
    for
      s0 <- registering
      s <- versions(s0)
      validation <- TxEnv.Validation.values.toVector
    do
      val what = s"${s.label}, $validation"
      assertEquals(
        eraLedger(s, ReferenceBuild.Release11_2, validation).left.toOption,
        Some(
          EraLedgerRun.Defect.Ledger(
            xyz.kd5ujc.scarab.ledger.LedgerFailure.LayoutNotYet(
              xyz.kd5ujc.scarab.protocol.ReferenceBuildNotYet.Layout(ReferenceBuild.Release11_2)
            )
          )
        ),
        s"$what under 11.2"
      )
      eraLedger(s, ReferenceBuild.Leios, validation) match
        case Left(
              EraLedgerRun.Defect.Ledger(
                xyz.kd5ujc.scarab.ledger.LedgerFailure
                  .PoolParamsLayout(_, StateLayout.Node11_1, StateLayout.Leios)
              )
            ) =>
          ()
        case other => fail(s"$what under the Leios build: $other")
      assert(eraLedger(s, ReferenceBuild.Release11_1, validation).isRight, s"$what under 11.1")

  test("LEDGERS of one transaction is its LEDGER one level down, embedded, on every Conway case"):
    seen.foreach { s =>
      val ours = EraLedgerRun.ledgers(
        s.globals,
        LedgersEnv[Conway](
          s.slot,
          right("epoch")(EpochNo.fromBigInt(s.ledger.epoch)),
          s.pp,
          ChainAccountState(s.ledger.treasury, Coin.zero)
        ),
        Vector(s.typed),
        context(s, s.build, TxEnv.Validation.All),
        s.ledger.ls,
        xyz.kd5ujc.scarab.plutus.ScarabEvaluator.default,
        xyz.kd5ujc.scarab.ledger.Verdicts.Direct,
        xyz.kd5ujc.scarab.plutus.ScarabEvaluator.defaultDecoder,
        clock.posix
      )
      val alone = eraLedger(s, s.build, TxEnv.Validation.All, depth = 1)
      (ours, alone) match
        case (Right(Left(fs)), Right(Left(gs))) =>
          // by their bytes: a script's failure holds terms, equal only as one object
          assertEquals(
            ConwayFailureCborCheck
              .direct(fs.toChain.toVector.map { case ShelleyLedgersPredFailure.LedgerFailure(f) =>
                f
              })
              .hex,
            ConwayFailureCborCheck.direct(gs.toChain.toVector).hex,
            s.label
          )
        case (Right(Right((a, _))), Right(Right((b, _)))) =>
          assert(cats.Eq[LedgerState[Conway]].eqv(a, b), s.label)
        case other => fail(s"${s.label}: $other")
    }

  // ---- GOV with the certificate state after CERTS

  private def leaf(v: Value): Node = Node(v, Bytes.empty)
  private def uint(n: BigInt): Node = leaf(Value.UInt(n))
  private def bytesN(b: Bytes): Node = leaf(Value.ByteString(b))
  private def arr(xs: Node*): Node = leaf(Value.Arr(xs.toVector))
  private def isKey(k: Int)(n: Node): Boolean = n.value == Value.UInt(k)

  /** `s` with its body's fields edited by `f`, read again by both decoders. */
  private def edited(s: Seen)(f: Vector[(Node, Node)] => Vector[(Node, Node)]): Seen =
    val root = right("envelope")(Cbor.decode(s.bytes))
    val again = root.value match
      case Value.Arr(parts) =>
        parts.head.value match
          case Value.Map(fields) =>
            right("re-encode")(
              Cbor.encode(
                Value.Arr(leaf(Value.Map(f(fields))) +: parts.tail),
                Cbor.Limits.Unbounded
              )
            )
          case other => fail(s"a body that is not a map: $other")
      case other => fail(s"an envelope that is not an array: $other")
    s.copy(
      bytes = again,
      flat = right("edited flat")(Tx.fromEnvelope(again, Era.Conway)),
      typed = right("edited typed")(ConwayTx.fromEnvelope(again, StateLayout.Node11_1))
    )

  test("GOV judges the votes with the certificate state after CERTS"):
    // a registered DRep unregisters and votes in one transaction: GOV, given the state after the
    // certificates (`Conway/Rules/Ledger.hs:409-421`, cardano-ledger f649f975), finds no such
    // voter, where the state before the transaction holds it
    // the first such state of the corpora the flat rules judged, the one the recording is of
    val base = flatSeen
      .find(s =>
        s.ledger.ls.cert.vState.dreps.nonEmpty && s.ledger.ls.utxo.gov.proposals.actions.nonEmpty
      )
      .getOrElse(fail("no recorded state with a DRep and a proposal"))
    val (drep, held) = base.ledger.ls.cert.vState.dreps.head
    val (credential, voter) = drep match
      case xyz.kd5ujc.scarab.ledger.state.Credential.KeyHashObj(h) =>
        (arr(uint(0), bytesN(h.bytes)), arr(uint(2), bytesN(h.bytes)))
      case xyz.kd5ujc.scarab.ledger.state.Credential.ScriptHashObj(h) =>
        (arr(uint(1), bytesN(h.bytes)), arr(uint(3), bytesN(h.bytes)))
    val action = base.ledger.ls.utxo.gov.proposals.actions.head.id
    val s = edited(base)(fields =>
      fields.filterNot(f => isKey(4)(f._1) || isKey(19)(f._1) || isKey(20)(f._1)) ++ Vector(
        uint(4) -> arr(arr(uint(17), credential, uint(held.deposit.toBigInt))),
        uint(19) -> leaf(
          Value.Map(
            Vector(
              voter -> leaf(
                Value.Map(
                  Vector(
                    arr(bytesN(action.txId), uint(action.index)) -> arr(uint(1), leaf(Value.Null))
                  )
                )
              )
            )
          )
        )
      )
    )
    val voterOf = xyz.kd5ujc.scarab.ledger.Voter.DRepVoter(
      xyz.kd5ujc.scarab.ledger.rules.Convert.back(drep)
    )
    val notThere: LedgerPredFailure[Conway] = ConwayLedgerPredFailure.ConwayGovFailure[Conway](
      ConwayGovPredFailure.VotersDoNotExist[Conway](cats.data.NonEmptyList.one(voterOf))
    )
    // the flat LEDGER refused it with the same failures in the same order under each build,
    // recorded in the order of the builds
    val recording = FlatRecordings.judgments("gov-after-certs/")
    val id = "gov-after-certs/drep-unregisters-and-votes"
    assertEquals(recording.keySet, Set(id), "the recording")
    val cells = ReferenceBuild.values.toVector.map { build =>
      eraLedger(s, build, TxEnv.Validation.All) match
        case Right(Left(fs)) =>
          assert(fs.toChain.toVector.contains(notThere), s"${build.name}: $fs")
          FlatRecordings.refused(fs.toChain.toVector)
        case other => fail(s"${build.name}: $other")
    }
    assertEquals(cells, recording(id), "the builds' refusals")
    // the contrast: GOV alone, given the state before the certificates, finds the voter
    val tb = EraTx.conway.body[TxLevel.Top](s.typed)(using EraTx.conway.bodyOf.top)
    val b = EraTxBody.conway
    val gov = s.ledger.ls.utxo.gov
    val before = xyz.kd5ujc.scarab.ledger.era.EraGovRun.run(
      xyz.kd5ujc.scarab.ledger.era.rules.GovEnv[Conway](
        right("id")(
          xyz.kd5ujc.scarab.ledger.state.Hash32.fromBytes(b.id[TxLevel.Top](tb)(using b.top))
        ),
        right("epoch")(EpochNo.fromBigInt(s.ledger.epoch)),
        s.pp,
        gov.constitution.script.map(h =>
          right("guardrails")(xyz.kd5ujc.scarab.ledger.state.ScriptHash.fromBytes(h))
        ),
        s.ledger.ls.cert,
        gov.committee
      ),
      xyz.kd5ujc.scarab.ledger.era.EraGovRun.signalOf(s.typed),
      s.netId,
      right("proposals")(xyz.kd5ujc.scarab.ledger.era.EraGovRun.proposals(gov.proposals))
    )
    before match
      case Right(Right(_)) => ()
      case Right(Left(fs)) =>
        assert(
          !fs.exists {
            case ConwayGovPredFailure.VotersDoNotExist(_) => true
            case _                                        => false
          },
          s"GOV before the certificates: $fs"
        )
      case Left(defect) => fail(s"GOV defect: $defect")
