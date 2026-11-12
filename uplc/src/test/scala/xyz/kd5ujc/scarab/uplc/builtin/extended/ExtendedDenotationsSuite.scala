// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.extended

import xyz.kd5ujc.scarab.uplc.{DefaultFun, SemanticsVariant}
import xyz.kd5ujc.scarab.uplc.DefaultFun.*
import xyz.kd5ujc.scarab.uplc.builtin.core.{CoreDenotations, CoreVariant, UnsupportedVariant}

/** What the table of [[ExtendedDenotations]] covers. */
final class ExtendedDenotationsSuite extends munit.FunSuite:
  /** Batch 7, never available at protocols 5 to 11 (`Common/Versions.hs`). */
  private val batch7: Set[DefaultFun] =
    Set(MultiIndexArray, Policies, AssetCount, KeepPolicies, DropPolicies)

  test("with the core denotations, every builtin but batch 7 has exactly one denotation") {
    val core = CoreDenotations.functions
    val extended = ExtendedDenotations.functions
    assertEquals(core intersect extended, Set.empty[DefaultFun])
    assertEquals(core ++ extended, DefaultFun.values.toSet -- batch7)
  }

  test("every variant of 1.70.0.0 has the same builtins; F and G are refused") {
    for v <- CoreVariant.values do
      assertEquals(ExtendedDenotations(v).keySet, ExtendedDenotations.functions, s"$v")
    for v <- Vector(SemanticsVariant.F, SemanticsVariant.G) do
      assertEquals(ExtendedDenotations.of(v).map(_.keySet), Left(UnsupportedVariant(v)))
    assertEquals(ExtendedDenotations.of(SemanticsVariant.C).map(_.keySet), Right(ExtendedDenotations.functions))
  }
