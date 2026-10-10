// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, ExitCode}
import cats.effect.unsafe.implicits.global
import java.nio.file.Files
import lab.submission.AdmissionProfile
import ReferenceJson.Json as J

class PlutusResearchCommandSuite extends munit.FunSuite:
  private val valid = List(
    "--profile",
    AdmissionProfile.PlutusV3.id,
    "--initial",
    "/private/initial",
    "--manifest-sha256",
    "ab" * 32,
    "--port",
    "12345",
    "--magic",
    "991122",
    "--exchange",
    "/private/exchange"
  )
  private def change(key: String, value: String) = valid.updated(valid.indexOf(key) + 1, value)
  test("explicit restricted options preserve controller protocol and permit option reordering") {
    val parsed = PlutusResearchCommand.options(valid).toOption.get
    assertEquals(
      parsed.legacyArgs,
      List("/private/initial", "ab" * 32, "12345", "991122", "/private/exchange")
    )
    assertEquals(
      PlutusResearchCommand.options(valid.grouped(2).toList.reverse.flatten),
      Right(parsed)
    )
  }
  test("missing, duplicate, unknown and implicit profiles fail before runtime allocation") {
    val cases = List(
      Nil,
      valid.drop(2),
      valid ++ List("--port", "1"),
      valid.updated(0, "--host"),
      valid.updated(2, "--profile"),
      change("--profile", AdmissionProfile.AdaVkey.id)
    )
    cases.foreach(args => assert(PlutusResearchCommand.options(args).isLeft))
  }
  test("public-known magic, noncanonical numerics, invalid pins and directory ambiguity fail") {
    val cases = List("1", "2", "764824073", "0", "4294967296", "+991122", "0991122").map(
      change("--magic", _)
    ) ++ List("0", "65536", "-1", "+12345", "012345").map(change("--port", _)) ++ List(
      change("--manifest-sha256", "AB" * 32),
      change("--initial", "relative"),
      change("--initial", "/private/../initial"),
      change("--exchange", "/private/initial")
    )
    cases.foreach(args => assert(PlutusResearchCommand.options(args).isLeft))
  }
  test("help and missing options never require source packets or open runtime") {
    (for
      help <- PlutusResearchCommand.run(List("--help"))
      invalid <- PlutusResearchCommand.run(Nil)
      _ = assertEquals(help, ExitCode.Success)
      _ = assertEquals(invalid, ExitCode(2))
    yield ()).unsafeToFuture()
  }
  test("diagnostic evidence publication refuses existing targets") {
    (for
      directory <- IO.blocking(Files.createTempDirectory("plutus-cli-evidence-"))
      target = directory.resolve("result.json")
      _ <- PlutusResearchIO.save(target, J.Str("first"))
      before <- IO.blocking(Files.readAllBytes(target).toVector)
      second <- PlutusResearchIO.save(target, J.Str("second")).attempt
      after <- IO.blocking(Files.readAllBytes(target).toVector)
      _ = assert(second.isLeft)
      _ = assertEquals(after, before)
    yield ()).unsafeToFuture()
  }

  test(
    "shared evidence encoder preserves sorted keys, original number spelling and escaped strings"
  ) {
    val value = J.Obj(
      Map(
        "z" -> J.Arr(Vector(J.Lit("true"), J.Lit("null"), J.Num("1.20"))),
        "a" -> J.Str("quote\" slash\\ newline\n")
      )
    )
    val raw = EvidenceJson.encode(value)
    assertEquals(
      new String(raw.toArray, java.nio.charset.StandardCharsets.UTF_8),
      """{"a":"quote\" slash\\ newline\u000a","z":[true,null,1.20]}""" + "\n"
    )
    assertEquals(ReferenceJson.parse(raw), value)
    assertEquals(SyntheticRewardProjection.encode(value), raw)
  }
