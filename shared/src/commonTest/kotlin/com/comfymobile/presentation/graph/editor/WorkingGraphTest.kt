package com.comfymobile.presentation.graph.editor

import com.comfymobile.domain.workflow.TopologyOp
import com.comfymobile.domain.workflow.WorkflowEnvelope
import com.comfymobile.domain.workflow.WorkflowFormat
import com.comfymobile.domain.workflow.WorkflowMetadata
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end coverage for `WorkingGraph` + `WorkflowConverter.applyTopologyOps`.
 *
 * These tests exercise the four ops in ADR-0005 §1 from the editor's
 * top-level API, asserting both the in-memory log behaviour and the
 * resulting JSON-level fold. The validation surface (§5 V1..V7) is
 * deferred to a follow-up task and not asserted here beyond the
 * existing `canAddNode` guard.
 */
class WorkingGraphTest {

    // --- fixtures -------------------------------------------------------------

    private fun emptyUiEnvelope(): WorkflowEnvelope = WorkflowEnvelope(
        original = buildJsonObject {
            put("nodes", JsonArray(emptyList()))
            put("links", JsonArray(emptyList()))
        },
        format = WorkflowFormat.UI,
        metadata = WorkflowMetadata(
            label = "test",
            createdAtEpochMs = 0L,
            lastEditedAtEpochMs = 0L,
        ),
    )

    private fun envelopeWithTwoNodesOneLink(): WorkflowEnvelope = WorkflowEnvelope(
        original = buildJsonObject {
            put("nodes", buildJsonArray {
                add(buildJsonObject {
                    put("id", JsonPrimitive(1))
                    put("type", JsonPrimitive("CheckpointLoaderSimple"))
                    put("inputs", JsonArray(emptyList()))
                    put("outputs", buildJsonArray {
                        add(buildJsonObject {
                            put("name", JsonPrimitive("MODEL"))
                            put("type", JsonPrimitive("MODEL"))
                            put("links", buildJsonArray { add(JsonPrimitive(1)) })
                        })
                    })
                    put("widgets_values", buildJsonArray { add(JsonPrimitive("base.safetensors")) })
                })
                add(buildJsonObject {
                    put("id", JsonPrimitive(2))
                    put("type", JsonPrimitive("KSampler"))
                    put("inputs", buildJsonArray {
                        add(buildJsonObject {
                            put("name", JsonPrimitive("model"))
                            put("type", JsonPrimitive("MODEL"))
                            put("link", JsonPrimitive(1))
                        })
                    })
                    put("outputs", JsonArray(emptyList()))
                    put("widgets_values", JsonArray(emptyList()))
                })
            })
            put("links", buildJsonArray {
                add(buildJsonArray {
                    add(JsonPrimitive(1)); add(JsonPrimitive(1)); add(JsonPrimitive(0))
                    add(JsonPrimitive(2)); add(JsonPrimitive(0)); add(JsonPrimitive("MODEL"))
                })
            })
        },
        format = WorkflowFormat.UI,
        metadata = WorkflowMetadata(
            label = "test",
            createdAtEpochMs = 0L,
            lastEditedAtEpochMs = 0L,
        ),
    )

    private fun objectInfoFor(classType: String) = buildJsonObject {
        put(classType, buildJsonObject {
            put("input", buildJsonObject {
                put("required", buildJsonObject { })
            })
        })
    }

    // --- pre-condition --------------------------------------------------------

    @Test
    fun rejectsApiFormatEnvelope() {
        // ADR-0005 §1 out-of-scope: API-format workflows are run-only in Phase 3.
        val apiEnvelope = emptyUiEnvelope().copy(format = WorkflowFormat.API)
        val failure = assertFails {
            WorkingGraph(
                initialEnvelope = apiEnvelope,
                importedOriginal = apiEnvelope.original,
            )
        }
        assertTrue("API-format" in failure.message.orEmpty(), "Failure message must call out API-format constraint, was: ${failure.message}")
    }

    // --- AddNode --------------------------------------------------------------

    @Test
    fun addNode_appendsToNodesArray_andLogsAssignedId() {
        val env = emptyUiEnvelope()
        val objectInfo = objectInfoFor("KSampler")
        val wg = WorkingGraph(env, env.original, objectInfo = objectInfo)

        assertTrue(wg.canAddNode("KSampler"))
        val newId = wg.addNode("KSampler", posX = 100f, posY = 200f)
        assertEquals("1", newId, "Empty graph: first minted id must be 1.")

        val rendered = wg.renderedUi.raw
        val nodes = rendered["nodes"]!!.jsonArray
        assertEquals(1, nodes.size)
        val node = nodes.single().jsonObject
        assertEquals(1, node["id"]!!.jsonPrimitive.intOrNull)
        assertEquals("KSampler", node["type"]!!.jsonPrimitive.content)
        val pos = node["pos"]!!.jsonArray
        assertEquals(100f, pos[0].jsonPrimitive.content.toFloat())
        assertEquals(200f, pos[1].jsonPrimitive.content.toFloat())
    }

    @Test
    fun addNode_blocksWhenObjectInfoMissingForClass() {
        val env = emptyUiEnvelope()
        // Pass a non-null objectInfo that does NOT contain "KSampler".
        val objectInfo = objectInfoFor("CheckpointLoaderSimple")
        val wg = WorkingGraph(env, env.original, objectInfo = objectInfo)

        assertFalse(wg.canAddNode("KSampler"))
        assertFails { wg.addNode("KSampler", 0f, 0f) }
    }

    @Test
    fun addNode_idMintingAvoidsCollisionsWithExistingAndPending() {
        val env = envelopeWithTwoNodesOneLink() // ids = 1, 2
        val objectInfo = objectInfoFor("KSampler")
        val wg = WorkingGraph(env, env.original, objectInfo = objectInfo)

        val firstNew = wg.addNode("KSampler", 0f, 0f)
        val secondNew = wg.addNode("KSampler", 0f, 0f)
        assertEquals("3", firstNew)
        assertEquals("4", secondNew, "Subsequent AddNode must respect pending log entries.")
    }

    // --- RemoveNode -----------------------------------------------------------

    @Test
    fun removeNode_dropsNodeAndCascadesLinks() {
        val env = envelopeWithTwoNodesOneLink()
        val wg = WorkingGraph(env, env.original)

        val cascaded = wg.removeNode("1")
        assertEquals(listOf(1), cascaded, "Link id=1 must cascade out when node 1 is removed.")

        val nodes = wg.renderedUi.raw["nodes"]!!.jsonArray
        assertEquals(1, nodes.size)
        assertEquals(2, nodes.single().jsonObject["id"]!!.jsonPrimitive.intOrNull)
        val links = wg.renderedUi.raw["links"]!!.jsonArray
        assertEquals(0, links.size, "All incident links must be removed in the same op.")
    }

    @Test
    fun removeNode_isCompoundAtomicForUndo() {
        // ADR-0005 §6 Q5: single undo restores node + every cascaded link.
        val env = envelopeWithTwoNodesOneLink()
        val wg = WorkingGraph(env, env.original)

        wg.removeNode("1")
        // One log entry covers both the node and its link.
        assertEquals(true, wg.canUndo)

        val undone = wg.undo()
        assertNotNull(undone)
        assertTrue(undone is TopologyOp.RemoveNode)

        val nodes = wg.renderedUi.raw["nodes"]!!.jsonArray
        assertEquals(2, nodes.size, "Undo of compound RemoveNode must restore the node.")
        val links = wg.renderedUi.raw["links"]!!.jsonArray
        assertEquals(1, links.size, "Undo of compound RemoveNode must also restore the cascaded link.")
    }

    @Test
    fun removeNode_unknownIdFails() {
        val env = envelopeWithTwoNodesOneLink()
        val wg = WorkingGraph(env, env.original)
        assertFails { wg.removeNode("999") }
    }

    // --- Connect / Disconnect -------------------------------------------------

    @Test
    fun connect_appendsLinkAndUpdatesTargetInputLink() {
        // Start with two nodes, no link. Add a second slot to KSampler's
        // inputs so Connect has a real targetSlot to land on.
        val nodes = buildJsonArray {
            add(buildJsonObject {
                put("id", JsonPrimitive(1)); put("type", JsonPrimitive("CheckpointLoaderSimple"))
                put("inputs", JsonArray(emptyList()))
                put("outputs", buildJsonArray { add(buildJsonObject { put("name", JsonPrimitive("MODEL")) }) })
            })
            add(buildJsonObject {
                put("id", JsonPrimitive(2)); put("type", JsonPrimitive("KSampler"))
                put("inputs", buildJsonArray {
                    add(buildJsonObject { put("name", JsonPrimitive("model")); put("link", JsonPrimitive("")) })
                })
                put("outputs", JsonArray(emptyList()))
            })
        }
        val env = WorkflowEnvelope(
            original = buildJsonObject {
                put("nodes", nodes); put("links", JsonArray(emptyList()))
            },
            format = WorkflowFormat.UI,
            metadata = WorkflowMetadata("t", 0L, 0L),
        )
        val wg = WorkingGraph(env, env.original)

        val linkId = wg.connect("1", 0, "2", 0, "MODEL")
        assertEquals(1, linkId)

        val rendered = wg.renderedUi.raw
        val links = rendered["links"]!!.jsonArray
        assertEquals(1, links.size)
        val tuple = links.single().jsonArray
        assertEquals(1, tuple[0].jsonPrimitive.intOrNull)
        assertEquals(1, tuple[1].jsonPrimitive.intOrNull)
        assertEquals(0, tuple[2].jsonPrimitive.intOrNull)
        assertEquals(2, tuple[3].jsonPrimitive.intOrNull)
        assertEquals(0, tuple[4].jsonPrimitive.intOrNull)
        assertEquals("MODEL", tuple[5].jsonPrimitive.content)

        // Target node's input slot now references the new link.
        val targetNode = rendered["nodes"]!!.jsonArray
            .first { (it as JsonObject)["id"]!!.jsonPrimitive.intOrNull == 2 } as JsonObject
        val slotLink = targetNode["inputs"]!!.jsonArray.single().jsonObject["link"]!!.jsonPrimitive.intOrNull
        assertEquals(1, slotLink)
    }

    @Test
    fun disconnect_removesLinkAndClearsTargetInputLink() {
        val env = envelopeWithTwoNodesOneLink()
        val wg = WorkingGraph(env, env.original)

        wg.disconnect(1)
        val rendered = wg.renderedUi.raw
        assertEquals(0, rendered["links"]!!.jsonArray.size)

        val targetNode = rendered["nodes"]!!.jsonArray
            .first { (it as JsonObject)["id"]!!.jsonPrimitive.intOrNull == 2 } as JsonObject
        val slot = targetNode["inputs"]!!.jsonArray.single().jsonObject
        // After disconnect the "link" field should be null (JsonNull),
        // not the prior int.
        assertNull(slot["link"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun disconnect_unknownIdFails() {
        val env = envelopeWithTwoNodesOneLink()
        val wg = WorkingGraph(env, env.original)
        assertFails { wg.disconnect(999) }
    }

    // --- confirm / reset ------------------------------------------------------

    @Test
    fun confirm_foldsLogIntoOriginal_butLeavesImportedAnchor() {
        val env = envelopeWithTwoNodesOneLink()
        val imported = env.original  // anchor reference for resetToImported
        val wg = WorkingGraph(env, imported)

        wg.removeNode("2")  // mutate: drop the KSampler
        val newEnvelope = wg.confirm()

        // Log is empty after confirm.
        assertFalse(wg.canUndo)
        // envelope.original was rewritten:
        val nodesAfterConfirm = (newEnvelope.original as JsonObject)["nodes"]!!.jsonArray
        assertEquals(1, nodesAfterConfirm.size)
        assertEquals(1, nodesAfterConfirm.single().jsonObject["id"]!!.jsonPrimitive.intOrNull)
    }

    @Test
    fun resetToImported_rebuildsEnvelopeFromAnchor_andClearsLog() {
        val env = envelopeWithTwoNodesOneLink()
        val imported = env.original
        val wg = WorkingGraph(env, imported)

        wg.removeNode("2")
        wg.confirm()
        // At this point envelope.original was folded; resetToImported
        // must roll back to the as-imported snapshot anyway. This is
        // the ADR-0005 §2 / T0.3 invariant the imported_original_json
        // column will persist across sessions.
        val resetEnvelope = wg.resetToImported()
        val nodes = (resetEnvelope.original as JsonObject)["nodes"]!!.jsonArray
        assertEquals(2, nodes.size, "resetToImported must restore the as-imported node count, even after a confirm folded away one node.")
        assertFalse(wg.canUndo)
        assertFalse(wg.canRedo)
    }

    // --- structure-lossless ---------------------------------------------------

    @Test
    fun applyTopologyOps_passesThroughUnknownTopLevelKeys() {
        // Per ADR-0003 / ADR-0005 §2 structure-lossless: any key on
        // the original raw object that we don't explicitly touch
        // must survive a fold unchanged.
        val env = WorkflowEnvelope(
            original = buildJsonObject {
                put("nodes", JsonArray(emptyList()))
                put("links", JsonArray(emptyList()))
                put("viewport", buildJsonObject {
                    put("offset", buildJsonArray { add(JsonPrimitive(50f)); add(JsonPrimitive(80f)) })
                })
                put("custom_node_extension", JsonPrimitive("preserve me"))
            },
            format = WorkflowFormat.UI,
            metadata = WorkflowMetadata("t", 0L, 0L),
        )
        val wg = WorkingGraph(env, env.original, objectInfo = objectInfoFor("KSampler"))
        wg.addNode("KSampler", 10f, 20f)
        val rendered = wg.renderedUi.raw

        assertEquals("preserve me", rendered["custom_node_extension"]!!.jsonPrimitive.content)
        val offset = rendered["viewport"]!!.jsonObject["offset"]!!.jsonArray
        assertEquals(50f, offset[0].jsonPrimitive.content.toFloat())
        assertEquals(80f, offset[1].jsonPrimitive.content.toFloat())
    }
}
