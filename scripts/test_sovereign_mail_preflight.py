import unittest
from unittest.mock import patch, MagicMock
from sovereign_mail_preflight import inspect

class PreflightTests(unittest.TestCase):
    @patch("sovereign_mail_preflight.socket.create_connection")
    @patch("sovereign_mail_preflight.socket.gethostbyaddr")
    @patch("sovereign_mail_preflight.socket.getaddrinfo")
    def test_pass(self, forward, reverse, connect):
        forward.return_value = [(None, None, None, None, ("192.0.2.1", 0))]
        reverse.return_value = ("tx.example.org", [], [])
        conn = connect.return_value.__enter__.return_value
        conn.makefile.return_value.__enter__.return_value.readline.return_value = b"220 ready\r\n"
        result = inspect("tx.example.org", "192.0.2.1", ["mx.example.org"])
        self.assertTrue(all(c["pass"] for c in result))
        connect.assert_called_once_with(("mx.example.org", 25), timeout=5)
        conn.sendall.assert_not_called()

    @patch("sovereign_mail_preflight.socket.create_connection", side_effect=TimeoutError("blocked"))
    @patch("sovereign_mail_preflight.socket.gethostbyaddr", return_value=("other.example.org", [], []))
    @patch("sovereign_mail_preflight.socket.getaddrinfo", side_effect=OSError("DNS failure"))
    def test_fail_closed(self, forward, reverse, connect):
        result = inspect("tx.example.org", "192.0.2.1", ["mx.example.org"])
        self.assertFalse(any(c["pass"] for c in result))

if __name__ == "__main__":
    unittest.main()
