// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import io.circe.parser.parse
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.state.{Build, Conway}
import xyz.kd5ujc.scarab.plutus.*

/** The backend the node is given is the one its paths evaluate and admit scripts with
  * (`--plutus-vm`, ADR 0027): a probe backend that has no verdict on anything, and counts what it
  * is asked, put where the node puts its backend. A path that kept another evaluator would admit
  * the transaction, which the Scalus backend and the own evaluator both pass, and leave the probe
  * unasked.
  *
  * The paths: the mempool's admission ([[MempoolEnv]], built by `Main` from the run's one
  * [[PlutusBackend]]) and the rules of a block
  * ([[xyz.kd5ujc.scarab.ledger.rules.EraBlock.Context]], which `MirrorRules` builds from the same
  * backend), serial and prepared for parallel evaluation. The forger applies held transactions
  * again without their static checks, so no script reaches its backend there
  * (`MempoolInterventionSuite`); its backend is a required argument like the others'.
  */
class PlutusBackendProbeSuite extends FunSuite:
  /** @param admits
    *   whether the decoder admits every script, so that the evaluator is reached; otherwise it has
    *   no verdict either
    */
  private final class Probe(admits: Boolean = false):
    val evaluated = AtomicInteger(0)
    val decoded = AtomicInteger(0)
    private val why = BackendFailure.NoResult
    val backend: PlutusBackend = PlutusBackend(
      PlutusVm.Scarab,
      _ =>
        evaluated.incrementAndGet()
        EvalResult.Failure(EvalFailure.Unsupported(why), ExUnits.zero, Vector.empty)
      ,
      (_, _, _) =>
        decoded.incrementAndGet()
        if admits then Right(()) else Left(ScriptDecodeFailure.Unavailable(why))
    )

  private def hex(j: io.circe.Json, field: String): Bytes =
    j.hcursor
      .get[String](field)
      .flatMap(h => Bytes.fromHex(h).left.map(e => io.circe.DecodingFailure(e.message, Nil)))
      .fold(e => fail(e.getMessage), identity)

  /** The boundary case whose script passes and whose transaction says so. */
  private val (plutus, raw) =
    val all = parse(
      Files.readString(
        Path.of("fixtures/reference/plutus-boundaries/transactions/conway-pv10.json")
      )
    ).fold(e => fail(e.message), identity).asArray.getOrElse(fail("not an array"))
    val j = all
      .find(_.hcursor.get[String]("name").contains("raw-dst-pass-valid-true"))
      .getOrElse(fail("no case raw-dst-pass-valid-true"))
    val c = PlutusCase.fromJson(j)
    val aux = j.hcursor.get[String]("auxiliary").toOption.map(_ => hex(j, "auxiliary"))
    c -> Tx.envelope(Block.Transaction(hex(j, "body"), hex(j, "witnesses"), aux, c.tx.isValid))

  private val ledger: MempoolLedger =
    MempoolLedger.of(
      Build.funded(
        Build.nes[Conway](5, plutus.params),
        plutus.spent ++ plutus.references ++ plutus.collateral
      )
    )

  private def admit(backend: PlutusBackend): Either[Rejection, (MempoolState, Entry)] =
    MempoolState
      .empty(Right(ledger), plutus.slot - 1, None)
      .add(
        raw,
        TxKit.env.copy(evaluator = backend.evaluator, decoder = backend.decoder),
        Source.Local
      )

  test("the mempool admits the transaction with either real backend") {
    for vm <- PlutusVm.values do
      assert(admit(PlutusBackend.of(vm)).isRight, s"${vm.name}: ${admit(PlutusBackend.of(vm))}")
  }

  test("the mempool asks the backend it is given, and nothing else") {
    val probe = Probe()
    admit(probe.backend) match
      case Left(Rejection.Ledger(LedgerFailure.Unavailable(_, _, EvalFailure.Unsupported(_)))) =>
        assert(probe.decoded.get + probe.evaluated.get > 0, "the probe was not asked")
      case other => fail(s"a path kept another backend: $other")
  }

  test("the rules of a block ask the backend of their context, serial and prepared") {
    val probe = Probe(admits = true)
    val ctx = (backend: PlutusBackend) =>
      PreparedBlockFixture.body(backend.evaluator, decoder = backend.decoder)
    def noVerdict(outcome: rules.EraBlock.Outcome): Boolean = outcome match
      case rules.EraBlock.Outcome.Refused(BlockFailure.Tx(_: LedgerFailure.Unavailable)) => true
      case _                                                                             => false
    val reference = ctx(PlutusBackend.of(PlutusVm.Scalus)).serial
    assert(!noVerdict(reference), s"the fixture block has a verdict: $reference")
    val serial = ctx(probe.backend).serial
    assert(noVerdict(serial), s"serial: $serial")
    assert(probe.evaluated.get > 0, "serial: the probe's evaluator was not asked")
    val before = probe.evaluated.get
    val plan = ctx(probe.backend).prepare
    val completed = plan.batches.flatten.map(plan.execute)
    plan.resolve(completed) match
      case Right(outcome) => assert(noVerdict(outcome), s"prepared: $outcome")
      case Left(defect)   => fail(s"prepared: $defect")
    assert(probe.evaluated.get > before, "prepared: the probe was not asked")
  }
