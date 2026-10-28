"""tools/order.py against a fake TV that answers content call like Android's `content` tool.

No connection to a real TV.
"""
import contextlib
import io
import os
import tempfile
import unittest
from unittest.mock import patch

from tools import order

YT = "com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity"
TIVI = "ar.tvplayer.tv/.ui.MainActivity"
NF = "com.netflix.ninja/.MainActivity"
SELF = "com.gathin.tvhome/.MainActivity"


class FakeTv:
    def __init__(self, get):
        self.get = get
        self.commands = []

    def shell(self, command, read_timeout_s=None):
        self.commands.append(command)
        if "--method get" in command:
            body = self.get
        elif "--method set" in command:
            body = "unknown=, saved=2"
        else:
            body = ""
        return f"Result: Bundle[{{{body}}}]\n__CYT_ORDER_RC__0\n"

    def close(self):
        pass


class CallTest(unittest.TestCase):

    def test_a_name_with_a_comma_does_not_cut_the_value(self):
        tv = FakeTv(f"stale=, labels={YT}\tYouTube, Kids=1\n{TIVI}\tTiviMate, order={YT}\n{TIVI}")
        fields = order.call(tv, "get")
        self.assertEqual(fields["order"], f"{YT}\n{TIVI}")
        self.assertEqual(fields["labels"], f"{YT}\tYouTube, Kids=1\n{TIVI}\tTiviMate")
        self.assertEqual(fields["stale"], "")


class RenderTest(unittest.TestCase):

    def test_saved_first_with_names_then_the_rest_without_the_launcher(self):
        text = order.render({"order": f"{TIVI}\n{YT}", "stale": "",
                             "labels": f"{TIVI}\tTiviMate\n{YT}\tYouTube\n{NF}\tNetflix\n{SELF}\tCleanYourTV"})
        rows = [l for l in text.splitlines() if not l.startswith("#")]
        self.assertEqual(rows, [f"{TIVI}  # TiviMate", f"{YT}  # YouTube", f"{NF}  # Netflix"])
        self.assertEqual(order.entries(text), [TIVI, YT, NF])

    def test_a_stale_order_is_listed_and_flagged(self):
        text = order.render({"order": "", "stale": YT, "labels": f"{YT}\tYouTube\n{NF}\tNetflix"})
        self.assertIn("no longer applies", text)
        self.assertEqual(order.entries(text), [YT, NF])


class MainTest(unittest.TestCase):

    def run_main(self, args, tv, home):
        out = io.StringIO()
        with patch.dict(os.environ, {"TV_HOST": "192.168.1.5"}), \
                patch("os.path.expanduser", return_value=home), \
                patch.dict("sys.modules", {"tv": type("tv", (), {"connect": staticmethod(lambda: tv)})}), \
                patch("sys.argv", ["order.py", *args]), contextlib.redirect_stdout(out):
            code = order.main()
        return code, out.getvalue()

    def test_set_copies_the_previous_order_first(self):
        with tempfile.TemporaryDirectory() as home:
            wanted = os.path.join(home, "mine.txt")
            with open(wanted, "w", encoding="utf-8") as f:
                f.write(f"{NF}  # Netflix\ncom.limelight\n")
            tv = FakeTv(f"order={TIVI}\n{YT}, stale=, labels={TIVI}\tTiviMate\n{YT}\tYouTube")
            code, out = self.run_main(["set", wanted], tv, home)
            self.assertEqual(code, 0)
            copies = os.listdir(os.path.join(home, ".cleanyourtv"))
            self.assertEqual(len(copies), 1)
            self.assertTrue(copies[0].startswith("order-192.168.1.5-"))
            with open(os.path.join(home, ".cleanyourtv", copies[0]), encoding="utf-8") as f:
                self.assertEqual(order.entries(f.read()), [TIVI, YT])
            self.assertIn("Previous order copied", out)
            # The copy is taken before the set, and the set sends the typed list.
            self.assertIn("--method get", tv.commands[0])
            self.assertIn(f"--arg '{NF},com.limelight'", tv.commands[1])

    def test_no_copy_when_nothing_was_saved(self):
        with tempfile.TemporaryDirectory() as home:
            tv = FakeTv(f"order=, stale=, labels={YT}\tYouTube")
            code, out = self.run_main(["clear"], tv, home)
            self.assertEqual(code, 0)
            self.assertFalse(os.path.exists(os.path.join(home, ".cleanyourtv")))
            self.assertNotIn("Previous order copied", out)

    def test_a_line_that_is_not_an_app_stops_before_the_tv(self):
        with tempfile.TemporaryDirectory() as home:
            wanted = os.path.join(home, "mine.txt")
            with open(wanted, "w", encoding="utf-8") as f:
                f.write("com.netflix.ninja; rm -rf /\n")
            tv = FakeTv("order=")
            with contextlib.redirect_stderr(io.StringIO()):
                code, _ = self.run_main(["set", wanted], tv, home)
            self.assertEqual(code, 1)
            self.assertEqual(tv.commands, [])


if __name__ == "__main__":
    unittest.main()
