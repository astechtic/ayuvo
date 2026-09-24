import Foundation

/// The gallery's text, keyed by catalog id (docs/coach.md §9).
///
/// The ids and the English source live in `shared/coach/prompt_gallery.json`; this table only
/// carries each one into the string catalog, so a card, a chip and a translation can never drift
/// apart. `PromptGalleryTextTests` compares this table with the catalog.
enum PromptGalleryText {
    static func title(_ id: String) -> String? {
        switch id {
        case "diet_chart_from_health": return String(localized: "Make a diet chart from my health data")
        case "macro_balance": return String(localized: "Are my macros balanced?")
        case "dinner_tonight": return String(localized: "What should I eat for dinner?")
        case "protein_sources": return String(localized: "Help me hit my protein target")
        case "week_review": return String(localized: "Review my week of eating")
        case "improve_sleep_cycle": return String(localized: "How do I improve my sleep cycle?")
        case "sleep_week": return String(localized: "How did I sleep this week?")
        case "sleep_consistency": return String(localized: "Is my sleep schedule consistent?")
        case "sleep_and_resting_hr": return String(localized: "Sleep and my resting heart rate")
        case "training_review": return String(localized: "Analyze my last 4 weeks of training")
        case "moving_enough": return String(localized: "Am I moving enough?")
        case "lift_progress": return String(localized: "How are my lifts progressing?")
        case "recovery_check": return String(localized: "Am I recovering between sessions?")
        case "explain_latest_report": return String(localized: "Explain my latest blood report")
        case "find_abnormal": return String(localized: "Find abnormal values in my reports")
        case "compare_reports": return String(localized: "Compare my last two reports")
        case "analyte_trend": return String(localized: "Chart my lab values over time")
        case "questions_for_doctor": return String(localized: "What should I ask my doctor?")
        case "adherence_check": return String(localized: "Am I taking my medicines on time?")
        case "medication_schedule": return String(localized: "Explain my current schedule")
        case "missed_dose_pattern": return String(localized: "Which doses do I keep missing?")
        case "reach_my_goal": return String(localized: "When will I reach my goal?")
        case "month_over_month": return String(localized: "Compare this month with last")
        case "one_thing_to_change": return String(localized: "One thing to change this month")
        case "whole_picture": return String(localized: "Give me the whole picture")
        default: return nil
        }
    }

    static func prompt(_ id: String) -> String? {
        switch id {
        case "diet_chart_from_health": return String(localized: "Make me a diet chart using my health data. Use what you know about my intake, weight trend and activity, and explain why each meal is there.")
        case "macro_balance": return String(localized: "How balanced are my macros over the last two weeks? Chart protein, carbs and fat and tell me what to change.")
        case "dinner_tonight": return String(localized: "Based on what I have already eaten today, what should I have for dinner to stay on target?")
        case "protein_sources": return String(localized: "I want more protein without many more calories. Look at what I usually eat and suggest realistic swaps.")
        case "week_review": return String(localized: "Review my last week of eating. What went well, what slipped, and what is the one thing to fix next week?")
        case "improve_sleep_cycle": return String(localized: "How do I improve my sleep cycle? Look at my bedtimes, wake times and sleep stages, chart the pattern, and give me a plan I can actually keep.")
        case "sleep_week": return String(localized: "How did I sleep this week? Chart it night by night and tell me whether it is enough.")
        case "sleep_consistency": return String(localized: "Is my sleep schedule consistent? Show me how much my bedtime moves around and what that is costing me.")
        case "sleep_and_resting_hr": return String(localized: "Does my resting heart rate change with how I sleep? Compare the two over the last month.")
        case "training_review": return String(localized: "Analyze my last four weeks of training. Where am I progressing, where have I stalled, and what should next week look like?")
        case "moving_enough": return String(localized: "Am I moving enough? Chart my steps and active energy for the last month against what would be a good target for me.")
        case "lift_progress": return String(localized: "Chart how my main lifts have moved over the last three months and tell me what to push next.")
        case "recovery_check": return String(localized: "Am I recovering well between sessions? Look at my training load alongside my sleep and resting heart rate.")
        case "explain_latest_report": return String(localized: "Explain my latest blood report: what stands out, what is normal, and what should I ask my doctor?")
        case "find_abnormal": return String(localized: "Find any abnormal values across my health records and tell me which ones are worth asking about.")
        case "compare_reports": return String(localized: "Compare my latest report with the previous one. What changed, and does the direction matter?")
        case "analyte_trend": return String(localized: "Chart how my key lab values have changed over time and explain the trend in plain language.")
        case "questions_for_doctor": return String(localized: "Based on my records, what should I ask at my next appointment? Keep it to a short list I can read out.")
        case "adherence_check": return String(localized: "Am I taking my medicines on time? Show my adherence for the last month and when I most often miss a dose.")
        case "medication_schedule": return String(localized: "Walk me through my current medicines and when I am meant to take each one.")
        case "missed_dose_pattern": return String(localized: "Which doses do I miss most often, and what would make them easier to remember?")
        case "reach_my_goal": return String(localized: "At my current rate, when do I reach my goal weight? Chart the projection and tell me what would move it.")
        case "month_over_month": return String(localized: "Compare this month with last month across everything you can see, and chart what changed most.")
        case "one_thing_to_change": return String(localized: "If I could only change one habit this month, what does my data say it should be?")
        case "whole_picture": return String(localized: "Put my whole picture together: intake, movement, sleep, labs and medicines. What is the headline?")
        default: return nil
        }
    }

    static func category(_ name: String) -> String? {
        switch name {
        case "nutrition": return String(localized: "Nutrition")
        case "sleep": return String(localized: "Sleep & recovery")
        case "training": return String(localized: "Training")
        case "labs": return String(localized: "Labs & reports")
        case "medications": return String(localized: "Medications")
        case "planning": return String(localized: "Trends & planning")
        default: return nil
        }
    }
}
