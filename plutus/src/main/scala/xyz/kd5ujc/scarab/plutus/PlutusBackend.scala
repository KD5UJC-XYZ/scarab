// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import cats.Id

/** The evaluator and the script decoder, which share their caches: what the node's rules, its
  * mempool and its forger evaluate and admit scripts with. Every one of them takes it explicitly.
  *
  * @param interruptible
  *   the evaluator as the mempool's admission runs it: one that stops, with no verdict, once the
  *   thread it runs on is interrupted, so that the admission's hard time limit ends it
  *   ([[ScarabEvaluator.interruptible]]); on a thread nothing interrupts, it is `evaluator`
  */
final case class PlutusBackend(
    evaluator: ScriptEvaluator[Id],
    decoder: ScriptDecoder[Id],
    interruptible: ScriptEvaluator[Id]
)

object PlutusBackend:
  /** The shared interpreter of Scarab's own evaluator ([[ScarabEvaluator]]), its program cache not
    * sized for a heap: the tools' and the suites' backend. Lazy, so that a node, which builds its
    * own ([[forHeap]]), never makes it.
    */
  lazy val default: PlutusBackend =
    PlutusBackend(
      ScarabEvaluator.default,
      ScarabEvaluator.defaultDecoder,
      ScarabEvaluator.interruptible
    )

  /** The node's backend for a maximum heap of `maxHeap` bytes: an interpreter of Scarab's own
    * evaluator of its own, whose program cache is bounded by the heap
    * ([[ScarabEvaluator.programs]]), as evaluator, decoder and interruptible evaluator.
    */
  def forHeap(maxHeap: Long): PlutusBackend =
    val interpreter = ScarabEvaluator.Interpreter(ScarabEvaluator.programs(maxHeap))
    PlutusBackend(interpreter, interpreter, ScarabEvaluator.interruptible(interpreter))
