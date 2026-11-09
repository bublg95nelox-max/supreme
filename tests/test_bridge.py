"""
Tests for Lowuncher Python Bridge CLI.
"""
import sys
import json
import unittest
from io import StringIO
from unittest.mock import patch
from pathlib import Path

from core.bridge_cli import emit_event, main, handle_run
from core.sync_engine import SyncNetworkError
import argparse
import tempfile


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

    def test_invalid_server_port_rejected(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            args = argparse.Namespace(
                game_dir=tmp_dir,
                server_port=70000,
            )
            with self.assertRaises(ValueError) as cm:
                handle_run(args)
            self.assertIn("1..65535", str(cm.exception))

            args.server_port = 0
            with self.assertRaises(ValueError) as cm:
                handle_run(args)
            self.assertIn("1..65535", str(cm.exception))

    def test_sync_network_error_attributes(self):
        err = SyncNetworkError("Not Found", url="https://example.com/mod.jar", status_code=404, retryable=False)
        self.assertEqual(err.url, "https://example.com/mod.jar")
        self.assertEqual(err.status_code, 404)
        self.assertFalse(err.retryable)

        err2 = SyncNetworkError("Connection timed out", retryable=True)
        self.assertTrue(err2.retryable)


if __name__ == "__main__":
    unittest.main()
