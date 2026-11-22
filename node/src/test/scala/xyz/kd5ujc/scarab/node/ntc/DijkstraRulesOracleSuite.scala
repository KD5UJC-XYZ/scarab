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
  CertFailure,
  CertsFailure,
  EpochGeometry,
  LedgerGlobals,
  Mismatch,
  PoolFailure,
  PredicateFailure,
  Proposals,
  ProtocolParams,
  ScriptKind,
  TestGeometry,
  TestLedger,
  TxIn,
  TxOut,
  Utxo,
  UtxoFailure,
  UtxowFailure
}
import xyz.kd5ujc.scarab.ledger.dijkstra.*
import xyz.kd5ujc.scarab.ledger.rules.*
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.protocol.{DijkstraNotYet, Era}

/** The Dijkstra BBODY, CERTS and GOV rules of the node against the reference's verdicts on the same
  * cases (`fixtures/reference/dijkstra-rules`, cardano-ledger 9df9f98c, recipe in
  * `scripts/oracle/dijkstra-rules`): the same acceptance, and for a refusal the same predicate
  * failures in the same order, each the same bytes as the reference's `EncCBOR`. A failure whose
  * Dijkstra type is a copy of Conway's (GOV, GOVCERT, DELEG; POOL but its BLS case; CERT and CERTS,
  * which are Conway's) is written by the Conway encoder ([[PredicateFailureCbor]]) without the
  * LEDGER constructor around it, whose tag the two eras share (`ConwayCertsFailure` 2 with
  * `CertFailure` 1, `ConwayGovFailure` 3).
  *
  * A case that reaches a rule this build does not have halts (`DijkstraNotYet.Rules`), and is an
  * incomplete gate, not agreement: the devnet's block with a transaction reaches ENTITIES.
  *
  * The era-typed UTXOW (`era.rules.DijkstraUtxow`, with its UTXO and Conway's UTXOS, #85) is run on
  * every `utxow` case too, from the same parameters, certificate state, UTxO set and slot, the
  * transaction read by the era decoder and annotated as `mkDijkstraStAnnTopTx` does: its failures,
  * in their flat form (`DijkstraUtxowPredFailure.toStored`) and written by this suite's encoder,
  * are the reference's bytes in its order, and the flat rule's.
  *
  * The era-typed CERTS (Conway's transition with Dijkstra's CERT, `CertRule.dijkstra`, #85) is run
  * on every `certs` case and on every case's setup: its failures, in the flat Dijkstra form
  * (`DijkstraCertsStored.toStored`) and written by this suite's encoder, are the reference's bytes
  * in its order, and its verdicts and states the flat rule's. ENTITIES is run on the transaction of
  * the devnet block the reference accepted, where the flat rules halt.
  *
  * The era-typed GOV (Conway's transition at Dijkstra, `EraGovRun.dijkstra`, #85) is run on every
  * `gov` case at the root, from the certificate state the era-typed CERTS leaves after its setup:
  * its failures, in the flat Dijkstra form (`DijkstraGovPredFailure.toStored`) and written by this
  * suite's encoder, are the reference's bytes in its order, on all 18; its verdicts are the flat
  * rule's on the 16 the flat rules judge, and on the 6 both admit its proposals after are the flat
  * rule's and its events the sequence Conway's transition tells (`GovNewProposals`, then
  * `GovRemovedVotes`), which the oracle does not record. The other two are a pool's vote on a
  * parameter change of key 38, which the pools may not vote on (`DisallowedVoters`), and of key 47,
  * which they may, each after a pool's registration, which the flat rules halt on (an incomplete
  * flat comparison, CNF-2). The flat form restores an action from the proposal it was read as, so
  * `gov/treasury-zero` 's failure is checked on the era type too.
  *
  * The era-typed LEDGER (`era.rules.DijkstraLedger`, #85) is run on every `utxow` case at the root,
  * from the certificate state the era CERTS leaves after its setup: where the refusal is LEDGER's
  * whole, its failures, in the flat Dijkstra form (`DijkstraLedgerPredFailure.toStored`) and
  * written by this suite's encoder, are the reference's bytes in its order, `utxow/no-utxo` 's
  * order against BBODY's recording of the same refusal one level down; the two whose deregistration
  * ENTITIES refuses are compared on their UTXOW part. The era-typed LEDGERS is run on every `bbody`
  * case one level below the root: its refusals are the reference's LEDGERS refusals byte for byte,
  * and it admits every other block, the one the flat rules halt on among them. On the two cases
  * flagged invalid, which the flat LEDGER judges, the era LEDGER and LEDGERS give the flat
  * failures.
  */
class DijkstraRulesOracleSuite extends FunSuite:
  private val fixture = Path.of("fixtures", "reference", "dijkstra-rules", "cases.jsonl")

  private val globals = Globals(
    LedgerGlobals(
      TestLedger.Supply,
      100,
      Rational(1, 2),
      network = 0,
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

  private def ledger(pp: ProtocolParams, epoch: BigInt, utxo: Utxo = Utxo.empty): DijkstraLedger =
    val ls = xyz.kd5ujc.scarab.ledger.state.Build.dijkstra(ok(EpochNo.fromBigInt(epoch)), pp).es.ls
    Counted(
      ls.copy[Dijkstra](utxo = ls.utxo.copy[Dijkstra](utxo = UtxoTables(utxo))),
      xyz.kd5ujc.scarab.ledger.PlutusStats.zero
    )

  /** What a rule made of a case: accepted, refused with each failure's bytes, or halted. */
  private enum Verdict:
    case Accepted
    case Refused(failures: Vector[Bytes])
    case Halted(cause: DijkstraHalt)

  private def reference(c: Json): Verdict =
    val r = c.hcursor.downField("reference")
    if r.downField("accepted").as[Boolean].contains(true) then Verdict.Accepted
    else
      Verdict.Refused(
        r.downField("failures").as[Vector[String]].fold(e => fail(e.message), identity).map(hex)
      )

  /** The item after the tag of `[tag, item]`, as its bytes. */
  private def encoded(f: PredicateFailure): Bytes =
    PredicateFailureCbor.ledger(f).fold(error => fail(error.message), identity)

  private def payload(conway: Bytes): Bytes = node(conway).value match
    case Value.Arr(Vector(_, item)) => item.original
    case other                      => fail(s"not a constructor of one field: $other")

  /** The one field of a constructor `[tag, field]`: through the LEDGER constructor, and through
    * UTXOW's `UtxoFailure`.
    */
  private def conway(f: PredicateFailure): Bytes =
    payload(encoded(f))

  /** Dijkstra's failure types write a `Mismatch` as a record of its two values (`To mm`:
    * `Dijkstra/Rules/Gov.hs:168-169, 180-181`, `GovCert.hs:81, 83`), where Conway's write them in
    * line (`ToGroup mm`, `Conway/Rules/Gov.hs:269-270`, cardano-ledger 9df9f98c); Conway's DELEG,
    * which Dijkstra keeps, writes them in line too. `regroup(at)` makes the record of the two items
    * from position `at` of the constructor at `path`, the tags of the constructors that hold it
    * from the outermost.
    */
  private def regroup(bytes: Bytes, path: List[Int], at: Int): Bytes =
    def go(n: Node, path: List[Int]): Node = (n.value, path) match
      case (Value.Arr(items), Nil) =>
        val (before, rest) = items.splitAt(at)
        item(Value.Arr(before ++ (item(Value.Arr(rest.take(2))) +: rest.drop(2))))
      case (Value.Arr(Vector(tag, inner)), _ :: rest) =>
        item(Value.Arr(Vector(tag, go(inner, rest))))
      case other => fail(s"no constructor at $path: $other")
    ok(Cbor.encode(go(node(bytes), path.drop(1)).value))

  /** Where a `Mismatch` sits in the failures the oracle's cases reach: by the tags from the
    * outermost (CERTS, CERT, GOVCERT; or GOV), its position in the constructor.
    */
  private val mismatches: Map[List[Int], Int] = Map(
    List(1, 3, 2) -> 1,
    List(1, 3, 4) -> 1,
    List(4) -> 1,
    List(10) -> 2
  )

  private def tags(bytes: Bytes): List[Int] =
    def go(n: Node): List[Int] = n.value match
      case Value.Arr(items) =>
        items.headOption.map(_.value) match
          case Some(Value.UInt(t)) =>
            val rest = items.drop(1)
            t.toInt :: rest.headOption.filter(_ => rest.size == 1).fold(Nil)(go)
          case _ => Nil
      case _ => Nil
    go(node(bytes))

  /** A Conway encoding of a failure as the Dijkstra type writes it. */
  private def dijkstra(bytes: Bytes): Bytes =
    val path = tags(bytes)
    mismatches
      .collectFirst {
        case (p, at) if path == p =>
          regroup(bytes, p, at)
      }
      .getOrElse(bytes)

  private def pool(f: DijkstraPoolFailure): PoolFailure = f match
    case DijkstraPoolFailure.StakePoolNotRegisteredOnKey(p) =>
      PoolFailure.StakePoolNotRegisteredOnKey(p)
    case DijkstraPoolFailure.StakePoolRetirementWrongEpoch(a, b) =>
      PoolFailure.StakePoolRetirementWrongEpoch(a, b)
    case DijkstraPoolFailure.StakePoolCostTooLow(m)     => PoolFailure.StakePoolCostTooLow(m)
    case DijkstraPoolFailure.WrongNetworkPool(m, p)     => PoolFailure.WrongNetworkPool(m, p)
    case DijkstraPoolFailure.PoolMedataHashTooBig(p, s) => PoolFailure.PoolMedataHashTooBig(p, s)
    case DijkstraPoolFailure.VRFKeyHashAlreadyRegistered(p, v) =>
      PoolFailure.VRFKeyHashAlreadyRegistered(p, v)
    case DijkstraPoolFailure.BlsKeyInvalidProofOfPossession(_, _) =>
      fail("no case of the oracle reaches a BLS key")

  private def certs(f: DijkstraCertsFailure): Bytes =
    val cert = f.failure match
      case DijkstraCertFailure.Deleg(d)   => CertFailure.Deleg(d)
      case DijkstraCertFailure.Pool(p)    => CertFailure.Pool(pool(p))
      case DijkstraCertFailure.GovCert(g) => CertFailure.GovCert(g)
    conway(PredicateFailure.Certs(CertsFailure.Cert(cert)))

  private def mismatch(tag: Int, a: Value, b: Value): Bytes =
    ok(
      Cbor.encode(
        Value.Arr(Vector(uint(tag), Node(Value.Arr(Vector(item(a), item(b))), Bytes.empty)))
      )
    )

  private def uint(n: BigInt): Node = item(Value.UInt(n))
  private def item(v: Value): Node = Node(v, Bytes.empty)

  /** A UTXOW failure as Dijkstra's encoder writes it, for the cases the oracle reaches. A failure
    * without a `Mismatch` is Conway's encoding of the same failure; one with is the constructor's
    * tag and the record of the supplied and the expected value (`To mm`,
    * `Dijkstra/Rules/Utxo.hs:501-504`, cardano-ledger 9df9f98c), where Conway writes them in line
    * and, for the fee, swapped.
    */
  private def utxow(f: DijkstraUtxowFailure): Bytes =
    // Conway's encoding of the payload, under the Dijkstra constructor's tag (Dijkstra numbers its
    // UTXO failures anew: `Utxo.hs:497-520`, cardano-ledger 9df9f98c)
    def conwayUtxo(tag: Int, u: UtxoFailure): Bytes =
      val conway =
        payload(
          payload(encoded(PredicateFailure.utxo(u)))
        )
      node(conway).value match
        case Value.Arr(items) =>
          ok(Cbor.encode(Value.Arr(uint(tag) +: items.drop(1))))
        case other => fail(s"not a constructor: $other")
    def mismatch[A](tag: Int, m: Mismatch[A])(each: (CborOut, A) => Unit): Bytes =
      CborOut.build { w =>
        w.array(2).uint(tag).array(2)
        each(w, m.supplied)
        each(w, m.expected)
      }
    // the UTXOW failures without a `Mismatch` keep Conway's tags and payloads (`Utxow.hs:381-401`,
    // cardano-ledger 9df9f98c)
    def conwayUtxow(c: UtxowFailure): Bytes =
      payload(encoded(PredicateFailure.utxow(c)))
    def utxo(u: DijkstraUtxoFailure): Bytes = u match
      case DijkstraUtxoFailure.FeeTooSmall(m) => mismatch(u.tag, m)(PredicateFailureCbor.coin)
      case DijkstraUtxoFailure.MaxTxSize(m)   => mismatch(u.tag, m)(PredicateFailureCbor.coin)
      case DijkstraUtxoFailure.ValueNotConserved(m) =>
        mismatch(u.tag, m)(PredicateFailureCbor.value)
      case DijkstraUtxoFailure.BadInputs(i)     => conwayUtxo(u.tag, UtxoFailure.BadInputs(i))
      case DijkstraUtxoFailure.OutputTooBig(os) => conwayUtxo(u.tag, UtxoFailure.OutputTooBig(os))
      case DijkstraUtxoFailure.BabbageOutputTooSmall(os) =>
        conwayUtxo(u.tag, UtxoFailure.BabbageOutputTooSmall(os))
      case DijkstraUtxoFailure.UtxosFailure(x) => conwayUtxo(u.tag, UtxoFailure.Utxos(x))
      case other                               => fail(s"no case of the oracle reaches $other")
    f match
      case DijkstraUtxowFailure.UtxoFailure(u) =>
        CborOut.build(w => w.array(2).uint(f.tag).raw(utxo(u)))
      case DijkstraUtxowFailure.MissingVKeyWitnesses(hs) =>
        conwayUtxow(UtxowFailure.MissingVKeyWitnesses(hs))
      case DijkstraUtxowFailure.MissingScriptWitnesses(hs) =>
        conwayUtxow(UtxowFailure.MissingScriptWitnesses(hs))
      case DijkstraUtxowFailure.ExtraneousScriptWitnesses(hs) =>
        conwayUtxow(UtxowFailure.ExtraneousScriptWitnesses(hs))
      case DijkstraUtxowFailure.ScriptWitnessNotValidating(hs) =>
        conwayUtxow(UtxowFailure.ScriptWitnessNotValidating(hs))
      case other => fail(s"no case of the oracle reaches $other")

  private def bbody(f: DijkstraBbodyFailure): Bytes = f match
    case DijkstraBbodyFailure.WrongBlockBodySize(m) =>
      mismatch(f.tag, Value.UInt(m.supplied), Value.UInt(m.expected))
    case DijkstraBbodyFailure.InvalidBodyHash(m) =>
      mismatch(f.tag, Value.ByteString(m.supplied), Value.ByteString(m.expected))
    case DijkstraBbodyFailure.HeaderProtVerTooLow(m) =>
      mismatch(f.tag, Value.UInt(m.supplied), Value.UInt(m.expected))
    // `ShelleyLedgersPredFailure`'s `LedgerFailure` is written as the LEDGER failure itself
    case DijkstraBbodyFailure.LedgersFailure(DijkstraLedgerFailure.UtxowFailure(u)) =>
      CborOut.build(w => w.array(2).uint(f.tag).array(2).uint(1).raw(utxow(u)))
    case other => fail(s"no case of the oracle refuses a block with $other")

  private def judged[R](
      result: Either[DijkstraHalt, Either[cats.data.NonEmptyChain[R], ?]]
  )(bytes: R => Bytes): Verdict = result match
    case Left(h)         => Verdict.Halted(h)
    case Right(Right(_)) => Verdict.Accepted
    case Right(Left(fs)) => Verdict.Refused(fs.toChain.toVector.map(bytes))

  private def decoded[A](hexes: Vector[String])(read: Node => Either[?, A]): Vector[A] =
    hexes.map(h => ok(read(node(hex(h)))))

  private def strings(c: ACursor): Vector[String] =
    c.as[Vector[String]].getOrElse(Vector.empty)

  private def certEnv(pp: ProtocolParams, epoch: BigInt) =
    CertEnv(Bytes.fromArray(Array.fill(32)(6)), pp, epoch, None, Map.empty)

  private def runCerts(pp: ProtocolParams, epoch: BigInt, cs: Vector[DijkstraCert]) =
    DijkstraSts.judge(DijkstraCertsRule, Judgment.Policy.Accumulate)(
      globals,
      certEnv(pp, epoch),
      ledger(pp, epoch).state.cert,
      cs
    )

  private def scarab(c: Json): Verdict =
    val h = c.hcursor
    val pp = params(c)
    val epoch = num(h.downField("epoch"))
    str(h.downField("kind")) match
      case "bbody" =>
        val block = Block.decodeShelley(hex(str(h.downField("block")))) match
          case Right(Block.Dijkstra(b)) => b
          case other                    => fail(s"not a Dijkstra block: $other")
        val issuer = DijkstraBbody.issuer(block).getOrElse(fail("issuer"))
        judged(
          DijkstraSts.judge(DijkstraBbody, Judgment.Policy.Accumulate)(
            globals,
            BbodyEnv(TestLedger.env.copy(slot = block.header.slot, epoch = epoch), pp, Coin(0)),
            DijkstraBbodyState(ledger(pp, epoch, utxoOf(c)), BlocksMade.empty),
            DijkstraBbody.Signal(block, issuer)
          )
        )(bbody)
      case "certs" =>
        val cs = decoded(strings(h.downField("certs")))(DijkstraCert.decode)
        judged(runCerts(pp, epoch, cs))(f => dijkstra(certs(f)))
      case "gov"   => judged(flatGov(c))(gov)
      case "utxow" => judged(flatUtxow(c, xyz.kd5ujc.scarab.ledger.TxEnv.Validation.All))(utxow)
      case other   => fail(s"kind $other")

  /** A GOV failure as Dijkstra's encoder writes it: Conway's, with a `Mismatch` as one item. */
  private def gov(g: xyz.kd5ujc.scarab.ledger.GovFailure): Bytes =
    dijkstra(conway(PredicateFailure.Gov(g)))

  /** The flat GOV on a `gov` case: its setup's certificate state, the default store of proposals,
    * for a transaction whose id is 32 bytes of 6.
    */
  private def flatGov(c: Json) =
    val h = c.hcursor
    val pp = params(c)
    val epoch = num(h.downField("epoch"))
    val setup = decoded(strings(h.downField("setup")))(DijkstraCert.decode)
    // a setup the flat rules halt on (a pool's registration) halts the case
    runCerts(pp, epoch, setup).flatMap {
      case Right(cert) => Right(flatGovOn(c, cert))
      case Left(fs)    => fail(s"the setup does not pass: $fs")
    }.flatten

  private def flatGovOn(c: Json, cert: ConwayCertState) =
    val h = c.hcursor
    val pp = params(c)
    val epoch = num(h.downField("epoch"))
    val votes = h
      .downField("votes")
      .as[String]
      .toOption
      .fold(Vector.empty)(v => ok(DijkstraVoting.procedures(node(hex(v)))))
    val proposals = h
      .downField("proposals")
      .as[String]
      .toOption
      .fold(Vector.empty)(p => ok(DijkstraProposal.set(node(hex(p)))))
    DijkstraSts.judge(DijkstraGov, Judgment.Policy.Accumulate)(
      globals,
      GovEnv(Bytes.fromArray(Array.fill(32)(6)), epoch, pp, None, cert, None),
      Proposals.empty,
      DijkstraGovSignal(votes, proposals, Vector.empty)
    )

  /** The flat UTXOW on a `utxow` case under `validation`. */
  private def flatUtxow(c: Json, validation: xyz.kd5ujc.scarab.ledger.TxEnv.Validation) =
    val h = c.hcursor
    val pp = params(c)
    val epoch = num(h.downField("epoch"))
    val tx = ok(DijkstraTx.inBlock(node(hex(str(h.downField("tx"))))))
    val utxo = utxoOf(c)
    val start = ledger(pp, epoch, utxo)
    DijkstraSts.judge(DijkstraUtxow, Judgment.Policy.Accumulate)(
      globals,
      DijkstraUtxoEnv(
        TestLedger.env
          .copy(slot = num(h.downField("slot")), epoch = epoch, validation = validation),
        pp,
        setupCerts(c).pState,
        setupCerts(c),
        utxo
      ),
      Counted(start.state.utxo, xyz.kd5ujc.scarab.ledger.PlutusStats.zero),
      tx
    )

  /** The certificate state a `utxow` case's setup certificates leave. */
  private def setupCerts(c: Json): ConwayCertState =
    val h = c.hcursor
    val pp = params(c)
    val epoch = num(h.downField("epoch"))
    val setup = decoded(strings(h.downField("setup")))(DijkstraCert.decode)
    runCerts(pp, epoch, setup) match
      case Right(Right(s)) => s
      case other           => fail(s"the setup does not pass: $other")

  private lazy val observations: Vector[(String, Verdict, Verdict)] = cases.map { c =>
    (str(c.hcursor.downField("name")), scarab(c), reference(c))
  }

  test("the fixture holds the oracle's cases") {
    assertEquals(cases.size, 70)
  }

  test("every completed case agrees with the unchanged reference verdict") {
    val completed = observations.filter { (_, ours, _) =>
      ours match
        case Verdict.Halted(_) => false
        case _                 => true
    }
    assertEquals(completed.size, 66, "completed oracle comparisons; halted cases are incomplete")
    val differing = completed.collect {
      case (name, ours, expected) if ours != expected => s"$name: $ours, the reference $expected"
    }
    assertEquals(differing, Vector.empty[String])
  }

  /** The cases of a pool's vote on a parameter change of a key Dijkstra adds, whose setup registers
    * the pool, which the flat CERTS halts on; the era's rules judge them.
    */
  private val poolVoteCases = Set("gov/pool-vote-key-38", "gov/pool-vote-key-47")

  test("unfinished ENTITIES cases are reported separately from reference agreement") {
    val incomplete = observations.collect { case (name, Verdict.Halted(cause), expected) =>
      (name, cause, expected)
    }
    assertEquals(incomplete.size, 4, "incomplete oracle comparisons, not conformance passes")
    incomplete.foreach { (name, cause, expected) =>
      // the two pools' votes register a pool first, which the flat rules halt on
      val pending =
        if poolVoteCases(name) then DijkstraPending.PoolRegistration else DijkstraPending.Entities
      assertEquals(
        cause,
        DijkstraHalt.NotYet(DijkstraNotYet.Rules, pending),
        s"$name remains incomplete; the unchanged reference verdict is $expected"
      )
    }
  }

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

  private def eraBytes(f: xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraUtxoRun.Rec): Bytes =
    utxow(
      xyz.kd5ujc.scarab.ledger.era.rules.DijkstraUtxowPredFailure
        .toStored(f, _ => None)
        .fold(u => fail(s"no flat form of $u"), identity)
    )

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
    val cert = setupCerts(c)
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
      DijkstraUtxoContext(Network.Testnet, 0, history, validation),
      ledger(pp, epoch, utxo).state.utxo,
      xyz.kd5ujc.scarab.plutus.ScarabEvaluator.default,
      xyz.kd5ujc.scarab.ledger.Verdicts.Direct,
      xyz.kd5ujc.scarab.plutus.ScarabEvaluator.defaultDecoder
    )

  private lazy val utxowCases: Vector[Json] =
    cases.filter(c => str(c.hcursor.downField("kind")) == "utxow")

  test("the era-typed UTXOW gives the reference's verdict on every utxow case, byte for byte") {
    assertEquals(utxowCases.size, 17)
    val differing = utxowCases.flatMap { c =>
      val name = str(c.hcursor.downField("name"))
      val ours = eraUtxow(c)
      val expected = reference(c)
      Option.when(ours != expected)(s"$name: $ours, the reference $expected")
    }
    assertEquals(differing, Vector.empty[String])
  }

  test("the era-typed UTXOW gives the flat rule's verdict on every utxow case") {
    val differing = utxowCases.flatMap { c =>
      val name = str(c.hcursor.downField("name"))
      val (ours, flat) = (eraUtxow(c), scarab(c))
      Option.when(ours != flat)(s"$name: $ours, the flat rule $flat")
    }
    assertEquals(differing, Vector.empty[String])
  }

  test("the era-typed UTXOW leaves the flat rule's state on every utxow case both admit") {
    val all = xyz.kd5ujc.scarab.ledger.TxEnv.Validation.All
    val admitted = utxowCases.flatMap { c =>
      val name = str(c.hcursor.downField("name"))
      (eraUtxowRun(c, all), flatUtxow(c, all)) match
        case (Right(Right((era, _))), Right(Right(flat))) =>
          assertEquals(era, flat.state, name)
          Some(name)
        case (Right(Left(_)), Right(Left(_))) => None
        case other                            => fail(s"$name: $other")
    }
    assertEquals(admitted.size, 4)
  }

  test(
    "under the policy that skips the signal's checks, the era-typed UTXOW gives the flat rule's"
  ) {
    val nonStatic = xyz.kd5ujc.scarab.ledger.TxEnv.Validation.NonStatic
    val differing = utxowCases.flatMap { c =>
      val name = str(c.hcursor.downField("name"))
      val era = eraUtxowRun(c, nonStatic) match
        case Left(defect)    => fail(s"$name: $defect")
        case Right(Right(_)) => Verdict.Accepted
        case Right(Left(fs)) => Verdict.Refused(fs.toChain.toVector.map(eraBytes))
      val flat = judged(flatUtxow(c, nonStatic))(utxow)
      Option.when(era != flat)(s"$name: $era, the flat rule $flat")
    }
    assertEquals(differing, Vector.empty[String])
  }

  // ---- the era-typed CERTS and ENTITIES -----------------------------------------------------------

  /** The globals of the Leios build, whose layout Dijkstra's POOL registers pools in. */
  private val leiosGlobals =
    globals.copy(ledger =
      globals.ledger.copy(referenceBuild = xyz.kd5ujc.scarab.protocol.ReferenceBuild.LeiosB004fe0c)
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
      ledger(pp, num(c.hcursor.downField("epoch"))).state.cert,
      strings(c.hcursor.downField(field)).map(h => DijkstraCorpus.eraCert(hex(h)))
    )

  private def eraCerts(c: Json): Verdict = eraCertsRun(c, "certs") match
    case Left(defect)    => fail(s"${str(c.hcursor.downField("name"))}: $defect")
    case Right(Right(_)) => Verdict.Accepted
    case Right(Left(fs)) =>
      Verdict.Refused(
        fs.toChain.toVector.map(f =>
          dijkstra(certs(xyz.kd5ujc.scarab.ledger.era.rules.DijkstraCertsStored.toStored(f)))
        )
      )

  private lazy val certsCases: Vector[Json] =
    cases.filter(c => str(c.hcursor.downField("kind")) == "certs")

  test("the era-typed CERTS gives the reference's verdict on every certs case, byte for byte") {
    assertEquals(certsCases.size, 20)
    val differing = certsCases.flatMap { c =>
      val name = str(c.hcursor.downField("name"))
      val (ours, expected) = (eraCerts(c), reference(c))
      Option.when(ours != expected)(s"$name: $ours, the reference $expected")
    }
    assertEquals(differing, Vector.empty[String])
  }

  test("the era-typed CERTS gives the flat rule's verdict on every certs case, and its state") {
    val admitted = certsCases.flatMap { c =>
      val name = str(c.hcursor.downField("name"))
      assertEquals(eraCerts(c), scarab(c), name)
      (
        eraCertsRun(c, "certs"),
        runCerts(
          params(c),
          num(c.hcursor.downField("epoch")),
          decoded(strings(c.hcursor.downField("certs")))(DijkstraCert.decode)
        )
      ) match
        case (Right(Right((era, _))), Right(Right(flat))) =>
          assertEquals(era, flat, name)
          Some(name)
        case _ => None
    }
    assertEquals(admitted.size, certsCases.count(c => reference(c) == Verdict.Accepted))
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
        ledger(pp, num(c.hcursor.downField("epoch"))).state.cert,
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
    assertEquals(agreed.size, 20)
    assertEquals(agreed.count(identity), certsCases.count(c => reference(c) == Verdict.Accepted))
  }

  test("the era-typed CERTS leaves the flat rule's state after every gov and utxow case's setup") {
    val withSetup = cases.filter(c => strings(c.hcursor.downField("setup")).nonEmpty)
    assertEquals(withSetup.size, 20)
    withSetup.foreach { c =>
      val name = str(c.hcursor.downField("name"))
      val flat = runCerts(
        params(c),
        num(c.hcursor.downField("epoch")),
        decoded(strings(c.hcursor.downField("setup")))(DijkstraCert.decode)
      )
      (eraCertsRun(c, "setup"), flat) match
        case (Right(Right((era, _))), Right(Right(flat))) => assertEquals(era, flat, name)
        // the era's CERTS registers the pool the flat rule halts on
        case (
              Right(Right((era, _))),
              Left(DijkstraHalt.NotYet(_, DijkstraPending.PoolRegistration))
            ) if poolVoteCases(name) =>
          assert(
            era.pState.stakePools.keySet.map(_.bytes).contains(Bytes.fromArray(Array.fill(28)(8))),
            name
          )
        case other => fail(s"$name: $other")
    }
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
    val start = ledger(pp, num(block.hcursor.downField("epoch")), utxoOf(block)).state.cert
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
      Verdict.Refused(
        fs.toChain.toVector.map(f =>
          gov(
            xyz.kd5ujc.scarab.ledger.era.EraGovRun
              .dijkstraStored(eraGovSignal(c), f)
              .fold(n => fail(s"no flat form of $n"), identity)
          )
        )
      )

  test("the era-typed GOV gives the reference's verdict on every gov case, byte for byte") {
    assertEquals(govCases.size, 18)
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

  test("the era-typed GOV gives the flat rule's verdict on every gov case, and its state") {
    import xyz.kd5ujc.scarab.ledger.era.rules.{ConwayGovEvent, drepCredentialOrdering}
    val judged = govCases.filterNot(c => poolVoteCases(str(c.hcursor.downField("name"))))
    val admitted = judged.flatMap { c =>
      val name = str(c.hcursor.downField("name"))
      assertEquals(eraGov(c), scarab(c), name)
      (eraGovRun(c), flatGov(c)) match
        case (Right(Right((era, events))), Right(Right(flat))) =>
          assertEquals(
            xyz.kd5ujc.scarab.ledger.era.EraGovRun.dijkstraToStored(era),
            Right(flat),
            name
          )
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
        case (Right(Left(_)), Right(Left(_))) => None
        case other                            => fail(s"$name: $other")
    }
    assertEquals(admitted.size, judged.count(c => reference(c) == Verdict.Accepted))
    assertEquals(admitted.size, 6)
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

  /** A LEDGER failure in the flat form, as the flat Dijkstra rules give it. */
  private def eraLedgerStored(
      f: xyz.kd5ujc.scarab.ledger.era.rules.DijkstraLedgerPredFailure[Dijkstra]
  ): DijkstraLedgerFailure =
    xyz.kd5ujc.scarab.ledger.era.rules.DijkstraLedgerPredFailure
      .toStored(f, _ => None, _ => None)
      .fold(u => fail(s"no flat form of $u"), identity)

  /** A LEDGER failure as the reference's `EncCBOR` writes it, for the cases the oracle reaches:
    * `DijkstraUtxowFailure`, tag 1, of UTXOW's failure.
    */
  private def ledgerBytes(f: DijkstraLedgerFailure): Bytes = f match
    case DijkstraLedgerFailure.UtxowFailure(u) =>
      CborOut.build(w => w.array(2).uint(f.tag).raw(utxow(u)))
    case other => fail(s"no case of the oracle reaches $other")

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
    val start = ledger(pp, num(h.downField("epoch")), utxo).state
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
          0,
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
        else Verdict.Refused(utxowPart.map(f => ledgerBytes(eraLedgerStored(f))).map(payload)),
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
        else Verdict.Refused(fs.map(f => payload(ledgerBytes(eraLedgerStored(f)))))
      val expected =
        if name == severalAtRoot then
          reference(caseNamed("bbody/devnet-37")) match
            case Verdict.Refused(bs) =>
              Verdict.Refused(bs.map(b => payload(inLedgers(b).getOrElse(fail(s"$b")))))
            case other => fail(s"$other")
        else reference(caseNamed(name))
      Option.when(ours != expected)(s"$name: $ours, the reference $expected")
    }
    assertEquals(whole.size, 15)
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
          0,
          history,
          xyz.kd5ujc.scarab.ledger.TxEnv.Validation.All
        ),
      ledger(pp, num(h.downField("epoch")), utxoOf(c)).state,
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
    // genesis output it spends, the reference and LEDGERS admit it, where the flat rules halt
    assertEquals(bbodyCases.size, 15)
    val judged = bbodyCases.map { c =>
      val name = str(c.hcursor.downField("name"))
      val ours = eraLedgersRun(c) match
        case Left(defect)    => fail(s"$name: $defect")
        case Right(Right(_)) => Vector.empty
        case Right(Left(fs)) =>
          fs.toChain.toVector.map {
            case xyz.kd5ujc.scarab.ledger.era.rules.ShelleyLedgersPredFailure.LedgerFailure(l) =>
              ledgerBytes(eraLedgerStored(l))
          }
      val recorded = reference(c) match
        case Verdict.Accepted    => Vector.empty
        case Verdict.Refused(bs) => bs.flatMap(inLedgers)
        case other               => fail(s"$name: $other")
      assertEquals(ours, recorded, name)
      name -> ours.nonEmpty
    }
    assertEquals(
      judged.filter(_._2).map(_._1),
      Vector("bbody/devnet-37", "bbody/devnet-37-invalid-flag")
    )
  }

  test("the era-typed LEDGER and LEDGERS give the flat rules' failures where those judge") {
    // the flat LEDGER judges a transaction flagged invalid without sub-transactions, and halts
    // on any other at ENTITIES or SUBLEDGERS (`DijkstraPending`): of the oracle's cases, the
    // `utxow` and `bbody` cases flagged invalid
    val c = caseNamed("utxow/invalid-flag")
    val h = c.hcursor
    val pp = params(c)
    val epoch = num(h.downField("epoch"))
    val flatTx = ok(DijkstraTx.inBlock(node(hex(str(h.downField("tx"))))))
    val flat = DijkstraSts.judge(DijkstraLedgerRule, Judgment.Policy.Accumulate)(
      globals,
      LedgerEnv(TestLedger.env.copy(slot = num(h.downField("slot")), epoch = epoch), Coin(0)),
      ledger(pp, epoch, utxoOf(c)),
      flatTx
    ) match
      case Right(Left(fs)) => fs.toChain.toVector
      case other           => fail(s"the flat LEDGER: $other")
    assertEquals(eraLedgerFailures(c).map(eraLedgerStored), flat)
    val b = caseNamed("bbody/devnet-37-invalid-flag")
    val flatBlock = Block.decodeShelley(hex(str(b.hcursor.downField("block")))) match
      case Right(Block.Dijkstra(block)) => block
      case other                        => fail(s"not a Dijkstra block: $other")
    val flatLedgers = DijkstraSts.judge(DijkstraLedgers, Judgment.Policy.Accumulate)(
      globals,
      LedgersEnv(
        TestLedger.env
          .copy(slot = flatBlock.header.slot, epoch = num(b.hcursor.downField("epoch"))),
        Coin(0)
      ),
      ledger(params(b), num(b.hcursor.downField("epoch")), utxoOf(b)),
      flatBlock.transactions
    ) match
      case Right(Left(fs)) => fs.toChain.toVector
      case other           => fail(s"the flat LEDGERS: $other")
    val era = eraLedgersRun(b) match
      case Right(Left(fs)) =>
        fs.toChain.toVector.map {
          case xyz.kd5ujc.scarab.ledger.era.rules.ShelleyLedgersPredFailure.LedgerFailure(l) =>
            eraLedgerStored(l)
        }
      case other => fail(s"the era LEDGERS: $other")
    assertEquals(era, flatLedgers)
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
          0,
          history,
          xyz.kd5ujc.scarab.ledger.TxEnv.Validation.All
        ),
      xyz.kd5ujc.scarab.ledger.era.rules.ShelleyBbodyState[Dijkstra](
        ledger(pp, num(h.downField("epoch")), utxoOf(c)).state,
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
          val ours = fs.toChain.toVector.map(f =>
            bbody(
              xyz.kd5ujc.scarab.ledger.era.rules.DijkstraBbody
                .toStored(f, _ => None, _ => None)
                .fold(u => fail(s"$name: no flat form of $u"), identity)
            )
          )
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
