// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.byron

import xyz.kd5ujc.scarab.ledger.DecodeField
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{CborRead, Header}

/** `abobMatchesBody` hashes annotated payload bytes, without applying ledger predicates.
  * cardano-ledger f649f975, Byron/API/Common.hs:145–161 and Chain/Block/Proof.hs. The SSC proof is
  * deliberately absent from the native predicate.
  */
private[ledger] object ByronBodyProof:
  def check(header: Header.Byron, body: Bytes): Either[ByronError, Unit] =
    val decoded = for
      proof <- CborRead.decode(header.bodyProof)
      fields <- CborRead.arrayN(proof, 4, DecodeField.BodyProof)
      txProof <- CborRead.arrayN(fields(0), 3, DecodeField.TxProof)
      count <- CborRead.uint(txProof(0), DecodeField.TxCount)
      root <- CborRead.bytes(txProof(1), DecodeField.TxRoot)
      witnesses <- CborRead.bytes(txProof(2), DecodeField.WitnessesHash)
      delegation <- CborRead.bytes(fields(2), DecodeField.DelegationProof)
      update <- CborRead.bytes(fields(3), DecodeField.UpdateProof)
      node <- CborRead.decode(body, ByronCbor.BodyLimits)
      payloads <- CborRead.arrayN(node, 4, DecodeField.ByronBody)
      txs <- CborRead.array(payloads(0), DecodeField.ByronTxPayload)
      pairs <- txs.traverse(CborRead.arrayN(_, 2, DecodeField.ByronTxAuxiliary))
    yield (count, root, witnesses, delegation, update, payloads, pairs)
    decoded.leftMap(ByronError.Decode(_)).flatMap {
      (count, root, witnesses, delegation, update, payloads, pairs) =>
        val rawWitnesses = Bytes(
          Vector(0x9f.toByte) ++ pairs.flatMap(_(1).original.value) :+ 0xff.toByte
        )
        for
          _ <- Either.cond(
            delegation == Blake2b.hash256.hash(payloads(2).original),
            (),
            ByronError.ProofMismatch(ByronError.Proof.Delegation)
          )
          _ <- Either.cond(
            count == pairs.size && root == ByronTx.merkleRoot(pairs.map(_(0).original)) &&
              witnesses == Blake2b.hash256.hash(rawWitnesses),
            (),
            ByronError.ProofMismatch(ByronError.Proof.Transaction)
          )
          _ <- Either.cond(
            update == Blake2b.hash256.hash(payloads(3).original),
            (),
            ByronError.ProofMismatch(ByronError.Proof.Update)
          )
        yield ()
    }
