package pw.vasilevskiy.loeuc.extension

import org.junit.Assert.assertEquals
import org.junit.Test

class ExtensionMetricTest {

    @Test
    fun codesAreDenseAndNamedOnce() {
        val codes = ExtensionMetric.NAMES.keys.sorted()
        assertEquals((1..codes.size).toList(), codes)
        assertEquals(codes.size, ExtensionMetric.NAMES.values.toSet().size)
    }

    @Test
    fun namesMatchTheirConstants() {
        ExtensionMetric::class.java.declaredFields
            .filter { it.type == Int::class.javaPrimitiveType && java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .forEach { field -> assertEquals(field.name, ExtensionMetric.NAMES[field.getInt(null)]) }
    }
}
