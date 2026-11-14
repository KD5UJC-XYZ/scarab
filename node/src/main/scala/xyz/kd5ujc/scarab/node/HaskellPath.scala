// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

/** The POSIX `FilePath` functions of filepath 1.4 (`System/FilePath/Posix.hs`) the reference names
  * a configured file with: a path is text, `/` its only separator, and the leading separators its
  * drive.
  */
object HaskellPath:
  private def separator(c: Char): Boolean = c == '/'

  /** `splitDrive`: the leading separators, and the rest. */
  private def splitDrive(path: String): (String, String) = path.span(separator)

  /** `splitFileName`: the directory, `./` when there is none, and the name after the last
    * separator.
    */
  private def splitFileName(path: String): (String, String) =
    val (drive, rest) = splitDrive(path)
    val at = rest.lastIndexWhere(separator) + 1
    val directory = drive + rest.take(at)
    (if directory.isEmpty then "./" else directory, rest.drop(at))

  /** `takeDirectory`: the directory without its trailing separators, unless it is all drive. */
  def takeDirectory(path: String): String =
    val file = splitFileName(path)._1
    val res = file.reverse.dropWhile(separator).reverse
    val drive = file.nonEmpty && splitDrive(file)._2.isEmpty
    if drive then file else if res.isEmpty && file.nonEmpty then file else res

  /** `</>` (`combine`): `name` itself when it is absolute, otherwise joined with one separator. */
  def combine(directory: String, name: String): String =
    if name.headOption.exists(separator) then name
    else if directory.isEmpty then name
    else if name.isEmpty then directory
    else if directory.lastOption.exists(separator) then directory + name
    else directory + "/" + name
