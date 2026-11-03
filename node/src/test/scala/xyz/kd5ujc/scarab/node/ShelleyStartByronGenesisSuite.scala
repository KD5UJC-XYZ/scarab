// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.{ByronFieldError, ByronGenesisField, ConfigError, ConfigProblem}
import xyz.kd5ujc.scarab.ledger.json.CanonicalJson

/** A chain that starts in Shelley (Preview, `TestShelleyHardForkAtEpoch: 0`) still has its Byron
  * genesis read whole, as `readGenesisData` reads it at the reference's start-up (#734): a Byron
  * genesis the reference refuses does not start the node, though no Byron block will be read.
  */
class ShelleyStartByronGenesisSuite extends FunSuite:
  private val dir = Path.of("fixtures/preview/genesis")

  /** The Preview files, the configured Byron hash left out unless `keepHash`. */
  private def setup(
      byron: String => String,
      keepHash: Boolean = false
  ): Either[ConfigError, NodeSetup] =
    NodeSetup.fromFiles { name =>
      Option.when(Files.exists(dir.resolve(name))) {
        val bytes = Files.readAllBytes(dir.resolve(name))
        name match
          case "config.json" if !keepHash =>
            new String(bytes, "UTF-8")
              .replaceAll("\\s*\"ByronGenesisHash\":\\s*\"[0-9a-f]+\",", "")
              .getBytes("UTF-8")
          case "byron-genesis.json" => byron(new String(bytes, "UTF-8")).getBytes("UTF-8")
          case _                    => bytes
      }
    }

  test("the unedited Byron genesis of a chain that starts in Shelley is read") {
    val read = setup(identity).fold(e => fail(e.message), identity)
    assertEquals(read.config.firstShelleyEpoch, Some(BigInt(0)))
    assertEquals(read.byronParams.map(_.securityParam), Some(BigInt(432)))
  }

  private val edited = (text: String) =>
    assertEquals(text.split("\"startTime\": 1666656000", -1).length, 2)
    text.replace("\"startTime\": 1666656000", "\"startTime\": \"1666656000\"")

  private val refusal = Left(
    ConfigProblem.ByronGenesisValue(
      ByronGenesisField.StartTime,
      ByronFieldError.Kind(CanonicalJson.Kind.Number, CanonicalJson.Kind.String)
    )
  )

  test("a Byron genesis the reference refuses does not start a chain that begins in Shelley") {
    assertEquals(setup(edited).left.map(_.problem), refusal)
  }

  test("the schema is read before the configured hash is compared, as readGenesis does") {
    assertEquals(setup(edited, keepHash = true).left.map(_.problem), refusal)
  }
