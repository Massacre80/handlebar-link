package com.abid.handlebarlink.carplay.engine.session

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import org.junit.Test

/** The tunnel's read buffer: whatever the pieces, the bytes come out once each, in the order they went in. */
class ByteQueueTest {
    private fun bodies(vararg sizes: Int): List<ByteArray> {
        var next = 0
        return sizes.map { size -> ByteArray(size) { (next++ % 251).toByte() } }
    }

    /** Appends [appended] interleaved with reads of [readSize]; returns everything read. */
    private fun run(
        queue: ByteQueue,
        appended: List<ByteArray>,
        readSize: Int,
    ): List<Byte> {
        val out = mutableListOf<Byte>()
        val buffer = ByteArray(readSize)
        appended.forEach { body ->
            queue.append(body)
            val count = queue.read(buffer)
            out += buffer.take(count)
        }
        while (!queue.isEmpty) {
            val count = queue.read(buffer)
            out += buffer.take(count)
        }
        return out
    }

    @Test
    fun `small reads of many records return every byte once, in order`() {
        val appended = bodies(*IntArray(40) { 37 + it })

        val out = run(ByteQueue(64), appended, readSize = 10)

        assertThat(out).isEqualTo(appended.flatMap { it.toList() })
    }

    @Test
    fun `a record larger than the buffer grows it, with the unread bytes kept in front`() {
        val queue = ByteQueue(16)
        val appended = bodies(10, 100, 3)

        val out = run(queue, appended, readSize = 4)

        assertThat(out).isEqualTo(appended.flatMap { it.toList() })
        assertThat(queue.isEmpty).isTrue()
    }

    @Test
    fun `room freed at the front is reused before the buffer grows`() {
        val queue = ByteQueue(16)
        val first = bodies(12).single()
        queue.append(first)
        val head = ByteArray(10)
        queue.read(head)
        val second = ByteArray(12) { (100 + it).toByte() }

        queue.append(second) // 2 unread + 12 fit in 16 only after moving the 2 to the front.
        val rest = ByteArray(32)
        val count = queue.read(rest)

        assertThat(rest.take(count)).isEqualTo(first.drop(10) + second.toList())
    }
}
