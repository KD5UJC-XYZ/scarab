// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.metrics

import scala.annotation.tailrec

/** A reader of the Prometheus text exposition format 0.0.4, written from the grammar of the
  * specification and independent of [[Exposition]], so that a test can check what the renderer
  * wrote. It is strict: anything the grammar does not allow is a `Left`.
  */
object TextFormat:
  final case class Sample(name: String, labels: List[(String, String)], value: Double)

  final case class Parsed(
      help: Map[String, String],
      kinds: Map[String, String],
      samples: Vector[Sample],
      /** The metric names in the order their `# TYPE` lines came. */
      order: Vector[String]
  )

  private val Name = "[a-zA-Z_:][a-zA-Z0-9_:]*".r
  private val LabelNameRe = "[a-zA-Z_][a-zA-Z0-9_]*".r
  private val Decimal = "[+-]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)([eE][+-]?[0-9]+)?".r
  private val Kinds = Set("counter", "gauge", "histogram", "summary", "untyped")

  def parse(text: String): Either[String, Parsed] =
    if text.nonEmpty && !text.endsWith("\n") then Left("the text does not end with a line feed")
    else
      text
        .split("\n", -1)
        .dropRight(1)
        .zipWithIndex
        .foldLeft[Either[String, Parsed]](
          Right(Parsed(Map.empty, Map.empty, Vector.empty, Vector.empty))
        ) { case (acc, (line, n)) =>
          acc.flatMap(p => parseLine(p, line).left.map(e => s"line ${n + 1}: $e in <$line>"))
        }

  private def parseLine(p: Parsed, line: String): Either[String, Parsed] =
    if line.startsWith("# HELP ") then
      val rest = line.drop(7)
      val (name, help) = rest.span(_ != ' ')
      if !Name.matches(name) then Left("bad name in HELP")
      else if p.help.contains(name) then Left(s"two HELP lines for $name")
      else unescape(help.drop(1), quote = false).map(h => p.copy(help = p.help.updated(name, h)))
    else if line.startsWith("# TYPE ") then
      line.drop(7).split(" ", -1).toList match
        case name :: kind :: Nil if Name.matches(name) && Kinds(kind) =>
          if p.kinds.contains(name) then Left(s"two TYPE lines for $name")
          else if !p.help.contains(name) then Left(s"TYPE before HELP for $name")
          else Right(p.copy(kinds = p.kinds.updated(name, kind), order = p.order :+ name))
        case _ => Left("bad TYPE line")
    else if line.startsWith("#") then Left("comment the format does not define here")
    else
      parseSample(line).flatMap(s => family(p, s.name).map(_ => p.copy(samples = p.samples :+ s)))

  /** The family a sample belongs to: its own name, or for a histogram the name without a suffix. */
  private def family(p: Parsed, sample: String): Either[String, String] =
    val suffixes = List("_bucket", "_sum", "_count")
    val candidates = sample :: suffixes.filter(sample.endsWith).map(sample.stripSuffix)
    candidates
      .find(c => p.kinds.contains(c) && (c == sample || p.kinds(c) == "histogram"))
      .toRight(s"sample $sample without HELP and TYPE")

  private def parseSample(line: String): Either[String, Sample] =
    val nameEnd = line.indexWhere(c => c == '{' || c == ' ')
    if nameEnd <= 0 then Left("no name")
    else
      val name = line.take(nameEnd)
      if !Name.matches(name) then Left("bad metric name")
      else
        val (labels, rest) =
          if line.charAt(nameEnd) == '{' then
            labelSet(line, nameEnd + 1, Nil) match
              case Right((ls, after)) => (Right(ls), line.drop(after))
              case Left(e)            => (Left(e), "")
          else (Right(Nil), line.drop(nameEnd))
        for
          ls <- labels
          _ <- Either.cond(rest.startsWith(" "), (), "no space before the value")
          v <- number(rest.drop(1))
        yield Sample(name, ls, v)

  /** The labels from `at` (just after the `{`) to the `}`: the list and the index after it. */
  @tailrec
  private def labelSet(
      line: String,
      at: Int,
      acc: List[(String, String)]
  ): Either[String, (List[(String, String)], Int)] =
    if at < line.length && line.charAt(at) == '}' && acc.isEmpty then Right((acc, at + 1))
    else
      val eq = line.indexOf("=\"", at)
      if eq < 0 then Left("label without =\"")
      else
        val label = line.substring(at, eq)
        if !LabelNameRe.matches(label) || label.startsWith("__") then Left(s"bad label name $label")
        else
          quoted(line, eq + 2, new java.lang.StringBuilder) match
            case Left(e) => Left(e)
            case Right((value, after)) =>
              val next = acc :+ (label -> value)
              if after < line.length && line.charAt(after) == ',' then
                labelSet(line, after + 1, next)
              else if after < line.length && line.charAt(after) == '}' then Right((next, after + 1))
              else Left("label set not closed")

  /** A quoted label value from `at` (after the opening quote): its text and the index after the
    * closing quote.
    */
  @tailrec
  private def quoted(
      line: String,
      at: Int,
      out: java.lang.StringBuilder
  ): Either[String, (String, Int)] =
    if at >= line.length then Left("unterminated label value")
    else
      line.charAt(at) match
        case '"' => Right((out.toString, at + 1))
        case '\\' =>
          if at + 1 >= line.length then Left("dangling backslash")
          else
            line.charAt(at + 1) match
              case '\\' => quoted(line, at + 2, out.append('\\'))
              case '"'  => quoted(line, at + 2, out.append('"'))
              case 'n'  => quoted(line, at + 2, out.append('\n'))
              case c    => Left(s"bad escape \\$c")
        case c => quoted(line, at + 1, out.append(c))

  private def unescape(text: String, quote: Boolean): Either[String, String] =
    @tailrec
    def go(at: Int, out: java.lang.StringBuilder): Either[String, String] =
      if at >= text.length then Right(out.toString)
      else if text.charAt(at) != '\\' then go(at + 1, out.append(text.charAt(at)))
      else if at + 1 >= text.length then Left("dangling backslash")
      else
        text.charAt(at + 1) match
          case '\\'         => go(at + 2, out.append('\\'))
          case 'n'          => go(at + 2, out.append('\n'))
          case '"' if quote => go(at + 2, out.append('"'))
          case c            => Left(s"bad escape \\$c")
    go(0, new java.lang.StringBuilder)

  private def number(text: String): Either[String, Double] = text match
    case "NaN"  => Right(Double.NaN)
    case "+Inf" => Right(Double.PositiveInfinity)
    case "-Inf" => Right(Double.NegativeInfinity)
    case other =>
      if Decimal.matches(other) then other.toDoubleOption.toRight(s"bad value <$other>")
      else Left(s"bad value <$other>")
