// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import io.circe.parser
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.{Bytes, Value}
import xyz.kd5ujc.scarab.ledger.state.{Build, Conway, NewEpochState}
import xyz.kd5ujc.scarab.protocol.Era

/** Preprod transaction f90dce57 (block 5,183,974, slot 133,883,340, epoch 313): a native script
  * 5,383 levels deep, 16,383 bytes in all (the maximum is 16,384), and the one output it spends,
  * which is all the ledger it needs. See `fixtures/preprod/README.md`.
  */
object PreprodDeepTx:
  val Slot: BigInt = 133883340
  val Epoch: BigInt = 313
  val Id = "f90dce5765108da976abdbb9fc618f9a6ffd9fa4d93b2f288eed1808545424c9"

  private val dir = Path.of("fixtures/preprod")
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(sys.error, identity)

  /** Run `body` on a thread whose stack (256 KiB) is far too small for a recursion over this
    * transaction's depth.
    */
  def onSmallStack[A](body: => A): A =
    var result: Option[Either[Throwable, A]] = None
    val thread = new Thread(
      null,
      () =>
        result = Some(
          try Right(body)
          catch case t: Throwable => Left(t)
        ),
      "small",
      256 * 1024
    )
    thread.start()
    thread.join()
    result.get.fold(throw _, identity)

  /** The transaction as submitted: `[body, witnesses, true, null]`. */
  val bytes: Bytes = hex(Files.readString(dir.resolve("tx-f90dce57.hex")).trim)

  def parsed: Tx =
    xyz.kd5ujc.scarab.ledger.CborRead.decode(bytes).fold(sys.error, identity).value match
      case Value.Arr(xs) =>
        Tx.fromBlock(Block.Transaction(xs(0).original, xs(1).original, None, true), Era.Conway)
          .fold(e => sys.error(e.toString), identity)
      case other => sys.error(s"not a transaction: ${other.toString.take(100)}")

  /** The output the transaction spends, in the legacy encoding it had when it was created. */
  val input: (TxIn, TxOut) =
    val e = parser
      .parse(Files.readString(dir.resolve("tx-f90dce57-inputs.json")))
      .toOption
      .get
      .hcursor
      .downField("inputs")
      .downArray
    val lovelace = e.get[String]("lovelace").toOption.get.toLong
    val addr = hex(e.get[String]("address").toOption.get)
    val legacy = Bytes(
      Vector(0x82, 0x58, addr.size).map(_.toByte) ++ addr.value ++
        Vector(0x1a, lovelace >> 24, lovelace >> 16, lovelace >> 8, lovelace).map(_.toByte)
    )
    TxIn(hex(e.get[String]("txHash").toOption.get), BigInt(e.get[Int]("index").toOption.get)) ->
      TxOut.fromBytes(legacy).fold(sys.error, identity)

  /** A Conway ledger at epoch 313 with the preprod fee parameters and the output, on which the
    * transaction is valid at [[Slot]].
    */
  def ledger: NewEpochState[Conway] =
    val ada = BigInt(1000000)
    val params = TestLedger.params(10).copy(minFeeA = 44, minFeeB = 155381, keyDeposit = 2 * ada)
    Build.funded(Build.nes[Conway](Epoch.toLong, params), Map(input))
