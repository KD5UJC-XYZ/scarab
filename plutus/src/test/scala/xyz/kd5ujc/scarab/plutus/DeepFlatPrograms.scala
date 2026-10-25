// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

/** Flat programs of version 1.0.0 deep enough that a decoder recursing on the call stack overflows
  * a small one (ADR 0011). Shared by [[FlatScanDepthSuite]], [[FlatScanFunctionalSuite]] and the
  * flat decoding benchmark.
  */
object DeepFlatPrograms:
  val DataLevels: Int = FlatDecodeCorpus.NearSizeBound
  val Delays = 100000
  val Spine = 50000
  val ListTypes = 5000

  /** Flat bytes of version 1.0.0 with `write` writing the term. */
  private def program(write: FlatProgram.Bits => Unit): Array[Byte] =
    val b = FlatProgram.Bits()
    (0 until 3).foreach(i => b.natural(if i == 0 then 1 else 0))
    write(b)
    b.bytes

  private def constant(term: FlatProgram.Tm): Array[Byte] = program(b =>
    term match
      case FlatProgram.Const(types, value) =>
        b.bits(4, 4)
        types.foreach { tag =>
          b.bit(1); b.bits(tag, 4)
        }
        b.bit(0)
        value(b)
      case _ => ()
  )

  /** A `data` constant of [[DataLevels]] nested one-element lists. */
  lazy val deepData: Array[Byte] =
    constant(FlatProgram.data(FlatDecodeCorpus.nestedList(DataLevels)))

  /** [[Delays]] nested `delay`s around `error`. */
  lazy val nestedDelays: Array[Byte] = program { b =>
    (0 until Delays).foreach(_ => b.bits(1, 4))
    b.bits(6, 4)
  }

  /** [[Spine]] applications of `error`, each nested in the argument of the one before. */
  lazy val applicationSpine: Array[Byte] = program { b =>
    (0 until Spine).foreach { _ =>
      b.bits(3, 4)
      b.bits(6, 4)
    }
    b.bits(6, 4)
  }

  /** An empty list constant whose type nests [[ListTypes]] list types. */
  lazy val nestedListTypes: Array[Byte] = constant(FlatProgram.nestedEmptyList(ListTypes))

  val all: Vector[(String, Array[Byte])] = Vector(
    s"data-$DataLevels-levels" -> deepData,
    s"delay-$Delays-levels" -> nestedDelays,
    s"apply-spine-$Spine" -> applicationSpine,
    s"list-type-$ListTypes-levels" -> nestedListTypes
  )
