import Foundation

// Heart: per-day heart metrics from minute heart rate, HRmax, Uth VO2max and resting-heart-rate strain. Port of the
// "Heart" section of `scripts/derived_reference.py`.

nonisolated enum HeartDerivation {
    struct Night: Sendable {
        var startMs: Int64
        var endMs: Int64
    }

    struct DayInput: Sendable {
        var timeZone: String
        var day: String
        var sex: String?
        var hr: DerivedMinuteSeries?
        var steps: DerivedMinuteSeries?
        /// The main night whose wake day is `day`.
        var night: Night?
        var hrMax: Double
        /// Resting heart rate used for zones and TRIMP.
        var rhrRef: Double?
    }

    struct DayResult: DerivedOutput {
        var restingHr: Double?
        var sleepingHr: Double?
        var lowestHr: Double?
        var dipPct: Double?
        var sedentaryHr: Double?
        var dayAvg: Double?
        var dayMin: Double?
        var dayMax: Double?
        var observedMax: Double?
        var wearMinutes = 0
        var completenessPct = 0.0
        var validDay = false
        var zoneMethod: String?
        var lightMin = 0
        var moderateMin = 0
        var vigorousMin = 0
        var maxMin = 0
        var moderateEquivalent = 0
        var trimp: Double?
        var walkingHr: Double?
        var rhrStatus = "no_night"

        var jsonObject: [String: Any] {
            let j = DerivedMath.json
            return ["resting_hr": j(restingHr), "sleeping_hr": j(sleepingHr), "lowest_hr": j(lowestHr),
                    "dip_pct": j(dipPct), "sedentary_hr": j(sedentaryHr), "day_avg": j(dayAvg), "day_min": j(dayMin),
                    "day_max": j(dayMax), "observed_max": j(observedMax), "wear_minutes": wearMinutes,
                    "completeness_pct": completenessPct, "valid_day": validDay, "zone_method": j(zoneMethod),
                    "light_min": lightMin, "moderate_min": moderateMin, "vigorous_min": vigorousMin, "max_min": maxMin,
                    "moderate_equivalent": moderateEquivalent, "trimp": j(trimp), "walking_hr": j(walkingHr),
                    "rhr_status": rhrStatus]
        }
    }

    /// 0 below light, 1 light, 2 moderate, 3 vigorous, 4 near-maximal. %HRR when rhr is known, else %HRmax.
    static func hrZone(_ hr: Double, hrMax: Double, rhr: Double?, _ th: DerivedConfig.Thresholds) -> Int {
        let x: Double
        let cuts: [Double]
        if let rhr, hrMax > rhr {
            x = (hr - rhr) / (hrMax - rhr)
            cuts = th.hrrZones
        } else {
            x = hr / hrMax
            cuts = th.hrmaxZones
        }
        var z = 0
        for c in cuts where x >= c { z += 1 }
        return z
    }

    static func heartDay(_ inp: DayInput, config: DerivedConfig) -> DayResult {
        let th = config.thresholds
        let minute = DerivedMath.minute
        let tz = DerivedDay.timeZone(inp.timeZone)
        let hr = DerivedMath.minuteMap(inp.hr, lo: th.hrValidMin, hi: th.hrValidMax)
        let steps: [Int64: Double]? = inp.steps.map { DerivedMath.minuteMap($0) }
        let d0 = DerivedDay.dayStartMs(inp.day, tz)
        let d1 = DerivedDay.dayStartMs(DerivedDay.addDays(inp.day, 1), tz)
        let night = inp.night
        let sortedTimes = hr.keys.sorted()
        var out = DayResult()

        func inNight(_ t: Int64) -> Bool {
            guard let night else { return false }
            return night.startMs <= t && t < night.endMs
        }

        // night
        if let night {
            let window = sortedTimes.filter { night.startMs <= $0 && $0 < night.endMs }
            let spanMin = DerivedMath.floorDiv(night.endMs - night.startMs, minute)
            let coverage = spanMin > 0 ? Double(window.count) / Double(spanMin) : 0.0
            if Double(spanMin) < th.rhrMinSleepMin {
                out.rhrStatus = "short_night"
            } else if coverage < th.rhrMinCoverage {
                out.rhrStatus = "low_coverage"
            } else {
                let n = th.rhrWindowMin
                var best: Double?
                for t in window {
                    var vals: [Double]? = []
                    for k in 0..<n {
                        let tk = t + Int64(k) * minute
                        guard let v = hr[tk], tk < night.endMs else {
                            vals = nil
                            break
                        }
                        vals!.append(v)
                    }
                    if let vals {
                        let m = DerivedMath.mean(vals)
                        if best == nil || m < best! { best = m }
                    }
                }
                out.restingHr = DerivedMath.roundTo(best, 1)
                out.rhrStatus = best != nil ? "ok" : "low_coverage"
            }
            if !window.isEmpty {
                let vals = window.map { hr[$0]! }
                out.sleepingHr = DerivedMath.roundTo(DerivedMath.mean(vals), 1)
                out.lowestHr = DerivedMath.roundTo(vals.min()!, 1)
                let wakeEnd = night.endMs + Int64(th.wakeWindowHours * 3_600_000)
                let wake = sortedTimes.filter { night.endMs <= $0 && $0 < wakeEnd }.map { hr[$0]! }
                if wake.count >= th.dipMinWakeMin && out.rhrStatus == "ok" {
                    out.dipPct = DerivedMath.roundTo((1.0 - DerivedMath.mean(vals) / DerivedMath.mean(wake)) * 100.0, 1)
                }
            }
        }

        let dayMinutes = sortedTimes.filter { d0 <= $0 && $0 < d1 }
        out.wearMinutes = dayMinutes.count
        out.completenessPct = DerivedMath.roundTo(
            Double(dayMinutes.count) * 100.0 / Double(DerivedMath.floorDiv(d1 - d0, minute)), 1)
        out.validDay = dayMinutes.count >= th.validWearMin

        // daytime
        let awake = dayMinutes.filter { !inNight($0) }.map { hr[$0]! }
        if awake.count >= th.daytimeMinMinutes {
            out.dayAvg = DerivedMath.roundTo(DerivedMath.mean(awake), 1)
            out.dayMin = DerivedMath.roundTo(awake.min()!, 1)
            out.dayMax = DerivedMath.roundTo(awake.max()!, 1)
        }

        // observed max: highest 2-minute mean
        var best2: Double?
        for t in dayMinutes {
            if let v2 = hr[t + minute] {
                let m = (hr[t]! + v2) / 2.0
                if best2 == nil || m > best2! { best2 = m }
            }
        }
        out.observedMax = DerivedMath.roundTo(best2, 1)

        // sedentary daytime
        if let steps {
            var sed: [Double] = []
            for t in dayMinutes {
                let hour = DerivedDay.localTime(t, tz).hour
                if !(th.sedentaryStartHour <= hour && hour < th.sedentaryEndHour) || inNight(t) { continue }
                var still = true
                for k in 0...th.sedentaryStillMin where (steps[t - Int64(k) * minute] ?? 0.0) > 0 {
                    still = false
                    break
                }
                if still { sed.append(hr[t]!) }
            }
            if sed.count >= th.sedentaryMinMinutes {
                out.sedentaryHr = DerivedMath.roundTo(DerivedMath.mean(sed), 1)
            }

            // walking heart rate: runs of >= walking_run_min minutes within the walking cadence band
            var walk: [Double] = []
            var run: [Int64] = []
            var t = d0
            while t < d1 {
                let s = steps[t] ?? 0.0
                if th.walkingMinSteps <= s && s <= th.walkingMaxSteps {
                    run.append(t)
                } else {
                    if run.count >= th.walkingRunMin { walk.append(contentsOf: run.compactMap { hr[$0] }) }
                    run = []
                }
                t += minute
            }
            if run.count >= th.walkingRunMin { walk.append(contentsOf: run.compactMap { hr[$0] }) }
            if walk.count >= th.walkingMinMinutes {
                out.walkingHr = DerivedMath.roundTo(DerivedMath.mean(walk), 1)
            }
        }

        // zones and TRIMP
        let hrMax = inp.hrMax
        let rhr = inp.rhrRef
        let hrr = rhr != nil && hrMax > rhr!
        out.zoneMethod = hrr ? "hrr" : "hrmax"
        var counts = [0, 0, 0, 0, 0]
        var trimp = 0.0
        let k = DerivedConfig.Thresholds.bySex(th.trimpK, inp.sex)
        let a = DerivedConfig.Thresholds.bySex(th.trimpA, inp.sex)
        for t in dayMinutes {
            let v = hr[t]!
            counts[hrZone(v, hrMax: hrMax, rhr: rhr, th)] += 1
            if hrr {
                let x = (v - rhr!) / (hrMax - rhr!)
                if x >= th.trimpMinHrr { trimp += x * a * exp(k * x) }
            }
        }
        out.lightMin = counts[1]
        out.moderateMin = counts[2]
        out.vigorousMin = counts[3]
        out.maxMin = counts[4]
        out.moderateEquivalent = counts[2] + 2 * (counts[3] + counts[4])
        out.trimp = hrr ? DerivedMath.roundTo(trimp, 1) : nil
        return out
    }

    // MARK: HRmax

    struct HrMaxResult: DerivedOutput {
        var hrMax: Double
        var method: String
        var hrReserve: Double?

        var jsonObject: [String: Any] {
            ["hr_max": hrMax, "method": method, "hr_reserve": DerivedMath.json(hrReserve)]
        }
    }

    /// `observed`: daily observed maxima from the lookback window.
    static func hrMax(age: Double, observed: [Double?], rhr: Double?, config: DerivedConfig) -> HrMaxResult {
        let th = config.thresholds
        let tanaka = th.tanaka[0] - th.tanaka[1] * age
        let obs = observed.compactMap { $0 }
        let top = obs.max()
        let value: Double
        let method: String
        if let top, top > tanaka {
            (value, method) = (top, "observed")
        } else {
            (value, method) = (tanaka, "tanaka")
        }
        return HrMaxResult(hrMax: DerivedMath.roundTo(value, 1), method: method,
                           hrReserve: rhr.map { DerivedMath.roundTo(value - $0, 1) })
    }

    // MARK: VO2max (Uth)

    struct Vo2MaxResult: DerivedOutput {
        var vo2max: Double?
        var confidence: String?

        var jsonObject: [String: Any] {
            ["vo2max": DerivedMath.json(vo2max), "confidence": DerivedMath.json(confidence)]
        }
    }

    static func vo2maxUth(hrMax: Double?, hrMaxMethod: String?, rhr: Double?, config: DerivedConfig) -> Vo2MaxResult {
        guard let rhr, rhr > 0, let hrMax else { return Vo2MaxResult() }
        let v = config.thresholds.uthFactor * hrMax / rhr
        return Vo2MaxResult(vo2max: DerivedMath.roundTo(v, 1), confidence: hrMaxMethod == "observed" ? "medium" : "low")
    }

    // MARK: Resting-heart-rate strain

    struct StrainResult: DerivedOutput {
        var status: String
        var baseline: Double?
        var deviation: Double?
        var z: Double?
        var flag: Bool

        var jsonObject: [String: Any] {
            let j = DerivedMath.json
            return ["status": status, "baseline": j(baseline), "deviation": j(deviation), "z": j(z), "flag": flag]
        }
    }

    /// `series`: {day: resting hr}.
    static func rhrStrain(series s: [String: Double], day: String, config: DerivedConfig) -> StrainResult {
        let th = config.thresholds

        func base(_ asOf: String) -> (mean: Double, sd: Double)? {
            var vals: [Double] = []
            for i in stride(from: th.strainWindowDays, to: 0, by: -1) {
                if let v = s[DerivedDay.addDays(asOf, -i)] { vals.append(v) }
            }
            if vals.count < th.strainMinDays { return nil }
            let m = DerivedMath.mean(vals)
            return (m, max(DerivedMath.sampleSD(vals, m), th.strainSdFloor))
        }

        guard let today = s[day], let b = base(day) else {
            return StrainResult(status: "insufficient", flag: false)
        }
        let dev = today - b.mean
        let y = s[DerivedDay.addDays(day, -1)]
        let yb = base(DerivedDay.addDays(day, -1))
        var flag = false
        if dev >= th.strainDeltaBpm, let y, let yb, y - yb.mean >= th.strainDeltaBpm { flag = true }
        return StrainResult(status: "ok", baseline: DerivedMath.roundTo(b.mean, 1), deviation: DerivedMath.roundTo(dev, 1),
                            z: DerivedMath.roundTo(dev / b.sd, 2), flag: flag)
    }
}
