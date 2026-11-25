// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import io.circe.HCursor
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.testByteStrings
import xyz.kd5ujc.scarab.ledger.rules.RedeemerIndexCases
import xyz.kd5ujc.scarab.ledger.state.{
  Alonzo,
  Babbage,
  Conway,
  ConwayLedgerState,
  ConwayNewEpochState,
  EpochNo,
  EraTag,
  LedgerState,
  ShelleyBasedEra,
  TxIx
}
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoStAnnTx,
  EraLedgerRun,
  EraTx,
  EraTxBody,
  EraUtxoRun,
  IsPhase2Valid,
  Network,
  PParams,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayPParams, ConwayTx, ConwayUTxO}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  ConwayLedgerPredFailure,
  ConwayUtxo,
  UtxoContext,
  UtxoEnv as EraUtxoEnv
}
import xyz.kd5ujc.scarab.protocol.{ReferenceBuild, StateLayout}
import xyz.kd5ujc.scarab.node.mempool.MempoolState
import xyz.kd5ujc.scarab.protocol.Era

/** The redeemer index of a repeated certificate, and the missing redeemers of every purpose,
  * against what the reference node (11.1.3) answered.
  *
  * `fixtures/devnet/redeemer-index.jsonl` holds each transaction of the redeemer-index cases the
  * `e2e` scenario sent the node (`redeemer_index` in `scripts/devnet/erarefusal.py`) with the
  * node's verdict: accepted, or refused with its raw `ApplyTxErr` over LocalTxSubmission and the
  * rendering `cardano-cli` printed. Each line also keeps the outputs the transaction spends, the
  * Plutus V1 cost model in force and the provenance of the run (commit, command, image, protocol
  * version). `devnet.py refused-fixture --redeemer-index` assembles the file from the run
  * directory, line for line.
  *
  * Each transaction is put to the node's pool rules ([[RedeemerIndexCases.judged]], the node's
  * evaluator) in a chain that holds the outputs it spends, the script's stake credential registered
  * and the pools it delegates to, at the recorded protocol version and cost model. One the
  * reference accepted must be accepted; one it refused must be refused with the failures that
  * [[PreConwayFailureCbor]] writes as exactly the recorded bytes. Conway's cases run its era UTXOW
  * and LEDGER, whose failures [[ConwayFailureCbor]] writes as exactly the recorded bytes.
  */
class RedeemerIndexReferenceSuite extends FunSuite:
  private final case class Record(
      label: String,
      era: Era,
      major: Int,
      raw: Bytes,
      accepted: Boolean,
      reason: Option[Bytes],
      cli: String,
      spent: Vector[(TxIn, TxOut)],
      costModel: Vector[BigInt]
  ):
    /** The case, without the era the label starts with. */
    def kind: String = label.stripPrefix(era.toString.toLowerCase + "-")

    def tx: Tx = MempoolState.parseIn(raw, era).fold(r => fail(s"$label: ${r.message}"), _.tx)

  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(e => fail(e.message), identity)

  private def output(hex: String): TxOut =
    Cbor
      .decode(bytes(hex))
      .left
      .map(e => s"$e")
      .flatMap(node =>
        TxOut
          .decode(node, Address.Mode.Rigorous, TxOut.Forms.Stored, ScriptKind.newestKnown)
          .left
          .map(_.message)
      )
      .fold(e => fail(e), identity)

  private def input(key: String): TxIn = key.split('#') match
    case Array(id, ix) => TxIn(bytes(id), BigInt(ix))
    case _             => fail(s"input $key")

  private def record(json: HCursor): Record =
    def get[A: io.circe.Decoder](key: String): A =
      json.get[A](key).fold(e => fail(e.message), identity)
    val label = get[String]("label")
    val era = get[String]("era") match
      case "alonzo"  => Era.Alonzo
      case "babbage" => Era.Babbage
      case "conway"  => Era.Conway
      case other     => fail(s"$label: era $other")
    val accepted = get[String]("verdict") match
      case "accepted" => true
      case "refused"  => false
      case other      => fail(s"$label: verdict $other")
    Record(
      label,
      era,
      json
        .downField("protocol")
        .downField("protocolVersion")
        .get[Int]("major")
        .fold(e => fail(e.message), identity),
      bytes(get[String]("tx")),
      accepted,
      json.get[String]("reason").toOption.map(bytes),
      json.get[String]("cli").getOrElse(""),
      get[Map[String, String]]("spent").toVector.map((k, v) => input(k) -> output(v)),
      get[Vector[BigInt]]("costModelPlutusV1")
    )

  private lazy val records: Vector[Record] =
    Files
      .readAllLines(Path.of("fixtures/devnet/redeemer-index.jsonl"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(line => record(parse(line).fold(e => fail(e.message), identity).hcursor))

  /** The node's verdict on the recorded transaction: the chain of [[RedeemerIndexCases.chain]] with
    * the outputs it spends, the script credential its certificates delegate registered, the pools
    * they delegate to, and the recorded cost model of Plutus V1 (language 0).
    */
  private def judge[E <: EraTag](r: Record)(using
      ShelleyBasedEra[E]
  ): Either[Vector[PredicateFailure], Long] =
    val tx = r.tx
    val delegations = tx.body.certificates.collect {
      case Certificate.Delegate(credential, Some(pool), _, _) => credential -> pool
    }
    val credential = delegations.map(_._1).distinct match
      case Vector(one) => one
      case other       => fail(s"${r.label}: the certificates delegate $other")
    val pp = TestLedger.params(r.major).copy(costModels = Map(0 -> r.costModel))
    val nes =
      RedeemerIndexCases.chain[E](pp, r.spent, credential, delegations.map(_._2).distinct)
    RedeemerIndexCases.judged[E](nes, TestLedger.env)(tx)

  private def verdict(r: Record): Either[Vector[PredicateFailure], Long] = r.era match
    case Era.Alonzo  => judge[Alonzo](r)
    case Era.Babbage => judge[Babbage](r)
    case other       => fail(s"${r.label}: era $other")

  /** Every case of a complete recording (`erarefusal.redeemer_index_labels`), with the reference's
    * verdict on it (true: accepted). A recording missing one is incomplete, not a pass (CNF-2).
    */
  private val expected = Map(
    "alonzo-missing-redeemers-every-purpose" -> false,
    "alonzo-repeated-certificate-redeemers-0-1" -> false,
    "alonzo-repeated-certificate-no-redeemer" -> false,
    "alonzo-distinct-certificates-redeemer-0" -> false,
    "alonzo-repeated-certificate-redeemer-0" -> true,
    "babbage-missing-redeemers-every-purpose" -> false,
    "babbage-repeated-certificate-redeemers-0-1" -> false,
    "babbage-repeated-certificate-no-redeemer" -> false,
    "babbage-distinct-certificates-redeemer-0" -> false,
    "babbage-repeated-certificate-redeemer-0" -> true,
    "conway-missing-redeemers-every-purpose" -> false,
    "conway-distinct-certificates-redeemer-0" -> false,
    "conway-distinct-certificates-redeemers-0-1" -> true
  )

  test("the recording holds every case once, with the reference's verdict on each") {
    val labels = records.map(_.label)
    assertEquals(labels.diff(labels.distinct), Vector.empty, "cases recorded more than once")
    assertEquals(labels.toSet, expected.keySet, "the cases recorded")
    assertEquals(
      records.map(r => r.label -> r.accepted).toMap,
      expected,
      "verdicts (true: accepted)"
    )
  }

  // the eras before Conway on their rules; Conway's cases run its era rules below
  records.filterNot(_.era == Era.Conway).foreach { r =>
    test(s"${r.era} ${r.kind}: the node's verdict is the reference's") {
      (verdict(r), r.reason) match
        case (Right(_), None) => assert(r.accepted, s"${r.label}: no reason, yet refused")
        case (Right(scripts), Some(_)) =>
          fail(s"${r.label}: accepted ($scripts scripts), the reference refused it: ${r.cli}")
        case (Left(failures), None) =>
          fail(s"${r.label}: refused with $failures, the reference accepted it")
        case (Left(failures), Some(reason)) =>
          assertEquals(
            FlatRefusal
              .applyTxError(r.era, failures)
              .fold(error => fail(error.message), identity)
              .map(_.hex),
            Some(reason.hex),
            s"${r.label}: $failures"
          )
    }
  }

  // the era-typed UTXOW whole (`babbageUtxowTransition`, with UTXO and UTXOS) on the Conway cases,
  // from the same chain: an accepted case is admitted, and a refused one's failures, two levels
  // below the root as under MEMPOOL and LEDGER, encode to the reference's refusal byte for byte
  records.filter(_.era == Era.Conway).foreach { r =>
    test(s"${r.era} ${r.kind}: the era-typed UTXOW's verdict is the reference's") {
      val flatTx = r.tx
      val credential = flatTx.body.certificates.collect {
        case Certificate.Delegate(credential, Some(_), _, _) => credential
      }.distinct match
        case Vector(one) => one
        case other       => fail(s"${r.label}: the certificates delegate $other")
      val pools = flatTx.body.certificates.collect {
        case Certificate.Delegate(_, Some(pool), _, _) => pool
      }.distinct
      val flatPp = TestLedger.params(r.major).copy(costModels = Map(0 -> r.costModel))
      val nes = RedeemerIndexCases.chain[Conway](flatPp, r.spent, credential, pools)(using
        ShelleyBasedEra.SConway
      )
      val ls = ConwayNewEpochState.ls.get(nes)
      val pp = PParams[Conway](ConwayPParams.fromStored(flatPp).fold(e => fail(s"$e"), identity))
      val tx = ConwayTx
        .fromEnvelope(r.raw, StateLayout.Node11_1)
        .fold(e => fail(s"${r.label}: $e"), identity)
      val utxo = ConwayUTxO.fromStored(r.spent.toMap).fold(e => fail(s"${r.label}: $e"), identity)
      val env = TestLedger.env
      val clock = env.clock.getOrElse(fail("the environment has no clock"))
      val params = Horizon.Params(100, 1000, 30, 30)
      val history = Horizon
        .History(Vector.empty, Horizon.Edge(0, 0, 0), params, Some(params))
        .at(Horizon.Transition.Unknown(Some(env.slot)))
      val result = EraUtxoRun.Utxow.run(
        EraUtxoEnv(env.slot, pp, ConwayLedgerState.cert.get(ls)),
        AlonzoStAnnTx.mkAlonzoStAnnTx[Conway](clock.posix, pp, utxo, Map.empty, tx),
        UtxoContext(
          Network.of(BigInt(env.network)).getOrElse(fail(s"network ${env.network}")),
          xyz.kd5ujc.scarab.ledger.SystemStart(java.time.Instant.EPOCH),
          history,
          TxEnv.Validation.All,
          ConwayUtxo.injectShelleyFailure(ReferenceBuild.Release11_1)
        ),
        LedgerState.utxo[Conway].get(ls),
        env.evaluator,
        env.verdicts,
        env.decoder,
        depth = 2
      )
      (result, r.reason) match
        case (Right(Right(_)), None)               => assert(r.accepted, r.label)
        case (Right(Left(failures)), Some(reason)) =>
          // UTXOW's failures as LEDGER embeds them, written by the era's encoder
          assertEquals(
            ConwayFailureCborCheck
              .direct(failures.toChain.toVector.map(ConwayLedgerPredFailure.ConwayUtxowFailure(_)))
              .hex,
            reason.hex,
            s"${r.label}: $failures"
          )
        case other => fail(s"${r.label}: $other, the reference's ${r.reason.map(_.hex)}")
    }
  }

  // the era-typed LEDGER whole on the Conway cases, from the same chain: the case the reference
  // accepted is admitted, spends its inputs and runs each of its scripts once, and a refused one's
  // failures, one level below the root as under MEMPOOL, encode to the reference's refusal byte
  // for byte
  records.filter(_.era == Era.Conway).foreach { r =>
    test(s"${r.era} ${r.kind}: the era-typed LEDGER's verdict is the reference's") {
      val flatTx = r.tx
      val credential = flatTx.body.certificates.collect {
        case Certificate.Delegate(credential, Some(_), _, _) => credential
      }.distinct match
        case Vector(one) => one
        case other       => fail(s"${r.label}: the certificates delegate $other")
      val pools = flatTx.body.certificates.collect {
        case Certificate.Delegate(_, Some(pool), _, _) => pool
      }.distinct
      val flatPp = TestLedger.params(r.major).copy(costModels = Map(0 -> r.costModel))
      val nes = RedeemerIndexCases.chain[Conway](flatPp, r.spent, credential, pools)(using
        ShelleyBasedEra.SConway
      )
      val ls = ConwayNewEpochState.ls.get(nes)
      val pp = PParams[Conway](ConwayPParams.fromStored(flatPp).fold(e => fail(s"$e"), identity))
      val tx = ConwayTx
        .fromEnvelope(r.raw, StateLayout.Node11_1)
        .fold(e => fail(s"${r.label}: $e"), identity)
      val utxo = ConwayUTxO.fromStored(r.spent.toMap).fold(e => fail(s"${r.label}: $e"), identity)
      val env = TestLedger.env
      val globals = xyz.kd5ujc.scarab.ledger.rules.NativeRules.globalsOf(env)
      val clock = env.clock.getOrElse(fail("the environment has no clock"))
      val params = Horizon.Params(100, 1000, 30, 30)
      val history = Horizon
        .History(Vector.empty, Horizon.Edge(0, 0, 0), params, Some(params))
        .at(Horizon.Transition.Unknown(Some(env.slot)))
      val t = EraTx.conway
      val body = t.body[TxLevel.Top](tx)(using t.bodyOf.top)
      def judged(depth: Int) = EraLedgerRun.ledger(
        globals,
        xyz.kd5ujc.scarab.ledger.era.rules.LedgerEnv[Conway](
          env.slot,
          Some(EpochNo.fromBigInt(env.epoch).fold(e => fail(s"$e"), identity)),
          TxIx.txIxFromIntegral(0).getOrElse(fail("index 0")),
          pp,
          nes.es.chainAccount
        ),
        AlonzoStAnnTx.mkAlonzoStAnnTx[Conway](clock.posix, pp, utxo, Map.empty, tx),
        UtxoContext(
          Network.of(BigInt(env.network)).getOrElse(fail(s"network ${env.network}")),
          xyz.kd5ujc.scarab.ledger.SystemStart(java.time.Instant.EPOCH),
          history,
          TxEnv.Validation.All,
          ConwayUtxo.injectShelleyFailure(ReferenceBuild.Release11_1)
        ),
        ls,
        env.evaluator,
        env.verdicts,
        env.decoder,
        depth
      )
      (judged(0), r.reason) match
        case (Right(Right((state, events))), None) =>
          assert(r.accepted, r.label)
          // UTXO's `updateUTxOState`: the inputs spent and the outputs added
          // (`Shelley/Rules/Utxo.hs:584-600`, cardano-ledger f649f975)
          val after = state.utxo.utxo.utxo
          val b = EraTxBody.conway
          b.inputs[TxLevel.Top](body)(using b.top).foreach { in =>
            assert(!after.contains(in), s"${r.label}: $in is spent")
          }
          val id = b.id[TxLevel.Top](body)(using b.top)
          // each output of the signed body at its index, as it was written
          assertEquals(b.outputs[TxLevel.Top](body)(using b.top).size, flatTx.body.outputs.size)
          flatTx.body.outputs.zipWithIndex.foreach { (out, ix) =>
            assertEquals(
              after.toMap.get(TxIn(id, BigInt(ix))),
              Some(out),
              s"${r.label}: output $ix"
            )
          }
          // a valid transaction keeps its collateral: UTXOS's `babbageEvalScriptsTxValid` spends
          // only the inputs (`Babbage/Rules/Utxos.hs:172-190`, cardano-ledger f649f975)
          assert(flatTx.body.collateral.nonEmpty, r.label)
          flatTx.body.collateral.foreach { in =>
            assert(after.contains(in), s"${r.label}: collateral $in is kept")
          }
          // the fee added to the fee pot (`utxosFeesL <>~ feeTxBodyL`,
          // `Shelley/Rules/Utxo.hs:600`, cardano-ledger f649f975)
          assertEquals(
            state.utxo.fees.value,
            ls.utxo.fees.value + flatTx.body.fee,
            s"${r.label}: the fee pot"
          )
          // the credential delegated to the pool of the last of its delegations: DELEG's
          // `delegStake` (`Conway/Rules/Deleg.hs:338-345`, cardano-ledger f649f975), in the order
          // of the body's certificates
          val lastPool = flatTx.body.certificates
            .collect { case Certificate.Delegate(`credential`, Some(pool), _, _) => pool }
            .lastOption
            .getOrElse(fail(s"${r.label}: no delegation"))
          val staking = xyz.kd5ujc.scarab.ledger.rules.Convert
            .credential[xyz.kd5ujc.scarab.ledger.state.Staking](credential)
            .fold(e => fail(s"$e"), identity)
          assertEquals(
            state.cert.dState.accounts.states.get(staking).flatMap(_.pool).map(_.bytes),
            Some(lastPool),
            s"${r.label}: the delegation"
          )
          // one passing transaction, each redeemer's script run once: UTXOS's one
          // `SuccessfulPlutusScriptsEvent` with every script (`Babbage/Rules/Utxos.hs:155`,
          // cardano-ledger f649f975), through LEDGER's embedding
          val redeemers = flatTx.witnesses.redeemers.size.toLong
          assert(redeemers > 0, r.label)
          assertEquals(
            EraLedgerRun.counters(t.isValid(tx) == IsPhase2Valid.Phase2Valid, events),
            Some((1L, 0L, redeemers)),
            r.label
          )
        case (Right(Left(_)), Some(reason)) =>
          val era = judged(1) match
            case Right(Left(fs)) => fs.toChain.toVector
            case other           => fail(s"${r.label}: one level down, $other")
          // the era failures written by their own encoder
          assertEquals(ConwayFailureCborCheck.direct(era).hex, reason.hex, s"${r.label}: direct")
        case other => fail(s"${r.label}: $other, the reference's ${r.reason.map(_.hex)}")
    }
  }
