// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.byron

import xyz.kd5ujc.scarab.ledger.LedgerDecodeError
import cats.syntax.all.*
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Value}
import xyz.kd5ujc.scarab.ledger.{CborRead, TxIn}

/** A Byron transaction output: the original address encoding and the lovelace value. */
final case class ByronOut(address: Bytes, value: BigInt)

/** A Byron transaction: `[inputs, outputs, attributes]`, its id the Blake2b-256 of the encoding. */
final case class ByronTx(
    raw: Bytes,
    id: Bytes,
    inputs: Vector[TxIn],
    outputs: Vector[ByronOut],
    attributesLength: Int
)

/** How an input is authorised: a verification key (64-byte extended key) or a redeem key (32). */
enum ByronWitness:
  case VerKey(key: Bytes, signature: Bytes)
  case Redeem(key: Bytes, signature: Bytes)

/** `[tx, witnesses]` with the original encodings the proofs and the size rule cover. */
final case class ByronTxAux(
    raw: Bytes,
    tx: ByronTx,
    witnesses: Vector[ByronWitness],
    rawWitnesses: Bytes
)

object ByronTx:
  private val limits = ByronCbor.BodyLimits

  /** Two-element `[tag, tag24(bytes(cbor))]` wrappers ("known CBOR data item"). */
  private def knownItem(n: Node, what: String): Either[LedgerDecodeError, (Int, Node)] =
    for
      xs <- CborRead.arrayN(n, 2, what)
      tag <- CborRead.uint(xs(0), what).map(_.toInt)
      inner <- xs(1).value match
        case Value.Tag(t, i) if t == 24 =>
          CborRead.bytes(i, what).flatMap(b => CborRead.decode(b, limits))
        case _ => Left(LedgerDecodeError.ExpectedTag(what, 24, xs(1).value))
    yield tag -> inner

  private def input(n: Node): Either[LedgerDecodeError, TxIn] =
    knownItem(n, "Byron input").flatMap {
      case (0, inner) =>
        CborRead.arrayN(inner, 2, "Byron input").flatMap { xs =>
          (CborRead.bytesN(xs(0), 32, "Byron txid"), CborRead.uint(xs(1), "Byron index")).tupled
            .flatMap((id, ix) =>
              Either.cond(
                ix < 65536,
                TxIn(id, ix),
                LedgerDecodeError.NumberOutOfRange("Byron input index", ix, 0, 65535)
              )
            )
        }
      case (t, _) => Left(LedgerDecodeError.UnknownTag("Byron input", t))
    }

  private def output(n: Node): Either[LedgerDecodeError, ByronOut] =
    CborRead.arrayN(n, 2, "Byron output").flatMap { xs =>
      (
        Right(xs(0).original),
        CborRead
          .uint(xs(1), "Byron output value")
          .flatMap(n =>
            Either.cond(
              n <= ByronLedger.MaxLovelace,
              n,
              LedgerDecodeError.NumberOutOfRange("Byron lovelace", n, 0, ByronLedger.MaxLovelace)
            )
          )
      ).tupled.map(ByronOut(_, _))
    }

  def decodeTx(n: Node): Either[LedgerDecodeError, ByronTx] =
    for
      xs <- CborRead.arrayN(n, 3, "Byron tx")
      inputs <- CborRead.array(xs(0), "Byron inputs").flatMap(_.traverse(input))
      outputs <- CborRead.array(xs(1), "Byron outputs").flatMap(_.traverse(output))
      _ <- Either.cond(
        inputs.nonEmpty && outputs.nonEmpty,
        (),
        LedgerDecodeError.EmptyInputsOrOutputs
      )
      attrs <- ByronAddress.attributesLength(xs(2), "Byron tx attributes")
    yield ByronTx(n.original, Blake2b.hash256.hash(n.original), inputs, outputs, attrs)

  private def witness(n: Node): Either[LedgerDecodeError, ByronWitness] =
    knownItem(n, "Byron witness").flatMap { (tag, inner) =>
      CborRead.arrayN(inner, 2, "Byron witness").flatMap { xs =>
        val keySize = if tag == 0 then 64 else 32
        (
          CborRead.bytesN(xs(0), keySize, "Byron witness key"),
          CborRead.bytesN(xs(1), 64, "Byron witness signature")
        ).tupled.flatMap { (k, s) =>
          tag match
            case 0 => Right(ByronWitness.VerKey(k, s))
            case 2 => Right(ByronWitness.Redeem(k, s))
            case t => Left(LedgerDecodeError.UnknownTag("Byron witness", t))
        }
      }
    }

  def decodeAux(n: Node): Either[LedgerDecodeError, ByronTxAux] =
    for
      xs <- CborRead.arrayN(n, 2, "Byron TxAux")
      tx <- decodeTx(xs(0))
      ws <- CborRead.array(xs(1), "Byron witnesses").flatMap(_.traverse(witness))
    yield ByronTxAux(n.original, tx, ws, xs(1).original)

  /** The Merkle root of the transactions: leaves are `blake2b-256(0x00 || tx)` of the original
    * encodings, branches `blake2b-256(0x01 || left || right)`, split at the largest power of two
    * below the count; no transaction hashes the empty string.
    */
  def merkleRoot(txs: Vector[Bytes]): Bytes =
    def leaf(b: Bytes) = Blake2b.hash256.hash(ByronCbor.of(0) ++ b)
    def go(xs: Vector[Bytes]): Bytes =
      if xs.size == 1 then leaf(xs.head)
      else
        val i = Integer.highestOneBit(xs.size - 1)
        val (l, r) = xs.splitAt(i)
        Blake2b.hash256.hash(ByronCbor.concat(ByronCbor.of(1), go(l), go(r)))
    if txs.isEmpty then Blake2b.hash256.hash(Bytes.empty) else go(txs)
