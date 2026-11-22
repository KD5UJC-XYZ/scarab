// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import java.nio.file.Path
import cats.data.NonEmptyVector
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{
  Horizon,
  LedgerFailure,
  MempoolProblem,
  PosixClock,
  PredicateFailure,
  Tx as FlatTx,
  TxEnv,
  TxIn,
  TxOut,
  Unsigned,
  Utxo
}
import xyz.kd5ujc.scarab.ledger.era.{
  EraLedgerRun,
  EraTx,
  EraTxBody,
  EraUTxO,
  EraUtxoRun,
  Network,
  PParams,
  EraPParams,
  ProtVer,
  TxLevel,
  UTxO,
  Version
}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayPParams, ConwayTx}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  ConwayApplyTxError,
  ConwayGovPredFailure,
  ConwayLedgerPredFailure,
  ConwayMempoolRun,
  ConwayUtxowPredFailure,
  ConwayValidated
}
import xyz.kd5ujc.scarab.ledger.rules.Globals
import xyz.kd5ujc.scarab.ledger.state.{
  ChainAccountState,
  Coin,
  Conway,
  LedgerState,
  NewEpochState,
  ShelleyBasedEra,
  UtxoTables
}
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.node.ntc.{PredicateFailureCbor, ReferenceRefusalSuite}
import xyz.kd5ujc.scarab.protocol.{Era, ReferenceBuild, StateLayout}
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, UtxoCodecs}

/** Conway's MEMPOOL on the era types and its run (`ConwayMempoolRun`) against the flat MEMPOOL the
  * node's pool runs (`MempoolLedger.submit` and `MempoolLedger.reapply`): on every Conway case of
  * the recorded refusal corpus, at its recorded protocol version and at 9, 10 and 11, under each
  * build, and on the pool suites' transactions (`TxKit`), the same failures in the same order, or
  * the same ledger state. At the root, as the mempool runs it, the era MEMPOOL's refusal of every
  * corpus case but those of the measure encodes to the reference's byte for byte. The unelected
  * committee voters are MEMPOOL's below version 11 and GOV's from it, and a held transaction is
  * applied again as each build's consensus does, which differ across a major version.
  */
class ConwayMempoolRunSuite extends FunSuite:
  import ReferenceRefusalSuite.*

  private given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway

  private def right[E, A](what: String)(e: Either[E, A]): A =
    e.fold(x => fail(s"$what: $x"), identity)

  /** A transaction as both paths read it, the state both judge it in, and how. */
  private final case class Seen(
      label: String,
      flat: FlatTx,
      typed: ConwayTx,
      ledger: MempoolLedger,
      slot: BigInt,
      globals: Globals,
      network: Int,
      clock: PosixClock,
      judging: ConwayMempoolRun.Judging,
      build: ReferenceBuild,
      families: Vector[Family],
      reason: Option[Bytes]
  ):
    def utxo: UTxO[Conway] =
      right(s"$label: typed UTxO")(ConwayMempoolRun.utxoOf(ledger.ls, typed))

    def pp: PParams[Conway] =
      PParams[Conway](right(s"$label: typed parameters")(ConwayPParams.fromStored(ledger.params)))

    /** `mkMempoolEnv`: no epoch, the slot's; the chain's treasury, the only account LEDGER reads.
      */
    def env = ConwayMempoolRun.mkMempoolEnv(slot, pp, ChainAccountState(ledger.treasury, Coin.zero))

    def under(b: ReferenceBuild): Seen =
      val g = globals.copy(ledger = globals.ledger.copy(referenceBuild = b))
      copy(build = b, globals = g, judging = judging.copy(globals = g))

    def atVersion(major: Int): Seen =
      val flatPp = ledger.params.copy(protocolMajor = BigInt(major))
      val ls = ledger.ls
      copy(
        label = s"$label@v$major",
        ledger = ledger.copy(ls =
          ls.copy[Conway](utxo = ls.utxo.copy(gov = ls.utxo.gov.copy(curPParams = flatPp)))
        )
      )

    def withLedger(l: MempoolLedger): Seen = copy(ledger = l)

  /** One clock for both paths' script contexts. */
  private val corpusClock = PosixClock.Total(slot => slot * 1000)

  /** An era history of 1-second slots and 100-slot epochs at a tip `tip`. */
  private def historyAt(tip: BigInt): Horizon =
    val params = Horizon.Params(100, 1000, 30, 30)
    Horizon
      .History(Vector.empty, Horizon.Edge(0, 0, 0), params, Some(params))
      .at(Horizon.Transition.Unknown(Some(tip)))

  private def networkOf(n: Int): Network =
    Network.of(BigInt(n)).getOrElse(fail(s"network $n"))

  private val corpus: Vector[Seen] =
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
        val ledger = right(s"$what tick")(MempoolLedger.tick(withUtxo, Some(rc.tipSlot), env))
        val slot = rc.tipSlot + 1
        Seen(
          what,
          right(s"$what flat")(FlatTx.fromEnvelope(bytes, Era.Conway)),
          right(s"$what typed")(ConwayTx.fromEnvelope(bytes, StateLayout.Node11_1)),
          ledger,
          slot,
          env.rules,
          setup.globals.network,
          corpusClock,
          ConwayMempoolRun.Judging(
            env.rules,
            networkOf(setup.globals.network),
            0,
            historyAt(slot),
            corpusClock.posix,
            xyz.kd5ujc.scarab.plutus.ScarabEvaluator.default,
            xyz.kd5ujc.scarab.ledger.Verdicts.Direct,
            xyz.kd5ujc.scarab.plutus.ScarabEvaluator.defaultDecoder
          ),
          c.build,
          rc.families,
          Some(rc.reason)
        )
      }
    }

  // ---- the comparison

  /** The purposes of `s`'s scripts needed in their flat form, for a missing redeemer's failure. */
  private def purposes(s: Seen) =
    val u = EraUTxO.conway
    val t = EraTx.conway
    ConwayUtxowPredFailure.storedPurposes(
      u.getScriptsNeeded[TxLevel.Top](s.utxo, t.body[TxLevel.Top](s.typed)(using t.bodyOf.top))(
        using u.txOf.bodyOf.top
      ),
      s.utxo
    )

  /** An action of a GOV failure as `s`'s proposals read it. */
  private def readOf(s: Seen) =
    val t = EraTx.conway
    val b = EraTxBody.conway
    ConwayGovPredFailure.readOf(
      b.proposalProcedures[TxLevel.Top](t.body[TxLevel.Top](s.typed)(using t.bodyOf.top))(using
        b.top
      ).toVector
    )

  /** An era failure in the comparison's form, through the node's flat form. */
  private def eraForm(s: Seen)(f: ConwayLedgerPredFailure[Conway]): EraUtxoRun.Failure =
    EraLedgerRun.form(f, purposes(s), readOf(s)).getOrElse(fail(s"${s.label}: $f"))

  /** An era failure in the node's flat form (`EraLedgerRun.flat`). */
  private def flatOf(s: Seen)(f: ConwayLedgerPredFailure[Conway]): PredicateFailure =
    right(s"${s.label}: flat form")(EraLedgerRun.flat(f, purposes(s), readOf(s)))

  /** What a whole rule made of a case: its failures in the reference's order, in the comparison's
    * form, or the ledger state after.
    */
  private enum Whole:
    case Refused(failures: List[EraUtxoRun.Failure])
    case Admitted(state: LedgerState[Conway])

  private def eraWhole(s: Seen, what: String)(
      outcome: ConwayMempoolRun.Outcome[LedgerState[Conway]]
  ): Whole = outcome match
    case Left(defect)                        => fail(s"$what: era defect $defect")
    case Right(Left(ConwayApplyTxError(fs))) => Whole.Refused(fs.toChain.toList.map(eraForm(s)))
    case Right(Right(state))                 => Whole.Admitted(state)

  private def flatWhole(what: String)(outcome: Either[LedgerFailure, MempoolLedger]): Whole =
    outcome match
      case Left(LedgerFailure.Rejected(_, fs)) =>
        Whole.Refused(fs.toChain.toList.map(f => EraUtxoRun.form(f).getOrElse(fail(s"$what: $f"))))
      case Left(other)   => fail(s"$what: flat defect $other")
      case Right(ledger) => Whole.Admitted(ledger.ls)

  private def txEnv(s: Seen, validation: TxEnv.Validation): TxEnv =
    TxEnv(
      slot = s.slot,
      epoch = s.ledger.epoch,
      network = s.network,
      evaluator = xyz.kd5ujc.scarab.plutus.ScarabEvaluator.default,
      decoder = xyz.kd5ujc.scarab.plutus.ScarabEvaluator.defaultDecoder,
      validation = validation,
      referenceBuild = s.build,
      clock = Some(s.clock)
    )

  /** The era MEMPOOL's application (`applyTx`) and the flat one's (`submit`) on `s`. */
  private def submitted(s: Seen): (Whole, Whole) =
    val what = s"${s.label} under ${s.build.name}, applyTx"
    (
      eraWhole(s, what)(
        ConwayMempoolRun.applyTx(s.judging, s.env, s.ledger.ls, s.typed).map(_.map(_._1))
      ),
      flatWhole(what)(s.ledger.submit(s.flat, txEnv(s, TxEnv.Validation.All), s.globals))
    )

  /** The era MEMPOOL's reapplication by the build (`reapply`: `reapplyTx` under 11.1, under 11.2
    * and Leios `reapplyValidatedTx`, here on the annotation of its validation at the state's
    * version) of `s` 's transaction held as validated in this state, and the flat one's
    * (`reapply`).
    */
  private def reapplied(s: Seen): (Whole, Whole) =
    val what = s"${s.label} under ${s.build.name}, reapply"
    (
      eraWhole(s, what)(ConwayMempoolRun.reapply(s.judging, s.env, s.ledger.ls, held(s))),
      flatWhole(what)(s.ledger.reapply(s.flat, txEnv(s, TxEnv.Validation.NonStatic), s.globals))
    )

  /** `s` 's transaction as held after a validation at `version`, the environment's by default
    * (`unsafeMakeValidatedTx`, `Shelley/API/Mempool.hs:131-151`, cardano-ledger f649f975, which the
    * reference keeps for tests): annotated against the state, whatever its verdict.
    */
  private def held(s: Seen, version: Option[ProtVer] = None): ConwayValidated =
    val pp = s.env.ledgerPp
    ConwayValidated(
      ConwayMempoolRun.annotate(s.judging.slotToPOSIXTime, pp, s.utxo, s.typed),
      version.getOrElse(EraPParams.conway.ppProtocolVersionL.get(pp)),
      s.slot
    )

  private def major(v: Version): ProtVer = ProtVer(v, Unsigned.lit[Unsigned.Width.W32](0))

  /** The 11.2 and Leios builds check the withdrawals in LEDGER at every version, the flat rules in
    * CERTS below 11 under every build: the named difference
    * (https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-7055304484), where a withdrawal
    * fails. `ConwayLedgerChecksSuite` compares each such failure with the reference's form.
    */
  private def isCertsWithdrawal(f: EraUtxoRun.Failure): Boolean = f match
    case EraUtxoRun.Failure.Utxo(
          PredicateFailure.Certs(xyz.kd5ujc.scarab.ledger.CertsFailure.WithdrawalsNotInRewards(_))
        ) =>
      true
    case _ => false

  private def isLedgerWithdrawal(f: EraUtxoRun.Failure): Boolean = f match
    case EraUtxoRun.Failure.Utxo(
          PredicateFailure.WithdrawalsMissingAccounts(_) | PredicateFailure.IncompleteWithdrawals(_)
        ) =>
      true
    case _ => false

  private def moved(s: Seen, flat: Whole): Boolean =
    s.build != ReferenceBuild.Release11_1 && s.ledger.params.protocolMajor < 11 && (flat match
      case Whole.Refused(fs) => fs.exists(isCertsWithdrawal)
      case Whole.Admitted(_) => false)

  /** How the two paths agreed on a case. */
  private enum Agreed derives CanEqual:
    case Admitted, Refused, Moved

  /** `era` and `flat` agree: the same failures in the same order, or the same state; where the
    * withdrawals moved, every failure but the moved one's.
    */
  private def agree(s: Seen, what: String)(era: Whole, flat: Whole): Agreed =
    (era, flat) match
      case (Whole.Admitted(ours), Whole.Admitted(theirs)) =>
        assert(cats.Eq[LedgerState[Conway]].eqv(ours, theirs), s"$what: the states")
        Agreed.Admitted
      case (Whole.Refused(ours), Whole.Refused(theirs)) if moved(s, flat) =>
        assertEquals(ours.count(isLedgerWithdrawal), 1, s"$what: LEDGER's withdrawal")
        assertEquals(ours.filterNot(isLedgerWithdrawal), theirs.filterNot(isCertsWithdrawal), what)
        Agreed.Moved
      case _ =>
        assertEquals(era, flat, what)
        Agreed.Refused

  private def versions(s: Seen): Vector[Seen] = s +: Vector(9, 10, 11).map(s.atVersion)

  test("the corpus has Conway cases, each read by both paths"):
    assert(clue(corpus.size) > 40)

  test("applyTx and reapply give the flat MEMPOOL's verdicts on every Conway case, each build"):
    val judged = for
      s0 <- corpus
      v <- versions(s0)
      build <- ReferenceBuild.values.toVector
      s = v.under(build)
      (what, (era, flat)) <- Vector(
        s"${s.label} under ${build.name}, applyTx" -> submitted(s),
        s"${s.label} under ${build.name}, reapply" -> reapplied(s)
      )
    yield (s0.label, what, agree(s, what)(era, flat), era)
    assert(judged.exists(_._3 == Agreed.Admitted), "no admitted case")
    assert(
      judged.exists {
        case (_, _, _, Whole.Refused(fs)) => fs.size >= 3
        case _                            => false
      },
      "no case with three failures"
    )
    assertEquals(judged.size, corpus.size * 4 * ReferenceBuild.values.size * 2)
    // the moved withdrawals: three cases, each at its recorded version (below 11), 9 and 10,
    // under two builds and by both applications
    val moved = judged.filter(_._3 == Agreed.Moved)
    assertEquals(
      moved.map(_._1).toSet,
      Set(
        "conway-devnet/withdrawal-more-than-balance",
        "conway-devnet/withdrawal-unregistered-account",
        "conway-devnet/withdrawal-network-flag-true"
      )
    )
    assertEquals(moved.size, 3 * 3 * 2 * 2)

  /** The cases the mempool refuses by its measure (`txMeasureAlonzo`, ouroboros-consensus 82ecba32
    * `Shelley/Ledger/Mempool.hs:590-612`), before MEMPOOL: their recorded refusal is not MEMPOOL's.
    */
  private val Measured: Set[String] = Set(
    "conway-devnet/tx-too-large",
    "conway-devnet/tx-too-large-spent-inputs",
    "conway-devnet/utxo-mint-units-too-big"
  )

  test("applyTx's refusal at the root is the reference's, byte for byte, on every MEMPOOL case"):
    val (left, compared) =
      corpus.partition(s => Capacity.phase1(s.flat, s.ledger.params).isLeft)
    assertEquals(left.map(_.label).toSet, Measured, "the cases the measure refuses")
    assert(compared.exists(_.families == Vector(Family.MEMPOOL)), "no case of MEMPOOL's own")
    compared.foreach { recorded =>
      // under the corpus's own build
      val s = recorded.under(recorded.build)
      val failures = ConwayMempoolRun.applyTx(s.judging, s.env, s.ledger.ls, s.typed) match
        case Right(Left(ConwayApplyTxError(fs))) => fs.toChain.toVector.map(flatOf(s))
        case other                               => fail(s"${s.label}: $other")
      assertEquals(
        PredicateFailureCbor.applyTxError(failures).fold(e => fail(s"${s.label}: $e"), _.hex),
        s.reason.getOrElse(fail(s"${s.label}: no reason")).hex,
        s.label
      )
    }

  // ---- the pool suites' transactions

  import TxKit.{ada, alice, applied, bob, bstr, head, tx}
  import xyz.kd5ujc.scarab.ledger.TestLedger.{key, txIn}

  /** The pool's environment of `TxKit`, judged at `slot`, in the state's epoch (5: 1000-slot
    * epochs), so that `mkMempoolEnv` 's epoch of the slot is the flat environment's epoch of the
    * state.
    */
  private val kitSlot: BigInt = 5001

  private def kit(label: String, raw: Bytes, ledger: MempoolLedger): Seen =
    val env = TxKit.env
    val clock = PosixClock.total(env.geometry)
    val g = env.geometry
    Seen(
      label,
      right(s"$label flat")(FlatTx.fromEnvelope(raw, Era.Conway)),
      right(s"$label typed")(ConwayTx.fromEnvelope(raw, StateLayout.Node11_1)),
      ledger,
      kitSlot,
      env.rules,
      env.globals.network,
      clock,
      ConwayMempoolRun.Judging(
        env.rules,
        networkOf(env.globals.network),
        BigInt(g.systemStart.toEpochMilli),
        Horizon(
          NonEmptyVector.one(
            Horizon.Era(
              Horizon.Edge(0, 0, 0),
              None,
              Horizon.Params(g.epochLength, g.slotLengthMillis, 0, 0)
            )
          )
        ),
        clock.posix,
        env.evaluator,
        env.verdicts,
        env.decoder
      ),
      env.globals.referenceBuild,
      Vector.empty,
      None
    )

  /** A vote by `hot`, an unelected committee hot key, `{ [0, hot]: { [action, 0]: [1, null] } }`.
    */
  private val hot = key()
  private val unelectedVote: Vector[Byte] =
    val action = Blake2b.hash256.hash(Bytes(Vector(7)))
    head(5, 1) ++ head(4, 2) ++ head(0, 0) ++ bstr(hot.hash) ++ head(5, 1) ++
      head(4, 2) ++ bstr(action) ++ head(0, 0) ++ head(4, 2) ++ head(0, 1) ++ Vector(0xf6.toByte)

  /** A Plutus script of a recorded preprod transaction, as `MempoolSuite` takes one. */
  private val plutusScript: (xyz.kd5ujc.scarab.ledger.ScriptKind, Bytes) =
    val fixture = xyz.kd5ujc.scarab.ledger.PlutusCase
      .load(Path.of("fixtures/preprod/plutus/tx-2edd684f.json"))
    val script =
      (fixture.tx.witnesses.scripts ++ fixture.references.values.flatMap(_.referenceScript))
        .find(_.kind.isPlutus)
        .getOrElse(fail("fixture has no Plutus script"))
    script.kind -> script.plutus.getOrElse(fail("fixture discarded script bytes"))

  /** Alice pays bob from `txIn(1)` with her witness's signature not over the body: the static check
    * of the signatures (`validateVerifiedWits`, run on the signal only) refuses it.
    */
  private val forged: Bytes =
    val (raw, id) = TxKit.pay(1)
    val signature = alice.sign(id).value
    val at = raw.value.indexOfSlice(signature)
    assert(at > 0 && raw.value.indexOfSlice(signature, at + 1) < 0, "the signature, once")
    Bytes(raw.value.updated(at, (raw.value(at) ^ 0x01).toByte))

  /** Transactions of the kinds `MempoolSuite` 's and `EraMempoolSuite` 's are, built with `TxKit`
    * over its Conway ledger: payments, refusals of MEMPOOL, UTXO and UTXOW, a validity interval, a
    * reference input, an output with a reference script, a stake registration and a vote.
    */
  private def kitCases: Vector[Seen] =
    val l3 = TxKit.ledger(3)
    val (paid, _) = TxKit.pay(1)
    val afterPaid =
      l3.applied(FlatTx.fromEnvelope(paid, Era.Conway).getOrElse(fail("paid")), kitSlot)
    Vector(
      kit("pay", paid, l3),
      kit("fee-too-small", TxKit.pay(1, fee = 1)._1, l3),
      kit("all-inputs-spent", TxKit.pay(9)._1, l3),
      kit("all-inputs-spent-fee-too-small", TxKit.pay(9, fee = 1)._1, l3),
      kit("spent-again", TxKit.pay(1, fee = 2 * ada)._1, afterPaid),
      kit("after-another", TxKit.pay(2)._1, afterPaid),
      kit(
        "not-signed-by-owner",
        tx(Vector(txIn(2)), Vector(bob.address() -> (99 * ada)), ada, Vector(bob))._1,
        l3
      ),
      kit("forged-signature", forged, l3),
      kit(
        "reference-script-output",
        tx(
          Vector(txIn(1)),
          Vector(bob.address() -> (99 * ada)),
          ada,
          Vector(alice),
          referenceScript = Some(plutusScript)
        )._1,
        l3
      ),
      kit(
        "stake-registration",
        tx(
          Vector(txIn(1)),
          Vector(bob.address() -> BigInt(97000000)),
          ada,
          Vector(alice),
          certificates = head(4, 1) ++ head(4, 2) ++ head(0, 0) ++ head(4, 2) ++ head(0, 0) ++
            bstr(Bytes.fromArray(Array.fill(28)(9.toByte)))
        )._1,
        l3
      ),
      kit(
        "expired",
        tx(
          Vector(txIn(1)),
          Vector(bob.address() -> (99 * ada)),
          ada,
          Vector(alice),
          expires = Some(10)
        )._1,
        l3
      ),
      kit(
        "ttl",
        tx(
          Vector(txIn(1)),
          Vector(bob.address() -> (99 * ada)),
          ada,
          Vector(alice),
          expires = Some(100000)
        )._1,
        l3
      ),
      kit(
        "reference-input",
        tx(
          Vector(txIn(1)),
          Vector(bob.address() -> (99 * ada)),
          ada,
          Vector(alice),
          referenceInputs = Vector(txIn(2))
        )._1,
        l3
      ),
      kit(
        "unelected-vote",
        tx(
          Vector(txIn(1)),
          Vector(bob.address() -> (99 * ada)),
          ada,
          Vector(alice, hot),
          votes = unelectedVote
        )._1,
        TxKit.ledger(1)
      )
    )

  test("applyTx and reapply give the flat MEMPOOL's verdicts on the pool suites' transactions"):
    assertEquals(TxKit.ledger(3).epoch, kitSlot / TxKit.env.geometry.epochLength, "the epoch")
    val judged = for
      s0 <- kitCases
      v <- s0 +: Vector(9, 10, 11).map(s0.atVersion)
      build <- ReferenceBuild.values.toVector
      s = v.under(build)
      (what, (era, flat)) <- Vector(
        s"${s.label} under ${build.name}, applyTx" -> submitted(s),
        s"${s.label} under ${build.name}, reapply" -> reapplied(s)
      )
    yield (s0.label, agree(s, what)(era, flat))
    // each transaction's verdict, the same at every version and under every build, but the forged
    // signature's: refused by the static check alone, it is admitted by both reapplications
    val verdicts = judged.groupMapReduce(_._1)(j => Set(j._2))(_ ++ _)
    assertEquals(
      verdicts,
      Map(
        "pay" -> Set(Agreed.Admitted),
        "fee-too-small" -> Set(Agreed.Refused),
        "all-inputs-spent" -> Set(Agreed.Refused),
        "all-inputs-spent-fee-too-small" -> Set(Agreed.Refused),
        "spent-again" -> Set(Agreed.Refused),
        "after-another" -> Set(Agreed.Admitted),
        "not-signed-by-owner" -> Set(Agreed.Refused),
        "forged-signature" -> Set(Agreed.Refused, Agreed.Admitted),
        "expired" -> Set(Agreed.Refused),
        "ttl" -> Set(Agreed.Admitted),
        "reference-input" -> Set(Agreed.Admitted),
        "reference-script-output" -> Set(Agreed.Admitted),
        "stake-registration" -> Set(Agreed.Admitted),
        "unelected-vote" -> Set(Agreed.Refused)
      )
    )
    // a transaction none of whose inputs is unspent: MEMPOOL's failure alone, nothing else checked
    Vector("all-inputs-spent", "all-inputs-spent-fee-too-small", "spent-again").foreach { label =>
      val s = kitCases.find(_.label == label).getOrElse(fail(label))
      assertEquals(
        submitted(s)._1,
        Whole.Refused(
          List(EraUtxoRun.Failure.Utxo(PredicateFailure.Mempool(MempoolProblem.AllInputsSpent)))
        ),
        label
      )
    }

  test(
    "an unelected committee voter: MEMPOOL's failure below protocol version 11, GOV's from it"
  ):
    val s0 = kitCases.find(_.label == "unelected-vote").getOrElse(fail("no vote"))
    def refusal(major: Int) =
      val s = s0.atVersion(major)
      ConwayMempoolRun.applyTx(s.judging, s.env, s.ledger.ls, s.typed) match
        case Right(Left(ConwayApplyTxError(fs))) => fs.toChain.toList.map(flatOf(s))
        case other                               => fail(s"${s.label}: $other")
    val unknown = PredicateFailure.gov(
      xyz.kd5ujc.scarab.ledger.GovFailure.VotersDoNotExist(
        Vector(xyz.kd5ujc.scarab.ledger.Voter.CommitteeHot(hot.credential))
      )
    )
    // MEMPOOL records the unelected voter and runs LEDGER, whose GOV does not know the voter; the
    // root's failures in the reference's order (`Judgment.flatten`): LEDGER's, then MEMPOOL's own
    assertEquals(
      refusal(10),
      List(
        unknown,
        PredicateFailure.Mempool(MempoolProblem.UnelectedCommittee(Vector(hot.credential)))
      )
    )
    // from 11 MEMPOOL does not check it: GOV refuses the unelected voter
    assertEquals(
      refusal(11),
      List(
        unknown,
        PredicateFailure.gov(
          xyz.kd5ujc.scarab.ledger.GovFailure.UnelectedCommitteeVoters(Vector(hot.credential))
        )
      )
    )

  // ---- the reapplication by build

  test("reapply: each build's consensus, which differ where the major version changed"):
    // a transaction only the static checks refuse (a signature that does not verify), held as
    // if validated: the mempool's reapplication skips them
    val base = kitCases.find(_.label == "forged-signature").getOrElse(fail("no case"))
    assert(submitted(base)._1.isInstanceOf[Whole.Refused], "refused by the full application")
    def again(s: Seen, at: Version): Whole =
      eraWhole(s, s"${s.label} under ${s.build.name}, held at ${at.value}")(
        ConwayMempoolRun.reapply(s.judging, s.env, s.ledger.ls, held(s, Some(major(at))))
      )
    val at11 = base.atVersion(11)
    val outcomes = ReferenceBuild.values.toVector.map { build =>
      val s = at11.under(build)
      assertEquals(
        ConwayMempoolRun.Reapply.of(build) == ConwayMempoolRun.Reapply.ReapplyTx,
        build == ReferenceBuild.Release11_1
      )
      // the same major version: the static checks skipped under every build
      assert(again(s, Version.lit(11)).isInstanceOf[Whole.Admitted], s"${build.name}: at 11")
      build -> again(s, Version.lit(10))
    }.toMap
    // another major version: 11.1's `reapplyTx` skips the static checks whatever the version;
    // 11.2's and Leios's `reapplyValidatedTx` validates in full, and the witness is missing
    assert(outcomes(ReferenceBuild.Release11_1).isInstanceOf[Whole.Admitted])
    Vector(ReferenceBuild.Release11_2, ReferenceBuild.LeiosB004fe0c).foreach { build =>
      outcomes(build) match
        case Whole.Refused(fs) =>
          val s = at11.under(build)
          // the full application's refusal
          assertEquals(
            fs,
            submitted(s)._1 match
              case Whole.Refused(gs) => gs
              case other             => fail(s"${build.name}: $other")
          )
        case other => fail(s"${build.name}: $other")
    }
    // the flat pool's reapplication is 11.1's under every build: it admits the transaction held
    // at another major version, where 11.2 and Leios refuse it
    ReferenceBuild.values.foreach { build =>
      val s = at11.under(build)
      assert(reapplied(s)._2.isInstanceOf[Whole.Admitted], s"${build.name}: flat reapply")
    }
