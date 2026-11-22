import json
import io
import os
import threading
import unittest
import urllib.error
import urllib.request
from unittest.mock import patch
import server


class TokenTests(unittest.TestCase):
    def test_missing_key_is_explicit(self):
        with patch.dict(os.environ, {}, clear=True), self.assertRaises(server.ServiceError) as failure:
            server.issue_token()
        self.assertEqual("NOT_CONFIGURED", failure.exception.code)

    def test_missing_or_invalid_model_never_calls_provider(self):
        with patch.dict(os.environ, {"GEMINI_API_KEY": "test-only-not-a-credential", "GEMINI_LIVE_MODEL": "../escape"}), \
                patch.object(server, "upstream") as provider, self.assertRaises(server.ServiceError):
            server.issue_token()
        provider.assert_not_called()

    def test_single_use_short_lived_model_constrained_credential(self):
        with patch.dict(os.environ, {"GEMINI_API_KEY": "test-only-not-a-credential", "GEMINI_LIVE_MODEL": "test-native-live"}), \
                patch.object(server, "upstream", side_effect=[{"supportedGenerationMethods": ["bidiGenerateContent"]}, {"name": "test-ephemeral"}]) as provider:
            result = server.issue_token()
        args = provider.call_args_list[1].args
        self.assertEqual("auth_tokens", args[0])
        self.assertEqual(1, args[1]["uses"])
        self.assertEqual("models/test-native-live", args[1]["bidiGenerateContentSetup"]["model"])
        self.assertEqual(["AUDIO"], args[1]["bidiGenerateContentSetup"]["generationConfig"]["responseModalities"])
        self.assertEqual(result["setup"], args[1]["bidiGenerateContentSetup"])
        self.assertNotIn("liveConnectConstraints", args[1])
        expiry = server.dt.datetime.fromisoformat(result["expireTime"].replace("Z", "+00:00"))
        new = server.dt.datetime.fromisoformat(result["newSessionExpireTime"].replace("Z", "+00:00"))
        self.assertEqual(540, (expiry - new).total_seconds())
        self.assertNotIn("test-only-not-a-credential", json.dumps(result))

    def test_non_live_model_cannot_mint_token(self):
        with patch.dict(os.environ, {"GEMINI_API_KEY": "test-only-not-a-credential", "GEMINI_LIVE_MODEL": "text-model"}), \
                patch.object(server, "upstream", return_value={"supportedGenerationMethods": ["generateContent"]}), \
                self.assertRaises(server.ServiceError) as failure:
            server.issue_token()
        self.assertEqual("NOT_A_LIVE_MODEL", failure.exception.code)

    def test_upstream_errors_are_classified_without_returning_private_bodies(self):
        for status, code in [(401, "INVALID_CREDENTIALS"), (403, "ACCESS_DENIED"),
                             (404, "MODEL_UNAVAILABLE"), (429, "QUOTA_EXCEEDED")]:
            error = urllib.error.HTTPError("https://example.invalid", status, "raw-private-details", {}, io.BytesIO(b"private-body"))
            with patch.dict(os.environ, {"GEMINI_API_KEY": "test-only-not-a-credential"}), \
                    patch.object(server.urllib.request, "urlopen", side_effect=error), self.assertRaises(server.ServiceError) as failure:
                server.upstream("auth_tokens", {})
            self.assertEqual(code, failure.exception.code)
            self.assertNotIn("private", failure.exception.message)
        with patch.dict(os.environ, {"GEMINI_API_KEY": "test-only-not-a-credential"}), \
                patch.object(server.urllib.request, "urlopen", side_effect=TimeoutError()), self.assertRaises(server.ServiceError) as failure:
            server.upstream("auth_tokens", {})
        self.assertEqual("PROVIDER_TIMEOUT", failure.exception.code)

    def test_loopback_host_origin_and_body_boundary(self):
        http = server.ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        worker = threading.Thread(target=http.serve_forever, daemon=True)
        worker.start()
        url = f"http://127.0.0.1:{http.server_port}"
        try:
            with urllib.request.urlopen(url + "/health") as response:
                self.assertEqual("no-store", response.headers["Cache-Control"])
                self.assertNotIn("token", json.loads(response.read()))
            for headers, body, status in [({"Origin": "https://example.org"}, b"", 403),
                                           ({"Host": "attacker.example"}, b"", 403),
                                           ({}, b"unexpected audio", 400)]:
                req = urllib.request.Request(url + "/token", data=body, headers=headers)
                with self.assertRaises(urllib.error.HTTPError) as error:
                    urllib.request.urlopen(req)
                self.assertEqual(status, error.exception.code)
        finally:
            http.shutdown(); http.server_close(); worker.join()


if __name__ == "__main__":
    unittest.main()
