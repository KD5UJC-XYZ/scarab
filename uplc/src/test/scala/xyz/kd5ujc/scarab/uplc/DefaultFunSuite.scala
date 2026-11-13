// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

import io.circe.parser
import java.nio.file.Files
import xyz.kd5ujc.scarab.uplc.DefaultFun.*
import xyz.kd5ujc.scarab.uplc.Signature.{Parameter, Type}

/** The builtin table against the reference: its tags, names and schemes, the builtins the vendored
  * cost models name, and the builtins the vendored conformance corpus uses.
  */
final class DefaultFunSuite extends munit.FunSuite:

  test("106 builtins whose Flat tags are 0 to 105, each once") {
    assertEquals(DefaultFun.values.length, 106)
    assertEquals(DefaultFun.values.map(_.flatTag).sorted.toVector, (0 to 105).toVector)
    DefaultFun.values.foreach(f => assertEquals(DefaultFun.fromFlatTag(f.flatTag), Some(f), f.name))
    assertEquals(DefaultFun.fromFlatTag(106), None)
    assertEquals(DefaultFun.fromFlatTag(127), None)
  }

  test("the tags where declaration order and tag order part (Builtins.hs:2566-2674)") {
    assertEquals(SerialiseData.flatTag, 51)
    assertEquals(MkPairData.flatTag, 48)
    assertEquals(VerifyEcdsaSecp256k1Signature.flatTag, 52)
    assertEquals(Bls12_381_G1_compress.flatTag, 58)
    assertEquals(Bls12_381_G1_hashToGroup.flatTag, 60)
    assertEquals(Bls12_381_G2_hashToGroup.flatTag, 67)
    assertEquals(DropPolicies.flatTag, 105)
  }

  test("names are unique and are the constructor with a lower-case first letter") {
    val names = DefaultFun.values.map(_.name).toVector
    assertEquals(names.distinct.size, names.size)
    assertEquals(AddInteger.name, "addInteger")
    assertEquals(Bls12_381_G1_add.name, "bls12_381_G1_add")
    assertEquals(IData.name, "iData")
    assertEquals(Sha2_256.name, "sha2_256")
    DefaultFun.values.foreach(f => assertEquals(DefaultFun.fromName(f.name), Some(f)))
    assertEquals(DefaultFun.fromName("AddInteger"), None)
  }

  test(
    "forces are the type variables, numbered by first appearance; opaque parameters are variables"
  ) {
    DefaultFun.values.foreach { f =>
      val vs = f.signature.variables
      assertEquals(vs, (0 until f.forces).toVector, s"${f.name}: variables")
      f.signature.parameters.foreach {
        case Parameter.Opaque(v)   => assert(v < f.forces, s"${f.name}: opaque $v")
        case Parameter.Constant(_) => ()
      }
    }
  }

  test("the polymorphic builtins and their arity (toBuiltinMeaning, Builtins.hs:1092-2528)") {
    val expected = Map(
      IfThenElse -> (1, 3),
      ChooseUnit -> (1, 2),
      Trace -> (1, 2),
      FstPair -> (2, 1),
      SndPair -> (2, 1),
      ChooseList -> (2, 3),
      MkCons -> (1, 2),
      HeadList -> (1, 1),
      TailList -> (1, 1),
      NullList -> (1, 1),
      ChooseData -> (1, 6),
      DropList -> (1, 2),
      LengthOfArray -> (1, 1),
      ListToArray -> (1, 1),
      IndexArray -> (1, 2),
      MultiIndexArray -> (1, 2)
    )
    val polymorphic =
      DefaultFun.values.filter(_.forces > 0).map(f => f -> (f.forces, f.arity)).toMap
    assertEquals(polymorphic, expected)
    assertEquals((MkNilData.forces, MkNilData.arity), (0, 1))
    assertEquals((InsertCoin.forces, InsertCoin.arity), (0, 4))
    assertEquals((ExpModInteger.forces, ExpModInteger.arity), (0, 3))
  }

  test("opaque and constant parameters are told apart") {
    assertEquals(
      IfThenElse.signature.parameters,
      Vector(Parameter.Constant(Type.Bool), Parameter.Opaque(0), Parameter.Opaque(0))
    )
    assertEquals(
      MkCons.signature.parameters,
      Vector(Parameter.Constant(Type.Variable(0)), Parameter.Constant(Type.List(Type.Variable(0))))
    )
    assertEquals(
      ChooseList.signature.parameters,
      Vector(
        Parameter.Constant(Type.List(Type.Variable(0))),
        Parameter.Opaque(1),
        Parameter.Opaque(1)
      )
    )
    assertEquals(
      UnConstrData.signature.result.ground,
      Some(DefaultUni.Pair(DefaultUni.Integer, DefaultUni.List(DefaultUni.Data)))
    )
    assertEquals(FstPair.signature.result.ground, None)
  }

  test(
    "every builtin is a key of each vendored builtin cost model, but the two costed as unimplemented in 1.70.0.0"
  ) {
    val names = DefaultFun.values.map(_.name).toSet
    for
      release <- ReferenceCorpus.Releases
      (path, file) <- ReferenceCorpus.view(release).toVector.sortBy(_._1)
      if path.startsWith("cost-model/builtinCostModel")
    do
      val keys = parser
        .parse(Files.readString(file))
        .toOption
        .flatMap(_.asObject)
        .map(_.keys.toSet)
        .getOrElse(Set.empty)
      assertEquals(keys -- names, Set.empty[String], s"$release $path: keys that are no builtin")
      // KeepPolicies and DropPolicies use `unimplementedCostingFun` in 1.70.0.0 (Builtins.hs:2513-2526).
      val uncosted =
        if release == "1.70.0.0" then Set(KeepPolicies.name, DropPolicies.name)
        else Set.empty[String]
      assertEquals(names -- keys, uncosted, s"$release $path: builtins without a cost")
  }

  private val BuiltinUse = raw"\(\s*builtin\s+([A-Za-z0-9_']+)".r

  private def usedBuiltins(files: Iterable[java.nio.file.Path]): Set[String] =
    files.iterator
      .flatMap(f => BuiltinUse.findAllMatchIn(Files.readString(f)).map(_.group(1)))
      .toSet

  test("every builtin the conformance corpus names is a builtin") {
    for release <- ReferenceCorpus.Releases do
      val programs = ReferenceCorpus.view(release).collect {
        case (path, file) if path.startsWith("conformance/") && path.endsWith(".uplc") => file
      }
      assertEquals(programs.size, 1009, release)
      val unknown = usedBuiltins(programs).filter(DefaultFun.fromName(_).isEmpty)
      assertEquals(unknown, Set.empty[String], release)
  }

  test(
    "the builtins with no case in builtin/semantics of the corpus are batch 7 and serialiseData"
  ) {
    // Batch 7 is released at no protocol version (Versions.hs:311-313); serialiseData has no
    // evaluation case in the upstream corpus of either release. Both are facts of the corpus, kept
    // here so that a change to it shows.
    for release <- ReferenceCorpus.Releases do
      val programs = ReferenceCorpus.view(release).collect {
        case (path, file)
            if path.startsWith("conformance/builtin/semantics/") && path.endsWith(".uplc") =>
          file
      }
      val uncovered = DefaultFun.values.map(_.name).toSet -- usedBuiltins(programs)
      assertEquals(
        uncovered,
        Set(SerialiseData, MultiIndexArray, Policies, AssetCount, KeepPolicies, DropPolicies).map(
          _.name
        ),
        release
      )
  }
