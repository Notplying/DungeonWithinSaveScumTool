"""Unit tests for save_manager logic (no device / no GUI needed)."""
import os
import subprocess
import sys
import unittest

import save_manager as sm


class TestParseDevices(unittest.TestCase):
    def test_connected(self):
        out = "List of devices attached\nlocalhost:7555\tdevice\n"
        self.assertEqual(sm.parse_adb_devices(out), ["localhost:7555"])

    def test_none(self):
        out = "List of devices attached\n\n"
        self.assertEqual(sm.parse_adb_devices(out), [])

    def test_offline_ignored(self):
        out = "List of devices attached\nlocalhost:7555\toffline\n"
        self.assertEqual(sm.parse_adb_devices(out), [])

    def test_unauthorized_ignored(self):
        out = "List of devices attached\nABCD1234\tunauthorized\n"
        self.assertEqual(sm.parse_adb_devices(out), [])


class TestCommands(unittest.TestCase):
    def test_backup_overwrites(self):
        cmd = sm.build_backup_shell()
        self.assertIn("cp -f", cmd)
        self.assertIn(sm.GAME_SAVE, cmd)
        self.assertIn(sm.BACKUP_SAVE, cmd)

    def test_restore_overwrites(self):
        cmd = sm.build_restore_shell()
        self.assertIn("cp -f", cmd)
        self.assertIn(sm.BACKUP_SAVE, cmd)
        self.assertIn(sm.GAME_SAVE, cmd)

    def test_restore_fallback_checks_both_dirs(self):
        cmd = sm.build_restore_shell_with_fallback()
        self.assertIn(sm.BACKUP_SAVE, cmd)
        self.assertIn(sm.BACKUP_SAVE_ALT, cmd)
        self.assertIn("NO_BACKUP_FOUND", cmd)


class TestGuiLayout(unittest.TestCase):
    """Regression test: every key widget must actually be laid out.

    Guards against the bug where card wrapper frames were created but
    never packed, leaving the backup/restore buttons invisible.
    Skipped automatically when no display is available.
    """

    def test_action_buttons_are_laid_out(self):
        try:
            import tkinter as tk
        except ImportError as exc:
            self.skipTest(f"tkinter unavailable: {exc}")
            return
        try:
            root = tk.Tk()
            root.withdraw()
        except tk.TclError as exc:
            self.skipTest(f"no display: {exc}")
            return
        # No-op the background worker: this test is about pure layout,
        # and it keeps the test deterministic (no threads, no adb).
        orig = sm.SaveManagerApp._run_in_thread
        sm.SaveManagerApp._run_in_thread = lambda self, func: None
        try:
            app = sm.SaveManagerApp(root)
            root.update()
            widgets = [app.backup_btn, app.restore_btn,
                       app.connect_btn, app.log]
            for widget in widgets:
                # Every ancestor up to the root must be geometry-managed.
                # (Unmanaged wrappers collapse to height 1 and are invisible.)
                w = widget
                while w is not root:
                    self.assertIn(w.winfo_manager(), ("pack", "grid", "place"),
                                        f"{w} is not laid out - invisible!")
                    w = w.master
        finally:
            sm.SaveManagerApp._run_in_thread = orig
            root.destroy()

    def test_device_selector_and_theme_toggle(self):
        import tempfile
        try:
            import tkinter as tk
        except ImportError as exc:
            self.skipTest(f"tkinter unavailable: {exc}")
            return
        try:
            root = tk.Tk()
            root.withdraw()
        except tk.TclError as exc:
            self.skipTest(f"no display: {exc}")
            return
        # Isolate config writes (device + theme) in a temp dir and
        # restore the globals afterwards. No-ops the background worker
        # for determinism (no threads, no adb).
        real_path = sm._config_path
        real_device = sm.get_device()
        real_theme = sm.get_theme()
        orig = sm.SaveManagerApp._run_in_thread
        sm.SaveManagerApp._run_in_thread = lambda self, func: None
        try:
            with tempfile.TemporaryDirectory() as tmp:
                sm._config_path = lambda: os.path.join(tmp, "config.json")  # type: ignore[assignment]
                sm._current_theme = "dark"
                sm._current_device = sm.DEFAULT_DEVICE
                app = sm.SaveManagerApp(root)
                root.update()
                # Dark theme is the default: window + cards render dark.
                self.assertEqual(sm.get_theme(), "dark")
                self.assertEqual(root.cget("background"), sm.DARK_THEME["BG"])
                self.assertEqual(app.log.cget("background"),
                                 sm.DARK_THEME["CARD"])
                # Selector lists every preset plus Custom.
                self.assertEqual(len(app.preset_box["values"]),
                                 len(sm.EMULATOR_PRESETS) + 1)
                # Live toggle to light repaints chrome, toggle back to dark.
                app.apply_theme("light")
                root.update()
                self.assertEqual(root.cget("background"), sm.LIGHT_THEME["BG"])
                self.assertEqual(app.log.cget("background"),
                                 sm.LIGHT_THEME["CARD"])
                app.apply_theme("dark")
                root.update()
                self.assertEqual(root.cget("background"), sm.DARK_THEME["BG"])
                # Preset hides the custom row, Custom shows it.
                app.preset_var.set(app.preset_box["values"][0])
                app._on_preset_selected()
                self.assertEqual(sm.get_device(), sm.EMULATOR_PRESETS[0][1])
                self.assertEqual(app.custom_row.winfo_manager(), "")
                app.preset_var.set(sm.CUSTOM_LABEL)
                app._on_preset_selected()
                self.assertEqual(app.custom_row.winfo_manager(), "pack")
                app.custom_entry_var.set("192.168.1.10:5555")
                app._connect_custom()
                self.assertEqual(sm.get_device(), "192.168.1.10:5555")
                self.assertIn("192.168.1.10:5555", app.caption_var.get())
        finally:
            sm._config_path = real_path
            sm._current_device = real_device
            sm._current_theme = real_theme
            sm.SaveManagerApp._run_in_thread = orig
            root.destroy()


class TestNoConsoleWindow(unittest.TestCase):
    """Every adb call must hide its console window (Windows)."""

    def test_kwargs_hide_window_on_windows(self):
        kwargs = sm._subprocess_kwargs()
        if sys.platform == "win32":
            self.assertEqual(kwargs.get("creationflags"),
                             subprocess.CREATE_NO_WINDOW)
        else:
            self.assertEqual(kwargs, {})

    def test_run_adb_forwards_hide_window_flag(self):
        captured: dict = {}

        class FakeProc:
            returncode = 0
            stdout = ""
            stderr = ""

        real_run = subprocess.run

        def fake_run(cmd, **kwargs):
            captured.update(kwargs)
            return FakeProc()

        subprocess.run = fake_run  # type: ignore[assignment]
        try:
            sm._invalidate_serial_cache()
            sm.run_adb("devices")
        finally:
            subprocess.run = real_run
            sm._invalidate_serial_cache()
        if sys.platform == "win32":
            self.assertEqual(captured.get("creationflags"),
                             subprocess.CREATE_NO_WINDOW)


class TestSerialCache(unittest.TestCase):
    """`adb devices` must be queried once, then cached briefly."""

    def test_target_serial_caches_devices_query(self):
        calls: list = []

        class FakeProc:
            returncode = 0
            stdout = "List of devices attached\nlocalhost:7555\tdevice\n"
            stderr = ""

        real_run = subprocess.run

        def fake_run(cmd, **kwargs):
            calls.append(cmd)
            return FakeProc()

        # Pin the device: _target_serial follows the configured device,
        # which otherwise depends on ambient config.json state.
        real_device = sm._current_device
        sm._current_device = "localhost:7555"
        subprocess.run = fake_run  # type: ignore[assignment]
        try:
            sm._invalidate_serial_cache()
            first = sm._target_serial()
            second = sm._target_serial()
        finally:
            subprocess.run = real_run
            sm._invalidate_serial_cache()
            sm._current_device = real_device
        self.assertEqual(first, "localhost:7555")
        self.assertEqual(second, "localhost:7555")
        self.assertEqual(len(calls), 1)

    def test_invalidate_forces_requery(self):
        real_run = subprocess.run
        calls: list = []

        class FakeProc:
            returncode = 0
            stdout = "List of devices attached\n"
            stderr = ""

        def fake_run(cmd, **kwargs):
            calls.append(cmd)
            return FakeProc()

        subprocess.run = fake_run  # type: ignore[assignment]
        try:
            sm._invalidate_serial_cache()
            sm._target_serial()
            sm._invalidate_serial_cache()
            sm._target_serial()
        finally:
            subprocess.run = real_run
            sm._invalidate_serial_cache()
        self.assertEqual(len(calls), 2)


class TestRestoreFlow(unittest.TestCase):
    """Restore must close the game, copy, then relaunch - in that order."""

    def _fake_run_adb(self, calls: list, *, backup_present: bool = True,
                      monkey_rc: int = 0):
        def fake(*args, timeout=30):
            calls.append(args)

            class FakeProc:
                returncode = 0
                stdout = ""
                stderr = ""

            proc = FakeProc()
            if args == ("devices",):
                proc.stdout = "List of devices attached\nlocalhost:7555\tdevice\n"
            elif args[0] == "shell":
                body = args[1]
                if body.startswith("test -f"):
                    proc.stdout = "EXISTS" if backup_present else ""
                elif body.startswith("pidof"):
                    proc.stdout = "12345"
                elif body.startswith("ls -l"):
                    proc.stdout = "-rw-rw---- 1 u0_a16 ext_data_rw 20532 save.es3"
                elif "monkey" in body:
                    proc.returncode = monkey_rc
                    proc.stdout = "Events injected: 1"
            return proc
        return fake

    def _run_restore(self, calls: list, **kwargs):
        real_run = sm.run_adb
        sm.run_adb = self._fake_run_adb(calls, **kwargs)  # type: ignore[assignment]
        try:
            sm._invalidate_serial_cache()
            return sm.do_restore()
        finally:
            sm.run_adb = real_run
            sm._invalidate_serial_cache()

    def test_force_stop_targets_package(self):
        self.assertIn(sm.PACKAGE, sm.build_force_stop_shell())
        self.assertTrue(sm.build_force_stop_shell().startswith("am force-stop"))

    def test_launch_targets_package(self):
        cmd = sm.build_launch_shell()
        self.assertIn(sm.PACKAGE, cmd)
        self.assertIn("monkey", cmd)

    def test_restore_closes_copies_then_relaunches(self):
        calls: list = []
        ok, msg = self._run_restore(calls)
        self.assertTrue(ok, msg)
        kinds = [" ".join(a) for a in calls]
        idx_stop = next(i for i, k in enumerate(kinds) if "force-stop" in k)
        idx_copy = next(i for i, k in enumerate(kinds) if "cp -f" in k)
        idx_launch = next(i for i, k in enumerate(kinds) if "monkey" in k)
        self.assertLess(idx_stop, idx_copy)
        self.assertLess(idx_copy, idx_launch)
        self.assertIn(sm.PACKAGE, kinds[idx_stop])
        self.assertIn(sm.PACKAGE, kinds[idx_launch])
        self.assertIn("elaunch", msg)

    def test_restore_without_backup_does_not_close_game(self):
        calls: list = []
        ok, msg = self._run_restore(calls, backup_present=False)
        self.assertFalse(ok)
        self.assertNotIn("force-stop", " ".join(" ".join(a) for a in calls))
        self.assertNotIn("monkey", " ".join(" ".join(a) for a in calls))
        self.assertIn("No backup found", msg)

    def test_restore_reports_when_relaunch_fails(self):
        calls: list = []
        ok, msg = self._run_restore(calls, monkey_rc=1)
        self.assertTrue(ok)  # the save itself was still restored
        self.assertIn("manually", msg)


class TestDeviceSelection(unittest.TestCase):
    """Custom device address: presets, persistence, explicit connect."""

    def test_presets_are_valid_host_port(self):
        self.assertTrue(sm.EMULATOR_PRESETS)
        names = [name for name, _ in sm.EMULATOR_PRESETS]
        self.assertEqual(len(names), len(set(names)))
        for name, addr in sm.EMULATOR_PRESETS:
            self.assertTrue(name, "preset needs a display name")
            self.assertRegex(addr, r"^[\w.\-]+:\d+$", f"bad preset address: {addr}")

    def test_default_device_kept_for_compatibility(self):
        self.assertEqual(sm.DEFAULT_DEVICE, "localhost:7555")

    def test_load_falls_back_to_default(self):
        real_path = sm._config_path
        sm._config_path = lambda: r"Z:\definitely\not\here\config.json"  # type: ignore[assignment]
        try:
            self.assertEqual(sm._load_device(), sm.DEFAULT_DEVICE)
        finally:
            sm._config_path = real_path

    def test_set_get_roundtrip_persists(self):
        import tempfile
        real_path = sm._config_path
        real_device = sm.get_device()
        with tempfile.TemporaryDirectory() as tmp:
            sm._config_path = lambda: os.path.join(tmp, "config.json")  # type: ignore[assignment]
            try:
                self.assertEqual(sm.set_device("  127.0.0.1:5555  "), "127.0.0.1:5555")
                self.assertEqual(sm.get_device(), "127.0.0.1:5555")
                # A fresh load reads the persisted file.
                self.assertEqual(sm._load_device(), "127.0.0.1:5555")
            finally:
                sm._config_path = real_path
                # Restore the global directly: set_device() would write
                # through to the real config file as a side effect.
                sm._current_device = real_device
                sm._invalidate_serial_cache()

    def _fake_run_adb(self, calls: list, *, visible: tuple = ()):
        def fake(*args, timeout=30):
            calls.append(args)

            class FakeProc:
                returncode = 0
                stdout = ""
                stderr = ""

            proc = FakeProc()
            if args[0] == "connect":
                proc.stdout = f"connected to {args[1]}"
            elif args == ("devices",):
                proc.stdout = "List of devices attached\n" + "".join(
                    f"{s}\tdevice\n" for s in visible)
            return proc
        return fake

    def _run_connect(self, address: str, calls: list, **kwargs):
        real_run = sm.run_adb
        sm.run_adb = self._fake_run_adb(calls, **kwargs)  # type: ignore[assignment]
        try:
            sm._invalidate_serial_cache()
            return sm.connect_to(address)
        finally:
            sm.run_adb = real_run
            sm._invalidate_serial_cache()

    def test_connect_to_reachable_address(self):
        calls: list = []
        ok, msg = self._run_connect("127.0.0.1:5555", calls,
                                    visible=("127.0.0.1:5555",))
        self.assertTrue(ok, msg)
        self.assertIn("127.0.0.1:5555", msg)
        self.assertTrue(any(a[0] == "connect" for a in calls))

    def test_connect_to_unreachable_address(self):
        calls: list = []
        ok, msg = self._run_connect("127.0.0.1:59999", calls, visible=())
        self.assertFalse(ok)
        self.assertIn("Could not connect", msg)

    def test_connect_to_bare_serial_needs_no_dial(self):
        calls: list = []
        ok, msg = self._run_connect("emulator-5554", calls,
                                    visible=("emulator-5554",))
        self.assertTrue(ok, msg)
        self.assertFalse(any(a[0] == "connect" for a in calls))

    def test_connect_to_missing_serial(self):
        calls: list = []
        ok, msg = self._run_connect("emulator-5554", calls, visible=())
        self.assertFalse(ok)
        self.assertIn("host:port", msg)

    def test_connect_rejects_blank(self):
        ok, _ = sm.connect_to("   ")
        self.assertFalse(ok)


class TestTheme(unittest.TestCase):
    """Light/dark themes share keys; dark is the default."""

    def test_themes_share_key_sets(self):
        self.assertEqual(set(sm.LIGHT_THEME), set(sm.DARK_THEME))
        self.assertTrue(sm.LIGHT_THEME)  # non-empty

    def test_dark_is_default(self):
        self.assertEqual(sm.DEFAULT_THEME, "dark")
        real_path = sm._config_path
        sm._config_path = lambda: r"Z:\definitely\not\here\config.json"  # type: ignore[assignment]
        try:
            self.assertEqual(sm._load_theme(), "dark")
        finally:
            sm._config_path = real_path

    def test_set_get_roundtrip_persists(self):
        import tempfile
        real_path = sm._config_path
        real_theme = sm.get_theme()
        with tempfile.TemporaryDirectory() as tmp:
            sm._config_path = lambda: os.path.join(tmp, "config.json")  # type: ignore[assignment]
            try:
                self.assertEqual(sm.set_theme("light"), "light")
                self.assertEqual(sm.get_theme(), "light")
                self.assertEqual(sm.set_theme("bogus"), "dark")
                self.assertEqual(sm.get_theme(), "dark")
            finally:
                sm._config_path = real_path
                # Restore the global directly: set_theme() would write
                # through to the real config file as a side effect.
                sm._current_theme = real_theme


if __name__ == "__main__":
    unittest.main()
