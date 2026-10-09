// SPDX-License-Identifier: Apache-2.0
package lab.vm

import scalus.uplc.builtin.{ByteString, Data}
import scalus.cardano.onchain.plutus.prelude.{List as PList}

enum SyntheticPurpose:
  case Spending, Minting

/** Only this finite-shape synthetic input surface is admitted. IDs are explicit synthetic bytes,
  * not computed transaction/script identities and never reference post-state.
  */
final case class SyntheticSpend(
    purpose: SyntheticPurpose = SyntheticPurpose.Spending,
    inputId: String = "11" * 32,
    purposeId: String = "11" * 32,
    inputIndex: Int = 0,
    purposeIndex: Int = 0,
    inlineBeneficiary: String = "22" * 28,
    purposeBeneficiary: String = "22" * 28,
    paymentBeneficiary: String = "22" * 28,
    inlineMinimum: Long = 2000000L,
    purposeMinimum: Long = 2000000L,
    payout: Long = 3000000L,
    redeemer: Int = 7
)

object SyntheticSpend:
  private def c(tag: Int, args: Data*): Data = Data.Constr(tag, PList.from(args))
  private def list(args: Data*): Data = Data.List(PList.from(args))
  private def map(args: (Data, Data)*): Data = Data.Map(PList.from(args))
  private def bytes(hex: String): Data = Data.B(ByteString.fromHex(hex))
  private def integer(value: Long): Data = Data.I(BigInt(value))
  private def ref(id: String, index: Int): Data = c(0, bytes(id), integer(index))
  private def datum(beneficiary: String, minimum: Long): Data =
    c(0, bytes(beneficiary), integer(minimum))
  private val none = c(1)
  private def address(tag: Int, credential: String): Data = c(0, c(tag, bytes(credential)), none)
  private def value(amount: Long): Data = map(bytes("") -> map(bytes("") -> integer(amount)))

  def context(input: SyntheticSpend): Either[String, Data] =
    val ids = Vector(input.inputId, input.purposeId)
    val credentials =
      Vector(input.inlineBeneficiary, input.purposeBeneficiary, input.paymentBeneficiary)
    if !ids.forall(_.matches("[0-9a-f]{64}")) ||
      !credentials.forall(_.matches("[0-9a-f]{56}"))
    then Left("synthetic identities require exact lowercase hex widths")
    else if !Vector(input.inputIndex, input.purposeIndex, input.redeemer).forall(n =>
        n >= 0 && n <= 255
      )
    then Left("synthetic index/redeemer outside 0..255")
    else if !Vector(input.inlineMinimum, input.purposeMinimum, input.payout)
        .forall(n => n >= 0 && n <= 5000000L)
    then Left("synthetic amount outside 0..5000000")
    else
      val ownRef = ref(input.inputId, input.inputIndex)
      val spendingRef = ref(input.purposeId, input.purposeIndex)
      val inline = datum(input.inlineBeneficiary, input.inlineMinimum)
      val purposeDatum = datum(input.purposeBeneficiary, input.purposeMinimum)
      val redeemer = integer(input.redeemer)
      val ownOutput = c(0, address(1, "44" * 28), value(5000000L), c(2, inline), none)
      val payment = c(0, address(0, input.paymentBeneficiary), value(input.payout), c(0), none)
      // Infinite closed bounds, no SlotConfig or implicit network time conversion.
      val always = c(0, c(0, c(0), c(1)), c(0, c(2), c(1)))
      val txInfo = c(
        0,
        list(c(0, ownRef, ownOutput)),
        list(),
        list(payment),
        integer(5000000L - input.payout),
        map(),
        list(),
        map(),
        always,
        list(),
        map(c(1, ownRef) -> redeemer),
        map(),
        bytes("33" * 32),
        map(),
        list(),
        none,
        none
      )
      val purpose = input.purpose match
        case SyntheticPurpose.Spending => c(1, spendingRef, c(0, purposeDatum))
        case SyntheticPurpose.Minting  => c(0, bytes("44" * 28))
      Right(c(0, txInfo, redeemer, purpose))
