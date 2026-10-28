// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.{ConfigError, ConfigProblem}

/** A Shelley genesis security parameter is a `NonZero Word64`, but the chain store counts blocks in
  * an `Int`: the node setup refuses a larger one with a typed failure, as the genesis reader did
  * before it read the reference's type.
  */
class GenesisSecurityParamSuite extends FunSuite:
  private val directory = Path.of("fixtures/devnet/lsq/genesis")

  private def setup(k: BigInt) =
    def read(name: String): Option[Array[Byte]] =
      Option.when(Files.exists(directory.resolve(name))) {
        val bytes = Files.readAllBytes(directory.resolve(name))
        if name == "shelley-genesis.json" then
          parser
            .parse(new String(bytes, "UTF-8"))
            .fold(e => fail(e.message), identity)
            .deepMerge(
              Json.obj(
                "securityParam" -> Json.fromBigInt(k),
                "epochLength" -> Json.fromLong(Long.MaxValue)
              )
            )
            .noSpaces
            .getBytes("UTF-8")
        else bytes
      }
    NodeSetup.fromFiles(read)

  test("a security parameter beyond Int is refused at the node setup, not truncated") {
    val k = BigInt(Int.MaxValue) + 1
    assertEquals(
      setup(k).map(_.geometry.k),
      Left(ConfigError(ConfigProblem.SecurityParamBeyondStorage(k)))
    )
  }
