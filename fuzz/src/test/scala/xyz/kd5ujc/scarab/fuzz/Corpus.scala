// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.fuzz

import cats.syntax.all.*
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.protocol.{Era, NtNHeader}
import xyz.kd5ujc.scarab.ledger.Block
import xyz.kd5ujc.scarab.ledger.byron.ByronBody
import xyz.kd5ujc.scarab.ledger.{Address, TxBody}
import scala.jdk.CollectionConverters.*
import scala.util.Random
import xyz.kd5ujc.scarab.models.gen.{CddlSampler, Config, GenError, Generator, QName}

/** The seeds the mutations start from: committed fixtures, and valid CBOR sampled from the pinned
  * CDDL.
  */
object Corpus extends munit.Assertions:
  /** Hex lines of the committed block fixtures (`[era tag, block]`), Byron to Conway. */
  private val blockFiles = Vector(
    "fixtures/preview/alonzo-first-20.hex",
    "fixtures/preview/alonzo-txs.hex",
    "fixtures/preview/babbage-mint.hex",
    "fixtures/preview/babbage-transition.hex",
    "fixtures/preview/conway-governance.hex",
    "fixtures/preview/conway.hex",
    "fixtures/preview/empty-redeemers.hex",
    "fixtures/preprod/blocks.hex",
    "fixtures/mainnet/byron-main-blocks.hex",
    "fixtures/mainnet/byron-tx-blocks.hex",
    "fixtures/eras/blocks.hex"
  )

  private def lines(file: String): Vector[Array[Byte]] =
    Files
      .readAllLines(Path.of(file))
      .asScala
      .toVector
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap(l => Bytes.fromHex(l).toOption.map(_.toArray))

  /** Every committed block, a bounded sample of the 731 era blocks. */
  lazy val blocks: Vector[Array[Byte]] =
    val rnd = new Random(7)
    blockFiles.flatMap { f =>
      val ls = lines(f)
      if ls.size > 80 then rnd.shuffle(ls).take(80) else ls
    }

  /** Mainnet Byron slots per epoch (10 k). */
  val byronEpochSlots: BigInt = BigInt(21600)

  private lazy val decoded: Vector[Block] =
    blocks.flatMap(b => Block.decode(Bytes.fromArray(b), byronEpochSlots).toOption)

  private lazy val shelleyBlocks: Vector[Block.Shelley] = decoded.collect { case b: Block.Shelley =>
    b
  }

  def blocksOf(era: Era): Vector[Array[Byte]] =
    blocks.filter { b =>
      Block.decode(Bytes.fromArray(b), byronEpochSlots).toOption.exists(_.era == era)
    }

  /** Raw headers `[header_body, kes_signature]` of an era. */
  def rawHeaders(era: Era): Vector[Array[Byte]] =
    decoded.filter(_.era == era).collect { case b: Block.Shelley => b.header.raw.toArray }

  /** Node-to-node ChainSync header envelopes `[era, tag24(header)]`. */
  lazy val headerEnvelopes: Vector[Array[Byte]] =
    decoded.flatMap {
      case b: Block.Shelley =>
        NtNHeader.codec.encode(NtNHeader(b.era, b.header.raw, None)).toOption.map(_.toArray)
      case _ => None
    }

  /** The column `column` (hex) of a tab-separated fixture of the Leios testnet. */
  private def leiosColumn(file: String, column: Int): Vector[Array[Byte]] =
    Files
      .readAllLines(Path.of("fixtures", "leios", "testnet", file))
      .asScala
      .toVector
      .filterNot(l => l.startsWith("#") || l.isEmpty)
      .flatMap(l => Bytes.fromHex(l.split('\t')(column)).toOption.map(_.toArray))

  /** Header envelopes `[era, tag24(header)]` of the public Leios testnet (HFC index 6 and 7). */
  lazy val leiosEnvelopes: Vector[Array[Byte]] =
    leiosColumn("headers-notable.tsv", 5) ++ leiosColumn("headers-from-origin.tsv", 4).take(40)

  /** The 12-element Leios headers inside those envelopes. */
  lazy val leiosHeaders: Vector[Array[Byte]] =
    leiosEnvelopes.flatMap { e =>
      xyz.kd5ujc.scarab.protocol.HfcHeader.decode(Bytes.fromArray(e)).toOption.collect {
        case xyz.kd5ujc.scarab.protocol.HfcHeader.Supported(h) => h.raw.toArray
        case xyz.kd5ujc.scarab.protocol.HfcHeader.Dijkstra(r)  => r.toArray
      }
    }

  /** Blocks of tag 7 and 8 of the same chain. */
  lazy val leiosBlocks: Vector[Array[Byte]] = leiosColumn("blocks.tsv", 5)

  def txBodies(era: Era): Vector[Array[Byte]] =
    shelleyBlocks.filter(_.era == era).flatMap(_.transactions.map(_.body.toArray)) ++
      lines("fixtures/reference/remaining-predicates/metadata-bodies.hex")
  def txWitnesses(era: Era): Vector[Array[Byte]] =
    shelleyBlocks.filter(_.era == era).flatMap(_.transactions.map(_.witnesses.toArray))
  def txAux(era: Era): Vector[Array[Byte]] =
    shelleyBlocks
      .filter(_.era == era)
      .flatMap(_.transactions.flatMap(_.auxiliaryData.map(_.toArray)))

  /** Whole transactions `[body, witnesses, is_valid, aux]` as a client submits them. */
  lazy val transactions: Vector[Array[Byte]] =
    shelleyBlocks.flatMap { b =>
      b.transactions.map { t =>
        val aux = t.auxiliaryData.fold(Array(0xf6.toByte))(_.toArray)
        Array(0x84.toByte) ++ t.body.toArray ++ t.witnesses.toArray ++
          Array((if t.isValid then 0xf5 else 0xf4).toByte) ++ aux
      }
    }

  /** Byron block bodies, as kept raw in `Block.Byron`. */
  lazy val byronBodies: Vector[Array[Byte]] =
    decoded.collect { case b: Block.Byron => b.body.toArray }

  /** All the small hex fixtures of the network directory (handshake, mux, BlockFetch envelopes). */
  lazy val networkFixtures: Vector[Array[Byte]] =
    val dirs = Vector("fixtures/network", "fixtures/network/block-fetch")
    dirs.flatMap { d =>
      Files
        .list(Path.of(d))
        .iterator()
        .asScala
        .filter(_.toString.endsWith(".hex"))
        .toVector
        .sorted
        .flatMap { p =>
          lines(p.toString)
        }
    }

  /** CBOR shapes that strain a decoder: bignums, nesting, indefinite items, tags, duplicate keys.
    */
  val tricky: Vector[Array[Byte]] =
    Vector(
      "00",
      "1bffffffffffffffff",
      "3bffffffffffffffff",
      "c249010000000000000000",
      "c34900ffffffffffffffff",
      "5f4101410241ffff",
      "7f6161ff",
      "9f9f9f9fffffffff",
      "bf6161bf6161f6ffff",
      "a2616101616102",
      "a1a1a1a1a100",
      "d818430102 03".replace(" ", ""),
      "d90102830102 03".replace(" ", ""),
      "d87982d87980d87a80",
      "d8799fd87980d87a9fffff",
      "82a0a0",
      "8301f5f6",
      "826161a1626263f6",
      "d9d9f700"
    ).map(h => Bytes.fromHex(h).fold(e => throw new IllegalStateException(e.toString), _.toArray))

  // --- CDDL sampling ------------------------------------------------------------------------------

  private val specs = "upstream/ouroboros-network/cardano-diffusion/protocols/cddl/specs"
  private val eraDir = "upstream/cardano-ledger/eras"
  private val eras = Vector("shelley", "allegra", "mary", "alonzo", "babbage", "conway", "dijkstra")

  /** The pinned CDDL of the mini-protocols and the eras, loaded as one schema (not the spike's own
    * configuration, which names fewer modules).
    */
  private val conf: String =
    val network = Vector(
      s"module network.base base $specs/network.base.cddl overrides/network-base.cddl",
      s"module keep-alive ka $specs/keep-alive.cddl",
      s"module peer-sharing-v14 ps $specs/peer-sharing-v14.cddl",
      s"module chain-sync cs $specs/chain-sync.cddl",
      s"module block-fetch bf $specs/block-fetch.cddl",
      s"module tx-submission2 ts $specs/tx-submission2.cddl",
      s"module local-state-query lsq $specs/local-state-query.cddl",
      s"module local-tx-submission lts $specs/local-tx-submission.cddl",
      s"module local-tx-monitor ltm $specs/local-tx-monitor.cddl",
      s"module node-to-node-version-data-v14 v14 $specs/node-to-node-version-data-v14.cddl",
      s"module node-to-node-version-data-v16 v16 $specs/node-to-node-version-data-v16.cddl",
      s"module handshake-node-to-node-v14 hs $specs/handshake-node-to-node-v14.cddl",
      s"module handshake-node-to-client hc $specs/handshake-node-to-client.cddl",
      "module base chain upstream/ouroboros-consensus/ouroboros-consensus-cardano/cddl/base.cddl",
      "module chainsync-header hfc upstream/ouroboros-consensus/ouroboros-consensus-cardano/cddl/node-to-node/chainsync/header.cddl"
    )
    val ledger = eras.map { e =>
      val extra =
        if e == "alonzo" || e == "babbage" || e == "conway" then " overrides/bounded-bytes.cddl"
        else ""
      s"module $e $e $eraDir/$e/impl/cddl/data/$e.cddl$extra"
    }
    ("package fuzz" +: (network ++ ledger)).mkString("\n")

  private lazy val sampler: CddlSampler =
    val dir = Path.of("models/src/main/cddl")
    Config
      .parse(conf)
      .flatMap(Generator.load(dir, _))
      .map(new CddlSampler(_))
      .fold(e => fail(e.message), identity)

  /** Valid items of a CDDL rule (`module:rule`); empty when the sampler cannot express the rule. */
  def sampled(rule: String, count: Int = 60, seed: Long = 11): Vector[Array[Byte]] =
    val Array(m, n) = rule.split(":", 2): @unchecked
    val rnd = new Random(seed ^ rule.hashCode)
    Vector
      .fill(count)(())
      .traverse(_ => sampler.sample(QName(m, n), rnd))
      .fold(
        {
          // This optional seed catalogue uses partial CDDL modules. A rule whose imports or
          // dependencies are absent cannot supply seeds; configuration/loading defects still fail.
          case GenError.Unsupported(_, _) | GenError.SamplingCycle(_) |
              GenError.UnknownModule(_, _) | GenError.UnresolvedName(_, _) =>
            Vector.empty
          case other => fail(other.message)
        },
        identity
      )

  def sampledEras(rule: String, from: String = "shelley"): Vector[Array[Byte]] =
    eras.dropWhile(_ != from).flatMap(e => sampled(s"$e:$rule", 25)).toVector

  lazy val byronHeaders: Vector[Array[Byte]] =
    decoded.collect { case b: Block.Byron => b.header.raw.toArray }

  /** Original submitted-transaction envelopes: three fields before Alonzo, four from Alonzo. */
  def transactionsOf(era: Era): Vector[Array[Byte]] =
    val reference = era match
      case Era.Shelley | Era.Allegra | Era.Mary =>
        lines(s"fixtures/fuzz/early-era-transactions/${era.toString.toLowerCase}.hex")
      case _ => Vector.empty
    reference ++ shelleyBlocks.filter(_.era == era).flatMap(_.transactions.map(wholeTx(_, era)))

  /** Auxiliary boundary bytes enter the real submitted-transaction decoder, rather than a test-only
    * parser. The enclosing body/witnesses are committed era transaction inputs.
    */
  def auxiliaryTransactions(era: Era): Vector[Array[Byte]] =
    if era.ordinal < Era.Shelley.ordinal then Vector.empty
    else
      val template = transactionsOf(era).headOption
        .flatMap(bytes => asTransaction(Bytes.fromArray(bytes), era))
        .getOrElse(fail(s"no transaction template for $era"))
      val rows = io.circe.parser
        .parse(
          Files.readString(
            Path.of(
              if era.hasInvalidTransactions then
                "fixtures/reference/auxiliary-scripts/auxiliary.json"
              else "fixtures/reference/auxiliary-historical/auxiliary.json"
            )
          )
        )
        .fold(error => fail(error.message), identity)
        .asArray
        .getOrElse(fail("not an array"))
      rows
        .filter(_.hcursor.get[String]("era").toOption.contains(era.toString))
        .filter(_.hcursor.get[String]("mode").forall(_ == "aux"))
        .map { row =>
          val auxiliary = row.hcursor
            .get[String]("auxiliary")
            .fold(error => fail(error.message), identity)
          val raw = Bytes.fromHex(auxiliary).fold(error => fail(error.toString), identity)
          wholeTx(template.copy(auxiliaryData = Some(raw)), era)
        }
        // Seeds that are no longer one envelope (empty or extra bytes, too deep) are `tricky`'s.
        .filter(bytes =>
          asTransaction(Bytes.fromArray(bytes), era).exists(_.auxiliaryData.nonEmpty)
        )

  private def wholeTx(t: Block.Transaction, era: Era): Array[Byte] =
    val aux = t.auxiliaryData.fold(Array(0xf6.toByte))(_.toArray)
    val head = Array((if era.hasInvalidTransactions then 0x84 else 0x83).toByte)
    val valid =
      if era.hasInvalidTransactions then Array((if t.isValid then 0xf5 else 0xf4).toByte)
      else Array.emptyByteArray
    head ++ t.body.toArray ++ t.witnesses.toArray ++ valid ++ aux

  /** Split the era's submitted-transaction envelope into the parts a block holds. */
  def asTransaction(b: Bytes, era: Era): Option[Block.Transaction] =
    def transaction(body: Node, wit: Node, aux: Node, valid: Boolean): Block.Transaction =
      Block.Transaction(
        body.original,
        wit.original,
        aux.value match
          case Value.Null => None
          case _          => Some(aux.original)
        ,
        valid
      )
    Cbor.decode(b).toOption.flatMap { n =>
      n.value match
        case Value.Arr(Vector(body, wit, aux)) if era != Era.Byron && !era.hasInvalidTransactions =>
          Some(transaction(body, wit, aux, true))
        case Value.Arr(Vector(body, wit, valid, aux)) if era.hasInvalidTransactions =>
          valid.value match
            case Value.Bool(v) => Some(transaction(body, wit, aux, v))
            case _             => None
        case _ => None
    }

  /** Addresses of the outputs of the committed transactions (Shelley and Byron). */
  lazy val addresses: Vector[Array[Byte]] =
    shelleyBlocks
      .flatMap(_.transactions)
      .flatMap(t =>
        TxBody.decode(t.body, Address.Mode.Backwards).toOption.toVector.flatMap(_.outputs)
      )
      .map(_.address.raw.toArray)
      .distinct
      .take(200)

  lazy val byronAddresses: Vector[Array[Byte]] =
    byronBodies
      .flatMap(b => ByronBody.decode(Bytes.fromArray(b)).toOption.toVector.flatMap(_.txs))
      .flatMap(_.tx.outputs.map(_.address.toArray))
      .distinct
      .take(50)

  lazy val plutusData: Vector[Array[Byte]] =
    val chunks = Files
      .readAllLines(Path.of("fixtures/reference/plutus-data-chunks/cases.tsv"))
      .asScala
      .toVector
      .filterNot(_.startsWith("#"))
      .map { line =>
        line.split("\t").toVector match
          case Vector(_, hex, _, _, _) => Bytes.fromHex(hex).fold(e => fail(e.toString), _.toArray)
          case other                   => fail(s"malformed native chunk fixture: $other")
      }
    lines("fixtures/preview/deeply-nested-datum.hex") ++ sampled("conway:plutus_data", 60) ++ chunks

  lazy val plutusFlat: Vector[Array[Byte]] =
    Files
      .readAllLines(Path.of("fixtures/reference/plutus-boundaries/scripts.jsonl"))
      .asScala
      .toVector
      .flatMap { line =>
        val script = io.circe.parser
          .parse(line)
          .flatMap(_.hcursor.get[String]("script"))
          .fold(throw _, identity)
        val bytes = Bytes.fromHex(script).fold(e => sys.error(e.toString), _.toArray)
        xyz.kd5ujc.scarab.plutus.PlutusScriptBytes.flatOf(bytes).toVector
      }

  /** Node-to-client queries in the envelope `cardano-cli` writes: `[0, [0, [6, query]]]`. */
  lazy val ntcQueries: Vector[Array[Byte]] =
    def h(n: Int) = Array((0x58).toByte, n.toByte) ++ Array.fill(n)(0xab.toByte)
    val args = Vector(
      Array.empty[Byte],
      Array(0x80.toByte),
      Array(0x81.toByte, 0x82.toByte, 0x00.toByte) ++ h(28),
      Array(0xd9, 0x01, 0x02, 0x81, 0x82, 0x00).map(_.toByte) ++ h(28),
      h(28),
      Array(0x82.toByte) ++ h(32) ++ Array(0x00.toByte)
    )
    val ledger = for tag <- 0 to 45; a <- args yield
      val body = Array((if a.isEmpty then 0x81 else 0x82).toByte, tag.toByte) ++ a
      Array(0x82, 0x00, 0x82, 0x00, 0x82, 0x06).map(_.toByte) ++ body
    val top = (0 to 4).map(t => Array(0x81.toByte, t.toByte)).toVector
    val hard = (0 to 2).map(t => Array(0x82, 0x00, 0x82, 0x02, 0x81, t).map(_.toByte)).toVector
    ledger.toVector ++ top ++ hard
