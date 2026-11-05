// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

/** GHC.Show's `showLitChar` /`showLitString`: decimal escapes protect a following digit and `\SO`
  * protects a following H. This is wire presentation, not a transformation of input bytes.
  */
private[ntc] object HaskellString:
  private val controls = Vector(
    "NUL",
    "SOH",
    "STX",
    "ETX",
    "EOT",
    "ENQ",
    "ACK",
    "a",
    "b",
    "t",
    "n",
    "v",
    "f",
    "r",
    "SO",
    "SI",
    "DLE",
    "DC1",
    "DC2",
    "DC3",
    "DC4",
    "NAK",
    "SYN",
    "ETB",
    "CAN",
    "EM",
    "SUB",
    "ESC",
    "FS",
    "GS",
    "RS",
    "US"
  )

  def quote(value: String): String = quoteCodes(value.codePoints().toArray.toVector)

  def quoteBytes(value: xyz.kd5ujc.scarab.codec.Bytes): String =
    quoteCodes(value.value.map(_ & 0xff))

  private def quoteCodes(codes: Vector[Int]): String =
    val escaped = codes.zipWithIndex.map { (code, index) =>
      val following = codes.lift(index + 1)
      if code > 127 then
        "\\" + code.toString + (if following.exists(n => n >= 48 && n <= 57) then "\\&" else "")
      else if code == 127 then "\\DEL"
      else if code == 92 then "\\\\"
      else if code == 34 then "\\\""
      else if code >= 32 then code.toChar.toString
      else
        val name = controls.lift(code).getOrElse("")
        "\\" + name + (if code == 14 && following.contains(72) then "\\&" else "")
    }
    "\"" + escaped.mkString + "\""
