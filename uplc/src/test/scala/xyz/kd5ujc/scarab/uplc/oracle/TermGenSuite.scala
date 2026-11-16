// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.oracle

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import xyz.kd5ujc.scarab.uplc.SmallStack
import xyz.kd5ujc.scarab.uplc.flat.SerialisedScript

/** The generator of [[GeneratedOracleSuite]]'s programs, without the oracle: a seed gives one
  * program, what the decoder admits of its bytes is that program, and the own evaluator gives every
  * program a verdict (a defect of its own or an exception is none), on random seeds of every
  * profile.
  */
final class TermGenSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(300)

  private val profiles = GeneratedCorpus.Plutus171.profiles

  private val generated: Gen[Generated] =
    for
      profile <- Gen.oneOf(profiles)
      seed <- Gen.long
    yield TermGen.generate(profile, seed)

  private lazy val contexts = profiles
    .map(p =>
      p -> Verdict
        .context(p.language, p.protocol, GeneratedCorpus.model(GeneratedCorpus.Plutus171, p))
    )
    .toMap

  property("a seed gives the same program, arguments and budget every time") {
    Prop.forAll(Gen.oneOf(profiles), Gen.long) { (profile, seed) =>
      assertEquals(TermGen.generate(profile, seed), TermGen.generate(profile, seed))
    }
  }

  property("what the decoder admits of a generated script is the generated program") {
    Prop.forAll(generated) { g =>
      val script = ScriptWriter.script(g.program)
      SerialisedScript.deserialiseScript(g.profile.language, g.profile.protocol, script) match
        case Right(program) => assertEquals(program, g.program, s"seed ${g.seed}")
        case Left(_)        => ()
    }
  }

  property("the own evaluator gives every generated script a verdict") {
    Prop.forAll(generated) { g =>
      val verdict = Verdict.evaluate(
        contexts(g.profile),
        ScriptWriter.script(g.program),
        g.arguments,
        g.budget
      )
      assert(verdict.isRight, s"${g.profile.name} seed ${g.seed}: $verdict")
    }
  }

  test("the generator runs on a small stack") {
    val programs = SmallStack.run(
      (0 until 38).map(n => TermGen.generate(profiles(n % profiles.size), n.toLong)).toVector
    )
    assertEquals(programs.size, 38)
  }
