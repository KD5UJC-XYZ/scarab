// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import java.nio.file.Path
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.*
import munit.FunSuite

/** The stack-safety rule of ADR 0011 for width: the number of inputs, outputs and elements of a
  * collection never costs stack. Every test runs on a 256 KiB stack.
  */
class WideTxStackSuite extends FunSuite:
  private val c = PlutusCase.load(Path.of("fixtures/preprod/plutus/tx-c70243bb.json"))
  private val out = c.tx.body.outputs.head

  private def wide(n: Int): (Tx, Map[TxIn, TxOut]) =
    val ins =
      Vector.tabulate(n)(i => TxIn(Bytes(Vector.fill[Byte](32)((i % 251).toByte)), BigInt(i)))
    val body =
      c.tx.body.copy(inputs = ins, outputs = Vector.fill(n)(out), referenceInputs = Vector.empty)
    (c.tx.copy(body = body), ins.map(_ -> out).toMap)

  PlutusLanguage.values.toList.foreach { language =>
    test(s"the $language transaction information of 5,000 inputs and 5,000 outputs") {
      val (tx, spent) = wide(5000)
      val env = ScriptContexts.Environment(c.protocolMajor, spent, Map.empty, c.posix)
      val info = PreprodDeepTx.onSmallStack(ScriptContexts.txInfo(language, tx, env))
      assert(info.isRight, info.left.toOption.map(_.reason))
    }
  }

  test("traversals over 100,000 elements in Either and Option, the shape of the decoders") {
    val xs = Vector.range(0, 100000)
    PreprodDeepTx.onSmallStack {
      assertEquals(xs.traverse(i => Right(i): Either[String, Int]).map(_.size), Right(xs.size))
      assertEquals(xs.traverse_(i => Right(i): Either[String, Int]), Right(()))
      assertEquals(
        xs.flatTraverse(i => Right(Vector(i)): Either[String, Vector[Int]]).map(_.size),
        Right(xs.size)
      )
      assertEquals(xs.foldLeftM(0)((a, i) => Right(a + i): Either[String, Int]), Right(xs.sum))
      assertEquals(xs.traverse(i => Some(i): Option[Int]).map(_.size), Some(xs.size))
    }
  }
