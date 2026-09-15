import Foundation

// §12 fields and §14 boundaries (port of `scripts/records_reference.py`).
extension RR {
    static let qualifications: Set<String> = ["mbbs", "md", "ms", "dm", "mch", "dnb", "mrcp", "frcs", "bds", "mds", "dgo", "dch",
                                              "facc", "fics", "mrcog", "frcp", "dmrd", "dmrt", "phd", "bams", "bhms", "mph", "do"]
    static let nameCut: Set<String> = ["consultant", "reg", "regd", "registration", "mci", "mobile", "ph", "phone", "timings",
                                       "sr", "senior", "junior", "head", "hod", "prof", "professor", "associate", "assistant",
                                       "department", "dept", "hospital", "clinic", "laboratories", "laboratory", "diagnostics",
                                       "labs", "centre", "center", "pathology", "radiology", "signature", "signed", "date",
                                       "the", "and", "for", "on", "at", "in", "of", "with"]
    static let reDrPrefix = Rx(#"(?<![A-Za-z])(?:Dr|DR|dr)(?:\.[ ]?|[ ])"#)
    static let reCapName = Rx(#"[A-Z][A-Za-z'.-]*(?: [A-Z][A-Za-z'.-]*){0,5}"#)
    static let reDoctorLabel = Rx(#"(?<![a-z])(consultant|consulting doctor|treating doctor|attending doctor|doctor|physician|surgeon|referred by|referring doctor|ref\.? ?by|ref\.? doctor|ref\.? dr\.?)[ ]?[:\-][ ]?(?:dr\.?[ ]?)?"#)
    static let reSpecialty = Rx(#"(?<![a-z])(m\.?b\.?b\.?s\.?|m\.?d\.?|m\.?s\.?|d\.?m\.?|m\.?ch\.?|d\.?n\.?b\.?|mrcp|frcs|b\.?d\.?s\.?|m\.?d\.?s\.?|dgo|dch|facc|mrcog|frcp|dmrd|cardiologist|physician|pediatrician|paediatrician|gyn(?:a)?ecologist|obstetrician|orthop(?:a)?edic(?: surgeon)?|dermatologist|neurologist|endocrinologist|pathologist|radiologist|general medicine|internal medicine|diabetologist|nephrologist|pulmonologist|gastroenterologist|psychiatrist|ophthalmologist|oncologist|urologist|surgeon)(?![a-z])"#)
    static let reFacility = Rx(#"(?<![a-z])(?:hospitals?|clinics?|medical cent(?:er|re)|health cent(?:er|re)|diagnostic cent(?:er|re)|diagnostics|laborator(?:y|ies)|labs|path ?labs|pathology|imaging|scans|nursing home|polyclinic|healthcare|health care|institute|medical college|pharmacy|chemists?|medicos|medicals)(?![a-z])"#)
    static let reFacilitySkip = Rx(#"(?<![a-z])(?:report|department|dept|referred|ref|consultant|patient|name|test|result|reference|range|sample|specimen|collected|date|dr)(?![a-z])"#)
    static let reDeptOf = Rx(#"(?<![a-z])(?:department|dept\.?) of ([a-z][a-z&]*(?: [a-z&]+){0,3})(?![a-z])"#)
    static let reXDept = Rx(#"(?:^|[,;:|(-] ?)([a-z][a-z&]*(?: [a-z&]+){0,3}) department(?![a-z])"#)
    static let rePatientStrong = Rx(#"(?<![a-z])(?:patient'?s? name|name of (?:the )?patient|pt\.? name)[ ]?[:\-][ ]?"#)
    static let rePatientWeak = Rx(#"(?<![a-z])(?:patient|name)[ ]?[:\-][ ]?"#)
    static let reTitle = Rx(#"(?<![a-z])(?:mr|mrs|ms|miss|master|mstr|baby|smt|shri|sri|kumari|kum)\.?[ ]"#)
    static let reTitleAt = Rx(#"(?:mr|mrs|ms|miss|master|mstr|baby|smt|shri|sri|kumari|kum)\.?[ ]"#)
    static let rePersonWords = Rx(#"[a-z][a-z.'-]*(?:,? [a-z][a-z.'-]*){0,5}"#)
    static let patientCut: Set<String> = ["age", "sex", "gender", "uhid", "mrn", "id", "dob", "ref", "date", "reg", "no", "ip", "op",
                                          "lab", "sample", "y", "yr", "yrs", "years", "m", "f", "male", "female", "bed", "ward",
                                          "phone", "mobile", "d", "o", "b"]
    static let rePatientContext = Rx(#"(?<![a-z])(?:age|sex|gender|uhid|mrn|yrs?|years?|y/o)(?![a-z])|[0-9]{1,3} ?y(?:rs?)?(?: ?/ ?| )(?:m|f)(?![a-z])|[0-9]{1,3} ?/ ?(?:m|f)(?![a-z])"#)
    static let reAgeLabel = Rx(#"(?<![a-z])age(?: ?/ ?(?:sex|gender))?[ ]?[:\-]?[ ]?([0-9]{1,3})(?:[ ]?(years?|yrs?|y|months?|mths?|mos?|days?))?(?![a-z0-9])"#)
    static let reAgeSlash = Rx(#"(?<![0-9a-z])([0-9]{1,3})[ ]?(?:y|yrs?|years?)?[ ]?[/,|][ ]?(m|f|male|female)(?![a-z])"#)
    static let reAgeYears = Rx(#"(?<![0-9a-z])([0-9]{1,3})[ ]?(years?|yrs?)(?: old)?(?![a-z])"#)
    static let reSexLabel = Rx(#"(?<![a-z])(?:sex|gender)[ ]?[:\-]?[ ]?(?:[0-9]{1,3}[ ]?(?:y|yrs?|years?)?[ ]?/[ ]?)?(male|female|other|transgender|m|f)(?![a-z])"#)
    static let reReportWord = Rx(#"(?<![a-z])report(?![a-z])"#)
    static let rePanelWord = Rx(#"(?<![a-z])(?:panel|profile|function tests?)(?![a-z])"#)
    static let reLeadingLabel = Rx(#"[a-z][a-z ]{0,24}:[ ]?"#)

    static let sectionLabels: [(String?, Rx)] = [
        ("diagnosis", Rx(#"(?:(?:provisional|final|clinical|working|discharge|differential) )?diagnos[ie]s(?: on discharge)?|impression|dx"#)),
        ("symptom", Rx(#"(?:(?:chief|presenting) )?complaints?|c/o|presenting symptoms?|symptoms?"#)),
        ("procedure", Rx(#"(?:procedures?|operations?|surgery)(?: (?:done|performed))?"#)),
        ("recommendation", Rx(#"advice(?: on discharge)?|discharge advice|advised|recommendations?|plan(?: of care)?|instructions|suggested|suggestions?|follow[ -]?up advice"#)),
        (nil, Rx(#"history(?: of present illness)?|hpi|past (?:medical )?history|examination|on examination|o/e|vitals|investigations?|medications?|discharge medications?|treatment(?: given)?|rx|course in (?:the )?hospital|hospital course|condition (?:at|on) discharge|findings|technique|conclusion|interpretation|clinical (?:history|indication)|allergies|comments?|notes?|remarks?|signature|medicines"#)),
    ]
    static let reBullet = Rx(#"(?:[0-9]{1,2}[.)][ ]?|[-•*·>][ ]?)"#)
    static let reGenericLabel = Rx(#"[a-z][a-z0-9 /().&'-]{0,30}[ ]?:"#)
    static let reSignature = Rx(#"^(?:dr\.? |\(dr)|(?<![a-z])(?:signature|signed|radiologist|pathologist|end of report|authori[sz]ed signatory)(?![a-z])"#)
    static let sectionLimit: [String: Int] = ["diagnosis": 120, "symptom": 80, "procedure": 120, "recommendation": 160]
    static let sectionConf: [String: Double] = ["diagnosis": 0.75, "symptom": 0.7, "procedure": 0.7, "recommendation": 0.7]
    static let reStandaloneRec = Rx(#"(?:repeat|review|follow[ -]?up|consult) "#)

    static let medIndex = #"(?:[0-9]{1,2}[.)][ ]?|[-•*·>][ ]?|rx[ :.]+)?"#
    static let reMedForm = Rx("^" + medIndex + #"(tablets?|tabs?|capsules?|caps?|syrup|syp|syr|injection|inj|ointment|oint|cream|gel|drops?|inhaler|sachets?|suspension|susp|lotion|spray|powder|solution|soln|nebuli[sz]ation|neb|respules?)(?:\.[ ]?|[ ]+)"#)
    static let formCanon: [(String, String)] = [("tab", "tablet"), ("cap", "capsule"), ("sy", "syrup"), ("inj", "injection"), ("oint", "ointment"),
                                                ("cream", "cream"), ("gel", "gel"), ("drop", "drops"), ("inhaler", "inhaler"), ("sachet", "sachet"),
                                                ("susp", "suspension"), ("lotion", "lotion"), ("spray", "spray"), ("powder", "powder"),
                                                ("sol", "solution"), ("neb", "nebulisation"), ("respule", "respules")]
    static let reMedFallback = Rx("^" + medIndex + #"([a-z][a-z0-9'-]*(?: [a-z][a-z0-9'-]*){0,3}) "#)
    static let reStrength = Rx(#"(?<![a-z0-9.])[0-9]+(?:\.[0-9]+)?(?:[ ]?[/+][ ]?[0-9]+(?:\.[0-9]+)?)*[ ]?(?:mg|mcg|μg|ug|gm|g|ml|iu|units?|meq|%)(?:[ ]?/[ ]?[0-9]*(?:\.[0-9]+)?[ ]?(?:ml|g))?(?![a-z0-9])"#)
    static let reFreq = Rx(#"(?<![a-z0-9/⁄])(?:[0-9](?:[/⁄][0-9])?[ ]?-[ ]?[0-9](?:[/⁄][0-9])?[ ]?-[ ]?[0-9](?:[/⁄][0-9])?(?:[ ]?-[ ]?[0-9](?:[/⁄][0-9])?)?|[o0]d|bd|bid|tds|tid|qid|qds|hs|qhs|sos|prn|stat|qd|once daily|twice daily|thrice daily|once a day|twice a day|thrice a day|three times a day|four times a day|once weekly|weekly|daily|at night|every [0-9]{1,2} ?(?:hours|hrs|h)|q[0-9]{1,2}h)(?![a-z0-9/⁄])"#)
    static let reDuration = Rx(#"(?:(?<![a-z])[x×*][ ]?[0-9]{1,3}[ ]?(?:days?|d|weeks?|wks?|w|months?|mths?|m)|(?<![a-z0-9])for [0-9]{1,3} ?(?:days?|weeks?|wks?|months?|mths?)|(?<![a-z0-9])[0-9]{1,3} ?(?:days|weeks|months))(?![a-z])"#)
    static let reInstr = Rx(#"(?<![a-z])(?:after food|before food|after meals?|before meals?|with food|with meals?|on empty stomach|empty stomach|at bedtime|bedtime|after breakfast|before breakfast|after lunch|after dinner|before dinner|with milk|with water|apply locally|local application|if needed|when required|as needed|if required)(?![a-z])"#)
    static let reDose = Rx(#"(?<![a-z0-9.])(?:[0-9]+(?:\.[0-9]+)?|1⁄2)[ ]?(?:tabs?|tablets?|caps?|capsules?|puffs?|drops?|sachets?|tsp|teaspoons?)(?![a-z])"#)
    static let medTypes: Set<String> = ["prescription", "discharge_summary", "medication_list", "consultation_note"]
    static let reNotAZ = Rx("[^a-z]")
    static let reLetterAny = Rx("[A-Za-z]")

    enum SectionLabel {
        case label(section: String?, inline: Int?)
    }

    static func sectionLabel(_ f: String) -> SectionLabel? {
        let off = reBullet.match(f)?.end ?? 0
        let fu = Array(f.utf16)
        for (section, rx) in sectionLabels {
            guard let m = rx.match(f.rSub(off)) else { continue }
            let e = off + m.end
            if e < fu.count, fu[e] >= 97, fu[e] <= 122 { continue }
            let rest = f.rSub(e)
            let rs = rest.rLStrip(" ")
            if rs.isEmpty || rs == ":" || rs == "-" || rs == "–" { return .label(section: section, inline: nil) }
            if ":-–".contains(rs.rChar(0)) {
                var inline = e + (rest.rLen - rs.rLen) + 1
                while inline < fu.count, fu[inline] == 32 { inline += 1 }
                return .label(section: section, inline: inline < fu.count ? inline : nil)
            }
            if section == "symptom", f.rSub(off, e) == "c/o", rest.hasPrefix(" ") {
                return .label(section: section, inline: e + 1)
            }
        }
        return nil
    }

    static func sectionOf(_ label: SectionLabel?) -> (isLabel: Bool, section: String?, inline: Int?) {
        guard case .label(let section, let inline)? = label else { return (false, nil, nil) }
        return (true, section, inline)
    }

    static func isStopLine(_ f: String) -> Bool {
        if f.isEmpty { return true }
        if sectionLabel(f) != nil { return true }
        if reGenericLabel.match(f) != nil { return true }
        if reSignature.search(f) != nil { return true }
        return false
    }

    static func splitItems(_ text: String, _ section: String) -> [(Int, Int)] {
        let seps: [UInt16] = ["diagnosis", "procedure", "recommendation"].contains(section) ? [59] : [44, 59]
        let units = Array(text.utf16)
        var out: [(Int, Int)] = []
        var start = 0
        for i in 0...units.count where i == units.count || seps.contains(units[i]) {
            out.append((start, i))
            start = i + 1
        }
        return out
    }

    static func cleanSpan(_ p: String, _ s0: Int, _ e0: Int) -> (Int, Int) {
        let pu = Array(p.utf16)
        var s = s0, e = e0
        while s < e, pu[s] == 32 { s += 1 }
        if let b = reBullet.match(p.rSub(s, e)) { s += b.end }
        let trail = Set(" .,;:-–".utf16)
        while s < e, trail.contains(pu[e - 1]) { e -= 1 }
        while s < e, pu[s] == 32 { s += 1 }
        return (s, e)
    }

    static func field(_ key: String, _ value: String, _ vj: RJ, _ conf: Double, _ ln: Line, _ start: Int = 0) -> Item {
        Item(key: key, valueText: value, valueJSON: vj, confidence: conf, pos: Pos(page: ln.page, line: ln.index, col: start), line: ln)
    }

    static func sections(_ linesByPage: [[Line]], _ recordType: String) -> [Item] {
        var items: [Item] = []
        for lines in linesByPage {
            var i = 0
            while i < lines.count {
                let ln = lines[i]
                let lab: (isLabel: Bool, section: String?, inline: Int?) = ln.f.isEmpty ? (false, nil, nil) : sectionOf(sectionLabel(ln.f))
                if !lab.isLabel || lab.section == nil {
                    if !ln.f.isEmpty, !["lab_report", "bill", "insurance"].contains(recordType),
                       reStandaloneRec.match(ln.f) != nil, ln.p.rLen <= 160 {
                        items.append(field("recommendation", ln.p.rRStrip(" .;,"), .null, 0.7, ln))
                    }
                    i += 1
                    continue
                }
                let section = lab.section!
                var spans: [(Line, Int)] = []
                if let inline = lab.inline { spans.append((ln, inline)) }
                var j = i + 1
                if lab.inline == nil {
                    while j < lines.count, lines[j].f.isEmpty { j += 1 }
                }
                while j < lines.count, spans.count < 10 {
                    let nl = lines[j]
                    if isStopLine(nl.f) { break }
                    spans.append((nl, 0))
                    j += 1
                }
                for (sl, st) in spans {
                    if section == "recommendation", reMedForm.match(sl.f) != nil { continue }
                    for (s0, e0) in splitItems(sl.p.rSub(st), section) {
                        let (s, e) = cleanSpan(sl.p, st + s0, st + e0)
                        if e - s < 2 || e - s > sectionLimit[section]! || reAZ1.search(sl.f.rSub(s, e)) == nil { continue }
                        items.append(field(section, sl.p.rSub(s, e), .null, sectionConf[section]!, sl, s))
                    }
                }
                i = j
            }
        }
        return items
    }

    static func cutName(_ pName: String, _ cutWords: Set<String>) -> String? {
        var kept: [String] = []
        for tk in pName.rSplitSpace {
            let bare = reNotAZ.sub(fold(tk), "")
            if cutWords.contains(bare) || (qualifications.contains(bare) && tk.uppercased() == tk && bare.rLen > 1) { break }
            kept.append(tk)
        }
        while let last = kept.last, last.rRStrip(".-'").isEmpty { kept.removeLast() }
        let value = kept.joined(separator: " ").rRStrip(".-'")
        if !kept.contains(where: { reNotAZ.sub(fold($0), "").rLen >= 2 }) { return nil }
        return value
    }

    static func doctors(_ linesByPage: [[Line]]) -> [Item] {
        let headIDs = Set((linesByPage.first.map(head) ?? []).map { Pos(page: $0.page, line: $0.index, col: 0) })
        var cands: [(role: String?, name: String, conf: Double, ln: Line, s: Int, e: Int)] = []
        for lines in linesByPage {
            for ln in lines where !ln.f.isEmpty {
                var used: [(Int, Int)] = []
                for m in reDoctorLabel.finditer(ln.f) {
                    let role: String? = m.g(1)!.hasPrefix("ref") ? "referrer" : nil
                    let s = m.end
                    guard let nm = reCapName.match(ln.p.rSub(s, ln.cellEnd(s))) else { continue }
                    if fold(nm.group0).hasPrefix("self") {
                        used.append((m.start, s + nm.end))
                        continue
                    }
                    let name = cutName(nm.group0, nameCut)
                    used.append((m.start, s + nm.end))
                    if let name { cands.append((role, name, 0.85, ln, s, s + name.rLen)) }
                }
                for m in reDrPrefix.finditer(ln.p) {
                    if used.contains(where: { $0.0 <= m.start && m.start < $0.1 }) { continue }
                    let s = m.end
                    guard let nm = reCapName.match(ln.p.rSub(s, ln.cellEnd(s))), let name = cutName(nm.group0, nameCut) else { continue }
                    let conf = headIDs.contains(Pos(page: ln.page, line: ln.index, col: 0)) ? 0.85 : 0.7
                    cands.append((nil, name, conf, ln, s, s + name.rLen))
                }
            }
        }
        var items: [Item] = []
        var best: [String: (role: String?, name: String, conf: Double, ln: Line, s: Int, e: Int)] = [:]
        for c in cands {
            let k = c.role ?? ""
            if best[k] == nil || c.conf > best[k]!.conf { best[k] = c }
        }
        let ref = best["referrer"]
        let prim = best[""]
        if let ref { items.append(field("doctor_name", ref.name, .obj(["role": .str("referrer")]), ref.conf, ref.ln, ref.s)) }
        if let prim, !(ref != nil && normText(ref!.name) == normText(prim.name)) {
            items.append(field("doctor_name", prim.name, .null, prim.conf, prim.ln, prim.s))
            let ln = prim.ln
            var spec = specialtyIn(ln, prim.e)
            if spec == nil, let lines = linesByPage.first(where: { !$0.isEmpty && $0[0].page == ln.page }) {
                if let next = lines.dropFirst(ln.index + 1).first(where: { !$0.p.isEmpty }) {
                    spec = specialtyIn(next, 0)
                }
            }
            if let spec { items.append(field("doctor_specialty", spec.0, .null, 0.7, spec.1, spec.2)) }
        }
        return items
    }

    static func specialtyIn(_ ln: Line, _ start: Int) -> (String, Line, Int)? {
        var ms: [(Int, Int)] = []
        for m in reSpecialty.finditer(ln.f.rSub(start)) {
            let s = start + m.start, e = start + m.end
            let printed = ln.p.rSub(s, e)
            let letters = reNotAZ.sub(m.g(1)!, "")
            if letters.rLen <= 3, qualifications.contains(letters), printed.uppercased() != printed { continue }
            ms.append((s, e))
        }
        guard let first = ms.first, let last = ms.last else { return nil }
        let s = first.0
        var e = last.1
        let span = ln.p.rSub(s, e)
        if span.rCount("(") > span.rCount(")"), ln.p.rSub(e, e + 1) == ")" { e += 1 }
        return (ln.p.rSub(s, e).rRStrip(" ,"), ln, s)
    }

    static func facility(_ linesByPage: [[Line]]) -> [Item] {
        for (pi, lines) in linesByPage.enumerated() {
            for ln in head(lines) {
                if ln.p.rLen > 80 || reFacility.search(ln.f) == nil { continue }
                if reFacilitySkip.search(ln.f) != nil || reDrPrefix.match(ln.p) != nil { continue }
                var s = 0
                if let lab = reLeadingLabel.match(ln.f) { s = lab.end }
                var value = ln.p.rSub(s)
                let kw = reFacility.search(ln.f.rSub(s))
                let comma = value.rFind(",")
                if comma >= 0, (kw != nil && kw!.start < comma) || value.rCount(",") >= 3 {
                    value = value.rSub(0, comma)
                }
                value = titleCase(value.rStrip(" ,.-|"), 2)
                if reLetterAny.findall(value).count < 3 { continue }
                return [field("facility", value, .null, pi == 0 ? 0.8 : 0.6, ln, s)]
            }
        }
        return []
    }

    static func department(_ linesByPage: [[Line]]) -> [Item] {
        for lines in linesByPage {
            for ln in lines where !ln.f.isEmpty {
                if let m = reDeptOf.search(ln.f) ?? reXDept.search(ln.f) {
                    let s = m.gStart(1), e = m.gEnd(1)
                    return [field("department", ln.p.rSub(s, e), .null, 0.7, ln, s)]
                }
            }
        }
        return []
    }

    static func personNameAt(_ ln: Line, _ start: Int) -> (String, Int)? {
        var s = start
        let end = ln.cellEnd(s)
        if let t = reTitleAt.match(ln.f.rSub(s, end)) { s += t.end }
        guard let m = rePersonWords.match(ln.f.rSub(s, end)) else { return nil }
        let toks = m.group0.rSplitSpace
        var kept = 0
        for tk in toks {
            if patientCut.contains(reNotAZ.sub(tk, "")) { break }
            kept += 1
        }
        if kept == 0 { return nil }
        let length = toks.prefix(kept).joined(separator: " ").rLen
        let value = ln.p.rSub(s, s + length).rRStrip(" .,-'")
        if reLetterAny.findall(value).count < 2 { return nil }
        return (value, s)
    }

    static func patient(_ linesByPage: [[Line]]) -> [Item] {
        guard let firstPage = linesByPage.first else { return [] }
        let hd = head(firstPage)
        var items: [Item] = []
        func context(_ i: Int) -> Bool {
            for k in [i - 1, i, i + 1] where k >= 0 && k < hd.count {
                if rePatientContext.search(hd[k].f) != nil { return true }
            }
            return false
        }
        for (i, ln) in hd.enumerated() {
            var found: (String, Int)?
            if let m = rePatientStrong.search(ln.f) { found = personNameAt(ln, m.end) }
            if found == nil, context(i) {
                if let m = rePatientWeak.search(ln.f), rePatientStrong.search(ln.f) == nil { found = personNameAt(ln, m.end) }
                if found == nil, let m = reTitle.search(ln.f) { found = personNameAt(ln, m.start) }
            }
            if let found {
                items.append(field("patient_name", found.0, .null, 0.75, ln, found.1))
                break
            }
        }
        for ln in hd {
            var m = reAgeLabel.search(ln.f)
            var isSlash = false
            if m == nil, let slash = reAgeSlash.search(ln.f) {
                m = slash
                isSlash = true
            }
            if m == nil, rePatientContext.search(ln.f) != nil { m = reAgeYears.search(ln.f) }
            if let m {
                let n = Int(m.g(1)!)!
                let unit = isSlash ? nil : m.g(2)
                if n <= 120 {
                    let txt: String
                    if let unit, unit.hasPrefix("m") { txt = "\(n) months" } else if let unit, unit.hasPrefix("d") { txt = "\(n) days" } else { txt = "\(n) years" }
                    items.append(field("patient_age", txt, .null, 0.75, ln, m.start))
                    break
                }
            }
        }
        for ln in hd {
            var m = reSexLabel.search(ln.f)
            var sex = m?.g(1)
            if m == nil, let m2 = reAgeSlash.search(ln.f) {
                m = m2
                sex = m2.g(2)
            }
            if let m {
                let v = ["m": "male", "male": "male", "f": "female", "female": "female"][sex ?? ""] ?? "other"
                items.append(field("patient_sex", v, .null, 0.75, ln, m.start))
                break
            }
        }
        return items
    }

    static let reCapsTok = Rx("[A-Z'.-]+")
    static let reCapLetter = Rx("[A-Z]")

    static func titleCase(_ p: String, _ minLetters: Int = 4) -> String {
        let small: Set<String> = ["and", "of", "the", "for", "with", "in", "on", "to"]
        return p.rSplitSpace.enumerated().map { i, tk in
            let lo = tk.lowercased()
            if i > 0, small.contains(lo), tk.uppercased() == tk { return lo }
            if reCapsTok.fullmatch(tk) != nil, reCapLetter.findall(tk).count >= minLetters {
                return tk.rSub(0, 1) + tk.rSub(1).lowercased()
            }
            return tk
        }.joined(separator: " ")
    }

    static func reportName(_ linesByPage: [[Line]], _ facilityLine: Line?) -> [Item] {
        guard let firstPage = linesByPage.first else { return [] }
        let panel = ruleSet.types.filter { ["lab_report", "imaging_report", "diagnostic_report"].contains($0.id) }
            .flatMap { $0.rules.filter { $0.weight >= 3 }.map(\.rx) }
        var fallback: Line?
        for ln in head(firstPage) {
            if sectionLabel(ln.f) != nil { break }
            guard let c = ln.f.utf16.first, c >= 97, c <= 122 else { continue }
            if ln.same(facilityLine) || isHeaderLine(ln.f) || ln.p.rSplitSpace.count > 8 { continue }
            if parseLabLine(ln) != nil || reGenericLabel.match(ln.f) != nil { continue }
            if ln.f.contains(":") || !dateCandidates(ln.f, YMD(y: 2100, m: 1, d: 1), "dmy").isEmpty { continue }
            if panel.contains(where: { $0.search(ln.f) != nil }) || rePanelWord.search(ln.f) != nil {
                return [field("report_name", titleCase(ln.p.rStrip(" .:-")), .null, 0.8, ln)]
            }
            if fallback == nil, reReportWord.search(ln.f) != nil, ln.p.rSplitSpace.count <= 6, reDigit.search(ln.f) == nil {
                fallback = ln
            }
        }
        if let fallback { return [field("report_name", titleCase(fallback.p.rStrip(" .:-")), .null, 0.7, fallback)] }
        return []
    }

    static func medicationLine(_ ln: Line) -> Item? {
        let f = ln.f, p = ln.p
        var form: String?
        let restStart: Int
        var fallbackName: String?
        if let m = reMedForm.match(f) {
            let word = m.g(1)!
            form = formCanon.first { word.hasPrefix($0.0) }?.1
            restStart = m.end
        } else {
            guard let fb = reMedFallback.match(f) else { return nil }
            restStart = fb.gStart(1)
            fallbackName = fb.g(1)
        }
        let rest = f.rSub(restStart)
        var found: [(String, (Int, Int))] = []
        for (name, rx) in [("strength", reStrength), ("frequency", reFreq), ("duration", reDuration), ("instructions", reInstr), ("dose", reDose)] {
            if let mm = rx.search(rest) { found.append((name, (restStart + mm.start, restStart + mm.end))) }
        }
        func has(_ k: String) -> (Int, Int)? { found.first { $0.0 == k }?.1 }
        if form == nil {
            guard let s = has("strength"), has("frequency") != nil || has("duration") != nil || has("instructions") != nil else { return nil }
            if s.0 != restStart + (fallbackName?.rLen ?? 0) + 1 || f.rSub(s.0, s.1).hasSuffix("%") { return nil }
        }
        var clean: [String: (Int, Int)] = [:]
        var last = -1
        for (k, span) in found.stableSorted(by: { $0.1.0 < $1.1.0 }) where span.0 >= last {
            clean[k] = span
            last = span.1
        }
        var stop = (clean.values.map(\.0) + [f.rLen]).min()!
        for sep in [" - ", " (", " — ", " – "] {
            let q = f.rFind(sep, restStart)
            if q >= 0, q < stop { stop = q }
        }
        let name = p.rSub(restStart, stop).rStrip(" -–:,.(")
        if reLetterAny.findall(name).count < 2 || name.rLen > 60 || name.rSplitSpace.count > 6 { return nil }
        var vals = clean.mapValues { p.rSub($0.0, $0.1) }
        if let form, ["syrup", "suspension", "drops", "solution"].contains(form), let strength = vals["strength"], vals["dose"] == nil {
            if fold(strength).replacingOccurrences(of: " ", with: "").hasSuffix("ml"), !strength.contains("/") {
                vals["dose"] = strength
                vals["strength"] = nil
            }
        }
        let vj: RJ = .obj(["name": .str(name), "strength": .string(vals["strength"]), "form": .string(form), "dose": .string(vals["dose"]),
                           "frequency": .string(vals["frequency"]), "duration": .string(vals["duration"]), "instructions": .string(vals["instructions"])])
        let conf = (vals["strength"] != nil || vals["frequency"] != nil) ? 0.8 : 0.6
        return field("medication", name, vj, conf, ln)
    }

    static func medications(_ linesByPage: [[Line]], _ recordType: String) -> [Item] {
        guard medTypes.contains(recordType) else { return [] }
        return linesByPage.flatMap { $0.filter { !$0.f.isEmpty }.compactMap(medicationLine) }
    }

    static func fieldsItems(_ linesByPage: [[Line]], _ recordType: String) -> [Item] {
        var items = doctors(linesByPage)
        let fac = facility(linesByPage)
        items += fac
        items += department(linesByPage)
        items += patient(linesByPage)
        if ["lab_report", "imaging_report", "diagnostic_report", "other"].contains(recordType) {
            items += reportName(linesByPage, fac.first?.line)
        }
        items += sections(linesByPage, recordType)
        items += medications(linesByPage, recordType)
        return dedup(items) { "\($0.key)|\($0.valueJSON["role"].string ?? "")|\(normalizeValue($0.key, $0.valueText, $0.valueJSON))" }
    }

    static func extractFields(_ pages: [String], _ recordType: String) -> [RJ] {
        fieldsItems(pages.enumerated().map { pageLines($1, $0) }, recordType).map(\.publicJSON)
    }

    // MARK: §14 Boundaries

    static let rePageOf = Rx(#"(?<![a-z0-9])(?:page|pg\.?)[ ]?([0-9]{1,3})[ ]?(?:of|/)[ ]?([0-9]{1,3})(?![0-9])"#)
    static let reBareOf = Rx(#"([0-9]{1,3}) ?(?:of|/) ?([0-9]{1,3})"#)
    static let reContinued = Rx(#"(?:continued|contd|cont'd|cont\.)(?![a-z])"#)

    static func pageNumbering(_ lines: [Line]) -> (Int, Int)? {
        let ne = nonempty(lines)
        let first3 = Array(ne.prefix(3))
        let zone = first3 + ne.suffix(3).filter { ln in !first3.contains { $0.same(ln) } }
        for ln in zone {
            if let m = rePageOf.search(ln.f) { return (Int(m.g(1)!)!, Int(m.g(2)!)!) }
            if let m = reBareOf.fullmatch(ln.f) { return (Int(m.g(1)!)!, Int(m.g(2)!)!) }
        }
        return nil
    }

    static func jaccard(_ a: Set<String>, _ b: Set<String>) -> Double {
        if a.isEmpty && b.isEmpty { return 1 }
        return Double(a.intersection(b).count) / Double(a.union(b).count)
    }

    static func detectBoundaries(_ pages: [String], _ today: String, _ dateOrder: String) -> RJ {
        let todayD = YMD.iso(today) ?? YMD(y: 2026, m: 1, d: 1)
        let lps = pages.enumerated().map { pageLines($1, $0) }
        let n = pages.count
        struct Info {
            var type: String?
            var names: Set<String>
            var report: String?
            var dates: Set<YMD>
            var numbering: (Int, Int)?
            var letters: Int
            var head: Set<String>
            var first: String
        }
        let info: [Info] = lps.map { lines in
            let headIDs = Set(head(lines).map(\.index))
            let ptype = pageBestType(lines)
            let fields = fieldsItems([lines], ptype ?? "other")
            let names = Set(fields.filter { $0.key == "patient_name" }.map { normText($0.valueText) })
            let rname = fields.filter { $0.key == "report_name" }.map { normText($0.valueText) }
            let dates = Set(dateItems([lines], ptype ?? "other", todayD, dateOrder)
                .filter { headIDs.contains($0.line.index) && $0.key != "follow_up_date" }.compactMap(\.date))
            var headWords = Set<String>()
            for ln in nonempty(lines).prefix(5) { headWords.formUnion(words(ln.f)) }
            return Info(type: ptype, names: names, report: rname.first, dates: dates, numbering: pageNumbering(lines),
                        letters: lines.reduce(0) { $0 + reAZ1.findall($1.f).count }, head: headWords,
                        first: nonempty(lines).first?.f ?? "")
        }
        var scores: [RJ] = []
        var breakScores: [Int: Int] = [:]
        var starts = [0]
        if n >= 3 {
            var segType = info[0].type
            var segNames = info[0].names
            var segDates = info[0].dates
            var segReport = info[0].report
            for i in 1..<n {
                let cur = info[i], prev = info[i - 1]
                var score = 0
                var signals: [RJ] = []
                if let num = cur.numbering, num.0 == 1 { score += 3; signals.append(.str("page_one_of")) }
                if let num = prev.numbering, num.0 == num.1, num.0 >= 1 { score += 2; signals.append(.str("previous_last_page")) }
                if let ct = cur.type, let st = segType, ct != st { score += 3; signals.append(.str("type_change")) }
                if jaccard(cur.head, prev.head) < 0.3 { score += 2; signals.append(.str("head_change")) }
                if (!cur.names.isEmpty && !segNames.isEmpty && cur.names.isDisjoint(with: segNames))
                    || (!cur.dates.isEmpty && !segDates.isEmpty && cur.dates.isDisjoint(with: segDates)) {
                    score += 2
                    signals.append(.str("patient_or_date_change"))
                }
                if let cr = cur.report, let sr = segReport, cr != sr { score += 2; signals.append(.str("report_name_change")) }
                if prev.letters < 20 { score += 1; signals.append(.str("previous_blank")) }
                if (cur.numbering.map { $0.0 > 1 } ?? false) || reContinued.match(cur.first) != nil {
                    score -= 3
                    signals.append(.str("continuation"))
                }
                scores.append(.obj(["page": .int(i), "score": .int(score), "signals": .arr(signals)]))
                breakScores[i] = score
                if score >= 5 {
                    starts.append(i)
                    segType = cur.type
                    segNames = cur.names
                    segDates = cur.dates
                    segReport = cur.report
                } else {
                    if segType == nil { segType = cur.type }
                    segNames.formUnion(cur.names)
                    segDates.formUnion(cur.dates)
                    if segReport == nil { segReport = cur.report }
                }
            }
        }
        var segments: [RJ] = []
        for (k, s) in starts.enumerated() {
            let e = k + 1 < starts.count ? starts[k + 1] - 1 : n - 1
            guard e >= s else { continue }
            let range = Array(pages[s...e])
            let cls = classify(range)
            let type = cls["record_type"].string ?? "other"
            let fl = extractFields(range, type)
            let rn = fl.filter { $0["key"].string == "report_name" }.compactMap { $0["value_text"].string }
            let fac = fl.filter { $0["key"].string == "facility" }.compactMap { $0["value_text"].string }
            let label = typeLabel[type] ?? "Record"
            let title = rn.first ?? (fac.first.map { label + " — " + $0 } ?? label)
            let sc: Int
            if let v = breakScores[s], s != 0 { sc = v } else if starts.count > 1 { sc = breakScores[starts[1]] ?? 0 } else { sc = 0 }
            segments.append(.obj(["page_start": .int(s), "page_end": .int(e), "record_type": .str(type), "title": .str(title),
                                  "confidence": .number(round2(min(0.95, 0.5 + 0.05 * Double(sc))))]))
        }
        if n == 0 { segments = [] }
        return .obj(["page_scores": .arr(scores), "segments": .arr(segments), "propose": .bool(segments.count >= 2)])
    }
}
