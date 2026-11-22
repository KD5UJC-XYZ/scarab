// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.syntax.all.*
import java.nio.file.Path
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{Horizon, PlutusStats, PredicateFailure, Tx, TxEnv, TxIn, TxOut}
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoStAnnTx,
  EraLedgerRun,
  EraTx,
  EraTxBody,
  EraUTxO,
  EraUtxoRun,
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
  ConwayUtxowPredFailure,
  LedgerEnv,
  LedgerPredFailure,
  LedgersEnv,
  ShelleyLedgersPredFailure,
  UtxoContext
}
import xyz.kd5ujc.scarab.ledger.rules.{
  Counted,
  Globals,
  Judgment,
  Ledger as FlatLedger,
  LedgerEnv as FlatLedgerEnv
}
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
import xyz.kd5ujc.scarab.node.mempool.{LocalSubmission, MempoolEnv, MempoolLedger}
import xyz.kd5ujc.scarab.protocol.{Era, ReferenceBuild, StateLayout}
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, UtxoCodecs}

/** Conway's LEDGER on the era types (`ConwayLedger.conwayLedgerTransition`) against the flat LEDGER
  * (`rules.Ledger`), and LEDGERS (`ShelleyLedgers.ledgersTransition`) over it, on every transaction
  * of the recorded Conway refusal corpora, from the state the mempool judges it in, at the recorded
  * protocol version and at 9, 10 and 11, under each build and validation policy: the same failures
  * in the same order, or the same ledger state. Where the reference refused a transaction for
  * LEDGER, the era rule's failures, one level below MEMPOOL, encode to the reference's refusal byte
  * for byte.
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
      ledger: MempoolLedger,
      slot: BigInt,
      build: ReferenceBuild,
      families: Vector[Family],
      reason: Bytes
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
      val network = setup.globals.network
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
        Seen(
          what,
          bytes,
          env.rules,
          network,
          Network.of(BigInt(network)).getOrElse(fail(s"$what network $network")),
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
          rc.reason
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

  /** An era failure in the node's flat form. */
  private def stored(s: Seen)(f: LedgerPredFailure[Conway]): PredicateFailure =
    right(s"${s.label}: flat form")(ConwayLedgerPredFailure.toStored(f, purposes(s), readOf(s)))

  /** An era failure in the comparison's form ([[EraUtxoRun.Failure]]). */
  private def eraForm(s: Seen)(f: LedgerPredFailure[Conway]): EraUtxoRun.Failure = f match
    case ConwayLedgerPredFailure.ConwayUtxowFailure(u) =>
      EraUtxoRun.Utxow.eraForm(u, purposes(s)).getOrElse(fail(s"${s.label}: $u"))
    case other =>
      EraUtxoRun.form(stored(s)(other)).getOrElse(fail(s"${s.label}: $other"))

  private def stAnnTx(s: Seen): AlonzoStAnnTx[Conway] =
    AlonzoStAnnTx.mkAlonzoStAnnTx[Conway](clock.posix, s.pp, s.utxo, Map.empty, s.typed)

  private def context(s: Seen, build: ReferenceBuild, validation: TxEnv.Validation) =
    UtxoContext(s.netId, 0, historyAt(s.slot), validation, ConwayUtxo.injectShelleyFailure(build))

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

  /** What a whole rule made of a case: its failures in the reference's order, in the comparison's
    * form, or the state after with the counters of the scripts it ran (the flat rule's
    * `Counted.plutus`, the era rule's from its events through LEDGER's embedding,
    * `EraLedgerRun.counters`).
    */
  private enum Whole:
    case Refused(failures: List[EraUtxoRun.Failure])
    case Admitted(state: LedgerState[Conway], scripts: (Long, Long, Long))

  /** The era-typed LEDGER and the flat one on `s` from the same state. */
  private def both(s: Seen, build: ReferenceBuild, validation: TxEnv.Validation): (Whole, Whole) =
    val what = s"${s.label} under ${build.name}, $validation"
    val era = eraLedger(s, build, validation) match
      case Left(defect)    => fail(s"$what: era defect $defect")
      case Right(Left(fs)) => Whole.Refused(fs.toChain.toList.map(eraForm(s)))
      case Right(Right((state, events))) =>
        Whole.Admitted(
          state,
          EraLedgerRun
            .counters(
              EraTx.conway
                .isValid(s.typed) == xyz.kd5ujc.scarab.ledger.era.IsPhase2Valid.Phase2Valid,
              events
            )
            .getOrElse(fail(s"$what: events $events"))
        )
    val env = TxEnv(
      slot = s.slot,
      epoch = s.ledger.epoch,
      network = s.network,
      evaluator = xyz.kd5ujc.scarab.plutus.ScarabEvaluator.default,
      decoder = xyz.kd5ujc.scarab.plutus.ScarabEvaluator.defaultDecoder,
      validation = validation,
      referenceBuild = build,
      clock = Some(clock)
    )
    val flat = Judgment.run(FlatLedger, Judgment.Policy.Accumulate)(
      globalsOf(s, build),
      FlatLedgerEnv(env, s.ledger.treasury),
      Counted(s.ledger.ls, PlutusStats.zero),
      s.flat
    ) match
      case Left(defect) => fail(s"$what: flat defect $defect")
      case Right(Left(fs)) =>
        Whole.Refused(fs.toChain.toList.map(f => EraUtxoRun.form(f).getOrElse(fail(s"$what: $f"))))
      case Right(Right(counted)) =>
        Whole.Admitted(
          counted.state,
          (counted.plutus.succeeded, counted.plutus.failed, counted.plutus.scripts)
        )
    (era, flat)

  /** The 11.2 and Leios builds check the withdrawals in LEDGER at every version, the flat rules in
    * CERTS below 11 under every build: the named difference
    * (https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-7055304484), where a withdrawal
    * fails.
    */
  private def movedWithdrawals(s: Seen, build: ReferenceBuild, flat: Whole): Boolean =
    build != ReferenceBuild.Release11_1 && s.ledger.params.protocolMajor < 11 && (flat match
      case Whole.Refused(fs) =>
        fs.exists {
          case EraUtxoRun.Failure.Utxo(
                PredicateFailure.Certs(
                  xyz.kd5ujc.scarab.ledger.CertsFailure.WithdrawalsNotInRewards(_)
                )
              ) =>
            true
          case _ => false
        }
      case Whole.Admitted(_, _) => false)

  /** What the reference's LEDGER makes of a case's one withdrawal under the moved withdrawals
    * (`testIncompleteAndMissingWithdrawals`, `Shelley/Rules/Ledger.hs:341-359`, cardano-ledger
    * f649f975): an account the chain does not hold, or holds on another network, is missing, with
    * the amount; one it holds whose balance the amount does not drain is incomplete, the amount
    * supplied against the balance.
    */
  private enum MovedWithdrawal:
    case Missing
    case Incomplete

  /** The corpus cases whose withdrawal the 11.2 and Leios builds refuse in LEDGER below version 11.
    */
  private val Moved: Map[String, MovedWithdrawal] = Map(
    "conway-devnet/withdrawal-more-than-balance" -> MovedWithdrawal.Incomplete,
    "conway-devnet/withdrawal-unregistered-account" -> MovedWithdrawal.Missing,
    "conway-devnet/withdrawal-network-flag-true" -> MovedWithdrawal.Missing
  )

  /** The era LEDGER's failures on a case of the moved withdrawals: the flat LEDGER's, in their
    * order, with CERTS's refusal of the withdrawal in its place replaced by LEDGER's, the account
    * and amount the transaction withdraws, and an incomplete one's balance the chain's.
    */
  private def movedExpected(
      s: Seen,
      base: String,
      flat: List[EraUtxoRun.Failure]
  ): List[EraUtxoRun.Failure] =
    val (account, amount) = s.flat.body.withdrawals.toVector match
      case Vector(one) => one
      case other       => fail(s"${s.label}: withdrawals $other")
    val ledgers =
      Moved.getOrElse(base, fail(s"${s.label}: not a case of the moved withdrawals")) match
        case MovedWithdrawal.Missing =>
          PredicateFailure.WithdrawalsMissingAccounts(Map(account -> amount.value))
        case MovedWithdrawal.Incomplete =>
          val credential = right("credential")(
            xyz.kd5ujc.scarab.ledger.rules.Convert
              .credential[xyz.kd5ujc.scarab.ledger.state.Staking](account.credential)
          )
          val balance = xyz.kd5ujc.scarab.ledger.rules.EraLedger
            .accounts(s.ledger.ls)
            .collectFirst { case (`credential`, view) => view.balance.toBigInt }
            .getOrElse(fail(s"${s.label}: the account is not held"))
          assert(amount.value < balance, s"${s.label}: $amount of $balance")
          PredicateFailure.IncompleteWithdrawals(
            Map(account -> xyz.kd5ujc.scarab.ledger.Mismatch(amount.value, balance))
          )
    val certs: EraUtxoRun.Failure = EraUtxoRun.Failure.Utxo(
      PredicateFailure.Certs(
        xyz.kd5ujc.scarab.ledger.CertsFailure.WithdrawalsNotInRewards(Map(account -> amount.value))
      )
    )
    assertEquals(flat.count(_ == certs), 1, s"${s.label}: the flat CERTS's refusal")
    flat.map(f => if f == certs then EraUtxoRun.Failure.Utxo(ledgers) else f)

  test("the corpus has Conway cases, each read by both paths"):
    assert(clue(seen.size) > 40)

  test("conwayLedgerTransition gives the flat LEDGER's failures, in order, on every Conway case"):
    val outcomes = for
      s0 <- seen
      s <- versions(s0)
      build <- ReferenceBuild.values.toVector
      validation <- TxEnv.Validation.values.toVector
    yield
      val base = s0.label
      val what = s"${s.label} under ${build.name}, $validation"
      both(s, build, validation) match
        case (era @ Whole.Admitted(ours, counted), Whole.Admitted(theirs, flatCounted)) =>
          assert(cats.Eq[LedgerState[Conway]].eqv(ours, theirs), what)
          assertEquals(counted, flatCounted, s"$what: the counters")
          (era, Option.empty[String])
        case (era, flat) if movedWithdrawals(s, build, flat) =>
          // the reference's form: the withdrawal refused in LEDGER, not in CERTS, with its
          // account and amount, where the flat CERTS refuses it; every other failure the same,
          // in the same order
          (era, flat) match
            case (Whole.Refused(ours), Whole.Refused(theirs)) =>
              assertEquals(ours, movedExpected(s, base, theirs), what)
            case _ => fail(s"$what: $era against $flat")
          (era, Some(base))
        case (era, flat) =>
          assertEquals(era, flat, what)
          (era, Option.empty[String])
    assert(
      outcomes.exists {
        case (Whole.Refused(fs), _) => fs.size >= 3
        case _                      => false
      },
      "no case with three failures"
    )
    assert(outcomes.exists(_._1.isInstanceOf[Whole.Admitted]), "no admitted case")
    // every case of the moved withdrawals, at the versions below 11 under the 11.2 and Leios
    // builds and each policy, and only those
    val moved = outcomes.collect { case (_, Some(label)) => label }
    assertEquals(moved.toSet, Moved.keySet, "the cases of the moved withdrawals")
    // each at its recorded version (below 11), 9 and 10, under two builds and two policies
    assertEquals(moved.size, Moved.size * 3 * 2 * 2, "the judgments of the moved withdrawals")

  /** The cases the mempool refuses before or beside LEDGER: its measure's (`txMeasureAlonzo`,
    * `Shelley/Ledger/Mempool.hs:590-612`, ouroboros-consensus 82ecba32) and MEMPOOL's own
    * (`ConwayMempoolFailure`, `Conway/Rules/Mempool.hs`, cardano-ledger f649f975). Their recorded
    * refusal is not LEDGER's, so the byte comparison leaves them out, by name.
    */
  private val NotLedgers: Set[String] = Set(
    "conway-devnet/tx-too-large",
    "conway-devnet/tx-too-large-spent-inputs",
    "conway-devnet/utxo-mint-units-too-big",
    "conway-devnet/all-inputs-spent"
  )

  test("conwayLedgerTransition's refusal is the reference's, byte for byte, on every LEDGER case"):
    // LEDGER one level down, under MEMPOOL, where the reference reports its failures in their
    // order; every other case is LEDGER's, the combined ones among them
    val (left, compared) = seen.partition(s => NotLedgers.contains(s.label))
    assertEquals(left.map(_.label).toSet, NotLedgers, "the cases left out are cases")
    assertEquals(
      seen
        .filter(s =>
          xyz.kd5ujc.scarab.node.mempool.Capacity.phase1(s.flat, s.ledger.params).isLeft ||
            s.families == Vector(Family.MEMPOOL)
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
      val failures = era.map(stored(s))
      assertEquals(
        PredicateFailureCbor.applyTxError(failures).fold(e => fail(s"${s.label}: $e"), _.hex),
        s.reason.hex,
        s.label
      )
      // the era failures written by their own encoder, without the flat form
      assertEquals(ConwayFailureCborCheck.direct(era).hex, s.reason.hex, s"${s.label}: direct")
    }

  test(
    "the era failures' own encoder writes the flat form's bytes, on every refused case, version, build and policy"
  ):
    // the same era failures, written by `ConwayFailureCbor` and by `PredicateFailureCbor` after the
    // flat projection: every refusal is compared, none left out, the flat projection having a form
    // for each failure of the corpus: the 3,744 refusals of the cases at each version, build, policy
    // and depth
    val judged = for
      s0 <- seen
      s <- versions(s0)
      build <- ReferenceBuild.values.toVector
      validation <- TxEnv.Validation.values.toVector
      depth <- Vector(0, 1)
      fs <- eraLedger(s, build, validation, depth) match
        case Right(Left(fs)) => Vector(fs.toChain.toVector)
        case Right(Right(_)) => Vector.empty
        case Left(defect)    => fail(s"${s.label}: defect $defect")
    yield
      val what = s"${s.label} under ${build.name}, $validation, depth $depth"
      val direct = ConwayFailureCborCheck.direct(fs)
      val flat = fs
        .traverse(ConwayLedgerPredFailure.toStored(_, purposes(s), readOf(s)))
        .fold(e => fail(s"$what: no flat form, $e"), identity)
      assertEquals(
        direct.hex,
        PredicateFailureCbor.applyTxError(flat).fold(e => fail(s"$what: $e"), _.hex),
        what
      )
      what
    assertEquals(judged.size, 3744, "the refusals compared")
    assertEquals(judged.distinct.size, judged.size, "each judgment once")

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
          // in the comparison's form: a script's failure holds terms, equal only as one object
          assertEquals(
            fs.toChain.toList.map { case ShelleyLedgersPredFailure.LedgerFailure(f) =>
              eraForm(s)(f)
            },
            gs.toChain.toList.map(eraForm(s)),
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
    val base = seen
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
    val flatVoter = xyz.kd5ujc.scarab.ledger.Voter.DRepVoter(
      xyz.kd5ujc.scarab.ledger.rules.Convert.back(drep)
    )
    val notThere = EraUtxoRun.Failure.Utxo(
      PredicateFailure.Gov(xyz.kd5ujc.scarab.ledger.GovFailure.VotersDoNotExist(Vector(flatVoter)))
    )
    ReferenceBuild.values.foreach { build =>
      both(s, build, TxEnv.Validation.All) match
        case (era @ Whole.Refused(fs), flat) =>
          assertEquals(era, flat, build.name)
          assert(fs.contains(notThere), s"${build.name}: $fs")
        case other => fail(s"${build.name}: $other")
    }
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
