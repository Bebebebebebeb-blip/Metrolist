package com.metrolist.music.audio

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Passthrough AudioProcessor: does NOT change the audio at all.
 * It just duplicates every PCM chunk that flows through the player
 * and writes it to a local TCP socket, so an external tool (cava,
 * running in Termux) can read it as if it were a `fifo` source.
 *
 * Connect from Termux with something like:
 *   nc 127.0.0.1 9877 > ~/mpd.fifo &
 *   cava
 *
 * The socket server lives in the companion object as a process-wide
 * singleton, NOT tied to this processor instance. The player (and
 * therefore this AudioProcessor) can be rebuilt many times over the
 * app's lifetime - login, crossfade's secondary/fading players, error
 * recovery, etc. each create a fresh instance via buildAudioSink().
 * If the server lived on the instance, every rebuild would try to
 * bind the same port again while the old instance's socket might not
 * be closed yet, and fail silently - which is exactly the "works,
 * then randomly stops" behavior this fixes. Binding once per process
 * and just funneling every instance's audio into the same queue means
 * only one bind ever happens, no matter how many times the player and
 * this processor get recreated.
 *
 * NOTE: exact AudioProcessor method signatures can shift a little
 * between Media3 versions - check this against whatever version
 * Metrolist pins in its build.gradle if it doesn't compile as-is.
 */
class PcmTapAudioProcessor(private val context: Context, private val port: Int = 9877) : AudioProcessor {

    private object Server {
        // Bounded queue: if nothing is connected on the socket, or the
        // reader is slow, we drop the oldest buffered chunk instead of
        // blocking audio or letting a lag accumulate (see queueInput).
        // Kept small on purpose - each chunk is only ~10-15ms of audio,
        // so even a full queue caps the max possible lag at a couple
        // hundred milliseconds instead of the ~1s a bigger queue allowed.
        val queue = LinkedBlockingQueue<ByteArray>(12)
        val started = AtomicBoolean(false)

        fun ensureStarted(port: Int, debugLog: (String) -> Unit) {
            if (started.getAndSet(true)) return
            debugLog("Server.ensureStarted: starting shared thread, will bind port $port")
            Thread {
                try {
                    val serverSocket = ServerSocket()
                    serverSocket.reuseAddress = true
                    serverSocket.bind(InetSocketAddress(port))
                    debugLog("bound to port $port, waiting for connections")
                    while (true) {
                        // Blocks here until `nc`/socat connects from Termux.
                        val socket: Socket = serverSocket.accept()
                        debugLog("client connected from ${socket.inetAddress}")
                        val out: OutputStream = socket.getOutputStream()
                        var bytesSent = 0L
                        try {
                            while (true) {
                                val chunk = queue.take()
                                out.write(chunk)
                                bytesSent += chunk.size
                            }
                        } catch (e: Exception) {
                            debugLog("client loop ended after $bytesSent bytes: ${e::class.simpleName}: ${e.message}")
                            // Client on the Termux side disconnected -
                            // loop back and wait for a new connection.
                        } finally {
                            runCatching { socket.close() }
                        }
                    }
                } catch (e: Exception) {
                    debugLog("server thread crashed: ${e::class.java.name}: ${e.message}")
                    started.set(false) // allow a future instance to retry binding
                }
            }.apply {
                isDaemon = true
                name = "PcmTapAudioProcessor-Server"
                start()
            }
        }
    }

    private var inputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false
    private var loggedFirstBuffer = false

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        debugLog("configure() called: encoding=${inputAudioFormat.encoding}, sampleRate=${inputAudioFormat.sampleRate}, channels=${inputAudioFormat.channelCount}")
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            debugLog("REJECTED: not 16-bit PCM, throwing UnhandledAudioFormatException")
            // Keep it simple for a first version - 16-bit PCM only.
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        this.inputAudioFormat = inputAudioFormat
        Server.ensureStarted(port, ::debugLog)
        return inputAudioFormat // passthrough: output format == input format
    }

    override fun isActive(): Boolean = inputAudioFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        if (!loggedFirstBuffer) {
            loggedFirstBuffer = true
            debugLog("queueInput() first call, remaining=$remaining bytes")
        }

        // Duplicate the data for tapping without disturbing the
        // buffer we still need to pass downstream untouched.
        val tapCopy = ByteArray(remaining)
        inputBuffer.duplicate().get(tapCopy)
        if (!Server.queue.offer(tapCopy)) {
            // Queue is full - the reader (nc/cava) isn't keeping up in
            // real time. Drop the OLDEST buffered chunk instead of this
            // new one, so the visualizer tracks the current moment
            // instead of accumulating a growing, self-sustaining delay.
            Server.queue.poll()
            Server.queue.offer(tapCopy)
        }

        if (outputBuffer.capacity() < remaining) {
            outputBuffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }
        outputBuffer.put(inputBuffer)
        outputBuffer.flip()
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val buf = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return buf
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer == AudioProcessor.EMPTY_BUFFER

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
    }

    override fun reset() {
        flush()
        inputAudioFormat = AudioFormat.NOT_SET
        // Deliberately NOT stopping Server here - it's shared across every
        // instance of this processor for the whole app lifetime. Some other
        // player (secondary/fading, or a newly rebuilt one) may still need it.
    }

    private fun debugLog(msg: String) {
        try {
            val dir = context.getExternalFilesDir(null)
            java.io.File(dir, "pcmtap_debug.log").appendText("${System.currentTimeMillis()}: $msg\n")
        } catch (e: Exception) {
            // If we can't even write the debug log, there's nothing more we can do here.
        }
    }
}
