// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import java.nio.file.Path
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Rational, Value}
import xyz.kd5ujc.scarab.ledger.{PredicateFailure, Tx, TxIn, TxOut, Utxo}
import xyz.kd5ujc.scarab.ledger.era.{
  ConwayEraPParams,
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
import xyz.kd5ujc.scarab.ledger.era.rules.{AllegraUtxo, AlonzoUtxo, BabbageUtxo, ShelleyUtxo}
import xyz.kd5ujc.scarab.ledger.{RefScripts, testByteStrings}
import xyz.kd5ujc.scarab.ledger.rules.{CertLens, Deposits, Test as Verdict, UtxoTests}
import xyz.kd5ujc.scarab.ledger.state.{
  Coin,
  Conway,
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
import xyz.kd5ujc.scarab.protocol.Era
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
      cert: ConwayCertState
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
        val bytes: Bytes = right(s"$what item")(LocalSubmission.unwrap(rc.item))
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
          right(s"$what typed")(ConwayTx.fromEnvelope(bytes)),
          stored,
          right(s"$what typed UTxO")(ConwayUTxO.fromStored(stored)),
          flatPp,
          PParams[Conway](right(s"$what typed parameters")(ConwayPParams.fromStored(flatPp))),
          ConwayNewEpochState.ls.andThen(ConwayLedgerState.cert).get(nes)
        )
      }
    }

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
        BabbageUtxo
          .disjointRefInputs[Conway](
            s.pp,
            body.inputs[TxLevel.Top](tb)(using top),
            body.referenceInputs[TxLevel.Top](tb)(using top)
          )
      ),
      (
        "validateOutsideValidityIntervalUTxO",
        UtxoTests.validityInterval(fb, s.slot),
        AllegraUtxo.validateOutsideValidityIntervalUTxO[Conway, TxLevel.Top](s.slot, tb)(using
          body,
          top
        )
      ),
      (
        "validateInputSetEmptyUTxO",
        UtxoTests.inputSetEmpty(fb),
        ShelleyUtxo.validateInputSetEmptyUTxO[Conway, TxLevel.Top](tb)(using body, top)
      ),
      (
        "validateBadInputsUTxO",
        UtxoTests
          .badInputs(Utxo.fromMap(s.stored), fb.inputs ++ fb.collateral ++ fb.referenceInputs),
        ShelleyUtxo.validateBadInputsUTxO[Conway](s.utxo, body.allInputs(tb))
      ),
      (
        "validateWrongNetwork",
        UtxoTests.wrongNetwork(s.network, outs),
        ShelleyUtxo
          .validateWrongNetwork[Conway](
            s.netId,
            body.allSizedOutputs[TxLevel.Top](tb)(using top).map(_.value)
          )(using
            body.outputsOf
          )
      ),
      (
        "validateWrongNetworkWithdrawal",
        UtxoTests.wrongNetworkWithdrawal(s.network, fb),
        ShelleyUtxo.validateWrongNetworkWithdrawal[Conway, TxLevel.Top](s.netId, tb)(using
          body,
          top
        )
      ),
      (
        "validateWrongNetworkInTxBody",
        UtxoTests.wrongNetworkInTxBody(s.network, fb),
        AlonzoUtxo.validateWrongNetworkInTxBody[Conway, TxLevel.Top](s.netId, tb)(using body, top)
      ),
      (
        "validateTooManyCollateralInputs",
        UtxoTests.tooManyCollateralInputs(s.flatPp, fb),
        AlonzoUtxo.validateTooManyCollateralInputs[Conway](s.pp, tb)
      ),
      (
        "feesOK",
        UtxoTests.feesOK[Conway](s.flatPp, Utxo.fromMap(s.stored), s.flat, flatMinFee(s)),
        BabbageUtxo.feesOK[Conway](s.pp, s.typed, s.utxo)
      ),
      (
        "validateMaxTxSizeUTxO",
        UtxoTests.maxTxSize(s.flatPp, s.flat),
        ShelleyUtxo.validateMaxTxSizeUTxO[Conway, TxLevel.Top](s.pp, s.typed)(using
          tx,
          tx.bodyOf.top
        )
      ),
      (
        "validateExUnitsTooBigUTxO",
        UtxoTests.exUnitsTooBig(s.flatPp, s.flat),
        AlonzoUtxo.validateExUnitsTooBigUTxO[Conway, TxLevel.Top](s.pp, s.typed)(using
          tx,
          tx.bodyOf.top
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
      typed = right("edited typed")(ConwayTx.fromEnvelope(again))
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
    val ref = TxIn(Bytes(Vector.fill(32)(9.toByte)), 0)
    val stored =
      base.stored ++ spent.map(_ -> withRefScript(base, 900)) + (ref -> withRefScript(base, 3))
    val s0 = base.copy(stored = stored, utxo = right("typed UTxO")(ConwayUTxO.fromStored(stored)))
    val s = edited(s0, envelope(s0))(fields =>
      val inputs =
        fields.collectFirst { case (k, v) if isKey(0)(k) => v }.getOrElse(fail("no inputs"))
      val refs = inputs.value match
        case Value.Arr(xs) =>
          leaf(Value.Arr(xs :+ leaf(Value.Arr(Vector(bytesOf(ref.id), uint(0))))))
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
    val base = seen.head
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
    assert(refunds > 0 && deposits > 0)
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

  test("the corpus fails the predicates it can tell apart, and names the rest"):
    val all = pairs(seen.head).map(_._1).toSet
    assertEquals(all -- refusedSomewhere, NeverRefused)

  /** The predicates no Conway case of the corpus fails: for these the comparison saw passes only.
    */
  private val NeverRefused: Set[String] =
    Set("disjointRefInputs", "validateTooManyCollateralInputs", "validateInputSetEmptyUTxO")
