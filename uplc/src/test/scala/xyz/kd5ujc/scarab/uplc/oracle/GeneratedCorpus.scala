// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.oracle

import cats.data.State
import cats.syntax.all.*
import io.circe.Json
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.uplc.cost.{DefaultCostModels, EvaluationContext}

/** The requests of `fixtures/reference/uplc-generated`: for every profile of [[Profile.all]],
  * `perProfile` programs of [[TermGen]], each from a seed derived from the corpus seed, the profile
  * and its number, with one of the profile's cost-model lists in `models.jsonl` ([[models]]).
  */
object GeneratedCorpus:
  /** The corpus seed and size of the retained fixture. */
  val Seed: Long = 43080001L
  val PerProfile: Int = 200
  val ModelsPerProfile: Int = 2

  /** The seed of program `n` of profile `p`: the corpus seed, the profile and the number each mixed
    * in turn, so that corpora of nearby seeds share no program.
    */
  def seedOf(corpusSeed: Long, p: Int, n: Int): Long =
    Vector(p.toLong, n.toLong).foldLeft(SplitRng(corpusSeed).next._2)((s, x) =>
      SplitRng(s ^ x).next._2
    )

  def model(profile: Profile): Vector[Long] =
    DefaultCostModels.list(profile.language, profile.variant)

  /** A list drawn from `values` (the list of `profile`), each parameter independently kept, scaled
    * or shifted, and a builtin's parameter sometimes zeroed, made large or negative: parameters
    * that the default lists keep in proportion (the slope and intercept of one builtin, or the
    * costs of two arguments) are then told apart by the budgets. The machine's parameters
    * (`cek...`) stay within a factor of two or so, or every program would run out of budget at its
    * first steps.
    */
  def perturbed(profile: Profile, values: Vector[Long], rng: SplitRng): Vector[Long] =
    import SplitRng.*
    val names = EvaluationContext.parameterNames(profile.language)
    def gentle(v: Long): Vector[(Int, Draw[Long])] = Vector(
      40 -> State.pure[SplitRng, Long](v),
      15 -> State.pure[SplitRng, Long](v * 2),
      10 -> State.pure[SplitRng, Long](v / 2),
      10 -> between(50, 200).map(k => v * k / 100),
      8 -> State.pure[SplitRng, Long](v + 13)
    )
    values.zipWithIndex
      .traverse((v, i) =>
        if names.lift(i).exists(_.startsWith("cek")) then pick(gentle(v))
        else
          pick(
            gentle(v) ++ Vector(
              6 -> State.pure[SplitRng, Long](0L),
              4 -> between(0, 300).map(k => v * k / 100 + 1),
              2 -> long.map(_ >>> 30),
              2 -> between(-1000, -1).map(_.toLong)
            )
          )
      )
      .runA(rng)
      .value

  /** The lists of a profile: its default, then `count - 1` lists drawn from it. */
  def models(profile: Profile, p: Int, corpusSeed: Long, count: Int): Vector[Vector[Long]] =
    val default = model(profile)
    default +: (1 until count).toVector.map(k =>
      perturbed(
        profile,
        default,
        SplitRng(corpusSeed ^ 0x6d6f64656c73L ^ (p.toLong << 40) ^ k.toLong)
      )
    )

  /** The programs of every profile, each with its number and the index of its cost-model list among
    * the `modelsPerProfile` lists of each profile.
    */
  def cases(
      corpusSeed: Long,
      perProfile: Int,
      modelsPerProfile: Int
  ): Vector[(Int, Int, Generated)] =
    Profile.all.zipWithIndex.flatMap { (profile, p) =>
      (0 until perProfile).map(n =>
        (
          p * modelsPerProfile + n % modelsPerProfile,
          n,
          TermGen.generate(profile, seedOf(corpusSeed, p, n))
        )
      )
    }

  def id(profile: Profile, n: Int): String = s"${profile.name}/$n"

  /** The request the oracle reads, without the cost-model list, which `models.jsonl` holds. */
  def request(model: Int, n: Int, generated: Generated): Json =
    Json.obj(
      "id" -> Json.fromString(id(generated.profile, n)),
      "model" -> Json.fromInt(model),
      "seed" -> Json.fromLong(generated.seed),
      "language" -> Json.fromInt(generated.profile.language.id + 1),
      "protocol" -> Json.fromInt(generated.profile.protocol.value),
      "script" -> Json.fromString(ScriptWriter.script(generated.program).hex),
      "arguments" -> Json.arr(
        generated.arguments.map(d => Json.fromString(ScriptWriter.argument(d).hex))*
      ),
      "cpu" -> Json.fromLong(generated.budget.cpu.toLong),
      "memory" -> Json.fromLong(generated.budget.memory.toLong),
      "verbose" -> Json.True
    )

  /** Writes `models.jsonl`, `requests.jsonl` and `corpus.json` into the directory `args(0)`;
    * `args(1)`, `args(2)` and `args(3)`, when given, replace the corpus seed, the number of
    * programs per profile and the number of cost-model lists per profile.
    */
  def write(args: Array[String]): Unit =
    val directory = Path.of(args.head)
    val corpusSeed = args.lift(1).map(_.toLong).getOrElse(Seed)
    val perProfile = args.lift(2).map(_.toInt).getOrElse(PerProfile)
    val modelsPerProfile = args.lift(3).map(_.toInt).getOrElse(ModelsPerProfile)
    Files.createDirectories(directory)
    val lists = Profile.all.zipWithIndex.flatMap((profile, p) =>
      models(profile, p, corpusSeed, modelsPerProfile).map(values =>
        Json.obj(
          "language" -> Json.fromInt(profile.language.id + 1),
          "protocol" -> Json.fromInt(profile.protocol.value),
          "costModel" -> Json.arr(values.map(Json.fromLong)*)
        )
      )
    )
    Files.writeString(
      directory.resolve("models.jsonl"),
      lists.map(_.noSpaces).mkString("", "\n", "\n")
    )
    val requests =
      cases(corpusSeed, perProfile, modelsPerProfile).map((m, n, g) => request(m, n, g).noSpaces)
    Files.writeString(directory.resolve("requests.jsonl"), requests.mkString("", "\n", "\n"))
    Files.writeString(
      directory.resolve("corpus.json"),
      Json
        .obj(
          "seed" -> Json.fromLong(corpusSeed),
          "perProfile" -> Json.fromInt(perProfile),
          "modelsPerProfile" -> Json.fromInt(modelsPerProfile),
          "generatorVersion" -> Json.fromInt(TermGen.Version)
        )
        .spaces2 + "\n"
    )
    println(
      s"${requests.size} requests with ${lists.size} cost-model lists, seed $corpusSeed, generator ${TermGen.Version}"
    )
