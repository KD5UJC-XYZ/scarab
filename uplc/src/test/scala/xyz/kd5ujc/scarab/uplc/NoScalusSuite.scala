// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

import java.io.File

/** The evaluator of this module is independent of Scalus (ADR 0027): none of its classes is
  * loadable from the test JVM's class path, so a dependency that brings one in fails here as well
  * as in the build's `checkUplcClasspath`.
  */
final class NoScalusSuite extends munit.FunSuite:

  test("no Scalus class loads in the uplc test JVM") {
    val probes = List(
      "scalus.uplc.DefaultFun",
      "scalus.uplc.eval.CekMachine",
      "scalus.uplc.builtin.Data",
      "scalus.cardano.ledger.ExUnits",
      "scalus.crypto.jni.CryptoJni"
    )
    val loadable = probes.filter(name => scala.util.Try(Class.forName(name)).isSuccess)
    assertEquals(loadable, Nil, "Scalus classes reachable from the uplc test class path")
  }

  test("no class path entry names a Scalus artifact") {
    val entries = System.getProperty("java.class.path", "").split(File.pathSeparator).toList
    val scalusEntries = entries.filter(_.toLowerCase.contains("scalus"))
    assertEquals(scalusEntries, Nil, "class path entries naming Scalus")
  }
