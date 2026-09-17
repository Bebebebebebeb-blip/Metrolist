package com.metrolist.music.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import java.io.OutputStream
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
 *   nc 127.0.0.1:9877 > /tmp/mpd.fifo &
 *   cava
 *
 * NOTE: exact AudioProcessor method signatures can shift a little
 * between Media3 versions - check this against whatever version
 * Metrolist pins in its build.gradle if it doesn't compile as-is.
 */
class PcmTapAudioProcessor(private val port: Int = 9877) : AudioProcessor {

    private var inputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    // Bounded queue: if nothing is connected on the socket, or the
    // reader is slow, we drop frames instead of ever blocking audio.
    private val queue = LinkedBlockingQueue<ByteArray>(64)
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var writerThread: Thread? = null

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            // Keep it simple for a first version - 16-bit PCM only.
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        this.inputAudioFormat = inputAudioFormat
        startServer()
        return inputAudioFormat // passthrough: output format == input format
    }

    override fun isActive(): Boolean = inputAudioFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        // Duplicate the data for tapping without disturbing the
        // buffer we still need to pass downstream untouched.
        val tapCopy = ByteArray(remaining)
        inputBuffer.duplicate().get(tapCopy)
        queue.offer(tapCopy) // never blocks; drops if the queue is full

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
        stopServer()
    }

    private fun startServer() {
        if (running.getAndSet(true)) return
        writerThread = Thread {
            try {
                serverSocket = ServerSocket(port)
                while (running.get()) {
                    // Blocks here until `nc`/socat connects from Termux.
                    val socket: Socket = serverSocket!!.accept()
                    val out: OutputStream = socket.getOutputStream()
                    try {
                        while (running.get()) {
                            val chunk = queue.take()
                            out.write(chunk)
                        }
                    } catch (e: Exception) {
                        // Client on the Termux side disconnected -
                        // loop back and wait for a new connection.
                    } finally {
                        runCatching { socket.close() }
                    }
                }
            } catch (e: Exception) {
                // Port busy / socket closed during reset() - fine to ignore,
                // this is a debug/visualizer tap, not critical playback path.
            }
        }.apply {
            isDaemon = true
            name = "PcmTapAudioProcessor"
            start()
        }
    }

    private fun stopServer() {
        running.set(false)
        runCatching { serverSocket?.close() }
        writerThread = null
    }
}
