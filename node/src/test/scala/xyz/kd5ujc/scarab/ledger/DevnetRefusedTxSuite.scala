// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Rational
import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.node.mempool.MempoolState
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.legacy.{FlatTestLedger, LedgerState}

import TestLedger.*
import FlatTestLedger.state

/** Transactions that the Haskell reference node refused on the devnet
  * (`fixtures/devnet/refused-txs.jsonl`, written by `scripts/devnet/govextra.py`), replayed through
  * our rules: each must fail with the rule named like the reference's predicate failure, and the
  * ones the reference accepted must apply. The ledger state is rebuilt from the transactions
  * themselves (inputs get the value that balances the transaction), so only the facts that decide
  * the rule are modelled: the pools holding VRF key hashes, the absence of a committee, the
  * protocol version.
  */
class DevnetRefusedTxSuite extends FunSuite:
  private final case class Entry(json: Json):
    def str(k: String): String = json.hcursor.get[String](k).fold(e => fail(e.message), identity)
    def opt(k: String): Option[String] = json.hcursor.get[String](k).toOption
    def int(k: String): Int = json.hcursor.get[Int](k).fold(e => fail(e.message), identity)
    def tx: (Bytes, Tx) =
      val raw = Bytes.fromHex(str("tx")).fold(e => fail(e.message), identity)
      MempoolState.parse(raw).fold(r => fail(r.message), identity)

  private val entries: Vector[Entry] =
    Files
      .readAllLines(Path.of("fixtures/devnet/refused-txs.jsonl"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => Entry(parse(l).fold(e => fail(e.message), identity)))

  private def scenario(name: String) = entries.filter(_.str("scenario") == name)

  /** `base` with the inputs of `tx` added: the first holds whatever makes the transaction balance.
    */
  private def funded(base: LedgerState, tx: Tx): LedgerState =
    val body = tx.body
    val owner = xyz.kd5ujc.scarab.ledger.Verdicts.Direct.keyHash(tx.witnesses.vkeys.head._1)
    val address =
      Address.decode(Bytes(0x60.toByte +: owner.value)).fold(e => fail(e.message), identity)
    val needed = body.outputs.map(_.value.coin).sum + body.fee
    val spare = BigInt(3000000)
    val inputs = body.inputs.distinct
    val values = inputs.zipWithIndex.map { case (in, i) =>
      in -> out(address, if i == 0 then needed - spare * (inputs.size - 1) else spare)
    }
    val utxo = base.utxo ++ values.filterNot(v => base.utxo.contains(v._1))
    base.copy(utxo = utxo, reserves = Supply - utxo.valuesIterator.map(_.value.coin).sum)

  private def withParams(s: LedgerState, major: Int): LedgerState =
    s.copy(params = s.params.copy(protocolMajor = major, poolDeposit = 0, minUTxOValue = 0))

  private def rule(r: Either[LedgerFailure, LedgerState]): String = r match
    case Left(LedgerFailure.Rejected.Single(_, f)) => f.ruleName
    case Left(other)                               => other.message
    case Right(_)                                  => "accepted"

  /** The failure as `cardano-cli` prints it (Haskell `Show`), for the constructors of this fixture.
    */
  private def shown(r: Either[LedgerFailure, LedgerState]): String = r match
    case Left(LedgerFailure.Rejected.Single(_, f)) =>
      def key(h: Bytes) = s"""KeyHash {unKeyHash = "${h.hex}"}"""
      f match
        case PredicateFailure.Certs(
              CertsFailure.Cert(
                CertFailure.Pool(PoolFailure.VRFKeyHashAlreadyRegistered(pool, vrf))
              )
            ) =>
          s"""ConwayCertsFailure (CertFailure (PoolFailure (VRFKeyHashAlreadyRegistered (${key(
              pool
            )}) (VRFVerKeyHash {unVRFVerKeyHash = "${vrf.hex}"}))))"""
        case PredicateFailure.Utxow(
              UtxowFailure.Utxo(UtxoFailure.BabbageNonDisjointRefInputs(Vector(in)))
            ) =>
          s"""ConwayUtxowFailure (UtxoFailure (BabbageNonDisjointRefInputs (TxIn (TxId {unTxId = SafeHash "${in.id.hex}"}) (TxIx {unTxIx = ${in.index}}) :| [])))"""
        case PredicateFailure.Gov(GovFailure.UnelectedCommitteeVoters(Vector(Credential.Key(h)))) =>
          s"""ConwayGovFailure (UnelectedCommitteeVoters (KeyHashObj (${key(h)}) :| []))"""
        case other => s"unrendered $other"
    case other => other.toString

  test("the fixture has refusals of every kind and the accepted neighbours of the VRF sequence") {
    assertEquals(
      scenario("vrf").count(_.opt("outcome").contains("refused")),
      5,
      "five registrations refused for a taken VRF key hash"
    )
    assert(scenario("vrf").exists(_.opt("outcome").contains("accepted")))
    assert(scenario("unelected").nonEmpty && scenario("refoverlap").nonEmpty)
  }

  test("VRF key hashes: the reference's sequence of accepted and refused registrations replays") {
    val context = scenario("vrf").find(_.opt("context").isDefined).get
    val pools = context.json.hcursor
      .get[Map[String, String]]("pools")
      .fold(e => fail(e.message), identity)
      .map { case (op, vrf) =>
        val operator = Bytes.fromHex(op).fold(e => fail(e.message), identity)
        operator -> PoolState(
          PoolParams(
            operator,
            Bytes.fromHex(vrf).fold(e => fail(e.message), identity),
            0,
            0,
            Rational.zero,
            RewardAccount(0, Credential.Key(operator)),
            Set.empty,
            Bytes.empty,
            None
          ),
          0
        )
      }
    val start =
      withParams(state(context.int("protocolMajor"), Vector.empty), context.int("protocolMajor"))
        .copy(pools = pools)
    val steps = scenario("vrf").filter(_.opt("label").isDefined)
    assert(steps.size >= 10, steps.size)
    steps.foldLeft(start) { (s, step) =>
      val (_, tx) = step.tx
      val result = RuleSet.tx(funded(s, tx), tx, env)
      step.str("outcome") match
        case "accepted" =>
          result.fold(f => fail(s"${step.str("label")}: ${f.message}"), identity)
        case _ =>
          assertEquals(rule(result), "VRFKeyHashAlreadyRegistered", step.str("label"))
          // the failure is the one the reference printed, payload included
          assert(
            step.str("error").contains(shown(result)),
            s"${step.str("label")}: ${shown(result)} not in ${step.str("error")}"
          )
          s
    }
  }

  test(
    "unelected committee voters: the votes the reference refused fail with the GOV rule at protocol version 11"
  ) {
    scenario("unelected").foreach { e =>
      val (_, tx) = e.tx
      val s11 = funded(withParams(state(11, Vector.empty), 11), tx)
      val result = RuleSet.tx(s11, tx, env)
      assertEquals(rule(result), "UnelectedCommitteeVoters", e.str("label"))
      assert(e.str("error").contains(shown(result)), s"${shown(result)} not in ${e.str("error")}")
    }
  }

  test(
    "reference inputs that are inputs: refused up to protocol version 10, fine from 11 without scripts"
  ) {
    scenario("refoverlap").foreach { e =>
      val (_, tx) = e.tx
      val s10 = funded(withParams(state(10, Vector.empty), 10), tx)
      val result = RuleSet.tx(s10, tx, env)
      assertEquals(rule(result), "BabbageNonDisjointRefInputs", e.str("label"))
      assert(e.str("error").contains(shown(result)), s"${shown(result)} not in ${e.str("error")}")
      val s11 = funded(withParams(state(11, Vector.empty), 11), tx)
      assertEquals(rule(RuleSet.tx(s11, tx, env)), "accepted", e.str("label"))
    }
  }
