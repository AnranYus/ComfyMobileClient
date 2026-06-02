package com.comfymobile.presentation.graph.editor

import com.comfymobile.domain.workflow.TopologyOp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit coverage for the undo / redo state machine.
 * No JSON parsing here — keep this test about stack semantics.
 */
class OpLogTest {

    private fun add(id: String) = TopologyOp.AddNode(
        classType = "KSampler",
        posX = 0f,
        posY = 0f,
        assignedId = id,
    )

    private fun connect(id: Int) = TopologyOp.Connect(
        sourceNodeId = "1",
        sourceSlot = 0,
        targetNodeId = "2",
        targetSlot = 0,
        type = "MODEL",
        assignedLinkId = id,
    )

    private fun disconnect(id: Int) = TopologyOp.Disconnect(
        linkId = id,
        removedLink = emptyJsonArray(),
    )

    private fun emptyJsonArray(): JsonArray = buildJsonArray { }

    @Test
    fun freshLog_isEmpty_andCannotUndoOrRedo() {
        val log = OpLog()
        assertEquals(0, log.size)
        assertFalse(log.canUndo)
        assertFalse(log.canRedo)
        assertEquals(emptyList(), log.entries)
    }

    @Test
    fun append_growsTheLog_andEnablesUndo() {
        val log = OpLog()
        val op = add("1")
        log.append(op)
        assertEquals(1, log.size)
        assertTrue(log.canUndo)
        assertFalse(log.canRedo)
        assertEquals(listOf<TopologyOp>(op), log.entries)
    }

    @Test
    fun undo_movesTailToRedoStack_andReturnsTheOp() {
        val log = OpLog()
        val first = add("1")
        val second = connect(7)
        log.append(first)
        log.append(second)

        val undone = log.undo()
        assertSame(second, undone)
        assertEquals(1, log.size)
        assertEquals(listOf<TopologyOp>(first), log.entries)
        assertTrue(log.canRedo)
    }

    @Test
    fun redo_restoresLastUndoneOp() {
        val log = OpLog()
        val op = add("1")
        log.append(op)
        log.undo()
        val redone = log.redo()
        assertSame(op, redone)
        assertEquals(listOf<TopologyOp>(op), log.entries)
        assertFalse(log.canRedo)
    }

    @Test
    fun appendAfterUndo_clearsRedoStack() {
        // Canonical branched-timeline behaviour: making a new
        // mutation after an undo invalidates the redo path.
        val log = OpLog()
        val first = add("1")
        val second = connect(7)
        val third = disconnect(7)
        log.append(first)
        log.append(second)
        log.undo()           // redoStack now has [second]
        assertTrue(log.canRedo)
        log.append(third)    // should clear redoStack
        assertFalse(log.canRedo)
        assertEquals(listOf<TopologyOp>(first, third), log.entries)
    }

    @Test
    fun undoOnEmptyLog_returnsNull_andLeavesState() {
        val log = OpLog()
        assertNull(log.undo())
        assertEquals(0, log.size)
        assertFalse(log.canRedo)
    }

    @Test
    fun redoOnEmptyStack_returnsNull() {
        val log = OpLog()
        log.append(add("1"))
        // Never undid → redoStack is empty.
        assertNull(log.redo())
        assertEquals(1, log.size)
    }

    @Test
    fun clear_dropsBothStacks() {
        val log = OpLog()
        log.append(add("1"))
        log.append(add("2"))
        log.undo()
        log.clear()
        assertEquals(0, log.size)
        assertFalse(log.canUndo)
        assertFalse(log.canRedo)
    }

    @Test
    fun multipleUndosThenMultipleRedos_replayLifo() {
        val log = OpLog()
        val a = add("1")
        val b = add("2")
        val c = add("3")
        log.append(a); log.append(b); log.append(c)
        log.undo(); log.undo()           // applied = [a]
        assertEquals(listOf<TopologyOp>(a), log.entries)
        log.redo()                       // applied = [a, b]
        assertEquals(listOf<TopologyOp>(a, b), log.entries)
        log.redo()                       // applied = [a, b, c]
        assertEquals(listOf<TopologyOp>(a, b, c), log.entries)
        assertFalse(log.canRedo)
    }
}
