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
import xyz.kd5ujc.scarab.ledger.rules.NativeRules
import xyz.kd5ujc.scarab.ledger.state.{Build, Coin, Conway, ConwayNewEpochState, NewEpochState}

import TestLedger.*

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

  /** `base` with the inputs of `tx` added: the first holds whatever makes the transaction balance;
    * the reserves are what the outputs leave of the supply.
    */
  private def funded(base: NewEpochState[Conway], tx: Tx): NewEpochState[Conway] =
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
    val held = base.es.ls.utxo.utxo.utxo.toMap
    val utxo = held ++ values.filterNot(v => held.contains(v._1))
    val reserves = Coin
      .fromBigInt(Supply - utxo.valuesIterator.map(_.value.coin).sum)
      .fold(e => fail(s"$e"), identity)
    ConwayNewEpochState.reserves.replace(reserves)(Build.funded(base, utxo))

  /** The empty state of the suites at protocol version `major`, with no pool deposit and no minimum
    * output value, as its current and previous parameters.
    */
  private def state(major: Int): NewEpochState[Conway] =
    val params = TestLedger.params(major).copy(poolDeposit = 0, minUTxOValue = 0)
    ConwayNewEpochState.gov.modify(_.copy(curPParams = params, prevPParams = params))(
      NativeRules.stateIn[Conway](major, Vector.empty)
    )

  /** The LEDGER rules of Conway on `tx`. */
  private def run(s: NewEpochState[Conway], tx: Tx): Either[LedgerFailure, NewEpochState[Conway]] =
    NativeRules.txIn[Conway](s, tx, env).map(_.state)

  private def rule(r: Either[LedgerFailure, ?]): String = r match
    case Left(LedgerFailure.Rejected(_, f)) => f.ruleName
    case Left(other)                        => other.message
    case Right(_)                           => "accepted"

  /** The failure as `cardano-cli` prints it (Haskell `Show`), for the constructors of this fixture.
    */
  private def shown(r: Either[LedgerFailure, ?]): String = r match
    case Left(LedgerFailure.Rejected(_, f)) =>
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
    // the pools registered, each VRF key hash once in the registry (the reference populates it from
    // the pools at protocol version 11, `populateVRFKeyHashes`)
    val pools = context.json.hcursor
      .get[Map[String, String]]("pools")
      .fold(e => fail(e.message), identity)
      .map { case (op, vrf) =>
        val operator = Bytes.fromHex(op).fold(e => fail(e.message), identity)
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
        )
      }
    val start = pools.foldLeft(state(context.int("protocolMajor")))(Build.pool(_, _, 0))
    val steps = scenario("vrf").filter(_.opt("label").isDefined)
    assert(steps.size >= 10, steps.size)
    steps.foldLeft(start) { (s, step) =>
      val (_, tx) = step.tx
      val result = run(funded(s, tx), tx)
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
      val result = run(funded(state(11), tx), tx)
      assertEquals(rule(result), "UnelectedCommitteeVoters", e.str("label"))
      assert(e.str("error").contains(shown(result)), s"${shown(result)} not in ${e.str("error")}")
    }
  }

  test(
    "reference inputs that are inputs: refused up to protocol version 10, fine from 11 without scripts"
  ) {
    scenario("refoverlap").foreach { e =>
      val (_, tx) = e.tx
      val result = run(funded(state(10), tx), tx)
      assertEquals(rule(result), "BabbageNonDisjointRefInputs", e.str("label"))
      assert(e.str("error").contains(shown(result)), s"${shown(result)} not in ${e.str("error")}")
      assertEquals(rule(run(funded(state(11), tx), tx)), "accepted", e.str("label"))
    }
  }
