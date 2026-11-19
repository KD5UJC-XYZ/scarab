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
  * `libs/cardano-ledger-core/src/Cardano/Ledger/BaseTypes.hs:878-881`), defined with the state's
  * vocabulary ([[xyz.kd5ujc.scarab.ledger.state.Network]]), which an account address holds.
  */
export xyz.kd5ujc.scarab.ledger.state.Network
