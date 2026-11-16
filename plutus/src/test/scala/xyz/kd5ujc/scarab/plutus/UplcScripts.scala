// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import java.security.MessageDigest
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.Program
import xyz.kd5ujc.scarab.uplc.flat.FlatEncoder
import xyz.kd5ujc.scarab.uplc.text.UplcParser

/** The scripts of the probe corpora, written as the ledger holds them (a CBOR byte string around
  * the Flat program) by the `uplc` module's text parser and Flat writer. The reference suites check
  * each script against the digest its oracle recorded, so a script that changed is seen.
  */
object UplcScripts:
  /** The program `text`, closed, as a script. A program a corpus writes that does not parse or
    * encode is a defect of the corpus, which ends the suite.
    */
  def compile(text: String): Bytes =
    UplcParser.program(text).fold(e => sys.error(s"$e: $text"), script)

  def script(program: Program): Bytes =
    FlatEncoder.script(program).fold(e => sys.error(e.toString), identity)

  def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString
