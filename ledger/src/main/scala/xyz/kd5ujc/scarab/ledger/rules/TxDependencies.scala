// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import xyz.kd5ujc.scarab.ledger.{Phase2, Tx, TxIn}

/** UTxO read/write dependencies in block order. Reference inputs are reads, not consumes (CIP-31);
  * invalid transactions consume collateral and may create a collateral return (CIP-40). Non-script
  * transactions still transmit dependencies between script jobs.
  */
object TxDependencies:
  private final case class Prefix(
      producers: Map[TxIn, Int],
      writers: Map[TxIn, Int],
      readers: Map[TxIn, Set[Int]],
      depths: Vector[Int]
  )

  def levels(txs: Vector[Tx], scriptIndices: Set[Int]): Vector[Int] =
    txs.zipWithIndex
      .foldLeft(Prefix(Map.empty, Map.empty, Map.empty, Vector.empty)) { case (s, (tx, index)) =>
        val body = tx.body
        val reads = (body.inputs ++ body.referenceInputs ++ body.collateral).toSet
        val consumes = (if tx.isValid then body.inputs else body.collateral).toSet
        val parents = reads.flatMap(in => s.producers.get(in).toSet ++ s.writers.get(in)) ++
          consumes.flatMap(in => s.readers.getOrElse(in, Set.empty))
        val depth = (if scriptIndices(index) then 1 else 0) + parents.iterator
          .map(s.depths)
          .maxOption
          .getOrElse(0)
        val created =
          if tx.isValid then body.outputs.indices.map(TxIn(body.id, _)).toVector
          else body.collateralReturn.toVector.map(_ => TxIn(body.id, body.outputs.size))
        val read =
          reads.foldLeft(s.readers)((m, in) => m.updated(in, m.getOrElse(in, Set.empty) + index))
        Prefix(
          s.producers ++ created.map(_ -> index),
          s.writers ++ consumes.iterator.map(_ -> index),
          read -- consumes,
          s.depths :+ depth
        )
      }
      .depths

  def batches(txs: Vector[Tx], jobs: Vector[Phase2.Job]): Vector[Vector[Phase2.Job]] =
    val depths = levels(txs, jobs.iterator.map(_.index).toSet)
    jobs.groupBy(j => depths(j.index)).toVector.sortBy(_._1).map(_._2)
