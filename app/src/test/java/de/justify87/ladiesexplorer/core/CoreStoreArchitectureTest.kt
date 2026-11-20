package de.justify87.ladiesexplorer.core

import java.lang.reflect.Modifier
import java.lang.reflect.Type
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Check compiled signatures without loading the native library. */
class CoreStoreArchitectureTest {
    private val engineType = LadiesCore::class.java.name
    private fun containsEngine(type: Type): Boolean =
        Regex("""\b${Regex.escape(engineType)}(?:Interface)?\b""").containsMatchIn(type.typeName)

    private fun exposedEngineMembers(type: Class<*>): List<String> =
        type.declaredMethods.filter { !it.isSynthetic && !Modifier.isPrivate(it.modifiers) &&
            (containsEngine(it.genericReturnType) || it.genericParameterTypes.any(::containsEngine))
        }.map { it.name } + type.declaredFields.filter {
            !it.isSynthetic && !Modifier.isPrivate(it.modifiers) && containsEngine(it.genericType)
        }.map { it.name }

    @Test fun nativeEngineDoesNotEscapeCoreStoreApi() {
        val exposed = exposedEngineMembers(CoreStore::class.java)
        assertTrue("ARC-03 native engine exposed: $exposed", exposed.isEmpty())
    }

    // Include inferred types and suspend Continuation signatures.
    private class BadApi(private val engine: LadiesCore) {
        fun getEngine() = engine
        suspend fun getEngineAsync() = engine
        fun getEngines() = listOf(engine)
    }

    @Test fun detectsInferredNativeTypes() {
        val exposed = exposedEngineMembers(BadApi::class.java)
        assertTrue(exposed.containsAll(listOf("getEngine", "getEngineAsync", "getEngines")))
        assertFalse(containsEngine(RequestControl::class.java))
    }
}
