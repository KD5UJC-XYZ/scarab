// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

/** The unsigned words of the Leios wire: SlotNo, transaction sizes and bitmap entries
  * (ouroboros-consensus `7b239a16`, Leios/Types.hs:202-205,315-322 and
  * MiniProtocol/LeiosFetch/Type.hs:387-435).
  */
object LeiosWord:
  opaque type Word16 = Int
  object Word16:
    val Max: BigInt = (BigInt(1) << 16) - 1
    def from(value: BigInt): Either[LeiosWireError, Word16] =
      checked(value, Max, LeiosField.Word16).map(_.toInt)
    extension (value: Word16) def toInt: Int = value

  opaque type Word32 = Long
  object Word32:
    val Max: BigInt = (BigInt(1) << 32) - 1
    def from(value: BigInt): Either[LeiosWireError, Word32] =
      checked(value, Max, LeiosField.Word32).map(_.toLong)
    extension (value: Word32) def toLong: Long = value

  opaque type Word64 = BigInt
  object Word64:
    val Max: BigInt = (BigInt(1) << 64) - 1
    def from(value: BigInt): Either[LeiosWireError, Word64] =
      checked(value, Max, LeiosField.Word64)
    extension (value: Word64) def toBigInt: BigInt = value

  private def checked(
      value: BigInt,
      maximum: BigInt,
      field: LeiosField
  ): Either[LeiosWireError, BigInt] =
    Either.cond(value >= 0 && value <= maximum, value, LeiosWireError.OutsideWord(field, value))
