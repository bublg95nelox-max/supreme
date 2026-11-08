"""
Tests for Lowuncher Python Bridge CLI.
"""
import sys
import json
import unittest
from io import StringIO
from unittest.mock import patch
from pathlib import Path

from core.bridge_cli import emit_event, main


class TestBridgeCLI(unittest.TestCase):

    def test_emit_event(self):
        with patch("sys.stdout", new_callable=StringIO) as mock_stdout:
            emit_event("progress", {"percent": 50, "status": "Testing"})
            output = mock_stdout.getvalue().strip()
            self.assertTrue(output.startswith("[BRIDGE_EVENT] "))
            payload = json.loads(output[15:])
            self.assertEqual(payload["type"], "progress")
            self.assertEqual(payload["percent"], 50)
            self.assertEqual(payload["status"], "Testing")

    def test_cli_help(self):
        with patch("sys.argv", ["bridge_cli", "--help"]):
            with self.assertRaises(SystemExit) as cm:
                main()
            self.assertEqual(cm.exception.code, 0)


if __name__ == "__main__":
    unittest.main()
