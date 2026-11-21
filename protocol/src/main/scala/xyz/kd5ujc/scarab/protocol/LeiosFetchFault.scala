// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word32

/** Why the LeiosFetch client ends the connection over a peer's answer (`processLeiosBlock` and
  * `processLeiosBlockTxs`, `LeiosDemoLogic.hs` 849-1172 and 1395-1515 of ouroboros-consensus
  * 2416493b, whose thread death disconnects the peer). The reference raises the answers that are
  * not what was asked for with `error` (`invalidReply`), and the two bodies that hash to the
  * endorser block asked for and are still refused with `ExnLeiosWellHashedBodyRejected`
  * (`:1960-1985`), once the body is accounted for.
  */
enum LeiosFetchFault:
  /** `MsgLeiosBlock hash mismatch`: the body does not hash to the endorser block asked for. */
  case BodyHashMismatch(point: EbPoint, received: EbHash)

  /** `MsgLeiosBlock duplicate tx hashes`: the body lists a transaction hash at two offsets, in the
    * hashes' order.
    */
  case DuplicateTxHashes(point: EbPoint, hashes: Vector[LeiosTxHash])

  /** A body that hashes to the endorser block asked for and that the store cannot hold (none
    * listed, or a hash that is not 32 bytes): the wire's decoder admits neither, and no such body
    * hashes to a requested point, so this is never met.
    */
  case BodyNotStorable(point: EbPoint, cause: DomainError)

  /** `MsgLeiosBlockTxs count mismatch`: another number of transactions than the request names. */
  case TxsCountMismatch(point: EbPoint, received: Int, requested: Int)

  /** `MsgLeiosBlockTxs job … count mismatch` (`checkJobSize`). */
  case JobCountMismatch(point: EbPoint, job: Int)

  /** `MsgLeiosBlockTxs job … byte-size mismatch` (`checkJobSize`). */
  case JobByteSizeMismatch(point: EbPoint, job: Int)

  /** `MsgLeiosBlockTxs job … root-hash mismatch` (`ingestJob`). */
  case JobRootHashMismatch(point: EbPoint, job: Int)

  /** `ExnLeiosBlockWrongSize`: the body is not of the size the peer offered it at. */
  case BlockWrongSize(point: EbPoint, offered: Word32, sent: Word32)

  /** `ExnLeiosClosureTooBig`: the body's transactions sum to more bytes than the closure's bound,
    * the sum a `Word64` as the reference's (at most `maxTxsPerEb` sizes of 32 bits each).
    */
  case ClosureTooBig(point: EbPoint, referenced: Long, bound: Word32)

  def message: String = this match
    case BodyHashMismatch(p, h) =>
      s"the body sent for ${LeiosFetchFault.text(p)} hashes to ${h.bytes.hex}"
    case DuplicateTxHashes(p, hs) =>
      s"the body of ${LeiosFetchFault.text(p)} lists ${hs.map(_.bytes.hex).mkString(", ")} twice"
    case BodyNotStorable(p, cause) => s"the body of ${LeiosFetchFault.text(p)}: $cause"
    case TxsCountMismatch(p, got, asked) =>
      s"$got transactions sent of ${LeiosFetchFault.text(p)} for $asked requested"
    case JobCountMismatch(p, j) =>
      s"the transactions of job $j of ${LeiosFetchFault.text(p)} are not as many as the job's"
    case JobByteSizeMismatch(p, j) =>
      s"the transactions of job $j of ${LeiosFetchFault.text(p)} are not of the job's size"
    case JobRootHashMismatch(p, j) =>
      s"the transactions of job $j of ${LeiosFetchFault.text(p)} are not the ones it commits to"
    case BlockWrongSize(p, offered, sent) =>
      s"the body of ${LeiosFetchFault.text(p)} is ${sent.toLong} bytes, offered at ${offered.toLong}"
    case ClosureTooBig(p, referenced, bound) =>
      s"the body of ${LeiosFetchFault.text(p)} references $referenced bytes, more than ${bound.toLong}"

object LeiosFetchFault:
  private def text(p: EbPoint): String = s"${p.hash.bytes.hex} at slot ${p.slot.toBigInt}"
