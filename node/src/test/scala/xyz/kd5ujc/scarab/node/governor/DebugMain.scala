// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.governor

import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import scala.concurrent.duration.*
import MockEnvironment.*

/** A run of the governor in a generated environment, printed (a debugging aid for the properties):
  * `DebugMain <seed> <env|boot> <minutes> <from seconds> <to seconds>`.
  */
object DebugMain:
  def main(args: Array[String]): Unit =
    val seed = Seed.fromBase64(args(0)).get
    val gen = if args(1) == "boot" then Generators.bootstrapEnvironment else Generators.environment
    val env = gen.apply(Gen.Parameters.default.withSize(100), seed).get
    println(env)
    val rec = run(env, args(2).toInt.minutes)
    println(s"overflowed=${rec.overflowed} fatal=${rec.fatal} records=${rec.seen.size}")
    val from = args(3).toLong.seconds
    val to = args(4).toLong.seconds
    rec.seen.foreach {
      case (t, Seen.State(st)) if t >= from && t <= to =>
        val v = st.view(t)
        println(
          s"${t.toSeconds}s STATE known=${v.known} est=${v.established} act=${v.active} " +
            s"conn=${v.connecting} cool=${v.cooling} roots=${st.localRoots.keys} " +
            s"status=${st.peers.map((p, r) => p -> r.status)}"
        )
      case (t, Seen.Connections(c)) if t >= from && t <= to =>
        println(s"${t.toSeconds}s ENVCONN $c")
      case (t, Seen.Governor(out)) if t >= from && t <= to && !out.toString.startsWith("Counted") =>
        println(s"${t.toMillis / 1000.0}s $out")
      case (t, Seen.Env(e)) if t >= from && t <= to => println(s"${t.toMillis / 1000.0}s ENV $e")
      case _                                        => ()
    }
