// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

/** Isolated negative control for issue 1082. This branch must never be merged. */
class AutoMergeTrialSuite extends munit.FunSuite:
  test("intentional failure verifies that the required summary covers a failing test shard") {
    fail("Intentional auto-merge trial failure (#1082): do not merge this branch")
  }
