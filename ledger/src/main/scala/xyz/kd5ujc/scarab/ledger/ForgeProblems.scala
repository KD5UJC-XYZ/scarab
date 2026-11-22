// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import io.circe.{ParsingFailure, DecodingFailure}
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor as BinaryCbor, Value}
import xyz.kd5ujc.scarab.crypto.vrf.Draft03Prover
import xyz.kd5ujc.scarab.ledger.state.{MirrorError, VocabError}
import xyz.kd5ujc.scarab.ledger.rules.TickFailure
import xyz.kd5ujc.scarab.protocol.Era

enum ForgeKeyProblem:
  case Json(cause: ParsingFailure)
  case Field(name: String, cause: DecodingFailure)
  case Hex(cause: Bytes.HexError)
  case EnvelopeType(expected: String, actual: String)
  case Cbor(cause: BinaryCbor.Error)
  case ExpectedByteString(actual: KeyShape)
  case Vrf(cause: Draft03Prover.Failure)

  /** A voting key of `--shelley-bls-key` is not a BLS12-381 scalar of the group. */
  case Bls(cause: xyz.kd5ujc.scarab.crypto.bls.BlsError)
  case CertificateEnvelope(actual: KeyShape)
  case CertificateFields(actual: KeyShape)
  case FixedBytes(expected: Int, actual: KeyShape)
  case UnsignedInteger(actual: KeyShape)
  case Io(cause: Throwable)

  /** The file gives its "other" class some access (`mode & 0o007 != 0`): refused, as
    * `checkVRFFilePermissions` refuses it for the VRF key (cardano-node 11.1.3, `Cardano.Node.Run`,
    * `OtherPermissionsExist`).
    */
  case OtherPermissions(mode: Int)

  /** Another running node holds the single-instance lock of the KES key file. */
  case InstanceLocked(lock: String)

  def message: String = this match
    case OtherPermissions(mode) =>
      s"has \"other\" file permissions (mode ${ForgeKeyProblem.octal(mode)}); remove all \"other\" file permissions"
    case InstanceLocked(lock) =>
      s"another instance holds the key lock $lock; stop it, or start this node with --key-lock off"
    case other => other.toString

object ForgeKeyProblem:
  /** The permission bits of a mode as three octal digits. */
  def octal(mode: Int): String = "%03o".format(mode & 0x1ff)

enum ForgeAssemblyProblem:
  case Cbor(cause: BinaryCbor.Error)

  /** A held transaction whose parts are not read again ([[Tx.envelopeParts]]). */
  case Envelope(id: Bytes, cause: LedgerDecodeError)

/** A check of the block just built that only a bug of ours can fail. The slot ends without a block,
  * the block's transactions leave the mempool, and the node goes on (owner decision of 2026-11-14,
  * #987). The released reference has no such check: its selection bounds the body, and a block its
  * own rules refuse is traced and its transactions evicted (`TraceForgedInvalidBlock`,
  * ouroboros-consensus 82ecba329 `NodeKernel.hs` 757-787).
  */
enum ForgeDefect:
  /** The body is larger than the maximum, though the transaction selection bounds it. */
  case BodySize(actual: Long, maximum: BigInt)

  /** The block just built does not decode. */
  case BlockDecode(cause: ChainError)

  /** The endorser block forged with the block encodes to more bytes than a LeiosFetch message
    * carries (`maxLeiosEbBytesSize`), though its capacity plans within them: the reference's
    * `assert` in `mkEb` (ouroboros-consensus 2416493b `Shelley/Ledger/Forge.hs:189-197`).
    */
  case EndorserBlockSize(actual: Long, maximum: Int)

/** Why the forger cannot carry out the certification it decided on: each is the reference's
  * `error`, a defect of the node's own that stops it, never a verdict on a block
  * (ouroboros-consensus 2416493b `NodeKernel/Forge.hs`).
  */
enum ForgeCertifyProblem:
  /** A block that certifies would extend genesis (`decideLeiosCertify` 's `error`,
    * `NodeKernel/Forge.hs:366`, ouroboros-consensus 2416493b), unreachable, since the announcing
    * block is the parent.
    */
  case OnGenesis

  /** The parent's hash is not the width of a ranking block's (`toRawHash`,
    * `NodeKernel/Forge.hs:368-370`, ouroboros-consensus 2416493b).
    */
  case TipHashWidth(size: Int)

  /** The certification gap of the parent's parameters (`getMinCertificationGap`,
    * `Shelley/Ledger/Ledger.hs:969-973`, ouroboros-consensus 2416493b) cannot be computed: a slot
    * length of no millisecond.
    */
  case Gap(cause: Praos2Defect)

  /** The certified endorser block's closure does not apply to the parent
    * (`resolveAndApplyLeiosClosure`, `NodeKernel/Forge.hs:827-835`, ouroboros-consensus 2416493b):
    * the reference's `error` "applyLeiosClosure failed" (`:837-841`), since a quorum of the
    * committee applied it before it voted.
    */
  case ClosureRefused(eb: xyz.kd5ujc.scarab.protocol.EbHash, failure: BlockFailure)

enum ForgeLedgerProblem:
  case Vocabulary(cause: VocabError)
  case Tick(cause: TickFailure)
  case EpochPrecedes(epoch: BigInt, ledgerEpoch: BigInt)

enum ForgeProtocolProblem:
  case Version(actual: BigInt)
  case LeftConway

  /** A Dijkstra state ticked out of Dijkstra, the last era of the Leios build: a defect. */
  case LeftDijkstra
  case InByron
  case Earlier(era: Era)
  case Mirror(cause: MirrorError)

  /** The parameters' `maxEndorserBlockReferencesSize` is more than a LeiosFetch message carries
    * (`wire`, `maxLeiosEbBytesSize`): `guardLeiosWireLimit` (ouroboros-consensus 2416493b
    * `Shelley/Ledger/Mempool.hs:915-939`) fails hard on it, and the node stops.
    */
  case EndorserBlockReferencesOverWire(parameter: BigInt, wire: Int)

/** Describe malformed key fields without retaining their contents. */
enum KeyShape:
  case Unsigned, Negative, Text, Boolean, Float, Null
  case Simple(value: Int)
  case Bytes(length: Int)
  case Array(length: Int)
  case Map(length: Int)
  case Tag(number: BigInt)

object KeyShape:
  def of(value: Value): KeyShape = value match
    case Value.UInt(_)           => KeyShape.Unsigned
    case Value.NInt(_)           => KeyShape.Negative
    case Value.ByteString(bytes) => KeyShape.Bytes(bytes.size)
    case Value.Text(_)           => KeyShape.Text
    case Value.Bool(_)           => KeyShape.Boolean
    case Value.Float(_, _)       => KeyShape.Float
    case Value.Null              => KeyShape.Null
    case Value.Simple(n)         => KeyShape.Simple(n)
    case Value.Arr(items)        => KeyShape.Array(items.size)
    case Value.Map(items)        => KeyShape.Map(items.size)
    case Value.Tag(number, _)    => KeyShape.Tag(number)
