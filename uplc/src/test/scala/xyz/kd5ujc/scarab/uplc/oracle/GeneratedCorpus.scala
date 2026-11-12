// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.oracle

import io.circe.Json
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.uplc.cost.DefaultCostModels

/** The requests of `fixtures/reference/uplc-generated`: for every profile of [[Profile.all]],
  * `perProfile` programs of [[TermGen]], each from a seed derived from the corpus seed, the profile
  * and its number, with the profile's default cost-model list (`models.jsonl`, one per profile).
  */
object GeneratedCorpus:
  /** The corpus seed and size of the retained fixture. */
  val Seed: Long = 43080001L
  val PerProfile: Int = 200

  /** The seed of program `n` of profile `p`. */
  def seedOf(corpusSeed: Long, p: Int, n: Int): Long =
    SplitRng(corpusSeed ^ (p.toLong << 32) ^ n.toLong).next._2

  def model(profile: Profile): Vector[Long] =
    DefaultCostModels.list(profile.language, profile.variant)

  def cases(corpusSeed: Long, perProfile: Int): Vector[(Int, Int, Generated)] =
    Profile.all.zipWithIndex.flatMap { (profile, p) =>
      (0 until perProfile).map(n => (p, n, TermGen.generate(profile, seedOf(corpusSeed, p, n))))
    }

  def id(profile: Profile, n: Int): String = s"${profile.name}/$n"

  /** The request the oracle reads, without the cost-model list, which `models.jsonl` holds. */
  def request(p: Int, n: Int, generated: Generated): Json =
    Json.obj(
      "id" -> Json.fromString(id(generated.profile, n)),
      "model" -> Json.fromInt(p),
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

  /** Writes `models.jsonl` and `requests.jsonl` into the directory `args(0)`; `args(1)` and
    * `args(2)`, when given, replace the corpus seed and the number of programs per profile.
    */
  def write(args: Array[String]): Unit =
    val directory = Path.of(args.head)
    val corpusSeed = args.lift(1).map(_.toLong).getOrElse(Seed)
    val perProfile = args.lift(2).map(_.toInt).getOrElse(PerProfile)
    Files.createDirectories(directory)
    val models = Profile.all.map(profile =>
      Json.obj(
        "language" -> Json.fromInt(profile.language.id + 1),
        "protocol" -> Json.fromInt(profile.protocol.value),
        "costModel" -> Json.arr(model(profile).map(Json.fromLong)*)
      )
    )
    Files.writeString(
      directory.resolve("models.jsonl"),
      models.map(_.noSpaces).mkString("", "\n", "\n")
    )
    val requests = cases(corpusSeed, perProfile).map((p, n, g) => request(p, n, g).noSpaces)
    Files.writeString(directory.resolve("requests.jsonl"), requests.mkString("", "\n", "\n"))
    Files.writeString(
      directory.resolve("corpus.json"),
      Json
        .obj(
          "seed" -> Json.fromLong(corpusSeed),
          "perProfile" -> Json.fromInt(perProfile),
          "generatorVersion" -> Json.fromInt(TermGen.Version)
        )
        .spaces2 + "\n"
    )
    println(
      s"${requests.size} requests over ${models.size} profiles, seed $corpusSeed, generator ${TermGen.Version}"
    )
