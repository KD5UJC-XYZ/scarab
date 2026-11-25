// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import munit.Assertions
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.flat.FlatEncoder
import xyz.kd5ujc.scarab.uplc.text.UplcParser

/** A Plutus V3 script that burns execution units: it ignores its context and counts `iterations`
  * down to zero by self-application, then returns unit, so its cost grows with the count. The
  * script-heavy blocks of the parallel-Plutus threshold (#85, ADR 0018) are made of it, as no
  * recorded block holds heavy scripts.
  */
object PlutusLoop extends Assertions:
  /** The script as the ledger holds it (a CBOR byte string of the flat program). */
  def script(iterations: Int): Bytes =
    val text =
      s"""(program 1.1.0
         |  (lam ctx
         |    [ [ (lam f [ f f ])
         |        (lam self (lam n
         |          (force
         |            [ [ [ (force (builtin ifThenElse))
         |                  [ [ (builtin lessThanEqualsInteger) n ] (con integer 0) ] ]
         |                (delay (con unit ())) ]
         |              (delay [ [ self self ] [ [ (builtin subtractInteger) n ] (con integer 1) ] ]) ])))
         |      ]
         |      (con integer $iterations) ]))""".stripMargin
    UplcParser
      .program(text)
      .fold(e => fail(s"$e"), FlatEncoder.script(_).fold(e => fail(s"$e"), identity))
