package com.lumenpearson.lessons.core.data.docs

import com.lumenpearson.lessons.core.model.DocsBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the parser is allowed to do to the guide.
 *
 * The rule under most of these is the same one: **the text survives**. A
 * documentation parser that drops a sentence is worse than one that draws it
 * plainly, because nothing on the screen says a line is missing and the reader
 * is looking for the thing it explained.
 */
class DocsMarkdownTest {

    private fun parse(markdown: String) = DocsMarkdown.parse(markdown.trimIndent(), "ru")

    @Test
    fun `a heading and its metadata become a page`() {
        val guide = parse(
            """
            # Книга

            ## Первый запуск
            <!-- id: START; label: Старт; summary: Пять шагов и код класса -->

            Расписание ведёт бот.
            """,
        )

        assertEquals("Книга", guide.title)
        assertEquals(1, guide.pages.size)
        val page = guide.pages.single()
        assertEquals("START", page.id)
        assertEquals("Первый запуск", page.title)
        assertEquals("Старт", page.label)
        assertEquals("Пять шагов и код класса", page.summary)
    }

    @Test
    fun `the four block shapes are told apart`() {
        val guide = parse(
            """
            ## Страница
            <!-- id: P; label: П; summary: — -->

            Обычный абзац.

            - Первый пункт
            - Второй пункт

            1. **Приветствие** — Знак приложения.
            2. **Свойства** — Отклик и цвета.

            > То, что нельзя пролистать.
            """,
        )

        val blocks = guide.pages.single().blocks
        assertEquals(5, blocks.size)
        assertTrue(blocks[0] is DocsBlock.Paragraph)
        assertEquals(2, (blocks[1] as DocsBlock.Points).items.size)
        val step = blocks[2] as DocsBlock.Step
        assertEquals(1, step.number)
        assertEquals("Приветствие", step.title.single().text)
        assertEquals("Знак приложения.", step.text.single().text)
        assertEquals(2, (blocks[3] as DocsBlock.Step).number)
        assertTrue(blocks[4] is DocsBlock.Note)
    }

    @Test
    fun `a wrapped paragraph is one paragraph and keeps its words`() {
        val guide = parse(
            """
            ## Страница
            <!-- id: P; label: П; summary: — -->

            Одно предложение, перенесённое
            на три строки редактором,
            остаётся одним.
            """,
        )

        val paragraph = guide.pages.single().blocks.single() as DocsBlock.Paragraph
        assertEquals(
            "Одно предложение, перенесённое на три строки редактором, остаётся одним.",
            paragraph.spans.single().text,
        )
    }

    @Test
    fun `a step numbers itself from the file rather than from its position`() {
        // The guide writes steps that follow a paragraph, and a step that
        // counted list positions would start again from one every time.
        val guide = parse(
            """
            ## Страница
            <!-- id: P; label: П; summary: — -->

            Вступление.

            4. **Разрешения** — Уведомления и будильники.
            """,
        )

        val step = guide.pages.single().blocks.last() as DocsBlock.Step
        assertEquals(4, step.number)
    }

    @Test
    fun `a step without a bold lead keeps its whole line`() {
        val guide = parse(
            """
            ## Страница
            <!-- id: P; label: П; summary: — -->

            1. Просто шаг без заголовка.
            """,
        )

        val step = guide.pages.single().blocks.single() as DocsBlock.Step
        assertTrue(step.title.isEmpty())
        assertEquals("Просто шаг без заголовка.", step.text.single().text)
    }

    @Test
    fun `bold, code and a link are split out and their text is kept`() {
        val spans = DocsMarkdown.spans("Нажмите **«Сервер»**, введите `localhost` или [адрес](https://x.test/).")

        assertEquals(
            "Нажмите «Сервер», введите localhost или адрес.",
            spans.joinToString("") { it.text },
        )
        assertTrue(spans.single { it.bold }.text == "«Сервер»")
        assertTrue(spans.single { it.code }.text == "localhost")
        assertEquals("https://x.test/", spans.single { it.link != null }.link)
    }

    @Test
    fun `a line with no marks is one span, and an empty line is none`() {
        assertEquals(1, DocsMarkdown.spans("Обычная строка").size)
        assertTrue(DocsMarkdown.spans("").isEmpty())
    }

    @Test
    fun `a comment that is not metadata is not drawn`() {
        // The file opens with a note to whoever edits it. It is not for the
        // screen, and a parser that let it through would print it as prose.
        val guide = parse(
            """
            <!--
              Заметка для того, кто правит файл.
              Она занимает несколько строк.
            -->

            ## Страница
            <!-- id: P; label: П; summary: — -->

            Только этот абзац.
            """,
        )

        val blocks = guide.pages.single().blocks
        assertEquals(1, blocks.size)
        assertEquals("Только этот абзац.", (blocks.single() as DocsBlock.Paragraph).spans.single().text)
    }

    @Test
    fun `a page with no metadata still gets an id and a label`() {
        val guide = parse(
            """
            ## Без комментария

            Абзац.
            """,
        )

        val page = guide.pages.single()
        assertEquals("БЕЗ КОММЕНТАРИЯ", page.id)
        assertEquals("Без комментария", page.label)
        assertEquals("", page.summary)
    }

    @Test
    fun `nothing that is not markdown throws`() {
        // The parser stands between the network and the screen. Whatever
        // arrives, the answer is a guide — empty if there is nothing in it —
        // and the caller decides whether to keep what it already had.
        listOf("", "   ", "<html><body>404", "{\"error\": true}", "## ", "-->")
            .forEach { input ->
                val guide = DocsMarkdown.parse(input, "ru")
                assertTrue(
                    "«$input» should parse to something drawable, not throw",
                    guide.pages.size <= 1,
                )
            }
    }
}
