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
  TestLedger,
  Vote
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
  ): Either[DijkstraHalt, Either[Vector[DijkstraBbodyFailure], DijkstraBbodyState]] =
    val issuer = DijkstraBbody.issuer(block).getOrElse(fail("issuer"))
    DijkstraSts
      .judge(DijkstraBbody, Judgment.Policy.Accumulate)(
        globals,
        BbodyEnv(TestLedger.env.copy(slot = block.header.slot, epoch = 1), params, Coin(0)),
        from,
        DijkstraBbody.Signal(block, issuer)
      )
      .map(_.left.map(_.toChain.toVector))

  test("the devnet's Dijkstra blocks are its eight, one of them with a valid transaction") {
    assertEquals(dijkstraBlocks.map(_._1), (33 to 40).map(BigInt(_)).toVector)
    assertEquals(dijkstraBlocks.filter(_._2.transactions.nonEmpty).map(_._1), Vector(BigInt(37)))
  }

  test("BBODY accepts every empty Dijkstra block of the devnet and counts it for its issuer") {
    dijkstraBlocks.filter(_._2.transactions.isEmpty).foreach { (n, block) =>
      val after =
        bbody(block).fold(h => fail(s"block $n: $h"), _.fold(f => fail(s"block $n: $f"), identity))
      val issuer = DijkstraBbody.issuer(block).getOrElse(fail("issuer"))
      assertEquals(after.blocksMade.blocks.get(issuer).map(_.bits), Some(1L), s"block $n")
      assertEquals(after.ledger, ledger(pp), s"block $n changes no ledger state")
    }
  }

  test("BBODY of the devnet's block with a valid transaction halts at ENTITIES") {
    val (_, block) = dijkstraBlocks.find(_._2.transactions.nonEmpty).getOrElse(fail("block 37"))
    assertEquals(
      bbody(block),
      Left(DijkstraHalt.NotYet(DijkstraNotYet.Rules, DijkstraPending.Entities))
    )
  }

  private lazy val empty = dijkstraBlocks
    .collectFirst {
      case (_, b) if b.transactions.isEmpty => b
    }
    .getOrElse(fail("no empty block"))

  private def rejected(fs: DijkstraBbodyFailure*) = Right(Left(fs.toVector))

  test("BBODY refuses a header whose body size is not the body's") {
    val header = empty.header.copy(bodySize = empty.header.bodySize + 1)
    assertEquals(
      bbody(empty.copy(header = header)),
      rejected(
        DijkstraBbodyFailure.WrongBlockBodySize(Mismatch(BigInt(empty.bodySize), header.bodySize))
      )
    )
  }

  // the reference records both and reports them in reverse (`Extended.hs:715-722`,
  // cardano-ledger 9df9f98c)
  test("BBODY refuses a header whose body hash is not the body's, and records a wrong size too") {
    val other = Bytes.fromArray(Array.fill(32)(9.toByte))
    val header = empty.header.copy(bodyHash = other)
    assertEquals(
      bbody(empty.copy(header = header)),
      rejected(DijkstraBbodyFailure.InvalidBodyHash(Mismatch(empty.bodyHash, other)))
    )
    assertEquals(
      bbody(empty.copy(header = header.copy(bodySize = header.bodySize + 1))),
      rejected(
        DijkstraBbodyFailure.InvalidBodyHash(Mismatch(empty.bodyHash, other)),
        DijkstraBbodyFailure.WrongBlockBodySize(
          Mismatch(BigInt(empty.bodySize), header.bodySize + 1)
        )
      )
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
        Right(
          DijkstraBbodyState(
            ledger(params),
            bbody(empty).toOption.flatMap(_.toOption).map(_.blocksMade).getOrElse(fail(""))
          )
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

  test("a pool registration halts: the pool state cannot hold a Dijkstra registration") {
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

  private def gov(signal: DijkstraGovSignal, stored: Proposals = Proposals.empty) =
    DijkstraSts.judge(DijkstraGov, Judgment.Policy.Accumulate)(
      globals,
      govEnv,
      stored,
      signal
    )

  // an unknown voter's votes are dropped before the actions are looked up (`Gov.hs:569-602`,
  // cardano-ledger 9df9f98c)
  test("GOV records a voter that does not exist and not its votes, with Conway's transition") {
    val missing = DijkstraGovActionId(Bytes.fromArray(Array.fill(32)(7)), 0)
    val signal = DijkstraGovSignal(
      Vector(
        DijkstraVoter.DRep(key(8)) -> Vector(missing -> DijkstraVotingProcedure(Vote.Yes, None))
      ),
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

  // whether a pool may vote is whether the update changes a parameter of the security group, which
  // Dijkstra's groups of keys 34 to 56 decide (`modifiedPPGroups`, cardano-ledger 9df9f98c
  // `Dijkstra/PParams.hs:798-1123`), not computed yet
  test("GOV halts on a pool's vote on a stored proposal of Dijkstra's keys, not on a DRep's") {
    val id = DijkstraGovActionId(Bytes.fromArray(Array.fill(32)(5)), 0)
    val update = xyz.kd5ujc.scarab.ledger.DijkstraKeysUpdate
      .from(DijkstraParamUpdate(Map(45 -> DijkstraParamValue.Number(BigInt(308_000)))))
      .getOrElse(fail("not an update of Dijkstra's keys"))
    val stored = Proposals(
      Vector(
        xyz.kd5ujc.scarab.ledger.GovActionState(
          DijkstraConway.actionId(id),
          Map.empty,
          Map.empty,
          Map.empty,
          xyz.kd5ujc.scarab.ledger.Proposal(
            pp.govActionDeposit,
            RewardAccount(0, key(9)),
            xyz.kd5ujc.scarab.ledger.GovAction.ParameterChange(None, update, None)
          ),
          BigInt(0),
          BigInt(6)
        )
      ),
      xyz.kd5ujc.scarab.ledger.PrevIds()
    )
    def voting(voter: DijkstraVoter) = DijkstraGovSignal(
      Vector(voter -> Vector(id -> DijkstraVotingProcedure(Vote.Yes, None))),
      Vector.empty,
      Vector.empty
    )
    val pool = Bytes.fromArray(Array.fill(28)(3))
    assertEquals(
      gov(voting(DijkstraVoter.StakePool(pool)), stored),
      Left(DijkstraHalt.NotYet(DijkstraNotYet.Rules, DijkstraPending.ParameterChangeProposal))
    )
    // a DRep may vote on any action: Conway's transition judges the vote (its voter is unknown)
    assert(gov(voting(DijkstraVoter.DRep(key(8))), stored).isRight)
  }

  // ---- the numbers of the failures

  test("the failures carry the reference's EncCBOR tags") {
    val m = Mismatch(BigInt(0), BigInt(0))
    assertEquals(
      Vector(
        DijkstraBbodyFailure.WrongBlockBodySize(m).tag,
        DijkstraBbodyFailure.HeaderProtVerTooLow(m).tag,
        DijkstraLedgerFailure.TreasuryValueMismatch(m).tag,
        DijkstraLedgerFailure.TxRefScriptsSizeTooBig(Mismatch(0L, 0L)).tag,
        DijkstraUtxowFailure.MalformedGuardDatums(Set.empty).tag,
        DijkstraUtxoFailure.OutputBootAddrAttrsTooBig(Vector.empty).tag,
        DijkstraUtxoFailure
          .ValueNotConservedInLegacyMode(
            Mismatch(xyz.kd5ujc.scarab.ledger.Value.zero, xyz.kd5ujc.scarab.ledger.Value.zero)
          )
          .tag,
        DijkstraPoolFailure.StakePoolCostTooLow(m).tag,
        DijkstraCertFailure.Pool(DijkstraPoolFailure.StakePoolNotRegisteredOnKey(Bytes.empty)).tag
      ),
      Vector(0, 6, 4, 5, 20, 9, 23, 3, 2)
    )
  }

  // the order of `certs/mixed-failures` of the oracle (`Judgment.flatten`)
  test("CERTS reports the failures of several certificates in the reference's order") {
    val mixed = Vector(
      DijkstraCert.Register(key(1), pp.keyDeposit),
      DijkstraCert.Register(key(1), pp.keyDeposit),
      DijkstraCert.RegisterDRep(key(2), 1, None),
      DijkstraCert.Unregister(key(7), pp.keyDeposit)
    )
    assertEquals(
      certs(mixed).map(_.left.map(_.map(_.failure).toChain.toVector)),
      Right(
        Left(
          Vector(
            DijkstraCertFailure.Deleg(DelegFailure.StakeKeyNotRegistered(key(7))),
            DijkstraCertFailure.Deleg(DelegFailure.StakeKeyRegistered(key(1))),
            DijkstraCertFailure.GovCert(
              GovCertFailure.DRepIncorrectDeposit(Mismatch(BigInt(1), pp.drepDeposit))
            )
          )
        )
      )
    )
  }

  // ---- UTXOW, UTXO and LEDGER

  /** The devnet transaction (block 37) and the genesis output it spends: the initial funds of
    * `607be6…` (the devnet's Shelley genesis, `extraConfig.initialFunds`), whose input is the
    * Blake2b-256 of the address, index 0.
    */
  private lazy val devnetTx: DijkstraTx =
    dijkstraBlocks.flatMap(_._2.transactions).headOption.getOrElse(fail("no transaction"))

  private val genesisAddress =
    Bytes.fromHex("607be6f4db9b5c16681157f9c2e15e57e341e21ecf94e553896f897a44").getOrElse(fail(""))

  private lazy val genesisUtxo: xyz.kd5ujc.scarab.ledger.Utxo =
    val address = xyz.kd5ujc.scarab.ledger.Address.decode(genesisAddress).getOrElse(fail("address"))
    xyz.kd5ujc.scarab.ledger.Utxo(
      Map(
        xyz.kd5ujc.scarab.ledger
          .TxIn(xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(genesisAddress), 0) ->
          xyz.kd5ujc.scarab.ledger.TxOut(
            address,
            xyz.kd5ujc.scarab.ledger.Value.coin(BigInt("900000000000000"))
          )
      )
    )

  private def funded(params: ProtocolParams): DijkstraLedger =
    val l = ledger(params)
    Counted(
      l.state.copy[Dijkstra](utxo = l.state.utxo.copy[Dijkstra](utxo = UtxoTables(genesisUtxo))),
      PlutusStats.zero
    )

  private def utxow(tx: DijkstraTx, from: DijkstraLedger = funded(pp)) =
    DijkstraSts.judge(DijkstraUtxow, Judgment.Policy.Accumulate)(
      globals,
      DijkstraUtxoEnv(
        TestLedger.env.copy(slot = 225),
        pp,
        from.state.cert.pState,
        from.state.cert,
        from.state.utxo.utxo.utxo
      ),
      Counted(from.state.utxo, PlutusStats.zero),
      tx
    )

  test("UTXOW applies the devnet transaction: its input spent, its outputs created, its fee kept") {
    val after = utxow(devnetTx).fold(h => fail(s"$h"), _.fold(f => fail(s"$f"), identity)).state
    val id = devnetTx.id
    assertEquals(
      after.utxo.utxo.keys.toSet,
      devnetTx.body.outputs.indices.map(i => xyz.kd5ujc.scarab.ledger.TxIn(id, i)).toSet
    )
    assertEquals(Some(after.fees.value), devnetTx.body.fee)
  }

  test("UTXOW halts on what the Conway form of a transaction cannot carry") {
    val guarded = devnetTx.copy(body = devnetTx.body.copy(guards = Vector(key(1))))
    val deposit = devnetTx.copy(body =
      devnetTx.body.copy(directDeposits = Vector(RewardAccount(0, key(1)) -> BigInt(1)))
    )
    def halted(p: DijkstraPending) = Left(DijkstraHalt.NotYet(DijkstraNotYet.Rules, p))
    assertEquals(utxow(guarded), halted(DijkstraPending.Guards))
    assertEquals(utxow(deposit), halted(DijkstraPending.AccountFields))
  }

  test("LEDGER judges a phase-2-invalid transaction through UTXOW in full") {
    val invalid = devnetTx.copy(isValid = false)
    val judged = DijkstraSts.judge(DijkstraLedgerRule, Judgment.Policy.Accumulate)(
      globals,
      LedgerEnv(TestLedger.env.copy(slot = 225), Coin(0)),
      funded(pp),
      invalid
    )
    assertEquals(
      judged.map(_.left.map(_.toChain.toVector)),
      Right(
        Left(
          Vector(
            DijkstraLedgerFailure.UtxowFailure(
              DijkstraUtxowFailure.UtxoFailure(
                DijkstraUtxoFailure.UtxosFailure(
                  xyz.kd5ujc.scarab.ledger.UtxosFailure.ValidationTagMismatch(false, None)
                )
              )
            )
          )
        )
      )
    )
  }

  test("LEDGER carries the batch's reference-script total in 64 bits, as the reference's Int") {
    // the devnet transaction with a reference input beside its input, and itself as a
    // sub-transaction: three inputs, each naming an output whose reference script is
    // Int.MaxValue bytes, a total past a 32-bit Int
    assertEquals((devnetTx.body.inputs.size, devnetTx.body.referenceInputs.size), (1, 0))
    val reference = DijkstraTxIn(Bytes.fromArray(Array.fill(32)(0x11)), 0)
    val sub = DijkstraSubTx(devnetTx.body.raw, devnetTx.body, devnetTx.witnesses, None)
    val tx = devnetTx.copy(body =
      devnetTx.body.copy(referenceInputs = Vector(reference), subTransactions = Vector(sub))
    )
    val each: xyz.kd5ujc.scarab.ledger.TxIn => Option[Long] = _ => Some(Int.MaxValue.toLong)
    assertEquals(DijkstraTxMeasures.batchRefScriptsSize(each, tx), 6442450941L)
    assertEquals(
      DijkstraLedgerRule.refScriptSize(pp, each, tx).toEither.left.map(_.toChain.toVector),
      Left(Vector(DijkstraLedgerFailure.TxRefScriptsSizeTooBig(Mismatch(6442450941L, 204800L))))
    )
  }

  // ---- native scripts in Dijkstra's form (#1126)

  private def bytes(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  private val guardKey = "55" * 28
  // `[6, [0, keyhash]]`: a guard by a key credential
  private val guard = "8206" + "8200581c" + guardKey
  // the devnet transaction's signer: the payment key of the genesis address
  private val genesisSigner =
    "8200581c" + "7be6f4db9b5c16681157f9c2e15e57e341e21ecf94e553896f897a44"

  /** The genesis input of the devnet transaction at a script address instead, of the native script
    * `native`, which the output holds as its reference script; stored as the store keeps a Dijkstra
    * output ([[xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTxOut.toStored]]), so a script with a
    * guard is kept without a time-lock tree. With the script's hash.
    */
  private def lockedBy(native: String): (Bytes, DijkstraLedger) =
    val hash = xyz.kd5ujc.scarab.crypto.Blake2b.hash224.hash(bytes("00" + native))
    val script = "8200" + native
    val wrapped = "d818" + f"58${script.length / 2}%02x" + script
    // `{0: script address, 1: the genesis funds, 3: the script}`
    val raw = bytes(
      "a3" + "00" + "581d70" + hash.hex + "01" + "1b0003328b944c4000" + "03" + wrapped
    )
    val out = xyz.kd5ujc.scarab.ledger.CborRead
      .decode(raw, V12.nestedLimits)
      .fold(e => fail(e.toString), identity)
    val stored = xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTxOut
      .decode(out)
      .map(xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTxOut.toStored)
      .fold(e => fail(e.toString), identity)
    val input = xyz.kd5ujc.scarab.ledger
      .TxIn(xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(genesisAddress), 0)
    val l = ledger(pp)
    val utxo = xyz.kd5ujc.scarab.ledger.Utxo(Map(input -> stored))
    hash -> Counted(
      l.state.copy[Dijkstra](utxo = l.state.utxo.copy[Dijkstra](utxo = UtxoTables(utxo))),
      PlutusStats.zero
    )

  test("UTXOW refuses a needed reference script with a guard the body does not have") {
    val (hash, from) = lockedBy(guard)
    assertEquals(
      from.state.utxo.utxo.utxo.toMap.values.flatMap(_.scriptRef).map(_.native).toVector,
      Vector(None)
    )
    assertEquals(
      utxow(devnetTx, from).map(_.left.map(_.toChain.toVector)),
      Right(Left(Vector(DijkstraUtxowFailure.ScriptWitnessNotValidating(Set(hash)))))
    )
  }

  test("UTXOW applies a transaction whose needed script holds by a signature beside its guard") {
    val (_, from) = lockedBy("8202" + "82" + guard + genesisSigner)
    val after = utxow(devnetTx, from).fold(h => fail(s"$h"), _.fold(f => fail(s"$f"), identity))
    assertEquals(after.state.utxo.utxo.keys.size, devnetTx.body.outputs.size)
  }

  test("the flat check takes no native script without a tree as holding") {
    val (hash, from) = lockedBy(guard)
    val ann = DijkstraStAnnTx
      .of(devnetTx, from.state.utxo.utxo.utxo)
      .fold(p => fail(s"$p"), identity)
    assertEquals(
      xyz.kd5ujc.scarab.ledger.TxChecks
        .failedNativeScripts(ann.conway.tx, ann.conway.scripts, Some(Set(hash)), Set.empty),
      Left(xyz.kd5ujc.scarab.ledger.LedgerFailure.NativeScriptUnread(devnetTx.id, hash))
    )
  }

  // ---- the era-typed UTXOW on the same cases (#85)

  /** The era-typed UTXOW (`era.rules.DijkstraUtxow`) on `tx` read by the era decoder, from the
    * state and parameters the flat UTXOW is given here.
    */
  private def eraUtxow(tx: DijkstraTx, from: DijkstraLedger) =
    import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraCorpus, DijkstraUtxoRun}
    import xyz.kd5ujc.scarab.ledger.era.rules.{DijkstraUtxoContext, DijkstraUtxoEnv as EraUtxoEnv}
    val era = xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTx
      .inBlock(
        xyz.kd5ujc.scarab.ledger.CborRead
          .decode(tx.raw, V12.nestedLimits)
          .fold(e => fail(e.toString), identity)
      )
      .fold(e => fail(e.toString), identity)
    val env = EraUtxoEnv[Dijkstra](
      225,
      DijkstraCorpus.era(pp),
      from.state.cert.pState,
      from.state.cert,
      DijkstraUtxoRun.originalUtxo(from.state.utxo.utxo.utxo, era)
    )
    val params = xyz.kd5ujc.scarab.ledger.Horizon.Params(100, 1000, 30, 30)
    val history = xyz.kd5ujc.scarab.ledger.Horizon
      .History(Vector.empty, xyz.kd5ujc.scarab.ledger.Horizon.Edge(0, 0, 0), params, Some(params))
      .at(xyz.kd5ujc.scarab.ledger.Horizon.Transition.Unknown(Some(BigInt(1000))))
    DijkstraUtxoRun.run(
      env,
      DijkstraUtxoRun.annotate(s => fail(s"the clock was read at $s"), env, era),
      DijkstraUtxoContext(
        Network.Testnet,
        0,
        history,
        xyz.kd5ujc.scarab.ledger.TxEnv.Validation.All
      ),
      from.state.utxo,
      xyz.kd5ujc.scarab.plutus.ScarabEvaluator.default,
      xyz.kd5ujc.scarab.ledger.Verdicts.Direct,
      xyz.kd5ujc.scarab.plutus.ScarabEvaluator.defaultDecoder
    )

  /** The era-typed UTXOW gives the flat one's failures in their flat form, or its state after. */
  private def sameAsFlat(tx: DijkstraTx, from: DijkstraLedger): Unit =
    (eraUtxow(tx, from), utxow(tx, from)) match
      case (Right(Right((era, _))), Right(Right(flat))) => assertEquals(era, flat.state)
      case (Right(Left(era)), Right(Left(flat))) =>
        assertEquals(
          era.toChain.toVector.map(f =>
            xyz.kd5ujc.scarab.ledger.era.rules.DijkstraUtxowPredFailure
              .toStored(f, _ => None)
              .fold(u => fail(s"no flat form of $u"), identity)
          ),
          flat.toChain.toVector
        )
      case other => fail(s"$other")

  test("the era-typed UTXOW gives the flat rule's verdicts and state on these UTXOW cases") {
    // the devnet transaction, its needed reference script with a guard the body lacks, and that
    // script holding by a signature beside the guard; the halting cases (a guard or a direct deposit
    // in the body, which the flat rule cannot carry) have no flat verdict to compare
    sameAsFlat(devnetTx, funded(pp))
    sameAsFlat(devnetTx, lockedBy(guard)._2)
    sameAsFlat(devnetTx, lockedBy("8202" + "82" + guard + genesisSigner)._2)
  }
