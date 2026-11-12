// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import munit.FunSuite

/** The backend the suites of this JVM run on ([[PlutusBackends]]): its name is in the test output,
  * so that a run meant for `scarab` shows that it ran on it.
  */
class PlutusBackendsSuite extends FunSuite:
  test(s"the suites of this JVM evaluate with ${PlutusBackends.vm.name}") {
    val expected = sys.props.get(PlutusBackends.Property).getOrElse(PlutusVm.Default.name)
    assertEquals(PlutusBackends.vm.name, expected)
    assertEquals(PlutusBackends.backend.vm, PlutusBackends.vm)
  }

  test("an unknown backend is refused, never read as the default") {
    assertEquals(PlutusBackends.chosen(Some("scalu")), Left(UnknownPlutusVm("scalu")))
    assertEquals(PlutusBackends.chosen(None), Right(PlutusVm.Scalus))
    assertEquals(PlutusBackends.chosen(Some("scarab")), Right(PlutusVm.Scarab))
  }

  test("each backend's evaluator and decoder are one interpreter, the shared one") {
    val scalus = PlutusBackend.of(PlutusVm.Scalus)
    assert(scalus.evaluator eq ScalusEvaluator.default)
    assert(scalus.decoder eq ScalusEvaluator.defaultDecoder)
    val scarab = PlutusBackend.of(PlutusVm.Scarab)
    assert(scarab.evaluator eq ScarabEvaluator.default)
    assert(scarab.decoder eq ScarabEvaluator.defaultDecoder)
  }
