// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import xyz.kd5ujc.scarab.consensus.state.{Bound, EraSummary}
import xyz.kd5ujc.scarab.consensus.state as hfc

/** The era history of the node-to-client answer. The types and the interpreter that computes the
  * summary are the hard-fork combinator's, in `consensus.state` (`Bound` is the one the telescope's
  * `Past` and `Current` carry); this object keeps the wire encoding, and the names the callers use.
  */
object EraHistory:
  export hfc.EraHistory.{summary, ByronParams}

  /** `Serialise (Summary xs)`: an indefinite list of `[start, end, [size, slotLength, safeZone,
    * genesisWindow]]`.
    */
  def encode(w: CborOut, eras: Vector[EraSummary]): CborOut =
    def bound(b: Bound): Unit = w.array(3).uint(b.timePs).uint(b.slot).uint(b.epoch)
    w.indefiniteArray
    eras.foreach { e =>
      w.array(3)
      bound(e.start)
      e.end match
        case Some(b) => bound(b)
        case None    => w.nul
      w.array(4).uint(e.epochSize).uint(e.slotLengthMillis)
      w.array(3).uint(0).uint(e.safeZone).array(1).uint(0)
      w.uint(e.genesisWindow)
    }
    w.break
