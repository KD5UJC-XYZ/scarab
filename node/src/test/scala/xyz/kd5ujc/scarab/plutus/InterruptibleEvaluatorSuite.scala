// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import cats.effect.IO
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.ledger.{EvaluationError, Unavailability}

/** The evaluator the mempool's admission runs ([[ScarabEvaluator.interruptible]]) is the node's
  * ([[ScarabEvaluator.default]]) on a thread nothing interrupts, verdict and budget alike; once its
  * thread is interrupted, as cancelling `IO.interruptible` interrupts it, it ends, with no verdict,
  * however long the script would have run. In the node's test tree for `FailsOnUncaught`, as
  * `IO.race` starts fibers (TST-3).
  */
class InterruptibleEvaluatorSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  private def requests: Vector[EvalRequest] =
    for
      probe <- PlutusBoundaryCorpus.probes
      language <- PlutusLanguage.values.toVector.filterNot(_ == PlutusLanguage.V4)
      protocol <- 9 to 11
    yield EvalRequest(
      language,
      protocol,
      probe.script,
      Vector.empty,
      PlutusBoundaryCorpus.costModel(language, protocol),
      PlutusBoundaryCorpus.budget
    )

  /** A result as compared: a failure of the machine by its error and the term it is about, whose
    * `Cause` has no equality of its own; everything else as it is.
    */
  private def shown(result: EvalResult): Any = result match
    case EvalResult.Failure(
          EvalFailure.Script(ScriptFailure.Uplc(EvaluationError.Cek(failure), logs)),
          used,
          more
        ) =>
      (failure.error, failure.cause.map(_.term), logs, used, more)
    case other => other

  test("on a thread nothing interrupts, every verdict and budget is the node's evaluator's") {
    val all = requests
    assert(all.size > 100, s"${all.size} requests")
    val differing = all.filter(r =>
      shown(ScarabEvaluator.interruptible.evaluate(r)) != shown(ScarabEvaluator.default.evaluate(r))
    )
    assertEquals(differing.map(r => (r.language, r.protocolMajor)), Vector.empty)
  }

  /** A request whose budget no evaluation of these scripts spends in the life of a test. */
  private def unbounded(script: Bytes): EvalRequest =
    EvalRequest(
      PlutusLanguage.V3,
      10,
      script,
      Vector.empty,
      PlutusBoundaryCorpus.costModel(PlutusLanguage.V3, 10),
      ExUnits(BigInt(Long.MaxValue), BigInt(Long.MaxValue))
    )

  /** The evaluation of `request`, cancelled once it has started: what the evaluation itself gave,
    * which the cancellation discards.
    */
  private def cancelled(request: EvalRequest): IO[Option[EvalResult]] =
    IO.delay((CountDownLatch(1), AtomicReference(Option.empty[EvalResult]))).flatMap {
      (started, seen) =>
        IO.race(
          IO.interruptible {
            started.countDown()
            seen.set(Some(ScarabEvaluator.interruptible.evaluate(request)))
          },
          IO.blocking(started.await())
        ).map(won => assert(won.isRight, s"the evaluation ended by itself: $won"))
          .flatMap(_ => IO.delay(seen.get))
    }

  private val stopped =
    EvalResult.Failure(
      EvalFailure.Unsupported(BackendFailure.Uplc(Unavailability.Interrupted)),
      ExUnits.zero,
      Vector.empty
    )

  test("a script that never ends ends once its evaluation is cancelled, with no verdict") {
    cancelled(unbounded(UplcScripts.compile("(program 1.1.0 [(lam x [x x]) (lam x [x x])])")))
      .map(assertEquals(_, Some(stopped)))
  }

  test("the audit's loop of G2 hashToGroup ends once its evaluation is cancelled") {
    val g2 =
      "(program 1.0.0 [(lam self [self self]) (lam self [(lam x [self self]) " +
        "[(builtin bls12_381_G2_hashToGroup) (con bytestring #00) (con bytestring #00)]])])"
    cancelled(unbounded(UplcScripts.compile(g2))).map(assertEquals(_, Some(stopped)))
  }
