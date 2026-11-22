// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import xyz.kd5ujc.scarab.ledger.{Phase2, Tx, TxIn}

/** UTxO read/write dependencies in block order. Reference inputs are reads, not consumes (CIP-31);
  * invalid transactions consume collateral and may create a collateral return (CIP-40). Non-script
  * transactions still transmit dependencies between script jobs.
  */
object TxDependencies:
  /** What a transaction does to the UTxO set, as the graph reads it: the inputs it reads (its
    * inputs, reference inputs and collateral), those it consumes and the inputs of the outputs it
    * creates, each by its validity flag. The flat rules' transactions ([[of]]) and the era rules'
    * give the same graph.
    */
  final case class Footprint(reads: Set[TxIn], consumes: Set[TxIn], creates: Vector[TxIn])

  object Footprint:
    /** A flat transaction's footprint. */
    def of(tx: Tx): Footprint =
      val body = tx.body
      Footprint(
        (body.inputs ++ body.referenceInputs ++ body.collateral).toSet,
        (if tx.isValid then body.inputs else body.collateral).toSet,
        if tx.isValid then TxIn.txouts(body.id, body.outputs).map(_._1)
        else TxIn.collOuts(body.id, body.outputs.size, body.collateralReturn).toVector.map(_._1)
      )

  private final case class Prefix(
      producers: Map[TxIn, Int],
      writers: Map[TxIn, Int],
      readers: Map[TxIn, Set[Int]],
      depths: Vector[Int]
  )

  def levels(txs: Vector[Tx], scriptIndices: Set[Int]): Vector[Int] =
    levelsOf(txs.map(Footprint.of), scriptIndices)

  /** The level of each transaction of `footprints`: one more than the largest of its ancestors' if
    * it is a script job's (`scriptIndices`), the largest otherwise.
    */
  def levelsOf(footprints: Vector[Footprint], scriptIndices: Set[Int]): Vector[Int] =
    footprints.zipWithIndex
      .foldLeft(Prefix(Map.empty, Map.empty, Map.empty, Vector.empty)) {
        case (s, (Footprint(reads, consumes, created), index)) =>
          val parents = reads.flatMap(in => s.producers.get(in).toSet ++ s.writers.get(in)) ++
            consumes.flatMap(in => s.readers.getOrElse(in, Set.empty))
          val depth = (if scriptIndices(index) then 1 else 0) + parents.iterator
            .map(s.depths)
            .maxOption
            .getOrElse(0)
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
    scheduled(txs.map(Footprint.of), jobs)(_.index)

  /** `jobs`, each at the place `index` gives it among the block's transactions of `footprints`, in
    * batches of one level, in level order, each in the jobs' order.
    */
  def scheduled[J](footprints: Vector[Footprint], jobs: Vector[J])(
      index: J => Int
  ): Vector[Vector[J]] =
    val depths = levelsOf(footprints, jobs.iterator.map(index).toSet)
    jobs.groupBy(j => depths(index(j))).toVector.sortBy(_._1).map(_._2)
