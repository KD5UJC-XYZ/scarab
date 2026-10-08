// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.{Files, Path}

class BlockFetchCommandSuite extends munit.FunSuite:
  private val directory =
    Vector(Path.of("fixtures/network/block-fetch"), Path.of("../fixtures/network/block-fetch"))
      .find(Files.isDirectory(_))
      .getOrElse(throw new IllegalStateException("missing fixture directory"))
  test("offline selftest preserves bytes and labels synthetic correlation accurately") {
    val result = BlockFetchCommand.verify(directory)
    for flag <- Vector(
        "miniProtocol=3",
        "transportExchanges=0",
        "blockIdentityVerified=false",
        "cardanoHeaderValidated=false",
        "ledgerValidated=false",
        "batchCompleteOnlyAfterBatchDone=true"
      )
    do assert(result.contains(flag))
  }
  test("changed and oversized fixture fails before protocol interpretation") {
    val temp = Files.createTempDirectory("block-fetch-cli-")
    val path = temp.resolve("conway-block-source-derived.hex")
    try
      Files.writeString(path, "8204d8184180")
      intercept[IllegalArgumentException](BlockFetchCommand.verify(temp))
      Files.writeString(path, "0" * 16001)
      intercept[IllegalArgumentException](BlockFetchCommand.verify(temp))
    finally
      Files.deleteIfExists(path)
      Files.deleteIfExists(temp)
  }
