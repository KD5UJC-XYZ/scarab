// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models.gen

/** Generator configuration: what to generate and the declared properties the CDDL cannot express.
  *
  * A line-oriented file (`#` starts a comment); every directive names a rule as `module:rule`:
  * {{{
  * package   xyz.kd5ujc.scarab.models        base Scala package
  * module    conway conway path/a.cddl path/b.cddl     CDDL module, Scala subpackage, files (later override)
  * root      conway:header                    generate this rule and everything it reaches
  * preserve  conway:header_body               keep the original bytes (hashed or signed by the spec)
  * rename    conway:hash32 Hash               Scala type name
  * cases     base:point Origin Block          Scala names of the alternatives of a choice, in order
  * strip     handshake:msg_x Msg              prefix removed from the names of alternatives
  * field     conway:transaction_body 0 inputs name of an integer-keyed map entry
  * external  base:tip                         not generated: a raw `Node` (hand-written boundary)
  * }}}
  */
final case class Config(
    basePackage: String,
    modules: Vector[Config.ModuleSpec],
    roots: Vector[QName],
    preserve: Set[QName],
    rename: Map[QName, String],
    cases: Map[QName, Vector[String]],
    strip: Map[QName, Vector[String]],
    fields: Map[(QName, String), String],
    external: Set[QName]
)

object Config:
  final case class ModuleSpec(name: String, subpackage: String, files: Vector[String])

  private def err(line: Int, message: String): Nothing = throw GenException(GenError.BadConfig(line, message))

  def parse(text: String): Config =
    var pkg = ""
    val modules = Vector.newBuilder[ModuleSpec]
    val roots = Vector.newBuilder[QName]
    var preserve = Set.empty[QName]
    var rename = Map.empty[QName, String]
    var cases = Map.empty[QName, Vector[String]]
    var strip = Map.empty[QName, Vector[String]]
    var fields = Map.empty[(QName, String), String]
    var external = Set.empty[QName]
    def q(line: Int, s: String): QName = s.split(":", 2) match
      case Array(m, n) => QName(m, n)
      case _           => err(line, s"expected module:rule, got `$s`")
    text.linesIterator.zipWithIndex.foreach { (raw, i) =>
      val line = i + 1
      val words = raw.takeWhile(_ != '#').trim.split("\\s+").toVector.filter(_.nonEmpty)
      words match
        case Vector()                         => ()
        case Vector("package", p)             => pkg = p
        case "module" +: n +: sub +: files if files.nonEmpty => modules += ModuleSpec(n, sub, files)
        case Vector("root", r)                => roots += q(line, r)
        case Vector("preserve", r)            => preserve += q(line, r)
        case Vector("rename", r, n)           => rename += q(line, r) -> n
        case "cases" +: r +: names if names.nonEmpty => cases += q(line, r) -> names
        case "strip" +: r +: ps if ps.nonEmpty => strip += q(line, r) -> ps
        case Vector("field", r, k, n)         => fields += (q(line, r), k) -> n
        case Vector("external", r)            => external += q(line, r)
        case other                            => err(line, s"unrecognised directive: ${other.mkString(" ")}")
    }
    if pkg.isEmpty then err(0, "missing `package`")
    Config(pkg, modules.result(), roots.result(), preserve, rename, cases, strip, fields, external)
