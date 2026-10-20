// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.collection.immutable.SortedMap

/** The #206 comparison workload: independent equal trees and first/last edits of a deterministic
  * 20,000-entry diagnostic state. Compile this same source with each version of Diff, since its
  * derivation is inline. Run outside other builds with a pinned JVM, CPU set and heap.
  *
  * `ledger/Test/runMain xyz.kd5ujc.scarab.ledger.state.DiffBenchMain`
  */
object DiffBenchMain:
  private enum Balance derives Diff:
    case Ada(value: Long)
    case Token(value: String)

  private final case class Account(balance: Balance, deposit: Long) derives Diff
  private final case class State(accounts: SortedMap[Int, Account]) derives Diff

  private val entries = 20000

  private def state: State = State(
    SortedMap.from((0 until entries).map(i => i -> Account(Balance.Ada(i.toLong), i.toLong)))
  )

  private def fingerprint(text: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes(StandardCharsets.UTF_8))
      .map(b => f"$b%02x")
      .mkString

  private def measure(name: String, iterations: Int)(body: => Long): Unit =
    val bean = ManagementFactory.getPlatformMXBean(classOf[com.sun.management.ThreadMXBean])
    def sample(): (Double, Double, Long) =
      val allocated = bean.getCurrentThreadAllocatedBytes
      val started = System.nanoTime()
      val result = (0 until iterations).foldLeft(0L)((sum, _) => sum + body)
      val elapsed = System.nanoTime() - started
      val bytes = bean.getCurrentThreadAllocatedBytes - allocated
      (elapsed.toDouble / iterations, bytes.toDouble / iterations, result)
    (0 until 3).foreach(_ => sample())
    val samples = Vector.fill(7)(sample())
    val ns = samples.map(_._1).sorted
    val bytes = samples.map(_._2).sorted
    println(
      f"METRIC $name iterations=$iterations ns/op=${ns(3)}%.1f min=${ns.head}%.1f max=${ns.last}%.1f bytes/op=${bytes(3)}%.1f checksum=${samples.map(_._3).sum}"
    )

  def main(args: Array[String]): Unit =
    println(s"Diff benchmark entries=$entries java=${System.getProperty("java.version")} args=${args.toVector}")
    val left = state
    val equal = state
    val early = left.copy(accounts = left.accounts.updated(0, Account(Balance.Ada(-1), 0)))
    val late = left.copy(
      accounts = left.accounts.updated(entries - 1, Account(Balance.Ada(-1), entries - 1L))
    )
    val d = Diff[State]
    val inputs = Vector(
      ("equal", equal, None, 20),
      ("early", early, Some("accounts[0].balance.value"), 100000),
      ("late", late, Some(s"accounts[${entries - 1}].balance.value"), 20)
    )
    val outputs = inputs.map { (name, right, expected, _) =>
      val found = d.diff(left, right)
      assert(found.map(_.path.render) == expected)
      name -> found
    }
    println(s"INPUT ${fingerprint(left.toString)}")
    println(s"OUTPUT ${fingerprint(outputs.toString)}")
    inputs.foreach { (name, right, _, iterations) =>
      measure(s"state.$name", iterations)(
        d.diff(left, right).fold(0L)(_.path.steps.size.toLong)
      )
    }
    val balance = Diff[Balance]
    val a = Balance.Ada(1)
    val b = Balance.Ada(2)
    val other = Balance.Token("two")
    println(s"SUM ${fingerprint(Vector(balance.diff(a, b), balance.diff(a, other)).toString)}")
    measure("sum.sameCase", 100000)(balance.diff(a, b).fold(0L)(_.path.steps.size.toLong))
