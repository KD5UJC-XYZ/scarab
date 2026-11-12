// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

/** The major component of the protocol version, as plutus receives it from the ledger: the
  * reference's `MajorProtocolVersion`, a newtype over `Int`
  * (`PlutusLedgerApi/Common/ProtocolVersions.hs:42-44`, plutus 1.70.0.0 `507cab8c`).
  */
opaque type ProtocolMajor = Int

object ProtocolMajor:
  def apply(major: Int): ProtocolMajor = major

  /** The names of `ProtocolVersions.hs:48-93`. */
  val Shelley: ProtocolMajor = 2
  val Allegra: ProtocolMajor = 3
  val Mary: ProtocolMajor = 4
  val Alonzo: ProtocolMajor = 5
  val Vasil: ProtocolMajor = 7
  val Valentine: ProtocolMajor = 8
  val Chang: ProtocolMajor = 9
  val Plomin: ProtocolMajor = 10
  val VanRossem: ProtocolMajor = 11
  val Dijkstra: ProtocolMajor = 12

  extension (p: ProtocolMajor) def value: Int = p

  given Ordering[ProtocolMajor] = Ordering.Int
