package dev.umi.amperomini.protocol

data class PresetEffect(
    val module: String,
    val model: String,
    val enabled: Boolean,
    val parameters: List<EffectParameter>,
    val modelCode: Int,
)

data class ParameterAddress(val block: Int, val model: Int, val index: Int)

data class EffectParameter(val name: String, val value: Int, val address: ParameterAddress?,
                           val scale: ParameterScale = ParameterScale())

data class ParameterScale(
    val maximum: Int = 100,
    val start: Double = 0.0,
    val step: Double = 1.0,
    val options: List<String> = emptyList(),
    val syncControl: String = "",
    val suffix: String = "",
) {
    fun label(raw: Int): String {
        options.getOrNull(raw)?.let { return it }
        val number = java.math.BigDecimal.valueOf(start + raw * step)
            .setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros()
        return number.toPlainString() + suffix
    }
}

internal object EffectCatalog {
    private data class Control(val id: Int, val name: String, val editable: Boolean, val scale: ParameterScale)
    private data class Model(val name: String, val controls: List<Control>)
    private const val FIRST_BLOCK = 95
    private const val BLOCK_SIZE = 33
    private val modules = listOf("FX1", "FX2", "AMP", "NR", "CAB", "EQ", "FX3", "DLY", "RVB")

    // Mini editor model codes are BaseIndex + ChildIndex, scoped to a module.
    private val models by lazy {
        requireNotNull(EffectCatalog::class.java.getResourceAsStream("/ampero-mini-models.tsv"))
            .bufferedReader().useLines { lines ->
                lines.associate { line ->
                    val fields = line.split('\t')
                    val controls = fields.drop(3).map {
                        // A star marks an editor knob with a direct 0..100 scale.
                        val key = it.substringBefore('=')
                        val spec = it.substringAfter('=').split('|')
                        val scale = if (spec.size == 1) ParameterScale() else ParameterScale(
                            spec[1].toInt(), spec[2].toDouble(), spec[3].toDouble(),
                            spec[4].split('~').filter(String::isNotEmpty), spec[5], spec[6],
                        )
                        Control(key.removeSuffix("*").toInt(), spec.first(), key.endsWith("*") || spec.size > 1, scale)
                    }
                    (fields[0] to fields[1].toInt()) to Model(fields[2], controls)
                }
            }
    }

    fun blockId(module: String): Int = modules.indexOf(module) + 1

    fun choices(module: String): Map<Int, String> = models.entries
        .filter { it.key.first == module }.associate { it.key.second to it.value.name }

    fun decode(blob: ByteArray): List<PresetEffect> {
        if (blob.size < FIRST_BLOCK + BLOCK_SIZE * modules.size) return emptyList()
        fun value(offset: Int) = (blob[offset].toInt() and 127) * 128 + (blob[offset + 1].toInt() and 127)
        return modules.mapIndexed { index, module ->
            val start = FIRST_BLOCK + index * BLOCK_SIZE
            val code = value(start + 1)
            val model = models[module to code]
            val parameters = model?.controls.orEmpty().map { control ->
                val slot = control.id - 1
                val raw = value(start + 3 + slot * 2)
                val address = if (control.editable && raw in 0..control.scale.maximum) ParameterAddress(index + 1, code, slot) else null
                EffectParameter(control.name, raw, address, control.scale)
            }
            PresetEffect(module, model?.name ?: "Модель №$code", blob[start].toInt() == 1, parameters, code)
        }
    }
}
