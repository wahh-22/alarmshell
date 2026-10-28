"""Source-level contract guards: the command surface, personal defaults and credential handling.

These are static checks, not runtime security proof.
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main/kotlin/dev/wahh/alarmshell"
CORE = ROOT / "app/core/src/main/kotlin/dev/wahh/alarmshell/core"


class CommandSurfaceTest(unittest.TestCase):
    def test_no_legacy_or_free_form_control_paths(self):
        forbidden = ("switchDefenceMode", "saasSwitch", "control/arm", "control/disarm",
                     "/v3/devconfig/", "remote/unlock", "set_area_defence_mode")
        for path in list(APP.rglob("*.kt")) + list(CORE.rglob("*.kt")):
            source = path.read_text()
            for token in forbidden:
                self.assertNotIn(token, source, f"{path} must not reference {token}")

    def test_arm_contract_is_pinned(self):
        api = (CORE / "HikApi.kt").read_text()
        for token in ("CtrlSubSystemArmList", "CtrlSubSystemDisarmList",
                      "SetSubSystemFaultArmConfirm", "SearchSubSystemArmStatus",
                      "additionInfo", "ENAuthentication", "userType"):
            self.assertIn(token, api)
        self.assertIn('mode != DefenceMode.DISARM', api)
        self.assertIn("mode.armType", api)

    def test_modes_are_closed(self):
        state = (CORE / "ArmState.kt").read_text()
        self.assertIn("enum class DefenceMode", state)
        self.assertIn('STAY("stay")', state)
        self.assertIn('AWAY("away")', state)
        self.assertIn("DISARM(null)", state)

    def test_client_exposes_no_generic_command(self):
        client = (CORE / "HikClient.kt").read_text()
        for method in ("fun login(", "fun refresh(", "fun ensureFresh(", "fun devices(",
                       "fun setDefenceMode(", "fun readArmState("):
            self.assertIn(method, client)
        for verb in ("fun arm(", "fun disarm(", "fun bypass(", "fun unlock(", "fun control("):
            self.assertNotIn(verb, client)


class PersonalDefaultsTest(unittest.TestCase):
    def test_no_panel_identity_is_shipped(self):
        api = (CORE / "HikApi.kt").read_text()
        self.assertIn('DEFAULT_EN_USER = ""', api)
        store = (APP / "ArmStateStore.kt").read_text()
        self.assertIn('DEFAULT_SERIAL = ""', store)


class CredentialTest(unittest.TestCase):
    def test_session_is_encrypted_with_keystore(self):
        store = (APP / "SecureSessionStore.kt").read_text()
        self.assertIn("AndroidKeyStore", store)
        self.assertIn("SessionCipher.encrypt", store)
        self.assertIn("Base64.NO_WRAP", store)
        self.assertIn("check(prefs.edit()", store)

    def test_password_is_never_persisted(self):
        login = (APP / "LoginActivity.kt").read_text()
        self.assertIn("takeChars()", login)
        self.assertIn("password.fill(", login)
        store = (APP / "SecureSessionStore.kt").read_text()
        self.assertNotIn("password", store.lower())

    def test_secret_screens_and_fields_are_hardened(self):
        for screen in ("LoginActivity.kt", "DiagnosticsActivity.kt"):
            self.assertIn("FLAG_SECURE", (APP / screen).read_text())
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

    def test_release_is_not_debuggable_and_backup_is_off(self):
        build = (ROOT / "app/build.gradle.kts").read_text()
        self.assertIn("isDebuggable = false", build)
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
        self.assertIn('android:allowBackup="false"', manifest)
        self.assertIn('android:usesCleartextTraffic="false"', manifest)
        rules = (ROOT / "app/src/main/res/xml/backup_rules.xml").read_text()
        self.assertIn("device-transfer", rules)
        self.assertIn('domain="sharedpref"', rules)


class WidgetTest(unittest.TestCase):
    def test_widget_only_offers_the_three_modes(self):
        layout = (ROOT / "app/src/main/res/layout/widget_arm.xml").read_text()
        for view_id in ("widget_hit_stay", "widget_hit_away", "widget_hit_disarm", "widget_hit_open"):
            self.assertIn(f'@+id/{view_id}', layout)
        widget = (APP / "ArmWidget.kt").read_text()
        for action in ("ACTION_STAY", "ACTION_AWAY", "ACTION_DISARM"):
            self.assertIn(action, widget)

    def test_widget_reads_state_from_cloud_and_push(self):
        widget = (APP / "ArmWidget.kt").read_text()
        self.assertIn("readArmState", widget)
        self.assertIn("refreshFromCloud", widget)
        listener = (APP / "HikNotificationListener.kt").read_text()
        self.assertIn("NotificationInterpreter.interpret", listener)


if __name__ == "__main__":
    unittest.main()
