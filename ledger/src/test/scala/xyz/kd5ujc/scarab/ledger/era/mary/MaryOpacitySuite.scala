// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.mary

/** An opaque era type is opaque outside the file that defines it, even in its own package: a Mary
  * output cannot be made from its representation here.
  */
class MaryOpacitySuite extends munit.FunSuite:
  test("a sibling file of the same package cannot make a Mary output from its representation") {
    val refused = compileErrors(
      "def f(r: xyz.kd5ujc.scarab.ledger.era.ShelleyTxOutRep[xyz.kd5ujc.scarab.ledger.era.MultiAssetValue]): MaryTxOut = r"
    )
    assert(refused.contains("MaryTxOut"), refused)
  }
