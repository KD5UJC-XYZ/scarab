// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import org.wartremover.test.WartTestTraverser
import xyz.kd5ujc.scarab.warts.FloatingPointInLedger

/** The probe of `FloatingPointInLedger` for `DisallowedWartsSuite`: in a ledger package, as the
  * wart reports floating point only in the ledger and consensus packages.
  */
object FloatingPointProbe:
  val wart: FloatingPointInLedger.type = FloatingPointInLedger
  val result: WartTestTraverser.Result =
    WartTestTraverser(FloatingPointInLedger)((n: Int) => n * 0.5)
