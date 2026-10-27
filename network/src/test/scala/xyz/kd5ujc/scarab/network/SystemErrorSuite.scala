// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import java.io.IOException
import java.nio.file.{AccessDeniedException, FileSystemException, NoSuchFileException}
import munit.FunSuite

/** The texts the reference shows for the failures of its local socket, character for character. The
  * expected strings were captured from `cardano-reference-11.1.3:local` (cardano-node 11.1.3, GHC
  * 9.6.7, a static musl build), run as `cardano-node run --config <preview config> --topology
  * <empty topology> --database-path <dir> --socket-path <path> --port 3001` with `<path>` set up to
  * fail: the `Startup.SocketConfigError` trace's `error` field, its message on standard error after
  * `cardano-node: `, and the `error` field of `Startup.DiffusionInit.DiffusionErrored`.
  */
class SystemErrorSuite extends FunSuite:
  private def unlinkFailure(path: String, errno: Errno) =
    new SocketConfigError.LocalSocketError(
      path,
      SystemCallError(SystemCall.RemoveLink, ErrnoCause.Known(errno), Some(path))
    )

  test("a stale socket that cannot be removed: the trace's error and the message, as captured") {
    val captured = List(
      // a directory at the path
      ("/w/isdir", Errno.EISDIR, "/w/isdir: removeLink: inappropriate type (Is a directory)"),
      // a file in a directory the node may not write
      ("/w/ro/sock", Errno.EACCES, "/w/ro/sock: removeLink: permission denied (Permission denied)"),
      // another user's file in a sticky directory
      (
        "/w/sticky/sock",
        Errno.EPERM,
        "/w/sticky/sock: removeLink: permission denied (Operation not permitted)"
      ),
      ("/ro/sock", Errno.EROFS, "/ro/sock: removeLink: permission denied (Read-only file system)"),
      (
        "/w/afile/sock",
        Errno.ENOTDIR,
        "/w/afile/sock: removeLink: inappropriate type (Not a directory)"
      ),
      (
        "/w/loop1/sock",
        Errno.ELOOP,
        "/w/loop1/sock: removeLink: invalid argument (Symbolic link loop)"
      ),
      ("/w/dir é", Errno.EISDIR, "/w/dir é: removeLink: inappropriate type (Is a directory)")
    )
    captured.foreach { (path, errno, ioError) =>
      val error = unlinkFailure(path, errno)
      assertEquals(error.cause.shown, ioError)
      assertEquals(
        error.message,
        s"Failure while attempting to remove the stale local socket: $path : $ioError"
      )
    }
    assertEquals(
      unlinkFailure("/w/isdir", Errno.EISDIR).shown,
      "LocalSocketError \"/w/isdir\" /w/isdir: removeLink: inappropriate type (Is a directory)"
    )
    assertEquals(
      unlinkFailure("/w/dir é", Errno.EISDIR).shown,
      "LocalSocketError \"/w/dir \\233\" /w/dir é: removeLink: inappropriate type (Is a directory)"
    )
    val long = "/w/" + "n" * 300
    assertEquals(
      unlinkFailure(long, Errno.ENAMETOOLONG).cause.shown,
      s"$long: removeLink: invalid argument (Filename too long)"
    )
  }

  test("a bind that fails, as the reference's DiffusionErrored names it") {
    def bind(errno: Errno) =
      DiffusionFailure.System(SystemCallError(SystemCall.Bind, ErrnoCause.Known(errno), None)).shown
    assertEquals(
      bind(Errno.ENOENT),
      "Network.Socket.bind: does not exist (No such file or directory)"
    )
    assertEquals(bind(Errno.EACCES), "Network.Socket.bind: permission denied (Permission denied)")
    val long = "/w/" + "m" * 110
    assertEquals(
      DiffusionFailure.PathTooLong(long).shown,
      s"pokeSockAddr: path is too long in SockAddrUnix \"$long\", length 113, unixPathMax 108\n" +
        "CallStack (from HasCallStack):\n" +
        "  error, called at Network/Socket/Types.hsc:1162:33 in " +
        "network-3.2.8.0-DfzdidsgO8q74pRUILGgOE:Network.Socket.Types"
    )
  }

  test("the other calls: their locations, and the musl text where glibc's differs") {
    def shown(call: SystemCall, errno: Errno, file: Option[String] = None) =
      SystemCallError(call, ErrnoCause.Known(errno), file).shown
    assertEquals(
      shown(SystemCall.Accept, Errno.EMFILE),
      "Network.Socket.accept: resource exhausted (No file descriptors available)"
    )
    assertEquals(
      shown(SystemCall.GetFileStatus, Errno.ENOENT, Some("/run/cardano")),
      "/run/cardano: getFileStatus: does not exist (No such file or directory)"
    )
    assertEquals(
      shown(SystemCall.Bind, Errno.EADDRINUSE),
      "Network.Socket.bind: resource busy (Address in use)"
    )
    assertEquals(
      shown(SystemCall.Accept, Errno.ECONNABORTED),
      "Network.Socket.accept: failed (Connection aborted)"
    )
  }

  test("the errno is read back from the JDK's exception: its class, or glibc's text") {
    def of(e: IOException) = Errno.of(e)
    assertEquals(of(new NoSuchFileException("p")), ErrnoCause.Known(Errno.ENOENT))
    assertEquals(of(new AccessDeniedException("p")), ErrnoCause.Known(Errno.EACCES))
    assertEquals(
      of(new FileSystemException("p", null, "Is a directory")),
      ErrnoCause.Known(Errno.EISDIR)
    )
    assertEquals(
      of(
        new FileSystemException(
          "p",
          null,
          "Too many levels of symbolic links or unable to access attributes of symbolic link"
        )
      ),
      ErrnoCause.Known(Errno.ELOOP)
    )
    assertEquals(
      of(new java.net.SocketException("Too many open files")),
      ErrnoCause.Known(Errno.EMFILE)
    )
    assertEquals(
      of(new java.nio.channels.AsynchronousCloseException),
      ErrnoCause.Known(Errno.EBADF)
    )
    // every errno has a glibc text of its own, so the text names one errno
    assertEquals(Errno.values.map(_.glibc).distinct.length, Errno.values.length)
    Errno.values.foreach(e =>
      assertEquals(of(new java.net.SocketException(e.glibc)), ErrnoCause.Known(e))
    )
  }

  test("a text that names no errno stays unrecognised and is shown as GHC's OtherError") {
    val cause = Errno.of(new java.net.SocketException("Unix domain path too long"))
    assertEquals(cause, ErrnoCause.Unrecognised("Unix domain path too long"))
    assertEquals(
      SystemCallError(SystemCall.Bind, cause, None).shown,
      "Network.Socket.bind: failed (Unix domain path too long)"
    )
  }

  test("Haskell's show of a string") {
    val cases = List(
      "/w/s/node.socket" -> "\"/w/s/node.socket\"",
      "/w/dir é" -> "\"/w/dir \\233\"",
      "é1" -> "\"\\233\\&1\"",
      "éa" -> "\"\\233a\"",
      "a\"b\\c" -> "\"a\\\"b\\\\c\"",
      "\u007f" -> "\"\\DEL\"",
      "\u0001\u001f" -> "\"\\SOH\\US\"",
      "\u000eH" -> "\"\\SO\\&H\"",
      "\u000ei" -> "\"\\SOi\"",
      "\t\n\r\u0007\b\f\u000b" -> "\"\\t\\n\\r\\a\\b\\f\\v\"",
      "\u0000" -> "\"\\NUL\"",
      "\ud83d\ude00" -> "\"\\128512\""
    )
    cases.foreach((s, shown) => assertEquals(HaskellShow.string(s), shown, s))
  }

  test("an address and a descriptor are shown as the reference shows them") {
    assertEquals(LocalAddress("/w/s/node.socket").shown, "LocalAddress \"/w/s/node.socket\"")
    assertEquals(FileDescriptor(20).shown, "FileDescriptor 20")
    assertEquals(FileDescriptor.Invalid.shown, "FileDescriptor (-1)")
  }
