// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import io.circe.{ACursor, Json, parser}
import java.nio.file.{Files, Path}
import java.time.Instant
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Rational, Value}
import xyz.kd5ujc.scarab.ledger.{
  Block,
  CertFailure,
  CertsFailure,
  EpochGeometry,
  LedgerGlobals,
  PoolFailure,
  PredicateFailure,
  Proposals,
  ProtocolParams,
  TestGeometry,
  TestLedger
}
import xyz.kd5ujc.scarab.ledger.dijkstra.*
import xyz.kd5ujc.scarab.ledger.rules.*
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.protocol.DijkstraNotYet

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
  */
class DijkstraRulesOracleSuite extends FunSuite:
  private val fixture = Path.of("fixtures", "reference", "dijkstra-rules", "cases.jsonl")

  private val globals = Globals(
    LedgerGlobals(TestLedger.Supply, 100, Rational(1, 2), network = 0, updateQuorum = 2),
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
        keyDeposit = num(p.downField("keyDeposit")),
        poolDeposit = num(p.downField("poolDeposit")),
        drepDeposit = num(p.downField("drepDeposit")),
        govActionDeposit = num(p.downField("govActionDeposit")),
        govActionLifetime = num(p.downField("govActionLifetime")),
        eMax = num(p.downField("eMax")),
        minPoolCost = num(p.downField("minPoolCost"))
      )

  private def ledger(pp: ProtocolParams, epoch: BigInt): DijkstraLedger =
    Counted(
      NewEpochState.empty[Dijkstra](ok(EpochNo.fromBigInt(epoch)), pp).es.ls,
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
  private def payload(conway: Bytes): Bytes = node(conway).value match
    case Value.Arr(Vector(_, item)) => item.original
    case other                      => fail(s"not a constructor of one field: $other")

  private def conway(f: PredicateFailure): Bytes =
    payload(CborOut.build(w => PredicateFailureCbor.ledger(w, f)))

  /** Dijkstra's failure types write a `Mismatch` as a record of its two values (`To mm`:
    * `Dijkstra/Rules/Gov.hs:168-169, 180-181`, `GovCert.hs:81, 83`), where Conway's write them in
    * line (`ToGroup mm`, `Conway/Rules/Gov.hs:269-270`); Conway's DELEG, which Dijkstra keeps,
    * writes them in line too. `regroup(at)` makes the record of the two items from position `at` of
    * the constructor at `path`, the tags of the constructors that hold it from the outermost.
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

  private def bbody(f: DijkstraBbodyFailure): Bytes = f match
    case DijkstraBbodyFailure.WrongBlockBodySize(m) =>
      mismatch(f.tag, Value.UInt(m.supplied), Value.UInt(m.expected))
    case DijkstraBbodyFailure.InvalidBodyHash(m) =>
      mismatch(f.tag, Value.ByteString(m.supplied), Value.ByteString(m.expected))
    case DijkstraBbodyFailure.HeaderProtVerTooLow(m) =>
      mismatch(f.tag, Value.UInt(m.supplied), Value.UInt(m.expected))
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
        Sts(DijkstraBbody)(
          globals,
          BbodyEnv(TestLedger.env.copy(epoch = epoch), pp, Coin(0)),
          DijkstraBbodyState(ledger(pp, epoch), BlocksMade.empty),
          DijkstraBbody.Signal(block, issuer)
        ) match
          case Right(_)                                 => Verdict.Accepted
          case Left(DijkstraBlockFailure.Rejected(f))   => Verdict.Refused(Vector(bbody(f)))
          case Left(DijkstraBlockFailure.Halted(cause)) => Verdict.Halted(cause)
      case "certs" =>
        val cs = decoded(strings(h.downField("certs")))(DijkstraCert.decode)
        judged(runCerts(pp, epoch, cs))(f => dijkstra(certs(f)))
      case "gov" =>
        val setup = decoded(strings(h.downField("setup")))(DijkstraCert.decode)
        val cert = runCerts(pp, epoch, setup) match
          case Right(Right(s)) => s
          case other           => fail(s"the setup does not pass: $other")
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
        judged(
          DijkstraSts.judge(DijkstraGov, Judgment.Policy.Accumulate)(
            globals,
            GovEnv(Bytes.fromArray(Array.fill(32)(6)), epoch, pp, None, cert, None),
            Proposals.empty,
            DijkstraGovSignal(votes, proposals, Vector.empty)
          )
        )(g => dijkstra(conway(PredicateFailure.Gov(g))))
      case other => fail(s"kind $other")

  /** The cases that reach a rule this build does not run yet, with the part they reach. */
  private val incomplete: Map[String, DijkstraPending] =
    Map("bbody/devnet-37" -> DijkstraPending.Entities)

  test("the fixture holds the oracle's cases") {
    assertEquals(cases.size, 48)
  }

  test("every case the node's rules complete agrees with the reference") {
    val differing = cases.flatMap { c =>
      val name = str(c.hcursor.downField("name"))
      val expected = incomplete
        .get(name)
        .fold(reference(c))(part => Verdict.Halted(DijkstraHalt.NotYet(DijkstraNotYet.Rules, part)))
      val ours = scarab(c)
      Option.when(ours != expected)(s"$name: $ours, the reference $expected")
    }
    assertEquals(differing, Vector.empty[String])
  }
