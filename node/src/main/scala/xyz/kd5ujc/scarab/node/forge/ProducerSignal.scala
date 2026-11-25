// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

/** What the minor protocol version of a forged header says about the software that forged it, under
  * CIP-0203's layout (cardano-foundation/CIPs#1276, `CIP-0203/README.md` "Bit layout of the minor
  * version"): `minor = (scheme << 30) | (payload << 8) | id`, scheme 0 the only one defined.
  *
  * A Deviation from the reference: cardano-node forges the minor of its declared protocol version
  * (`cardanoProtocolVersion`), the release minor within the major; Scarab forges the reference's
  * major with this minor instead (#1348). No rule of the ledger or of consensus reads the minor of
  * a header, only its major.
  */
enum ProducerSignal:
  /** Scarab's identifier, 73, requested in the registry (`CIP-0203/registry.json`,
    * cardano-foundation/CIPs#1334), with no payload: the entry
    * links no payload definition, so the payload is 0 ("Producer requirements").
    */
  case Identified

  /** The deliberate opt-out, identifier 255 ("Reserved identifiers"), for an operator who declines
    * to name the software.
    */
  case OptedOut

  /** The header's minor protocol version. */
  def minor: BigInt = this match
    case Identified => ProducerSignal.minor(ProducerSignal.Payload.None, ProducerSignal.Id.Scarab)
    case OptedOut   => ProducerSignal.minor(ProducerSignal.Payload.None, ProducerSignal.Id.OptOut)

object ProducerSignal:
  /** The default: CIP-0203 asks producers to signal unless the operator opts out. */
  val Default: ProducerSignal = Identified

  /** `--producer-id`'s values. */
  val byName: Map[String, ProducerSignal] = Map("scarab" -> Identified, "opt-out" -> OptedOut)

  /** An implementation identifier, the minor's low 8 bits. */
  opaque type Id = Int

  object Id:
    val Scarab: Id = 73
    val OptOut: Id = 255

    def from(n: Int): Option[Id] = Option.when(n >= 0 && n <= 0xff)(n)

  /** The implementation-defined payload, the minor's 22 bits above the identifier. */
  opaque type Payload = Int

  object Payload:
    val None: Payload = 0

    def from(n: Int): Option[Payload] = Option.when(n >= 0 && n <= 0x3fffff)(n)

  /** The minor of scheme 0 ("Construction and extraction"); the scheme bits are 0. */
  def minor(payload: Payload, id: Id): BigInt = BigInt((payload.toLong << 8) | id.toLong)
