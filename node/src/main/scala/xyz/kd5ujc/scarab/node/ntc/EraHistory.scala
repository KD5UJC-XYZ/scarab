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

  /** `Serialise (Summary xs)`: an indefinite list of `[start, end, params]` (`Summary.hs:614-631`,
    * ouroboros-consensus 2416493b), each bound as [[bound]] writes it and the parameters as
    * [[params]].
    */
  def encode(w: CborOut, eras: Vector[EraSummary]): CborOut =
    w.indefiniteArray
    eras.foreach { e =>
      w.array(3)
      bound(w, e.start)
      e.end match
        case Some(b) => bound(w, b)
        case None    => w.nul
      params(w, e.epochSize, e.slotLengthMillis, e.safeZone, e.genesisWindow, e.perasRoundLength)
    }
    w.break

  /** `Serialise Bound` of the bound's layout: `[time in picoseconds, slot, epoch]` and the Peras
    * round where there is one, the time an `Integer` (`Serialise RelativeTime` writes
    * `toPicoseconds`), so a tag-2 big number once the chain is older than about 213 days. The 11.1
    * line writes the round only when it is `PerasEnabled` (ouroboros-consensus 82ecba32
    * `Summary.hs:526-538`); the Leios build always writes the next round, four items (2416493b
    * `Summary.hs:573-581`).
    */
  def bound(w: CborOut, b: Bound): CborOut =
    def numbers(n: Int) = w.array(n).integer(b.timePs).uint(b.slot).uint(b.epoch)
    b.layout match
      case Bound.Layout.Node11_1(None)        => numbers(3)
      case Bound.Layout.Node11_1(Some(round)) => numbers(4).uint(round.word.toBigInt)
      case Bound.Layout.Leios(next)           => numbers(4).uint(next.word.toBigInt)

  /** `Serialise EraParams` (`HardFork/History/EraParams.hs:293-306` at ouroboros-consensus
    * 82ecba32, `:253-266` at 2416493b): `[size, slot length in milliseconds (an ` Integer`), safe
    * zone, genesis window]`, and the Peras round length fifth where the era has rounds; the safe
    * zone a `StandardSafeZone` `[0, safe from tip, [0]]` with the artificial `NoLowerBound` it
    * still writes (`:280-281` at 82ecba32, `:240-241` at 2416493b).
    */
  private[ntc] def params(
      w: CborOut,
      epochSize: BigInt,
      slotLengthMillis: BigInt,
      safeZone: BigInt,
      genesisWindow: BigInt,
      perasRoundLength: Option[BigInt]
  ): CborOut =
    w.array(perasRoundLength.fold(4)(_ => 5)).uint(epochSize).integer(slotLengthMillis)
    w.array(3).uint(0).uint(safeZone).array(1).uint(0)
    w.uint(genesisWindow)
    perasRoundLength.fold(w)(w.uint)
