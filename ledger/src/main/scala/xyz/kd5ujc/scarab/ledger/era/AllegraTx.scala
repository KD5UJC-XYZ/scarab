// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.Eval
import cats.syntax.foldable.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.NativeScript as FlatNativeScript
import xyz.kd5ujc.scarab.ledger.state.EraTag

/** Allegra's evaluation of a native script (`evalTimelock`,
  * `eras/allegra/impl/src/Cardano/Ledger/Allegra/Scripts.hs:468-497`, cardano-ledger f649f975),
  * written once over the representation every era from Allegra to Conway shares ([[Timelock]]), as
  * the reference writes it once over `Timelock era`.
  */
object AllegraScripts:
  /** `evalTimelock` (`Allegra/Scripts.hs:477-497`, cardano-ledger f649f975): whether `timelock`
    * holds for the key hashes `vhks` of a transaction's key witnesses and its validity interval
    * `vi`. A validity start holds when the interval starts at or after it (`lteNegInfty`, false for
    * an unbounded start) and a validity end when the interval ends at or before it (`ltePosInfty`,
    * false for an unbounded end).
    *
    * It evaluates what the reference evaluates, in its order, and stops where it stops: all of a
    * list at the first script that fails and any of a list at the first that holds (`all go xs`,
    * `any go xs`, `Allegra/Scripts.hs:494-495`), and `m` of a list (`isValidMOf`,
    * `Allegra/Scripts.hs:487-489`) once `m` of its scripts hold, a non-positive `m` evaluating
    * none; `m` of a list whose scripts run out first does not hold. A script nests as deep as a
    * transaction is long, so the evaluation is deferred and run as a trampoline ([[Eval]]): its
    * stack is on the heap.
    */
  def evalTimelock(vhks: Set[Bytes], vi: ValidityInterval, timelock: Timelock): Boolean =
    import FlatNativeScript.{AllOf, AnyOf, InvalidBefore, InvalidHereafter, NOfK, Sig}
    // the scripts of `ts` from index `i` on: the quota is checked before the next is evaluated
    def isValidMOf(n: BigInt, ts: Vector[Timelock], i: Int): Eval[Boolean] =
      if n <= 0 then Eval.True
      else if i >= ts.size then Eval.False
      else go(ts(i)).flatMap(holds => isValidMOf(if holds then n - 1 else n, ts, i + 1))
    def go(t: Timelock): Eval[Boolean] = Eval.defer(
      t.script match
        case InvalidBefore(lockStart)  => Eval.now(vi.invalidBefore.exists(lockStart <= _))
        case InvalidHereafter(lockExp) => Eval.now(vi.invalidHereafter.exists(_ <= lockExp))
        case Sig(hash)                 => Eval.now(vhks.contains(hash))
        case AllOf(_)                  => t.children.forallM(go)
        case AnyOf(_)                  => t.children.existsM(go)
        case NOfK(m, _)                => isValidMOf(m, t.children, 0)
    )
    go(timelock).value

/** Allegra's transaction functions (`eras/allegra/impl/src/Cardano/Ledger/Allegra/Tx.hs`,
  * cardano-ledger f649f975).
  */
object AllegraTx:
  /** `validateTimelock` (`Allegra/Tx.hs:104-110`, cardano-ledger f649f975): the native script
    * evaluated (`evalTimelock`) for the key hashes of the transaction's key witnesses
    * (`witVKeyHash` of `addrTxWitsL`, the bootstrap witnesses not among them) and its body's
    * validity interval. The era's `validateNativeScript` gives its native script as the
    * representation (`NativeScript era ~ Timelock era`).
    */
  def validateTimelock[E <: EraTag, L <: TxLevel](tx: Tx[L, E], timelock: Timelock)(using
      t: EraTx[E] { val bodyOf: AllegraEraTxBody[E] },
      l: t.bodyOf.Level[L]
  ): Boolean =
    val vhks = t.witsOf.addrTxWits(t.wits[L](tx)).map(_.keyHash)
    AllegraScripts.evalTimelock(vhks, t.bodyOf.vldt[L](t.body[L](tx)), timelock)
