"""Source-level read-only and credential-handling guards, not runtime security proof."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main/kotlin/dev/wahh/alarmshell"
CORE = ROOT / "app/core/src/main/kotlin/dev/wahh/alarmshell/core"

FORBIDDEN_REMOTE = (
    "switchDefenceMode",
    "control/arm",
    "control/disarm",
    "/v3/devconfig/",
    "remote/unlock",
    "set_area_defence_mode",
)


class ReadOnlyTest(unittest.TestCase):
    def test_no_control_endpoint_anywhere(self):
        for path in list(APP.rglob("*.kt")) + list(CORE.rglob("*.kt")):
            source = path.read_text()
            for token in FORBIDDEN_REMOTE:
                self.assertNotIn(token, source, f"{path} must not reference {token}")

    def test_isapi_tunnel_is_allowlisted_by_construction(self):
        api = (CORE / "HikApi.kt").read_text()
        self.assertIn("READ_ONLY_PATHS.any { path == it }", api)
        self.assertIn('"GET $path?format=json\\r\\n"', api)
        self.assertIn('"/ISAPI/SecurityCP/status/subSystems"', api)
        self.assertIn('"/ISAPI/SecurityCP/status/zones"', api)

    def test_client_exposes_only_read_operations(self):
        client = (CORE / "HikClient.kt").read_text()
        for method in ("fun login(", "fun refresh(", "fun captchaImage(", "fun ensureFresh(", "fun devices(", "fun panelStatus("):
            self.assertIn(method, client)
        for verb in ("fun arm", "fun disarm", "fun bypass", "fun unlock", "fun control"):
            self.assertNotIn(verb, client)


class CredentialTest(unittest.TestCase):
    def test_session_is_encrypted_with_keystore(self):
        store = (APP / "SecureSessionStore.kt").read_text()
        self.assertIn("AndroidKeyStore", store)
        self.assertIn("SessionCipher.encrypt", store)
        self.assertIn("Base64.NO_WRAP", store)
        self.assertNotIn("MODE_WORLD_READABLE", store)

    def test_password_is_never_persisted(self):
        login = (APP / "LoginActivity.kt").read_text()
        self.assertIn("takeChars()", login)
        self.assertIn("password.fill(", login)
        self.assertNotIn("sessions.write(", login.split("onSuccess")[0])
        store = (APP / "SecureSessionStore.kt").read_text()
        self.assertNotIn("password", store.lower())

    def test_secret_screens_and_fields_are_hardened(self):
        login = (APP / "LoginActivity.kt").read_text()
        diagnostics = (APP / "DiagnosticsActivity.kt").read_text()
        self.assertIn("FLAG_SECURE", login)
        self.assertIn("FLAG_SECURE", diagnostics)
        field = (APP / "ui/SecureField.kt").read_text()
        for contract in (
            "TYPE_TEXT_VARIATION_PASSWORD",
            "TYPE_TEXT_FLAG_NO_SUGGESTIONS",
            "IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS",
            "IME_FLAG_NO_PERSONALIZED_LEARNING",
            "labelFor",
            "contentDescription",
            "isSaveEnabled = false",
            "hideSecret",
        ):
            self.assertIn(contract, field)

    def test_release_is_not_debuggable(self):
        build = (ROOT / "app/build.gradle.kts").read_text()
        self.assertIn("isDebuggable = false", build)
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
        self.assertIn('android:allowBackup="false"', manifest)
        self.assertIn('android:usesCleartextTraffic="false"', manifest)


if __name__ == "__main__":
    unittest.main()
