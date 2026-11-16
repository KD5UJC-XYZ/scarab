// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.extended

import xyz.kd5ujc.scarab.uplc.{DefaultFun, SemanticsVariant}
import xyz.kd5ujc.scarab.uplc.builtin.core.{CoreDenotations, CoreVariant}

/** What the table of [[ExtendedDenotations]] covers. */
final class ExtendedDenotationsSuite extends munit.FunSuite:
  test("with the core denotations, every builtin has exactly one denotation") {
    val core = CoreDenotations.functions
    val extended = ExtendedDenotations.functions
    assertEquals(core intersect extended, Set.empty[DefaultFun])
    assertEquals(core ++ extended, DefaultFun.values.toSet)
  }

  test("every variant, F and G included, has the same builtins") {
    for v <- CoreVariant.values do
      assertEquals(ExtendedDenotations(v).keySet, ExtendedDenotations.functions, s"$v")
    for v <- SemanticsVariant.values do
      assertEquals(ExtendedDenotations.of(v).keySet, ExtendedDenotations.functions, s"$v")
  }
