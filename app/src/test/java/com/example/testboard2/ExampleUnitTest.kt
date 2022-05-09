package com.example.testboard2

import org.junit.Test

import org.junit.Assert.*
import kotlin.properties.Delegates

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun test() {
        for (i in 6 downTo 0) println(i)
    }

    @Test
    fun test1() {
        B(1F,"qqq")
    }

    abstract class A(a: String) {

        protected var aa: String = a

        init {
            println("+++++++++++++A____init____aa:$aa")
            init()
        }

        abstract fun init()
    }

    class B(b: Float, a: String) : A(a) {

        private var bb:Float = b

        private var cc by Delegates.notNull<Int>()

        init {
            println("+++++++++++++B____init____aa:$bb")
            cc = 9
        }

        override fun init() {
            bb = 7F
            println("+++++++++++++____init()____aa:${aa}____bb:${bb}")
        }

    }
}