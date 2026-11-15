// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

/** A value with the size in bytes of the encoding it was read from (`data Sized a`, cardano-ledger
  * f649f975 `libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/Sized.hs:31-36`): an
  * output in a body, whose size the minimum-coin rule measures.
  */
final case class Sized[A](value: A, size: Long)

object Sized:
  given [A](using CanEqual[A, A]): CanEqual[Sized[A], Sized[A]] = CanEqual.derived

/** The network a body names (`data Network = Testnet | Mainnet`, cardano-ledger f649f975
  * `libs/cardano-ledger-core/src/Cardano/Ledger/BaseTypes.hs:878-881`), by its wire value 0 or 1.
  */
enum Network:
  case Testnet, Mainnet

object Network:
  given CanEqual[Network, Network] = CanEqual.derived

  /** `word8ToNetwork`: 0 is Testnet and 1 is Mainnet. */
  def of(value: BigInt): Option[Network] =
    if value == 0 then Some(Testnet) else if value == 1 then Some(Mainnet) else None
