// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.mempack.TxOutMemPack
import xyz.kd5ujc.scarab.ledger.state.Conway

/** Historical microbenchmark implementation (haskell-snapshots.md section 8.4). Run only after
  * comparison admission; mutation and wall-clock measurement stay inside the caller's IO.blocking.
  */
private[storage] object MemPackJoinBenchmark:
  @volatile private var sink = 0L

  private def median(xs: Vector[Double]): Double = xs.sorted.apply(xs.size / 2)

  private def measureNs(units: Int)(work: => Long): Double =
    (0 until 3).foreach(_ => sink += work)
    median(Vector.fill(7) {
      val t0 = System.nanoTime()
      sink += work
      (System.nanoTime() - t0).toDouble / units
    })

  /** Bytes and median ns per output of the stored CBOR value and the MemPack one over the same
    * outputs.
    */
  def measure(cbor: Vector[Array[Byte]], pack: Vector[Array[Byte]]): String =
    val n = cbor.size
    val cborBytes = cbor.map(_.length.toLong).sum
    val packBytes = pack.map(_.length.toLong).sum
    val outs = cbor.map(c => UtxoStore.Coding.decodeValue(c).toOption.get)
    val packed = pack.map(Bytes.wrap)
    val partsV = packed.map(p => TxOutMemPack.decodeParts[Conway](p).toOption.get)
    val rounds = 3
    def round(): (Double, Double, Double, Double, Double, Double) =
      (
        measureNs(n)(
          cbor.foldLeft(0L)((a, c) =>
            a + UtxoStore.Coding.decodeValue(c).toOption.get.value.coin.toLong
          )
        ),
        measureNs(n)(
          packed.foldLeft(0L)((a, p) =>
            a + TxOutMemPack.decodeParts[Conway](p).toOption.get.value.coin.toLong
          )
        ),
        measureNs(n)(
          packed.foldLeft(0L)((a, p) =>
            a + TxOutMemPack.decode[Conway](p).toOption.get.value.coin.toLong
          )
        ),
        measureNs(n)(outs.foldLeft(0L)((a, o) => a + UtxoStore.Coding.encodeValue(o).length)),
        measureNs(n)(
          outs.foldLeft(0L)((a, o) => a + TxOutMemPack.encode[Conway](o).toOption.get.size)
        ),
        measureNs(n)(
          partsV.foldLeft(0L)((a, p) => a + TxOutMemPack.encodeParts[Conway](p).toOption.get.size)
        )
      )
    val rs = (1 to rounds).map(_ => round())
    def col(f: ((Double, Double, Double, Double, Double, Double)) => Double) = median(
      rs.map(f).toVector
    )
    f"""BENCH outputs=$n
       |BENCH bytes/output: stored cbor value ${cborBytes.toDouble / n}%.1f  mempack ${packBytes.toDouble / n}%.1f  (mempack / cbor ${packBytes.toDouble / cborBytes}%.3f)
       |BENCH ns/output decode: cbor value to TxOut ${col(_._1)}%.0f   mempack to parts ${col(
        _._2
      )}%.0f   mempack to TxOut with raw ${col(_._3)}%.0f
       |BENCH ns/output encode: cbor value from TxOut ${col(_._4)}%.0f   mempack from TxOut ${col(
        _._5
      )}%.0f   mempack from parts ${col(_._6)}%.0f
       |BENCH checksum of results: $sink""".stripMargin
