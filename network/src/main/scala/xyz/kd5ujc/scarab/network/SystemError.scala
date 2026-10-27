// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import java.io.IOException
import java.nio.channels.ClosedChannelException
import java.nio.file.{
  AccessDeniedException,
  FileAlreadyExistsException,
  FileSystemException,
  NoSuchFileException
}
import xyz.kd5ujc.scarab.codec.DomainError

/** The kind of an `IOError` as GHC shows it (`GHC.IO.Exception`, `instance Show IOErrorType`), for
  * the kinds a socket or file system call of the local socket can raise.
  */
enum IoErrorType(val shown: String):
  case AlreadyExists extends IoErrorType("already exists")
  case NoSuchThing extends IoErrorType("does not exist")
  case ResourceBusy extends IoErrorType("resource busy")
  case ResourceExhausted extends IoErrorType("resource exhausted")
  case PermissionDenied extends IoErrorType("permission denied")
  case HardwareFault extends IoErrorType("hardware fault")
  case InappropriateType extends IoErrorType("inappropriate type")
  case Interrupted extends IoErrorType("interrupted")
  case InvalidArgument extends IoErrorType("invalid argument")
  case OtherError extends IoErrorType("failed")
  case ProtocolError extends IoErrorType("protocol error")
  case UnsupportedOperation extends IoErrorType("unsupported operation")

/** The Linux `errno` values a call of the local socket can fail with. Each carries its text in the
  * C library the JVM runs on (glibc, what the JDK puts in its exceptions), its text in musl (the C
  * library of the reference's static release binary, so the text the reference shows), and the kind
  * GHC gives it (`Foreign.C.Error.errnoToIOError`, base 4.18).
  */
enum Errno(val glibc: String, val musl: String, val kind: IoErrorType):
  case EPERM
      extends Errno(
        "Operation not permitted",
        "Operation not permitted",
        IoErrorType.PermissionDenied
      )
  case ENOENT
      extends Errno(
        "No such file or directory",
        "No such file or directory",
        IoErrorType.NoSuchThing
      )
  case EINTR
      extends Errno("Interrupted system call", "Interrupted system call", IoErrorType.Interrupted)
  case EIO extends Errno("Input/output error", "I/O error", IoErrorType.HardwareFault)
  case EBADF
      extends Errno("Bad file descriptor", "Bad file descriptor", IoErrorType.InvalidArgument)
  case EAGAIN
      extends Errno(
        "Resource temporarily unavailable",
        "Resource temporarily unavailable",
        IoErrorType.ResourceExhausted
      )
  case ENOMEM
      extends Errno("Cannot allocate memory", "Out of memory", IoErrorType.ResourceExhausted)
  case EACCES extends Errno("Permission denied", "Permission denied", IoErrorType.PermissionDenied)
  case EFAULT extends Errno("Bad address", "Bad address", IoErrorType.OtherError)
  case EBUSY extends Errno("Device or resource busy", "Resource busy", IoErrorType.ResourceBusy)
  case EEXIST extends Errno("File exists", "File exists", IoErrorType.AlreadyExists)
  case ENOTDIR extends Errno("Not a directory", "Not a directory", IoErrorType.InappropriateType)
  case EISDIR extends Errno("Is a directory", "Is a directory", IoErrorType.InappropriateType)
  case EINVAL extends Errno("Invalid argument", "Invalid argument", IoErrorType.InvalidArgument)
  case ENFILE
      extends Errno(
        "Too many open files in system",
        "Too many open files in system",
        IoErrorType.ResourceExhausted
      )
  case EMFILE
      extends Errno(
        "Too many open files",
        "No file descriptors available",
        IoErrorType.ResourceExhausted
      )
  case ENOSPC
      extends Errno(
        "No space left on device",
        "No space left on device",
        IoErrorType.ResourceExhausted
      )
  case EROFS
      extends Errno("Read-only file system", "Read-only file system", IoErrorType.PermissionDenied)
  case ENAMETOOLONG
      extends Errno("File name too long", "Filename too long", IoErrorType.InvalidArgument)
  case ELOOP
      extends Errno(
        "Too many levels of symbolic links",
        "Symbolic link loop",
        IoErrorType.InvalidArgument
      )
  case EOVERFLOW
      extends Errno(
        "Value too large for defined data type",
        "Value too large for data type",
        IoErrorType.OtherError
      )
  case EPROTO extends Errno("Protocol error", "Protocol error", IoErrorType.ProtocolError)
  case ENOTSOCK
      extends Errno("Socket operation on non-socket", "Not a socket", IoErrorType.InvalidArgument)
  case EPROTONOSUPPORT
      extends Errno("Protocol not supported", "Protocol not supported", IoErrorType.ProtocolError)
  case ESOCKTNOSUPPORT
      extends Errno(
        "Socket type not supported",
        "Socket type not supported",
        IoErrorType.UnsupportedOperation
      )
  case EOPNOTSUPP
      extends Errno("Operation not supported", "Not supported", IoErrorType.UnsupportedOperation)
  case EAFNOSUPPORT
      extends Errno(
        "Address family not supported by protocol",
        "Address family not supported by protocol",
        IoErrorType.UnsupportedOperation
      )
  case EADDRINUSE
      extends Errno("Address already in use", "Address in use", IoErrorType.ResourceBusy)
  case EADDRNOTAVAIL
      extends Errno(
        "Cannot assign requested address",
        "Address not available",
        IoErrorType.UnsupportedOperation
      )
  case ECONNABORTED
      extends Errno(
        "Software caused connection abort",
        "Connection aborted",
        IoErrorType.OtherError
      )
  case ENOBUFS
      extends Errno(
        "No buffer space available",
        "No buffer space available",
        IoErrorType.ResourceExhausted
      )

object Errno:
  private val byGlibc: Map[String, Errno] = values.map(e => e.glibc -> e).toMap

  /** What the JDK appends to the text of `ELOOP` (`sun.nio.fs.UnixException`). */
  private val LoopSuffix = " or unable to access attributes of symbolic link"

  /** The errno of a failed call, read from the exception the JDK raised for it. The JDK keeps the
    * number to itself and gives a class for three of them and glibc's `strerror` text for the rest;
    * a text of no errno here (another locale, a message of the JDK's own) stays unrecognised.
    */
  def of(e: IOException): ErrnoCause = e match
    case _: NoSuchFileException        => ErrnoCause.Known(ENOENT)
    case _: AccessDeniedException      => ErrnoCause.Known(EACCES)
    case _: FileAlreadyExistsException => ErrnoCause.Known(EEXIST)
    case _: ClosedChannelException     => ErrnoCause.Known(EBADF)
    case f: FileSystemException        => text(Option(f.getReason).orElse(Option(f.getMessage)), e)
    case other                         => text(Option(other.getMessage), e)

  private def text(message: Option[String], e: IOException): ErrnoCause =
    message.fold(ErrnoCause.Unrecognised(e.getClass.getName)) { m =>
      byGlibc.get(m.stripSuffix(LoopSuffix)).fold(ErrnoCause.Unrecognised(m))(ErrnoCause.Known(_))
    }

/** Why a call failed: its errno, or the JDK's text when that names none. */
enum ErrnoCause:
  case Known(errno: Errno)
  case Unrecognised(text: String)

  /** The kind and the reason of the `IOError`, as GHC shows them (an unknown errno is
    * `OtherError`).
    */
  def shown: String = this match
    case Known(e)        => s"${e.kind.shown} (${e.musl})"
    case Unrecognised(t) => s"${IoErrorType.OtherError.shown} ($t)"

/** The calls of the local socket, with the location their `IOError` names: `removeLink` (the
  * `unlink` of `removeFile`, as the reference's binary names it), `getFileStatus` (unix) and the
  * calls of network 3.2.8.0 (`throwSocketErrorIfMinus1Retry_ "Network.Socket.bind"` and so on).
  */
enum SystemCall(val location: String):
  case RemoveLink extends SystemCall("removeLink")
  case GetFileStatus extends SystemCall("getFileStatus")
  case Socket extends SystemCall("Network.Socket.socket")
  case Bind extends SystemCall("Network.Socket.bind")
  case Listen extends SystemCall("Network.Socket.listen")
  case Accept extends SystemCall("Network.Socket.accept")

/** A failed call as the reference's `IOError`: the call, why, and the file it names (the socket
  * calls name none).
  */
final case class SystemCallError(call: SystemCall, cause: ErrnoCause, file: Option[String])
    extends DomainError:
  /** GHC's `show` of the `IOError` (`instance Show IOException`): `<file>: <location>: <kind>
    * (<reason>)`, the file as it is, without quotes.
    */
  def shown: String = file.fold("")(f => s"$f: ") + s"${call.location}: ${cause.shown}"

  /** The errno, when it is known. */
  def errno: Option[Errno] = cause match
    case ErrnoCause.Known(e)        => Some(e)
    case ErrnoCause.Unrecognised(_) => None

  /** `isDoesNotExistError`: the kind is `NoSuchThing`. */
  def doesNotExist: Boolean = errno.exists(_.kind == IoErrorType.NoSuchThing)

object SystemCallError:
  /** The failure of `call` on `file` that the JDK reported as `e`. */
  def of(call: SystemCall, file: Option[String])(e: IOException): SystemCallError =
    SystemCallError(call, Errno.of(e), file)
