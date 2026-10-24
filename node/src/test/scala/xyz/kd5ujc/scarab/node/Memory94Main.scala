// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.mtl.Ask
import java.nio.ByteBuffer
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.consensus.state.ConsensusMirrorEnv
import xyz.kd5ujc.scarab.storage.{SnapshotCheck, SnapshotV7}
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellLedgerFile, HaskellState}

/** SPIKE for issue #94 (docs/design/memory-lean-decoding.md): what the snapshot read-back costs in
  * heap, stage by stage. Not a test and not part of the node: it is run by hand under different
  * `-Xmx` values inside the JDK build container to find the minimum heap of each stage.
  *
  * {{{
  * java -Xmx3g -cp <test classpath> xyz.kd5ujc.scarab.node.Memory94Main <mode> <snapshot> [genesis-dir]
  * }}}
  *
  * Modes: `cbor` (`Cbor.decode` of the Haskell section: since stage 1 of #94 the index of its
  * items, before it the object tree), `ledger` (`HaskellLedgerFile.decode`), `v4` (the whole
  * `SnapshotV7` read), `written` (the node's write of the decoded state, with its read-back),
  * `votes` (the synthetic committee-votes map, `VOTES=<n>` entries),
  * `spam <snapshot> <voters> <out>` (writes a snapshot with the vote spam of epochs 188 to 193 put
  * into the Conway gov state of a current one, see [[spamMain]]), `tape` (the offsets-only index of
  * the first spike, which `Cbor.decode` has replaced). Each prints the heap in use after a full GC
  * while the result is still reachable (the retained size) and the wall time. An `OutOfMemoryError`
  * ends the run with exit 3. The snapshot file is the content followed by its 32-byte checksum,
  * which the mode drops without checking it.
  */
object Memory94Main:
  private def usedMb(): Long =
    System.gc(); System.gc()
    val rt = Runtime.getRuntime
    (rt.totalMemory - rt.freeMemory) / (1024 * 1024)

  /** Option 1, as a spike: an index with two ints per CBOR item (where its head starts; for a
    * container, the index one past its last descendant), built in one pass with an explicit stack.
    * The items are read back lazily from the shared array. Counts the items by major type.
    */
  final class Tape(val starts: Array[Int], val skips: Array[Int], val size: Int)

  def tape(data: Array[Byte], from: Int): Tape =
    var starts = new Array[Int](1 << 20)
    var skips = new Array[Int](1 << 20)
    var n = 0
    var pos = from
    // an open container: its own tape index and the items still to read (-1: indefinite)
    var openIdx = new Array[Int](64)
    var openLeft = new Array[Long](64)
    var depth = 0
    def grow(): Unit =
      if n == starts.length then
        starts = java.util.Arrays.copyOf(starts, n * 2)
        skips = java.util.Arrays.copyOf(skips, n * 2)
    def arg(ai: Int): Long =
      if ai < 24 then ai.toLong
      else
        val count = 1 << (ai - 24)
        var v = 0L
        var i = 0
        while i < count do
          v = (v << 8) | (data(pos + i) & 0xffL)
          i += 1
        pos += count
        v
    def close(): Unit =
      depth -= 1
      skips(openIdx(depth)) = n
    // after a complete item, close every container that has no more items
    def completed(): Unit =
      var done = false
      while !done && depth > 0 do
        openLeft(depth - 1) match
          case l if l > 0 =>
            openLeft(depth - 1) = l - 1
            if l - 1 == 0 then close() else done = true
          case _ => done = true
    while pos < data.length || depth > 0 do
      if depth > 0 && openLeft(depth - 1) < 0 && (data(pos) & 0xff) == 0xff then
        pos += 1
        close()
        completed()
      else
        grow()
        val h = data(pos) & 0xff
        starts(n) = pos
        pos += 1
        val major = h >> 5
        val ai = h & 31
        val idx = n
        n += 1
        major match
          case 0 | 1 => { arg(ai); skips(idx) = n; completed() }
          case 2 | 3 =>
            if ai == 31 then
              // chunks: definite strings until the break
              while (data(pos) & 0xff) != 0xff do
                val ch = data(pos) & 0xff
                pos += 1
                val len = arg(ch & 31)
                pos += len.toInt
              pos += 1
            else
              val len = arg(ai)
              pos += len.toInt
            skips(idx) = n; completed()
          case 4 | 5 | 6 =>
            val count: Long =
              if major == 6 then { arg(ai); 1L }
              else if ai == 31 then -1L
              else if major == 5 then 2 * arg(ai)
              else arg(ai)
            if count == 0 then { skips(idx) = n; completed() }
            else
              if depth == openIdx.length then
                openIdx = java.util.Arrays.copyOf(openIdx, depth * 2)
                openLeft = java.util.Arrays.copyOf(openLeft, depth * 2)
              openIdx(depth) = idx
              openLeft(depth) = count
              depth += 1
          case _ =>
            // simple values and floats
            ai match
              case 25 => pos += 2
              case 26 => pos += 4
              case 27 => pos += 8
              case _  => ()
            skips(idx) = n; completed()
    new Tape(starts, skips, n)

  /** The largest heap in use right after any collection (the post-GC peak the issue quotes), kept
    * by a listener on the collectors' notifications. A young collection leaves live old objects, so
    * this is an upper bound of the live set at that moment; run with a large `-Xmx` and read the
    * value together with the full-GC `retained`.
    */
  @volatile private var postGcPeakBytes = 0L
  private def trackPostGcPeak(): Unit =
    import java.lang.management.ManagementFactory
    import javax.management.NotificationEmitter
    import com.sun.management.GarbageCollectionNotificationInfo as Info
    import javax.management.openmbean.CompositeData
    ManagementFactory.getGarbageCollectorMXBeans.forEach {
      case e: NotificationEmitter =>
        e.addNotificationListener(
          (n, _) =>
            if n.getType == Info.GARBAGE_COLLECTION_NOTIFICATION then
              val info = Info.from(n.getUserData.asInstanceOf[CompositeData])
              val used = info.getGcInfo.getMemoryUsageAfterGc.values.stream
                .mapToLong(_.getUsed)
                .sum
              if used > postGcPeakBytes then postGcPeakBytes = used,
          null,
          null
        )
      case _ => ()
    }

  /** SAMPLE=1: a daemon thread forces a full collection every 200 ms and keeps the largest heap in
    * use right after it, the live set at its largest (what a heap must hold, without the garbage
    * that G1 leaves in old regions between its collections). Slows the run down a lot.
    */
  @volatile private var liveSampleMb = 0L
  private def sampleLive(): Unit =
    val t = new Thread(() =>
      while true do
        Thread.sleep(200)
        val used = usedMb()
        if used > liveSampleMb then liveSampleMb = used
    )
    t.setDaemon(true)
    t.start()

  /** The CBOR of a committee-votes map of `n` entries, `{[0, hash28] => 0|1|2}` in key order: the
    * shape of the ones in the epoch-188 proposals, 34 bytes an entry.
    */
  def syntheticVotes(n: Int): Array[Byte] =
    val out = new java.io.ByteArrayOutputStream(5 + 34 * n)
    out.write(0xba)
    out.write(ByteBuffer.allocate(4).putInt(n).array())
    val hash = new Array[Byte](28)
    var i = 0
    while i < n do
      ByteBuffer.wrap(hash).putInt(20, i)
      out.write(0x82); out.write(0x00); out.write(0x58); out.write(0x1c)
      out.write(hash)
      out.write(i % 3)
      i += 1
    out.toByteArray

  private def votesMain(): Unit =
    import xyz.kd5ujc.scarab.ledger.state.{ProtVer, Word64}
    import xyz.kd5ujc.scarab.storage.haskell.state.HaskellStateCodecs
    import xyz.kd5ujc.scarab.storage.haskell.state.NodeOrders.given
    given ProtVer = ProtVer(Word64.fromUnsigned(10), Word64.zero)
    val n = sys.env.get("VOTES").fold(1000000)(_.toInt)
    val bytes = Bytes.fromArray(syntheticVotes(n))
    val base = usedMb()
    println(
      s"mode=votes maxHeap=${Runtime.getRuntime.maxMemory / (1024 * 1024)}MB entries=$n cbor=${bytes.size / (1024 * 1024)}MB afterBuild=${base}MB"
    )
    val codec = xyz.kd5ujc.scarab.storage.haskell.state.Wire
      .mapOf(HaskellStateCodecs.nodeCredential, HaskellStateCodecs.vote)
    try
      val began = System.nanoTime()
      val decoded =
        codec.decodeBytes(bytes, HaskellState.limits).fold(e => sys.error(e.render), identity)
      val ms = (System.nanoTime() - began) / 1000000
      println(s"OK retained=${usedMb() - base}MB size=${decoded.size} time=${ms}ms")
    catch
      case _: OutOfMemoryError =>
        println("OOM")
        sys.exit(3)

  /** The vote spam of preprod epochs 188 to 193 put into a snapshot of today: `proposals` proposals
    * (the Info action) each carrying the same `voters` committee votes, `[0, hash28] => 0..2`, and
    * the DRep pulser's snapshot holding them again, which is the shape that ran the node out of
    * memory (5 x 648,752 votes, the copy making ten). The original file of the issue is a
    * `CSLSNAP1` snapshot of a schema the migration no longer reads, so the Haskell section is had
    * from a current one and the spam added to its Conway gov state. `spam <snapshot> <voters>
    * <out>` writes a V5-shaped profiling file (the sidecar of the input, the new Haskell section, a
    * zero checksum).
    */
  private def spamMain(content: Array[Byte], voters: Int, out: Path): Unit =
    import xyz.kd5ujc.scarab.consensus.state.EraLedgerState
    import xyz.kd5ujc.scarab.ledger.{
      GovAction,
      GovActionId,
      GovActionState,
      Proposal,
      RewardAccount,
      Vote
    }
    import xyz.kd5ujc.scarab.ledger.{Credential => NodeCredential}
    import xyz.kd5ujc.scarab.ledger.state.{ConwayNewEpochState, ShelleyBasedEra}
    import xyz.kd5ujc.scarab.consensus.state.ShelleyLedgerState
    import xyz.kd5ujc.scarab.storage.haskell.state.LedgerFileEra
    val sidecarLength = ByteBuffer.wrap(content, 8, 4).getInt
    val haskellFrom = 12 + sidecarLength
    val file = HaskellLedgerFile
      .decode(Bytes.wrap(content).drop(haskellFrom))
      .fold(e => sys.error(e.render), identity)
    val votes: Map[NodeCredential, Vote] = Iterator
      .range(0, voters)
      .map { i =>
        val h = new Array[Byte](28)
        // a voter is a hash, so as random as the real ones (a structured one skews a hash table)
        new java.util.SplittableRandom(i.toLong).nextBytes(h)
        (NodeCredential.Key(Bytes.wrap(h)): NodeCredential) -> Vote.fromOrdinal(i % 3)
      }
      .toMap
    val pageProposals = 5
    def action(i: Int, drops: GovActionState => GovActionState = identity): GovActionState =
      val id = GovActionId(Bytes.wrap(Array.fill[Byte](32)(i.toByte)), BigInt(0))
      val account = RewardAccount(0, NodeCredential.Key(Bytes.wrap(Array.fill[Byte](28)(i.toByte))))
      drops(
        GovActionState(
          id,
          votes,
          Map.empty,
          Map.empty,
          Proposal(BigInt(100000000000L), account, GovAction.Info),
          BigInt(188),
          BigInt(194)
        )
      )
    val actions = Vector.tabulate(pageProposals)(i => action(i))
    val state = file.ledger.state match
      case LedgerFileEra.Mirrored(i: EraLedgerState.InShelleyEra[e]) =>
        i.era match
          case ShelleyBasedEra.SConway =>
            val gov = ShelleyLedgerState.nes[e].andThen(ConwayNewEpochState.gov)
            val old = gov.get(i.state)
            val spammed = old.copy(
              proposals = old.proposals.copy(actions = actions),
              drepPulsing =
                old.drepPulsing.copy(snapshot = old.drepPulsing.snapshot.copy(proposals = actions))
            )
            LedgerFileEra.Mirrored(
              EraLedgerState.InShelleyEra(i.era, gov.replace(spammed)(i.state))
            )
          case _ => sys.error("not a Conway state")
      case _ => sys.error("not a Shelley-based state")
    val telescope = xyz.kd5ujc.scarab.consensus.state.Telescope
      .make(file.ledger.past, file.ledger.current.copy(state = state))
      .fold(e => sys.error(e.toString), identity)
    val spammedFile = file.copy(ledger = telescope)
    val haskell = HaskellLedgerFile.encode(spammedFile).fold(e => sys.error(e.toString), identity)
    val head = java.util.Arrays.copyOf(content, haskellFrom)
    Files.write(out, head ++ haskell.toArray ++ new Array[Byte](32))
    println(
      s"wrote ${(haskellFrom + haskell.size) / (1024 * 1024)}MB, haskell ${haskell.size / (1024 * 1024)}MB"
    )

  def main(args: Array[String]): Unit =
    if args(0) == "votes" then
      votesMain()
      return
    trackPostGcPeak()
    if sys.env.contains("SAMPLE") then sampleLive()
    val mode = args(0)
    // the store's checksum (SHA-256) closes the file; the content is what a decoder is given
    val content = java.util.Arrays
      .copyOf(Files.readAllBytes(Path.of(args(1))), (Files.size(Path.of(args(1))) - 32).toInt)
    if mode == "spam" then
      spamMain(content, args(2).toInt, Path.of(args(3)))
      return
    val sidecarLength = ByteBuffer.wrap(content, 8, 4).getInt
    val haskellFrom = 12 + sidecarLength
    val haskell = Bytes.wrap(content).drop(haskellFrom)
    val base = usedMb()
    println(
      s"mode=$mode maxHeap=${Runtime.getRuntime.maxMemory / (1024 * 1024)}MB file=${content.length / (1024 * 1024)}MB " +
        s"haskell=${haskell.size / (1024 * 1024)}MB afterRead=${base}MB"
    )
    val began = System.nanoTime()
    try
      val retained: AnyRef = mode match
        case "cbor" =>
          Cbor.decode(haskell, HaskellState.limits).fold(m => sys.error(m.message), identity)
        case "ledger" =>
          HaskellLedgerFile.decode(haskell).fold(e => sys.error(e.render), identity)
        case "tape" =>
          tape(content, haskellFrom)
        case "v5" =>
          val setup = NodeSetup
            .fromDirectory(Path.of(args.lift(2).getOrElse("fixtures/preprod/genesis")))
            .fold(e => sys.error(e.message), identity)
          given Ask[IO, ConsensusMirrorEnv] = Ask.const(setup.mirrorEnv())
          SnapshotV7
            .format[IO](SnapshotCheck.Full)
            .decode(content)
            .unsafeRunSync()
            .fold(e => sys.error(e.toString), identity)
        case "written" =>
          // the node's write: encode a state, read the file back and compare (the issue's path)
          val setup = NodeSetup
            .fromDirectory(Path.of(args.lift(2).getOrElse("fixtures/preprod/genesis")))
            .fold(e => sys.error(e.message), identity)
          given Ask[IO, ConsensusMirrorEnv] = Ask.const(setup.mirrorEnv())
          val format = SnapshotV7.format[IO](SnapshotCheck.Full)
          val snapshot =
            format.decode(content).unsafeRunSync().fold(e => sys.error(e.toString), identity)
          System.gc()
          postGcPeakBytes = 0L
          val before = usedMb()
          println(s"state decoded; live=${before}MB; writing")
          val written =
            format.encode(snapshot).unsafeRunSync().fold(e => sys.error(e.toString), identity)
          println(s"written ${written.length / (1024 * 1024)}MB")
          snapshot
        case other => sys.error(s"unknown mode $other")
      val ms = (System.nanoTime() - began) / 1000000
      val after = usedMb()
      val extra = retained match
        case t: Tape =>
          s" items=${t.size} bytesPerItem=${(t.starts.length + t.skips.length) * 4.0 / t.size}"
        case _ => ""
      println(s"sampled live peak=${liveSampleMb}MB")
      println(s"post-GC peak=${postGcPeakBytes / (1024 * 1024)}MB")
      println(s"OK retained=${after - base}MB (heapAfterGc=${after}MB) time=${ms}ms$extra")
      // HOLD=1: stay alive so that `jcmd <pid> GC.class_histogram` can be taken of the result
      if sys.env.contains("HOLD") then Thread.sleep(600000)
      if retained eq null then println("unreachable")
    catch
      case _: OutOfMemoryError =>
        println("OOM")
        sys.exit(3)
