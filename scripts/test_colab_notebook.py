"""Validate notebook structure and compile every Python cell without allocating a runtime."""
import json
from pathlib import Path
import unittest


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


if __name__ == "__main__":
    unittest.main()
