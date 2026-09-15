package com.ayuvo.health.records.knowledge

import com.ayuvo.health.records.analytes.AnalyteCatalog
import com.ayuvo.health.records.model.EntityKind
import com.ayuvo.health.records.model.FieldKey
import com.ayuvo.health.records.model.FieldState
import com.ayuvo.health.records.model.RecordField
import com.ayuvo.health.records.processing.ExtractionWriter
import com.ayuvo.health.records.processing.RecordJson
import com.ayuvo.health.records.processing.RecordJson.string
import com.ayuvo.health.records.processing.RecordText

/**
 * §19 doctor / facility entities, ported from the reference `doctor_display_name`,
 * `normalize_doctor_name`, `facility_display_name`, `normalize_facility_name` and `rebuild_entities`.
 */
object EntityRules {

    data class Entity(val kind: EntityKind, val displayName: String, val normalizedName: String, val specialty: String?)
    data class Link(val kind: EntityKind, val normalizedName: String, val role: String)
    data class Rebuild(val entities: List<Entity>, val recordEntities: List<Link>)

    private val WS = Regex("[ \t\n]+")
    private val DOCTOR_TITLES = setOf("dr", "doctor", "prof", "professor")
    private val QUALIFICATIONS = setOf(
        "mbbs", "md", "ms", "dm", "mch", "dnb", "mrcp", "frcs", "bds", "mds", "dgo", "dch",
        "facc", "fics", "mrcog", "frcp", "dmrd", "dmrt", "phd", "bams", "bhms", "mph", "do",
        "mrcgp", "fcps", "facp", "frcpath", "dpm", "dortho", "dlo", "dvd", "dnbe", "fnb", "fracs", "mams"
    )
    private val FACILITY_WORD: Map<String, String?> = mapOf(
        "hospitals" to "hospital", "clinics" to "clinic", "laboratories" to "lab", "laboratory" to "lab",
        "labs" to "lab", "diagnostics" to "diagnostic", "centre" to "center", "centres" to "center",
        "centers" to "center", "pathlabs" to "pathlab", "speciality" to "specialty", "specialities" to "specialty",
        "specialties" to "specialty", "pvt" to null, "private" to null, "ltd" to null, "limited" to null,
        "llp" to null, "inc" to null, "and" to null
    )
    private val FACILITY_JOIN = listOf(
        Triple("health", "care", "healthcare"), Triple("path", "lab", "pathlab"), Triple("multi", "specialty", "multispecialty"),
        Triple("super", "specialty", "superspecialty"), Triple("poly", "clinic", "polyclinic")
    )

    private fun lettersOf(tok: String): String = RecordText.fold(tok).filter { it in 'a'..'z' }

    fun doctorDisplayName(value: String?): String {
        var p = RecordText.pfold(value ?: "").replace(WS, " ").trim(' ')
        val cut = listOf(p.indexOf(','), p.indexOf('(')).filter { it >= 0 }
        if (cut.isNotEmpty()) p = p.substring(0, cut.min())
        var toks = p.trim(' ', '.', '-').split(" ").filter { it.isNotEmpty() }
        if (toks.isNotEmpty()) {
            val t0 = RecordText.fold(toks[0])
            for (pre in listOf("dr.", "prof.")) {
                if (t0.startsWith(pre) && t0.length > pre.length) {
                    toks = listOf(toks[0].substring(0, pre.length), toks[0].substring(pre.length)) + toks.drop(1)
                    break
                }
            }
        }
        while (toks.size > 1 && lettersOf(toks[0]) in DOCTOR_TITLES) toks = toks.drop(1)
        while (toks.size > 1 && lettersOf(toks.last()) in QUALIFICATIONS) toks = toks.dropLast(1)
        return toks.joinToString(" ").trim(' ', '.', '-', '\'')
    }

    fun normalizeDoctorName(value: String?): String = AnalyteCatalog.analyteWords(doctorDisplayName(value)).joinToString(" ")

    fun facilityDisplayName(value: String?): String = RecordText.pfold(value ?: "").replace(WS, " ").trim(' ', ',', '.', '-', '|')

    fun normalizeFacilityName(value: String?): String {
        val ws = AnalyteCatalog.analyteWords(facilityDisplayName(value))
        val out = ws.mapNotNull { w -> if (FACILITY_WORD.containsKey(w)) FACILITY_WORD[w] else w }
        val joined = mutableListOf<String>()
        for (w in out) {
            if (joined.isNotEmpty()) {
                val pair = FACILITY_JOIN.firstOrNull { (a, b, _) -> joined.last() == a && w == b }?.third
                if (pair != null) {
                    joined[joined.size - 1] = pair
                    continue
                }
            }
            joined += w
        }
        if (joined.isNotEmpty() && joined[0] == "the" && joined.size > 1) joined.removeAt(0)
        return if (joined.isNotEmpty()) joined.joinToString(" ") else ws.joinToString(" ")
    }

    /** Filter text / picker match: the same normalization as the stored name. */
    fun normalize(kind: EntityKind, name: String): String = when (kind) {
        EntityKind.DOCTOR -> normalizeDoctorName(name)
        EntityKind.FACILITY -> normalizeFacilityName(name)
    }

    private fun bestOf(rows: List<RecordField>): RecordField? {
        val rank = mapOf(FieldState.USER to 0, FieldState.CONFIRMED to 1, FieldState.SUGGESTED to 2)
        return rows.withIndex()
            .filter { (_, r) -> r.state != FieldState.REJECTED && (r.state == FieldState.USER || r.state == FieldState.CONFIRMED || r.confidence >= 0.6) }
            .sortedWith(compareBy<IndexedValue<RecordField>>({ rank.getValue(it.value.state) }, { -it.value.confidence }, { it.index }))
            .firstOrNull()?.value
    }

    /** Reference `rebuild_entities`. */
    fun rebuild(fields: List<RecordField>): Rebuild {
        fun role(r: RecordField) = RecordJson.parseObject(r.valueJson)?.string("role")
        val docs = fields.filter { it.key == FieldKey.DOCTOR_NAME }
        val prim = bestOf(docs.filter { role(it) != "referrer" })
        val ref = bestOf(docs.filter { role(it) == "referrer" })
        val spec = ExtractionWriter.best(fields, FieldKey.DOCTOR_SPECIALTY)
        val fac = ExtractionWriter.best(fields, FieldKey.FACILITY)
        val entities = mutableListOf<Entity>()
        val links = mutableListOf<Link>()
        fun add(kind: EntityKind, display: String, norm: String, specialty: String?, rl: String) {
            if (norm.isEmpty()) return
            var idx = entities.indexOfFirst { it.kind == kind && it.normalizedName == norm }
            if (idx < 0) {
                entities += Entity(kind, display, norm, null)
                idx = entities.size - 1
            }
            if (!specialty.isNullOrEmpty() && entities[idx].specialty.isNullOrEmpty()) entities[idx] = entities[idx].copy(specialty = specialty)
            val link = Link(kind, norm, rl)
            if (link !in links) links += link
        }
        prim?.let { add(EntityKind.DOCTOR, doctorDisplayName(it.valueText), normalizeDoctorName(it.valueText), spec?.valueText, "doctor") }
        ref?.let { add(EntityKind.DOCTOR, doctorDisplayName(it.valueText), normalizeDoctorName(it.valueText), null, "referrer") }
        fac?.let { add(EntityKind.FACILITY, facilityDisplayName(it.valueText), normalizeFacilityName(it.valueText), null, "facility") }
        return Rebuild(entities, links)
    }
}
