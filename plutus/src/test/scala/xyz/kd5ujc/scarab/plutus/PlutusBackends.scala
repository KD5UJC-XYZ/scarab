// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import cats.Id

/** The Plutus backend of the suites that need an evaluator and do not test one in particular,
  * chosen by the JVM's system property `scarab.plutus.vm` (`scalus`, the default, or `scarab`), so
  * that `check` runs them once per backend (ADR 0027). An unknown value fails every suite that asks
  * for the backend: a run meant for one backend never runs on the other unnoticed.
  *
  * The suites that test the Scalus backend itself name [[ScalusEvaluator]] directly.
  */
object PlutusBackends:
  val Property = "scarab.plutus.vm"

  /** The backend the property names, or the refusal of an unknown value. */
  def chosen(property: Option[String]): Either[UnknownPlutusVm, PlutusVm] =
    property.fold(Right(PlutusVm.Default))(PlutusVm.parse)

  /** The backend of this JVM. */
  lazy val vm: PlutusVm =
    chosen(sys.props.get(Property)).fold(e => munit.Assertions.fail(e.message), identity)

  /** The shared interpreter of [[vm]]. */
  lazy val backend: PlutusBackend = PlutusBackend.of(vm)

  def evaluator: ScriptEvaluator[Id] = backend.evaluator
  def decoder: ScriptDecoder[Id] = backend.decoder
