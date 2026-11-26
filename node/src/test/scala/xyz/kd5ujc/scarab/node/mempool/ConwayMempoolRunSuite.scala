// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import java.nio.file.Path
import cats.data.{NonEmptyChain, NonEmptyList, NonEmptySet, NonEmptyVector}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{
  Horizon,
  MempoolProblem,
  PosixClock,
  Tx as FlatTx,
  TxIn,
  TxOut,
  Utxo
}
import xyz.kd5ujc.scarab.ledger.era.{EraUTxO, Mismatch, PParams, TxLevel, UTxO, ValidityInterval}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayPParams, ConwayTx}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  ConwayApplyTxError,
  ConwayGovPredFailure,
  ConwayLedgerPredFailure,
  ConwayMempoolRun,
  ConwayRuleDefect,
  ConwayUtxoPredFailure,
  ConwayUtxowPredFailure,
  UtxoStateDefect
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
import xyz.kd5ujc.scarab.node.ntc.{ConwayFailureCborCheck, FlatRecordings, ReferenceRefusalSuite}
import xyz.kd5ujc.scarab.protocol.{Era, ReferenceBuild, StateLayout}
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, LedgerFileCodecs, UtxoCodecs}
import xyz.kd5ujc.scarab.storage.haskell.state.NodeOrders.given_Order_Bytes

/** Conway's MEMPOOL on the era types and its run (`ConwayMempoolRun`), the rule the node's pool
  * judges Conway's transactions by ([[ConwayPoolLedger]]; `mempoolTransition`,
  * `Conway/Rules/Mempool.hs:91-138` (f649f975)). At the root, as the mempool runs it, its refusal
  * of every case of the recorded refusal corpus but those of the measure encodes to the reference's
  * byte for byte. On the pool suites' transactions (`TxKit`), at their protocol version and at 9,
  * 10 and 11, under each build, applied and applied again, the verdict the reference's rules give,
  * each failure named from the rule that gives it. The unelected committee voters are MEMPOOL's
  * below version 11 and GOV's from it, and a held transaction is applied again as each build's
  * consensus does, which differ across a major version.
  */
class ConwayMempoolRunSuite extends FunSuite:
  import ReferenceRefusalSuite.*

  private given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway

  private def right[E, A](what: String)(e: Either[E, A]): A =
    e.fold(x => fail(s"$what: $x"), identity)

  /** A transaction as both decoders read it, the state Conway's rules judge it in, and how. */
  private final case class Seen(
      label: String,
      raw: Bytes,
      flat: FlatTx,
      typed: ConwayTx,
      ledger: ConwayTicked,
      slot: BigInt,
      globals: Globals,
      judging: ConwayMempoolRun.Judging,
      build: ReferenceBuild,
      families: Vector[Family],
      reason: Option[Bytes],
      afterAdmitted: Boolean = false
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
      copy(
        label = s"$label@v$major",
        ledger = ledger.withParams(ledger.params.copy(protocolMajor = BigInt(major)))
      )

    def withLedger(l: ConwayTicked): Seen = copy(ledger = l)

  /** One clock for the corpus's script contexts. */
  private val corpusClock = PosixClock.Total(slot => slot * 1000)

  /** An era history of 1-second slots and 100-slot epochs at a tip `tip`. */
  private def historyAt(tip: BigInt): Horizon =
    val params = Horizon.Params(100, 1000, 30, 30)
    Horizon
      .History(Vector.empty, Horizon.Edge(0, 0, 0), params, Some(params))
      .at(Horizon.Transition.Unknown(Some(tip)))

  /** The codecs of each corpus case's state, in the layout of the build that recorded it. */
  private lazy val corpusCodecs: Map[String, LedgerFileCodecs] =
    right("the refusal corpus")(load(Path.of("fixtures/reference/refusals"))).corpora
      .filter(_.era == RefusalEra.Conway)
      .flatMap { c =>
        val codecs = right(s"${c.path} layout")(stateCodecs(c.build))
        c.cases.map(rc => s"${c.path}/${rc.label}" -> codecs)
      }
      .toMap

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
        val ledger = right(s"$what tick")(ConwayTicked.ticked(withUtxo, Some(rc.tipSlot), env))
        val slot = rc.tipSlot + 1
        Seen(
          what,
          bytes,
          right(s"$what flat")(FlatTx.fromEnvelope(bytes, Era.Conway)),
          right(s"$what typed")(ConwayTx.fromEnvelope(bytes, StateLayout.Node11_1)),
          ledger,
          slot,
          env.rules,
          ConwayMempoolRun.Judging(
            env.rules,
            setup.globals.network,
            xyz.kd5ujc.scarab.ledger.SystemStart(java.time.Instant.EPOCH),
            historyAt(slot),
            corpusClock.posix,
            xyz.kd5ujc.scarab.plutus.ScarabEvaluator.default,
            xyz.kd5ujc.scarab.ledger.Verdicts.Direct,
            xyz.kd5ujc.scarab.plutus.ScarabEvaluator.defaultDecoder
          ),
          c.build,
          rc.families,
          Some(rc.reason),
          rc.before.nonEmpty
        )
      }
    }

  // ---- what a rule made of a case

  /** What a whole rule made of a case: its failures in the reference's order, or the ledger state
    * after.
    */
  private enum Whole:
    case Refused(failures: List[ConwayLedgerPredFailure[Conway]])
    case Admitted(state: LedgerState[Conway])

  private def eraWhole(what: String)(
      outcome: ConwayMempoolRun.Outcome[LedgerState[Conway]]
  ): Whole = outcome match
    case Left(defect)                        => fail(s"$what: era defect $defect")
    case Right(Left(ConwayApplyTxError(fs))) => Whole.Refused(fs.toChain.toList)
    case Right(Right(state))                 => Whole.Admitted(state)

  /** The era MEMPOOL's application (`applyTx`) on `s`. */
  private def submitted(s: Seen): Whole =
    eraWhole(s"${s.label} under ${s.build.name}, applyTx")(
      ConwayMempoolRun.applyTx(s.judging, s.env, s.ledger.ls, s.typed).map(_.map(_._1))
    )

  /** The era MEMPOOL's reapplication by the build (`reapply`: `reapplyTx` under 11.1, under 11.2
    * and Leios `reapplyValidatedTx`, here on the annotation of its validation at the state's
    * version) of `s` 's transaction held as validated in this state.
    */
  private def reapplied(s: Seen): Whole =
    eraWhole(s"${s.label} under ${s.build.name}, reapply")(
      ConwayMempoolRun.reapply(s.judging, s.env, s.ledger.ls, held(s))
    )

  /** `s` 's transaction held as if validated in its environment and state, whatever its verdict
    * (`unsafeMakeValidatedTx`, `Shelley/API/Mempool.hs:131-151`, cardano-ledger f649f975, which the
    * reference keeps for tests).
    */
  private def held(s: Seen): ConwayMempoolRun.ValidatedTx =
    right(s"${s.label}: held")(
      ConwayMempoolRun.unsafeMakeValidatedTx(s.judging, s.env, s.ledger.ls, s.typed)
    )

  private def versions(s: Seen): Vector[Seen] = s +: Vector(9, 10, 11).map(s.atVersion)

  test("the corpus has Conway cases, each read by both decoders"):
    assert(clue(corpus.size) > 40)
    assertEquals(corpus.flatMap(versions).size, corpus.size * 4)

  /** The cases the mempool refuses by its measure (`txMeasureAlonzo`, ouroboros-consensus 82ecba32
    * `Shelley/Ledger/Mempool.hs:590-612`), before MEMPOOL: their recorded refusal is not MEMPOOL's.
    */
  private val Measured: Set[String] = Set(
    "conway-devnet/tx-too-large",
    "conway-devnet/tx-too-large-spent-inputs",
    "conway-devnet/utxo-mint-units-too-big"
  )

  test("applyTx's refusal at the root is the reference's, byte for byte, on every MEMPOOL case"):
    // a case the reference judged after admitting items first is replayed by
    // `ReferenceRefusalSuite`, which admits them too
    val (left, compared) =
      corpus
        .filterNot(_.afterAdmitted)
        .partition(s => Capacity.phase1(s.flat, s.ledger.params).isLeft)
    assertEquals(left.map(_.label).toSet, Measured, "the cases the measure refuses")
    assertEquals(
      corpus.filter(_.afterAdmitted).map(_.label).toSet,
      Set(
        "conway-mempool/mempool-vote-on-admitted-proposal",
        "conway-mempool/mempool-authorization-after-admitted-proposal"
      ),
      "the cases judged after admitted items"
    )
    assertEquals((corpus.size, compared.size), (112, 107), "the Conway cases, and those compared")
    assert(compared.exists(_.families == Vector(Family.MEMPOOL)), "no case of MEMPOOL's own")
    compared.foreach { recorded =>
      // under the corpus's own build
      val s = recorded.under(recorded.build)
      val failures = ConwayMempoolRun.applyTx(s.judging, s.env, s.ledger.ls, s.typed) match
        case Right(Left(ConwayApplyTxError(fs))) => fs.toChain.toVector
        case other                               => fail(s"${s.label}: $other")
      assertEquals(
        ConwayFailureCborCheck.direct(failures).hex,
        s.reason.getOrElse(fail(s"${s.label}: no reason")).hex,
        s.label
      )
    }

  /** What the era MEMPOOL made of a corpus case, as the recording of the flat MEMPOOL's holds it
    * (`fixtures/conway-flat-refusals/mempool-run.jsonl`): `R:` and the digest of a refusal's bytes
    * (every failure, in its order), or `A:` and that of the ledger state an admission leaves, in
    * the Haskell state's encoding of the case's layout at protocol version 9.
    */
  private def recordedForm(what: String, codecs: LedgerFileCodecs)(
      outcome: ConwayMempoolRun.Outcome[LedgerState[Conway]]
  ): String = outcome match
    case Left(defect) => fail(s"$what: era defect $defect")
    case Right(Left(ConwayApplyTxError(fs))) =>
      "R:" + FlatRecordings.digest(ConwayFailureCborCheck.direct(fs.toChain.toVector))
    case Right(Right(state)) =>
      given xyz.kd5ujc.scarab.ledger.state.ProtVer = xyz.kd5ujc.scarab.ledger.state.ProtVer(
        xyz.kd5ujc.scarab.ledger.state.Word64.fromUnsigned(9),
        xyz.kd5ujc.scarab.ledger.state.Word64.zero
      )
      "A:" + FlatRecordings.digest(
        right(s"$what: the state's bytes")(
          codecs.ledgerState[Conway].write(state)
        )
      )

  test(
    "applyTx and reapply give the flat MEMPOOL's recorded verdicts on every Conway case, version and build"
  ):
    // the flat MEMPOOL, before it was deleted, judged each case at its recorded protocol version
    // and at 9, 10 and 11, under each build, applied and applied again, and the era MEMPOOL gave
    // the same failures in the same order, or the same ledger state (the moved withdrawals as
    // `MovedWithdrawals.expected` has them); the era MEMPOOL's results were recorded. The corpora
    // recorded after the flat MEMPOOL was gone (`ReferenceRefusalSuite.EraOnly`) have no such
    // recording; their reference bytes are checked above
    val recorded = FlatRecordings.mempoolRun
    val flatCorpus = corpus.filterNot(s => ReferenceRefusalSuite.eraOnly(s.label))
    assertEquals(recorded.keySet, flatCorpus.map(_.label).toSet, "the cases recorded")
    val codecsOf = corpusCodecs
    val cells = flatCorpus.flatMap { s0 =>
      val judged = for
        v <- versions(s0)
        build <- ReferenceBuild.values.toVector
        s = v.under(build)
        cell <- Vector(
          recordedForm(s"${s.label} under ${build.name}, applyTx", codecsOf(s0.label))(
            ConwayMempoolRun.applyTx(s.judging, s.env, s.ledger.ls, s.typed).map(_.map(_._1))
          ),
          recordedForm(s"${s.label} under ${build.name}, reapply", codecsOf(s0.label))(
            ConwayMempoolRun.reapply(s.judging, s.env, s.ledger.ls, held(s))
          )
        )
      yield cell
      assertEquals(judged, recorded(s0.label), s0.label)
      judged
    }
    assertEquals(cells.size, flatCorpus.size * 4 * ReferenceBuild.values.size * 2, "the judgments")
    assert(cells.exists(_.startsWith("A:")), "no admission")
    assert(cells.exists(_.startsWith("R:")), "no refusal")

  // ---- the pool suites' transactions

  import TxKit.{ada, alice, bob, bstr, head, tx}
  import xyz.kd5ujc.scarab.ledger.TestLedger.{key, txIn}

  /** TxKit's Conway state with `n` outputs for alice, at protocol version 10. */
  private def kitState(n: Int): ConwayTicked = ConwayTicked.of(TxKit.native(n))

  extension (l: ConwayTicked)
    /** The state after `raw`, which the suite knows to be valid, admitted by the pool's Conway
      * ledger at `slot` ([[TxKit.applied]]).
      */
    private def applied(raw: Bytes, slot: BigInt): ConwayTicked =
      val pool = ConwayPoolLedger
        .of(l.ls, ChainAccountState(l.treasury, Coin.zero), l.epoch, StateLayout.Node11_1)
        .fold(e => fail(s"parameters that are not Conway's: $e"), identity)
      ConwayTicked.of(TxKit.applied(pool)(raw, slot - 1))

  /** The pool's environment of `TxKit`, judged at `slot`, in the state's epoch (5: 1000-slot
    * epochs), so that `mkMempoolEnv` 's epoch of the slot is the state's.
    */
  private val kitSlot: BigInt = 5001

  private def kit(label: String, raw: Bytes, ledger: ConwayTicked): Seen =
    val env = TxKit.env
    val clock = PosixClock.total(env.globals.systemStart, env.geometry)
    val g = env.geometry
    Seen(
      label,
      raw,
      right(s"$label flat")(FlatTx.fromEnvelope(raw, Era.Conway)),
      right(s"$label typed")(ConwayTx.fromEnvelope(raw, StateLayout.Node11_1)),
      ledger,
      kitSlot,
      env.rules,
      ConwayMempoolRun.Judging(
        env.rules,
        env.globals.network,
        env.globals.systemStart,
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
    val l3 = kitState(3)
    val (paid, _) = TxKit.pay(1)
    val afterPaid = l3.applied(paid, kitSlot)
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
        kitState(1)
      )
    )

  private def utxow(f: ConwayUtxowPredFailure[Conway]): ConwayLedgerPredFailure[Conway] =
    ConwayLedgerPredFailure.ConwayUtxowFailure[Conway](f)
  private def utxo(f: ConwayUtxoPredFailure[Conway]): ConwayLedgerPredFailure[Conway] =
    utxow(ConwayUtxowPredFailure.UtxoFailure[Conway](f))

  /** MEMPOOL's own refusal of a transaction none of whose inputs is unspent, alone: nothing else is
    * checked (`whenFailureFreeDefault`, `Conway/Rules/Mempool.hs:113-121` (f649f975)).
    */
  private val inputsSpent = ConwayLedgerPredFailure.ConwayMempoolFailure[Conway](
    MempoolProblem.AllInputsSpent
  )

  /** The refusal of alice's payment with her signature not over the body (`validateVerifiedWits`,
    * `Babbage/Rules/Utxow.hs:366` (f649f975), a static check): her key's witness is invalid.
    */
  private val forgedWitness =
    utxow(ConwayUtxowPredFailure.InvalidWitnessesUTXOW(NonEmptyList.one(alice.vkey)))

  /** The ledger state an admitted transaction of `s` leaves, derived from the transaction and the
    * state before it, and compared whole. `updateUTxOState` (`Shelley/Rules/Utxo.hs:597-626`
    * (f649f975), through Babbage's and Conway's UTXO) takes the inputs out of the UTxO and adds
    * each output at its index under the transaction's id, as the body holds it (`txouts`,
    * `:614-618`), leaving every other output as it was; adds the fee to the fee pot; and adds the
    * deposits less the refunds to the deposit pot (`updateUTxOStateDeposits`, `:641-647`). The
    * instant stake does not change: every address of the cases is an enterprise address, with no
    * stake reference. A stake registration (`ConwayRegCert`, a body's `[0, credential]` among them)
    * registers its credential with the key deposit in force, no balance and no delegation
    * (`registerConwayAccount`, `Conway/Rules/Deleg.hs:233-239` (f649f975)). No case withdraws,
    * votes or proposes, so nothing else changes.
    */
  private def adopted(s: Seen, what: String)(after: LedgerState[Conway]): Unit =
    val body = s.flat.body
    val before = s.ledger.ls
    assert(
      body.withdrawals.isEmpty && body.votes.isEmpty && body.proposals.isEmpty,
      s"$what: a case that withdraws, votes or proposes"
    )
    val registered = body.certificates.map {
      case xyz.kd5ujc.scarab.ledger.Certificate.Register(credential, None) =>
        right(s"$what: credential")(
          xyz.kd5ujc.scarab.ledger.rules.Convert.credential[xyz.kd5ujc.scarab.ledger.state.Staking](
            credential
          )
        )
      case other => fail(s"$what: a certificate the suite does not derive: $other")
    }
    val keyDeposit = s.ledger.params.keyDeposit
    val account = xyz.kd5ujc.scarab.ledger.state.ConwayAccountState(
      xyz.kd5ujc.scarab.ledger.state.CompactCoin.zero,
      right(s"$what: deposit")(xyz.kd5ujc.scarab.ledger.state.CompactCoin.fromBigInt(keyDeposit)),
      None,
      None
    )
    val utxo =
      s.ledger.utxo.toMap -- body.inputs ++
        body.outputs.zipWithIndex.map((out, i) => TxIn(body.id, i) -> out)
    val accounts = before.cert.dState.accounts
    val expected = before.copy[Conway](
      cert = before.cert.copy(dState =
        before.cert.dState
          .copy(accounts = accounts.copy(states = accounts.states ++ registered.map(_ -> account)))
      ),
      utxo = before.utxo.copy(
        utxo = UtxoTables(Utxo.fromMap(utxo)),
        fees = Coin(before.utxo.fees.value + body.fee),
        deposited = Coin(before.utxo.deposited.value + keyDeposit * registered.size)
      )
    )
    // the UTxO by its entries (its handle also keeps the changes it was made by), every entry, the
    // inputs left alone among them; then the rest of the state whole
    assertEquals(after.utxo.utxo.utxo.toMap, utxo, s"$what: the UTxO")
    assertEquals(
      xyz.kd5ujc.scarab.ledger.state
        .Diff[LedgerState[Conway]]
        .diff(after.copy[Conway](utxo = after.utxo.copy(utxo = expected.utxo.utxo)), expected),
      None,
      s"$what: the ledger state"
    )

  /** What the reference's rules give of the pool suites' transaction of `s`, applied with every
    * check (`applyTx`) or again (`reapply`), at every version and under every build: a refusal's
    * failures in the reference's order, each from the check of the rule that gives it, or an
    * admission. Applied again in the state it was held as validated in, a transaction skips the
    * static checks alone (`runTestOnSignal`, `Babbage/Rules/Utxow.hs:366, 374, 379` (f649f975)), so
    * only the forged signature's verdict differs between the two. The unelected voter's failures
    * differ by version; they are named below.
    */
  private def expected(
      label: String,
      s: Seen,
      again: Boolean
  ): Option[List[ConwayLedgerPredFailure[Conway]]] =
    // the minimum fee of a transaction with no redeemers and no reference scripts: its size by
    // `txFeePerByte` plus `txFeeFixed` (`getConwayMinFeeTx`, `Conway/Tx.hs:109-110`;
    // `alonzoMinFeeTx`, `Alonzo/Tx.hs:453-456` (f649f975)), the size `sizeTxF` the bytes of
    // `[body, witnesses, auxiliary data]`, the transaction without its one-byte `is_valid` flag
    // under the same list header (`toCBORForSizeComputation`, `Alonzo/Tx.hs:439-443` (f649f975))
    def minFee = Coin(s.ledger.params.minFeeA * (s.raw.size - 1) + s.ledger.params.minFeeB)
    label match
      case "all-inputs-spent" | "all-inputs-spent-fee-too-small" | "spent-again" =>
        Some(List(inputsSpent))
      // `feesOK` (`Babbage/Rules/Utxo.hs:186-192` (f649f975)): the fee paid, then the minimum
      case "fee-too-small" =>
        Some(List(utxo(ConwayUtxoPredFailure.FeeTooSmallUTxO(Mismatch(Coin(1), minFee)))))
      // `validateNeededWitnesses` (`Babbage/Rules/Utxow.hs:369` (f649f975)): alice's input,
      // signed by bob
      case "not-signed-by-owner" =>
        Some(
          List(utxow(ConwayUtxowPredFailure.MissingVKeyWitnessesUTXOW(NonEmptySet.one(alice.hash))))
        )
      case "forged-signature" if !again => Some(List(forgedWitness))
      case "forged-signature"           => None
      // `validateOutsideValidityIntervalUTxO` (`Allegra/Rules/Utxo.hs:235` (f649f975)): no
      // later than slot 10
      case "expired" =>
        Some(
          List(
            utxo(
              ConwayUtxoPredFailure.OutsideValidityIntervalUTxO(
                ValidityInterval(None, Some(10)),
                kitSlot
              )
            )
          )
        )
      case "pay" | "after-another" | "ttl" | "reference-input" | "reference-script-output" |
          "stake-registration" =>
        None
      case other => fail(s"no expectation of $other")

  test("applyTx and reapply give the reference's verdicts on the pool suites' transactions"):
    assertEquals(kitState(3).epoch, kitSlot / TxKit.env.geometry.epochLength, "the epoch")
    assertEquals(kitState(3).params.protocolMajor, BigInt(10), "the version")
    val judged = for
      s0 <- kitCases
      v <- versions(s0)
      build <- ReferenceBuild.values.toVector
      s = v.under(build)
      (again, whole) <- Vector(false -> submitted(s), true -> reapplied(s))
    yield
      val what = s"${s.label} under ${build.name}, ${if again then "reapply" else "applyTx"}"
      // the unelected voter: refused at every version, its failures named below
      if s0.label != "unelected-vote" then
        (expected(s0.label, s, again), whole) match
          case (Some(failures), Whole.Refused(fs)) => assertEquals(fs, failures, what)
          case (None, Whole.Admitted(after))       => adopted(s, what)(after)
          case (want, got)                         => fail(s"$what: $got, where $want")
      s0.label -> whole.isInstanceOf[Whole.Admitted]
    // each transaction's verdict, the same at every version and under every build, but the forged
    // signature's: refused by the static check alone, it is admitted by both reapplications
    val verdicts = judged.groupMapReduce(_._1)(j => Set(j._2))(_ ++ _)
    assertEquals(
      verdicts,
      Map(
        "pay" -> Set(true),
        "fee-too-small" -> Set(false),
        "all-inputs-spent" -> Set(false),
        "all-inputs-spent-fee-too-small" -> Set(false),
        "spent-again" -> Set(false),
        "after-another" -> Set(true),
        "not-signed-by-owner" -> Set(false),
        "forged-signature" -> Set(false, true),
        "expired" -> Set(false),
        "ttl" -> Set(true),
        "reference-input" -> Set(true),
        "reference-script-output" -> Set(true),
        "stake-registration" -> Set(true),
        "unelected-vote" -> Set(false)
      )
    )
    assertEquals(judged.size, kitCases.size * 4 * ReferenceBuild.values.size * 2)

  test(
    "an unelected committee voter: MEMPOOL's failure below protocol version 11, GOV's from it"
  ):
    val s0 = kitCases.find(_.label == "unelected-vote").getOrElse(fail("no vote"))
    // under every build, applied (`applyTx`) and applied again (`reapply`, held as validated in the
    // same state, so without the static checks, which none of these failures is)
    def refusals(major: Int): Vector[(String, List[ConwayLedgerPredFailure[Conway]])] =
      for
        build <- ReferenceBuild.values.toVector
        s = s0.atVersion(major).under(build)
        (how, outcome) <- Vector(
          "applyTx" -> ConwayMempoolRun
            .applyTx(s.judging, s.env, s.ledger.ls, s.typed)
            .map(_.map(_._1)),
          "reapply" -> ConwayMempoolRun.reapply(s.judging, s.env, s.ledger.ls, held(s))
        )
      yield s"${s.label} under ${build.name}, $how" -> (outcome match
        case Right(Left(ConwayApplyTxError(fs))) => fs.toChain.toList
        case other => fail(s"${s.label} under ${build.name}, $how: $other"))
    def refusedAs(major: Int)(expected: List[ConwayLedgerPredFailure[Conway]]): Unit =
      val all = refusals(major)
      assertEquals(all.size, ReferenceBuild.values.size * 2)
      all.foreach((what, fs) => assertEquals(fs, expected, what))
    val unknown = ConwayLedgerPredFailure.ConwayGovFailure[Conway](
      ConwayGovPredFailure.VotersDoNotExist(
        NonEmptyList.one(xyz.kd5ujc.scarab.ledger.Voter.CommitteeHot(hot.credential))
      )
    )
    // MEMPOOL refuses the unelected voter (`Conway/Rules/Mempool.hs:129-135` (f649f975)), then
    // runs LEDGER (`:138`), whose GOV (`Conway/Rules/Ledger.hs:410`) does not know the voter
    // (`Conway/Rules/Gov.hs:604`). The reference's list of a rule's failures holds each of its own
    // before those already there (`libs/small-steps/src/Control/State/Transition/Extended.hs:708`
    // (f649f975)) and each failure of a sub-rule, in that rule's list, before those already there
    // (`:721`), and is not reversed after (`:598-608`): GOV's one failure, through LEDGER's list,
    // comes before MEMPOOL's own
    Vector(9, 10).foreach(major =>
      refusedAs(major)(
        List(
          unknown,
          ConwayLedgerPredFailure.ConwayMempoolFailure[Conway](
            MempoolProblem.UnelectedCommittee(Vector(hot.credential))
          )
        )
      )
    )
    // from 11 MEMPOOL does not check it: GOV refuses the unelected voter (`Gov.hs:478-481` (f649f975)) before
    // the unknown one (`:604`), so GOV's list holds the unknown voter first; LEDGER's, which takes
    // GOV's failures one by one before its own, the unelected voter first; and MEMPOOL's, which
    // takes LEDGER's so, the unknown voter first
    refusedAs(11)(
      List(
        unknown,
        ConwayLedgerPredFailure.ConwayGovFailure[Conway](
          ConwayGovPredFailure.UnelectedCommitteeVoters(NonEmptyList.one(hot.credential))
        )
      )
    )

  // ---- the annotation, made where LEDGER first reads it

  test(
    "all inputs spent: MEMPOOL's failure alone, though a reference input's stored output is one the era cannot read"
  ):
    // an output with a reference script, created by a payment, then kept with a script hash that
    // is not its bytes' (an output only a damaged store holds)
    val l3 = kitState(3)
    val (created, _) = tx(
      Vector(txIn(1)),
      Vector(bob.address() -> (99 * ada)),
      ada,
      Vector(alice),
      referenceScript = Some(plutusScript)
    )
    val withScript = l3.applied(created, kitSlot)
    val (scripted, out) = withScript.utxo.toMap
      .collectFirst { case (in, o) if o.scriptRef.nonEmpty => in -> o }
      .getOrElse(fail("no output with a reference script"))
    val damaged = withScript.withUtxo(
      withScript.utxo.updated(
        scripted,
        out.copy(scriptRef = out.scriptRef.map(_.copy(hash = Bytes(Vector.fill(28)(7.toByte)))))
      )
    )
    def referencing(spent: TxIn, l: ConwayTicked): Seen =
      kit(
        s"spends ${spent.index}",
        tx(
          Vector(spent),
          Vector(bob.address() -> (99 * ada)),
          ada,
          Vector(alice),
          referenceInputs = Vector(scripted)
        )._1,
        l
      )
    // the transaction's only input is not in the state: a duplicate, whose annotation would read
    // the reference input's output, which the era cannot read
    val duplicate = referencing(txIn(9), damaged)
    ConwayMempoolRun.utxoOf(duplicate.ledger.ls, duplicate.typed) match
      case Left(UtxoStateDefect.StoredOutput(`scripted`, _)) => ()
      case other => fail(s"the stored output is read: $other")
    val allSpent = ConwayApplyTxError(
      NonEmptyChain.one(ConwayLedgerPredFailure.ConwayMempoolFailure(MempoolProblem.AllInputsSpent))
    )
    // held from the state before the damage, as admitted there
    val heldBefore = held(referencing(txIn(9), withScript))
    ReferenceBuild.values.foreach { build =>
      val s = duplicate.under(build)
      assertEquals(
        ConwayMempoolRun.applyTx(s.judging, s.env, s.ledger.ls, s.typed).map(_.map(_._1)),
        Right(Left(allSpent)),
        build.name
      )
      assertEquals(
        ConwayMempoolRun.reapply(s.judging, s.env, s.ledger.ls, heldBefore),
        Right(Left(allSpent)),
        s"${build.name}: reapply"
      )
      // with an input the state holds, LEDGER runs and reads the output: the node's defect
      val live = referencing(txIn(2), damaged).under(build)
      ConwayMempoolRun.applyTx(live.judging, live.env, live.ledger.ls, live.typed) match
        case Left(ConwayRuleDefect.UtxoState(UtxoStateDefect.StoredOutput(`scripted`, _))) => ()
        case other => fail(s"${build.name}: not the stored output's defect: $other")
    }

  /** The run annotated a transaction where a test forbids it. */
  private final class Annotated(label: String)
      extends RuntimeException(s"$label: the run annotated the transaction")

  /** `s` judged with an annotation that refuses to be made. */
  private def forbidding(s: Seen): Seen =
    s.copy(judging = s.judging.copy(annotation = (_, _, _, _) => throw Annotated(s.label)))

  test("a duplicate is never annotated: no stored output read and no script context built for it"):
    val l3 = kitState(3)
    val (paid, _) = TxKit.pay(1)
    val after = l3.applied(paid, kitSlot)
    val allSpent = ConwayApplyTxError(
      NonEmptyChain.one(ConwayLedgerPredFailure.ConwayMempoolFailure(MempoolProblem.AllInputsSpent))
    )
    // the payment again, once its input is spent, and one whose only input the state never held
    Vector(kit("again", paid, after), kit("ghost", TxKit.pay(9)._1, l3)).foreach { s0 =>
      // held as validated in the state before the payment
      val heldBefore = held(s0.withLedger(l3))
      ReferenceBuild.values.foreach { build =>
        val s = forbidding(s0.under(build))
        assertEquals(
          ConwayMempoolRun.applyTx(s.judging, s.env, s.ledger.ls, s.typed).map(_.map(_._1)),
          Right(Left(allSpent)),
          s"${s.label} under ${build.name}"
        )
        assertEquals(
          ConwayMempoolRun.reapply(s.judging, s.env, s.ledger.ls, heldBefore).map(_.map(_ => ())),
          Right(Left(allSpent)),
          s"${s.label} under ${build.name}: reapply"
        )
      }
    }
    // the control: a transaction MEMPOOL does not refuse is annotated, by LEDGER's step
    val live = forbidding(kit("live", paid, l3))
    intercept[Annotated](
      ConwayMempoolRun.applyTx(live.judging, live.env, live.ledger.ls, live.typed)
    )

  // ---- the reapplication by build

  test("reapply: each build's consensus, which differ where the major version changed"):
    // a transaction only the static checks refuse (a signature that does not verify), held as
    // if validated (`unsafeMakeValidatedTx`): the mempool's reapplication skips them
    val base = kitCases.find(_.label == "forged-signature").getOrElse(fail("no case"))
    assertEquals(submitted(base), Whole.Refused(List(forgedWitness)), "the full application")
    def again(at: Seen, on: Seen): Whole =
      eraWhole(s"${on.label} under ${on.build.name}, held at ${at.label}")(
        ConwayMempoolRun.reapply(on.judging, on.env, on.ledger.ls, held(at))
      )
    val outcomes = ReferenceBuild.values.toVector.map { build =>
      val at10 = base.atVersion(10).under(build)
      val at11 = base.atVersion(11).under(build)
      assertEquals(
        ConwayMempoolRun.Reapply.of(build) == ConwayMempoolRun.Reapply.ReapplyTx,
        build == ReferenceBuild.Release11_1
      )
      // the same major version: the static checks skipped under every build
      assert(again(at11, at11).isInstanceOf[Whole.Admitted], s"${build.name}: at 11")
      build -> again(at10, at11)
    }.toMap
    // another major version: 11.1's `reapplyTx` skips the static checks whatever the version;
    // 11.2's and Leios's `reapplyValidatedTx` validates in full, and the signature fails
    assert(outcomes(ReferenceBuild.Release11_1).isInstanceOf[Whole.Admitted])
    Vector(ReferenceBuild.Release11_2, ReferenceBuild.Leios).foreach { build =>
      assertEquals(outcomes(build), Whole.Refused(List(forgedWitness)), build.name)
    }

  test(
    "reapply: an admission's annotation is kept by 11.2 and Leios at its major version, made again by 11.1"
  ):
    // alice pays from txIn(1), admitted by `applyTx`: its annotation needs no script
    val (raw, _) = TxKit.pay(1)
    val l3 = kitState(3)
    // the state it is applied again in holds txIn(1)'s output locked by a native script the
    // transaction does not provide: an output never changes on a chain, so only a crafted state
    // tells the held annotation (no script needed) from one made again (the script needed and
    // missing), the scripts being run by neither reapplication
    val locked = xyz.kd5ujc.scarab.ledger.Address
      .decode(Bytes(0x70.toByte +: Vector.fill(28)(5.toByte)))
      .fold(e => fail(e.toString), identity)
    val changed = l3.withUtxo(
      l3.utxo.updated(txIn(1), xyz.kd5ujc.scarab.ledger.TestLedger.out(locked, 100 * ada))
    )
    val needed = (stAnnTx: xyz.kd5ujc.scarab.ledger.era.StAnnTx[TxLevel.Top, Conway]) =>
      EraUTxO.conway.getScriptsHashesNeeded(
        EraUTxO.conway.scriptsNeededStAnnTx[TxLevel.Top](stAnnTx)(using
          EraUTxO.conway.txOf.bodyOf.top
        )
      )
    ReferenceBuild.values.foreach { build =>
      val s = kit("admitted", raw, l3).under(build)
      val admitted = ConwayMempoolRun.applyTx(s.judging, s.env, s.ledger.ls, s.typed) match
        case Right(Right((_, validated))) => validated
        case other                        => fail(s"${build.name}: not admitted: $other")
      assert(needed(admitted.stAnnTx).isEmpty, s"${build.name}: the admission's annotation")
      val r = s.withLedger(changed)
      assert(needed(held(r).stAnnTx).nonEmpty, s"${build.name}: an annotation made again")
      val ours = eraWhole(s"${build.name}: reapplied")(
        ConwayMempoolRun.reapply(r.judging, r.env, r.ledger.ls, admitted)
      )
      val again = eraWhole(s"${build.name}: annotated again")(
        ConwayMempoolRun.reapplyTx(r.judging, r.env, r.ledger.ls, admitted)
      )
      // made again, the annotation needs the native script the transaction does not provide
      // (`babbageMissingScripts`, `Babbage/Rules/Utxow.hs:354` (f649f975)), and nothing else fails
      assertEquals(
        again,
        Whole.Refused(
          List(
            utxow(
              ConwayUtxowPredFailure.MissingScriptWitnessesUTXOW(
                NonEmptySet.one(Bytes(Vector.fill(28)(5.toByte)))
              )
            )
          )
        ),
        s"${build.name}: annotated again"
      )
      build match
        case ReferenceBuild.Release11_1 =>
          assertEquals(ours, again, "11.1 annotates again")
        case ReferenceBuild.Release11_2 | ReferenceBuild.Leios =>
          // the admission's annotation, at the version it was validated at
          assert(ours.isInstanceOf[Whole.Admitted], s"${build.name}: $ours")
          // at another major version, annotated again with every check
          val r11 = r.atVersion(11)
          assertEquals(
            eraWhole(s"${build.name}: at 11")(
              ConwayMempoolRun.reapply(r11.judging, r11.env, r11.ledger.ls, admitted)
            ),
            submitted(r11),
            s"${build.name}: at 11"
          )
    }

  test("a stored output held under many inputs is read into the era once, and shared"):
    // an output with a reference script of 64 KiB, held under 64 inputs: a read copies the
    // script out of the output's bytes, so each input's own read cost a copy of it
    val (scriptRaw, _) = tx(
      Vector(txIn(1)),
      Vector(bob.address() -> (99 * ada)),
      ada,
      Vector(alice),
      referenceScript = Some(
        xyz.kd5ujc.scarab.ledger.ScriptKind.PlutusV2 -> Bytes(Vector.fill(1 << 16)(1.toByte))
      )
    )
    val stored =
      right("stored output")(FlatTx.fromEnvelope(scriptRaw, Era.Conway)).body.outputs.head
    val inputs = Vector.tabulate(64)(i => TxIn(txIn(60).id, i))
    val base = kitState(1)
    val held = base.withUtxo(inputs.foldLeft(base.utxo)(_.updated(_, stored)))
    val (raw, _) = tx(inputs, Vector(bob.address() -> (99 * ada)), ada, Vector(alice))
    val typed = right("typed")(ConwayTx.fromEnvelope(raw, StateLayout.Node11_1))
    val read = right("read")(ConwayMempoolRun.utxoOf(held.ls, typed)).unUTxO
    assertEquals(read.keySet, inputs.toSet)
    val first = read(inputs.head)
    assert(inputs.forall(in => read(in).asInstanceOf[AnyRef] eq first.asInstanceOf[AnyRef]))
