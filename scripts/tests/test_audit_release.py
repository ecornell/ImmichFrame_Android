import contextlib
import importlib.util
import io
import json
import pathlib
import sys
import tempfile
import unittest
from unittest import mock


SCRIPT = pathlib.Path(__file__).parents[1] / "audit-release.py"
SPEC = importlib.util.spec_from_file_location("audit_release", SCRIPT)
audit_release = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(audit_release)


class AuditReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = pathlib.Path(self.temp.name)
        self.target = root / "target.apk"
        self.rollback = root / "rollback.apk"
        self.target.write_bytes(b"target")
        self.rollback.write_bytes(b"rollback")
        self.metadata = root / "metadata.json"
        self.qualification = root / "qualification.json"
        self.metadata.write_text(json.dumps({
            "schemaVersion": 1,
            "releaseId": "v54-test",
            "sourceCommit": "abc123",
            "applicationId": "com.immichframe.immichframe.debug",
            "versionCode": 54,
            "versionName": "1.0.54.0-debug",
            "apkSha256": audit_release.sha256(self.target),
            "signerSha256": "signer",
            "copiedTo": str(self.target),
            "validation": {"status": "passed"},
            "rollback": {
                "path": str(self.rollback),
                "versionCode": 53,
                "versionName": "1.0.53.0-debug",
                "apkSha256": audit_release.sha256(self.rollback),
                "signerSha256": "signer",
            },
        }), encoding="utf-8")
        self.qualification.write_text(json.dumps({
            "networkRecovery": "passed",
            "sleepWakeCycles": 10,
            "soakHours": 24,
            "evidence": ["history.csv"],
        }), encoding="utf-8")

    def tearDown(self):
        self.temp.cleanup()

    def run_audit(self, git_status=""):
        def fake_git(*args):
            return git_status if args == ("status", "--porcelain") else "abc123"

        argv = [str(SCRIPT), "--metadata", str(self.metadata),
                "--qualification", str(self.qualification)]
        def fake_inspect(path):
            if path == self.target:
                return {"applicationId": "com.immichframe.immichframe.debug",
                        "versionCode": 54, "versionName": "1.0.54.0-debug", "signerSha256": "signer"}
            return {"applicationId": "com.immichframe.immichframe.debug",
                    "versionCode": 53, "versionName": "1.0.53.0-debug", "signerSha256": "signer"}

        with mock.patch.object(sys, "argv", argv), mock.patch.object(audit_release, "git", fake_git), \
                mock.patch.object(audit_release, "inspect_apk", fake_inspect):
            with contextlib.redirect_stdout(io.StringIO()) as output:
                code = audit_release.main()
        return code, json.loads(output.getvalue())

    def test_ready_with_complete_evidence(self):
        code, report = self.run_audit()
        self.assertEqual(0, code)
        self.assertEqual("ready", report["status"])

    def test_dirty_tree_is_not_ready(self):
        code, report = self.run_audit(" M file")
        self.assertEqual(1, code)
        self.assertEqual("not-ready", report["status"])
        self.assertIn("source tree is dirty", report["failures"])

    def test_nan_soak_is_not_ready_for_qualification(self):
        evidence = json.loads(self.qualification.read_text(encoding="utf-8"))
        evidence["soakHours"] = float("nan")
        self.qualification.write_text(json.dumps(evidence), encoding="utf-8")
        code, report = self.run_audit()
        self.assertEqual(0, code)
        self.assertEqual("conditionally-ready", report["status"])
        self.assertIn("fewer than 24 soak hours are recorded", report["outstanding"])

    def test_nan_sleep_cycles_is_not_ready_for_qualification(self):
        evidence = json.loads(self.qualification.read_text(encoding="utf-8"))
        evidence["sleepWakeCycles"] = float("nan")
        self.qualification.write_text(json.dumps(evidence), encoding="utf-8")
        code, report = self.run_audit()
        self.assertEqual(0, code)
        self.assertEqual("conditionally-ready", report["status"])
        self.assertIn("fewer than 10 sleep/wake cycles are recorded", report["outstanding"])


if __name__ == "__main__":
    unittest.main()
