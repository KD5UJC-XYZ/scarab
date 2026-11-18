// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Value}
import xyz.kd5ujc.scarab.ledger.{DecodeField, LedgerDecodeError, TransactionDecodeProblem}
import xyz.kd5ujc.scarab.ledger.DecodeSite.Qualifier
import xyz.kd5ujc.scarab.ledger.PreprodDeepTx.onSmallStack
import xyz.kd5ujc.scarab.network.LocalTxSubmissionServer
import xyz.kd5ujc.scarab.node.ntc.PredicateFailureCbor
import xyz.kd5ujc.scarab.protocol.Era

import ByronPayloads.*

/** A submitted Byron `MempoolPayload` read whole by the decoder of its kind, as the reference reads
  * it (`decodeByronGenTx`, `fromByronCBOR` of `AMempoolPayload ByteSpan`, cardano-ledger f649f975
  * `eras/byron/ledger/impl/src/Cardano/Chain/MempoolPayload.hs:85-93`): a payload it reads gets the
  * era mismatch, the pool's ledger being of a later era, and one it refuses is malformed, so the
  * connection ends with no reply (#924).
  */
class ByronSubmissionSuite extends FunSuite:
  private def read(message: Bytes): Either[Rejection, MempoolState.Read] =
    LocalSubmission.unwrap(message).flatMap(MempoolState.read)

  /** Why the decoder of the payload's kind refuses `message`. */
  private def cause(message: Bytes): LedgerDecodeError = read(message) match
    case Left(Rejection.Malformed(TransactionDecodeProblem.ByronPayload(e))) => e
    case other => fail(s"not refused by Byron's decoder: $other")

  test("a recorded payload of each kind is read, and gets the era mismatch") {
    valid.foreach { (name, payload) =>
      assertEquals(read(genTx(payload)), Right(MempoolState.Read.Foreign(0)), name)
      assertEquals(
        LocalSubmission.response(Refused(Rejection.WrongEra(0, Era.Conway), Some(Era.Conway))),
        LocalTxSubmissionServer.Response.Rejected(
          PredicateFailureCbor.eraMismatch(0, "Byron", Era.Conway)
        ),
        name
      )
    }
  }

  // each kind's decoder refuses these past the envelope: a transaction of three items (`enforceSize
  // "TxAux" 2`), a certificate whose epoch is negative (`Word64`), a proposal whose protocol
  // version's third number is 256 (`Word8`) and a vote whose decision is not a `Bool`
  private val malformed: Vector[(String, Bytes, LedgerDecodeError => Boolean)] = Vector(
    (
      "transaction of three items",
      submitted(
        0,
        Bytes(Vector(0x83.toByte)) ++ items(keyTx).map(_.original).reduce(_ ++ _) ++
          Bytes(Vector(0x00.toByte))
      ),
      _ == LedgerDecodeError.ArrayLength(DecodeField.TxAux, 2, 3)
    ),
    (
      "certificate of a negative epoch",
      submitted(1, replacing(certificate, 0, Bytes(Vector(0x20.toByte)))),
      {
        case LedgerDecodeError.Expected(
              DecodeField.DelegationEpoch,
              LedgerDecodeError.Shape.Unsigned,
              Value.NInt(_)
            ) =>
          true
        case _ => false
      }
    ),
    (
      "proposal of protocol alt 256",
      submitted(
        2,
        replacing(proposal, 0, Bytes(Vector(0x83, 0x01, 0x00, 0x19, 0x01, 0x00).map(_.toByte)))
      ),
      _ == LedgerDecodeError.NumberOutOfRange(DecodeField.ProtocolAlternative, 256, 0, 255)
    ),
    (
      "vote whose decision is not a Bool",
      submitted(3, replacing(vote, 2, Bytes(Vector(0x01.toByte)))),
      _ == LedgerDecodeError.Expected(
        DecodeField.VoteDecision,
        LedgerDecodeError.Shape.Boolean,
        Value.UInt(1)
      )
    )
  )

  malformed.foreach { (name, message, expected) =>
    test(s"a payload its kind's decoder refuses past the envelope is malformed: $name") {
      val refusal = cause(message)
      assert(expected(refusal), s"$name: $refusal")
    }
  }

  test("a payload the decoder refuses has no reply: the connection ends") {
    malformed.foreach { (name, message, _) =>
      read(message) match
        case Left(rejection) =>
          assertEquals(
            LocalSubmission.response(Refused(rejection, None)),
            LocalTxSubmissionServer.Response.Undecodable(
              TransactionDecodeProblem.ByronPayload(cause(message))
            ),
            name
          )
        case Right(read) => fail(s"$name: read as $read")
    }
  }

  // `VerificationKey` is read with `decodeBytesCanonical` (`decCBORXPub`, cardano-ledger f649f975
  // `eras/byron/crypto/src/Cardano/Crypto/Signing/VerificationKey.hs:96-97`): a key whose length
  // is written in two bytes is refused wherever one is read
  private def wideKey(field: DecodeField, key: Node) =
    LedgerDecodeError.Expected(
      field / Qualifier.Canonical,
      LedgerDecodeError.Shape.Bytes,
      key.value
    )

  private val wideKeys: Vector[(String, Bytes, LedgerDecodeError)] =
    val witnessKey = witnesses(keyTx)(0)._2
    Vector(
      (
        "transaction's witness key",
        submitted(0, rewitnessed(keyTx)(wide, _.original)),
        wideKey(DecodeField.WitnessKey, witnessKey)
      ),
      (
        "certificate's issuer",
        submitted(1, replacing(certificate, 1, wide(items(certificate)(1)))),
        wideKey(DecodeField.DelegationKeySignature, items(certificate)(1))
      ),
      (
        "certificate's delegate",
        submitted(1, replacing(certificate, 2, wide(items(certificate)(2)))),
        wideKey(DecodeField.DelegationKeySignature, items(certificate)(2))
      ),
      (
        "proposal's issuer",
        submitted(2, replacing(proposal, 5, wide(items(proposal)(5)))),
        wideKey(DecodeField.ProposalIssuer, items(proposal)(5))
      ),
      (
        "vote's voter",
        submitted(3, replacing(vote, 0, wide(items(vote)(0)))),
        wideKey(DecodeField.Voter, items(vote)(0))
      )
    )

  wideKeys.foreach { (name, message, expected) =>
    test(s"a verification key whose length is not in its shortest form is refused: $name") {
      assertEquals(cause(message), expected, name)
    }
  }

  // a `Signature`, a redeem key and a redeem signature are read with plain `decodeBytes`
  // (`Signing/Signature.hs:129-130`, `Crypto/Orphans.hs:89-92, 111-114`), and so is a hash
  // (`Hashing.hs:157-165`), cardano-ledger f649f975: any width of length
  test("a signature, a redeem key or a hash whose length is not in its shortest form is read") {
    Vector(
      "transaction's witness signature" -> submitted(0, rewitnessed(keyTx)(_.original, wide)),
      "redeem key and signature" -> submitted(0, rewitnessed(redeemTx)(wide, wide)),
      "certificate's signature" -> submitted(
        1,
        replacing(certificate, 3, wide(items(certificate)(3)))
      ),
      "proposal's signature" -> submitted(2, replacing(proposal, 6, wide(items(proposal)(6)))),
      "vote's proposal id and signature" -> submitted(
        3,
        list(
          items(vote)(0).original,
          wide(items(vote)(1)),
          items(vote)(2).original,
          wide(items(vote)(3))
        )
      )
    ).foreach((name, message) =>
      assertEquals(read(message), Right(MempoolState.Read.Foreign(0)), name)
    )
  }

  test("a payload nested deeper than a small stack recurses is refused on one") {
    // a vote whose proposal id is a list 200,000 levels deep
    val deep = submitted(3, replacing(vote, 1, nested(200000)))
    val refusal = onSmallStack(read(deep) match
      case Left(Rejection.Malformed(TransactionDecodeProblem.ByronPayload(e))) =>
        Some((e, TransactionDecodeProblem.ByronPayload(e).message))
      case _ => None)
    refusal match
      case Some(
            (
              LedgerDecodeError.Expected(DecodeField.ByronBytes, LedgerDecodeError.Shape.Bytes, _),
              _
            )
          ) =>
        ()
      case other => fail(s"a deep vote: ${other.map(_._2)}")
  }
