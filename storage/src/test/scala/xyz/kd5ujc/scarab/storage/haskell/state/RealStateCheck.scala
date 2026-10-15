// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.state.{Conway, ShelleyBasedEra}

/** A check on a real Haskell `state` file (a LedgerDB snapshot of `cardano-node`): the
  * `NewEpochState` of its Conway era is read with the codec and written again, and the two byte
  * strings must be the same. Not a test of the build (the files are tens of megabytes and the
  * reading needs a few gigabytes of heap): run it by hand,
  * `java -Xmx16g -cp <storage test classpath> ...RealStateCheck <state file>`.
  *
  * The path to the item is the one of the envelope of the snapshot (`array(2)[1, ExtLedgerState]`,
  * the telescope of the hard-fork combinator, the current era's `[2, [tip, state, ...]]`), which
  * the next stage reads properly; here it is only walked to.
  */
object RealStateCheck:
  /** The end of the item that starts at `from`, without building it. */
  private def skip(d: Array[Byte], from: Int): Int =
    val b = d(from) & 0xff
    val major = b >> 5
    val ai = b & 31
    val (arg, after) =
      if ai < 24 then (ai.toLong, from + 1)
      else if ai == 31 then (-1L, from + 1)
      else
        val n = 1 << (ai - 24)
        (BigInt(1, d.slice(from + 1, from + 1 + n)).toLong, from + 1 + n)
    major match
      case 0 | 1 | 7 => after
      case 2 | 3 =>
        if arg >= 0 then after + arg.toInt
        else
          var p = after
          while (d(p) & 0xff) != 0xff do p = skip(d, p)
          p + 1
      case 4 | 5 =>
        val per = if major == 4 then 1 else 2
        if arg >= 0 then
          var p = after
          var i = 0L
          while i < arg * per do { p = skip(d, p); i += 1 }
          p
        else
          var p = after
          while (d(p) & 0xff) != 0xff do p = skip(d, p)
          p + 1
      case _ => skip(d, after)

  /** The start of item `index` of the array that starts at `from`. */
  private def child(d: Array[Byte], from: Int, index: Int): Int =
    val b = d(from) & 0xff
    val ai = b & 31
    val head = if ai < 24 then 1 else 1 + (1 << (ai - 24))
    var p = from + head
    var i = 0
    while i < index do { p = skip(d, p); i += 1 }
    p

  def main(args: Array[String]): Unit =
    val data = Files.readAllBytes(Path.of(args(0)))
    val start = List(1, 0, 6, 1, 1, 1).foldLeft(0)((p, i) => child(data, p, i))
    val end = skip(data, start)
    val slice = Bytes.fromArray(java.util.Arrays.copyOfRange(data, start, end))
    println(s"state file ${data.length} bytes; NewEpochState at $start, ${slice.size} bytes")
    given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway
    val t0 = System.nanoTime()
    HaskellState.decode[Conway](slice) match
      case Left(error) =>
        println(s"DECODE FAILED: ${error.render}")
        sys.exit(1)
      case Right(nes) =>
        println(s"decoded in ${(System.nanoTime() - t0) / 1000000} ms: epoch ${nes.epoch}")
        val gov = nes.es.ls.utxo.gov
        println(
          s"  pools ${nes.es.ls.cert.pState.stakePools.size}, " +
            s"dreps ${nes.es.ls.cert.vState.dreps.size}, " +
            s"accounts ${nes.es.ls.cert.dState.accounts.states.size}, " +
            s"proposals ${gov.proposals.actions.size}, " +
            s"nonMyopic ${nes.es.nonMyopic.likelihoods.size}"
        )
        HaskellState.encode(nes) match
          case Left(why) =>
            println(s"NOT WRITABLE: $why")
            sys.exit(1)
          case Right(again) =>
            if again == slice then println(s"IDENTICAL: ${again.size} bytes written again")
            else
              val a = slice.toArray
              val b = again.toArray
              val at = a.indices.find(i => i >= b.length || a(i) != b(i)).getOrElse(b.length)
              println(s"DIFFERENT: ${a.length} vs ${b.length} bytes, first difference at $at")
              println(
                s"  original: ${a.slice(math.max(0, at - 16), at + 32).map("%02x".format(_)).mkString}"
              )
              println(
                s"  written : ${b.slice(math.max(0, at - 16), at + 32).map("%02x".format(_)).mkString}"
              )
              sys.exit(2)
