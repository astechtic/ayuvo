import Foundation

/// `shared/records/coach_tools.json` (docs/health-records.md §26–§30), bundled verbatim as
/// `Records/Resources/coach_tools.json` and byte-compared with the shared file by the parity tests.
/// Tool names, descriptions, input schemas, prompt lines and error strings all come from this file,
/// so the three provider formats advertise exactly the contract text.
nonisolated struct RecordsCoachContract: Sendable {
    nonisolated struct Tool: Sendable {
        var name: String
        var description: String
        /// The schema object exactly as written in the file (compact, keys in file order).
        var schemaText: String
        var schema: RJ
    }

    var tools: [Tool]
    var prompt: [String: String]
    var errors: [String: String]

    static let searchTool = "records_search"
    static let getTool = "records_get"
    static let seriesTool = "records_observation_series"
    static let toolNames = [searchTool, getTool, seriesTool]

    var names: [String] { tools.map(\.name) }
    var descriptions: [String: String] { Dictionary(tools.map { ($0.name, $0.description) }, uniquingKeysWith: { a, _ in a }) }

    func tool(_ name: String) -> Tool? { tools.first { $0.name == name } }

    /// Provider-ready schema (`[String: Any]`) of a records tool.
    func parameterSchema(for name: String) -> [String: Any]? {
        tool(name)?.schema.anyValue as? [String: Any]
    }

    func promptText(_ key: String) -> String { prompt[key] ?? "" }

    /// `errors.<key>` with `{placeholders}` filled.
    func error(_ key: String, _ values: [String: String] = [:]) -> String {
        Self.fill(errors[key] ?? key, values)
    }

    static func fill(_ template: String, _ values: [String: String]) -> String {
        var text = template
        for (key, value) in values {
            text = text.replacingOccurrences(of: "{\(key)}", with: value)
        }
        return text
    }

    // MARK: Loading

    init(tools: [Tool], prompt: [String: String], errors: [String: String]) {
        self.tools = tools
        self.prompt = prompt
        self.errors = errors
    }

    init?(data: Data) {
        let text = String(decoding: data, as: UTF8.self)
        guard let root = RJ.parse(text), root["format"].string == "ayuvo-records-coach-tools" else { return nil }
        let rawSchemas = Self.rawSchemaTexts(text)
        var tools: [Tool] = []
        for (index, entry) in (root["tools"].array ?? []).enumerated() {
            guard let name = entry["name"].string else { continue }
            tools.append(Tool(
                name: name,
                description: entry["description"].string ?? "",
                schemaText: index < rawSchemas.count ? rawSchemas[index] : entry["input_schema"].jsonText,
                schema: entry["input_schema"]
            ))
        }
        self.init(
            tools: tools,
            prompt: (root["prompt"].object ?? [:]).compactMapValues(\.string),
            errors: (root["errors"].object ?? [:]).compactMapValues(\.string)
        )
    }

    /// The literal text of every `"input_schema": {…}` object, in file order (balanced braces outside
    /// string literals), so tests can compare the serialized schema with the file byte for byte.
    static func rawSchemaTexts(_ text: String) -> [String] {
        let scalars = Array(text.unicodeScalars)
        let marker = Array("\"input_schema\":".unicodeScalars)
        var results: [String] = []
        var index = 0
        while index + marker.count <= scalars.count {
            guard Array(scalars[index..<(index + marker.count)]) == marker else {
                index += 1
                continue
            }
            var cursor = index + marker.count
            while cursor < scalars.count, scalars[cursor] == " " { cursor += 1 }
            guard cursor < scalars.count, scalars[cursor] == "{" else {
                index += marker.count
                continue
            }
            let start = cursor
            var depth = 0
            var inString = false
            var escaped = false
            while cursor < scalars.count {
                let c = scalars[cursor]
                if inString {
                    if escaped { escaped = false } else if c == "\\" { escaped = true } else if c == "\"" { inString = false }
                } else if c == "\"" {
                    inString = true
                } else if c == "{" {
                    depth += 1
                } else if c == "}" {
                    depth -= 1
                    if depth == 0 { break }
                }
                cursor += 1
            }
            var slice = String.UnicodeScalarView()
            slice.append(contentsOf: scalars[start...min(cursor, scalars.count - 1)])
            results.append(String(slice))
            index = cursor + 1
        }
        return results
    }

    static let shared: RecordsCoachContract = {
        for bundle in [Bundle.main, Bundle(for: BundleMarker.self)] {
            if let url = bundle.url(forResource: "coach_tools", withExtension: "json"),
               let data = try? Data(contentsOf: url),
               let contract = RecordsCoachContract(data: data) {
                return contract
            }
        }
        return RecordsCoachContract(tools: [], prompt: [:], errors: [:])
    }()

    private final class BundleMarker {}
}
