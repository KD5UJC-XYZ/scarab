// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import cats.Id

/** The evaluator and the script decoder, which share their caches: what the node's rules, its
  * mempool and its forger evaluate and admit scripts with. Every one of them takes it explicitly.
  */
final case class PlutusBackend(evaluator: ScriptEvaluator[Id], decoder: ScriptDecoder[Id])

object PlutusBackend:
  /** The node's backend: the shared interpreter of Scarab's own evaluator ([[ScarabEvaluator]]). */
  val default: PlutusBackend =
    PlutusBackend(ScarabEvaluator.default, ScarabEvaluator.defaultDecoder)
