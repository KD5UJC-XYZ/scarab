// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import cats.syntax.show.*
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.EraLedgerState

/** A check on a real Haskell `state` file (a LedgerDB snapshot of `cardano-node`, tens of
  * megabytes): the whole file is read with [[HaskellLedgerFile]] and written again, and the two
  * byte strings must be the same. Not a test of the build, because the reading needs a few
  * gigabytes of heap: run it in a forked JVM,
  * `java -Xmx6g -cp <storage test classpath> ...RealLedgerFileCheck <state file> [<meta file>]`.
  * The munit suite `LedgerFileFixturesSuite` runs the same check on the committed fixtures, and on
  * the real file when `HASKELL_STATE_FILE` names one.
  */
object RealLedgerFileCheck:
  /** What a run found. */
  final case class Result(
      size: Int,
      era: String,
      identical: Boolean,
      firstDifference: Option[Int],
      readMillis: Long,
      writeMillis: Long
  )

  /** Read `path`, write it again, compare. */
  def run(path: Path): Either[String, (Result, HaskellLedgerFile)] =
    val data = Bytes.fromArray(Files.readAllBytes(path))
    val t0 = System.nanoTime()
    HaskellLedgerFile.decode(data) match
      case Left(error) => Left(s"decode failed: ${error.render}")
      case Right(file) =>
        val t1 = System.nanoTime()
        HaskellLedgerFile.encode(file) match
          case Left(why) => Left(s"not writable: ${why.violation.render}")
          case Right(again) =>
            val t2 = System.nanoTime()
            val same = again == data
            val at =
              if same then None
              else
                val a = data.toArray
                val b = again.toArray
                Some(a.indices.find(i => i >= b.length || a(i) != b(i)).getOrElse(b.length))
            Right(
              (
                Result(
                  data.size,
                  file.era.toString,
                  same,
                  at,
                  (t1 - t0) / 1000000,
                  (t2 - t1) / 1000000
                ),
                file
              )
            )

  def main(args: Array[String]): Unit =
    val path = Path.of(args(0))
    args.lift(1).foreach { meta =>
      println(s"meta: ${SnapshotMeta.parse(Files.readString(Path.of(meta))).show}")
    }
    run(path) match
      case Left(message) =>
        println(message)
        sys.exit(1)
      case Right((r, file)) =>
        val rt = Runtime.getRuntime
        println(s"state file ${r.size} bytes, era ${r.era}")
        println(
          s"  read ${r.readMillis} ms, written ${r.writeMillis} ms, max heap ${rt.maxMemory() >> 20} MB"
        )
        file.ext.foreach { ext =>
          ext.ledgerState.state match
            case EraLedgerState.InShelleyEra(_, s) =>
              println(
                s"  tip ${s.tip.show}, transition ${s.transition.show}, peras ${s.peras.show}"
              )
            case _ => ()
          println(s"  header tip ${ext.headerState.tip.show}")
          println(s"  past eras ${ext.ledgerState.past.size}")
        }
        Derivations.ofFile(file).foreach(d => println(s"  derivations: $d"))
        if r.identical then println(s"IDENTICAL: ${r.size} bytes written again")
        else
          println(s"DIFFERENT: first difference at ${r.firstDifference}")
          sys.exit(2)
