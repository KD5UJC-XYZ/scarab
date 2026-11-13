// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.fuzz

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{PlutusLanguage, ProtocolMajor}
import xyz.kd5ujc.scarab.uplc.flat.{FlatProbes, SerialisedScript}
import xyz.kd5ujc.scarab.uplc.oracle.ScriptWriter

/** The own evaluator's script decoder (`uplc`, `SerialisedScript.deserialiseScript`), the ledger's
  * `deserialiseScript` of plutus 1.70.0.0, under profiles that differ in the builtins they admit,
  * the program versions and the bytes allowed after the script. Seeds: the Flat probes of
  * `plutus-flat-decode`, and the recorded scripts of `plutus-boundaries`, `plutus-costing` and
  * `uplc-generated`. Properties, besides the harness's: no exception, and a script it admits writes
  * back ([[ScriptWriter]]) to bytes it admits as the same program.
  */
object UplcTargets:
  private val name = "uplc-deserialise-script"

  private val profiles: Vector[(PlutusLanguage, ProtocolMajor)] = Vector(
    PlutusLanguage.V1 -> ProtocolMajor(5),
    PlutusLanguage.V1 -> ProtocolMajor(11),
    PlutusLanguage.V2 -> ProtocolMajor(10),
    PlutusLanguage.V3 -> ProtocolMajor(9),
    PlutusLanguage.V3 -> ProtocolMajor(11)
  )

  // A missing file fails the target rather than leaving it without the seeds it names.
  private def scripts(path: String, field: String, every: Int): Vector[Array[Byte]] =
    Files
      .readAllLines(Path.of(path))
      .asScala
      .toVector
      .zipWithIndex
      .collect { case (line, i) if i % every == 0 => line }
      .map { line =>
        val script = io.circe.parser
          .parse(line)
          .flatMap(_.hcursor.get[String](field))
          .fold(throw _, identity)
        Bytes.fromHex(script).fold(e => sys.error(e.message), _.toArray)
      }

  // The probes of `Data` 16,000 levels deep are left out: decoded under every profile and written
  // back, a level costs more than the 1,000 bytes per input byte `GuardSuite` allows a seed;
  // `Mutate` nests deeply on its own.
  private val seeds: () => Vector[Array[Byte]] = () =>
    FlatProbes.probes.map(_.script.toArray).filter(_.length <= 8192) ++
      scripts("fixtures/reference/plutus-boundaries/scripts.jsonl", "script", 1) ++
      scripts("fixtures/reference/plutus-costing/scripts.jsonl", "script", 10) ++
      scripts("fixtures/reference/uplc-generated/requests.jsonl", "script", 5) ++
      Corpus.tricky

  private def violation(what: String): Nothing = throw new Violation(what)

  val all: Vector[Target] = Vector(
    Target(
      name,
      seeds,
      in =>
        // A decode under each profile, and the write and second decode of the first it admits.
        Probe.run(name, in, perByte = 1000L * (profiles.size + 4)) {
          val bytes = Bytes.fromArray(in.bytes)
          val decoded = profiles.map((language, protocol) =>
            (language, protocol, SerialisedScript.deserialiseScript(language, protocol, bytes))
          )
          decoded.collectFirst { case (language, protocol, Right(program)) =>
            Targets.accepted
              .computeIfAbsent(name, _ => new java.util.concurrent.atomic.LongAdder)
              .increment()
            val written = ScriptWriter.script(program)
            SerialisedScript.deserialiseScript(language, protocol, written) match
              case Right(again) if again == program => ()
              case Right(_) =>
                violation(s"$name: the program written back decodes to another program")
              case Left(error) =>
                violation(s"$name: the program written back is refused: $error")
          }
        }
    )
  )
