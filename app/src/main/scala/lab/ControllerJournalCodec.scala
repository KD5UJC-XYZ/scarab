// SPDX-License-Identifier: Apache-2.0
package lab

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream}
import java.nio.file.Path
import java.nio.charset.StandardCharsets.UTF_8
import scala.util.control.NonFatal
import lab.cbor.Bytes
import ControllerReducer.*
import LocalDerivedCheckpoint.{Claim as FullClaim, Token}

/** Strict bounded wire/storage values. Decoding is not authentication or runtime authority. */
object ControllerJournalCodec:
  val MaxBytes = 32768
  val MaxClaims = 6
  val LaunchPolicy = "in-process-resource-v1"
  final case class Binding(
      root: String,
      checkpoint: String,
      store: Store,
      profile: String = CoherentSequence.ProfileId,
      format: String = LocalDerivedCheckpoint.Format,
      authority: String = LocalDerivedCheckpoint.Authority,
      launchPolicy: String = LaunchPolicy
  )
  final case class Image(binding: Binding, journal: Journal, claims: Vector[FullClaim])
  final case class Message(input: Input, claims: Vector[FullClaim] = Vector.empty)

  private def checked[A](body: => A): Either[String, A] =
    try Right(body)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def get[A](a: Either[String, A]): A =
    a.fold(s => throw new IllegalArgumentException(s), identity)
  private def id(i: Id): Unit = require(i != null && i.valid, "identity")
  private def hash(b: Bytes): Id =
    require(b != null && b.size == 32, "hash width"); Id(b.hex)
  private def raw(i: Id): Bytes =
    id(i); Bytes.fromArray(i.value.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray)
  def project(c: FullClaim): Claim =
    Claim(
      Store(hash(c.token.storeId), hash(c.token.contextId)),
      hash(c.token.sessionId),
      c.token.generation,
      hash(c.token.digest)
    )
  private def binding(b: Binding): Unit =
    require(b != null && b.store != null, "binding")
    id(b.store.id); id(b.store.context)
    def path(s: String): Path =
      require(s != null && s.getBytes(UTF_8).length <= 4096, "path size")
      val p = Path.of(s)
      require(
        p.isAbsolute && p.normalize.toString == s && !s.contains('\u0000'),
        "canonical absolute path"
      )
      p
    val r = path(b.root); val c = path(b.checkpoint)
    require(!r.startsWith(c) && !c.startsWith(r), "overlapping journal/store paths")
    require(
      b.profile == CoherentSequence.ProfileId && b.format == LocalDerivedCheckpoint.Format &&
        b.authority == LocalDerivedCheckpoint.Authority && b.launchPolicy == LaunchPolicy,
      "profile/format/authority"
    )
  private def full(c: FullClaim): Unit =
    require(c != null && c.token != null, "claim")
    project(c)
    require(
      c.token.generation >= 0 && c.format == LocalDerivedCheckpoint.Format &&
        c.profile == CoherentSequence.ProfileId && c.authority == LocalDerivedCheckpoint.Authority,
      "claim policy"
    )
    hash(c.anchorId); hash(c.finalId)
    require(
      c.compactedBlocks != null && c.revision != null && c.compactedBlocks > 0 &&
        c.compactedBlocks <= c.revision && c.revision <= lab.ledger.ClusterTransition.MaxRevision,
      "claim numbers"
    )
  def references(j: Journal): Vector[Claim] =
    val selected = j.selection match
      case Selection.Active(c)  => Vector(c)
      case Selection.Dormant(_) => Vector.empty
      case _                    => throw new IllegalArgumentException("migration unsupported")
    (selected ++ j.lease.toVector.flatMap(_.verified) ++ j.pending.toVector.flatMap(o =>
      o.before.toVector :+ o.after
    ) ++
      j.last.toVector.flatMap(c => c.operation.before.toVector :+ c.operation.after)).distinct
  def validate(i: Image): Either[String, Unit] = checked {
    binding(i.binding)
    get(ControllerReducer.open(i.journal, Id("0" * 64)))
    val selected = i.journal.selection match
      case Selection.Active(c)  => c.store
      case Selection.Dormant(s) => s
      case _                    => throw new IllegalArgumentException("migration unsupported")
    require(
      selected == i.binding.store && i.journal.lease.forall(_.store == selected),
      "store mapping"
    )
    require(i.claims != null && i.claims.size <= MaxClaims, "claim count")
    i.claims.foreach { c =>
      full(c); require(project(c).store == selected, "claim store mapping")
    }
    val keys = i.claims.map(project)
    require(
      keys.distinct.size == keys.size && keys.toSet == references(i.journal).toSet,
      "exact complete claim set"
    )
  }
  def updated(
      previous: Image,
      journal: Journal,
      supplied: Vector[FullClaim]
  ): Either[String, Image] = checked {
    require(supplied.size <= 1, "one new publication claim")
    supplied.foreach { c =>
      full(c); require(project(c).store == previous.binding.store, "supplied store mapping")
    }
    val all = previous.claims ++ supplied
    all
      .groupBy(project)
      .values
      .foreach(cs => require(cs.distinct.size == 1, "conflicting full claim"))
    val needed = references(journal)
    val next = Image(
      previous.binding,
      journal,
      needed.map(k =>
        all
          .find(project(_) == k)
          .getOrElse(throw new IllegalArgumentException("missing complete authority claim"))
      )
    )
    get(validate(next)); next
  }

  private final class Writer:
    private val bytes = new ByteArrayOutputStream()
    val out = new DataOutputStream(bytes)
    def text(s: String, max: Int): Unit =
      require(s != null, "null text")
      val b = s.getBytes(UTF_8); require(b.length > 0 && b.length <= max, "text bound")
      out.writeInt(b.length); out.write(b)
    def identity(i: Id): Unit = out.write(raw(i).toArray)
    def number(n: BigInt): Unit = text(n.toString, 78)
    def option[A](v: Option[A])(f: A => Unit): Unit =
      require(v != null, "null option"); out.writeByte(if v.isDefined then 1 else 0); v.foreach(f)
    def result: Bytes =
      out.flush(); require(bytes.size <= MaxBytes - 32, "total size");
      Bytes.fromArray(bytes.toByteArray)
  private final class Reader(b: Bytes):
    val in = new DataInputStream(new ByteArrayInputStream(b.toArray))
    def tag: Int = in.readUnsignedByte()
    def bool: Boolean = tag match
      case 0 => false
      case 1 => true
      case _ => throw new IllegalArgumentException("boolean tag")
    def text(max: Int): String =
      val n = in.readInt(); require(n > 0 && n <= max && n <= in.available(), "text bound")
      val a = in.readNBytes(n); val s = new String(a, UTF_8)
      require(java.util.Arrays.equals(a, s.getBytes(UTF_8)), "invalid UTF-8"); s
    def identity: Id =
      val b = in.readNBytes(32); require(b.length == 32, "truncated hash");
      Id(Bytes.fromArray(b).hex)
    def number: BigInt =
      val s = text(78); require(s.matches("0|[1-9][0-9]*"), "canonical number"); BigInt(s)
    def option[A](f: => A): Option[A] = if bool then Some(f) else None
    def end(): Unit = require(in.available() == 0, "trailing bytes")
  private def putStore(w: Writer, s: Store): Unit = { w.identity(s.id); w.identity(s.context) }
  private def readStore(r: Reader): Store = Store(r.identity, r.identity)
  private def putClaim(w: Writer, c: Claim): Unit =
    putStore(w, c.store); w.identity(c.issuer); w.out.writeLong(c.generation); w.identity(c.digest)
  private def readClaim(r: Reader): Claim =
    val c = Claim(readStore(r), r.identity, r.in.readLong(), r.identity)
    require(c.generation >= 0, "generation"); c
  private def putFull(w: Writer, c: FullClaim): Unit =
    full(c); putClaim(w, project(c)); w.text(c.format, 128); w.text(c.profile, 128);
    w.text(c.authority, 128)
    w.identity(hash(c.anchorId)); w.identity(hash(c.finalId)); w.number(c.compactedBlocks);
    w.number(c.revision)
  private def readFull(r: Reader): FullClaim =
    val c = readClaim(r)
    val f = FullClaim(
      Token(raw(c.store.id), raw(c.store.context), raw(c.issuer), c.generation, raw(c.digest)),
      r.text(128),
      r.text(128),
      r.text(128),
      raw(r.identity),
      raw(r.identity),
      r.number,
      r.number
    )
    full(f); f
  private def putOperation(w: Writer, o: Operation): Unit =
    w.identity(o.id); w.out.writeLong(o.epoch); w.identity(o.session);
    w.option(o.before)(putClaim(w, _)); putClaim(w, o.after)
  private def readOperation(r: Reader): Operation =
    val o = Operation(r.identity, r.in.readLong(), r.identity, r.option(readClaim(r)), readClaim(r))
    require(
      o.epoch > 0 && o.session == o.after.issuer && o.before.fold(o.after.generation == 0)(c =>
        c.store == o.after.store && c.generation < Long.MaxValue && o.after.generation == c.generation + 1
      ),
      "operation successor"
    )
    o
  private def putLease(w: Writer, l: Lease): Unit =
    w.out.writeLong(l.epoch); w.identity(l.session); w.identity(l.launch); putStore(w, l.store)
    w.out.writeByte(l.phase.ordinal); w.option(l.child)(w.identity); w.out.writeBoolean(l.locked);
    w.out.writeBoolean(l.ended)
    w.option(l.verified)(putClaim(w, _))
  private def readLease(r: Reader): Lease = Lease(
    r.in.readLong(),
    r.identity,
    r.identity,
    readStore(r),
    Phase.fromOrdinal(r.tag),
    r.option(r.identity),
    r.bool,
    r.bool,
    r.option(readClaim(r))
  )
  private def putJournal(w: Writer, j: Journal): Unit =
    w.out.writeLong(j.revision); w.out.writeLong(j.epoch)
    j.selection match
      case Selection.Dormant(s) => w.out.writeByte(0); putStore(w, s)
      case Selection.Active(c)  => w.out.writeByte(1); putClaim(w, c)
      case _                    => throw new IllegalArgumentException("migration unsupported")
    w.option(j.lease)(putLease(w, _)); w.option(j.pending)(putOperation(w, _))
    w.option(j.last) { c =>
      putOperation(w, c.operation); w.out.writeByte(c.outcome.ordinal)
    }
    w.out.writeBoolean(j.recovering)
  private def readJournal(r: Reader): Journal =
    val rev = r.in.readLong(); val epoch = r.in.readLong()
    val selection = r.tag match
      case 0 => Selection.Dormant(readStore(r))
      case 1 => Selection.Active(readClaim(r))
      case _ => throw new IllegalArgumentException("selection tag")
    Journal(
      rev,
      epoch,
      selection,
      r.option(readLease(r)),
      r.option(readOperation(r)),
      r.option(Completion(readOperation(r), Outcome.fromOrdinal(r.tag))),
      r.bool
    )
  private def claims(w: Writer, cs: Vector[FullClaim], max: Int): Unit =
    require(cs != null && cs.size <= max, "claim count"); w.out.writeByte(cs.size);
    cs.foreach(putFull(w, _))
  private def claims(r: Reader, max: Int): Vector[FullClaim] =
    val n = r.tag; require(n <= max, "claim count"); Vector.fill(n)(readFull(r))
  private def seal(w: Writer): Bytes =
    val p = w.result; Bytes(p.value ++ ClusterHeaderObservation.sha256(p).value)
  private def reader(b: Bytes, magic: String): Reader =
    require(b != null && b.size >= 32 && b.size <= MaxBytes, "total bound")
    val p = Bytes(b.value.dropRight(32))
    require(ClusterHeaderObservation.sha256(p) == Bytes(b.value.takeRight(32)), "checksum")
    val r = new Reader(p); require(r.text(64) == magic, "format tag"); r
  def encode(i: Image): Either[String, Bytes] = checked {
    get(validate(i)); val w = new Writer; w.text("controller-journal-v2", 64)
    val b = i.binding
    w.text(b.root, 4096); w.text(b.checkpoint, 4096); putStore(w, b.store)
    w.text(b.profile, 128); w.text(b.format, 128); w.text(b.authority, 128);
    w.text(b.launchPolicy, 128)
    putJournal(w, i.journal); claims(w, i.claims.sortBy(c => project(c).toString), MaxClaims);
    seal(w)
  }
  def decode(b: Bytes, expected: Binding): Either[String, Image] = checked {
    binding(expected); val r = reader(b, "controller-journal-v2")
    val actual =
      Binding(
        r.text(4096),
        r.text(4096),
        readStore(r),
        r.text(128),
        r.text(128),
        r.text(128),
        r.text(128)
      )
    require(actual == expected, "immutable binding mismatch")
    val i = Image(actual, readJournal(r), claims(r, MaxClaims)); r.end(); get(validate(i))
    require(get(encode(i)) == b, "noncanonical journal"); i
  }

  private def putInput(w: Writer, input: Input): Unit = input match
    case Input.Request(rev, c) =>
      require(rev >= 0, "revision"); w.out.writeByte(0); w.out.writeLong(rev)
      c match
        case Command.ReserveLaunch(s, l) => w.out.writeByte(0); w.identity(s); w.identity(l)
        case Command.Begin(o)            => w.out.writeByte(1); putOperation(w, o)
        case Command.Installed(o)        => w.out.writeByte(2); putOperation(w, o)
        case Command.Activate            => w.out.writeByte(3)
        case Command.Retire(i)           => w.out.writeByte(4); w.identity(i)
        case _ => throw new IllegalArgumentException("unsupported command")
    case Input.Completed(e) =>
      w.out.writeByte(1)
      e match
        case Evidence.ChildBound(ep, s, l, c) =>
          w.out.writeByte(0); w.out.writeLong(ep); w.identity(s); w.identity(l); w.identity(c)
        case Evidence.NoMatchingChild(ep, s, l) =>
          w.out.writeByte(1); w.out.writeLong(ep); w.identity(s); w.identity(l)
        case Evidence.OwnershipEnded(ep, s, l, p) =>
          w.out.writeByte(2); w.out.writeLong(ep); w.identity(s); w.identity(l)
          p match
            case OwnershipEnd.Absent(i) => w.out.writeByte(0); w.identity(i)
            case OwnershipEnd.Exited(c, i, k) =>
              w.out.writeByte(1); w.identity(c); w.identity(i); w.identity(k)
        case Evidence.LockHeld(ep, s, c) =>
          w.out.writeByte(3); w.out.writeLong(ep); w.identity(s); w.identity(c)
        case Evidence.Inspected(ep, s, d) =>
          w.out.writeByte(4); w.out.writeLong(ep); w.identity(s)
          d match
            case Disk.Missing    => w.out.writeByte(0)
            case Disk.Present(c) => w.out.writeByte(1); putClaim(w, c)
        case Evidence.Verified(ep, s, c) =>
          w.out.writeByte(5); w.out.writeLong(ep); w.identity(s); putClaim(w, c)
        case Evidence.Failed(ep, s, stage, c) =>
          w.out.writeByte(6); w.out.writeLong(ep); w.identity(s); w.out.writeByte(stage.ordinal);
          w.option(c)(putClaim(w, _))
        case _ => throw new IllegalArgumentException("force completions are internal")
  private def readInput(r: Reader): Input = r.tag match
    case 0 =>
      val rev = r.in.readLong()
      val c = r.tag match
        case 0 => Command.ReserveLaunch(r.identity, r.identity)
        case 1 => Command.Begin(readOperation(r))
        case 2 => Command.Installed(readOperation(r))
        case 3 => Command.Activate
        case 4 => Command.Retire(r.identity)
        case _ => throw new IllegalArgumentException("command tag")
      Input.Request(rev, c)
    case 1 =>
      val tag = r.tag; val ep = r.in.readLong(); val s = r.identity
      require(ep > 0, "epoch")
      val e = tag match
        case 0 => Evidence.ChildBound(ep, s, r.identity, r.identity)
        case 1 => Evidence.NoMatchingChild(ep, s, r.identity)
        case 2 =>
          val l = r.identity
          val p = r.tag match
            case 0 => OwnershipEnd.Absent(r.identity)
            case 1 => OwnershipEnd.Exited(r.identity, r.identity, r.identity)
            case _ => throw new IllegalArgumentException("settlement tag")
          Evidence.OwnershipEnded(ep, s, l, p)
        case 3 => Evidence.LockHeld(ep, s, r.identity)
        case 4 =>
          val d = r.tag match
            case 0 => Disk.Missing
            case 1 => Disk.Present(readClaim(r))
            case _ => throw new IllegalArgumentException("disk tag")
          Evidence.Inspected(ep, s, d)
        case 5 => Evidence.Verified(ep, s, readClaim(r))
        case 6 => Evidence.Failed(ep, s, Stage.fromOrdinal(r.tag), r.option(readClaim(r)))
        case _ => throw new IllegalArgumentException("evidence tag")
      Input.Completed(e)
    case _ => throw new IllegalArgumentException("input tag")
  def encodeMessage(m: Message): Either[String, Bytes] = checked {
    val w = new Writer; w.text("controller-message-v1", 64); putInput(w, m.input)
    m.input match
      case Input.Request(_, Command.Begin(o)) =>
        require(
          m.claims.size == 1 && project(m.claims.head) == o.after,
          "complete successor claim required"
        )
      case _ => require(m.claims.isEmpty, "unexpected claims")
    claims(w, m.claims, 1); seal(w)
  }
  def decodeMessage(b: Bytes): Either[String, Message] = checked {
    val r = reader(b, "controller-message-v1"); val m = Message(readInput(r), claims(r, 1)); r.end()
    require(get(encodeMessage(m)) == b, "noncanonical message"); m
  }
