// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import java.util.Locale

/** The decimal numbers in the names of storage files: chunk and log file numbers, and the slots of
  * snapshots and their checkpoints.
  *
  * `%d` writes the digits of the default format locale, and under some locales (Persian, most
  * Arabic ones, Bengali, Marathi, Nepali, Burmese, ...) they are not ASCII, so a store written
  * under one of them would not open under another. A number here is written as the root locale
  * writes it, in ASCII digits under every locale, as the reference writes the numbers of its file
  * names with `show` (ouroboros-consensus 82ecba32: `renderFile`,
  * `Storage/ImmutableDB/Impl/Util.hs:66-69`; `filePath`, `Storage/VolatileDB/Impl/Util.hs:73-74`).
  * They are the bytes `%d` writes under `en_US`.
  */
object FileNumber:

  /** `n` in decimal, padded with zeros to a field of at least `width` characters (a sign included):
    * `%0<width>d` in the root locale.
    */
  def padded(n: Long, width: Int): String = s"%0${width}d".formatLocal(Locale.ROOT, n)

  /** As [[padded]], for a number that may not fit a `Long` (a slot is a `Word64`). */
  def padded(n: BigInt, width: Int): String = s"%0${width}d".formatLocal(Locale.ROOT, n)
