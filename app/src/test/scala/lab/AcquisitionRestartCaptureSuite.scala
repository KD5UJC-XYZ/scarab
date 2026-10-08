// SPDX-License-Identifier: Apache-2.0
package lab

class AcquisitionRestartCaptureSuite extends munit.FunSuite:
  private val common = List("3001", "1082026", "0", "0" * 64, "a" * 64, "b" * 64)
  test("phase B requires independent exact revision; phase A cannot silently resume") {
    assert(AcquisitionRestartCapture.options(List("a") ++ common ++ List("-", "-")).isRight)
    assert(AcquisitionRestartCapture.options(List("b") ++ common ++ List("3", "c" * 64)).isRight)
    for phase <- List("a", "b", "unknown") do
      assert(AcquisitionRestartCapture.options(List(phase) ++ common ++ List("-1", "bad")).isLeft)
    assert(AcquisitionRestartCapture.options(List("a") ++ common ++ List("3", "c" * 64)).isLeft)
    assert(AcquisitionRestartCapture.options(List("b") ++ common ++ List("-", "-")).isLeft)
  }
  test("invalid bounds or contexts reject before opening the checkpoint or network") {
    for index <- List(0, 1, 2, 3, 4, 5) do
      assert(
        AcquisitionRestartCapture
          .options(List("a") ++ common.updated(index, "invalid") ++ List("-", "-"))
          .isLeft
      )
    assert(AcquisitionRestartCapture.options(Nil).isLeft)
  }
