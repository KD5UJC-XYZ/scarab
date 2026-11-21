// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.DomainError

/** Why the LeiosFetch server ends the connection instead of answering a request
  * (`leiosFetchHandler`, `msgLeiosBlockRequest` and `msgLeiosBlockTxsRequest`, `LeiosDemoLogic.hs`
  * 246-307 of ouroboros-consensus 2416493b, which throw these and so end the mini-protocol, and
  * with it the connection; the first two are `ExnLeiosInvalidRequest`, `:1950-1958`).
  */
enum LeiosFetchRefusal:
  /** `ExnLeiosUnknownBlockRequested`: the store holds no body of the point's endorser block. */
  case UnknownBlockRequested(point: EbPoint)

  /** `ExnLeiosUnknownTxsRequested`: an offset the request names is not in the body, or its
    * transaction is not written yet.
    */
  case UnknownTxsRequested(point: EbPoint, offsets: Vector[Int])

  /** `throwLeiosDbException`: the stored body does not decode. */
  case StoredBodyUndecodable(point: EbPoint, cause: DomainError)

  /** The stored body names more transactions than an endorser block may (`maxTxsPerEb`): the
    * reference's decoder of the body refuses it, as a database an older build wrote can hold.
    */
  case StoredBodyTooLarge(point: EbPoint, entries: Int)

  /** The store failed (the reference's `LeiosDbException`, traced as `TraceLeiosPeerDbException`).
    */
  case Store(cause: DomainError)

  def message: String = this match
    case UnknownBlockRequested(p) => s"the endorser block ${LeiosFetchRefusal.text(p)} is not held"
    case UnknownTxsRequested(p, offsets) =>
      s"the endorser block ${LeiosFetchRefusal.text(p)} has no transaction at ${offsets.mkString(", ")}"
    case StoredBodyUndecodable(p, cause) =>
      s"the stored body of ${LeiosFetchRefusal.text(p)} does not decode: $cause"
    case StoredBodyTooLarge(p, n) =>
      s"the stored body of ${LeiosFetchRefusal.text(p)} names $n transactions, more than an endorser block may"
    case Store(cause) => s"the endorser-block store failed: $cause"

object LeiosFetchRefusal:
  private def text(p: EbPoint): String = s"${p.hash.bytes.hex} at slot ${p.slot.toBigInt}"
