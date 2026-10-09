// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.Files

class CaptureReaderBoundsSuite extends munit.FunSuite:
  test(
    "nonce reader admits sixteen originals while legacy reader retains eight and seventeen rejects"
  ) {
    val file = Files.createTempFile("nonce-captures-", ".jsonl")
    val row = """{"record":"transfer-range-block","headerEnvelopeHex":"00","rawBlockHex":"00"}"""
    try
      Files.writeString(file, Vector.fill(16)(row).mkString("\n"))
      assertEquals(ClusterHeaderObservation.capturesBounded(file, 16).map(_.size), Right(16))
      assert(ClusterHeaderObservation.captures(file).isLeft)
      Files.writeString(file, Vector.fill(17)(row).mkString("\n"))
      assert(ClusterHeaderObservation.capturesBounded(file, 16).isLeft)
      assert(ClusterHeaderObservation.capturesBounded(file, 17).isLeft)
    finally Files.deleteIfExists(file)
  }
