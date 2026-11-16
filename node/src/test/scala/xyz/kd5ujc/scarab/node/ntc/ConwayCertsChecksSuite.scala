// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.data.NonEmptyChain
import java.nio.file.Path
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Rational, Value}
import xyz.kd5ujc.scarab.ledger.{
  Certificate,
  CertFailure,
  CertsFailure,
  Committee,
  DelegFailure,
  GovAction,
  GovActionId,
  GovActionState,
  GovCertFailure,
  LedgerDefect,
  PoolFailure,
  PredicateFailure,
  Proposal,
  RewardAccount,
  Tx,
  TxIn,
  TxOut,
  Utxo,
  Credential as FlatCredential
}
import xyz.kd5ujc.scarab.ledger.era.{EraCertState, EraTx, EraTxBody, Obligations, PParams, TxLevel}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayPParams, ConwayTx, ConwayTxCert}
import xyz.kd5ujc.scarab.ledger.era.rules.{CERTS, CertsEnv as TypedCertsEnv}
import xyz.kd5ujc.scarab.ledger.rules.{CertEnv, Certs, CertsEnv, Globals, Judgment}
import xyz.kd5ujc.scarab.ledger.state.{
  CompactCoin,
  Conway,
  ConwayCertState,
  ConwayGovState,
  EpochNo,
  NewEpochState,
  UtxoTables
}
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.node.mempool.{LocalSubmission, MempoolEnv, MempoolLedger}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, UtxoCodecs}

/** Conway's certificate rules on the era types (`era.rules.CERTS` and the rules under it) against
  * the flat ones (`rules.Certs`): from the same ticked state, each judges a transaction's
  * certificates to the same defect, the same predicate failures in the same order, or the same
  * certificate state. On every transaction of the recorded Conway refusal corpora, at the recorded
  * protocol version and at 9, 10 and 11; and on certificates crafted against a recorded state for
  * every predicate failure the corpus never records.
  */
class ConwayCertsChecksSuite extends FunSuite:
  import ReferenceRefusalSuite.*

  private type Verdict =
    Either[LedgerDefect, Either[NonEmptyChain[PredicateFailure], ConwayCertState]]

  /** One Conway case as both paths see it: its transaction, and the state the mempool judges it in.
    */
  private final case class Seen(
      label: String,
      globals: Globals,
      network: Int,
      bytes: Bytes,
      flat: Tx,
      typed: ConwayTx,
      flatPp: xyz.kd5ujc.scarab.ledger.ProtocolParams,
      pp: PParams[Conway],
      epoch: BigInt,
      cert: ConwayCertState,
      committee: Option[Committee],
      proposals: Map[GovActionId, GovActionState]
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
      val env = MempoolEnv(
        setup.globals,
        setup.geometry,
        xyz.kd5ujc.scarab.plutus.ScarabEvaluator.default,
        xyz.kd5ujc.scarab.plutus.ScarabEvaluator.defaultDecoder
      )
      c.cases.map { rc =>
        val what = s"${c.path}/${rc.label}"
        val bytes: Bytes = right(s"$what item")(LocalSubmission.unwrap(rc.item))
        val nes = right(s"$what state")(
          codecs.newEpochState[Conway].decodeBytes(c.states(rc.state), HaskellState.limits)
        )
        val stored: Map[TxIn, TxOut] =
          right(s"$what UTxO")(UtxoCodecs.utxo.decodeBytes(c.utxos(rc.utxo)))
        val withUtxo = NewEpochState
          .ledger[Conway]
          .modify(ls =>
            ls.copy[Conway](utxo = ls.utxo.copy(utxo = UtxoTables(Utxo.fromMap(stored))))
          )(nes)
        val ledger = right(s"$what tick")(MempoolLedger.tick(withUtxo, Some(rc.tipSlot), env))
        val gov: ConwayGovState = ledger.ls.utxo.gov
        Seen(
          what,
          env.rules,
          setup.globals.network,
          bytes,
          right(s"$what flat")(Tx.fromEnvelope(bytes, Era.Conway)),
          right(s"$what typed")(ConwayTx.fromEnvelope(bytes)),
          gov.curPParams,
          PParams[Conway](
            right(s"$what typed parameters")(ConwayPParams.fromStored(gov.curPParams))
          ),
          ledger.epoch,
          ledger.ls.cert,
          gov.committee,
          gov.proposals.actions.map(g => g.id -> g).toMap
        )
      }
    }

  /** The same parameters in both forms, edited by `f`. */
  private def withParams(s: Seen, label: String)(
      f: xyz.kd5ujc.scarab.ledger.ProtocolParams => xyz.kd5ujc.scarab.ledger.ProtocolParams
  ): Seen =
    val flatPp = f(s.flatPp)
    s.copy(
      label = s"${s.label}$label",
      flatPp = flatPp,
      pp = PParams[Conway](right("edited parameters")(ConwayPParams.fromStored(flatPp)))
    )

  /** The same parameters in both forms, at major version `major`. */
  private def atVersion(s: Seen, major: Int): Seen =
    withParams(s, s"@v$major")(_.copy(protocolMajor = BigInt(major)))

  private def flatOf(s: Seen, certs: Vector[Certificate]): Verdict =
    Judgment.run(Certs, Judgment.Policy.Accumulate)(
      s.globals,
      CertsEnv(s.flat, CertEnv(s.flat.body.id, s.flatPp, s.epoch, s.committee, s.proposals)),
      s.cert,
      certs
    )

  private def typedOf(s: Seen, certs: Vector[ConwayTxCert]): Verdict =
    Judgment.run(CERTS[Conway], Judgment.Policy.Accumulate)(
      s.globals,
      TypedCertsEnv[Conway](
        s.typed,
        s.pp,
        right("epoch")(EpochNo.fromBigInt(s.epoch)),
        s.committee,
        s.proposals
      ),
      s.cert,
      certs
    )

  /** Both judgments of the transaction's own certificates. */
  private def recorded(s: Seen): (Verdict, Verdict) =
    val tx = EraTx.conway
    val body = EraTxBody.conway
    val tb = tx.body[TxLevel.Top](s.typed)(using tx.bodyOf.top)
    (flatOf(s, s.flat.body.certificates), typedOf(s, body.certs[TxLevel.Top](tb)(using body.top)))

  // ---- what a judgment reached

  /** A predicate failure of CERTS, by its rule and constructor. */
  private enum Reached:
    case Deleg(constructor: Int)
    case Pool(constructor: Int)
    case GovCert(constructor: Int)
    case Withdrawals

  private def reached(v: Verdict): Set[Reached] =
    v.toOption
      .flatMap(_.left.toOption)
      .fold(Set.empty[Reached])(
        _.toChain.toVector
          .collect {
            case PredicateFailure.Certs(CertsFailure.Cert(CertFailure.Deleg(d))) =>
              Reached.Deleg(d.ordinal)
            case PredicateFailure.Certs(CertsFailure.Cert(CertFailure.Pool(p))) =>
              Reached.Pool(p.ordinal)
            case PredicateFailure.Certs(CertsFailure.Cert(CertFailure.GovCert(g))) =>
              Reached.GovCert(g.ordinal)
            case PredicateFailure.Certs(CertsFailure.WithdrawalsNotInRewards(_)) =>
              Reached.Withdrawals
          }
          .toSet
      )

  private val someCred = FlatCredential.Key(Bytes.fromArray(Array.fill(28)(0)))
  private val someMismatch = xyz.kd5ujc.scarab.ledger.Mismatch(BigInt(0), BigInt(0))

  /** Every predicate failure Conway's CERTS and its sub-rules record (`ConwayDelegPredFailure`,
    * `ShelleyPoolPredFailure`, `ConwayGovCertPredFailure`, `ConwayCertsPredFailure`).
    */
  private val every: Map[Reached, String] = Map(
    Reached.Deleg(DelegFailure.IncorrectDeposit(0).ordinal) -> "IncorrectDepositDELEG",
    Reached.Deleg(DelegFailure.StakeKeyRegistered(someCred).ordinal) -> "StakeKeyRegisteredDELEG",
    Reached.Deleg(DelegFailure.StakeKeyNotRegistered(someCred).ordinal) ->
      "StakeKeyNotRegisteredDELEG",
    Reached.Deleg(DelegFailure.StakeKeyHasNonZeroAccountBalance(0).ordinal) ->
      "StakeKeyHasNonZeroAccountBalanceDELEG",
    Reached.Deleg(DelegFailure.DelegateeDRepNotRegistered(someCred).ordinal) ->
      "DelegateeDRepNotRegisteredDELEG",
    Reached.Deleg(DelegFailure.DelegateeStakePoolNotRegistered(Bytes.empty).ordinal) ->
      "DelegateeStakePoolNotRegisteredDELEG",
    Reached.Deleg(DelegFailure.DepositIncorrect(someMismatch).ordinal) -> "DepositIncorrectDELEG",
    Reached.Deleg(DelegFailure.RefundIncorrect(someMismatch).ordinal) -> "RefundIncorrectDELEG",
    Reached.Pool(PoolFailure.StakePoolNotRegisteredOnKey(Bytes.empty).ordinal) ->
      "StakePoolNotRegisteredOnKeyPOOL",
    Reached.Pool(PoolFailure.StakePoolRetirementWrongEpoch(someMismatch, someMismatch).ordinal) ->
      "StakePoolRetirementWrongEpochPOOL",
    Reached.Pool(PoolFailure.StakePoolCostTooLow(someMismatch).ordinal) ->
      "StakePoolCostTooLowPOOL",
    Reached.Pool(
      PoolFailure.WrongNetworkPool(xyz.kd5ujc.scarab.ledger.Mismatch(0, 0), Bytes.empty).ordinal
    ) -> "WrongNetworkPOOL",
    Reached.Pool(PoolFailure.PoolMedataHashTooBig(Bytes.empty, 0).ordinal) ->
      "PoolMedataHashTooBig",
    Reached.Pool(PoolFailure.VRFKeyHashAlreadyRegistered(Bytes.empty, Bytes.empty).ordinal) ->
      "VRFKeyHashAlreadyRegistered",
    Reached.GovCert(GovCertFailure.DRepAlreadyRegistered(someCred).ordinal) ->
      "ConwayDRepAlreadyRegistered",
    Reached.GovCert(GovCertFailure.DRepNotRegistered(someCred).ordinal) ->
      "ConwayDRepNotRegistered",
    Reached.GovCert(GovCertFailure.DRepIncorrectDeposit(someMismatch).ordinal) ->
      "ConwayDRepIncorrectDeposit",
    Reached.GovCert(GovCertFailure.CommitteeHasPreviouslyResigned(someCred).ordinal) ->
      "ConwayCommitteeHasPreviouslyResigned",
    Reached.GovCert(GovCertFailure.DRepIncorrectRefund(someMismatch).ordinal) ->
      "ConwayDRepIncorrectRefund",
    Reached.GovCert(GovCertFailure.CommitteeIsUnknown(someCred).ordinal) ->
      "ConwayCommitteeIsUnknown",
    Reached.Withdrawals -> "WithdrawalsNotInRewardsCERTS"
  )

  // ---- the recorded corpus

  private def versions(s: Seen): Vector[Seen] =
    s +: Vector(9, 10, 11).map(atVersion(s, _))

  test("the corpus has Conway cases, each read by both paths"):
    assert(clue(seen.size) > 0)

  test("CERTS on the era types judges every recorded Conway transaction as the flat rule does"):
    seen.flatMap(versions).foreach { s =>
      val (flat, typed) = recorded(s)
      assertEquals(typed, flat, s.label)
    }

  /** The corpus cases (at their recorded version) that record each failure. */
  private lazy val corpusReach: Map[Reached, Int] =
    seen
      .flatMap(s => reached(recorded(s)._1).toVector)
      .groupMapReduce(identity)(_ => 1)(_ + _)

  test("the corpus cases that reach each predicate failure, and those with certificates"):
    val withCerts = seen.count(_.flat.body.certificates.nonEmpty)
    val refused = seen.count(s => reached(recorded(s)._1).nonEmpty)
    println(
      s"ConwayCertsChecksSuite: ${seen.size} Conway cases, $withCerts with certificates, " +
        s"$refused refused by CERTS"
    )
    every.toVector.sortBy(_._2).foreach { (r, name) =>
      println(s"  $name: ${corpusReach.getOrElse(r, 0)} corpus cases")
    }
    assert(withCerts > 0)

  test("the certificate state's obligations are the deposits the flat state holds"):
    seen.foreach { s =>
      val c = s.cert
      val o = EraCertState.conway.obligationCertState(c)
      val stake = c.dState.accounts.states.valuesIterator.map(_.deposit.toBigInt).sum
      val pool = c.pState.stakePools.valuesIterator.map(_.deposit.toBigInt).sum
      val drep = c.vState.dreps.valuesIterator.map(_.deposit.toBigInt).sum
      assertEquals(
        (o.oblStake.value, o.oblPool.value, o.oblDRep.value, o.oblProposal.value),
        (stake, pool, drep, BigInt(0)),
        s.label
      )
      assertEquals(Obligations.sumObligation(o).value, stake + pool + drep, s.label)
    }

  // ---- crafted certificates, read by both decoders

  private def node(v: Value): Node = Node(v, Bytes.empty)
  private def uint(n: BigInt): Node = node(Value.UInt(n))
  private def bytesN(b: Bytes): Node = node(Value.ByteString(b))
  private def arr(xs: Node*): Node = node(Value.Arr(xs.toVector))
  private val nul: Node = node(Value.Null)

  private def hash(seed: Int, size: Int): Bytes =
    Bytes.fromArray(Array.tabulate(size)(i => ((seed * 31 + i * 7) & 0xff).toByte))

  private def keyCred(seed: Int): Node = arr(uint(0), bytesN(hash(seed, 28)))
  private def flatKey(seed: Int): FlatCredential = FlatCredential.Key(hash(seed, 28))
  private def anchor(seed: Int): Node =
    arr(node(Value.Text(s"https://example.com/$seed")), bytesN(hash(seed, 32)))

  private enum D:
    case Key(seed: Int)
    case Abstain
    case NoConfidence

  private def drep(d: D): Node = d match
    case D.Key(seed)    => arr(uint(0), bytesN(hash(seed, 28)))
    case D.Abstain      => arr(uint(2))
    case D.NoConfidence => arr(uint(3))

  private def rewardAccount(network: Int, seed: Int): Bytes = accountOf(network, flatKey(seed))

  /** A reward account's bytes: its header (`0xe0` for a key, `0xf0` for a script, with the network)
    * and the credential's hash.
    */
  private def accountOf(network: Int, c: FlatCredential): Bytes =
    val header = c match
      case FlatCredential.Key(_)    => 0xe0
      case FlatCredential.Script(_) => 0xf0
    Bytes.fromArray((header | network).toByte +: c.hash.toArray)

  /** A Conway certificate's encoding (`conway.cddl`). */
  private object C:
    def reg(c: Int): Node = arr(uint(0), keyCred(c))
    def unreg(c: Int): Node = arr(uint(1), keyCred(c))
    def delegStake(c: Int, pool: Bytes): Node = arr(uint(2), keyCred(c), bytesN(pool))
    def regDeposit(c: Int, coin: BigInt): Node = arr(uint(7), keyCred(c), uint(coin))
    def unregRefund(c: Int, coin: BigInt): Node = arr(uint(8), keyCred(c), uint(coin))
    def voteDeleg(c: Int, d: D): Node = arr(uint(9), keyCred(c), drep(d))
    def stakeVoteDeleg(c: Int, pool: Bytes, d: D): Node =
      arr(uint(10), keyCred(c), bytesN(pool), drep(d))
    def stakeRegDeleg(c: Int, pool: Bytes, coin: BigInt): Node =
      arr(uint(11), keyCred(c), bytesN(pool), uint(coin))
    def voteRegDeleg(c: Int, d: D, coin: BigInt): Node =
      arr(uint(12), keyCred(c), drep(d), uint(coin))
    def stakeVoteRegDeleg(c: Int, pool: Bytes, d: D, coin: BigInt): Node =
      arr(uint(13), keyCred(c), bytesN(pool), drep(d), uint(coin))
    def authHot(cold: Int, hot: Int): Node = arr(uint(14), keyCred(cold), keyCred(hot))
    def resign(cold: Int): Node = arr(uint(15), keyCred(cold), anchor(cold))
    def regDRep(d: Int, coin: BigInt): Node = arr(uint(16), keyCred(d), uint(coin), anchor(d))
    def unregDRep(d: Int, coin: BigInt): Node = arr(uint(17), keyCred(d), uint(coin))
    def updateDRep(d: Int): Node = arr(uint(18), keyCred(d), nul)
    def retire(pool: Bytes, epoch: BigInt): Node = arr(uint(4), bytesN(pool), uint(epoch))
    def regPool(
        operator: Bytes,
        vrf: Bytes,
        cost: BigInt,
        account: Bytes,
        metadataHash: Option[Bytes] = None
    ): Node =
      arr(
        uint(3),
        bytesN(operator),
        bytesN(vrf),
        uint(1000),
        uint(cost),
        node(Value.Tag(30, arr(uint(1), uint(10)))),
        bytesN(account),
        arr(),
        arr(),
        metadataHash.fold(nul)(h => arr(node(Value.Text("https://example.com/p")), bytesN(h)))
      )

  /** The encoding read again, as a block's certificate is: its own bytes in each node. */
  private def read(n: Node): Node =
    right("read")(Cbor.decode(right("encode")(Cbor.encode(n.value, Cbor.Limits.Unbounded))))

  private def both(certs: Vector[Node]): (Vector[Certificate], Vector[ConwayTxCert]) =
    val nodes = certs.map(read)
    (
      nodes.map(n => right("flat certificate")(Certificate.decode(n, Era.Conway))),
      nodes.map(n => right("typed certificate")(ConwayTxCert.decode(n)))
    )

  /** A recorded state to craft against: one with pools, accounts and DReps registered. */
  private lazy val base: Seen =
    seen
      .find(s =>
        s.cert.pState.stakePools.size >= 2 && s.cert.dState.accounts.states.nonEmpty &&
          s.cert.vState.dreps.nonEmpty
      )
      .orElse(seen.find(s => s.cert.pState.stakePools.nonEmpty))
      .getOrElse(fail("no recorded Conway state with a registered pool"))

  /** Both judgments of `certs` from `s`, which must agree; what they reached is recorded. */
  private def compared(s: Seen, name: String, certs: Vector[Node]): Verdict =
    val (flatCerts, typedCerts) = both(certs)
    val flat = flatOf(s, flatCerts)
    assertEquals(typedOf(s, typedCerts), flat, s"${s.label}: $name")
    flat

  /** [[compared]] for certificates the flat rule accepts, so the two states are compared. */
  private def accepted(s: Seen, name: String, certs: Vector[Node]): Verdict =
    val verdict = compared(s, name, certs)
    assert(verdict.exists(_.isRight), s"${s.label}: $name is refused: $verdict")
    verdict

  private def keyDeposit(s: Seen): BigInt = s.flatPp.keyDeposit
  private def drepDeposit(s: Seen): BigInt = s.flatPp.drepDeposit

  /** The crafted scenarios, at protocol versions 9, 10 and 11. */
  private def scenarios(at: Seen): Vector[Verdict] =
    // a minimum pool cost a registration can fall below
    val s = withParams(at, "")(pp => pp.copy(minPoolCost = pp.minPoolCost.max(BigInt(1000))))
    val pools = s.cert.pState.stakePools.keys.toVector.map(_.bytes)
    val pool = pools.head
    val otherPool = pools.lift(1).getOrElse(pool)
    val account =
      s.cert.dState.accounts.states.keys.headOption.map(xyz.kd5ujc.scarab.ledger.rules.Convert.back)
    val unknownPool = hash(900, 28)
    val kd = keyDeposit(s)
    val dd = drepDeposit(s)
    // DELEG
    val v1 = compared(
      s,
      "registrations",
      Vector(C.regDeposit(1, kd + 1), C.reg(1), C.regDeposit(2, kd), C.reg(2))
    )
    val v2 = compared(
      s,
      "unregistrations",
      Vector(
        C.unreg(3),
        C.unregRefund(3, kd),
        C.regDeposit(4, kd),
        C.unregRefund(4, kd + 5),
        C.regDeposit(5, kd),
        C.unregRefund(5, kd),
        C.unreg(5)
      )
    )
    val v3 = compared(
      s,
      "delegations",
      Vector(
        C.delegStake(6, pool),
        C.regDeposit(6, kd),
        C.delegStake(6, unknownPool),
        C.voteDeleg(6, D.Key(700)),
        C.voteDeleg(6, D.Abstain),
        C.stakeVoteDeleg(6, pool, D.NoConfidence),
        C.delegStake(6, otherPool),
        C.delegStake(6, otherPool)
      )
    )
    val v4 = compared(
      s,
      "registrations with delegation",
      Vector(
        C.stakeRegDeleg(7, pool, kd),
        C.stakeRegDeleg(8, unknownPool, kd + 1),
        C.voteRegDeleg(9, D.Key(701), kd),
        C.stakeVoteRegDeleg(10, pool, D.Abstain, kd),
        C.stakeVoteRegDeleg(7, pool, D.Abstain, kd),
        C.unregRefund(7, kd),
        C.unregRefund(10, kd)
      )
    )
    // DReps, their delegators, and a delegation moved between them
    val v5 = compared(
      s,
      "DReps",
      Vector(
        C.regDRep(20, dd + 1),
        C.regDRep(20, dd),
        C.updateDRep(21),
        C.updateDRep(20),
        C.regDRep(22, dd),
        C.voteRegDeleg(23, D.Key(20), kd),
        C.voteDeleg(23, D.Key(22)),
        C.voteDeleg(23, D.Key(22)),
        C.stakeVoteRegDeleg(24, pool, D.Key(22), kd),
        C.unregDRep(22, dd + 1),
        C.unregDRep(22, dd),
        C.unregDRep(20, dd),
        C.voteDeleg(24, D.Key(20)),
        C.unreg(23)
      )
    )
    val vAccount = account.toVector.map { a =>
      val hashOf = a.hash
      val existing = a match
        case FlatCredential.Key(_)    => arr(uint(0), bytesN(hashOf))
        case FlatCredential.Script(_) => arr(uint(1), bytesN(hashOf))
      compared(
        s,
        "a registered account",
        Vector(
          arr(uint(9), existing, drep(D.Key(30))),
          arr(uint(2), existing, bytesN(otherPool)),
          arr(uint(8), existing, uint(kd + 7))
        )
      )
    }
    // POOL
    val network = s.network
    val minCost = s.flatPp.minPoolCost
    val v6 = compared(
      s,
      "pool registrations",
      Vector(
        C.regPool(hash(40, 28), hash(40, 32), minCost, rewardAccount(1 - network, 40)),
        C.regPool(
          hash(41, 28),
          hash(41, 32),
          minCost,
          rewardAccount(network, 41),
          Some(hash(41, 33))
        ),
        C.regPool(hash(42, 28), hash(42, 32), minCost - 1, rewardAccount(network, 42)),
        C.regPool(hash(43, 28), hash(40, 32), minCost, rewardAccount(network, 43)),
        C.regPool(hash(40, 28), hash(44, 32), minCost, rewardAccount(network, 40)),
        C.regPool(hash(40, 28), hash(45, 32), minCost, rewardAccount(network, 40)),
        C.regPool(hash(40, 28), hash(45, 32), minCost, rewardAccount(network, 40)),
        C.regPool(hash(40, 28), hash(42, 32), minCost, rewardAccount(network, 40))
      )
    )
    val existingVrf = s.cert.pState.stakePools.values.head.vrf.bytes
    val v7 = compared(
      s,
      "pool retirements",
      Vector(
        C.retire(unknownPool, s.epoch + 1),
        C.retire(pool, s.epoch),
        C.retire(pool, s.epoch + 1000000),
        C.retire(pool, s.epoch + 1),
        C.regPool(pool, existingVrf, minCost, rewardAccount(network, 46)),
        C.regPool(hash(47, 28), existingVrf, minCost, rewardAccount(network, 47))
      )
    )
    // the committee, current and proposed
    val cold = 50
    val proposed = 51
    val committee = Committee(Map(flatKey(cold) -> BigInt(1000)), Rational(1, 2))
    val proposal = GovActionState(
      GovActionId(hash(52, 32), 0),
      Map.empty,
      Map.empty,
      Map.empty,
      Proposal(
        0,
        RewardAccount(network, flatKey(52)),
        GovAction
          .UpdateCommittee(None, Set.empty, Map(flatKey(proposed) -> BigInt(1000)), Rational(1, 2))
      ),
      0,
      1000
    )
    val withCommittee = s.copy(
      committee = Some(committee),
      proposals = s.proposals.updated(proposal.id, proposal)
    )
    val v8 = compared(
      withCommittee,
      "committee",
      Vector(
        C.authHot(53, 54),
        C.authHot(cold, 55),
        C.authHot(proposed, 56),
        C.resign(cold),
        C.authHot(cold, 57),
        C.resign(proposed),
        C.resign(proposed)
      )
    )
    val v9 = compared(s, "an unknown committee", Vector(C.authHot(cold, 55), C.resign(proposed)))
    // sequences every version accepts, so the states they end in are compared too: a refusal
    // carries its failures and not its state
    val existing = account.map(a =>
      a match
        case FlatCredential.Key(h)    => arr(uint(0), bytesN(h))
        case FlatCredential.Script(h) => arr(uint(1), bytesN(h))
    )
    val moves = accepted(
      s,
      "delegations moved",
      Vector(
        C.regDRep(20, dd),
        C.regDRep(22, dd),
        C.voteRegDeleg(23, D.Key(20), kd),
        C.voteDeleg(23, D.Key(22)),
        C.stakeVoteRegDeleg(24, pool, D.Key(22), kd),
        C.voteDeleg(24, D.Key(20)),
        C.voteDeleg(24, D.Abstain),
        C.voteDeleg(24, D.Key(22)),
        C.delegStake(23, otherPool),
        C.stakeVoteDeleg(23, pool, D.Key(20)),
        C.regDeposit(25, kd),
        C.stakeVoteDeleg(25, otherPool, D.Key(22)),
        C.unregDRep(22, dd),
        C.updateDRep(20),
        C.unregRefund(23, kd),
        C.unreg(24)
      ) ++ existing.toVector.flatMap(e =>
        Vector(
          arr(uint(9), e, drep(D.Key(20))),
          arr(uint(2), e, bytesN(otherPool)),
          arr(uint(10), e, bytesN(pool), drep(D.NoConfidence)),
          arr(uint(9), e, drep(D.Key(20)))
        )
      )
    )
    val pooled = accepted(
      s,
      "pools registered, re-registered and retired",
      Vector(
        C.regPool(hash(70, 28), hash(70, 32), minCost, rewardAccount(network, 70)),
        C.regPool(hash(70, 28), hash(71, 32), minCost, rewardAccount(network, 70)),
        C.regPool(hash(70, 28), hash(72, 32), minCost, rewardAccount(network, 70)),
        C.retire(hash(70, 28), s.epoch + 1),
        C.regPool(hash(70, 28), hash(73, 32), minCost, rewardAccount(network, 70)),
        C.stakeRegDeleg(26, hash(70, 28), kd),
        C.retire(pool, s.epoch + 1),
        C.regPool(pool, existingVrf, minCost, rewardAccount(network, 74))
      )
    )
    val members = accepted(
      withCommittee,
      "committee members",
      Vector(
        C.authHot(cold, 55),
        C.authHot(cold, 56),
        C.authHot(proposed, 57),
        C.resign(proposed),
        C.resign(cold)
      )
    )
    Vector(v1, v2, v3, v4, v5, v6, v7, v8, v9, moves, pooled, members) ++ vAccount

  /** The crafted scenarios' judgments, each compared as it is made. */
  private lazy val craftedVerdicts: Vector[Verdict] =
    Vector(9, 10, 11).flatMap(v => scenarios(atVersion(base, v))) ++ baseCases ++ funded

  test("crafted certificates are judged by both paths alike at versions 9, 10 and 11"):
    assert(craftedVerdicts.nonEmpty)

  // ---- the base case of CERTS: withdrawals and the DReps' expiries

  /** `s` with its transaction's body edited by `f`, read again by both decoders. */
  private def edited(s: Seen)(f: Vector[(Node, Node)] => Vector[(Node, Node)]): Seen =
    val root = right("envelope")(Cbor.decode(s.bytes))
    val again = root.value match
      case Value.Arr(parts) =>
        parts.head.value match
          case Value.Map(fields) =>
            right("re-encode")(
              Cbor.encode(
                Value.Arr(node(Value.Map(f(fields))) +: parts.tail),
                Cbor.Limits.Unbounded
              )
            )
          case other => fail(s"a body that is not a map: $other")
      case other => fail(s"an envelope that is not an array: $other")
    s.copy(
      bytes = again,
      flat = right("edited flat")(Tx.fromEnvelope(again, Era.Conway)),
      typed = right("edited typed")(ConwayTx.fromEnvelope(again))
    )

  private def isKey(k: Int)(n: Node): Boolean = n.value == Value.UInt(k)

  /** The base case at versions 9, 10 and 11: withdrawals of an unregistered account, of another
    * network and of less than the balance; a draining one; and the DReps' expiries moved by the
    * corpus's transactions with proposals or votes, with dormant epochs counted and without.
    */
  private def baseCases: Vector[Verdict] =
    Vector(9, 10, 11).flatMap { v =>
      val s = atVersion(base, v)
      val (registered, held) = s.cert.dState.accounts.states.head
      val back = xyz.kd5ujc.scarab.ledger.rules.Convert.back(registered)
      val registeredAccount = accountOf(s.network, back)
      val balance = held.balance.toBigInt
      def withWithdrawals(entries: Vector[(Node, Node)]): Seen =
        edited(s)(fields =>
          fields.filterNot(f => isKey(5)(f._1)) :+ (uint(5) -> node(Value.Map(entries)))
        )
      val withdrawing = withWithdrawals(
        Vector(
          bytesN(registeredAccount) -> uint(balance + 1),
          bytesN(rewardAccount(s.network, 60)) -> uint(5),
          bytesN(accountOf(1 - s.network, back)) -> uint(balance)
        )
      )
      val draining = withWithdrawals(Vector(bytesN(registeredAccount) -> uint(balance)))
      val dormant = s.cert.copy(vState =
        s.cert.vState.copy(numDormantEpochs = right("dormant")(EpochNo.fromBigInt(3)))
      )
      val voting = seen
        .filter(c => c.flat.body.proposals.nonEmpty || c.flat.body.votes.nonEmpty)
        .map(atVersion(_, v))
      // a proposal and a vote of each registered DRep, crafted into the transaction
      val voters = s.cert.vState.dreps.keys.toVector.collect {
        case xyz.kd5ujc.scarab.ledger.state.Credential.KeyHashObj(h) =>
          arr(uint(2), bytesN(h.bytes))
        case xyz.kd5ujc.scarab.ledger.state.Credential.ScriptHashObj(h) =>
          arr(uint(3), bytesN(h.bytes))
      }
      val proposing = edited(s)(fields =>
        fields.filterNot(f => isKey(19)(f._1) || isKey(20)(f._1)) ++ Vector(
          uint(20) -> arr(
            arr(
              uint(s.flatPp.govActionDeposit),
              bytesN(rewardAccount(s.network, 62)),
              arr(uint(6)),
              anchor(62)
            )
          )
        ) ++ Option
          .when(voters.nonEmpty)(
            uint(19) -> node(
              Value.Map(
                voters.map(v =>
                  v -> node(
                    Value.Map(Vector(arr(bytesN(hash(63, 32)), uint(0)) -> arr(uint(1), nul)))
                  )
                )
              )
            )
          )
          .toVector
      )
      Vector(
        accepted(
          proposing.copy(cert = dormant),
          "a proposal and votes, dormant epochs",
          Vector.empty
        ),
        accepted(proposing, "a proposal and votes", Vector.empty),
        compared(withdrawing, "withdrawals", Vector.empty),
        accepted(draining, "a draining withdrawal", Vector(C.regDeposit(61, keyDeposit(s))))
      ) ++ voting.flatMap(c =>
        Vector(
          compared(c.copy(cert = dormant), "proposals and votes, dormant epochs", Vector.empty),
          compared(c, "proposals and votes", Vector.empty)
        )
      )
    }

  /** An account holding a balance, unregistered, at versions 10 and 11. */
  private def funded: Vector[Verdict] =
    Vector(10, 11).map { v =>
      val s = atVersion(base, v)
      val (registered, held) = s.cert.dState.accounts.states.head
      val accounts = s.cert.dState.accounts
      val fundedState = s.cert.copy(dState =
        s.cert.dState.copy(accounts =
          accounts.copy(states =
            accounts.states
              .updated(registered, held.copy(balance = CompactCoin.fromUnsigned(12345L)))
          )
        )
      )
      val existing = xyz.kd5ujc.scarab.ledger.rules.Convert.back(registered) match
        case FlatCredential.Key(h)    => arr(uint(0), bytesN(h))
        case FlatCredential.Script(h) => arr(uint(1), bytesN(h))
      compared(s.copy(cert = fundedState), "a funded account", Vector(arr(uint(1), existing)))
    }

  test("the base case and a funded account are judged by both paths alike"):
    assert(baseCases.nonEmpty && funded.nonEmpty)

  test("every predicate failure of CERTS is reached by the corpus or a crafted case"):
    val all = corpusReach.keySet ++ craftedVerdicts.flatMap(reached)
    val missing = every.collect { case (r, name) if !all.contains(r) => name }.toVector.sorted
    assertEquals(missing, Vector.empty[String])
