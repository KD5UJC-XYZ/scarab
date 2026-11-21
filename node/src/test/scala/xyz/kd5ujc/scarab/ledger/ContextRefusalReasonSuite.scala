// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.node.ntc.PredicateFailureCbor

/** The whole raw reason of each refused context submission (#434).
  *
  * `fixtures/reference/context-errors/submissions/conway-pv10.json` records, for every transaction
  * the reference refused, the identical `ApplyTxErr` the reference node answered over
  * LocalTxSubmission (`reference.reasonCbor`). Those are the only refused cases in the tree that
  * carry the reference's raw reason rather than the truncated `cardano-cli` text.
  *
  * `V1ContextSuite` reads the one cause that text names and checks that the Scala rule gives that
  * failure; a constructor name appearing in the text does not show that the reference's failure
  * list held only that failure, nor that the encodings agree. Here each refusal is compared as a
  * whole: the Conway LEDGER rule replays the transaction at the recorded protocol parameters, and
  * the encoded Scala refusal must be the reference's reason, byte for byte. The encoding lives in
  * the node module (`PredicateFailureCbor`), which the ledger module cannot reach, so the
  * comparison is made here. Every recorded refusal is compared without exclusions (#781), by the
  * flat LEDGER and by the era-typed one, each translation failure with its contents.
  *
  * Cases that keep no raw reason (the `v1-context` and `plutus-boundaries` files) are still bounded
  * to containment; register entry `v1context-refusal-membership` stays `bounded` until a fresh
  * recording gives them one too (#434).
  */
class ContextRefusalReasonSuite extends FunSuite:
  private final case class Refused(name: String, plutus: PlutusCase, reason: Bytes)

  // The submission files are discovered the way the sibling suite discovers its corpus: a
  // re-recording adds or replaces a file here and the comparison picks it up, rather than this
  // suite pinning one file name and silently skipping anything added beside it.
  private val submissions: Vector[io.circe.Json] =
    val directory = Path.of("fixtures/reference/context-errors/submissions")
    // committed fixtures: a missing directory fails rather than leaving no cases to run
    if !Files.isDirectory(directory) then fail(s"missing committed fixture directory $directory")
    else
      val stream = Files.newDirectoryStream(directory)
      val paths =
        try
          val builder = Vector.newBuilder[Path]
          val it = stream.iterator()
          while it.hasNext do builder += it.next()
          builder.result()
        finally stream.close()
      paths
        .filter(_.toString.endsWith(".json"))
        .sortBy(_.getFileName.toString)
        .flatMap { path =>
          parse(Files.readString(path))
            .fold(error => fail(error.message), identity)
            .asArray
            .getOrElse(fail(s"not submissions: $path"))
        }

  /** Every refused submission, with the raw reason the reference answered. */
  private val refused: Vector[Refused] =
    submissions.flatMap { json =>
      val reference = json.hcursor.downField("reference")
      val name = json.hcursor.get[String]("name").fold(e => fail(e.message), identity)
      val accepted = reference.get[Boolean]("accepted").fold(e => fail(e.message), identity)
      if accepted then None
      else
        val raw = reference.get[String]("reasonCbor").fold(e => fail(e.message), identity)
        val reason = Bytes.fromHex(raw).fold(e => fail(e.toString), identity)
        Some(Refused(name, PlutusCase.fromJson(json), reason))
    }

  private def utxoOf(c: PlutusCase): Map[TxIn, TxOut] = c.spent ++ c.references ++ c.collateral

  /** The reason the Conway transaction rule gives for `c`, as the node sends it. */
  private def encodedRefusal(c: PlutusCase, name: String): Bytes =
    val nes = Build.funded(Build.nes[Conway](5, c.params), utxoOf(c))
    TestLedger.conwayLedger(nes, c.tx, TestLedger.env) match
      case Right(_) => fail(s"$name: the node accepts what the reference refused")
      case Left(LedgerFailure.Rejected.Single(_, failure)) =>
        PredicateFailureCbor
          .applyTxError(Vector(failure))
          .fold(error => fail(error.message), identity)
      case Left(other) => fail(s"$name: not a single refusal: $other")

  test("every refused submission carries the reference's whole raw reason") {
    assertEquals(refused.size, 12, "refusals in the recorded submissions")
    assert(refused.forall(_.reason.nonEmpty), "a refusal with an empty reason")
  }

  refused.foreach { r =>
    test(s"${r.name}: the ledger rule's encoded refusal is the reference's raw reason") {
      assertEquals(
        encodedRefusal(r.plutus, r.name),
        r.reason,
        s"encoded refusal of ${r.plutus.name}"
      )
    }
  }

  /** The era-typed LEDGER (`ConwayLedger.conwayLedgerTransition`) on `c`, read by Conway's decoder
    * from the bytes the flat transaction keeps, from the same chain, one level below the root as
    * under MEMPOOL: its failures in the node's flat form (`EraLedgerRun.flat`, a collection's
    * translation failures with their contents), encoded as the node sends them.
    */
  private def eraRefusal(c: PlutusCase, name: String): Bytes =
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
    import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayPParams, ConwayTx, ConwayTxOut}
    import xyz.kd5ujc.scarab.ledger.era.rules.{
      ConwayGovPredFailure,
      ConwayUtxo,
      ConwayUtxowPredFailure,
      LedgerEnv,
      UtxoContext
    }
    val env = TestLedger.env
    val nes = Build.funded(Build.nes[Conway](5, c.params), utxoOf(c))
    val witnesses = c.tx.witnesses.raw.getOrElse(fail(s"$name: no witness bytes"))
    val tx = ConwayTx
      .decode(
        Block.Transaction(c.tx.body.raw, witnesses, c.tx.auxiliary, c.tx.isValid),
        xyz.kd5ujc.scarab.protocol.StateLayout.Node11_1
      )
      .fold(e => fail(s"$name: $e"), identity)
    val pp = PParams[Conway](ConwayPParams.fromStored(c.params).fold(e => fail(s"$e"), identity))
    val utxo = UTxO[Conway](
      utxoOf(c).map((in, out) => in -> ConwayTxOut.fromStored(out).fold(e => fail(s"$e"), identity))
    )
    val params = Horizon.Params(100, 1000, 30, 30)
    val history = Horizon
      .History(Vector.empty, Horizon.Edge(0, 0, 0), params, Some(params))
      .at(Horizon.Transition.Unknown(Some(env.slot)))
    val clock = env.clock.getOrElse(fail("the environment has no clock"))
    val judged = EraLedgerRun.ledger(
      rules.NativeRules.globalsOf(env),
      LedgerEnv[Conway](
        env.slot,
        Some(EpochNo.fromBigInt(env.epoch).fold(e => fail(s"$e"), identity)),
        TxIx.txIxFromIntegral(0).getOrElse(fail("index 0")),
        pp,
        nes.es.chainAccount
      ),
      AlonzoStAnnTx.mkAlonzoStAnnTx[Conway](clock.posix, pp, utxo, Map.empty, tx),
      UtxoContext[Conway](
        Network.of(BigInt(env.network)).getOrElse(fail(s"network ${env.network}")),
        0,
        history,
        env.validation,
        ConwayUtxo.injectShelleyFailure(env.referenceBuild)
      ),
      nes.es.ls,
      env.evaluator,
      env.verdicts,
      env.decoder,
      depth = 1
    )
    val t = EraTx.conway
    val body = t.body[TxLevel.Top](tx)(using t.bodyOf.top)
    val purposes = ConwayUtxowPredFailure.storedPurposes(
      EraUTxO.conway.getScriptsNeeded[TxLevel.Top](utxo, body)(using
        EraUTxO.conway.txOf.bodyOf.top
      ),
      utxo
    )
    val read = ConwayGovPredFailure.readOf(
      EraTxBody.conway.proposalProcedures[TxLevel.Top](body)(using EraTxBody.conway.top).toVector
    )
    judged match
      case Right(Left(fs)) =>
        PredicateFailureCbor
          .applyTxError(
            fs.toChain.toVector.map(f =>
              EraLedgerRun.flat(f, purposes, read).fold(e => fail(s"$name: $e"), identity)
            )
          )
          .fold(error => fail(error.message), identity)
      case Right(Right(_)) => fail(s"$name: the era LEDGER accepts what the reference refused")
      case Left(defect)    => fail(s"$name: era defect $defect")

  refused.foreach { r =>
    test(s"${r.name}: the era-typed LEDGER's encoded refusal is the reference's raw reason") {
      assertEquals(eraRefusal(r.plutus, r.name), r.reason, s"era refusal of ${r.plutus.name}")
    }
  }
