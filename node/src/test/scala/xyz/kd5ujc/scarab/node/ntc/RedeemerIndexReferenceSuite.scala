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
  EraUTxO,
  EraUtxoRun,
  IsPhase2Valid,
  Network,
  PParams,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayPParams, ConwayTx, ConwayUTxO}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  ConwayUtxo,
  ConwayUtxowPredFailure,
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
  * [[PredicateFailureCbor]] writes as exactly the recorded bytes.
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
    case Era.Conway  => judge[Conway](r)
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

  records.foreach { r =>
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
      val u = EraUTxO.conway
      val t = EraTx.conway
      val purposes = ConwayUtxowPredFailure.storedPurposes(
        u.getScriptsNeeded[TxLevel.Top](utxo, t.body[TxLevel.Top](tx)(using t.bodyOf.top))(using
          u.txOf.bodyOf.top
        ),
        utxo
      )
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
        case (Right(Right(_)), None) => assert(r.accepted, r.label)
        case (Right(Left(failures)), Some(reason)) =>
          val flat = failures.toChain.toVector.map(f =>
            ConwayUtxowPredFailure
              .toStored(f, purposes)
              .fold(e => fail(s"${r.label}: $e"), identity)
          )
          assertEquals(
            FlatRefusal
              .applyTxError(r.era, flat)
              .fold(error => fail(error.message), identity)
              .map(_.hex),
            Some(reason.hex),
            s"${r.label}: $failures"
          )
        case other => fail(s"${r.label}: $other, the reference's ${r.reason.map(_.hex)}")
    }
  }

  // the era-typed LEDGER whole on the Conway cases, from the same chain: the case the reference
  // accepted is admitted and leaves the flat LEDGER's state and counters, and a refused one's failures, one level
  // below the root as under MEMPOOL, encode to the reference's refusal byte for byte
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
      val u = EraUTxO.conway
      val t = EraTx.conway
      val body = t.body[TxLevel.Top](tx)(using t.bodyOf.top)
      val purposes = ConwayUtxowPredFailure.storedPurposes(
        u.getScriptsNeeded[TxLevel.Top](utxo, body)(using u.txOf.bodyOf.top),
        utxo
      )
      val read = xyz.kd5ujc.scarab.ledger.era.rules.ConwayGovPredFailure.readOf(
        EraTxBody.conway.proposalProcedures[TxLevel.Top](body)(using EraTxBody.conway.top).toVector
      )
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
          val flat = xyz.kd5ujc.scarab.ledger.rules.Judgment
            .run(
              xyz.kd5ujc.scarab.ledger.rules.Ledger,
              xyz.kd5ujc.scarab.ledger.rules.Judgment.Policy.Accumulate
            )(
              globals,
              xyz.kd5ujc.scarab.ledger.rules.LedgerEnv(env, nes.es.chainAccount.treasury),
              xyz.kd5ujc.scarab.ledger.rules.Counted(ls, PlutusStats.zero),
              flatTx
            )
            .fold(e => fail(s"${r.label}: flat defect $e"), identity)
            .fold(fs => fail(s"${r.label}: the flat LEDGER refused $fs"), identity)
          assert(cats.Eq[LedgerState[Conway]].eqv(state, flat.state), r.label)
          // the counters the scripts' events give through LEDGER's embedding are the flat rule's
          assertEquals(
            EraLedgerRun.counters(t.isValid(tx) == IsPhase2Valid.Phase2Valid, events),
            Some((flat.plutus.succeeded, flat.plutus.failed, flat.plutus.scripts)),
            r.label
          )
          assert(flat.plutus.scripts > 0, r.label)
        case (Right(Left(_)), Some(reason)) =>
          val era = judged(1) match
            case Right(Left(fs)) => fs.toChain.toVector
            case other           => fail(s"${r.label}: one level down, $other")
          // the era failures written by their own encoder, without the flat form
          assertEquals(ConwayFailureCborCheck.direct(era).hex, reason.hex, s"${r.label}: direct")
          val failures = era.map(f =>
            EraLedgerRun.flat(f, purposes, read).fold(e => fail(s"${r.label}: $e"), identity)
          )
          assertEquals(
            FlatRefusal
              .applyTxError(r.era, failures)
              .fold(error => fail(error.message), identity)
              .map(_.hex),
            Some(reason.hex),
            s"${r.label}: $failures"
          )
        case other => fail(s"${r.label}: $other, the reference's ${r.reason.map(_.hex)}")
    }
  }
