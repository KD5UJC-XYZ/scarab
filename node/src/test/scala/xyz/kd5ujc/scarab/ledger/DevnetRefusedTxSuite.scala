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
import cats.data.NonEmptyList
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayTestKit
import xyz.kd5ujc.scarab.ledger.era.rules.{
  ConwayCertPredFailure,
  ConwayCertsPredFailure,
  ConwayGovPredFailure,
  ConwayLedgerPredFailure,
  ConwayUtxoPredFailure,
  ConwayUtxowPredFailure,
  LedgerPredFailure,
  ShelleyPoolPredFailure
}
import xyz.kd5ujc.scarab.ledger.rules.{NativeRules, RuleT}
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
    val reserves = Coin(Supply - utxo.valuesIterator.map(_.value.coin).sum)
    ConwayNewEpochState.reserves.replace(reserves)(Build.funded(base, utxo))

  /** The empty state of the suites at protocol version `major`, with no pool deposit and no minimum
    * output value, as its current and previous parameters.
    */
  private def state(major: Int): NewEpochState[Conway] =
    val params = TestLedger.params(major).copy(poolDeposit = 0, minUTxOValue = 0)
    ConwayNewEpochState.gov.modify(_.copy(curPParams = params, prevPParams = params))(
      NativeRules.stateIn[Conway](major, Vector.empty)
    )

  private type Failure = LedgerPredFailure[Conway]

  /** Conway's LEDGER on the era types on `tx`, read from its bytes, to its first failure
    * ([[ConwayTestKit.ledger]]): the state models only the facts that decide the rule, so a later
    * check may fail on what it leaves out (a committee voter it does not hold).
    */
  private def run(
      s: NewEpochState[Conway],
      tx: Tx
  ): Either[Vector[Failure], NewEpochState[Conway]] =
    ConwayTestKit.ledger(s, ConwayTestKit.built(tx), env, RuleT.Policy.FirstFailure)

  /** The failure of a refusal; any other verdict fails the test. */
  private def refusal(what: String)(r: Either[Vector[Failure], ?]): Failure = r match
    case Left(Vector(f)) => f
    case other           => fail(s"$what: not a refusal for one failure: $other")

  /** The rules this fixture's refusals name, each by the failure's constructor. */
  private enum Named:
    case VRFKeyHashAlreadyRegistered, BabbageNonDisjointRefInputs, UnelectedCommitteeVoters

  private def named(f: Failure): Option[Named] = f match
    case ConwayLedgerPredFailure.ConwayCertsFailure(
          ConwayCertsPredFailure.CertFailure(
            ConwayCertPredFailure.PoolFailure(
              ShelleyPoolPredFailure.VRFKeyHashAlreadyRegistered(_, _)
            )
          )
        ) =>
      Some(Named.VRFKeyHashAlreadyRegistered)
    case ConwayLedgerPredFailure.ConwayUtxowFailure(
          ConwayUtxowPredFailure.UtxoFailure(ConwayUtxoPredFailure.BabbageNonDisjointRefInputs(_))
        ) =>
      Some(Named.BabbageNonDisjointRefInputs)
    case ConwayLedgerPredFailure.ConwayGovFailure(
          ConwayGovPredFailure.UnelectedCommitteeVoters(_)
        ) =>
      Some(Named.UnelectedCommitteeVoters)
    case _ => None

  /** The failure as `cardano-cli` prints it (Haskell `Show`), for the constructors of this fixture.
    */
  private def shown(f: Failure): String =
    def key(h: Bytes) = s"""KeyHash {unKeyHash = "${h.hex}"}"""
    f match
      case ConwayLedgerPredFailure.ConwayCertsFailure(
            ConwayCertsPredFailure.CertFailure(
              ConwayCertPredFailure.PoolFailure(
                ShelleyPoolPredFailure.VRFKeyHashAlreadyRegistered(pool, vrf)
              )
            )
          ) =>
        s"""ConwayCertsFailure (CertFailure (PoolFailure (VRFKeyHashAlreadyRegistered (${key(
            pool.bytes
          )}) (VRFVerKeyHash {unVRFVerKeyHash = "${vrf.bytes.hex}"}))))"""
      case ConwayLedgerPredFailure.ConwayUtxowFailure(
            ConwayUtxowPredFailure.UtxoFailure(
              ConwayUtxoPredFailure.BabbageNonDisjointRefInputs(NonEmptyList(in, Nil))
            )
          ) =>
        s"""ConwayUtxowFailure (UtxoFailure (BabbageNonDisjointRefInputs (TxIn (TxId {unTxId = SafeHash "${in.id.hex}"}) (TxIx {unTxIx = ${in.index}}) :| [])))"""
      case ConwayLedgerPredFailure.ConwayGovFailure(
            ConwayGovPredFailure.UnelectedCommitteeVoters(NonEmptyList(Credential.Key(h), Nil))
          ) =>
        s"""ConwayGovFailure (UnelectedCommitteeVoters (KeyHashObj (${key(h)}) :| []))"""
      case other => s"unrendered $other"

  /** `result` is a refusal for the rule `rule` alone, the one the reference printed in `error`,
    * payload included.
    */
  private def refusedAs(what: String, rule: Named, error: String)(
      result: Either[Vector[Failure], ?]
  ): Unit =
    val f = refusal(what)(result)
    assertEquals(named(f), Some(rule), what)
    assert(error.contains(shown(f)), s"$what: ${shown(f)} not in $error")

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
          result.fold(f => fail(s"${step.str("label")}: refused $f"), identity)
        case _ =>
          refusedAs(step.str("label"), Named.VRFKeyHashAlreadyRegistered, step.str("error"))(result)
          s
    }
  }

  test(
    "unelected committee voters: the votes the reference refused fail with the GOV rule at protocol version 11"
  ) {
    scenario("unelected").foreach { e =>
      val (_, tx) = e.tx
      val result = run(funded(state(11), tx), tx)
      refusedAs(e.str("label"), Named.UnelectedCommitteeVoters, e.str("error"))(result)
    }
  }

  test(
    "reference inputs that are inputs: refused up to protocol version 10, fine from 11 without scripts"
  ) {
    scenario("refoverlap").foreach { e =>
      val (_, tx) = e.tx
      val result = run(funded(state(10), tx), tx)
      refusedAs(e.str("label"), Named.BabbageNonDisjointRefInputs, e.str("error"))(result)
      assert(run(funded(state(11), tx), tx).isRight, s"${e.str("label")}: refused at 11")
    }
  }
