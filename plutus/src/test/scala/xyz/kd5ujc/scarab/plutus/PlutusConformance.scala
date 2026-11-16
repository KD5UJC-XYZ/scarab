// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import io.circe.{Json, parser}

/** The vendored reference data of plutus 1.70.0.0 ([[PlutusReference]]) that the probe corpora and
  * the suites read: its conformance cases, its cost-model files and its parameter names.
  */
object PlutusConformance:
  /** The conformance cases' directory in a [[PlutusReference.View]]. */
  val Conformance = "conformance"

  def reference: PlutusReference.View = PlutusReference.view(PlutusRelease.Plutus1_70)

  /** A file of the release's `cost-model` or `ledger-api` data, by its name. */
  def resource(name: String): String =
    reference.read(
      Vector(s"cost-model/$name", s"ledger-api/$name").find(reference.contains).getOrElse(name)
    )

  def json(name: String): Json = parser.parse(resource(name)).fold(throw _, identity)
