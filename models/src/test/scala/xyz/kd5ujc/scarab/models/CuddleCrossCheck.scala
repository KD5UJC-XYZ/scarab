// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import scala.jdk.CollectionConverters.*
import scala.util.Random
import xyz.kd5ujc.scarab.models.gen.{
  CddlParser,
  CddlSampler,
  Config,
  Emitter,
  GenError,
  GenException,
  Generator,
  QName,
  Schema
}
import xyz.kd5ujc.scarab.models.runtime.Codec

/** The Scala half of the cuddle cross-check (`scripts/cddl_crosscheck.py` drives it;
  * docs/models.md, section 13). Two steps, because cuddle (Haskell) only runs in Docker and the
  * generated codecs only exist on the JVM:
  *
  *   - `export <dir> <count> <seed>`: writes the targets, `<count>` terms per target sampled by
  *     `CddlSampler`, and the rule names (with arity) our parser finds in every vendored file.
  *   - `check <dir>`: decodes the terms cuddle generated (`<dir>/cuddle/<mode>/<target>.hex`, one
  *     hex term per line) with the generated codecs and writes `<dir>/decode.tsv`.
  *
  * Run with `sbt "models/Test/runMain xyz.kd5ujc.scarab.models.CuddleCrossCheck export ..."`.
  */
object CuddleCrossCheck:
  private val cddlDir = Path.of("models/src/main/cddl")

  /** What is cross-checked: every `root` of `models.conf`, plus the generated types the sampled
    * suite exercises (headers of every era, the hard-fork envelope, point and tip).
    */
  val targets: Vector[(String, Codec[?])] = Vector(
    "handshake-node-to-node-v14:handshakeMessage" -> handshake.HandshakeMessage,
    "chain-sync:chainSyncMessage" -> chainsync.ChainSyncMessage,
    "peer-sharing-v14:peerSharingMessage" -> peersharing.PeerSharingMessage,
    "leios-notify:leiosNotifyMessage" -> leios.notify.LeiosNotifyMessage,
    "conway:header" -> conway.Header,
    "conway:block" -> conway.Block,
    "conway:transaction" -> conway.Transaction,
    "shelley:block" -> shelley.Block,
    "allegra:block" -> allegra.Block,
    "mary:block" -> mary.Block,
    "alonzo:block" -> alonzo.Block,
    "babbage:block" -> babbage.Block,
    "features:transaction" -> features.Transaction,
    "features:key_descriptor" -> features.KeyDescriptor,
    "features:person" -> features.Person,
    "features:prelude_use" -> features.PreludeUse,
    // generated, but not roots
    "shelley:header" -> shelley.Header,
    "allegra:header" -> allegra.Header,
    "mary:header" -> mary.Header,
    "alonzo:header" -> alonzo.Header,
    "babbage:header" -> babbage.Header,
    "dijkstra:header" -> dijkstra.Header,
    "chainsync-header:header" -> chain.hfc.Header,
    "network.base:tip" -> common.Tip,
    "base:point" -> chain.Point
  )

  def fileName(id: String): String = id.replace(":", "__") + ".hex"

  private def write(p: Path, lines: Iterable[String]): Unit =
    Files.createDirectories(p.getParent)
    Files.writeString(p, lines.mkString("", "\n", "\n"), UTF_8)

  private def die(msg: String): Nothing =
    System.err.println(s"cross-check: $msg")
    sys.exit(1)

  def main(args: Array[String]): Unit = args.toList match
    case "export" :: dir :: count :: seed :: Nil =>
      exportTerms(Path.of(dir), count.toInt, seed.toLong)
    case "check" :: dir :: Nil => check(Path.of(dir))
    case other =>
      die(s"usage: export <dir> <count> <seed> | check <dir>; got ${other.mkString(" ")}")

  private def exportTerms(dir: Path, count: Int, seed: Long): Unit =
    val config = Generator.configFrom(cddlDir).fold(e => die(e.message), identity)
    val schema = Generator.load(cddlDir, config).fold(e => die(e.message), identity)
    val ids = targets.map(_._1).toSet
    val missing = config.roots.map(_.toString).filterNot(ids)
    if missing.nonEmpty then
      die(s"roots of models.conf without a codec here: ${missing.mkString(", ")}")
    val sampler = new CddlSampler(schema)
    val rootIds = config.roots.map(_.toString).toSet
    write(
      dir.resolve("targets.tsv"),
      targets.map((id, _) => s"$id\t${if rootIds(id) then "root" else "derived"}")
    )
    targets.foreach { (id, _) =>
      val Array(m, n) = id.split(":", 2): @unchecked
      val rnd = new Random(seed ^ id.hashCode.toLong)
      write(
        dir.resolve("ours").resolve(fileName(id)),
        Vector.fill(count)(Bytes.fromArray(sampler.sample(QName(m, n), rnd)).hex)
      )
    }
    // Rule names and arities as our parser reads each vendored file (cuddle reads the same files).
    val files = Files.walk(cddlDir).iterator().asScala.filter(_.toString.endsWith(".cddl")).toVector
    val rows = files.sorted.flatMap { f =>
      val rel = cddlDir.relativize(f).toString
      CddlParser.parse(Files.readString(f, UTF_8)) match
        case Left(e) => Vector(s"$rel\t!parse-error\t0\t${e.getMessage.replace('\t', ' ')}")
        case Right(parsed) =>
          parsed.rules.map(r => (r.name, r.params.size)).distinct.map((n, a) => s"$rel\t$n\t$a\t")
    }
    write(dir.resolve("rules.tsv"), rows)
    write(dir.resolve("unsupported.tsv"), unsupported(schema, config))
    println(
      s"exported ${targets.size} targets x $count terms and ${rows.size} rules of ${files.size} files"
    )

  /** Non-generic rules that cannot be generated on their own (as in GeneratorSuite's coverage
    * test), as `module<TAB>rule<TAB>reason`.
    */
  private def unsupported(schema: Schema, config: Config): Vector[String] =
    for
      m <- config.modules.map(_.name).sorted
      n <- schema
        .modules(m)
        .rules
        .collect { case (n, (ds, _)) if ds.head.params.isEmpty => n }
        .toVector
        .sorted
      single = config.copy(
        roots = Vector(QName(m, n)),
        preserve = Set.empty,
        external = Set.empty,
        cases = Map.empty
      )
      reason <- failure(schema, single)
    yield s"$m\t$n\t$reason"

  private def failure(schema: Schema, config: Config): Option[String] =
    try
      new Emitter(schema, config).generate()
      None
    catch
      case GenException(GenError.Unsupported(_, f))    => Some(f.takeWhile(_ != '$').take(60))
      case GenException(GenError.UnresolvedName(_, n)) => Some(s"unresolved name `$n`")
      case GenException(other)                         => Some(other.getClass.getSimpleName)

  private def check(dir: Path): Unit =
    val rows = for
      mode <- Vector("exact", "twiddle")
      (id, codec) <- targets
      f = dir.resolve("cuddle").resolve(mode).resolve(fileName(id))
      if Files.exists(f)
    yield checkFile(id, codec, mode, f)
    write(dir.resolve("decode.tsv"), rows)
    println(s"checked ${rows.size} (target, mode) files")

  private def checkFile(id: String, codec: Codec[?], mode: String, f: Path): String =
    val terms = Files.readAllLines(f, UTF_8).asScala.toVector.filter(_.nonEmpty)
    var accepted = 0
    var exact = 0
    var stable = 0
    var floats = 0
    var firstReject = ""
    var firstInexact = ""
    def clean(s: String): String = s.replace('\t', ' ').replace('\n', ' ')
    terms.foreach { hex =>
      val input = Bytes.fromHex(hex).fold(e => die(s"$f: $e"), identity)
      codec.decodeBytes(input) match
        // xyz.kd5ujc.scarab.codec has no floating-point item: a float inside an `any` is a known, listed rejection
        case Left(e) if e.deepest.render.contains("float") => floats += 1
        case Left(e) =>
          if firstReject.isEmpty then firstReject = s"$hex (${clean(e.deepest.render)})"
        case Right(value) =>
          accepted += 1
          val again = encode(codec, value)
          if again == input then exact += 1
          else if firstInexact.isEmpty then firstInexact = s"$hex (re-encoded as ${again.hex})"
          // The re-encoding must itself decode to the same value and be a fixed point.
          codec.decodeBytes(again) match
            case Right(v2) if v2 == value && encode(codec, v2) == again => stable += 1
            case _ =>
              if firstReject.isEmpty then firstReject = s"$hex (re-encoding is not stable)"
    }
    Seq(id, mode, terms.size, accepted, exact, stable, floats, firstReject, firstInexact)
      .mkString("\t")

  private def encode(codec: Codec[?], value: Any): Bytes =
    codec.asInstanceOf[Codec[Any]].encodeUnchecked(value)
