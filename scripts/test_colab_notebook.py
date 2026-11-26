"""Validate notebook structure and compile every Python cell without allocating a runtime."""
import json
from pathlib import Path
import unittest
import tempfile
import sys


class ColabNotebookTest(unittest.TestCase):
    def test_notebook_code_compiles(self):
        path = Path(__file__).resolve().parents[1] / "notebooks/ExtrusionExtraction.ipynb"
        notebook = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(notebook["nbformat"], 4)
        cells = notebook["cells"]
        self.assertEqual(len({cell["id"] for cell in cells}), len(cells))
        for cell in cells:
            if cell["cell_type"] == "code":
                self.assertEqual(cell["outputs"], [])
                self.assertIsNone(cell["execution_count"])
                compile("".join(cell["source"]), cell["id"], "exec")

    def test_paired_worker_lifecycle(self):
        path = Path(__file__).resolve().parents[1] / "notebooks/ExtrusionExtraction.ipynb"
        notebook = json.loads(path.read_text(encoding="utf-8"))
        helper = next(cell for cell in notebook["cells"] if cell["id"] == "ee-session-helpers")
        namespace = {}
        exec(compile("".join(helper["source"]), "session-helper", "exec"), namespace)
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            output = directory / "output"
            output.mkdir()
            code = "0123456789abcdef0123456789abcdef"
            command = [sys.executable, "-c", "from pathlib import Path; import sys; Path(sys.argv[1]).write_bytes(b'fixture'); sys.exit(3)", str(output / "reconstruction.eeresult")]
            self.assertEqual(namespace["run_paired_job"](directory, code, output, command), 3)
            self.assertEqual(json.loads((directory / "status.json").read_text())["state"], "PARTIAL")
            self.assertEqual((directory / "reconstruction.eeresult").read_bytes(), b"fixture")
            with self.assertRaises(FileExistsError):
                namespace["run_paired_job"](directory, code, output, command)
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            (directory / "cancel.txt").write_text("EECANCEL1:" + code)
            self.assertEqual(namespace["run_paired_job"](directory, code, directory, ["must-not-launch"]), 130)
            self.assertEqual(json.loads((directory / "status.json").read_text())["state"], "CANCELLED")


if __name__ == "__main__":
    unittest.main()
