// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import xyz.kd5ujc.scarab.protocol.StateLayout
import java.nio.file.Path
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{Horizon, Tx, TxEnv, TxIn, TxOut, Utxo, testByteStrings}
import xyz.kd5ujc.scarab.ledger.era.{
  ConwayEraPParams,
  EraCertState,
  EraPParams,
  EraTx,
  EraTxBody,
  EraUTxO,
  Network,
  PParams,
  StoredTxOut,
  TxLevel,
  UTxO
}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayPParams, ConwayTx, ConwayUTxO}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  AllegraUtxo,
  AllegraUtxoPredFailure,
  AlonzoUtxo,
  AlonzoUtxoPredFailure,
  BabbageUtxo,
  BabbageUtxoPredFailure,
  ConwayUtxo,
  ConwayUtxoPredFailure,
  ConwayUtxowPredFailure,
  InjectUtxoFailure,
  ShelleyUtxo,
  ShelleyUtxoPredFailure,
  UtxoContext,
  UtxoEnv
}
import xyz.kd5ujc.scarab.ledger.era.EraUtxoRun
import xyz.kd5ujc.scarab.ledger.rules.{CertLens, Test as Verdict}
import xyz.kd5ujc.scarab.ledger.state.{
  Coin,
  Conway,
  LedgerState,
  UTxOState,
  UtxoTables,
  ConwayCertState,
  ConwayLedgerState,
  ConwayNewEpochState,
  Credential,
  KeyHash,
  ShelleyBasedEra,
  StakePool,
  Staking
}
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.node.mempool.LocalSubmission
import xyz.kd5ujc.scarab.protocol.{Era, ReferenceBuild}
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, UtxoCodecs}

/** Conway's UTXO predicates on the era types, each on its own, assembled (`babbageUtxoValidation`)
  * and whole (`conwayUtxoTransition`, and UTXOW's `babbageUtxowTransition` over it), on every
  * transaction of the recorded Conway refusal corpora and on cases crafted from them. Before the
  * flat Conway UTXO and UTXOW were deleted, the era rules were compared with them here, and they
  * gave the same verdicts, failures included, the same values (the minimum fee, the value consumed
  * and produced, the deposits and the refunds) and the same state; the era rules' judgments were
  * recorded after the two were found to agree
  * (`fixtures/conway-flat-refusals/utxo-judgments.jsonl`, under `utxo-checks/`): a refusal by the
  * bytes of its failures, an admission by the state it leaves, a value as it is. The era rules are
  * held to that recording. The corpora the flat predicates judged are named ([[RecordedCorpora]]):
  * the corpora recorded for the era rules alone
  * ([[xyz.kd5ujc.scarab.node.ntc.ReferenceRefusalSuite.EraOnly]]) are not among them, while the
  * whole rules judged every Conway corpus ([[WholeCorpora]]). Where only UTXO or UTXOW refused, the
  * refusal is the reference's, byte for byte; a predicate the corpus never fails is named.
  */
class ConwayUtxoChecksSuite extends FunSuite:
  import ReferenceRefusalSuite.*

  private given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway

  private val body = EraTxBody.conway
  private val top = body.top

  /** One Conway case as the era types see it, with the transaction the flat decoder read, which the
    * crafted cases are made from, and the parameters as stored.
    */
  private final case class Seen(
      label: String,
      bytes: Bytes,
      slot: BigInt,
      network: Int,
      netId: Network,
      flat: Tx,
      typed: ConwayTx,
      stored: Map[TxIn, TxOut],
      utxo: UTxO[Conway],
      flatPp: xyz.kd5ujc.scarab.ledger.ProtocolParams,
      pp: PParams[Conway],
      cert: ConwayCertState,
      utxoState: UTxOState[Conway],
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
      val netId = setup.globals.network
      val network = netId.wire
      c.cases.map { rc =>
        val what = s"${c.path}/${rc.label}"
        val bytes: Bytes = right(s"$what item")(LocalSubmission.unwrap(rc.item)).raw
        val nes = right(s"$what state")(
          codecs.newEpochState[Conway].decodeBytes(c.states(rc.state), HaskellState.limits)
        )
        val flatPp = ConwayNewEpochState.gov.get(nes).curPParams
        val stored = right(s"$what UTxO")(UtxoCodecs.utxo.decodeBytes(c.utxos(rc.utxo)))
        Seen(
          what,
          bytes,
          rc.tipSlot + 1,
          network,
          netId,
          right(s"$what flat")(Tx.fromEnvelope(bytes, Era.Conway)),
          right(s"$what typed")(ConwayTx.fromEnvelope(bytes, StateLayout.Node11_1)),
          stored,
          right(s"$what typed UTxO")(ConwayUTxO.fromStored(stored)),
          flatPp,
          PParams[Conway](right(s"$what typed parameters")(ConwayPParams.fromStored(flatPp))),
          ConwayNewEpochState.ls.andThen(ConwayLedgerState.cert).get(nes),
          ConwayNewEpochState.ls
            .andThen(LedgerState.utxo[Conway])
            .get(nes)
            .copy(utxo = UtxoTables(Utxo.fromMap(stored))),
          c.build,
          rc.families,
          rc.reason,
          rc.before.nonEmpty
        )
      }
    }

  // ---- the recording -----------------------------------------------------------------------------

  /** The corpora whose cases the flat predicates judged before they were deleted, by name: a corpus
    * added later is not among them.
    */
  private val RecordedCorpora: Set[String] = Set(
    "conway-bootstrap-attributes",
    "conway-bootstrap-attributes-leios-b004fe0c",
    "conway-deleg-collateral",
    "conway-devnet",
    "conway-gov-proposals",
    "conway-governance",
    "conway-three-certificates",
    "conway-v11-coverage"
  )

  /** The corpora whose cases the flat UTXO and UTXOW judged whole, by name: every Conway corpus. */
  private val WholeCorpora: Set[String] = RecordedCorpora ++ Set(
    "conway-constructors",
    "conway-constructors-v11",
    "conway-gov-order",
    "conway-mempool"
  )

  private def of(corpora: Set[String]): Vector[Seen] =
    seen.filter(s => corpora.exists(c => s.label.startsWith(s"$c/")))

  /** The cases the flat predicates judged. */
  private lazy val recordedSeen: Vector[Seen] = of(RecordedCorpora)

  /** The cases the flat rules judged whole. */
  private lazy val wholeSeen: Vector[Seen] = of(WholeCorpora)

  /** The recorded judgment `id`, failing the test where none was recorded. */
  private def recorded(id: String): Vector[String] =
    FlatRecordings.utxoJudgments.getOrElse(id, fail(s"no recording of $id"))

  /** That `cells` are the recorded judgment `id`, which are returned. */
  private def holds(id: String, cells: Vector[String]): Vector[String] =
    assertEquals(cells, recorded(id), id)
    cells

  /** A verdict's cell: `-` where it passes, a refusal's cell ([[FlatRecordings.utxoRefused]]) where
    * it fails.
    */
  private def cell(test: Verdict[ConwayUtxoPredFailure[Conway]]): String =
    test.fold(fs => FlatRecordings.utxoRefused(fs.toChain.toVector), _ => "-")

  // ---- the predicates ----------------------------------------------------------------------------

  /** A Shelley predicate's failures through the 11.2 injection, which keeps each failure a Conway
    * transaction gives Shelley's predicates as it is: the predicates are judged, not the injection,
    * which `assembled` judges under each build.
    */
  private def shelley(
      test: Verdict[ShelleyUtxoPredFailure[Conway]]
  ): Verdict[ConwayUtxoPredFailure[Conway]] =
    test.leftMap(_.map(ConwayUtxo.injectShelleyFailure11_2.injectFailure))

  private def allegra(
      test: Verdict[AllegraUtxoPredFailure[Conway]]
  ): Verdict[ConwayUtxoPredFailure[Conway]] =
    test.leftMap(_.map(InjectUtxoFailure.conwayAllegra.injectFailure))

  private def alonzo(
      test: Verdict[AlonzoUtxoPredFailure[Conway]]
  ): Verdict[ConwayUtxoPredFailure[Conway]] =
    test.leftMap(_.map(InjectUtxoFailure.conwayAlonzo.injectFailure))

  private def babbage(
      test: Verdict[BabbageUtxoPredFailure[Conway]]
  ): Verdict[ConwayUtxoPredFailure[Conway]] =
    test.leftMap(_.map(InjectUtxoFailure.conwayBabbage.injectFailure))

  /** Each predicate, by its name, and its verdict in Conway's failures. */
  private def pairs(s: Seen): Vector[(String, Verdict[ConwayUtxoPredFailure[Conway]])] =
    val tx = EraTx.conway
    val tb = tx.body[TxLevel.Top](s.typed)(using tx.bodyOf.top)
    Vector(
      "disjointRefInputs" -> babbage(
        BabbageUtxo
          .disjointRefInputs[Conway](
            s.pp,
            body.inputs[TxLevel.Top](tb)(using top),
            body.referenceInputs[TxLevel.Top](tb)(using top)
          )
      ),
      "validateOutsideValidityIntervalUTxO" -> allegra(
        AllegraUtxo.validateOutsideValidityIntervalUTxO[Conway, TxLevel.Top](s.slot, tb)(using
          body,
          top
        )
      ),
      "validateInputSetEmptyUTxO" -> shelley(
        ShelleyUtxo.validateInputSetEmptyUTxO[Conway, TxLevel.Top](tb)(using body, top)
      ),
      "validateBadInputsUTxO" -> shelley(
        ShelleyUtxo.validateBadInputsUTxO[Conway](s.utxo, body.allInputs(tb))
      ),
      "validateWrongNetwork" -> shelley(
        ShelleyUtxo
          .validateWrongNetwork[Conway](
            s.netId,
            body.allSizedOutputs[TxLevel.Top](tb)(using top).map(_.value)
          )(using
            body.outputsOf
          )
      ),
      "validateWrongNetworkWithdrawal" -> shelley(
        ShelleyUtxo.validateWrongNetworkWithdrawal[Conway, TxLevel.Top](s.netId, tb)(using
          body,
          top
        )
      ),
      "validateWrongNetworkInTxBody" -> alonzo(
        AlonzoUtxo.validateWrongNetworkInTxBody[Conway, TxLevel.Top](s.netId, tb)(using
          body,
          top
        )
      ),
      "validateTooManyCollateralInputs" -> alonzo(
        AlonzoUtxo.validateTooManyCollateralInputs[Conway](s.pp, tb)
      ),
      "validateOutputTooSmallUTxO" -> babbage(
        BabbageUtxo.validateOutputTooSmallUTxO[Conway](
          s.pp,
          body.allSizedOutputs[TxLevel.Top](tb)(using top)
        )(using body.outputsOf)
      ),
      "validateOutputTooBigUTxO" -> alonzo(
        AlonzoUtxo.validateOutputTooBigUTxO[Conway](
          s.pp,
          body.allSizedOutputs[TxLevel.Top](tb)(using top).map(_.value)
        )(using body.outputsOf)
      ),
      "validateOutputBootAddrAttrsTooBig" -> shelley(
        ShelleyUtxo.validateOutputBootAddrAttrsTooBig[Conway](
          body.allSizedOutputs[TxLevel.Top](tb)(using top).map(_.value)
        )(using body.outputsOf)
      ),
      "validateValueNotConservedUTxO" -> shelley(
        ShelleyUtxo.validateValueNotConservedUTxO[Conway](s.pp, s.utxo, s.cert, tb)
      ),
      "feesOK" -> BabbageUtxo.feesOK[Conway](s.pp, s.typed, s.utxo),
      "validateMaxTxSizeUTxO" -> shelley(
        ShelleyUtxo.validateMaxTxSizeUTxO[Conway, TxLevel.Top](s.pp, s.typed)(using
          tx,
          tx.bodyOf.top
        )
      ),
      "validateExUnitsTooBigUTxO" -> alonzo(
        AlonzoUtxo.validateExUnitsTooBigUTxO[Conway, TxLevel.Top](s.pp, s.typed)(using
          tx,
          tx.bodyOf.top
        )
      )
    )

  /** The verdict of the predicate `name` on `s`. */
  private def verdictOf(s: Seen, name: String): Verdict[ConwayUtxoPredFailure[Conway]] =
    pairs(s).find(_._1 == name).getOrElse(fail(s"no predicate $name"))._2

  // ---- the values --------------------------------------------------------------------------------

  /** A value as it is recorded: its lovelace, then each asset `<policy>.<name>=<quantity>`, in the
    * order of the policies and names, separated by `+`.
    */
  private def valueText(v: xyz.kd5ujc.scarab.ledger.Value): String =
    (v.coin.toString +: v.assets.toVector.sortBy(_._1.hex).flatMap { (policy, names) =>
      names.toVector.sortBy(_._1.hex).map((name, n) => s"${policy.hex}.${name.hex}=$n")
    }).mkString("+")

  private def lookupStakingDeposit(s: Seen)(c: Credential[Staking]): Option[Coin] =
    CertLens.accounts.get(s.cert).get(c).map(_.deposit.toCoin)

  private def isPoolRegistered(s: Seen)(k: KeyHash[StakePool]): Boolean =
    CertLens.pools.get(s.cert).contains(k)

  /** The minimum fee (`getMinFeeTxUtxo`) of `s`. */
  private def minFee(s: Seen): BigInt =
    val u = EraUTxO.conway
    u.getMinFeeTxUtxo[TxLevel.Top](s.pp, s.typed, s.utxo)(using u.txOf.bodyOf.top).value

  /** The value `s` consumes and produces (`getConsumedValue`, `getProducedValue`), as recorded. */
  private def balance(s: Seen): Vector[String] =
    val u = EraUTxO.conway
    val stored = StoredTxOut.conway
    val tb = u.txOf.body[TxLevel.Top](s.typed)(using u.txOf.bodyOf.top)
    Vector(
      valueText(
        stored.storedValue(
          u.getConsumedValue[TxLevel.Top](s.pp, lookupStakingDeposit(s), s.utxo, tb)(using
            u.txOf.bodyOf.top
          )
        )
      ),
      valueText(stored.storedValue(u.getProducedValue(s.pp, isPoolRegistered(s), tb)))
    )

  /** The deposits and refunds of `s` 's certificates (`certsTotalDepositsTxBody`,
    * `certsTotalRefundsTxBody`).
    */
  private def certDeposits(s: Seen): Vector[BigInt] =
    val c = EraCertState.conway
    val tb = EraTx.conway.body[TxLevel.Top](s.typed)(using EraTx.conway.bodyOf.top)
    Vector(
      c.certsTotalDepositsTxBody(s.pp, s.cert, tb)(using body).value,
      c.certsTotalRefundsTxBody[TxLevel.Top](s.pp, c.certDStateL.get(s.cert).accounts, tb)(using
        body,
        top
      ).value
    )

  /** The recorded values of a case: its minimum fee, the value it consumes and produces, and its
    * certificates' deposits and refunds.
    */
  private def values(s: Seen): Vector[String] =
    minFee(s).toString +: (balance(s) ++ certDeposits(s).map(_.toString))

  test("the corpus has Conway cases, each read by the era types, and the recorded corpora"):
    assert(clue(seen.size) > 0)
    assertEquals(
      WholeCorpora -- seen.map(_.label.takeWhile(_ != '/')),
      Set.empty[String],
      "the recorded corpora not loaded"
    )

  private lazy val valuesJudged: Vector[(String, Vector[String])] =
    recordedSeen.map(s => s"utxo-checks/values/${s.label}" -> values(s))

  test(
    "getMinFeeTxUtxo, getConsumedValue, getProducedValue and the certificates' deposits and refunds are the flat path's recorded values on every case"
  ):
    valuesJudged.foreach(holds.tupled)

  // ---- each predicate ----------------------------------------------------------------------------

  private lazy val predicatesJudged: Vector[(String, Vector[String])] =
    recordedSeen.map(s => s"utxo-checks/predicates/${s.label}" -> pairs(s).map((_, t) => cell(t)))

  test("each predicate gives the flat one's recorded verdict on every recorded case"):
    predicatesJudged.foreach(holds.tupled)

  /** The predicates some recorded case fails, so the flat comparison saw them refuse. */
  private lazy val refusedSomewhere: Set[String] =
    recordedSeen.flatMap(s => pairs(s).collect { case (name, t) if t.isInvalid => name }).toSet

  // ---- crafted cases -----------------------------------------------------------------------------

  /** The bytes of `s` 's transaction with its body's fields edited by `f`, and both decoders'
    * reading of them. The predicates are pure, so a body whose witnesses no longer sign it still
    * tells them apart.
    */
  private def edited(s: Seen, bytes: Bytes)(
      f: Vector[(Node, Node)] => Vector[(Node, Node)]
  ): Seen =
    val root = right("envelope")(Cbor.decode(bytes))
    val again = root.value match
      case Value.Arr(parts) =>
        parts.head.value match
          case Value.Map(fields) =>
            val body = Node(Value.Map(f(fields)), Bytes.empty)
            right("re-encode")(Cbor.encode(Value.Arr(body +: parts.tail), Cbor.Limits.Unbounded))
          case other => fail(s"a body that is not a map: $other")
      case other => fail(s"an envelope that is not an array: $other")
    s.copy(
      flat = right("edited flat")(Tx.fromEnvelope(again, Era.Conway)),
      typed = right("edited typed")(ConwayTx.fromEnvelope(again, StateLayout.Node11_1))
    )

  private def key(k: Int): Node = Node(Value.UInt(k), Bytes.empty)
  private def isKey(k: Int)(n: Node): Boolean = n.value == Value.UInt(k)

  /** The same parameters in both forms, edited by `f`. */
  private def withParams(s: Seen)(
      f: xyz.kd5ujc.scarab.ledger.ProtocolParams => xyz.kd5ujc.scarab.ledger.ProtocolParams
  ): Seen =
    val flatPp = f(s.flatPp)
    s.copy(
      flatPp = flatPp,
      pp = PParams[Conway](right("edited parameters")(ConwayPParams.fromStored(flatPp)))
    )

  /** The crafted case `id`: the predicate `name` refuses `s`, and its verdict's cell. */
  private def crafted(id: String, s: Seen, name: String): (String, Vector[String]) =
    val verdict = verdictOf(s, name)
    assert(verdict.isInvalid, s"$id: the crafted case passes $name")
    s"utxo-checks/crafted/$id" -> Vector(cell(verdict))

  private def envelope(s: Seen): Bytes = s.bytes

  private lazy val emptyInputs: (String, Vector[String]) =
    val s = seen.head
    crafted(
      "empty-inputs",
      edited(s, envelope(s))(fields =>
        fields.map((k, v) =>
          if isKey(0)(k) then k -> Node(Value.Arr(Vector.empty), Bytes.empty) else k -> v
        )
      ),
      "validateInputSetEmptyUTxO"
    )

  test("crafted from a recorded case: an empty input set"):
    holds.tupled(emptyInputs)

  private lazy val spentReferences: (String, Vector[String]) =
    val s = withParams(seen.head)(_.copy(protocolMajor = 10))
    crafted(
      "reference-inputs-spent-v10",
      edited(s, envelope(s))(fields =>
        val inputs =
          fields.collectFirst { case (k, v) if isKey(0)(k) => v }.getOrElse(fail("no inputs"))
        fields.filterNot((k, _) => isKey(18)(k)) :+ (key(18) -> inputs)
      ),
      "disjointRefInputs"
    )

  test("crafted from a recorded case: reference inputs that are also spent, at version 10"):
    holds.tupled(spentReferences)

  private lazy val tooManyCollateral: (String, Vector[String]) =
    val s = seen.find(_.flat.body.collateral.nonEmpty).getOrElse(fail("no case with collateral"))
    crafted(
      "too-many-collateral",
      withParams(s)(_.copy(maxCollateralInputs = 0)),
      "validateTooManyCollateralInputs"
    )

  test("crafted from a recorded case: more collateral inputs than the parameters allow"):
    holds.tupled(tooManyCollateral)

  /** A collateral set of `n` distinct inputs: indices below 50,000 of one transaction id after
    * another, since an index is a `Word16`.
    */
  private def collateral(n: Int): Node =
    def leaf(v: Value) = Node(v, Bytes.empty)
    def input(i: Int) =
      val id = leaf(Value.ByteString(Bytes(Vector.fill(31)(7.toByte) :+ (i / 50000).toByte)))
      leaf(Value.Arr(Vector(id, leaf(Value.UInt(i % 50000)))))
    leaf(Value.Arr(Vector.tabulate(n)(input)))

  private def withCollateral(s: Seen, n: Int): Seen =
    edited(s, envelope(s))(fields =>
      fields.filterNot((k, _) => isKey(13)(k)) :+ (key(13) -> collateral(n))
    )

  /** The collateral counts past a `Word16`, and the verdict on each. */
  private lazy val narrowedCollateral: Vector[(Int, Verdict[ConwayUtxoPredFailure[Conway]])] =
    val s = withParams(seen.head)(_.copy(maxCollateralInputs = 3))
    Vector(1 << 16, (1 << 16) + 3, (1 << 16) + 4).map(n =>
      n -> verdictOf(withCollateral(s, n), "validateTooManyCollateralInputs")
    )

  private lazy val narrowedJudged: (String, Vector[String]) =
    "utxo-checks/crafted/collateral-word16" -> narrowedCollateral.map((_, t) => cell(t))

  test("the collateral count is narrowed to a Word16, as the reference compares it"):
    holds.tupled(narrowedJudged)
    narrowedCollateral match
      case Vector((_, at0), (_, at3), (_, at4)) =>
        assert(at0.isValid, "65,536 inputs count as 0")
        assert(at3.isValid, "65,539 inputs count as 3")
        at4.fold(
          fs =>
            fs.toChain.toList match
              case List(ConwayUtxoPredFailure.TooManyCollateralInputs(m)) =>
                assertEquals((m.supplied.value, m.expected.value), (BigInt(4), BigInt(3)))
              case other => fail(s"65,540 inputs: $other")
          ,
          _ => fail("65,540 inputs count as 4, past the 3 allowed")
        )
      case other => fail(s"the verdicts: $other")

  // ---- crafted cases for the branches the corpus never takes (review of #1119) ----

  private def leaf(v: Value): Node = Node(v, Bytes.empty)
  private def uint(n: BigInt): Node = leaf(Value.UInt(n))
  private def bytesOf(b: Bytes): Node = leaf(Value.ByteString(b))
  private def filled(n: Int, b: Int): Bytes = Bytes(Vector.fill(n)(b.toByte))
  private def keyCred(b: Int): Node = leaf(Value.Arr(Vector(uint(0), bytesOf(filled(28, b)))))

  /** An output of `s` 's first spent output's address and two ada, holding a native reference
    * script of `sigs` signatures (an `all-of`), read by the flat output decoder.
    */
  private def withRefScript(s: Seen, sigs: Int): TxOut =
    val address = s.stored(s.flat.body.inputs.head).address.raw
    val native = leaf(
      Value.Arr(
        Vector(
          uint(1),
          leaf(
            Value.Arr(
              Vector.tabulate(sigs)(i =>
                leaf(Value.Arr(Vector(uint(0), bytesOf(filled(28, i % 256)))))
              )
            )
          )
        )
      )
    )
    val script =
      right("script")(Cbor.encode(Value.Arr(Vector(uint(0), native)), Cbor.Limits.Unbounded))
    val out = Value.Map(
      Vector(
        uint(0) -> bytesOf(address),
        uint(1) -> uint(2000000),
        uint(3) -> leaf(Value.Tag(24, bytesOf(script)))
      )
    )
    right("output")(
      TxOut.fromBytes(right("encode output")(Cbor.encode(out, Cbor.Limits.Unbounded)))
    )

  /** The reference scripts' size `s` 's minimum fee counts (`txNonDistinctRefScriptsSize`): an
    * input in both sets once.
    */
  private def refScriptsSize(s: Seen): Long =
    (s.flat.body.inputs.toSet ++ s.flat.body.referenceInputs.toSet).iterator
      .flatMap(s.stored.get)
      .map(_.referenceScriptSize)
      .sum

  private lazy val repeatedScripts: (String, Vector[String]) =
    val base = seen.head
    val spent = base.flat.body.inputs.toVector
    // the same script on a spent output and on a reference output, and another on a second one
    val ref2 = TxIn(Bytes(Vector.fill(32)(9.toByte)), 1)
    val ref = TxIn(Bytes(Vector.fill(32)(9.toByte)), 0)
    val stored =
      base.stored ++ spent.map(_ -> withRefScript(base, 900)) +
        (ref -> withRefScript(base, 900)) + (ref2 -> withRefScript(base, 3))
    val s0 = base.copy(stored = stored, utxo = right("typed UTxO")(ConwayUTxO.fromStored(stored)))
    val s = edited(s0, envelope(s0))(fields =>
      val inputs =
        fields.collectFirst { case (k, v) if isKey(0)(k) => v }.getOrElse(fail("no inputs"))
      val refs = inputs.value match
        case Value.Arr(xs) =>
          leaf(
            Value.Arr(
              xs ++ Vector(ref, ref2).map(in =>
                leaf(Value.Arr(Vector(bytesOf(in.id), uint(in.index))))
              )
            )
          )
        case other => fail(s"inputs: $other")
      fields.filterNot((k, _) => isKey(18)(k)) :+ (key(18) -> refs)
    )
    assert(refScriptsSize(s) > 25600L, "past the first tier")
    "utxo-checks/crafted/repeated-reference-scripts" -> Vector(minFee(s).toString)

  test(
    "the minimum fee counts repeated reference scripts and an input in both sets once, as the flat path's recorded fee"
  ):
    holds.tupled(repeatedScripts)

  /** The sizes about each tier's boundary, and the fee of each. */
  private lazy val tiers: (String, Vector[String]) =
    val p = EraPParams.conway
    val pp = seen.head.pp
    "utxo-checks/crafted/tier-ref-script-fee" ->
      Vector(0L, 1L, 25599L, 25600L, 25601L, 51199L, 51200L, 204800L, 1048576L).map(size =>
        ConwayTx
          .tierRefScriptFee(
            p.ppRefScriptCostMultiplierG.get(pp),
            p.ppRefScriptCostStrideG.get(pp),
            ConwayEraPParams.ppMinFeeRefScriptCostPerByteL[Conway].get(pp).rational,
            size
          )
          .value
          .toString
      )

  test("tierRefScriptFee is the flat path's recorded fee at and around each tier's boundary"):
    holds.tupled(tiers)

  /** A pool registration (tag 3) of `operator` at cost `cost`. */
  private def regPool(s: Seen, operator: Bytes, cost: Int): Node =
    leaf(
      Value.Arr(
        Vector(
          uint(3),
          bytesOf(operator),
          bytesOf(filled(32, 1)),
          uint(0),
          uint(cost),
          leaf(Value.Tag(30, leaf(Value.Arr(Vector(uint(1), uint(10)))))),
          bytesOf(Bytes(Vector((0xe0 | s.network).toByte)) ++ filled(28, 3)),
          leaf(Value.Arr(Vector.empty)),
          leaf(Value.Arr(Vector.empty)),
          leaf(Value.Null)
        )
      )
    )

  /** The crafted certificates' case and its deposits and refunds, by `getTotalDepositsTxBody` and
    * `getTotalRefundsTxBody`.
    */
  private lazy val craftedDeposits: (Seen, BigInt, BigInt) =
    // a pool deposit of its own, so the pools' share of the deposits is seen
    val base = withParams(seen.head)(_.copy(poolDeposit = 500000000))
    val certs = Vector(
      leaf(Value.Arr(Vector(uint(7), keyCred(0x41), uint(base.flatPp.keyDeposit)))),
      leaf(Value.Arr(Vector(uint(8), keyCred(0x41), uint(base.flatPp.keyDeposit)))),
      leaf(Value.Arr(Vector(uint(8), keyCred(0x42), uint(base.flatPp.keyDeposit)))),
      regPool(base, filled(28, 0x51), 340000000),
      regPool(base, filled(28, 0x51), 340000001),
      regPool(base, filled(28, 0x52), 340000000),
      leaf(
        Value.Arr(Vector(uint(16), keyCred(0x61), uint(base.flatPp.drepDeposit), leaf(Value.Null)))
      ),
      leaf(Value.Arr(Vector(uint(17), keyCred(0x62), uint(5))))
    ) ++ CertLens.pools.get(base.cert).keys.headOption.map(k => regPool(base, k.bytes, 1)).toVector
    val s = edited(base, envelope(base))(fields =>
      fields.filterNot((k, _) => isKey(4)(k) || isKey(22)(k)) :+ (key(4) -> leaf(
        Value.Arr(certs)
      )) :+
        (key(22) -> uint(7))
    )
    val tb = EraTx.conway.body[TxLevel.Top](s.typed)(using EraTx.conway.bodyOf.top)
    (
      s,
      body.getTotalDepositsTxBody(s.pp, isPoolRegistered(s), tb).value,
      body.getTotalRefundsTxBody[TxLevel.Top](s.pp, lookupStakingDeposit(s), tb)(using top).value
    )

  private lazy val depositsJudged: (String, Vector[String]) =
    val (s, deposits, refunds) = craftedDeposits
    "utxo-checks/crafted/deposits-refunds" ->
      (Vector(deposits, refunds).map(_.toString) ++ certDeposits(s).map(_.toString) ++ balance(s))

  test(
    "deposits and refunds of crafted certificates, proposals aside, are the flat path's recorded ones, as is the donation"
  ):
    holds.tupled(depositsJudged)
    val (s, deposits, refunds) = craftedDeposits
    assert(refunds > 0, "refunds")
    assert(deposits >= 2 * 500000000, "two new pools' deposits")
    // proposals aside, the certificates' deposits and refunds are the body's
    assertEquals(certDeposits(s), Vector(deposits, refunds))

  private lazy val wrongTotalCollateral: (String, Vector[String]) =
    val s = seen
      .find(s => s.flat.body.collateral.nonEmpty && s.flat.witnesses.redeemers.nonEmpty)
      .getOrElse(fail("no case with collateral and redeemers"))
    crafted(
      "total-collateral-not-balance",
      edited(s, envelope(s))(fields =>
        fields.filterNot((k, _) => isKey(17)(k)) :+ (key(17) -> uint(1))
      ),
      "feesOK"
    )

  test("crafted from a recorded case: a declared total collateral that is not the balance"):
    holds.tupled(wrongTotalCollateral)

  private lazy val feeOffByOne: Vector[(String, Vector[String])] =
    val s = seen
      .find(s => verdictOf(s, "validateValueNotConservedUTxO").isValid)
      .getOrElse(fail("no case that conserves its value"))
    Vector("over" -> BigInt(1), "under" -> BigInt(-1)).map { (name, d) =>
      crafted(
        s"fee-one-$name",
        edited(s, envelope(s))(fields =>
          fields.filterNot((k, _) => isKey(2)(k)) :+ (key(2) -> uint(s.flat.body.fee + d))
        ),
        "validateValueNotConservedUTxO"
      )
    }

  test("crafted from a recorded case: a fee one lovelace over the balance, and one under"):
    feeOffByOne.foreach(holds.tupled)

  /** An era history of 1-second slots and 100-slot epochs at a tip `tip`: it covers the slots up to
    * the epoch boundary after the tip plus a safe zone of 30 slots (as `HorizonSuite` shows).
    */
  private def historyAt(tip: BigInt): Horizon =
    val params = Horizon.Params(100, 1000, 30, 30)
    Horizon
      .History(Vector.empty, Horizon.Edge(0, 0, 0), params, Some(params))
      .at(Horizon.Transition.Unknown(Some(tip)))

  private def withUpperBound(s: Seen, bound: Option[BigInt]): Seen =
    edited(s, envelope(s))(fields =>
      fields.filterNot((k, _) => isKey(3)(k)) ++ bound.map(b => key(3) -> uint(b)).toVector
    )

  private def outsideForecast(
      s: Seen,
      ei: Horizon,
      slot: BigInt
  ): Verdict[ConwayUtxoPredFailure[Conway]] =
    alonzo(
      AlonzoUtxo.validateOutsideForecast[Conway, TxLevel.Top](
        ei,
        slot,
        xyz.kd5ujc.scarab.ledger.SystemStart(java.time.Instant.EPOCH),
        s.typed
      )(using
        EraTx.conway,
        EraTx.conway.bodyOf.top
      )
    )

  /** `OutsideForecast` of `slot`, as Conway reports Alonzo's failure. */
  private def forecastFailure(slot: BigInt): Verdict[ConwayUtxoPredFailure[Conway]] =
    alonzo(Verdict.failureUnless(false, AlonzoUtxoPredFailure.OutsideForecast[Conway](slot)))

  test("validateOutsideForecast in each of the reference's branches"):
    val redeemed = seen
      .find(_.flat.witnesses.redeemers.nonEmpty)
      .getOrElse(fail("no case with redeemers"))
    val plain =
      seen.find(_.flat.witnesses.redeemers.isEmpty).getOrElse(fail("no case without redeemers"))
    val slot = BigInt(1000)
    // the history at the slot before covers up to 1100; one 200 slots back ends at 900
    val (inside, behind) = (historyAt(slot - 1), historyAt(slot - 200))
    assert(inside.timePs(slot).isDefined && behind.timePs(slot).isEmpty)
    val far = slot + 5000
    assert(inside.timePs(far).isEmpty, "the far end is past the horizon")
    // no end, or no redeemers: nothing is translated, even with the slot past the history
    assertEquals(outsideForecast(withUpperBound(redeemed, None), behind, slot), Verdict.pass)
    assertEquals(outsideForecast(withUpperBound(plain, Some(far)), behind, slot), Verdict.pass)
    // the slot inside the history: an end before it, and one past the horizon, extended linearly
    assertEquals(
      outsideForecast(withUpperBound(redeemed, Some(slot - 5)), inside, slot),
      Verdict.pass
    )
    assertEquals(outsideForecast(withUpperBound(redeemed, Some(far)), inside, slot), Verdict.pass)
    // the slot past the history: an end the history still covers is translated; one before the slot
    // but past the history is not, nor is one after the slot; the failure names the end, not the slot
    assertEquals(outsideForecast(withUpperBound(redeemed, Some(850)), behind, slot), Verdict.pass)
    assertEquals(
      outsideForecast(withUpperBound(redeemed, Some(950)), behind, slot),
      forecastFailure(950)
    )
    assertEquals(
      outsideForecast(withUpperBound(redeemed, Some(far)), behind, slot),
      forecastFailure(far)
    )

  // ---- UTXO's predicates assembled ---------------------------------------------------------------

  /** `babbageUtxoValidation` on `s`: its verdict in the era's failures, with the history `ei`,
    * under `build` and `validation`.
    */
  private def typedUtxo(
      s: Seen,
      build: ReferenceBuild,
      validation: TxEnv.Validation,
      ei: Horizon
  ): Verdict[ConwayUtxoPredFailure[Conway]] =
    BabbageUtxo.babbageUtxoValidation[Conway](
      UtxoEnv(s.slot, s.pp, s.cert),
      s.utxo,
      s.typed,
      UtxoContext(
        s.netId,
        xyz.kd5ujc.scarab.ledger.SystemStart(java.time.Instant.EPOCH),
        ei,
        validation,
        ConwayUtxo.injectShelleyFailure(build)
      )
    )

  /** A UTXO failure under LEDGER's UTXOW, as LEDGER reports it. */
  private def underLedger(f: ConwayUtxoPredFailure[Conway]) =
    xyz.kd5ujc.scarab.ledger.era.rules.ConwayLedgerPredFailure.ConwayUtxowFailure[Conway](
      ConwayUtxowPredFailure.UtxoFailure[Conway](f)
    )

  /** Each build, then each policy under it, the order of a judgment's cells. */
  private val underEach: Vector[(ReferenceBuild, TxEnv.Validation)] =
    for
      build <- ReferenceBuild.values.toVector
      validation <- TxEnv.Validation.values.toVector
    yield (build, validation)

  private lazy val assembledJudged: Vector[(String, Vector[String])] =
    recordedSeen.map(s =>
      s"utxo-checks/assembled/${s.label}" -> underEach.map((build, validation) =>
        cell(typedUtxo(s, build, validation, historyAt(s.slot)))
      )
    )

  test(
    "babbageUtxoValidation gives the flat UTXO's recorded verdict on every recorded case, build and policy"
  ):
    assembledJudged.foreach(holds.tupled)

  test("babbageUtxoValidation's refusal is the reference's, byte for byte, where only UTXO failed"):
    // where every failure the reference recorded is UTXO's, its refusal is UTXO's alone: the
    // era-typed failures, under the case's own build, encoded as the node sends them. A transaction
    // the mempool's measure refuses never reaches the ledger (`txMeasureAlonzo`,
    // `Shelley/Ledger/Mempool.hs:590-612`, ouroboros-consensus 82ecba32; `Capacity.phase1`), so its
    // refusal is the measure's, not UTXO's
    // (a case the reference judged after admitting items first is `ReferenceRefusalSuite`'s)
    val utxoOnly = seen.filter(s =>
      s.families.nonEmpty && s.families.forall(_ == Family.UTXO) && !s.afterAdmitted &&
        xyz.kd5ujc.scarab.node.mempool.Capacity.phase1(s.flat, s.flatPp).isRight
    )
    assert(utxoOnly.nonEmpty, "no case that only UTXO refuses")
    utxoOnly.foreach { s =>
      val failures = typedUtxo(s, s.build, TxEnv.Validation.All, historyAt(s.slot))
        .fold(_.iterator.toVector.map(underLedger), _ => fail(s"${s.label}: no failure"))
      assertEquals(ConwayFailureCborCheck.direct(failures).hex, s.reason.hex, s.label)
    }
    assert(
      utxoOnly.map(_.build).toSet.size >= 2,
      "the cases that only UTXO refuses come from a single build"
    )

  test("babbageUtxoValidation's judgments hold several failures and both injections"):
    def failures(s: Seen, build: ReferenceBuild) =
      typedUtxo(s, build, TxEnv.Validation.All, historyAt(s.slot)).fold(_.iterator.toList, _ => Nil)
    assert(
      recordedSeen.exists(s => failures(s, s.build).size >= 2),
      "no case with several failures"
    )
    assert(
      recordedSeen.exists(s =>
        failures(s, ReferenceBuild.Release11_1) != failures(s, ReferenceBuild.Release11_2)
      ),
      "no case whose failures differ by build"
    )

  /** A case whose interval, forecast and value all fail, and its judgment under 11.1. */
  private lazy val forecastOrder: (Seen, Verdict[ConwayUtxoPredFailure[Conway]]) =
    val s0 = seen
      .find(_.flat.witnesses.redeemers.nonEmpty)
      .getOrElse(fail("no case with redeemers"))
    // an end just before the slot, which the history 200 slots back does not cover either, and a
    // fee one lovelace over the balance: the interval, the forecast and the value all fail
    val s = edited(s0, envelope(s0))(fields =>
      fields.filterNot((k, _) => isKey(3)(k) || isKey(2)(k)) :+ (key(3) -> uint(s0.slot - 1)) :+
        (key(2) -> uint(s0.flat.body.fee + 1))
    )
    val behind = historyAt(s.slot - 200)
    assert(behind.timePs(s.slot - 1).isEmpty)
    (s, typedUtxo(s, ReferenceBuild.Release11_1, TxEnv.Validation.All, behind))

  test("babbageUtxoValidation puts the forecast after the validity interval and before the rest"):
    val (s, verdict) = forecastOrder
    holds("utxo-checks/crafted/forecast-order", Vector(cell(verdict)))
    verdict.fold(_.iterator.toList, _ => Nil) match
      case ConwayUtxoPredFailure.OutsideValidityIntervalUTxO(_, _) ::
          ConwayUtxoPredFailure.OutsideForecast(slot) :: rest =>
        assertEquals(slot, s.slot - 1)
        assert(rest.nonEmpty, "nothing fails after the forecast")
      case other => fail(s"the interval and the forecast do not fail first: $other")

  private lazy val tooBigValues: (String, Vector[String]) =
    val s = withParams(
      seen
        .find(s => s.flat.body.outputs.size + s.flat.body.collateralReturn.size >= 2)
        .getOrElse(fail("no case with two outputs"))
    )(_.copy(maxValueSize = 1))
    crafted("values-over-one-byte", s, "validateOutputTooBigUTxO")

  test("crafted from a recorded case: values over a 1-byte limit, reported last output first"):
    holds.tupled(tooBigValues)

  test("the corpus fails the predicates it can tell apart, and names the rest"):
    val all = pairs(seen.head).map(_._1).toSet
    assertEquals(all -- refusedSomewhere, NeverRefused)

  /** The predicates no recorded case of the corpus fails: for these the comparison saw passes only.
    */
  private val NeverRefused: Set[String] =
    Set(
      "disjointRefInputs",
      "validateTooManyCollateralInputs",
      "validateInputSetEmptyUTxO",
      "validateOutputTooBigUTxO"
    )

  // ---- UTXO whole: the transition with UTXOS and the change of the state -------------------------

  /** One clock for the script contexts: the corpus records no era history, and the comparison was
    * of the two rules, not of the scripts' time.
    */
  private val clock = xyz.kd5ujc.scarab.ledger.PosixClock.Total(slot => slot * 1000)

  private def context(s: Seen, build: ReferenceBuild, validation: TxEnv.Validation) =
    UtxoContext(
      s.netId,
      xyz.kd5ujc.scarab.ledger.SystemStart(java.time.Instant.EPOCH),
      historyAt(s.slot),
      validation,
      ConwayUtxo.injectShelleyFailure(build)
    )

  private def stAnnTx(s: Seen) =
    xyz.kd5ujc.scarab.ledger.era.AlonzoStAnnTx
      .mkAlonzoStAnnTx[Conway](clock.posix, s.pp, s.utxo, Map.empty, s.typed)

  /** The era-typed `conwayUtxoTransition` on `s` from its state, under `build` and `validation`, at
    * the root: its failures in the reference's order, or the state after. A defect fails the suite:
    * the corpus holds none.
    */
  private def wholeRule(
      s: Seen,
      build: ReferenceBuild,
      validation: TxEnv.Validation
  ): Either[Vector[ConwayUtxoPredFailure[Conway]], UTxOState[Conway]] =
    EraUtxoRun.run(
      UtxoEnv(s.slot, s.pp, s.cert),
      stAnnTx(s),
      context(s, build, validation),
      s.utxoState,
      xyz.kd5ujc.scarab.plutus.ScarabEvaluator.default
    ) match
      case Left(defect) => fail(s"${s.label} under ${build.name}: defect $defect")
      case Right(Left(fs)) =>
        val failures = fs.toChain.toVector
        assert(
          EraUtxoRun.causesHeld(failures, xyz.kd5ujc.scarab.plutus.ScarabEvaluator.default),
          s"${s.label}: a script's cause is not the evaluator's on its request"
        )
        Left(failures)
      case Right(Right((after, _))) => Right(after)

  /** A whole rule's cell: a refusal's, or `A:` and the digest of the UTxO state it leaves
    * (`UtxoRecordings.stateCell`).
    */
  private def wholeCell[F](refusal: Vector[F] => String, before: UTxOState[Conway])(
      outcome: Either[Vector[F], UTxOState[Conway]]
  ): String =
    outcome.fold(refusal, FlatRecordings.utxoStateCell(_, before))

  private lazy val wholeOutcomes
      : Vector[(String, Vector[Either[Vector[ConwayUtxoPredFailure[Conway]], UTxOState[Conway]]])] =
    wholeSeen.map(s =>
      s"utxo-checks/utxo/${s.label}" -> underEach.map((build, validation) =>
        wholeRule(s, build, validation)
      )
    )

  private lazy val wholeJudged: Vector[(String, Vector[String])] =
    wholeSeen.zip(wholeOutcomes).map { case (s, (id, os)) =>
      id -> os.map(wholeCell(FlatRecordings.utxoRefused, s.utxoState))
    }

  test(
    "conwayUtxoTransition gives the flat UTXO's recorded failures, in order, or state, on every Conway case"
  ):
    wholeJudged.foreach(holds.tupled)
    val outcomes = wholeOutcomes.flatMap(_._2)
    assert(outcomes.exists(_.left.exists(_.size >= 2)), "no case with several failures")
    assert(outcomes.exists(_.isRight), "no admitted case: the states were never compared")
    assert(
      outcomes.exists(_.left.exists(_.exists {
        case ConwayUtxoPredFailure.UtxosFailure(_) => true
        case _                                     => false
      })),
      "no case UTXOS refuses"
    )

  /** The cases the mempool's measure refuses before the ledger (`txMeasureAlonzo`,
    * `Shelley/Ledger/Mempool.hs:590-612`, ouroboros-consensus 82ecba32): their recorded refusal is
    * the measure's, not the rules', so the whole rules' byte comparisons leave them out, by name.
    */
  private val MeasureRefused: Set[String] = Set(
    "conway-devnet/tx-too-large",
    "conway-devnet/tx-too-large-spent-inputs",
    "conway-devnet/utxo-mint-units-too-big"
  )

  /** `cases` without [[MeasureRefused]], each of which is among them; the measure
    * (`Capacity.phase1`) refuses those and no other, so a case it comes to refuse fails the suite
    * and is not dropped.
    */
  private def withoutMeasureRefusals(cases: Vector[Seen]): Vector[Seen] =
    val (refused, compared) = cases.partition(s => MeasureRefused.contains(s.label))
    assertEquals(refused.map(_.label).toSet, MeasureRefused, "the measure's refusals are not cases")
    assertEquals(
      cases
        .filter(s => xyz.kd5ujc.scarab.node.mempool.Capacity.phase1(s.flat, s.flatPp).isLeft)
        .map(_.label)
        .toSet,
      MeasureRefused,
      "the measure refuses other cases than those named"
    )
    compared

  test("conwayUtxoTransition's refusal is the reference's, byte for byte, where only UTXO failed"):
    // UTXO one level down, an odd depth as under MEMPOOL, LEDGER and UTXOW, where the reference
    // reports its failures in their order (`EraUtxoRun.run`); the cases the mempool's measure
    // refuses before the ledger are left out, by name
    val utxoOnly = withoutMeasureRefusals(
      seen.filter(s => s.families.nonEmpty && s.families.forall(_ == Family.UTXO))
    )
    assert(utxoOnly.nonEmpty, "no case that only UTXO refuses")
    utxoOnly.foreach { s =>
      val failures = EraUtxoRun
        .run(
          UtxoEnv(s.slot, s.pp, s.cert),
          stAnnTx(s),
          context(s, s.build, TxEnv.Validation.All),
          s.utxoState,
          xyz.kd5ujc.scarab.plutus.ScarabEvaluator.default,
          asSubRule = true
        ) match
        case Right(Left(fs)) => fs.toChain.toVector.map(underLedger)
        case other           => fail(s"${s.label}: $other")
      assertEquals(ConwayFailureCborCheck.direct(failures).hex, s.reason.hex, s.label)
    }

  // ---- UTXOW whole: its checks, then UTXO with UTXOS -------------------------------------------

  /** The era-typed `babbageUtxowTransition` on `s`, `depth` levels below the root. */
  private def eraUtxow(s: Seen, build: ReferenceBuild, validation: TxEnv.Validation, depth: Int) =
    EraUtxoRun.Utxow.run(
      UtxoEnv(s.slot, s.pp, s.cert),
      stAnnTx(s),
      context(s, build, validation),
      s.utxoState,
      xyz.kd5ujc.scarab.plutus.ScarabEvaluator.default,
      xyz.kd5ujc.scarab.ledger.Verdicts.Direct,
      xyz.kd5ujc.scarab.plutus.ScarabEvaluator.defaultDecoder,
      depth
    )

  private lazy val utxowOutcomes: Vector[
    (String, Vector[Either[Vector[ConwayUtxowPredFailure[Conway]], UTxOState[Conway]]])
  ] =
    wholeSeen.map(s =>
      s"utxo-checks/utxow/${s.label}" -> underEach.map((build, validation) =>
        eraUtxow(s, build, validation, 0) match
          case Left(defect) => fail(s"${s.label} under ${build.name}: defect $defect")
          case Right(Left(fs)) =>
            val failures = fs.toChain.toVector
            assert(
              EraUtxoRun
                .utxowCausesHeld(failures, xyz.kd5ujc.scarab.plutus.ScarabEvaluator.default),
              s"${s.label}: a script's cause is not the evaluator's on its request"
            )
            Left(failures)
          case Right(Right((after, _))) => Right(after)
      )
    )

  private lazy val utxowJudged: Vector[(String, Vector[String])] =
    wholeSeen.zip(utxowOutcomes).map { case (s, (id, os)) =>
      id -> os.map(wholeCell(FlatRecordings.utxowRefused, s.utxoState))
    }

  test(
    "babbageUtxowTransition gives the flat UTXOW's recorded failures, in order, or state, on every Conway case"
  ):
    utxowJudged.foreach(holds.tupled)
    assert(
      utxowOutcomes
        .flatMap(_._2)
        .exists(_.left.exists(_.exists {
          case ConwayUtxowPredFailure.UtxoFailure(_) => false
          case _                                     => true
        })),
      "no case UTXOW's own checks refuse"
    )

  test("babbageUtxowTransition's refusal is the reference's, byte for byte, where only it failed"):
    // UTXOW two levels down, as under MEMPOOL and LEDGER; the cases the mempool's measure refuses
    // before the ledger are left out, by name, as for UTXO above
    val utxowOnly = withoutMeasureRefusals(
      seen.filter(s =>
        s.families.nonEmpty && s.families.forall(f => f == Family.UTXOW || f == Family.UTXO)
      )
    )
    assert(utxowOnly.exists(_.families.contains(Family.UTXOW)), "no case only UTXOW refuses")
    utxowOnly.foreach { s =>
      val failures = eraUtxow(s, s.build, TxEnv.Validation.All, 2) match
        case Right(Left(fs)) =>
          fs.toChain.toVector.map(
            xyz.kd5ujc.scarab.ledger.era.rules.ConwayLedgerPredFailure.ConwayUtxowFailure[Conway](_)
          )
        case other => fail(s"${s.label}: $other")
      assertEquals(ConwayFailureCborCheck.direct(failures).hex, s.reason.hex, s.label)
    }

  // ---- the recording whole -----------------------------------------------------------------------

  /** Every judgment of this suite the recording holds. */
  private lazy val judged: Vector[(String, Vector[String])] =
    valuesJudged ++ predicatesJudged ++ Vector(
      emptyInputs,
      spentReferences,
      tooManyCollateral,
      narrowedJudged,
      repeatedScripts,
      tiers,
      depositsJudged,
      wrongTotalCollateral
    ) ++ feeOffByOne ++ Vector(
      "utxo-checks/crafted/forecast-order" -> Vector(cell(forecastOrder._2)),
      tooBigValues
    ) ++ assembledJudged ++ wholeJudged ++ utxowJudged

  test("the recording of the flat UTXO's judgments holds this suite's alone, each once"):
    val ids = judged.map(_._1)
    assertEquals(ids.distinct.size, ids.size, "a judgment twice")
    assertEquals(
      ids.toSet,
      FlatRecordings.utxoJudgments.keySet.filter(_.startsWith("utxo-checks/"))
    )

  test(
    "a refusal's cell writes an output from its fields, so each output is checked to be its bytes"
  ) {
    // the refusal's encoding writes an output from its fields (`UtxoCodecs.txOut`), so the same
    // output in the other of its two forms (`[address, value]` and `{0: address, 1: value}`), which
    // the era's output keeps as the bytes it came with, has the same cell: each output a refusal
    // carries is checked to be written as its own bytes (`FlatRecordings.outputsHeld`), as the flat
    // comparison, through the output's stored form, held its bytes too
    val failure = recordedSeen
      .flatMap(s => pairs(s).flatMap((_, t) => t.fold(_.toChain.toVector, _ => Vector.empty)))
      .find(f => FlatRecordings.outputsOf(f).nonEmpty)
      .getOrElse(fail("no recorded refusal carries an output"))
    val out = FlatRecordings.outputsOf(failure).head
    val raw = StoredTxOut.conway.toStored(out).raw
    val other = right("output")(Cbor.decode(raw)).value match
      case Value.Arr(Vector(address, value)) =>
        Value.Map(Vector(key(0) -> address, key(1) -> value))
      case Value.Map(Vector((k0, address), (k1, value))) if isKey(0)(k0) && isKey(1)(k1) =>
        Value.Arr(Vector(address, value))
      case other => fail(s"an output with more than an address and a value: $other")
    val mutantRaw = right("re-encode")(Cbor.encode(other, Cbor.Limits.Unbounded))
    assertNotEquals(mutantRaw, raw)
    val mutant = right("mutant")(
      ConwayUTxO.fromStored(
        Map(
          TxIn(Bytes(Vector.fill(32)(0.toByte)), 0) -> right("stored")(TxOut.fromBytes(mutantRaw))
        )
      )
    ).unUTxO.values.head
    val swapped = failure match
      case ConwayUtxoPredFailure.OutputTooSmallUTxO(os) =>
        ConwayUtxoPredFailure.OutputTooSmallUTxO[Conway](
          os.map(o => if o == out then mutant else o)
        )
      case ConwayUtxoPredFailure.OutputBootAddrAttrsTooBig(os) =>
        ConwayUtxoPredFailure.OutputBootAddrAttrsTooBig[Conway](
          os.map(o => if o == out then mutant else o)
        )
      case ConwayUtxoPredFailure.BabbageOutputTooSmallUTxO(os) =>
        ConwayUtxoPredFailure.BabbageOutputTooSmallUTxO[Conway](
          os.map((o, c) => (if o == out then mutant else o, c))
        )
      case other => fail(s"a failure this test does not rewrite: $other")
    // the era's output keeps the bytes it came with, which its equality and the encoding leave out
    assertEquals(swapped, failure)
    assertEquals(StoredTxOut.conway.toStored(mutant).raw, mutantRaw)
    def under(f: ConwayUtxoPredFailure[Conway]) = Vector(underLedger(f))
    assertEquals(
      ConwayFailureCborCheck.direct(under(swapped)),
      ConwayFailureCborCheck.direct(under(failure))
    )
    assert(FlatRecordings.outputsHeld(Vector(ConwayUtxowPredFailure.UtxoFailure(failure))))
    assert(!FlatRecordings.outputsHeld(Vector(ConwayUtxowPredFailure.UtxoFailure(swapped))))
  }

  test("the recording holds each prefix's judgments, as many as were recorded") {
    def count(prefix: String) =
      FlatRecordings.utxoJudgments.keySet.count(_.startsWith(s"utxo-checks/$prefix/"))
    assertEquals(
      Vector("values", "predicates", "assembled", "utxo", "utxow", "crafted").map(count),
      Vector(84, 84, 84, 112, 112, 12)
    )
  }
