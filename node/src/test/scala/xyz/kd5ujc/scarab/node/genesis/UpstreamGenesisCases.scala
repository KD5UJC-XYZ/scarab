// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.genesis

import cats.effect.IO
import cats.syntax.all.*
import io.circe.{Decoder, DecodingFailure, HCursor}
import io.circe.parser.parse
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.zip.GZIPInputStream
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.consensus.genesis.GenesisDensity.Fragment
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, UInt64}

/** The complete original peer-simulator input and observable result, without fixture-specific
  * substitutions. The server's tip, available header and available body are independent inputs.
  */
object UpstreamGenesisCases:
  enum Family:
    case LoE, DensityDisconnect, LoP, CSJ, Uniform, LongRangeAttack
  enum PeerId:
    case Honest(index: Int)
    case Adversarial(index: Int)
  enum Component:
    case ChainSyncClient, ChainSyncServer, BlockFetchClient, BlockFetchServer
  enum End:
    case Success, PatienceExpired, DensityTooLow, CandidateTooSparse, Timeout, Cancelled, Unexpected
  enum Event:
    case Tip(point: Point)
    case Header(point: Point)
    case Block(point: Point)
  final case class Timed(atPicos: BigInt, event: Event)
  final case class Schedule(peer: PeerId, events: Vector[Timed])
  final case class NodeState(tip: Point, header: Point, block: Point)
  final case class Tick(atPicos: BigInt, durationPicos: BigInt, peer: PeerId, state: NodeState)
  final case class RuntimeConfig(
      chainSyncTimeouts: Boolean,
      blockFetchTimeouts: Boolean,
      debug: Boolean,
      trace: Boolean,
      traceState: Boolean,
      loe: Boolean,
      lop: Boolean,
      downtimePicos: Option[BigInt],
      starvation: Boolean,
      jumping: Boolean
  )
  final case class ChainSyncTimeouts(
      canAwait: Option[BigInt],
      intersect: Option[BigInt],
      mustReply: Option[BigInt],
      idle: Option[BigInt]
  )
  final case class BlockFetchTimeouts(busy: Option[BigInt], streaming: Option[BigInt])
  final case class PeerOutcome(peer: PeerId, component: Component, end: End, rendering: String)
  final case class Expected(
      selection: Fragment,
      tipBlock: Point,
      peers: Vector[PeerOutcome],
      renderings: Vector[String],
      propertyPassed: Boolean,
      expectedExceptionsPassed: Boolean,
      combinedPassed: Boolean
  )
  final case class Scenario(
      label: String,
      family: Family,
      description: String,
      seed: Int,
      generatorBaseSize: Int,
      generatorSize: Int,
      k: Int,
      genesisWindow: BigInt,
      forecastRange: BigInt,
      delay: BigInt,
      slotLengthPicos: BigInt,
      extraHonestPeers: Int,
      config: RuntimeConfig,
      bucketCapacity: Rational,
      bucketRate: Rational,
      jumpSize: BigInt,
      chainSync: ChainSyncTimeouts,
      blockFetch: BlockFetchTimeouts,
      tree: Vector[Fragment],
      startOrder: Vector[PeerId],
      minEndTimePicos: BigInt,
      schedules: Vector[Schedule],
      timeline: Vector[Tick],
      completeInputRendering: String,
      expected: Expected
  )

  given Decoder[NodeState] = Decoder.instance { c =>
    for
      tip <- c.get[Point]("tip")
      header <- c.get[Point]("header")
      block <- c.get[Point]("block")
    yield NodeState(tip, header, block)
  }
  given Decoder[Tick] = Decoder.instance { c =>
    for
      time <- c.get[BigInt]("timePicos")
      duration <- c.get[BigInt]("durationPicos")
      peer <- c.get[PeerId]("peer")
      state <- c.get[NodeState]("state")
    yield Tick(time, duration, peer, state)
  }

  private def closed[A](cursor: HCursor, choices: Map[String, A]): Decoder.Result[A] =
    cursor
      .as[String]
      .flatMap(value =>
        choices
          .get(value)
          .toRight(DecodingFailure(s"unknown closed fixture tag: $value", cursor.history))
      )

  private given Decoder[Family] = Decoder.instance(cursor =>
    closed(cursor, Family.values.map(value => value.toString -> value).toMap)
  )
  private given Decoder[PeerId] = Decoder.instance { cursor =>
    cursor.as[String].flatMap { text =>
      val parts = text.split(" ", -1).toVector
      parts match
        case Vector("HonestPeer", value) =>
          value.toIntOption
            .map(PeerId.Honest(_))
            .toRight(DecodingFailure("invalid honest peer number", cursor.history))
        case Vector("AdversarialPeer", value) =>
          value.toIntOption
            .map(PeerId.Adversarial(_))
            .toRight(DecodingFailure("invalid adversarial peer number", cursor.history))
        case _ => Left(DecodingFailure("invalid native peer identifier", cursor.history))
    }
  }
  private given Decoder[Component] = Decoder.instance(cursor =>
    closed(
      cursor,
      Map(
        "chainSyncClient" -> Component.ChainSyncClient,
        "chainSyncServer" -> Component.ChainSyncServer,
        "blockFetchClient" -> Component.BlockFetchClient,
        "blockFetchServer" -> Component.BlockFetchServer
      )
    )
  )
  private given Decoder[End] = Decoder.instance(cursor =>
    closed(
      cursor,
      Map(
        "success" -> End.Success,
        "EmptyBucketFailure" -> End.PatienceExpired,
        "DensityTooLowFailure" -> End.DensityTooLow,
        "CandidateTooSparseFailure" -> End.CandidateTooSparse,
        "TimeoutFailure" -> End.Timeout,
        "CancelledFailure" -> End.Cancelled,
        "UnexpectedFailure" -> End.Unexpected
      )
    )
  )
  private given Decoder[Point] = Decoder.instance { cursor =>
    if cursor.value.isNull then Right(Point.Origin)
    else
      for
        slot <- cursor.get[BigInt]("slot")
        number <- UInt64
          .from(slot)
          .leftMap(error => DecodingFailure(error.toString, cursor.history))
        hex <- cursor.get[String]("hash")
        hash <- Bytes.fromHex(hex).leftMap(error => DecodingFailure(error.toString, cursor.history))
      yield Point.Block(number, hash)
  }
  private given Decoder[Fragment] = Decoder.instance { cursor =>
    for
      anchor <- cursor.get[Point]("anchor")
      points <- cursor.get[Vector[Point]]("headers")
      headers <- points.traverse {
        case block: Point.Block => Right(block)
        case Point.Origin => Left(DecodingFailure("Origin cannot be a header", cursor.history))
      }
    yield Fragment(anchor, headers)
  }
  private given Decoder[Rational] = Decoder.instance { cursor =>
    for
      numerator <- cursor.get[BigInt]("numerator")
      denominator <- cursor.get[BigInt]("denominator")
      _ <- Either.cond(
        denominator > 0,
        (),
        DecodingFailure("nonpositive rational denominator", cursor.history)
      )
    yield Rational(numerator, denominator)
  }
  private given Decoder[Event] = Decoder.instance { cursor =>
    for
      tag <- cursor.get[String]("tag")
      point <- cursor.get[Point]("point")
      event <- Map(
        "tip" -> Event.Tip(point),
        "header" -> Event.Header(point),
        "block" -> Event.Block(point)
      )
        .get(tag)
        .toRight(DecodingFailure("unknown availability event", cursor.history))
    yield event
  }
  private given Decoder[Timed] = Decoder.instance(cursor =>
    (cursor.get[BigInt]("timePicos"), cursor.get[Event]("event")).mapN(Timed.apply)
  )
  private given Decoder[Schedule] = Decoder.instance(cursor =>
    (cursor.get[PeerId]("peer"), cursor.get[Vector[Timed]]("events")).mapN(Schedule.apply)
  )
  private given Decoder[PeerOutcome] = Decoder.instance(cursor =>
    (
      cursor.get[PeerId]("peer"),
      cursor.get[Component]("component"),
      cursor.get[End]("outcome"),
      cursor.get[String]("completeResultRendering")
    ).mapN(PeerOutcome.apply)
  )
  private given Decoder[RuntimeConfig] = Decoder.instance { c =>
    (
      c.get[Boolean]("enableChainSyncTimeouts"),
      c.get[Boolean]("enableBlockFetchTimeouts"),
      c.get[Boolean]("debug"),
      c.get[Boolean]("trace"),
      c.get[Boolean]("traceState"),
      c.get[Boolean]("enableLoE"),
      c.get[Boolean]("enableLoP"),
      c.get[Option[BigInt]]("downtimePicos"),
      c.get[Boolean]("enableChainSelStarvation"),
      c.get[Boolean]("enableCSJ")
    ).mapN(RuntimeConfig.apply)
  }
  private given Decoder[ChainSyncTimeouts] = Decoder.instance(c =>
    (
      c.get[Option[BigInt]]("canAwaitPicos"),
      c.get[Option[BigInt]]("intersectPicos"),
      c.get[Option[BigInt]]("mustReplyPicos"),
      c.get[Option[BigInt]]("idlePicos")
    ).mapN(ChainSyncTimeouts.apply)
  )
  private given Decoder[BlockFetchTimeouts] = Decoder.instance(c =>
    (c.get[Option[BigInt]]("busyPicos"), c.get[Option[BigInt]]("streamingPicos"))
      .mapN(BlockFetchTimeouts.apply)
  )
  private given Decoder[Expected] = Decoder.instance(c =>
    (
      c.get[Fragment]("selectedChain"),
      c.get[Point]("tipBlock"),
      c.get[Vector[PeerOutcome]]("peerOutcomes"),
      c.get[Vector[String]]("peerResults"),
      c.get[Boolean]("ctPropertyPassed"),
      c.get[Boolean]("expectedExceptionsPassed"),
      c.get[Boolean]("propertyPassed")
    ).mapN(Expected.apply)
  )
  given Decoder[Scenario] = Decoder.instance { c =>
    for
      label <- c.get[String]("label")
      family <- c.get[Family]("family")
      description <- c.get[String]("description")
      seed <- c.get[Int]("seed")
      baseSize <- c.get[Int]("generatorBaseSize")
      size <- c.get[Int]("generatorSize")
      k <- c.get[Int]("k")
      window <- c.get[BigInt]("genesisWindow")
      forecast <- c.get[BigInt]("forecastRange")
      delay <- c.get[BigInt]("delay")
      slotLength <- c.get[BigInt]("slotLengthPicos")
      extra <- c.get[Int]("extraHonestPeers")
      config <- c.get[RuntimeConfig]("schedulerConfig")
      capacity <- c.get[Rational]("lopCapacity")
      rate <- c.get[Rational]("lopRate")
      jump <- c.get[BigInt]("csjJumpSize")
      cs <- c.get[ChainSyncTimeouts]("chainSyncTimeouts")
      bf <- c.get[BlockFetchTimeouts]("blockFetchTimeouts")
      tree <- c.get[Vector[Fragment]]("treeFragments")
      order <- c.get[Vector[PeerId]]("startOrder")
      end <- c.get[BigInt]("minEndTimePicos")
      schedules <- c.get[Vector[Schedule]]("schedules")
      timeline <- c.get[Vector[Tick]]("stateTimeline")
      rendering <- c.get[String]("completeInputRendering")
      expected <- c.as[Expected]
    yield Scenario(
      label,
      family,
      description,
      seed,
      baseSize,
      size,
      k,
      window,
      forecast,
      delay,
      slotLength,
      extra,
      config,
      capacity,
      rate,
      jump,
      cs,
      bf,
      tree,
      order,
      end,
      schedules,
      timeline,
      rendering,
      expected
    )
  }

  def read: IO[Either[io.circe.Error, Vector[Scenario]]] = IO.blocking {
    val input = new GZIPInputStream(
      Files.newInputStream(Path.of("fixtures/reference/genesis-density/loe.jsonl.gz"))
    )
    val contents =
      try new String(input.readAllBytes(), StandardCharsets.UTF_8)
      finally input.close()
    contents.linesIterator.toVector.traverse(line => parse(line).flatMap(_.as[Scenario]))
  }
