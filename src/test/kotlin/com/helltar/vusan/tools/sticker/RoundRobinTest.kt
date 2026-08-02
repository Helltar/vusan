package com.helltar.vusan.tools.sticker

import kotlin.test.Test
import kotlin.test.assertEquals

class RoundRobinTest {

    @Test
    fun `a long source cannot crowd out the others`() {
        val picked = roundRobin(listOf((1..60).toList(), (61..120).toList(), (121..180).toList()), limit = 80)

        assertEquals(80, picked.size)

        val perSource = picked.groupingBy { (it - 1) / 60 }.eachCount()
        assertEquals(mapOf(0 to 27, 1 to 27, 2 to 26), perSource)
    }

    @Test
    fun `a source that runs out leaves its room to the rest`() {
        val picked = roundRobin(listOf(listOf("a1", "a2"), listOf("b1"), listOf("c1", "c2", "c3")), limit = 10)

        assertEquals(listOf("a1", "b1", "c1", "a2", "c2", "c3"), picked)
    }

    @Test
    fun `everything fits when the limit is generous`() {
        val picked = roundRobin(listOf(listOf(1, 2), listOf(3)), limit = 99)

        assertEquals(listOf(1, 3, 2), picked)
    }

    @Test
    fun `no sources and no room both give nothing`() {
        assertEquals(emptyList(), roundRobin(emptyList<List<Int>>(), limit = 5))
        assertEquals(emptyList(), roundRobin(listOf(listOf(1, 2)), limit = 0))
    }
}
