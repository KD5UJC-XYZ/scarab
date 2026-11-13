// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import cats.Id
import xyz.kd5ujc.scarab.codec.DomainError

/** The Plutus evaluator a node runs, `--plutus-vm` (ADR 0027): the Scalus backend, the default and
  * the fast path, or Scarab's own, an opt-in correctness backend with no Scalus in it.
  */
enum PlutusVm(val name: String):
  case Scalus extends PlutusVm("scalus")
  case Scarab extends PlutusVm("scarab")

  /** Whether the node may forge with this evaluator. The own evaluator may not until the evidence
    * of its gates is in: a disagreement with the reference while following stays on this node, but
    * a forged block with a wrong validity flag is refused by every other node (ADR 0027, its
    * amendment of 2026-11-12). The owner may lift the refusal then.
    */
  def forges: Boolean = this match
    case Scalus => true
    case Scarab => false

object PlutusVm:
  val Default: PlutusVm = Scalus

  /** The evaluator a flag value names, or the typed refusal of an unknown one. */
  def parse(text: String): Either[UnknownPlutusVm, PlutusVm] =
    values.find(_.name == text).toRight(UnknownPlutusVm(text))

/** A `--plutus-vm` value that names no evaluator. */
final case class UnknownPlutusVm(text: String) extends DomainError:
  def message: String =
    s"unknown Plutus evaluator '$text' (known: ${PlutusVm.values.map(_.name).mkString(", ")})"

/** One backend's evaluator and script decoder, which share its caches: what the node's rules, its
  * mempool and its forger evaluate and admit scripts with. Every one of them takes it explicitly,
  * so no path of the node can keep another backend than the one chosen.
  */
final case class PlutusBackend(
    vm: PlutusVm,
    evaluator: ScriptEvaluator[Id],
    decoder: ScriptDecoder[Id]
)

object PlutusBackend:
  /** The shared interpreter of `vm`: for Scalus, the one the node used before it had a choice. */
  def of(vm: PlutusVm): PlutusBackend = vm match
    case PlutusVm.Scalus =>
      PlutusBackend(vm, ScalusEvaluator.default, ScalusEvaluator.defaultDecoder)
    case PlutusVm.Scarab =>
      PlutusBackend(vm, ScarabEvaluator.default, ScarabEvaluator.defaultDecoder)
