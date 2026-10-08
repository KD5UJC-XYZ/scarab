// SPDX-License-Identifier: Apache-2.0
package lab.ledger.runtime

import java.io.{ByteArrayOutputStream, IOException, InputStream}
import java.nio.charset.StandardCharsets.UTF_8

/** Drain continuously so a full pipe cannot prevent the owned child from exiting. Only the first 64
  * KiB is retained; excess bytes are counted and discarded rather than blocking or accumulating.
  */
private[runtime] final class ReplayProbeOutput(input: InputStream):
  import ReplayProbeOutput.*
  private val buffer = new ByteArrayOutputStream(MaxRetained)
  private var total = 0L
  private var failure: Option[Throwable] = None
  private val reader = new Thread(
    () =>
      try
        val chunk = new Array[Byte](4096)
        var count = input.read(chunk)
        while count >= 0 do
          if count > 0 then
            synchronized {
              total += count
              val retained = math.min(count, MaxRetained - buffer.size())
              if retained > 0 then buffer.write(chunk, 0, retained)
            }
          count = input.read(chunk)
      catch case error: Throwable => synchronized { failure = Some(error) },
    "restricted-replay-probe-output"
  )
  reader.setDaemon(true)
  reader.start()

  def snapshot: Captured = synchronized {
    Captured(new String(buffer.toByteArray, UTF_8), buffer.size(), total)
  }

  /** First wait for normal EOF. If it is absent, close/interrupt only this reader and give it one
    * further bounded cleanup wait. A forced drain close remains an error, never a successful run.
    */
  def finish(): Captured =
    reader.join(5000)
    if reader.isAlive then
      var closeFailure: Option[Throwable] = None
      try input.close()
      catch case error: Throwable => closeFailure = Some(error)
      reader.interrupt()
      reader.join(5000)
      val error = new IOException(
        s"probe output drain did not finish normally; threadAlive=${reader.isAlive}"
      )
      closeFailure.foreach(error.addSuppressed)
      throw error
    synchronized { failure.foreach(throw _) }
    snapshot

private[runtime] object ReplayProbeOutput:
  val MaxRetained = 65536
  final case class Captured(text: String, retainedBytes: Int, totalBytes: Long):
    def droppedBytes: Long = totalBytes - retainedBytes
    def rendered: String =
      text + (if droppedBytes == 0 then ""
              else
                s"\n[probe-output-truncated retained=$retainedBytes total=$totalBytes dropped=$droppedBytes]\n")
