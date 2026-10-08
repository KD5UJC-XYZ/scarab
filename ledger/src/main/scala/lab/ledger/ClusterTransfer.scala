// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.{Bytes, Cbor, Node, Value as CValue}
import lab.witness.{CardanoWitness, VerificationResult}

/** Reusable, pure, closed ADA transition under an explicit caller-attributed cluster context. Does
  * not relax RestrictedReplay or claim atomic reference acquisition/full ledger validity.
  */
object ClusterTransfer:
  val ProfileId = "conway-pv9-cluster-ada-transition-v1"
  final class Context private[ClusterTransfer] (
      val genesisDigest: Bytes,
      val parameterDigest: Bytes,
      val networkMagic: Long,
      val preHash: Bytes,
      val postHash: Bytes,
      val preSlot: BigInt,
      val postSlot: BigInt,
      val epoch: BigInt,
      val feesBefore: BigInt,
      val feesAfter: BigInt,
      val parameters: FeeSize.Parameters
  )
  object Context:
    def checked(
        genesisDigest: Bytes,
        parameterDigest: Bytes,
        networkMagic: Long,
        preHash: Bytes,
        postHash: Bytes,
        preSlot: BigInt,
        postSlot: BigInt,
        preEpoch: BigInt,
        postEpoch: BigInt,
        major: Int,
        minor: Int,
        feePerByte: BigInt,
        feeFixed: BigInt,
        maxTxSize: BigInt,
        feesBefore: BigInt,
        feesAfter: BigInt
    ): Either[String, Context] =
      for
        _ <- Either.cond(
          Vector(genesisDigest, parameterDigest, preHash, postHash).forall(_.size == 32),
          (),
          "context requires four 32-byte digests"
        )
        _ <- Either.cond(
          networkMagic > 0 && networkMagic <= 0xffffffffL && major == 9 && minor == 0,
          (),
          "unsupported cluster network or ledger protocol version"
        )
        _ <- Either.cond(
          preSlot >= 0 && postSlot > preSlot && preEpoch >= 0 && preEpoch == postEpoch &&
            feesBefore >= 0 && feesAfter >= feesBefore && maxTxSize > 0,
          (),
          "unsupported context: advancing same-epoch points and nondecreasing fee pot required"
        )
        parameters <- FeeSize.Parameters
          .create("Conway", major, feePerByte, feeFixed, maxTxSize)
          .left
          .map(_.toString)
      yield new Context(
        genesisDigest,
        parameterDigest,
        networkMagic,
        preHash,
        postHash,
        preSlot,
        postSlot,
        preEpoch,
        feesBefore,
        feesAfter,
        parameters
      )

  final case class Receipt(
      transactionId: Bytes,
      fee: BigInt,
      observedFeePotDelta: BigInt,
      spent: Set[TxIn],
      created: Set[TxIn],
      untouchedEntries: Int
  ):
    val profileId = ProfileId
    val fullLedgerValidated = false
    val referenceSnapshotAtomic = false

  private def bounded(raw: Bytes): Either[String, Node] =
    Cbor.decode(raw, Cbor.Limits(1048576, 16, 65536, 1048576))
  private def entries(raw: Bytes): Either[String, Map[TxIn, Node]] =
    bounded(raw).flatMap { root =>
      root.value match
        case CValue.Map(xs) if xs.size <= 4096 =>
          xs.foldLeft[Either[String, Map[TxIn, Node]]](Right(Map.empty)) {
            case (acc, (key, value)) =>
              for
                out <- acc
                ref <- key.value match
                  case CValue.Arr(Vector(id, index)) =>
                    (id.value, index.value) match
                      case (CValue.ByteString(b), CValue.UInt(n)) =>
                        TxIn.create(b, n).left.map(_.toString)
                      case _ => Left("malformed UTxO key")
                  case _ => Left("malformed UTxO key")
                _ <- Either.cond(!out.contains(ref), (), "duplicate UTxO key")
              yield out.updated(ref, value)
          }
        case _ => Left("bounded UTxO map required")
    }
  private def subset(values: Map[TxIn, Node]): Either[String, Bytes] =
    def node(value: CValue): Node = Node(value, Bytes.empty)
    Cbor.encode(CValue.Map(values.toVector.sortBy(_._1.toString).map { case (ref, value) =>
      node(
        CValue.Arr(Vector(node(CValue.ByteString(ref.id)), node(CValue.UInt(ref.index))))
      ) -> value
    }))

  def compare(context: Context, pre: Bytes, post: Bytes, original: Bytes): Either[String, Receipt] =
    for
      _ <- bounded(original)
      tx <- Coverage.decode(original).left.map(_.toString)
      before <- entries(pre)
      after <- entries(post)
      _ <- Either.cond(tx.inputs.subsetOf(before.keySet), (), "unresolved spending inputs")
      selectedRaw <- subset(before.view.filterKeys(tx.inputs.contains).toMap)
      selected <- Coverage.decodeResolved(selectedRaw).left.map(_.toString)
      _ <- Either.cond(
        (selected.values.toVector ++ tx.outputs).forall(o =>
          o.value.assets.isEmpty && o.address.size > 0 && (o.address.value.head & 15) == 0
        ),
        (),
        "unsupported non-ADA or non-testnet selected output"
      )
      coverage <- Coverage.check("Conway", 9, selected, tx).left.map(_.toString)
      _ <- Either.cond(coverage.covered, (), "missing required witness keys")
      _ <- tx.witnesses.foldLeft[Either[String, Unit]](Right(())) { (acc, witness) =>
        for
          _ <- acc
          result <- CardanoWitness.verifyVKeyWitness(tx.body, witness).left.map(_.toString)
          _ <- Either.cond(
            result == VerificationResult.SignatureVerified,
            (),
            "witness signature rejected"
          )
        yield ()
      }
      body <- Balance.decode(original).left.map(_.toString)
      balance <- Balance
        .check("Conway", 9, selected.view.mapValues(_.value).toMap, body)
        .left
        .map(_.toString)
      _ <- Either.cond(
        balance.isInstanceOf[BalanceResult.PredicateSatisfied],
        (),
        "value not conserved"
      )
      feeContext <- FeeSize.Context.decode(context.parameters, selectedRaw).left.map(_.toString)
      feeSize <- FeeSize.checkTransferFeeAndSize(feeContext, tx).left.map(_.toString)
      _ <- Either.cond(
        feeSize.fee.isInstanceOf[FeePredicate.Satisfied] &&
          feeSize.size.isInstanceOf[SizePredicate.Satisfied],
        (),
        "fee or size predicate failed"
      )
      created <- tx.outputs.zipWithIndex.foldLeft[Either[String, Map[TxIn, Coverage.Output]]](
        Right(Map.empty)
      ) { case (acc, (output, i)) =>
        for
          out <- acc
          ref <- TxIn.create(tx.body.hash.bytes, BigInt(i)).left.map(_.toString)
          _ <- Either.cond(!before.contains(ref), (), "output collision")
        yield out.updated(ref, output)
      }
      _ <- Either.cond(
        after.keySet == (before.keySet -- tx.inputs) ++ created.keySet,
        (),
        "reference UTxO identity delta differs"
      )
      _ <- Either.cond(
        (before.keySet -- tx.inputs).forall(k => before(k).original == after(k).original),
        (),
        "untouched reference UTxO bytes changed"
      )
      createdRaw <- subset(after.view.filterKeys(created.contains).toMap)
      observed <- Coverage.decodeResolved(createdRaw).left.map(_.toString)
      _ <- Either.cond(
        created.forall { (key, expected) =>
          observed(key).address == expected.address && observed(key).value == expected.value
        },
        (),
        "reference output value/address differs"
      )
      _ <- Either.cond(
        context.feesAfter - context.feesBefore == tx.fee,
        (),
        "observed reference fee-pot delta differs"
      )
    yield Receipt(
      tx.body.hash.bytes,
      tx.fee,
      context.feesAfter - context.feesBefore,
      tx.inputs,
      created.keySet,
      before.size - tx.inputs.size
    )
