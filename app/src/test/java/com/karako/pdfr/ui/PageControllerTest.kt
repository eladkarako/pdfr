package com.karako.pdfr.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the page state shared by the reader and its permanent page bar:
 * clamping, single-page documents and the jump hand-off.
 */
class PageControllerTest {

    @Test
    fun startsOnTheOnlyPage() {
        val controller = PageController()
        assertEquals(1, controller.pageCount)
        assertEquals(0, controller.currentPage)
    }

    @Test
    fun clampsTheCurrentPageToTheDocument() {
        val controller = PageController()
        controller.setPages(10, current = 3)
        assertEquals(10, controller.pageCount)
        assertEquals(3, controller.currentPage)

        // A remembered page can be past the end after a rewrite.
        controller.setPages(4, current = 9)
        assertEquals(4, controller.pageCount)
        assertEquals(3, controller.currentPage)

        controller.setPages(4, current = -2)
        assertEquals(0, controller.currentPage)
    }

    @Test
    fun onePageDocumentNeverLeavesPageOne() {
        val controller = PageController()
        controller.setPages(1, current = 0)
        controller.goToPage(5)
        assertEquals(0, controller.currentPage)
        controller.updateCurrentPage(-1)
        assertEquals(0, controller.currentPage)
    }

    @Test
    fun goToPageClampsAndDelegatesTheJump() {
        val controller = PageController()
        controller.setPages(8, current = 0)

        var jumped = -1
        controller.setJumpHandler { jumped = it }

        controller.goToPage(3)
        assertEquals(3, controller.currentPage)
        assertEquals(3, jumped)

        controller.goToPage(-4)
        assertEquals(0, controller.currentPage)
        assertEquals(0, jumped)

        controller.goToPage(99)
        assertEquals(7, controller.currentPage)
        assertEquals(7, jumped)
    }

    @Test
    fun removingTheJumpHandlerKeepsThePageState() {
        val controller = PageController()
        controller.setPages(5, current = 2)
        controller.setJumpHandler { }
        controller.setJumpHandler(null)

        // No handler means the toolbar still moves the number, it just cannot
        // ask a reader that has been disposed to scroll.
        controller.goToPage(4)
        assertEquals(4, controller.currentPage)
    }

    @Test
    fun updateCurrentPageFollowsScrollingWithoutJumping() {
        val controller = PageController()
        controller.setPages(6, current = 0)
        var jumps = 0
        controller.setJumpHandler { jumps++ }

        controller.updateCurrentPage(4)
        assertEquals(4, controller.currentPage)
        assertEquals(0, jumps)
    }
}
