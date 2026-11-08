// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.data.NonEmptyChain
import java.nio.file.{Files, Path}
import java.time.Instant
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{
  Block,
  Credential,
  DelegFailure,
  EpochGeometry,
  GovCertFailure,
  GovFailure,
  LedgerGlobals,
  LeiosBlock,
  Mismatch,
  PlutusStats,
  Proposals,
  ProtocolParams,
  RewardAccount,
  TestGeometry,
  TestLedger
}
import xyz.kd5ujc.scarab.ledger.dijkstra.*
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.protocol.DijkstraNotYet

/** The Dijkstra rules of cardano-ledger 9df9f98c that this build has (BBODY, LEDGER, CERT, CERTS,
  * POOL, GOV): BBODY over the Dijkstra blocks of the reference cluster's chain
  * (`fixtures/dijkstra-devnet`, provenance in its README), which the reference accepted, and each
  * rule on its own. The reference's verdicts on the refused cases are the oracle's
  * (`DijkstraRulesOracleSuite`).
  */
class DijkstraRulesSuite extends FunSuite:
  private val globals = Globals(
    LedgerGlobals(TestLedger.Supply, 100, Rational(1, 2), network = 0, updateQuorum = 2),
    EpochGeometry(Instant.EPOCH, 1000, 10000, TestGeometry.k(5), TestGeometry.f("0.05"))
  )

  private def ok[A](e: Either[VocabError, A]): A = e.fold(v => fail(s"vocabulary: $v"), identity)

  /** The parameters of the devnet's first Dijkstra epoch: protocol 12 with the Dijkstra genesis. */
  private val pp: ProtocolParams = DijkstraFixture.genesis.upgrade(TestLedger.params(12))

  private def ledger(params: ProtocolParams): DijkstraLedger =
    Counted(
      NewEpochState.empty[Dijkstra](ok(EpochNo.fromBigInt(1)), params).es.ls,
      PlutusStats.zero
    )

  // ---- the devnet chain

  private val devnet = Path.of("fixtures", "dijkstra-devnet")

  private lazy val dijkstraBlocks: Vector[(BigInt, LeiosBlock)] =
    Files
      .readAllLines(devnet.resolve("blocks.tsv"))
      .asScala
      .toVector
      .drop(1)
      .map(_.split('\t'))
      .filter(_(0) == "8")
      .map { p =>
        val raw = Bytes.fromHex(p(4)).fold(e => fail(e.toString), identity)
        Block.decodeShelley(raw).fold(e => fail(e.message), identity) match
          case Block.Dijkstra(b) => BigInt(p(1)) -> b
          case other             => fail(s"block ${p(1)} is of ${other.era}")
      }

  private def bbody(
      block: LeiosBlock,
      params: ProtocolParams = pp,
      from: DijkstraBbodyState = DijkstraBbodyState(ledger(pp), BlocksMade.empty)
  ): Either[DijkstraBlockFailure, DijkstraBbodyState] =
    val issuer = DijkstraBbody.issuer(block).getOrElse(fail("issuer"))
    Sts(DijkstraBbody)(
      globals,
      BbodyEnv(TestLedger.env, params, Coin(0)),
      from,
      DijkstraBbody.Signal(block, issuer)
    )

  test("the devnet's Dijkstra blocks are its eight, one of them with a valid transaction") {
    assertEquals(dijkstraBlocks.map(_._1), (33 to 40).map(BigInt(_)).toVector)
    assertEquals(dijkstraBlocks.filter(_._2.transactions.nonEmpty).map(_._1), Vector(BigInt(37)))
  }

  test("BBODY accepts every empty Dijkstra block of the devnet and counts it for its issuer") {
    dijkstraBlocks.filter(_._2.transactions.isEmpty).foreach { (n, block) =>
      val after = bbody(block).fold(f => fail(s"block $n: $f"), identity)
      val issuer = DijkstraBbody.issuer(block).getOrElse(fail("issuer"))
      assertEquals(after.blocksMade.blocks.get(issuer).map(_.bits), Some(1L), s"block $n")
      assertEquals(after.ledger, ledger(pp), s"block $n changes no ledger state")
    }
  }

  test("BBODY of the devnet's block with a valid transaction halts at ENTITIES") {
    val (_, block) = dijkstraBlocks.find(_._2.transactions.nonEmpty).getOrElse(fail("block 37"))
    assertEquals(
      bbody(block),
      Left(
        DijkstraBlockFailure.Halted(
          DijkstraHalt.NotYet(DijkstraNotYet.Rules, DijkstraPending.Entities)
        )
      )
    )
  }

  private lazy val empty = dijkstraBlocks
    .collectFirst {
      case (_, b) if b.transactions.isEmpty => b
    }
    .getOrElse(fail("no empty block"))

  private def rejected(f: DijkstraBbodyFailure) = Left(DijkstraBlockFailure.Rejected(f))

  test("BBODY refuses a header whose body size is not the body's") {
    val header = empty.header.copy(bodySize = empty.header.bodySize + 1)
    assertEquals(
      bbody(empty.copy(header = header)),
      rejected(
        DijkstraBbodyFailure.WrongBlockBodySize(Mismatch(BigInt(empty.bodySize), header.bodySize))
      )
    )
  }

  test("BBODY refuses a header whose body hash is not the body's, after its size") {
    val other = Bytes.fromArray(Array.fill(32)(9.toByte))
    val header = empty.header.copy(bodyHash = other)
    assertEquals(
      bbody(empty.copy(header = header)),
      rejected(DijkstraBbodyFailure.InvalidBodyHash(Mismatch(empty.bodyHash, other)))
    )
    assertEquals(
      bbody(empty.copy(header = header.copy(bodySize = header.bodySize + 1))).left.map {
        case DijkstraBlockFailure.Rejected(f) => f.tag
        case other                            => fail(s"$other")
      },
      Left(0)
    )
  }

  test("BBODY refuses a header that supports no higher major than the parameters'") {
    val params = pp.copy(protocolMajor = 13)
    assertEquals(
      bbody(empty, params, DijkstraBbodyState(ledger(params), BlocksMade.empty)),
      rejected(DijkstraBbodyFailure.HeaderProtVerTooLow(Mismatch(BigInt(12), BigInt(13))))
    )
  }

  test("BBODY refuses every Peras certificate, as validatePerasCert does") {
    val cert = Bytes.fromArray(Array(0x80.toByte))
    assertEquals(
      bbody(empty.copy(perasCertificate = Some(cert))),
      rejected(
        DijkstraBbodyFailure.PerasCertValidationFailed(cert, xyz.kd5ujc.scarab.ledger.Nonce.Neutral)
      )
    )
  }

  test("the execution units of an empty block, none, fit a limit of zero") {
    val params = pp.copy(maxBlockExUnits = ExUnits(0, 0))
    assertEquals(
      bbody(empty, params, DijkstraBbodyState(ledger(params), BlocksMade.empty)),
      Right(
        DijkstraBbodyState(
          ledger(params),
          bbody(empty).toOption.map(_.blocksMade).getOrElse(fail(""))
        )
      )
    )
  }

  // ---- CERT and CERTS

  private def key(n: Int): Credential = Credential.Key(Bytes.fromArray(Array.fill(28)(n.toByte)))

  private val certEnv = CertEnv(Bytes.fromArray(Array.fill(32)(0)), pp, 1, None, Map.empty)

  private val emptyCert: ConwayCertState = ledger(pp).state.cert

  private def certs(
      cs: Vector[DijkstraCert],
      from: ConwayCertState = emptyCert
  ): Either[DijkstraHalt, Either[NonEmptyChain[DijkstraCertsFailure], ConwayCertState]] =
    DijkstraSts.judge(DijkstraCertsRule, Judgment.Policy.Accumulate)(globals, certEnv, from, cs)

  private def refused(fs: DijkstraCertFailure*) =
    Right(Left(NonEmptyChain.fromSeq(fs.map(DijkstraCertsFailure(_))).getOrElse(fail("none"))))

  test("CERTS registers and unregisters a stake credential with Conway's DELEG") {
    val registered = certs(Vector(DijkstraCert.Register(key(1), pp.keyDeposit)))
      .fold(h => fail(s"$h"), _.fold(f => fail(s"$f"), identity))
    assert(CertLens.accounts.get(registered).contains(ok(Convert.credential[Staking](key(1)))))
    val gone = certs(Vector(DijkstraCert.Unregister(key(1), pp.keyDeposit)), registered)
      .fold(h => fail(s"$h"), _.fold(f => fail(s"$f"), identity))
    assertEquals(gone, emptyCert)
  }

  test("CERTS records DELEG's failure 2 for a credential registered twice") {
    assertEquals(
      certs(
        Vector(
          DijkstraCert.Register(key(1), pp.keyDeposit),
          DijkstraCert.Register(key(1), pp.keyDeposit)
        )
      ).map(_.left.map(_.map(_.failure))),
      Right(
        Left(NonEmptyChain.one(DijkstraCertFailure.Deleg(DelegFailure.StakeKeyRegistered(key(1)))))
      )
    )
  }

  test("CERTS records GOVCERT's DRepAlreadyRegistered with Conway's transition") {
    val reg = DijkstraCert.RegisterDRep(key(2), pp.drepDeposit, None)
    assertEquals(
      certs(Vector(reg, reg)).map(_.left.map(_.map(_.failure))),
      Right(
        Left(
          NonEmptyChain.one(
            DijkstraCertFailure.GovCert(GovCertFailure.DRepAlreadyRegistered(key(2)))
          )
        )
      )
    )
  }

  test("POOL refuses to retire a pool that is not registered, with Dijkstra's failure 0") {
    val pool = Bytes.fromArray(Array.fill(28)(3.toByte))
    assertEquals(
      certs(Vector(DijkstraCert.RetirePool(pool, 2))),
      refused(
        DijkstraCertFailure.Pool(DijkstraPoolFailure.StakePoolNotRegisteredOnKey(pool))
      )
    )
  }

  test("a pool registration halts: the pool state holds no BLS key") {
    val params = DijkstraPoolParams(
      Bytes.fromArray(Array.fill(28)(4.toByte)),
      Bytes.fromArray(Array.fill(32)(5.toByte)),
      None,
      0,
      0,
      Rational.zero,
      RewardAccount(0, key(4)),
      Set.empty,
      Vector.empty,
      None
    )
    assertEquals(
      certs(Vector(DijkstraCert.RegisterPool(params))),
      Left(DijkstraHalt.NotYet(DijkstraNotYet.Rules, DijkstraPending.PoolRegistration))
    )
  }

  // ---- GOV

  private val govEnv = GovEnv(Bytes.fromArray(Array.fill(32)(6)), 1, pp, None, emptyCert, None)

  private def gov(signal: DijkstraGovSignal) =
    DijkstraSts.judge(DijkstraGov, Judgment.Policy.Accumulate)(
      globals,
      govEnv,
      Proposals.empty,
      signal
    )

  // an unknown voter's votes are dropped before the actions are looked up (`Gov.hs:569-602`)
  test("GOV records a voter that does not exist and not its votes, with Conway's transition") {
    val missing = DijkstraGovActionId(Bytes.fromArray(Array.fill(32)(7)), 0)
    val signal = DijkstraGovSignal(
      Vector(DijkstraVoter.DRep(key(8)) -> Vector(missing -> DijkstraVotingProcedure(1, None))),
      Vector.empty,
      Vector.empty
    )
    assertEquals(
      gov(signal).map(_.left.map(_.toChain.toVector)),
      Right(
        Left(
          Vector(
            GovFailure.VotersDoNotExist(Vector(xyz.kd5ujc.scarab.ledger.Voter.DRepVoter(key(8))))
          )
        )
      )
    )
  }

  test("GOV halts on a parameter-change proposal") {
    val proposal = DijkstraProposal(
      pp.govActionDeposit,
      RewardAccount(0, key(9)),
      DijkstraGovAction.ParameterChange(None, DijkstraParamUpdate(Map.empty), None),
      DijkstraAnchor("", Bytes.fromArray(Array.fill(32)(0)))
    )
    assertEquals(
      gov(DijkstraGovSignal(Vector.empty, Vector(proposal), Vector.empty)),
      Left(DijkstraHalt.NotYet(DijkstraNotYet.Rules, DijkstraPending.ParameterChangeProposal))
    )
  }

  // ---- the numbers of the failures

  test("the failures carry the reference's EncCBOR tags") {
    val m = Mismatch(BigInt(0), BigInt(0))
    assertEquals(
      Vector(
        DijkstraBbodyFailure.WrongBlockBodySize(m).tag,
        DijkstraBbodyFailure.HeaderProtVerTooLow(m).tag,
        DijkstraLedgerFailure.TreasuryValueMismatch(m).tag,
        DijkstraLedgerFailure.TxRefScriptsSizeTooBig(Mismatch(0, 0)).tag,
        DijkstraUtxowFailure.MalformedGuardDatums(Set.empty).tag,
        DijkstraUtxoFailure.OutputBootAddrAttrsTooBig(Vector.empty).tag,
        DijkstraUtxoFailure
          .ValueNotConservedInLegacyMode(
            Mismatch(DijkstraValue(0, Map.empty), DijkstraValue(0, Map.empty))
          )
          .tag,
        DijkstraPoolFailure.StakePoolCostTooLow(m).tag,
        DijkstraCertFailure.Pool(DijkstraPoolFailure.StakePoolNotRegisteredOnKey(Bytes.empty)).tag
      ),
      Vector(0, 6, 4, 5, 20, 9, 23, 3, 2)
    )
  }
