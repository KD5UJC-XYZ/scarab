// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes

import ProtocolWire.{attempt, bad}

/** A [[TxSubmission2.TransactionReader]] for the suites of the protocols, which have no ledger: the
  * transaction `[body, witnesses, flag, aux]`, definite or indefinite, read as a tree of items. It
  * does not read a `RecD` record written as an empty indefinite list as the reference does; the
  * node's reader (`RelayedTransactions`) does, and its suite covers those.
  */
object TxSubmission2Fixtures:
  private val wire = ProtocolWire.Limits(
    TxSubmission2.MaxOriginalBytes,
    TxSubmission2.MaxOriginalBytes,
    32,
    ProtocolWire.MaxItems,
    ProtocolWire.MaxItems
  )

  given structural: TxSubmission2.TransactionReader with
    def body(original: Bytes): Either[ProtocolError, Bytes] = attempt {
      val r = new ProtocolWire.Reader(original, wire)
      val indefinite = original.headOption.contains(0x9f.toByte)
      val declared = if indefinite then { r.read(); None }
      else Some(r.arrayLength())
      if declared.exists(n => n != 3 && n != 4) then
        bad(ProtocolError.ExpectedConwayTransactionMembers)
      val body = r.item()
      if !body.headOption.exists(b => (b & 0xe0) == 0xa0) then
        bad(ProtocolError.TransactionBodyIsNotAMap)
      r.item()
      val third = original.byteOr(r.position)(bad(ProtocolError.TruncatedCbor))(_ & 255)
      val flagged = third == 0xf4 || third == 0xf5
      if flagged then r.read()
      r.item()
      if declared.exists(_ != (if flagged then 4 else 3)) then
        bad(ProtocolError.ExpectedConwayTransactionMembers)
      if indefinite && r.read() != 0xff then bad(ProtocolError.ExpectedConwayTransactionMembers)
      if r.position != original.size then bad(ProtocolError.TrailingPayloadBytes)
      body
    }
