// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{HeaderCheckpoints, HeaderRules}
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{
  Block,
  BlockFailure,
  ConfigError,
  ConfigProblem,
  EnvelopeError,
  HeaderError
}
import xyz.kd5ujc.scarab.node.read.ChainRead.given

class CheckpointsSuite extends FunSuite:
  private def bytes(text: String): Array[Byte] =
    text.getBytes(java.nio.charset.StandardCharsets.UTF_8)
  private def parsed(text: String) = CheckpointsFile.parse("checkpoints.json", bytes(text))
  private val zero = "00" * 32

  test("the complete pinned mainnet checkpoint file is accepted without changing its hashes") {
    val data = Files.readAllBytes(Path.of("fixtures/mainnet/genesis/checkpoints.json"))
    val digest =
      Bytes.fromArray(java.security.MessageDigest.getInstance("SHA-256").digest(data)).hex
    assertEquals(digest, "59f85d337372f398b0e49a58716d028bed5f123b04b07377c1322bcb6134fcb1")
    val checkpoints = CheckpointsFile
      .parse("mainnet-checkpoints.json", data)
      .fold(e => fail(e.message), identity)
    assertEquals(checkpoints.entries.size, 2767)
    assertEquals(
      checkpoints.entries.get(BigInt(2000)).map(_.hex),
      Some("a82e2b2253a39897f57912cf8fdd70786eb8a2460c2955bb3521642539b87ead")
    )
    assertEquals(
      checkpoints.entries.get(BigInt(5534000)).map(_.hex),
      Some("f21b827c5bf684e4f600647dcfcd741c197ab248c60b2c75ec29c4ec33ffd534")
    )
  }

  test("duplicate block numbers are refused even when both hashes agree") {
    val result =
      parsed(s"""{"checkpoints":[{"blockNo":7,"hash":"$zero"},{"blockNo":7,"hash":"$zero"}]}""")
    assertEquals(
      result,
      Left(
        ConfigError(
          ConfigProblem.Ancillary(
            CheckpointsFile.Problem.DuplicateBlockNumbers(Set(BigInt(7)))
          )
        )
      )
    )
  }

  test("the hash binds the exact bytes parsed and is checked before JSON decoding") {
    val invalidJson = bytes("not JSON")
    val actual = Blake2b.hash256.hash(Bytes.fromArray(invalidJson))
    val wrong = Bytes.fromArray(Array.fill[Byte](32)(0))
    assertEquals(
      CheckpointsFile.parse("checkpoints.json", invalidJson, Some(wrong)),
      Left(
        ConfigError(
          ConfigProblem.GenesisHashMismatch(
            "Checkpoints",
            "checkpoints.json",
            wrong.hex,
            actual.hex
          )
        )
      )
    )
    assert(CheckpointsFile.parse("checkpoints.json", invalidJson, Some(actual)).isLeft)
  }

  test("checkpoint block numbers retain the full unsigned Word64 range") {
    val maximum = (BigInt(1) << 64) - 1
    assertEquals(
      parsed(s"""{"checkpoints":[{"blockNo":$maximum,"hash":"$zero"}]}""")
        .map(_.entries.keySet),
      Right(Set(maximum))
    )
    Vector(BigInt(-1), maximum + 1).foreach { invalid =>
      assert(parsed(s"""{"checkpoints":[{"blockNo":$invalid,"hash":"$zero"}]}""").isLeft)
    }
  }

  test("malformed hashes, missing lists and fractional block numbers are refused") {
    Vector(
      "{}",
      "[]",
      "{\"checkpoints\":null}",
      s"""{"checkpoints":[{"blockNo":1,"hash":"00"}]}""",
      s"""{"checkpoints":[{"blockNo":1,"hash":"${"zz" * 32}"}]}""",
      s"""{"checkpoints":[{"blockNo":1.5,"hash":"$zero"}]}"""
    ).foreach(text => assert(parsed(text).isLeft, text))
    assertEquals(parsed("{\"checkpoints\":[]}"), Right(HeaderCheckpoints.empty))
  }

  for name <- Vector("preprod", "eras") do
    test(
      s"$name original signed first block enforces checkpoints in both header and full-block validation"
    ) {
      val root = Path.of("fixtures", name)
      val setup =
        NodeSetup.fromDirectory(root.resolve("genesis")).fold(e => fail(e.message), identity)
      val line = Files
        .readString(root.resolve("blocks.hex"))
        .linesIterator
        .find(_.nonEmpty)
        .getOrElse(fail("capture has no block"))
      val raw = Bytes.fromHex(line).fold(e => fail(e.message), identity)
      val header =
        Block.decode(raw, setup.geometry.byron.epochSlots).fold(e => fail(e.message), _.header)
      val wrong = Bytes.fromArray(Array.fill[Byte](32)(0))
      assert(header.hash != wrong)
      val failure = EnvelopeError.CheckpointMismatch(header.blockNo, wrong, header.hash)
      val config = setup.praos.copy(checkpoints = HeaderCheckpoints(Map(header.blockNo -> wrong)))
      val check =
        new NodeHeaderRules[MirrorState](config, setup.globals, setup.byronRules, setup.handOver)
      assertEquals(
        check.validate(setup.mirrorStart, None, check.at(setup.mirrorStart), header),
        HeaderRules.Outcome.Invalid(HeaderError.Envelope(failure))
      )
      assertEquals(
        MirrorSteps.block(setup, setup.mirrorStart, header, raw, Some(config)),
        Left(BlockFailure.Envelope(failure))
      )
      val matching =
        config.copy(checkpoints = HeaderCheckpoints(Map(header.blockNo -> header.hash)))
      assert(MirrorSteps.block(setup, setup.mirrorStart, header, raw, Some(matching)).isRight)
    }

  test("a configured checkpoint file is required, rather than silently ignored") {
    val root = Path.of("fixtures/preprod/genesis")
    val setup = NodeSetup.fromDirectory(root).fold(e => fail(e.message), identity)
    val config = setup.config.copy(files =
      setup.config.files.copy(checkpoints = Some("missing-checkpoints.json"))
    )
    val result = NodeSetup.assemble(
      config,
      file => Option.when(Files.exists(root.resolve(file)))(Files.readAllBytes(root.resolve(file)))
    )
    assertEquals(
      result,
      Left(ConfigError(ConfigProblem.FileMissing("Checkpoints", "missing-checkpoints.json")))
    )
  }
