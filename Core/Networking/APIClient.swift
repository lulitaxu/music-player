import Foundation

/// Shared errors for every provider adapter.
public enum ProviderError: Error, Sendable {
    case unsupported          // the provider cannot do this; declared in its capabilities
    case http(Int)            // non-2xx response
    case transport            // no usable response / bad URL
}

/// Minimal JSON GET client. Providers own their URLs and headers;
/// this owns the request, the status check, and decoding.
public struct APIClient: Sendable {
    public init() {}

    public func get<T: Decodable>(_ type: T.Type, url: URL,
                                  headers: [String: String] = [:]) async throws -> T {
        var request = URLRequest(url: url)
        for (key, value) in headers { request.setValue(value, forHTTPHeaderField: key) }
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw ProviderError.transport }
        guard (200..<300).contains(http.statusCode) else { throw ProviderError.http(http.statusCode) }
        return try JSONDecoder().decode(T.self, from: data)
    }
}
