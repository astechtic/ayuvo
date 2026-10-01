import Foundation

/// One `dataPoints.list` page. Points stay as `RJ` (Sendable JSON) until the mapper reads them.
nonisolated struct GoogleHealthPage: Sendable {
    var points: [RJ]
    var nextPageToken: String?
}

nonisolated enum GoogleHealthAPIError: LocalizedError, Equatable {
    /// Still 401 after one token refresh.
    case unauthorized
    /// 403: usually a scope the user did not grant.
    case forbidden(String?)
    case http(status: Int, message: String?)
    case invalidResponse
    case network(String)

    /// 400 / 404 on an `optional` map type: the type does not exist for this account / API revision.
    func isUnsupported(optionalType: Bool) -> Bool {
        guard optionalType, case .http(let status, _) = self else { return false }
        return status == 400 || status == 404
    }

    var statusCode: Int? {
        switch self {
        case .unauthorized: return 401
        case .forbidden: return 403
        case .http(let status, _): return status
        case .invalidResponse, .network: return nil
        }
    }

    var errorDescription: String? {
        switch self {
        case .unauthorized:
            return String(localized: "Google no longer accepts this sign-in. Reconnect Google Health.", comment: "Google Health sync error")
        case .forbidden(let message):
            return message ?? String(localized: "Google refused access to this data type.", comment: "Google Health sync error")
        case .http(let status, let message):
            return message ?? String(localized: "Google Health returned error \(status).", comment: "Google Health sync error; placeholder is the HTTP status")
        case .invalidResponse:
            return String(localized: "Google Health sent a response Ayuvo could not read.", comment: "Google Health sync error")
        case .network(let message):
            return message
        }
    }
}

/// What the sync engine needs from the API (`FakeGoogleHealthFetcher` in tests).
nonisolated protocol GoogleHealthFetching: Sendable {
    func listDataPoints(ghType: String, filter: String, pageSize: Int, pageToken: String?) async throws -> GoogleHealthPage
}

/// `GET {base}/users/me/dataTypes/{type}/dataPoints` with URLSession (the `GeminiService`
/// style, no SDK). 401 → refresh the token → retry once; 429 / 5xx → exponential backoff
/// honouring `Retry-After`; everything else surfaces as `GoogleHealthAPIError`.
nonisolated final class GoogleHealthClient: GoogleHealthFetching, Sendable {
    let api: GoogleHealthMap.API
    let tokens: any GoogleHealthTokenProviding
    let session: URLSession
    let maxRetries: Int
    let baseDelay: TimeInterval
    let sleep: @Sendable (TimeInterval) async throws -> Void

    init(
        api: GoogleHealthMap.API,
        tokens: any GoogleHealthTokenProviding,
        session: URLSession = .shared,
        maxRetries: Int = 4,
        baseDelay: TimeInterval = 1,
        sleep: @escaping @Sendable (TimeInterval) async throws -> Void = { try await Task.sleep(nanoseconds: UInt64($0 * 1_000_000_000)) }
    ) {
        self.api = api
        self.tokens = tokens
        self.session = session
        self.maxRetries = maxRetries
        self.baseDelay = baseDelay
        self.sleep = sleep
    }

    func listDataPoints(ghType: String, filter: String, pageSize: Int, pageToken: String?) async throws -> GoogleHealthPage {
        guard var components = api.listURL(ghType: ghType).flatMap({ URLComponents(url: $0, resolvingAgainstBaseURL: false) }) else {
            throw GoogleHealthAPIError.invalidResponse
        }
        var items = [URLQueryItem(name: "filter", value: filter), URLQueryItem(name: "pageSize", value: String(pageSize))]
        if let pageToken { items.append(URLQueryItem(name: "pageToken", value: pageToken)) }
        components.queryItems = items
        // `+` is literal in a query item but means space to Google's parser.
        components.percentEncodedQuery = components.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        guard let url = components.url else { throw GoogleHealthAPIError.invalidResponse }
        let data = try await send(url: url)
        guard let object = try? JSONSerialization.jsonObject(with: data) else { throw GoogleHealthAPIError.invalidResponse }
        let json = RJ.from(object)
        let next = json["nextPageToken"].string
        return GoogleHealthPage(points: json["dataPoints"].array ?? [], nextPageToken: next?.isEmpty == true ? nil : next)
    }

    func send(url: URL) async throws -> Data {
        var refreshed = false
        var attempt = 0
        var forceRefresh = false
        while true {
            try Task.checkCancellation()
            let token = try await tokens.accessToken(forceRefresh: forceRefresh)
            forceRefresh = false
            var request = URLRequest(url: url)
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            request.setValue("application/json", forHTTPHeaderField: "Accept")
            request.timeoutInterval = 60
            let data: Data
            let response: URLResponse
            do {
                (data, response) = try await session.data(for: request)
            } catch is CancellationError {
                throw CancellationError()
            } catch let error as URLError where error.code == .cancelled {
                throw CancellationError()
            } catch {
                throw GoogleHealthAPIError.network(error.localizedDescription)
            }
            guard let http = response as? HTTPURLResponse else { throw GoogleHealthAPIError.invalidResponse }
            switch http.statusCode {
            case 200..<300:
                return data
            case 401:
                guard !refreshed else { throw GoogleHealthAPIError.unauthorized }
                refreshed = true
                forceRefresh = true
            case 429, 500...599:
                guard attempt < maxRetries else {
                    throw GoogleHealthAPIError.http(status: http.statusCode, message: Self.message(data))
                }
                let retryAfter = (http.value(forHTTPHeaderField: "Retry-After")).flatMap(Double.init)
                let delay = retryAfter ?? baseDelay * pow(2, Double(attempt))
                attempt += 1
                try await sleep(min(delay, 60))
            case 403:
                throw GoogleHealthAPIError.forbidden(Self.message(data))
            default:
                throw GoogleHealthAPIError.http(status: http.statusCode, message: Self.message(data))
            }
        }
    }

    /// `error.message` of a Google error body.
    static func message(_ data: Data) -> String? {
        guard let object = try? JSONSerialization.jsonObject(with: data) else { return nil }
        return RJ.from(object)["error"]["message"].string
    }
}
