"""Offline tests for native dependency staging, using real temporary Git repositories."""
import subprocess
import tempfile
import unittest
from pathlib import Path
from build_skintokens import checkout, verify_checkout


class CheckoutTest(unittest.TestCase):
    def test_pinned_checkout_and_dirty_rejection(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source, destination = root / "origin", root / "pinned"
            subprocess.run(["git", "init", "-q", str(source)], check=True)
            (source / "fixture").write_text("original")
            subprocess.run(["git", "-C", str(source), "add", "fixture"], check=True)
            subprocess.run(["git", "-C", str(source), "-c", "user.name=Test", "-c",
                            "user.email=test@example.invalid", "commit", "-qm", "fixture"], check=True)
            rev = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
            checkout(destination, source.as_uri(), rev)
            self.assertEqual((destination / "fixture").read_text(), "original")
            checkout(destination, source.as_uri(), rev)
            with self.assertRaisesRegex(RuntimeError, "Wrong revision"):
                verify_checkout(destination, "0" * 40)
            (destination / "fixture").write_text("modified")
            with self.assertRaisesRegex(RuntimeError, "dirty"):
                checkout(destination, source.as_uri(), rev)
            self.assertEqual((destination / "fixture").read_text(), "modified")
            failed = root / "failed"
            with self.assertRaises(subprocess.CalledProcessError):
                checkout(failed, source.as_uri(), "0" * 40)
            self.assertFalse(failed.exists())
            self.assertFalse(list(root.glob("fetch-*")))


if __name__ == "__main__":
    unittest.main()
