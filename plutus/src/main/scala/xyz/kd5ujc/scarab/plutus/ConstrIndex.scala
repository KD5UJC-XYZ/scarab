// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import scala.annotation.nowarn
import scalus.cardano.onchain.plutus.prelude.List as PList
import scalus.uplc.builtin.Data

/** The constructor index of `Data` as the reference's `constrData` takes it
  * (`PlutusCore/Default/Builtins.hs`, `ConstrData`, plutus 1.70.0.0 at 507cab8c, lines 1745-1760):
  * any `Integer` in semantics variants A to C, an unsigned 64-bit integer in the `ensurable`
  * variants D and E (lines 2788-2793), which the ledger selects from protocol 11 (`vanRossemPV`,
  * V1/V2/V3 `EvaluationContext.hs`).
  *
  * Scalus 1.4.0's (as 1.3.0's) `Data.Constr` asserts a non-negative index in its constructor
  * (`Data.scala:142`), so a negative index is built as [[ConstrIndex.Negative]]: a `Data.Constr`
  * whose `constr` accessor returns the index. Every reader of a `Data.Constr` in the pinned Scalus
  * goes through that accessor: the extractor (`unapply` returns the value, `_1` calls `constr`),
  * the synthesised `equals` and `hashCode` (through `productElement`), and the type tests of
  * `chooseData` and the memory usage, which ignore the index as the reference's
  * `ExMemoryUsage Data` does. Only `copy` and `Data.Constr.apply` would run the assertion again,
  * and no builtin calls them on an existing value. A value is a [[Negative]] exactly when its index
  * is negative, so equality between the two classes never has to compare a negative index with a
  * non-negative one.
  *
  * Negative indices arise only from `constrData`: the reference decodes the tag-102 index with
  * `decodeWord64` (`PlutusCore/Data.hs:300`), as does this node's `PlutusData` and Scalus's own
  * decoder.
  */
private[plutus] object ConstrIndex:
  private val Word64Max = (BigInt(1) << 64) - 1

  /** `Data.Constr(index, fields)` for an index of any sign. */
  def constr(index: BigInt, fields: PList[Data]): Data =
    if index >= 0 then Data.Constr(index, fields) else Negative(index, fields)

  /** Whether `constrData` takes the index in the `ensurable` variants: an unsigned 64-bit integer.
    */
  def word64(index: BigInt): Boolean = index >= 0 && index <= Word64Max

  /** A constructor with a negative index; the field the Scalus constructor checks holds zero.
    *
    * Two compiler diagnostics are silenced here, both about overriding the parameter val `constr`:
    * Scala 3 warns on it (3.3 as a deprecation, 3.9 as "cannot override"), and the initialisation
    * checker reads the assertion in the body of
    * `Data.Constr` as a use of the overridden, not yet initialised, val. The compiled Scalus 1.4.0
    * constructor tests its parameter, not the accessor (`javap` of `Data$Constr.<init>`: `aload_1`
    * before `BigInt.$greater$eq`), so the assertion sees zero. `ConstrIndexReferenceSuite` builds
    * these values and fails if a Scalus release reads the accessor there (the assertion would then
    * see `null`) or if a compiler refuses the override.
    */
  @nowarn(
    "msg=overriding val parameter|cannot override val parameter|Access non-initialized value constr"
  )
  final class Negative(index: BigInt, fields: PList[Data]) extends Data.Constr(BigInt(0), fields):
    override val constr: BigInt = index
