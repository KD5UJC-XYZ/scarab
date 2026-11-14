// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

import xyz.kd5ujc.scarab.uplc.Availability.{Arrival, Batch, Refusal}
import xyz.kd5ujc.scarab.uplc.PlutusLanguage.*
import xyz.kd5ujc.scarab.uplc.SemanticsVariant.*

/** The availability tables and the semantics variants against `Common/Versions.hs` and the
  * evaluation contexts of plutus 1.70.0.0, and against the tables of `docs/plutus-conformance.md`.
  */
final class AvailabilitySuite extends munit.FunSuite:
  private def pv(n: Int): ProtocolMajor = ProtocolMajor(n)
  private val Protocols = (0 to 14).map(pv)

  test("the batches hold every builtin exactly once") {
    val all = Batch.values.toVector.flatMap(_.builtins)
    assertEquals(all.size, DefaultFun.values.length)
    assertEquals(all.toSet, DefaultFun.values.toSet)
  }

  test("batch 7 is available to no language at any protocol") {
    for language <- PlutusLanguage.values; p <- Protocols :+ pv(Int.MaxValue) do
      assertEquals(
        Availability.builtins(language, p).intersect(Batch.Batch7.builtins.toSet),
        Set.empty[DefaultFun]
      )
  }

  test("the documented table (docs/plutus-conformance.md, section 1)") {
    def gains(language: PlutusLanguage, p: Int): Set[DefaultFun] =
      Availability.builtins(language, pv(p)) -- Availability.builtins(language, pv(p - 1))
    def of(batches: Batch*): Set[DefaultFun] = batches.flatMap(_.builtins).toSet
    import Batch.*
    assertEquals(gains(V1, 5), of(Batch1))
    assertEquals(gains(V1, 11), of(Batch2, Batch3, Batch4a, Batch4b, Batch5, Batch6))
    assertEquals(gains(V2, 7), of(Batch1, Batch2))
    assertEquals(gains(V2, 8), of(Batch3))
    assertEquals(gains(V2, 10), of(Batch4b))
    assertEquals(gains(V2, 11), of(Batch4a, Batch5, Batch6))
    assertEquals(gains(V3, 9), of(Batch1, Batch2, Batch3, Batch4a, Batch4b))
    assertEquals(gains(V3, 10), of(Batch5))
    assertEquals(gains(V3, 11), of(Batch6))
    for language <- PlutusLanguage.values; p <- Seq(6, 12, 13) do
      assertEquals(gains(language, p), Set.empty[DefaultFun], s"$language $p")
    assertEquals(Availability.builtins(V1, pv(4)), Set.empty[DefaultFun])
  }

  test("a V4 script uses V3's tables, which equal plutus's own V4 rows from protocol 12") {
    def raw(language: PlutusLanguage, p: ProtocolMajor): Set[DefaultFun] =
      Availability
        .introductions(language)
        .collect { case (a, bs) if a.reachedBy(p) => bs }
        .flatten
        .flatMap(_.builtins)
        .toSet
    for p <- Protocols do
      assertEquals(Availability.builtins(V4, p), Availability.builtins(V3, p))
      assertEquals(Availability.programVersions(V4, p), Availability.programVersions(V3, p))
      if p.value >= 12 then assertEquals(raw(V4, p), raw(V3, p))
      else assertEquals(raw(V4, p), Set.empty[DefaultFun])
    assertEquals(
      Availability.checkLanguage(V4, pv(11)),
      Left(Refusal.LanguageNotAvailable(V4, pv(12), pv(11)))
    )
    assertEquals(Availability.checkLanguage(V4, pv(12)), Right(()))
  }

  test("program versions: 1.1.0 for V3 from 9 and for V1 and V2 from 11; nothing else") {
    val both = Set(ProgramVersion.V1_0_0, ProgramVersion.V1_1_0)
    assertEquals(Availability.programVersions(V1, pv(10)), Set(ProgramVersion.V1_0_0))
    assertEquals(Availability.programVersions(V1, pv(11)), both)
    assertEquals(Availability.programVersions(V2, pv(6)), Set.empty[ProgramVersion])
    assertEquals(Availability.programVersions(V2, pv(7)), Set(ProgramVersion.V1_0_0))
    assertEquals(Availability.programVersions(V3, pv(9)), both)
    val v101 = ProgramVersion.of(1, 0, 1).getOrElse(fail("1.0.1"))
    assertEquals(
      Availability.checkProgramVersion(V3, pv(9), v101),
      Left(Refusal.ProgramVersionNotAvailable(v101, V3, pv(9)))
    )
    // The Flat decoder's gate is an order, not a set: 1.0.1 has no constr, 2.0.0 has.
    assertEquals(Availability.checkConstr(v101), Left(Refusal.ConstrBeforeVersion(v101)))
    assertEquals(
      Availability.checkCase(ProgramVersion.of(2, 0, 0).getOrElse(fail("2.0.0"))),
      Right(())
    )
  }

  test("decoder bounds from protocol 11: 32 type tags and 1024 constr fields") {
    val deep = (1 to 15).foldLeft(DefaultUni.Integer: DefaultUni)((t, _) => DefaultUni.List(t))
    assertEquals(deep.size, 31)
    assertEquals(DefaultUni.Pair(DefaultUni.Integer, DefaultUni.List(DefaultUni.Data)).size, 7)
    assertEquals(
      Availability.checkConstantType(pv(11), DefaultUni.List(deep)),
      Left(Refusal.ConstantTypeTooLarge(33, 32, pv(11)))
    )
    assertEquals(Availability.checkConstantType(pv(11), deep), Right(()))
    assertEquals(Availability.checkConstantType(pv(10), DefaultUni.List(deep)), Right(()))
    assertEquals(
      Availability.checkConstrFields(pv(11), 1025),
      Left(Refusal.ConstrTooManyFields(1025, 1024, pv(11)))
    )
    assertEquals(Availability.checkConstrFields(pv(11), 1024), Right(()))
    assertEquals(Availability.checkConstrFields(pv(10), 100000), Right(()))
  }

  test(
    "semantics variants: the table of the evaluation contexts and of docs/plutus-conformance.md"
  ) {
    for p <- 5 to 8 do
      assertEquals((SemanticsVariant.of(V1, pv(p)), SemanticsVariant.of(V2, pv(p))), (A, A))
    for p <- 9 to 10 do
      assertEquals((SemanticsVariant.of(V1, pv(p)), SemanticsVariant.of(V2, pv(p))), (B, B))
      assertEquals(SemanticsVariant.of(V3, pv(p)), C)
    for p <- 11 to 14 do
      assertEquals((SemanticsVariant.of(V1, pv(p)), SemanticsVariant.of(V2, pv(p))), (D, D))
      assertEquals((SemanticsVariant.of(V3, pv(p)), SemanticsVariant.of(V4, pv(p))), (E, E))
    for language <- PlutusLanguage.values; p <- Protocols do
      assert(
        SemanticsVariant.ofContext(language).contains(SemanticsVariant.of(language, p)),
        s"$language $p"
      )
    assertEquals(SemanticsVariant.ofContext(V2), Vector(A, B, D))
    assertEquals(SemanticsVariant.ofContext(V3), Vector(C, E))
  }

  test("an arrival at futurePV is reached by no protocol") {
    assert(!Arrival.Future.reachedBy(pv(Int.MaxValue)))
    assert(Arrival.At(pv(11)).reachedBy(pv(11)))
    assert(!Arrival.At(pv(11)).reachedBy(pv(10)))
  }
