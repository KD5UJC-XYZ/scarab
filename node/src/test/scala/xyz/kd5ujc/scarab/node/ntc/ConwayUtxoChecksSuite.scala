// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import xyz.kd5ujc.scarab.protocol.StateLayout
import java.nio.file.Path
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Rational, Value}
import xyz.kd5ujc.scarab.ledger.{
  Horizon,
  PredicateFailure,
  Tx,
  TxEnv,
  TxIn,
  TxOut,
  Utxo,
  UtxoFailure,
  UtxowFailure
}
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
  InjectUtxoFailure,
  ShelleyUtxo,
  ShelleyUtxoPredFailure,
  UtxoContext,
  UtxoEnv
}
import xyz.kd5ujc.scarab.ledger.{RefScripts, testByteStrings}
import xyz.kd5ujc.scarab.ledger.era.EraUtxoRun
import xyz.kd5ujc.scarab.ledger.rules.{
  CertLens,
  Counted,
  Deposits,
  Judgment,
  RuleT,
  StAnnTx,
  Test as Verdict,
  Utxo as FlatUtxoRule,
  UtxoEnv as FlatUtxoEnv,
  UtxoTests
}
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

/** Conway's UTXO predicates on the era types against the flat ones, on every transaction of the
  * recorded Conway refusal corpora: the same verdict, failures included, from each. A predicate the
  * corpus never fails is named, since only a failing case tells the two apart.
  */
class ConwayUtxoChecksSuite extends FunSuite:
  import ReferenceRefusalSuite.*

  private given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway

  private val body = EraTxBody.conway
  private val top = body.top

  /** One Conway case as both paths see it. */
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
      val network = setup.globals.network
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
          Network.of(BigInt(network)).getOrElse(fail(s"$what network $network")),
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
          rc.reason
        )
      }
    }

  /** A failure of the era types in the node's flat form; none of UTXO's predicates reports UTXOS's
    * collection errors, the projection's one lossy case.
    */
  private def stored(f: ConwayUtxoPredFailure[Conway]): PredicateFailure =
    ConwayUtxoPredFailure.toStored(f).fold(e => fail(s"collection errors: $e"), identity)

  /** A typed predicate's verdict with its failures injected into Conway's (`inject`) and in the
    * flat form, so it compares with the flat predicate's.
    */
  private def asConway[A](test: Verdict[A])(
      inject: A => ConwayUtxoPredFailure[Conway]
  ): Verdict[PredicateFailure] =
    test.leftMap(_.map(a => stored(inject(a))))

  /** A Shelley predicate's failures through the 11.2 injection, which keeps each failure a Conway
    * transaction gives Shelley's predicates as it is: the predicates are compared, not the
    * injection, which `assembled` compares under each build.
    */
  private def shelley(test: Verdict[ShelleyUtxoPredFailure[Conway]]): Verdict[PredicateFailure] =
    asConway(test)(ConwayUtxo.injectShelleyFailure11_2.injectFailure)

  private def allegra(test: Verdict[AllegraUtxoPredFailure[Conway]]): Verdict[PredicateFailure] =
    asConway(test)(InjectUtxoFailure.conwayAllegra.injectFailure)

  private def alonzo(test: Verdict[AlonzoUtxoPredFailure[Conway]]): Verdict[PredicateFailure] =
    asConway(test)(InjectUtxoFailure.conwayAlonzo.injectFailure)

  private def babbage(test: Verdict[BabbageUtxoPredFailure[Conway]]): Verdict[PredicateFailure] =
    asConway(test)(InjectUtxoFailure.conwayBabbage.injectFailure)

  private def conway(test: Verdict[ConwayUtxoPredFailure[Conway]]): Verdict[PredicateFailure] =
    asConway(test)(identity)

  /** Each predicate: its name, the flat verdict and the typed one. */
  private def pairs(
      s: Seen
  ): Vector[(String, Verdict[PredicateFailure], Verdict[PredicateFailure])] =
    val fb = s.flat.body
    val tx = EraTx.conway
    val tb = tx.body[TxLevel.Top](s.typed)(using tx.bodyOf.top)
    val outs = fb.outputs ++ fb.collateralReturn.toVector
    Vector(
      (
        "disjointRefInputs",
        UtxoTests.disjointRefInputs(s.flatPp, fb),
        babbage(
          BabbageUtxo
            .disjointRefInputs[Conway](
              s.pp,
              body.inputs[TxLevel.Top](tb)(using top),
              body.referenceInputs[TxLevel.Top](tb)(using top)
            )
        )
      ),
      (
        "validateOutsideValidityIntervalUTxO",
        UtxoTests.validityInterval(fb, s.slot),
        allegra(
          AllegraUtxo.validateOutsideValidityIntervalUTxO[Conway, TxLevel.Top](s.slot, tb)(using
            body,
            top
          )
        )
      ),
      (
        "validateInputSetEmptyUTxO",
        UtxoTests.inputSetEmpty(fb),
        shelley(ShelleyUtxo.validateInputSetEmptyUTxO[Conway, TxLevel.Top](tb)(using body, top))
      ),
      (
        "validateBadInputsUTxO",
        UtxoTests
          .badInputs(Utxo.fromMap(s.stored), fb.inputs ++ fb.collateral ++ fb.referenceInputs),
        shelley(ShelleyUtxo.validateBadInputsUTxO[Conway](s.utxo, body.allInputs(tb)))
      ),
      (
        "validateWrongNetwork",
        UtxoTests.wrongNetwork(s.network, outs),
        shelley(
          ShelleyUtxo
            .validateWrongNetwork[Conway](
              s.netId,
              body.allSizedOutputs[TxLevel.Top](tb)(using top).map(_.value)
            )(using
              body.outputsOf
            )
        )
      ),
      (
        "validateWrongNetworkWithdrawal",
        UtxoTests.wrongNetworkWithdrawal(s.network, fb),
        shelley(
          ShelleyUtxo.validateWrongNetworkWithdrawal[Conway, TxLevel.Top](s.netId, tb)(using
            body,
            top
          )
        )
      ),
      (
        "validateWrongNetworkInTxBody",
        UtxoTests.wrongNetworkInTxBody(s.network, fb),
        alonzo(
          AlonzoUtxo.validateWrongNetworkInTxBody[Conway, TxLevel.Top](s.netId, tb)(using
            body,
            top
          )
        )
      ),
      (
        "validateTooManyCollateralInputs",
        UtxoTests.tooManyCollateralInputs(s.flatPp, fb),
        alonzo(AlonzoUtxo.validateTooManyCollateralInputs[Conway](s.pp, tb))
      ),
      (
        "validateOutputTooSmallUTxO",
        UtxoTests.outputTooSmall[Conway](s.flatPp, outs),
        babbage(
          BabbageUtxo.validateOutputTooSmallUTxO[Conway](
            s.pp,
            body.allSizedOutputs[TxLevel.Top](tb)(using top)
          )(using body.outputsOf)
        )
      ),
      (
        "validateOutputTooBigUTxO",
        UtxoTests.outputTooBig[Conway](s.flatPp, outs),
        alonzo(
          AlonzoUtxo.validateOutputTooBigUTxO[Conway](
            s.pp,
            body.allSizedOutputs[TxLevel.Top](tb)(using top).map(_.value)
          )(using body.outputsOf)
        )
      ),
      (
        "validateOutputBootAddrAttrsTooBig",
        UtxoTests.bootAddrAttrsTooBig(outs),
        shelley(
          ShelleyUtxo.validateOutputBootAddrAttrsTooBig[Conway](
            body.allSizedOutputs[TxLevel.Top](tb)(using top).map(_.value)
          )(using body.outputsOf)
        )
      ),
      (
        "validateValueNotConservedUTxO", {
          val (consumed, produced) = flatBalance(s)
          UtxoTests.valueNotConserved(consumed, produced)
        },
        shelley(ShelleyUtxo.validateValueNotConservedUTxO[Conway](s.pp, s.utxo, s.cert, tb))
      ),
      (
        "feesOK",
        UtxoTests.feesOK[Conway](s.flatPp, Utxo.fromMap(s.stored), s.flat, flatMinFee(s)),
        conway(BabbageUtxo.feesOK[Conway](s.pp, s.typed, s.utxo))
      ),
      (
        "validateMaxTxSizeUTxO",
        UtxoTests.maxTxSize(s.flatPp, s.flat),
        shelley(
          ShelleyUtxo.validateMaxTxSizeUTxO[Conway, TxLevel.Top](s.pp, s.typed)(using
            tx,
            tx.bodyOf.top
          )
        )
      ),
      (
        "validateExUnitsTooBigUTxO",
        UtxoTests.exUnitsTooBig(s.flatPp, s.flat),
        alonzo(
          AlonzoUtxo.validateExUnitsTooBigUTxO[Conway, TxLevel.Top](s.pp, s.typed)(using
            tx,
            tx.bodyOf.top
          )
        )
      )
    )

  /** The flat path's minimum fee, as its UTXO computes it
    * ([[xyz.kd5ujc.scarab.ledger.rules.Utxo]]'s `validate`).
    */
  private def flatMinFee(s: Seen): BigInt =
    val pp = s.flatPp
    val units = s.flat.witnesses.totalExUnits
    val scriptFee =
      (pp.priceMem * Rational(units.mem) + pp.priceSteps * Rational(units.steps)).ceil
    pp.minFeeA * s.flat.size + pp.minFeeB + scriptFee +
      RefScripts.tierRefScriptFee(
        pp.minFeeRefScriptCostPerByte,
        RefScripts.refScriptsSize(Utxo.fromMap(s.stored), s.flat.body)
      )

  /** The flat path's value consumed and produced, as its UTXO computes them from the deposits and
    * refunds of [[Deposits.of]].
    */
  private def flatBalance(
      s: Seen
  ): (xyz.kd5ujc.scarab.ledger.Value, xyz.kd5ujc.scarab.ledger.Value) =
    import xyz.kd5ujc.scarab.ledger.Value as FlatValue
    val body = s.flat.body
    val (deposits, refunds) = Deposits.of(s.flatPp, s.cert, body)
    val spent = body.inputs.flatMap(s.stored.get)
    val withdrawn = body.withdrawals.valuesIterator.map(_.value).sum
    val consumed = spent.foldLeft(FlatValue.zero)(_ + _.value) +
      FlatValue.coin(withdrawn + refunds) + FlatValue.minted(body.mint)
    val produced = body.outputs.foldLeft(FlatValue.zero)(_ + _.value) +
      FlatValue.coin(body.fee + deposits + body.donation) + FlatValue.burned(body.mint)
    (consumed, produced)

  private def lookupStakingDeposit(s: Seen)(c: Credential[Staking]): Option[Coin] =
    CertLens.accounts.get(s.cert).get(c).map(_.deposit.toCoin)

  private def isPoolRegistered(s: Seen)(k: KeyHash[StakePool]): Boolean =
    CertLens.pools.get(s.cert).contains(k)

  test("getMinFeeTxUtxo is the flat path's minimum fee on every Conway case"):
    val u = EraUTxO.conway
    seen.foreach { s =>
      assertEquals(
        u.getMinFeeTxUtxo[TxLevel.Top](s.pp, s.typed, s.utxo)(using u.txOf.bodyOf.top).value,
        flatMinFee(s),
        s.label
      )
    }

  test("getConsumedValue and getProducedValue are the flat path's on every Conway case"):
    val u = EraUTxO.conway
    val stored = StoredTxOut.conway
    seen.foreach { s =>
      val tb = u.txOf.body[TxLevel.Top](s.typed)(using u.txOf.bodyOf.top)
      val (consumed, produced) = flatBalance(s)
      assertEquals(
        stored.storedValue(
          u.getConsumedValue[TxLevel.Top](s.pp, lookupStakingDeposit(s), s.utxo, tb)(using
            u.txOf.bodyOf.top
          )
        ),
        consumed,
        s"${s.label}: consumed"
      )
      assertEquals(
        stored.storedValue(u.getProducedValue(s.pp, isPoolRegistered(s), tb)),
        produced,
        s"${s.label}: produced"
      )
    }

  test(
    "certsTotalDepositsTxBody and certsTotalRefundsTxBody are the flat path's on every Conway case"
  ):
    val c = EraCertState.conway
    seen.foreach { s =>
      val tb = EraTx.conway.body[TxLevel.Top](s.typed)(using EraTx.conway.bodyOf.top)
      val (deposits, refunds) = Deposits.of(s.flatPp, s.cert, s.flat.body)
      assertEquals(
        c.certsTotalDepositsTxBody(s.pp, s.cert, tb)(using body).value,
        deposits,
        s.label
      )
      assertEquals(
        c.certsTotalRefundsTxBody[TxLevel.Top](s.pp, c.certDStateL.get(s.cert).accounts, tb)(using
          body,
          top
        ).value,
        refunds,
        s.label
      )
    }

  test("the corpus has Conway cases, each read by both paths"):
    assert(clue(seen.size) > 0)

  test("each typed predicate gives the flat one's verdict on every Conway case"):
    seen.foreach { s =>
      pairs(s).foreach { (name, flat, typed) =>
        assertEquals(typed, flat, s"${s.label}: $name")
      }
    }

  /** The predicates some case fails, so the comparison above saw them refuse. */
  private val refusedSomewhere: Set[String] =
    seen.flatMap(s => pairs(s).collect { case (name, flat, _) if flat.isInvalid => name }).toSet

  /** The bytes of `s` 's transaction with its body's fields edited by `f`, and both paths' reading
    * of them. The predicates are pure, so a body whose witnesses no longer sign it still tells them
    * apart.
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

  private def compared(s: Seen, name: String): Unit =
    val (_, flat, typed) = pairs(s).find(_._1 == name).getOrElse(fail(s"no predicate $name"))
    assert(flat.isInvalid, s"${s.label}: the crafted case passes $name")
    assertEquals(typed, flat, s"${s.label}: $name")

  private def envelope(s: Seen): Bytes = s.bytes

  test("crafted from a recorded case: an empty input set"):
    val s = seen.head
    compared(
      edited(s, envelope(s))(fields =>
        fields.map((k, v) =>
          if isKey(0)(k) then k -> Node(Value.Arr(Vector.empty), Bytes.empty) else k -> v
        )
      ),
      "validateInputSetEmptyUTxO"
    )

  test("crafted from a recorded case: reference inputs that are also spent, at version 10"):
    val s = withParams(seen.head)(_.copy(protocolMajor = 10))
    compared(
      edited(s, envelope(s))(fields =>
        val inputs =
          fields.collectFirst { case (k, v) if isKey(0)(k) => v }.getOrElse(fail("no inputs"))
        fields.filterNot((k, _) => isKey(18)(k)) :+ (key(18) -> inputs)
      ),
      "disjointRefInputs"
    )

  test("crafted from a recorded case: more collateral inputs than the parameters allow"):
    val s = seen.find(_.flat.body.collateral.nonEmpty).getOrElse(fail("no case with collateral"))
    compared(withParams(s)(_.copy(maxCollateralInputs = 0)), "validateTooManyCollateralInputs")

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

  test("the collateral count is narrowed to a Word16, as the reference compares it, in both paths"):
    val s = withParams(seen.head)(_.copy(maxCollateralInputs = 3))
    def verdicts(n: Int) =
      val (_, flat, typed) =
        pairs(withCollateral(s, n)).find(_._1 == "validateTooManyCollateralInputs").get
      assertEquals(typed, flat, s"$n collateral inputs")
      flat
    assert(verdicts(1 << 16).isValid, "65,536 inputs count as 0")
    assert(verdicts((1 << 16) + 3).isValid, "65,539 inputs count as 3")
    assertEquals(
      verdicts((1 << 16) + 4),
      Verdict.failureUnless(
        false,
        PredicateFailure.utxo(
          xyz.kd5ujc.scarab.ledger.UtxoFailure
            .TooManyCollateralInputs(xyz.kd5ujc.scarab.ledger.Mismatch(4, 3))
        )
      )
    )

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

  test(
    "the minimum fee counts repeated reference scripts and an input in both sets once, as the flat path"
  ):
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
    assert(
      RefScripts.refScriptsSize(Utxo.fromMap(s.stored), s.flat.body) > 25600L,
      "past the first tier"
    )
    val u = EraUTxO.conway
    assertEquals(
      u.getMinFeeTxUtxo[TxLevel.Top](s.pp, s.typed, s.utxo)(using u.txOf.bodyOf.top).value,
      flatMinFee(s)
    )

  test("tierRefScriptFee is the flat path's at and around each tier's boundary"):
    val p = EraPParams.conway
    val pp = seen.head.pp
    val base = seen.head.flatPp.minFeeRefScriptCostPerByte
    Vector(0L, 1L, 25599L, 25600L, 25601L, 51199L, 51200L, 204800L, 1048576L).foreach { size =>
      assertEquals(
        ConwayTx
          .tierRefScriptFee(
            p.ppRefScriptCostMultiplierG.get(pp),
            p.ppRefScriptCostStrideG.get(pp),
            ConwayEraPParams.ppMinFeeRefScriptCostPerByteL[Conway].get(pp).rational,
            size
          )
          .value,
        RefScripts.tierRefScriptFee(base, size),
        s"size $size"
      )
    }

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

  test(
    "deposits and refunds of crafted certificates, proposals aside, are the flat path's, as is the donation"
  ):
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
    val b = EraTxBody.conway
    val tb = b.top
    val body = EraTx.conway.body[TxLevel.Top](s.typed)(using EraTx.conway.bodyOf.top)
    val (deposits, refunds) = Deposits.of(s.flatPp, s.cert, s.flat.body)
    assertEquals(b.getTotalDepositsTxBody(s.pp, isPoolRegistered(s), body).value, deposits)
    assertEquals(
      b.getTotalRefundsTxBody[TxLevel.Top](s.pp, lookupStakingDeposit(s), body)(using tb).value,
      refunds
    )
    assert(refunds > 0, "refunds")
    assert(deposits >= 2 * base.flatPp.poolDeposit, "two new pools' deposits")
    val c = EraCertState.conway
    assertEquals(c.certsTotalDepositsTxBody(s.pp, s.cert, body)(using b).value, deposits)
    assertEquals(
      c.certsTotalRefundsTxBody[TxLevel.Top](s.pp, c.certDStateL.get(s.cert).accounts, body)(using
        b,
        tb
      ).value,
      refunds
    )
    val u = EraUTxO.conway
    val (consumed, produced) = flatBalance(s)
    assertEquals(
      StoredTxOut.conway.storedValue(u.getProducedValue(s.pp, isPoolRegistered(s), body)),
      produced
    )
    assertEquals(
      StoredTxOut.conway.storedValue(
        u.getConsumedValue[TxLevel.Top](s.pp, lookupStakingDeposit(s), s.utxo, body)(using
          u.txOf.bodyOf.top
        )
      ),
      consumed
    )

  test("crafted from a recorded case: a declared total collateral that is not the balance"):
    val s = seen
      .find(s => s.flat.body.collateral.nonEmpty && s.flat.witnesses.redeemers.nonEmpty)
      .getOrElse(fail("no case with collateral and redeemers"))
    val wrong = edited(s, envelope(s))(fields =>
      fields.filterNot((k, _) => isKey(17)(k)) :+ (key(17) -> uint(1))
    )
    compared(wrong, "feesOK")

  test("crafted from a recorded case: a fee one lovelace over the balance, and one under"):
    val s = seen
      .find(s =>
        pairs(s).exists((n, flat, _) => n == "validateValueNotConservedUTxO" && flat.isValid)
      )
      .getOrElse(fail("no case that conserves its value"))
    Vector(BigInt(1), BigInt(-1)).foreach { d =>
      compared(
        edited(s, envelope(s))(fields =>
          fields.filterNot((k, _) => isKey(2)(k)) :+ (key(2) -> uint(s.flat.body.fee + d))
        ),
        "validateValueNotConservedUTxO"
      )
    }

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

  private def outsideForecast(s: Seen, ei: Horizon, slot: BigInt): Verdict[PredicateFailure] =
    alonzo(
      AlonzoUtxo.validateOutsideForecast[Conway, TxLevel.Top](ei, slot, 0, s.typed)(using
        EraTx.conway,
        EraTx.conway.bodyOf.top
      )
    )

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
      Verdict.failureUnless(false, PredicateFailure.utxo(UtxoFailure.OutsideForecast(950)))
    )
    assertEquals(
      outsideForecast(withUpperBound(redeemed, Some(far)), behind, slot),
      Verdict.failureUnless(false, PredicateFailure.utxo(UtxoFailure.OutsideForecast(far)))
    )

  /** UTXO's predicates on both paths: the flat `UtxoTests.babbage` with the case's balance and
    * minimum fee, and the era-typed `babbageUtxoValidation` with the history `ei`, under `build`
    * and `validation`.
    */
  private def assembled(
      s: Seen,
      build: ReferenceBuild,
      validation: TxEnv.Validation,
      ei: Horizon
  ): (Verdict[PredicateFailure], Verdict[PredicateFailure]) =
    val (consumed, produced) = flatBalance(s)
    val env = TxEnv(
      slot = s.slot,
      epoch = 0,
      network = s.network,
      evaluator = xyz.kd5ujc.scarab.plutus.ScarabEvaluator.default,
      decoder = xyz.kd5ujc.scarab.plutus.ScarabEvaluator.defaultDecoder,
      validation = validation,
      referenceBuild = build
    )
    val flat = UtxoTests.babbage[Conway](
      s.flatPp,
      env,
      StAnnTx(s.flat, Utxo.fromMap(s.stored)),
      flatMinFee(s),
      consumed,
      produced
    )
    val typed = BabbageUtxo.babbageUtxoValidation[Conway](
      UtxoEnv(s.slot, s.pp, s.cert),
      s.utxo,
      s.typed,
      UtxoContext(s.netId, 0, ei, validation, ConwayUtxo.injectShelleyFailure(build))
    )
    (flat, conway(typed))

  test(
    "babbageUtxoValidation gives the flat UTXO's verdict on every Conway case, build and policy"
  ):
    seen.foreach { s =>
      for
        build <- ReferenceBuild.values
        validation <- TxEnv.Validation.values
      do
        val (flat, typed) = assembled(s, build, validation, historyAt(s.slot))
        assertEquals(typed, flat, s"${s.label} under ${build.name}, $validation")
    }

  test("babbageUtxoValidation's refusal is the reference's, byte for byte, where only UTXO failed"):
    // where every failure the reference recorded is UTXO's, its refusal is UTXO's alone: the
    // era-typed failures, under the case's own build, encoded as the node sends them. A transaction
    // the mempool's measure refuses never reaches the ledger (`txMeasureAlonzo`,
    // `Shelley/Ledger/Mempool.hs:590-612`, ouroboros-consensus 82ecba32; `Capacity.phase1`), so its
    // refusal is the measure's, not UTXO's
    val utxoOnly = seen.filter(s =>
      s.families.nonEmpty && s.families.forall(_ == Family.UTXO) &&
        xyz.kd5ujc.scarab.node.mempool.Capacity.phase1(s.flat, s.flatPp).isRight
    )
    assert(utxoOnly.nonEmpty, "no case that only UTXO refuses")
    utxoOnly.foreach { s =>
      val (_, typed) = assembled(s, s.build, TxEnv.Validation.All, historyAt(s.slot))
      val failures = typed.fold(_.iterator.toVector, _ => fail(s"${s.label}: no failure"))
      assertEquals(
        PredicateFailureCbor.applyTxError(failures).fold(e => fail(s"${s.label}: $e"), _.hex),
        s.reason.hex,
        s.label
      )
    }
    assert(
      utxoOnly.map(_.build).toSet.size >= 2,
      "the cases that only UTXO refuses come from a single build"
    )

  test("the comparison of babbageUtxoValidation sees several failures and both injections"):
    def failures(s: Seen, build: ReferenceBuild) =
      assembled(s, build, TxEnv.Validation.All, historyAt(s.slot))._1
        .fold(_.iterator.toList, _ => Nil)
    assert(seen.exists(s => failures(s, s.build).size >= 2), "no case with several failures")
    assert(
      seen.exists(s =>
        failures(s, ReferenceBuild.Release11_1) != failures(s, ReferenceBuild.Release11_2)
      ),
      "no case whose failures differ by build"
    )

  test("babbageUtxoValidation puts the forecast after the validity interval and before the rest"):
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
    val (flat, typed) =
      assembled(s, ReferenceBuild.Release11_1, TxEnv.Validation.All, behind)
    val flatFailures = flat.fold(_.iterator.toList, _ => Nil)
    flatFailures match
      case (interval @ PredicateFailure.Utxow(
            xyz.kd5ujc.scarab.ledger.UtxowFailure.Utxo(_: UtxoFailure.OutsideValidityInterval)
          )) :: rest =>
        assert(rest.nonEmpty, "nothing fails after the interval")
        assertEquals(
          typed.fold(_.iterator.toList, _ => Nil),
          interval :: PredicateFailure.utxo(UtxoFailure.OutsideForecast(s.slot - 1)) :: rest
        )
      case other => fail(s"the interval does not fail first: $other")

  test("crafted from a recorded case: values over a 1-byte limit, reported last output first"):
    val s = withParams(
      seen
        .find(s => s.flat.body.outputs.size + s.flat.body.collateralReturn.size >= 2)
        .getOrElse(fail("no case with two outputs"))
    )(_.copy(maxValueSize = 1))
    compared(s, "validateOutputTooBigUTxO")

  test("the corpus fails the predicates it can tell apart, and names the rest"):
    val all = pairs(seen.head).map(_._1).toSet
    assertEquals(all -- refusedSomewhere, NeverRefused)

  /** The predicates no Conway case of the corpus fails: for these the comparison saw passes only.
    */
  private val NeverRefused: Set[String] =
    Set(
      "disjointRefInputs",
      "validateTooManyCollateralInputs",
      "validateInputSetEmptyUTxO",
      "validateOutputTooBigUTxO"
    )

  // ---- UTXO whole: the transition with UTXOS and the change of the state -------------------------

  /** One clock for both paths' script contexts: the corpus records no era history, and the
    * comparison is of the two rules, not of the scripts' time.
    */
  private val clock = xyz.kd5ujc.scarab.ledger.PosixClock.Total(slot => slot * 1000)

  /** What a whole rule made of a case: its failures in the reference's order, in the comparison's
    * form, or the state after. A defect fails the suite: the corpus holds none.
    */
  private enum Whole:
    case Refused(failures: List[EraUtxoRun.Failure])
    case Admitted(state: UTxOState[Conway])

  /** The era-typed `conwayUtxoTransition` and the flat UTXO (`rules.Utxo`) on `s` from its state,
    * under `build` and `validation`.
    */
  private def wholeRule(
      s: Seen,
      build: ReferenceBuild,
      validation: TxEnv.Validation
  ): (Whole, Whole) =
    val what = s"${s.label} under ${build.name}, $validation"
    val evaluator = xyz.kd5ujc.scarab.plutus.ScarabEvaluator.default
    val stAnnTx = xyz.kd5ujc.scarab.ledger.era.AlonzoStAnnTx
      .mkAlonzoStAnnTx[Conway](clock.posix, s.pp, s.utxo, Map.empty, s.typed)
    val context =
      UtxoContext(s.netId, 0, historyAt(s.slot), validation, ConwayUtxo.injectShelleyFailure(build))
    val era =
      EraUtxoRun.run(UtxoEnv(s.slot, s.pp, s.cert), stAnnTx, context, s.utxoState, evaluator) match
        case Left(defect) => fail(s"$what: era defect $defect")
        case Right(Left(fs)) =>
          Whole.Refused(
            fs.toChain.toList.map(f =>
              EraUtxoRun.eraForm(f, purposes(s)).getOrElse(fail(s"$what: $f"))
            )
          )
        case Right(Right((after, _))) => Whole.Admitted(after)
    val env = TxEnv(
      slot = s.slot,
      epoch = 0,
      network = s.network,
      evaluator = evaluator,
      decoder = xyz.kd5ujc.scarab.plutus.ScarabEvaluator.defaultDecoder,
      validation = validation,
      referenceBuild = build,
      clock = Some(clock)
    )
    val flat = Judgment.run(FlatUtxoRule, RuleT.Policy.Accumulate)(
      xyz.kd5ujc.scarab.ledger.TestLedger.globals,
      FlatUtxoEnv(env, s.flatPp, s.cert),
      Counted(s.utxoState, xyz.kd5ujc.scarab.ledger.PlutusStats.zero),
      StAnnTx(s.flat, Utxo.fromMap(s.stored))
    ) match
      case Left(defect) => fail(s"$what: flat defect $defect")
      case Right(Left(fs)) =>
        Whole.Refused(fs.toChain.toList.map(f => EraUtxoRun.form(f).getOrElse(fail(s"$what: $f"))))
      case Right(Right(counted)) => Whole.Admitted(counted.state)
    (era, flat)

  test("conwayUtxoTransition gives the flat UTXO's failures, in order, on every Conway case"):
    val outcomes = for
      s <- seen
      build <- ReferenceBuild.values.toVector
      validation <- TxEnv.Validation.values.toVector
    yield
      val what = s"${s.label} under ${build.name}, $validation"
      wholeRule(s, build, validation) match
        case (Whole.Admitted(ours), Whole.Admitted(theirs)) =>
          assert(summon[cats.Eq[UTxOState[Conway]]].eqv(ours, theirs), what)
          Whole.Admitted(ours)
        case (era, flat) =>
          assertEquals(era, flat, what)
          era
    assert(
      outcomes.exists {
        case Whole.Refused(fs) => fs.size >= 2
        case _                 => false
      },
      "no case with several failures"
    )
    assert(
      outcomes.exists {
        case Whole.Admitted(_) => true
        case _                 => false
      },
      "no admitted case: the states were never compared"
    )
    assert(
      outcomes.exists {
        case Whole.Refused(fs) =>
          fs.exists {
            case EraUtxoRun.Failure.Utxo(_) => false
            case _                          => true
          }
        case _ => false
      },
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
          xyz.kd5ujc.scarab.ledger.era.AlonzoStAnnTx
            .mkAlonzoStAnnTx[Conway](clock.posix, s.pp, s.utxo, Map.empty, s.typed),
          UtxoContext(
            s.netId,
            0,
            historyAt(s.slot),
            TxEnv.Validation.All,
            ConwayUtxo.injectShelleyFailure(s.build)
          ),
          s.utxoState,
          xyz.kd5ujc.scarab.plutus.ScarabEvaluator.default,
          asSubRule = true
        ) match
        case Right(Left(fs)) => fs.toChain.toVector.map(stored)
        case other           => fail(s"${s.label}: $other")
      assertEquals(
        PredicateFailureCbor.applyTxError(failures).fold(e => fail(s"${s.label}: $e"), _.hex),
        s.reason.hex,
        s.label
      )
    }

  // ---- UTXOW whole: its checks, then UTXO with UTXOS -------------------------------------------

  /** The purposes of `s`'s scripts needed in their flat form, for a missing redeemer's failure. */
  private def purposes(s: Seen) =
    val u = EraUTxO.conway
    val t = EraTx.conway
    xyz.kd5ujc.scarab.ledger.era.rules.ConwayUtxowPredFailure.storedPurposes(
      u.getScriptsNeeded[TxLevel.Top](s.utxo, t.body[TxLevel.Top](s.typed)(using t.bodyOf.top))(
        using u.txOf.bodyOf.top
      ),
      s.utxo
    )

  /** The era-typed `babbageUtxowTransition` on `s`, `depth` levels below the root. */
  private def eraUtxow(s: Seen, build: ReferenceBuild, validation: TxEnv.Validation, depth: Int) =
    EraUtxoRun.Utxow.run(
      UtxoEnv(s.slot, s.pp, s.cert),
      xyz.kd5ujc.scarab.ledger.era.AlonzoStAnnTx
        .mkAlonzoStAnnTx[Conway](clock.posix, s.pp, s.utxo, Map.empty, s.typed),
      UtxoContext(
        s.netId,
        0,
        historyAt(s.slot),
        validation,
        ConwayUtxo.injectShelleyFailure(build)
      ),
      s.utxoState,
      xyz.kd5ujc.scarab.plutus.ScarabEvaluator.default,
      xyz.kd5ujc.scarab.ledger.Verdicts.Direct,
      xyz.kd5ujc.scarab.plutus.ScarabEvaluator.defaultDecoder,
      depth
    )

  /** The era-typed UTXOW and the flat one (`rules.Utxow`) on `s` from its state. */
  private def wholeUtxow(
      s: Seen,
      build: ReferenceBuild,
      validation: TxEnv.Validation
  ): (Whole, Whole) =
    val what = s"${s.label} under ${build.name}, $validation (UTXOW)"
    val era = eraUtxow(s, build, validation, 0) match
      case Left(defect) => fail(s"$what: era defect $defect")
      case Right(Left(fs)) =>
        Whole.Refused(
          fs.toChain.toList.map(f =>
            EraUtxoRun.Utxow.eraForm(f, purposes(s)).getOrElse(fail(s"$what: $f"))
          )
        )
      case Right(Right((after, _))) => Whole.Admitted(after)
    val env = TxEnv(
      slot = s.slot,
      epoch = 0,
      network = s.network,
      evaluator = xyz.kd5ujc.scarab.plutus.ScarabEvaluator.default,
      decoder = xyz.kd5ujc.scarab.plutus.ScarabEvaluator.defaultDecoder,
      validation = validation,
      referenceBuild = build,
      clock = Some(clock)
    )
    val flat = Judgment.run(xyz.kd5ujc.scarab.ledger.rules.Utxow, RuleT.Policy.Accumulate)(
      xyz.kd5ujc.scarab.ledger.TestLedger.globals,
      FlatUtxoEnv(env, s.flatPp, s.cert),
      Counted(s.utxoState, xyz.kd5ujc.scarab.ledger.PlutusStats.zero),
      StAnnTx(s.flat, Utxo.fromMap(s.stored))
    ) match
      case Left(defect) => fail(s"$what: flat defect $defect")
      case Right(Left(fs)) =>
        Whole.Refused(fs.toChain.toList.map(f => EraUtxoRun.form(f).getOrElse(fail(s"$what: $f"))))
      case Right(Right(counted)) => Whole.Admitted(counted.state)
    (era, flat)

  test("babbageUtxowTransition gives the flat UTXOW's failures, in order, on every Conway case"):
    val outcomes = for
      s <- seen
      build <- ReferenceBuild.values.toVector
      validation <- TxEnv.Validation.values.toVector
    yield
      val what = s"${s.label} under ${build.name}, $validation"
      wholeUtxow(s, build, validation) match
        case (Whole.Admitted(ours), Whole.Admitted(theirs)) =>
          assert(summon[cats.Eq[UTxOState[Conway]]].eqv(ours, theirs), what)
          Whole.Admitted(ours)
        case (era, flat) =>
          assertEquals(era, flat, what)
          era
    assert(
      outcomes.exists {
        case Whole.Refused(fs) =>
          fs.exists {
            case EraUtxoRun.Failure.Utxo(PredicateFailure.Utxow(UtxowFailure.Utxo(_))) => false
            case EraUtxoRun.Failure.Utxo(PredicateFailure.Utxow(_))                    => true
            case _                                                                     => false
          }
        case _ => false
      },
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
          fs.toChain.toVector.map(f =>
            xyz.kd5ujc.scarab.ledger.era.rules.ConwayUtxowPredFailure
              .toStored(f, purposes(s))
              .fold(e => fail(s"${s.label}: $e"), identity)
          )
        case other => fail(s"${s.label}: $other")
      assertEquals(
        PredicateFailureCbor.applyTxError(failures).fold(e => fail(s"${s.label}: $e"), _.hex),
        s.reason.hex,
        s.label
      )
    }
