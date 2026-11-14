// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.Eq
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.ExUnits

/** BBODY predicate failures, retaining the native fields rather than a first-failure message.
  * `cardano-ledger f649f975`, `Shelley/Rules/Bbody.hs:88-96`, `Alonzo/Rules/Bbody.hs:61-63`,
  * `Conway/Rules/Bbody.hs:96-105`. LEDGERS' `LedgerFailure` newtype
  * (`Shelley/Rules/Ledgers.hs:89-90`) carries no transaction identifier or position: its payload is
  * the transaction predicate failure itself.
  */
enum BbodyFailure:
  case WrongBlockBodySizeBBODY(mismatch: Mismatch[BigInt])
  case InvalidBodyHashBBODY(mismatch: Mismatch[Bytes])
  case LedgersFailure(failure: PredicateFailure)
  case TooManyExUnits(mismatch: Mismatch[ExUnits])
  case BodyRefScriptsSizeTooBig(mismatch: Mismatch[Long])
  case HeaderProtVerTooHigh(mismatch: Mismatch[BigInt])

object BbodyFailure:
  given Eq[BbodyFailure] = Eq.fromUniversalEquals
