// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import munit.FunSuite

class ParamNamesSuite extends FunSuite:
  private def recorded(version: Int): Vector[String] =
    PlutusConformanceSuite
      .resource(s"V$version-param-names.txt")
      .linesIterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .toVector

  test("the tables are the constructors of the pinned ParamName sources, in declaration order") {
    assertEquals(ParamNames.v1.size, 343)
    assertEquals(ParamNames.v2.size, 343)
    assertEquals(ParamNames.v3.size, 361)
    assertEquals(ParamNames.v1, recorded(1).map(ParamNames.show))
    assertEquals(ParamNames.v2, recorded(2).map(ParamNames.show))
    assertEquals(ParamNames.v3, recorded(3).map(ParamNames.show))
  }

  test("showParamName writes the first letter in lower case and every quote as a hyphen") {
    assertEquals(
      ParamNames.show("AddInteger'cpu'arguments'intercept"),
      "addInteger-cpu-arguments-intercept"
    )
    assertEquals(ParamNames.show("BData'cpu'arguments"), "bData-cpu-arguments")
    assertEquals(ParamNames.show("Sha2_256'memory'arguments"), "sha2_256-memory-arguments")
  }

  test("every name of a table is distinct") {
    Vector(ParamNames.v1, ParamNames.v2, ParamNames.v3).foreach(names =>
      assertEquals(names.distinct, names)
    )
  }
