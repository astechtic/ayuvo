package com.ayuvo.health.medications.logic

import com.ayuvo.health.medications.logic.MedicationJson.str
import com.ayuvo.health.medications.model.DoseUnit
import com.ayuvo.health.medications.model.FoodRelation
import com.ayuvo.health.medications.model.FrequencyDraft
import com.ayuvo.health.medications.model.MedicationForm
import com.ayuvo.health.medications.model.ScheduleFrequency
import kotlinx.serialization.json.JsonObject

/**
 * `frequency_hint` (docs/medications.md §13): a pre-filled Add-medication draft from a Records
 * `medication` field value_json `{name, strength, form, dose, frequency, duration, instructions}`.
 * Every default is listed in `notes`; the draft is a suggestion the user reviews before anything
 * is created. Regexes are the reference's verbatim (portable subset; text is lowercased first).
 */
object FrequencyHint {

    private val FORM_MAP = listOf(
        "tablet" to "tablet", "tab" to "tablet", "capsule" to "capsule", "cap" to "capsule",
        "syrup" to "syrup", "syp" to "syrup", "syr" to "syrup", "suspension" to "syrup", "susp" to "syrup",
        "solution" to "syrup", "soln" to "syrup", "injection" to "injection", "inj" to "injection",
        "ointment" to "cream", "oint" to "cream", "cream" to "cream", "gel" to "cream", "lotion" to "cream",
        "drops" to "drops", "drop" to "drops", "inhaler" to "inhaler", "nebulisation" to "inhaler",
        "nebulization" to "inhaler", "neb" to "inhaler", "respules" to "inhaler", "respule" to "inhaler",
        "sachet" to "other", "powder" to "other", "spray" to "other"
    )
    private val DEFAULT_UNIT_FOR_FORM = mapOf(
        "tablet" to "tablet", "capsule" to "capsule", "drops" to "drop", "inhaler" to "puff", "injection" to "unit",
        "cream" to "application", "syrup" to "ml", "other" to "unit"
    )
    private val TRIPLET = Regex(
        "^([0-9]+(?:/[0-9]+)?|[0-9]+\\.[0-9]+)-([0-9]+(?:/[0-9]+)?|[0-9]+\\.[0-9]+)-" +
            "([0-9]+(?:/[0-9]+)?|[0-9]+\\.[0-9]+)(?:-([0-9]+(?:/[0-9]+)?|[0-9]+\\.[0-9]+))?$"
    )
    private val EVERY_H = Regex("(?:^|[^a-z0-9])(?:every|each) ([0-9]{1,2}) ?(?:hours|hour|hrs|hr|hourly|h)(?:$|[^a-z0-9])")
    private val QNH = Regex("(?:^|[^a-z0-9])q([0-9]{1,2}) ?h(?:$|[^a-z0-9])")
    private val N_HOURLY = Regex("(?:^|[^a-z0-9])([0-9]{1,2}) ?hourly(?:$|[^a-z0-9])")
    private val DURATION = Regex(
        "(?:^|[^a-z0-9])(?:x|for)? ?([0-9]{1,3}) ?(days|day|d|weeks|week|wks|wk|w|months|month|mths|mth|m)(?:$|[^a-z0-9])"
    )
    private val DOSE = Regex(
        "(?:^|[^a-z0-9])([0-9]+(?:\\.[0-9]+)?|1/2) ?(tablets|tablet|tabs|tab|capsules|capsule|caps|cap|" +
            "puffs|puff|drops|drop|sachets|sachet|teaspoons|teaspoon|tsp|ml)(?:$|[^a-z0-9])"
    )
    private val BEFORE = Regex("before (?:food|meals|meal|breakfast|lunch|dinner|eating)")
    private val AFTER = Regex("after (?:food|meals|meal|breakfast|lunch|dinner|eating)")
    private val WITH = Regex("with (?:food|meals|meal|milk|water)")

    private val ONCE = listOf("once daily", "once a day", "one time a day", "every day", "everyday", "daily", "od", "0d", "qd", "1 time a day")
    private val TWICE = listOf("twice daily", "twice a day", "two times a day", "two times daily", "2 times a day", "bd", "bid")
    private val THRICE = listOf("thrice daily", "thrice a day", "three times a day", "three times daily", "3 times a day", "tds", "tid")
    private val FOUR = listOf("four times a day", "four times daily", "4 times a day", "qid", "qds")
    private val WEEKLY = listOf("once weekly", "once a week", "weekly", "every week")
    private val PRN = listOf(
        "sos", "prn", "if needed", "if required", "when required", "when needed", "as needed", "as required",
        "as directed when needed"
    )
    private val NIGHT = listOf("hs", "qhs", "at night", "at bedtime", "nocte", "bedtime", "nightly")
    private val STAT = listOf("stat")

    private const val WORD_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789/"

    /** Lowercase, `×` → `x`, `½` → `1/2`, whitespace runs → one space, trimmed. */
    fun norm(s: String?): String {
        if (s == null) return ""
        val t = s.replace("×", "x").replace("½", "1/2").lowercase()
        return t.split(' ', '\t', '\n', '\r', '', '').filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** Whole-word phrase match on normalized text (word chars = `[a-z0-9/]`). */
    fun hasPhrase(text: String, phrase: String): Boolean {
        var start = 0
        while (true) {
            val i = text.indexOf(phrase, start)
            if (i < 0) return false
            val before = if (i > 0) text[i - 1] else ' '
            val end = i + phrase.length
            val after = if (end < text.length) text[end] else ' '
            if (before !in WORD_CHARS && after !in WORD_CHARS) return true
            start = i + 1
        }
    }

    private fun anyPhrase(text: String, phrases: List<String>): Boolean = phrases.any { hasPhrase(text, it) }

    private fun num(tok: String): Double {
        if ("/" in tok) {
            val (a, b) = tok.split("/", limit = 2)
            val da = a.toDoubleOrNull() ?: return 0.0
            val db = b.toDoubleOrNull() ?: return 0.0
            return if (db != 0.0) da / db else 0.0
        }
        return tok.toDoubleOrNull() ?: 0.0
    }

    /** (canonical form or null, mapped-from-another-word). */
    private fun mapForm(formText: String?): Pair<String?, Boolean> {
        val f = norm(formText).trimEnd('.')
        if (f.isEmpty()) return null to false
        for ((word, canon) in FORM_MAP) {
            if (f == word || f.startsWith(word)) return canon to (canon != word)
        }
        return "other" to true
    }

    fun fromRecordField(valueJson: JsonObject?): FrequencyDraft {
        val v = valueJson
        return fromValues(
            name = v?.str("name"), strength = v?.str("strength"), form = v?.str("form"), dose = v?.str("dose"),
            frequency = v?.str("frequency"), duration = v?.str("duration"), instructions = v?.str("instructions")
        )
    }

    fun fromValues(
        name: String?,
        strength: String?,
        form: String?,
        dose: String?,
        frequency: String?,
        duration: String?,
        instructions: String?
    ): FrequencyDraft {
        val notes = mutableListOf<String>()
        val nameOut = (name ?: "").trim()
        val strengthOut = (strength ?: "").trim().ifEmpty { null }
        val instructionsRaw = (instructions ?: "").trim().ifEmpty { null }
        val (mappedForm, mapped) = mapForm(form)
        val formOut: String
        if (mappedForm == null) {
            formOut = "other"
            notes += "form_defaulted"
        } else {
            formOut = mappedForm
            if (mapped) notes += "form_mapped"
        }
        val freq = norm(frequency)
        val instr = norm(instructions)
        val dur = norm(duration)
        val doseText = norm(dose)

        var isPrn = false
        var kind: String? = null
        var times: List<String> = emptyList()
        var days: List<Int> = emptyList()
        var interval: Int? = null
        var anchor: String? = null
        var quantity: Double? = null
        var explicit = false
        val combined = (freq + " " + instr).trim()

        TRIPLET.matchEntire(freq)?.let { m ->
            val parts = m.groupValues.drop(1).filter { it.isNotEmpty() }
            val nums = parts.map(::num)
            val table = if (nums.size == 4) MedicationConstants.SLOTS_4 else MedicationConstants.SLOTS_3
            times = nums.indices.filter { nums[it] > 0 }.map { table[it] }
            val nonzero = nums.filter { it > 0 }
            if (nonzero.isNotEmpty()) {
                explicit = true
                kind = "daily"
                quantity = nonzero[0]
                if (nonzero.toSet().size != 1) notes += "uneven_doses"
            }
        }
        if (!explicit) {
            when {
                anyPhrase(combined, PRN) -> { isPrn = true; explicit = true }
                anyPhrase(freq, FOUR) -> { kind = "daily"; times = MedicationConstants.SLOTS_4; explicit = true }
                anyPhrase(freq, THRICE) -> { kind = "daily"; times = MedicationConstants.SLOTS_3; explicit = true }
                anyPhrase(freq, TWICE) -> { kind = "daily"; times = MedicationConstants.SLOTS_2; explicit = true }
                EVERY_H.containsMatchIn(freq) || QNH.containsMatchIn(freq) || N_HOURLY.containsMatchIn(freq) -> {
                    val mm = EVERY_H.find(freq) ?: QNH.find(freq) ?: N_HOURLY.find(freq)!!
                    val n = mm.groupValues[1].toInt()
                    explicit = true
                    if (n in MedicationConstants.INTERVAL_HOURS) {
                        kind = "interval"; interval = n; anchor = MedicationConstants.INTERVAL_ANCHOR
                    } else {
                        kind = "daily"; times = MedicationConstants.SLOTS_3
                        notes += "interval_rounded"
                    }
                }
                anyPhrase(freq, WEEKLY) -> {
                    kind = "weekly"; days = MedicationConstants.WEEKLY_DEFAULT_DAYS; times = MedicationConstants.SLOTS_1; explicit = true
                    notes += "weekday_defaulted"
                }
                anyPhrase(freq, NIGHT) -> { kind = "daily"; times = MedicationConstants.SLOT_NIGHT; explicit = true }
                anyPhrase(freq, ONCE) -> { kind = "daily"; times = MedicationConstants.SLOTS_1; explicit = true }
                anyPhrase(freq, STAT) -> { kind = "daily"; times = MedicationConstants.SLOTS_1; explicit = true }
            }
        }
        var durationDays: Int? = null
        if (anyPhrase(freq, STAT) && !isPrn) durationDays = 1
        if (!explicit) {
            kind = "daily"; times = MedicationConstants.SLOTS_1
            notes += "frequency_defaulted"
        }
        if (kind == "daily" && times.size == 1 && !anyPhrase(freq, NIGHT) && anyPhrase(instr, NIGHT)) times = MedicationConstants.SLOT_NIGHT
        if (!isPrn && kind != "interval") notes += "time_defaulted"

        val md = DURATION.find(dur)
        if (md != null && durationDays == null) {
            val n = md.groupValues[1].toInt()
            durationDays = when (md.groupValues[2][0]) {
                'd' -> n
                'w' -> n * 7
                else -> n * 30
            }
        }

        var doseUnit: String? = null
        val mdose = DOSE.find(doseText) ?: (if (formOut == "syrup") DOSE.find(norm(strength)) else null)
        if (mdose != null) {
            var q = num(mdose.groupValues[1])
            val u = mdose.groupValues[2]
            doseUnit = when {
                u.startsWith("tab") -> "tablet"
                u.startsWith("cap") -> "capsule"
                u.startsWith("puff") -> "puff"
                u.startsWith("drop") -> "drop"
                u.startsWith("sachet") -> "sachet"
                u == "tsp" || u == "teaspoon" || u == "teaspoons" -> { q *= 5; "ml" }
                else -> "ml"
            }
            quantity = q
        }
        if (doseUnit == null) doseUnit = DEFAULT_UNIT_FOR_FORM[formOut] ?: "unit"
        if (quantity == null) {
            if (formOut == "syrup") {
                notes += "dose_required"
            } else {
                quantity = 1.0
                notes += "dose_defaulted"
            }
        }

        val food = when {
            hasPhrase(instr, "empty stomach") || BEFORE.containsMatchIn(instr) -> "before"
            AFTER.containsMatchIn(instr) -> "after"
            WITH.containsMatchIn(instr) -> "with"
            else -> "anytime"
        }
        val confidence = when {
            isPrn -> 0.9
            !explicit -> 0.3
            notes.any { it == "interval_rounded" || it == "weekday_defaulted" || it == "uneven_doses" } -> 0.6
            else -> 0.9
        }
        return FrequencyDraft(
            name = nameOut,
            strength = strengthOut,
            form = MedicationForm.fromRaw(formOut),
            doseQuantity = quantity,
            doseUnit = DoseUnit.fromRaw(doseUnit),
            isPrn = isPrn,
            frequency = if (isPrn) null else ScheduleFrequency.fromRaw(kind),
            times = if (isPrn) emptyList() else times,
            days = if (isPrn) emptyList() else days,
            intervalHours = if (isPrn) null else interval,
            anchorTime = if (isPrn) null else anchor,
            durationDays = durationDays,
            foodRelation = FoodRelation.fromRaw(food),
            instructions = instructionsRaw,
            confidence = confidence,
            notes = notes.toList()
        )
    }

    /** The reference's output object, for the vector tests and the iOS-parity fixtures. */
    fun toJson(d: FrequencyDraft): JsonObject = MedicationJson.obj(
        "name" to d.name, "strength" to d.strength, "form" to d.form.raw, "dose_quantity" to d.doseQuantity,
        "dose_unit" to d.doseUnit.raw, "is_prn" to d.isPrn, "frequency_kind" to d.frequency?.raw,
        "times" to d.times, "days" to d.days, "interval_hours" to d.intervalHours, "anchor_time" to d.anchorTime,
        "duration_days" to d.durationDays, "food_relation" to d.foodRelation.raw, "instructions" to d.instructions,
        "confidence" to d.confidence, "notes" to d.notes
    )
}
