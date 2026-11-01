// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

/** Development tool: the time of [[Tree.equal]] and [[Tree.hash]] over the decoded blocks of
  * [[CborCorpus]] (issue 650), the walk that structural equality and hashing of a value (and Plutus
  * `equalsData`) use. Each block is decoded twice, so the two roots are equal and share nothing.
  *
  * `java -cp <codec test classpath> xyz.kd5ujc.scarab.codec.TreeBench [rounds]`
  */
object TreeBench:
  def main(args: Array[String]): Unit =
    val rounds = args.headOption.flatMap(_.toIntOption).getOrElse(30)
    def decoded(b: Bytes): Node =
      Cbor.decode(b, CborCorpus.Blocks).fold(e => sys.error(e.message), identity)
    val pairs = CborCorpus.all.map(b => (decoded(b), decoded(b)))
    def pass(): (Int, Int) =
      pairs.foldLeft((0, 0)) { case ((equal, hash), (a, b)) =>
        (equal + (if a == b then 1 else 0), hash ^ a.hashCode)
      }
    (1 to 5).foreach(_ => pass())
    val samples = (1 to rounds).map { _ =>
      val start = System.nanoTime()
      val (equal, hash) = pass()
      (System.nanoTime() - start, equal, hash)
    }
    val ns = samples.map(_._1).sorted
    println(
      f"METRIC tree median_ns=${ns(ns.size / 2)} min_ns=${ns.head} equal=${samples.map(_._2).distinct.mkString(",")} hash=${samples.map(_._3).distinct.mkString(",")}"
    )
