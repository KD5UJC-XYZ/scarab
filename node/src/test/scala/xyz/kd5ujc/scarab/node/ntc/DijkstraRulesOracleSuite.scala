// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import io.circe.{ACursor, Json, parser}
import java.nio.file.{Files, Path}
import java.time.Instant
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Rational, Value}
import xyz.kd5ujc.scarab.ledger.{
  Address,
  Block,
  ByteStrings,
  EpochGeometry,
  LedgerGlobals,
  ProtocolParams,
  ScriptKind,
  TestGeometry,
  TestLedger,
  TxIn,
  TxOut,
  Utxo
}
import xyz.kd5ujc.scarab.ledger.dijkstra.*
import xyz.kd5ujc.scarab.ledger.rules.*
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.protocol.Era

/** Dijkstra's rules on the era types against the reference's verdicts on the same cases
  * (`fixtures/reference/dijkstra-rules`, cardano-ledger 9df9f98c, recipe in
  * `scripts/oracle/dijkstra-rules`): the same acceptance, and for a refusal the same predicate
  * failures in the same order, each the same bytes as the reference's `EncCBOR`, written by the
  * test-side encoder of Dijkstra's era failures ([[DijkstraEraFailureCbor]]).
  *
  * The era-typed UTXOW (`era.rules.DijkstraUtxow`, with its UTXO and Conway's UTXOS, #85) is run on
  * every `utxow` case, from the case's parameters, the certificate state the era-typed CERTS leaves
  * after its setup, its UTxO set and slot, the transaction read by the era decoder and annotated as
  * `mkDijkstraStAnnTopTx` does: its failures are the reference's bytes in its order.
  *
  * The era-typed CERTS (Conway's transition with Dijkstra's CERT, `CertRule.dijkstra`, #85) is run
  * on every `certs` case and on every case's setup: its failures are the reference's bytes in its
  * order, and SUBCERTS's are CERTS's through the reference's injection. ENTITIES is run at the root
  * on every `entities` case, and on the transaction of the devnet block the reference accepted.
  *
  * The era-typed GOV (Conway's transition at Dijkstra, `EraGovRun.dijkstra`, #85) is run on every
  * `gov` case at the root, from the certificate state the era-typed CERTS leaves after its setup:
  * its failures are the reference's bytes in its order, on all 44; on the cases it admits, its
  * events are the sequence Conway's transition tells (`GovNewProposals`, then `GovRemovedVotes`),
  * which the oracle does not record. A pool's vote on a parameter change of key 38, which the pools
  * may not vote on (`DisallowedVoters`), and of key 47, which they may, are checked on the era type
  * too, as is `gov/treasury-zero` 's failure.
  *
  * The era-typed LEDGER (`era.rules.DijkstraLedger`, #85) is run on every `utxow` case at the root,
  * from the certificate state the era CERTS leaves after its setup: where the refusal is LEDGER's
  * whole, its failures are the reference's bytes in its order, `utxow/no-utxo` 's order against
  * BBODY's recording of the same refusal one level down; the two whose deregistration ENTITIES
  * refuses are compared on their UTXOW part. The era-typed LEDGERS is run on every `bbody` case one
  * level below the root: its refusals are the reference's LEDGERS refusals byte for byte, and it
  * admits every other block. The era-typed BBODY is run on every `bbody` case at the root: its
  * verdict byte for byte, and the blocks made of each block it admits.
  */
class DijkstraRulesOracleSuite extends FunSuite:
  private val fixture = Path.of("fixtures", "reference", "dijkstra-rules", "cases.jsonl")

  private val globals = Globals(
    LedgerGlobals(
      TestLedger.Supply,
      100,
      Rational(1, 2),
      network = Network.Testnet,
      systemStart = xyz.kd5ujc.scarab.ledger.SystemStart(java.time.Instant.EPOCH),
      updateQuorum = 2,
      kes = xyz.kd5ujc.scarab.ledger.KesParameters.Mainnet
    ),
    EpochGeometry(Instant.EPOCH, 1000, 10000, TestGeometry.k(5), TestGeometry.f("0.05"))
  )

  private def ok[A](e: Either[?, A]): A = e.fold(v => fail(s"$v"), identity)

  private def hex(s: String): Bytes = ok(Bytes.fromHex(s))
  private def node(b: Bytes): Node = ok(Cbor.decode(b))

  private def str(c: ACursor): String = c.as[String].fold(e => fail(e.message), identity)
  private def num(c: ACursor): BigInt = c.as[BigInt].fold(e => fail(e.message), identity)

  private lazy val cases: Vector[Json] =
    Files.readAllLines(fixture).asScala.toVector.filter(_.nonEmpty).map(l => ok(parser.parse(l)))

  /** The parameters of a case, on those of protocol 12 with the Dijkstra genesis. */
  private def params(c: Json): ProtocolParams =
    val p = c.hcursor.downField("params")
    val units = p.downField("maxBlockExUnits")
    DijkstraFixture.genesis
      .upgrade(TestLedger.params(12))
      .copy(
        protocolMajor = num(p.downField("major")),
        maxBlockExUnits = ExUnits(num(units.downN(0)), num(units.downN(1))),
        maxRefScriptSizePerBlock = num(p.downField("maxRefScriptSizePerBlock")),
        maxRefScriptSizePerTx = num(p.downField("maxRefScriptSizePerTx")),
        keyDeposit = num(p.downField("keyDeposit")),
        poolDeposit = num(p.downField("poolDeposit")),
        drepDeposit = num(p.downField("drepDeposit")),
        govActionDeposit = num(p.downField("govActionDeposit")),
        govActionLifetime = num(p.downField("govActionLifetime")),
        eMax = num(p.downField("eMax")),
        minPoolCost = num(p.downField("minPoolCost")),
        minFeeA = num(p.downField("minFeeA")),
        minFeeB = num(p.downField("minFeeB")),
        maxTxSize = num(p.downField("maxTxSize")),
        maxValueSize = num(p.downField("maxValSize")),
        coinsPerUTxOByte = num(p.downField("coinsPerUTxOByte")),
        maxTxExUnits = ExUnits(
          num(p.downField("maxTxExUnits").downN(0)),
          num(p.downField("maxTxExUnits").downN(1))
        ),
        collateralPercentage = num(p.downField("collateralPercentage")),
        maxCollateralInputs = num(p.downField("maxCollateralInputs")),
        minFeeRefScriptCostPerByte = Rational(num(p.downField("minFeeRefScriptCostPerByte"))),
        refScriptCostStride = num(p.downField("refScriptCostStride")),
        refScriptCostMultiplier = ratio(p.downField("refScriptCostMultiplier")),
        priceMem = ratio(p.downField("prices").downN(0)),
        priceSteps = ratio(p.downField("prices").downN(1))
      )

  private def ratio(c: ACursor): Rational = Rational(num(c.downN(0)), num(c.downN(1)))

  /** The UTxO set of a case: each input and output as the node's Conway forms read them. */
  private def utxoOf(c: Json): Utxo =
    given ByteStrings = ByteStrings.Chunked
    Utxo(
      c.hcursor
        .downField("utxo")
        .as[Vector[(String, String)]]
        .getOrElse(Vector.empty)
        .map { (i, o) =>
          ok(TxIn.decode(node(hex(i)))) -> ok(
            TxOut.decode(
              node(hex(o)),
              Address.Mode.of(Era.Conway),
              TxOut.Forms.of(Era.Conway),
              ScriptKind.PlutusV3
            )
          )
        }
        .toMap
    )

  /** The default ledger state of a case's epoch and parameters, with `utxo` as its UTxO set. */
  private def ledger(
      pp: ProtocolParams,
      epoch: BigInt,
      utxo: Utxo = Utxo.empty
  ): LedgerState[Dijkstra] =
    val ls = xyz.kd5ujc.scarab.ledger.state.Build.dijkstra(ok(EpochNo.fromBigInt(epoch)), pp).es.ls
    ls.copy[Dijkstra](utxo = ls.utxo.copy[Dijkstra](utxo = UtxoTables(utxo)))

  /** What a rule made of a case: accepted, or refused with each failure's bytes. */
  private enum Verdict:
    case Accepted
    case Refused(failures: Vector[Bytes])

  private def reference(c: Json): Verdict =
    val r = c.hcursor.downField("reference")
    if r.downField("accepted").as[Boolean].contains(true) then Verdict.Accepted
    else
      Verdict.Refused(
        r.downField("failures").as[Vector[String]].fold(e => fail(e.message), identity).map(hex)
      )

  private def payload(conway: Bytes): Bytes = node(conway).value match
    case Value.Arr(Vector(_, item)) => item.original
    case other                      => fail(s"not a constructor of one field: $other")

  private def strings(c: ACursor): Vector[String] =
    c.as[Vector[String]].getOrElse(Vector.empty)

  test("the fixture holds the oracle's cases") {
    assertEquals(cases.size, 117)
  }

  /** The cases of a pool's vote on a parameter change of a key Dijkstra adds, whose setup registers
    * the pool.
    */
  private val poolVoteCases = Set("gov/pool-vote-key-38", "gov/pool-vote-key-47")

  // ---- the era-typed UTXOW ------------------------------------------------------------------------

  /** An era of history that ends far after every case's slot: no case has a redeemer, so the
    * forecast is not read.
    */
  private val history: xyz.kd5ujc.scarab.ledger.Horizon =
    val params = xyz.kd5ujc.scarab.ledger.Horizon.Params(100, 1000, 30, 30)
    xyz.kd5ujc.scarab.ledger.Horizon
      .History(Vector.empty, xyz.kd5ujc.scarab.ledger.Horizon.Edge(0, 0, 0), params, Some(params))
      .at(xyz.kd5ujc.scarab.ledger.Horizon.Transition.Unknown(Some(BigInt(1000))))

  private def eraUtxow(c: Json): Verdict =
    eraUtxowRun(c, xyz.kd5ujc.scarab.ledger.TxEnv.Validation.All) match
      case Left(defect)    => fail(s"${str(c.hcursor.downField("name"))}: $defect")
      case Right(Right(_)) => Verdict.Accepted
      case Right(Left(fs)) => Verdict.Refused(fs.toChain.toVector.map(eraBytes))

  /** An era failure as the reference writes it ([[DijkstraEraFailureCbor]]). */
  private def written(r: DijkstraEraFailureCbor.Result[Bytes]): Bytes =
    r.fold(u => fail(s"no encoding: $u"), identity)

  private def eraBytes(f: xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraUtxoRun.Rec): Bytes =
    written(DijkstraEraFailureCbor.utxow(f))

  private def eraUtxowRun(c: Json, validation: xyz.kd5ujc.scarab.ledger.TxEnv.Validation) =
    import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraCorpus, DijkstraUtxoRun}
    import xyz.kd5ujc.scarab.ledger.era.rules.{DijkstraUtxoContext, DijkstraUtxoEnv as EraUtxoEnv}
    val h = c.hcursor
    val pp = params(c)
    val epoch = num(h.downField("epoch"))
    val tx = ok(
      xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTx.inBlock(node(hex(str(h.downField("tx")))))
    )
    val utxo = utxoOf(c)
    val cert = eraCertsRun(c, "setup") match
      case Right(Right((s, _))) => s
      case other                => fail(s"the setup does not pass: $other")
    val env = EraUtxoEnv[Dijkstra](
      num(h.downField("slot")),
      DijkstraCorpus.era(pp),
      cert.pState,
      cert,
      DijkstraUtxoRun.originalUtxo(utxo, tx)
    )
    DijkstraUtxoRun.run(
      env,
      DijkstraUtxoRun.annotate(s => fail(s"the clock was read at $s"), env, tx),
      DijkstraUtxoContext(
        Network.Testnet,
        xyz.kd5ujc.scarab.ledger.SystemStart(java.time.Instant.EPOCH),
        history,
        validation
      ),
      ledger(pp, epoch, utxo).utxo,
      xyz.kd5ujc.scarab.plutus.ScarabEvaluator.default,
      xyz.kd5ujc.scarab.ledger.Verdicts.Direct,
      xyz.kd5ujc.scarab.plutus.ScarabEvaluator.defaultDecoder
    )

  private lazy val utxowCases: Vector[Json] =
    cases.filter(c => str(c.hcursor.downField("kind")) == "utxow")

  test("the era-typed UTXOW gives the reference's verdict on every utxow case, byte for byte") {
    assertEquals(utxowCases.size, 18)
    val differing = utxowCases.flatMap { c =>
      val name = str(c.hcursor.downField("name"))
      val ours = eraUtxow(c)
      val expected = reference(c)
      Option.when(ours != expected)(s"$name: $ours, the reference $expected")
    }
    assertEquals(differing, Vector.empty[String])
  }

  // ---- the era-typed CERTS and ENTITIES -----------------------------------------------------------

  /** The globals of the Leios build, whose layout Dijkstra's POOL registers pools in. */
  private val leiosGlobals =
    globals.copy(ledger =
      globals.ledger.copy(referenceBuild = xyz.kd5ujc.scarab.protocol.ReferenceBuild.Leios)
    )

  private def epochOf(c: Json): EpochNo =
    ok(EpochNo.fromBigInt(num(c.hcursor.downField("epoch"))))

  private lazy val devnetCase: Json =
    utxowCases
      .find(c => str(c.hcursor.downField("name")) == "utxow/devnet")
      .getOrElse(fail("no devnet case"))

  private def eraTx(c: Json): xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTx[
    xyz.kd5ujc.scarab.ledger.era.TxLevel.Top
  ] =
    ok(
      xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTx
        .inBlock(node(hex(str(c.hcursor.downField("tx")))))
    )

  /** Dijkstra's CERTS from the case's state on the certificates of `field`, read by the era's
    * decoder. The environment's transaction is the 11.1 build's field, read only by that build's
    * base case, which Dijkstra's CERTS does not have (`CertsBaseCase`): the devnet's transaction.
    */
  private def eraCertsRun(c: Json, field: String) =
    import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraCorpus
    val pp = params(c)
    xyz.kd5ujc.scarab.ledger.era.EraCertsRun.dijkstraCerts(
      leiosGlobals,
      xyz.kd5ujc.scarab.ledger.era.rules
        .CertsEnv[Dijkstra](eraTx(devnetCase), DijkstraCorpus.era(pp), epochOf(c), None, Map.empty),
      ledger(pp, num(c.hcursor.downField("epoch"))).cert,
      strings(c.hcursor.downField(field)).map(h => DijkstraCorpus.eraCert(hex(h)))
    )

  private def eraCerts(c: Json): Verdict = eraCertsRun(c, "certs") match
    case Left(defect)    => fail(s"${str(c.hcursor.downField("name"))}: $defect")
    case Right(Right(_)) => Verdict.Accepted
    case Right(Left(fs)) =>
      Verdict.Refused(fs.toChain.toVector.map(f => written(DijkstraEraFailureCbor.certs(f))))

  private lazy val certsCases: Vector[Json] =
    cases.filter(c => str(c.hcursor.downField("kind")) == "certs")

  test("the era-typed CERTS gives the reference's verdict on every certs case, byte for byte") {
    assertEquals(certsCases.size, 27)
    val differing = certsCases.flatMap { c =>
      val name = str(c.hcursor.downField("name"))
      val (ours, expected) = (eraCerts(c), reference(c))
      Option.when(ours != expected)(s"$name: $ours, the reference $expected")
    }
    assertEquals(differing, Vector.empty[String])
  }

  test("a registration then its deregistration leave the whole starting certificate state") {
    // `certs/register-unregister`, which the reference accepts: the era CERTS on both certificates
    // in one judgment, and on each in a judgment of its own from the state the first leaves, ends
    // in the certificate state it started from, every field of it, not only the account
    import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraCorpus
    val c = certsCases
      .find(c => str(c.hcursor.downField("name")) == "certs/register-unregister")
      .getOrElse(fail("no case certs/register-unregister"))
    assertEquals(reference(c), Verdict.Accepted)
    val pp = params(c)
    val start = ledger(pp, num(c.hcursor.downField("epoch"))).cert
    val certs = strings(c.hcursor.downField("certs")).map(h => DijkstraCorpus.eraCert(hex(h)))
    assertEquals(certs.size, 2)
    def certsFrom(
        state: xyz.kd5ujc.scarab.ledger.state.CertState[Dijkstra],
        cs: Vector[xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTxCert]
    ) =
      xyz.kd5ujc.scarab.ledger.era.EraCertsRun.dijkstraCerts(
        leiosGlobals,
        xyz.kd5ujc.scarab.ledger.era.rules
          .CertsEnv[Dijkstra](
            eraTx(devnetCase),
            DijkstraCorpus.era(pp),
            epochOf(c),
            None,
            Map.empty
          ),
        state,
        cs
      ) match
        case Right(Right((after, _))) => after
        case other                    => fail(s"not admitted: $other")
    assertEquals(certsFrom(start, certs), start)
    val registered = certsFrom(start, certs.take(1))
    assertNotEquals(registered, start)
    assertEquals(certsFrom(registered, certs.drop(1)), start)
  }

  test("SUBCERTS gives on every certs case CERTS's refusal through the reference's injection") {
    // a component check, as the reference applied CERTS, not SUBCERTS, to these certificates: on
    // the same state, SUBCERTS's failures are CERTS's, which equal the reference's bytes (above),
    // through `conwayToDijkstraSubCertsPredFailure` (`Dijkstra/Rules/SubCerts.hs:183-190`,
    // cardano-ledger 9df9f98c), in the same order, and on each case both admit its state is
    // CERTS's. SUBCERTS reads no sub-transaction; the environment holds the chain's first.
    import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraCorpus
    import xyz.kd5ujc.scarab.ledger.era.rules.{DijkstraSUBCERTS, SubCertsEnv}
    val subTx = DijkstraCorpus.chainContextCases.iterator
      .map(j =>
        ok(
          xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTx
            .inBlock(node(hex(str(j.hcursor.downField("bytes")))))
        )
      )
      .flatMap(t =>
        xyz.kd5ujc.scarab.ledger.era.EraTxBody.dijkstra.subTransactions(t.body).toVector
      )
      .nextOption()
      .getOrElse(fail("no sub-transaction"))
    val agreed = certsCases.map { c =>
      val name = str(c.hcursor.downField("name"))
      val pp = params(c)
      val sub = xyz.kd5ujc.scarab.ledger.era.EraCertsRun.dijkstraSubCerts(
        leiosGlobals,
        SubCertsEnv(subTx, DijkstraCorpus.era(pp), epochOf(c), None, Map.empty),
        ledger(pp, num(c.hcursor.downField("epoch"))).cert,
        strings(c.hcursor.downField("certs")).map(h => DijkstraCorpus.eraCert(hex(h)))
      )
      (sub, eraCertsRun(c, "certs")) match
        case (Right(Left(subFs)), Right(Left(fs))) =>
          assertEquals(
            subFs.toChain.toVector,
            fs.toChain.toVector.map(DijkstraSUBCERTS.conwayToDijkstraSubCertsPredFailure),
            name
          )
          false
        case (Right(Right((subState, _))), Right(Right((state, _)))) =>
          assertEquals(subState, state, name)
          true
        case other => fail(s"$name: $other")
    }
    assertEquals(agreed.size, 27)
    assertEquals(agreed.count(identity), certsCases.count(c => reference(c) == Verdict.Accepted))
  }

  test("ENTITIES admits the transaction of the devnet block the reference accepted") {
    import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraCorpus, DijkstraStAnnTx, DijkstraUtxoRun}
    val block = cases
      .find(c => str(c.hcursor.downField("name")) == "bbody/devnet-37-genesis-utxo")
      .getOrElse(fail("no block case"))
    assertEquals(reference(block), Verdict.Accepted)
    val txs = Block.decodeShelley(hex(str(block.hcursor.downField("block")))) match
      case Right(Block.Dijkstra(b)) => b.transactions
      case other                    => fail(s"not a Dijkstra block: $other")
    val tx = eraTx(devnetCase)
    // the block's one transaction is the devnet case's
    assertEquals(
      txs.map(_.id),
      Vector(ok(DijkstraTx.inBlock(node(hex(str(devnetCase.hcursor.downField("tx")))))).id)
    )
    val pp = params(block)
    val start = ledger(pp, num(block.hcursor.downField("epoch")), utxoOf(block)).cert
    val era = DijkstraCorpus.era(pp)
    val stAnnTx = DijkstraStAnnTx.mkDijkstraStAnnTopTx(
      s => fail(s"the clock was read at $s"),
      era,
      DijkstraUtxoRun.originalUtxo(utxoOf(block), tx),
      Map.empty,
      tx
    )
    val judged = xyz.kd5ujc.scarab.ledger.era.EraCertsRun.entities(
      leiosGlobals,
      xyz.kd5ujc.scarab.ledger.era.rules.EntitiesEnv[Dijkstra](
        epochOf(block),
        era,
        None,
        Map.empty,
        xyz.kd5ujc.scarab.ledger.era.EraCertState.dijkstra.certDStateL.get(start).accounts
      ),
      start,
      stAnnTx
    )
    assertEquals(judged.map(_.map(_._1)), Right(Right(start)))
  }

  private lazy val entitiesCases: Vector[Json] =
    cases.filter(c => str(c.hcursor.downField("kind")) == "entities")

  /** Dijkstra's ENTITIES on the era types at the root, as the oracle applies it: on the certificate
    * state the era CERTS leaves after the case's setup, whose accounts are the original ones too,
    * the transaction annotated as `mkDijkstraStAnnTopTx` does, with no committee.
    */
  private def eraEntitiesRun(c: Json) =
    import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraCorpus, DijkstraStAnnTx, DijkstraUtxoRun}
    val era = DijkstraCorpus.era(params(c))
    val tx = eraTx(c)
    val start = eraCertsRun(c, "setup") match
      case Right(Right((s, _))) => s
      case other                => fail(s"the setup does not pass: $other")
    xyz.kd5ujc.scarab.ledger.era.EraCertsRun.entities(
      leiosGlobals,
      xyz.kd5ujc.scarab.ledger.era.rules.EntitiesEnv[Dijkstra](
        epochOf(c),
        era,
        None,
        Map.empty,
        xyz.kd5ujc.scarab.ledger.era.EraCertState.dijkstra.certDStateL.get(start).accounts
      ),
      start,
      DijkstraStAnnTx.mkDijkstraStAnnTopTx(
        s => fail(s"the clock was read at $s"),
        era,
        DijkstraUtxoRun.originalUtxo(utxoOf(c), tx),
        Map.empty,
        tx
      )
    )

  test(
    "the era-typed ENTITIES gives the reference's verdict on every entities case, byte for byte"
  ) {
    // withdrawals, direct deposits and balance intervals of accounts on another network, missing,
    // or whose balance does not allow them, and a refund ENTITIES' CERTS refuses
    assertEquals(entitiesCases.size, 13)
    val judged = entitiesCases.map { c =>
      val name = str(c.hcursor.downField("name"))
      val ours = eraEntitiesRun(c) match
        case Left(defect)    => fail(s"$name: $defect")
        case Right(Right(_)) => Verdict.Accepted
        case Right(Left(fs)) =>
          Verdict.Refused(fs.toChain.toVector.map(f => written(DijkstraEraFailureCbor.entities(f))))
      (name, ours, reference(c))
    }
    assertEquals(
      judged.collect {
        case (name, ours, expected) if ours != expected => s"$name: $ours, the reference $expected"
      },
      Vector.empty[String]
    )
    assertEquals(judged.count(_._2 == Verdict.Accepted), 2)
  }

  // ---- the era-typed GOV --------------------------------------------------------------------------

  private lazy val govCases: Vector[Json] =
    cases.filter(c => str(c.hcursor.downField("kind")) == "gov")

  /** The signal of a `gov` case as the era reads it: its votes and proposals, no certificates. */
  private def eraGovSignal(c: Json): xyz.kd5ujc.scarab.ledger.era.rules.GovSignal[Dijkstra] =
    import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraCorpus
    val h = c.hcursor
    xyz.kd5ujc.scarab.ledger.era.rules.GovSignal[Dijkstra](
      h.downField("votes")
        .as[String]
        .toOption
        .fold(DijkstraCorpus.noVotes)(v => DijkstraCorpus.eraVotes(hex(v))),
      h.downField("proposals")
        .as[String]
        .toOption
        .fold(xyz.kd5ujc.scarab.ledger.era.OSet.empty)(p => DijkstraCorpus.eraProposals(hex(p))),
      Vector.empty
    )

  /** The transaction id of every `gov` case: 32 bytes of 6. */
  private val govTxId: Hash32 = ok(Hash32.fromBytes(Bytes.fromArray(Array.fill(32)(6))))

  /** Dijkstra's GOV on the era types (Conway's transition at Dijkstra, `Dijkstra/Rules/Gov.hs:220`,
    * cardano-ledger 9df9f98c) at the root, as the oracle applies it: from the certificate state
    * Dijkstra's era CERTS leaves after the case's setup and the default store of proposals, with no
    * guardrails script and no committee, on the testnet.
    */
  private def eraGovRun(c: Json) =
    import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraCorpus
    val cert = eraCertsRun(c, "setup") match
      case Right(Right((s, _))) => s
      case other                => fail(s"the setup does not pass: $other")
    xyz.kd5ujc.scarab.ledger.era.EraGovRun.dijkstra(
      xyz.kd5ujc.scarab.ledger.era.rules
        .GovEnv[Dijkstra](govTxId, epochOf(c), DijkstraCorpus.era(params(c)), None, cert, None),
      eraGovSignal(c),
      Network.Testnet,
      xyz.kd5ujc.scarab.ledger.era.EraGovRun.dijkstraEmpty
    )

  private def eraGov(c: Json): Verdict = eraGovRun(c) match
    case Left(defect)    => fail(s"${str(c.hcursor.downField("name"))}: $defect")
    case Right(Right(_)) => Verdict.Accepted
    case Right(Left(fs)) =>
      Verdict.Refused(fs.toChain.toVector.map(f => written(DijkstraEraFailureCbor.gov(f))))

  test("the era-typed GOV gives the reference's verdict on every gov case, byte for byte") {
    assertEquals(govCases.size, 44)
    val differing = govCases.flatMap { c =>
      val name = str(c.hcursor.downField("name"))
      val (ours, expected) = (eraGov(c), reference(c))
      Option.when(ours != expected)(s"$name: $ours, the reference $expected")
    }
    assertEquals(differing, Vector.empty[String])
  }

  test(
    "the era-typed GOV's refusal of a zero withdrawal holds the account, the zero and no policy"
  ) {
    import xyz.kd5ujc.scarab.ledger.era.GovAction
    import xyz.kd5ujc.scarab.ledger.era.rules.DijkstraGovPredFailure
    val c = govCases
      .find(c => str(c.hcursor.downField("name")) == "gov/treasury-zero")
      .getOrElse(fail("no case gov/treasury-zero"))
    // the case's one proposal withdraws 0 to the testnet's key account of 28 bytes of 1, with no
    // guardrails script (`ZeroTreasuryWithdrawals`, `Conway/Rules/Gov.hs:550`, cardano-ledger
    // 9df9f98c)
    val account = xyz.kd5ujc.scarab.ledger.RewardAccount(
      0,
      xyz.kd5ujc.scarab.ledger.Credential.Key(Bytes.fromArray(Array.fill(28)(1)))
    )
    eraGovRun(c) match
      case Right(Left(fs)) =>
        assertEquals(
          fs.toChain.toVector,
          Vector(
            DijkstraGovPredFailure.ZeroTreasuryWithdrawals[Dijkstra](
              GovAction.TreasuryWithdrawals(Map(account -> Coin(0)), None)
            )
          )
        )
      case other => fail(s"not refused: $other")
  }

  test("the era-typed GOV tells the proposals after, and no vote removed, on each case it admits") {
    import xyz.kd5ujc.scarab.ledger.era.rules.{ConwayGovEvent, drepCredentialOrdering}
    val admitted = govCases.flatMap { c =>
      val name = str(c.hcursor.downField("name"))
      eraGovRun(c) match
        case Right(Right((era, events))) =>
          // both events, each by the transaction's id: the proposals after, and no vote replaced
          // or removed, as no case casts a vote twice or unregisters a DRep
          assertEquals(
            events.toVector,
            Vector(
              ConwayGovEvent.GovNewProposals[Dijkstra](govTxId, era),
              ConwayGovEvent.GovRemovedVotes[Dijkstra](
                govTxId,
                scala.collection.immutable.SortedSet.empty,
                scala.collection.immutable.SortedSet.empty
              )
            ),
            name
          )
          Some(name)
        case Right(Left(_)) => None
        case other          => fail(s"$name: $other")
    }
    assertEquals(admitted.size, govCases.count(c => reference(c) == Verdict.Accepted))
    assert(admitted.contains("gov/pool-vote-key-47"), s"$admitted")
    assertEquals(admitted.size, 27)
  }

  test("the era-typed GOV refuses a pool's vote on key 38 and admits one on key 47, as the build") {
    import xyz.kd5ujc.scarab.ledger.era.rules.DijkstraGovPredFailure
    val byName = govCases.map(c => str(c.hcursor.downField("name")) -> c).toMap
    // `DisallowedVoters` of the pool (28 bytes of 8) on the case's one action, the transaction's
    // first proposal (`Conway/Rules/Gov.hs:364-376`, `isStakePoolVotingAllowed`, cardano-ledger
    // 9df9f98c): key 38 is of no stake pool group (`Dijkstra/PParams.hs:236`)
    eraGovRun(byName("gov/pool-vote-key-38")) match
      case Right(Left(fs)) =>
        val failures = fs.toChain.toVector
        assertEquals(failures.size, 1)
        failures.head match
          case DijkstraGovPredFailure.DisallowedVoters(votes) =>
            assertEquals(
              votes.toList.map((v, id) => (v, id)),
              List(
                (
                  xyz.kd5ujc.scarab.ledger.Voter.Pool(Bytes.fromArray(Array.fill(28)(8))),
                  xyz.kd5ujc.scarab.ledger.GovActionId(govTxId.bytes, BigInt(0))
                )
              )
            )
          case other => fail(s"not DisallowedVoters: $other")
      case other => fail(s"not refused: $other")
    // key 47 is of the security group, which the pools vote on (`Dijkstra/PParams.hs:265-266`,
    // cardano-ledger 9df9f98c)
    eraGovRun(byName("gov/pool-vote-key-47")) match
      case Right(Right((proposals, _))) =>
        val gas = proposals.pProps.toVector
        assertEquals(gas.size, 1)
        assertEquals(gas.head.gasStakePoolVotes.size, 1)
      case other => fail(s"not admitted: $other")
    poolVoteCases.foreach(n => assertEquals(eraGov(byName(n)), reference(byName(n)), n))
  }

  // ---- the era-typed LEDGER and LEDGERS -----------------------------------------------------------

  private lazy val bbodyCases: Vector[Json] =
    cases.filter(c => str(c.hcursor.downField("kind")) == "bbody")

  private def caseNamed(name: String): Json =
    cases.find(c => str(c.hcursor.downField("name")) == name).getOrElse(fail(s"no case $name"))

  /** The item after BBODY's tag of a `LedgersFailure`, which is LEDGER's failure itself. */
  private def inLedgers(bytes: Bytes): Option[Bytes] = node(bytes).value match
    case Value.Arr(Vector(tag, item)) if tag.value == Value.UInt(2) => Some(item.original)
    case _                                                          => None

  /** Dijkstra's LEDGER on the era types (`era.rules.DijkstraLedger`, #85) on a `utxow` case at the
    * root: from the certificate state the era CERTS leaves after the case's setup, the case's UTxO
    * set and the default store of proposals, the transaction annotated as `mkDijkstraStAnnTopTx`
    * does, at the case's slot and epoch, with a treasury of zero.
    */
  private def eraLedgerRun(c: Json) =
    import xyz.kd5ujc.scarab.ledger.era.dijkstra.{
      DijkstraCorpus,
      DijkstraLedgerRun,
      DijkstraStAnnTx,
      DijkstraUtxoRun
    }
    val h = c.hcursor
    val pp = params(c)
    val era = DijkstraCorpus.era(pp)
    val tx = eraTx(c)
    val utxo = utxoOf(c)
    val cert = eraCertsRun(c, "setup") match
      case Right(Right((s, _))) => s
      case other                => fail(s"the setup does not pass: $other")
    val start = ledger(pp, num(h.downField("epoch")), utxo)
    DijkstraLedgerRun.ledger(
      leiosGlobals,
      xyz.kd5ujc.scarab.ledger.era.rules.LedgerEnv[Dijkstra](
        num(h.downField("slot")),
        Some(epochOf(c)),
        TxIx.txIxFromIntegral(0).getOrElse(fail("index 0")),
        era,
        ChainAccountState(Coin(0), Coin(0))
      ),
      DijkstraStAnnTx.mkDijkstraStAnnTopTx(
        s => fail(s"the clock was read at $s"),
        era,
        DijkstraUtxoRun.originalUtxo(utxo, tx),
        Map.empty,
        tx
      ),
      xyz.kd5ujc.scarab.ledger.era.rules
        .DijkstraUtxoContext(
          Network.Testnet,
          xyz.kd5ujc.scarab.ledger.SystemStart(java.time.Instant.EPOCH),
          history,
          xyz.kd5ujc.scarab.ledger.TxEnv.Validation.All
        ),
      LedgerState(cert, start.utxo),
      xyz.kd5ujc.scarab.ledger.era.EraGovRun.dijkstraEmpty,
      xyz.kd5ujc.scarab.plutus.ScarabEvaluator.default,
      xyz.kd5ujc.scarab.ledger.Verdicts.Direct,
      xyz.kd5ujc.scarab.plutus.ScarabEvaluator.defaultDecoder
    )

  private def eraLedgerFailures(c: Json) = eraLedgerRun(c) match
    case Left(defect)    => fail(s"${str(c.hcursor.downField("name"))}: $defect")
    case Right(Right(_)) => Vector.empty
    case Right(Left(fs)) => fs.toChain.toVector

  test(
    "the era-typed LEDGER gives each utxow case's refusal byte for byte where it is LEDGER's whole"
  ) {
    import xyz.kd5ujc.scarab.ledger.era.rules.DijkstraLedgerPredFailure as L
    // the refusals the oracle recorded are UTXOW's at the root; LEDGER runs UTXOW one level down,
    // so its failures are UTXOW's wrapped (`DijkstraUtxowFailure`), and the whole refusal is
    // LEDGER's where nothing else refuses the transaction
    val judged = utxowCases.map(c => str(c.hcursor.downField("name")) -> eraLedgerFailures(c))
    val (whole, more) = judged.partition((_, fs) =>
      fs.forall {
        case L.DijkstraUtxowFailure(_) => true
        case _                         => false
      }
    )
    // the two deregistrations whose declared refund is not the deposit held: ENTITIES' CERTS
    // refuses that, which the oracle's UTXOW alone does not judge (CNF-2)
    assertEquals(
      more.map(_._1),
      Vector("utxow/refund-declared-balanced", "utxow/refund-held-balanced")
    )
    // each case registers the account with 2 ada (`setup`) and declares a refund of 3 ada
    // (`scripts/oracle/dijkstra-rules/run.py`): DELEG refuses the refund once, through CERT, CERTS
    // and ENTITIES (`Conway/Rules/Deleg.hs:245-261`, cardano-ledger 9df9f98c);
    // ENTITIES runs before UTXOW, and LEDGER at the root reports its blocks last first, so UTXOW's
    // block comes before ENTITIES' one failure
    val refund = {
      import xyz.kd5ujc.scarab.ledger.era.rules.*
      L.DijkstraEntitiesFailure[Dijkstra](
        EntitiesPredFailure.CertsFailure(
          ConwayCertsPredFailure.CertFailure(
            ConwayCertPredFailure.DelegFailure(
              ConwayDelegPredFailure.RefundIncorrectDELEG(
                xyz.kd5ujc.scarab.ledger.era.Mismatch(Coin(3000000), Coin(2000000))
              )
            )
          )
        )
      )
    }
    more.foreach { (name, fs) =>
      val utxowPart = fs.filter {
        case L.DijkstraUtxowFailure(_) => true
        case _                         => false
      }
      assertEquals(fs, utxowPart :+ refund, name)
      val expected = reference(caseNamed(name))
      assertEquals(
        if utxowPart.isEmpty then Verdict.Accepted
        else
          Verdict.Refused(utxowPart.map(f => payload(written(DijkstraEraFailureCbor.ledger(f)))))
        ,
        expected,
        name
      )
    }
    // the order of a refusal of several failures: the oracle applied UTXOW at the root, whose
    // failures the reference reports last first; one level down, LEDGER's block keeps the order
    // they were recorded in, which BBODY's recorded refusal of the same transaction holds
    // (`bbody/devnet-37`, through LEDGERS and LEDGER)
    val severalAtRoot = "utxow/no-utxo"
    val differing = whole.flatMap { (name, fs) =>
      val ours: Verdict =
        if fs.isEmpty then Verdict.Accepted
        else Verdict.Refused(fs.map(f => payload(written(DijkstraEraFailureCbor.ledger(f)))))
      val expected =
        if name == severalAtRoot then
          reference(caseNamed("bbody/devnet-37")) match
            case Verdict.Refused(bs) =>
              Verdict.Refused(bs.map(b => payload(inLedgers(b).getOrElse(fail(s"$b")))))
            case other => fail(s"$other")
        else reference(caseNamed(name))
      Option.when(ours != expected)(s"$name: $ours, the reference $expected")
    }
    assertEquals(whole.size, 16)
    assertEquals(differing, Vector.empty[String])
  }

  /** Dijkstra's LEDGERS on the era types on a `bbody` case's transactions, one level below the root
    * as BBODY runs it, from the default ledger state with the case's UTxO set, at the header's slot
    * and the case's epoch.
    */
  private def eraLedgersRun(c: Json) =
    import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraCorpus, DijkstraLedgerRun}
    val h = c.hcursor
    val pp = params(c)
    val block = Block.decodeShelley(hex(str(h.downField("block")))) match
      case Right(Block.Dijkstra(b)) => b
      case other                    => fail(s"not a Dijkstra block: $other")
    val txs = block.transactions.map(t =>
      ok(xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTx.inBlock(node(t.raw)))
    )
    DijkstraLedgerRun.ledgers(
      leiosGlobals,
      xyz.kd5ujc.scarab.ledger.era.rules
        .LedgersEnv[Dijkstra](
          block.header.slot,
          epochOf(c),
          DijkstraCorpus.era(pp),
          ChainAccountState(Coin(0), Coin(0))
        ),
      txs,
      xyz.kd5ujc.scarab.ledger.era.rules
        .DijkstraUtxoContext(
          Network.Testnet,
          xyz.kd5ujc.scarab.ledger.SystemStart(java.time.Instant.EPOCH),
          history,
          xyz.kd5ujc.scarab.ledger.TxEnv.Validation.All
        ),
      ledger(pp, num(h.downField("epoch")), utxoOf(c)),
      xyz.kd5ujc.scarab.ledger.era.EraGovRun.dijkstraEmpty,
      xyz.kd5ujc.scarab.plutus.ScarabEvaluator.default,
      xyz.kd5ujc.scarab.ledger.Verdicts.Direct,
      xyz.kd5ujc.scarab.plutus.ScarabEvaluator.defaultDecoder,
      s => fail(s"the clock was read at $s"),
      depth = 1
    )

  test("the era-typed LEDGERS gives each bbody case's LEDGERS refusal byte for byte") {
    // BBODY's own failures are its header's and body's (`bbody/major-13`, `other-body-*`), whose
    // blocks LEDGERS admits; the refusals that are LEDGERS' whole are those of the devnet's block
    // with a transaction, on the default state (`bbody/devnet-37`) and flagged invalid; on the
    // genesis output it spends, the reference and LEDGERS admit it
    assertEquals(bbodyCases.size, 15)
    val judged = bbodyCases.map { c =>
      val name = str(c.hcursor.downField("name"))
      val ours = eraLedgersRun(c) match
        case Left(defect)    => fail(s"$name: $defect")
        case Right(Right(_)) => Vector.empty
        case Right(Left(fs)) =>
          fs.toChain.toVector.map(l => written(DijkstraEraFailureCbor.ledgers(l)))
      val recorded = reference(c) match
        case Verdict.Accepted    => Vector.empty
        case Verdict.Refused(bs) => bs.flatMap(inLedgers)
      assertEquals(ours, recorded, name)
      name -> ours.nonEmpty
    }
    assertEquals(
      judged.filter(_._2).map(_._1),
      Vector("bbody/devnet-37", "bbody/devnet-37-invalid-flag")
    )
  }

  // ---- the era-typed BBODY ------------------------------------------------------------------------

  /** The oracle's globals for BBODY: epochs of 100 slots (`fixedEpochInfo (EpochSize 100)`), whose
    * boundary BBODY reads (`slotToEpochBoundary`), in the Leios build.
    */
  private val bbodyGlobals =
    leiosGlobals.copy(geometry = leiosGlobals.geometry.copy(epochLength = 100))

  /** Dijkstra's BBODY on the era types (`era.rules.DijkstraBbody`, #85) on a `bbody` case at the
    * root, as the oracle applies it: the block read into the era's types, from the default ledger
    * state with the case's UTxO set, no blocks made, the default store of proposals and a chain
    * account of zero.
    */
  private def eraBbodyRun(c: Json) =
    import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraBbodyRun, DijkstraBlock, DijkstraCorpus}
    val h = c.hcursor
    val pp = params(c)
    DijkstraBbodyRun.bbody(
      bbodyGlobals,
      xyz.kd5ujc.scarab.ledger.era.rules
        .BbodyEnv[Dijkstra](DijkstraCorpus.era(pp), ChainAccountState(Coin(0), Coin(0))),
      ok(DijkstraBlock.decode(hex(str(h.downField("block"))))),
      xyz.kd5ujc.scarab.ledger.era.rules
        .DijkstraUtxoContext(
          Network.Testnet,
          xyz.kd5ujc.scarab.ledger.SystemStart(java.time.Instant.EPOCH),
          history,
          xyz.kd5ujc.scarab.ledger.TxEnv.Validation.All
        ),
      xyz.kd5ujc.scarab.ledger.era.rules.ShelleyBbodyState[Dijkstra](
        ledger(pp, num(h.downField("epoch")), utxoOf(c)),
        BlocksMade.empty
      ),
      xyz.kd5ujc.scarab.ledger.era.EraGovRun.dijkstraEmpty,
      xyz.kd5ujc.scarab.plutus.ScarabEvaluator.default,
      xyz.kd5ujc.scarab.ledger.Verdicts.Direct,
      xyz.kd5ujc.scarab.plutus.ScarabEvaluator.defaultDecoder,
      s => fail(s"the clock was read at $s")
    )

  test("the era-typed BBODY gives each bbody case's verdict byte for byte, and the blocks made") {
    // the refusals are the header's and body's (`bbody/major-13`, `other-body-*`) and LEDGERS'
    // (`bbody/devnet-37` on the default state, and flagged invalid); every other block is admitted,
    // and counted once for its issuer, as the reference's `blocksMade` has it
    assertEquals(bbodyCases.size, 15)
    val judged = bbodyCases.map { c =>
      val name = str(c.hcursor.downField("name"))
      eraBbodyRun(c) match
        case Left(defect) => fail(s"$name: $defect")
        case Right(Right((after, _, _))) =>
          assertEquals(reference(c), Verdict.Accepted, name)
          assertEquals(
            after.blocksMade.blocks.values.map(n => BigInt(n.bits)).toVector,
            c.hcursor
              .downField("reference")
              .downField("blocksMade")
              .as[Vector[BigInt]]
              .fold(e => fail(e.message), identity),
            name
          )
          name -> false
        case Right(Left(fs)) =>
          val ours = fs.toChain.toVector.map(f => written(DijkstraEraFailureCbor.bbody(f)))
          assertEquals(Verdict.Refused(ours), reference(c), name)
          name -> true
    }
    assertEquals(
      judged.filter(_._2).map(_._1),
      Vector(
        "bbody/devnet-37",
        "bbody/major-13",
        "bbody/other-body-hash",
        "bbody/other-body-size-and-hash",
        "bbody/devnet-37-invalid-flag"
      )
    )
  }
