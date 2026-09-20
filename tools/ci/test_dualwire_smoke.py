import contextlib
import importlib.util
import io
from pathlib import Path
import unittest
from unittest.mock import Mock, call, patch


spec = importlib.util.spec_from_file_location("dualwire_smoke", Path(__file__).with_name("dualwire-smoke.py"))
dualwire = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dualwire)


class ArgumentTest(unittest.TestCase):
    REQUIRED = ["--serial", "emulator-5554", "--output", "build/emulator-results",
                "--manager", "manager.apk", "--compat", "compat.apk", "--native", "native.apk",
                "--legacy", "legacy.apk", "--shizuku", "shizuku.apk", "--bridge", "bridge.apk",
                "--unlisted", "unlisted.apk"]

    def parse(self, *extra):
        return dualwire.parse_args(self.REQUIRED + list(extra))

    def rejected(self, *extra):
        stderr = io.StringIO()
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(stderr):
            self.parse(*extra)
        return stderr.getvalue()

    def test_the_shared_workflow_arguments_and_this_suite_s_own_are_both_accepted(self):
        args = self.parse()
        self.assertEqual((args.manager, args.compat, args.native, args.legacy, args.shizuku),
                         (Path("manager.apk"), Path("compat.apk"), Path("native.apk"),
                          Path("legacy.apk"), Path("shizuku.apk")))
        self.assertEqual((args.bridge, args.unlisted), (Path("bridge.apk"), Path("unlisted.apk")))
        self.assertIsNone(args.cases)

    def test_an_omitted_apk_is_rejected(self):
        stderr = io.StringIO()
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(stderr):
            dualwire.parse_args(self.REQUIRED[:-2])
        self.assertIn("--unlisted", stderr.getvalue())

    def test_the_flag_repeats_into_one_selection(self):
        self.assertEqual(self.parse("--case", "setup", "--case", "visibility-listed-manager").cases,
                         ["setup", "visibility-listed-manager"])

    def test_a_selection_without_setup_is_rejected(self):
        self.assertIn("--case setup is required", self.rejected("--case", "selection-prefers-porter"))

    def test_a_selection_dropping_a_case_a_later_one_needs_is_rejected(self):
        # Only shizuku-permission-lifecycle installs Shizuku and starts its server, so without it
        # selection-prefers-porter asserts a preference on a device holding one backend.
        message = self.rejected("--case", "setup", "--case", "selection-prefers-porter")
        self.assertIn("shizuku-permission-lifecycle", message)

    def test_a_selection_dropping_the_server_the_cold_start_case_kills_is_rejected(self):
        # secondary-before-delivery kills a Shizuku server and starts it again, and only
        # shizuku-permission-lifecycle installs Shizuku and brings one up in the first place.
        message = self.rejected("--case", "setup", "--case", "secondary-before-delivery")
        self.assertIn("shizuku-permission-lifecycle", message)

    def test_a_selection_dropping_the_porter_connection_the_duplicate_case_needs_is_rejected(self):
        # Against a live Shizuku connection the backend guard refuses the probe's redelivery on its
        # own, so duplicate-delivery asserts nothing unless selection-prefers-porter ran first.
        message = self.rejected("--case", "setup", "--case", "duplicate-delivery")
        self.assertIn("selection-prefers-porter", message)

    def test_a_selection_leaving_porter_installed_for_a_later_case_is_rejected(self):
        # selection-prefers-porter leaves the manager installed and its server up, and only
        # selection-porter-stopped removes them, so without it the recovery case waits out its
        # timeout on BACKEND SHIZUKU against a device that answers with Porter.
        message = self.rejected("--case", "setup", "--case", "shizuku-permission-lifecycle",
                                "--case", "selection-prefers-porter",
                                "--case", "multiprocess-delivery-and-recovery")
        self.assertIn("selection-porter-stopped", message)

    def test_a_selection_that_never_installs_porter_keeps_the_recovery_case(self):
        selection = ["setup", "shizuku-permission-lifecycle", "multiprocess-delivery-and-recovery"]
        self.assertEqual(self.parse(*[arg for name in selection for arg in ("--case", name)]).cases,
                         selection)

    def test_a_selection_ending_at_the_case_that_installs_porter_is_accepted(self):
        selection = ["setup", "shizuku-permission-lifecycle", "selection-prefers-porter"]
        self.assertEqual(self.parse(*[arg for name in selection for arg in ("--case", name)]).cases,
                         selection)

    def test_a_selection_naming_the_whole_chain_is_accepted(self):
        chain = ["setup", "shizuku-permission-lifecycle", "selection-prefers-porter",
                 "selection-porter-stopped"]
        self.assertEqual(self.parse(*[arg for name in chain for arg in ("--case", name)]).cases,
                         chain)

    def test_an_unknown_case_is_rejected_and_names_the_valid_ones(self):
        message = self.rejected("--case", "setup", "--case", "dualwire")
        self.assertIn("dualwire", message)
        for name in dualwire.CASES:
            self.assertIn(name, message)


class LaunchBridgeTest(unittest.TestCase):
    def setUp(self):
        self.runner = dualwire.Dualwire.__new__(dualwire.Dualwire)
        self.runner.shell = Mock(return_value="")
        self.runner.until = Mock(return_value="4711")
        self.runner.expect_log = Mock()
        self.runner.logs = Mock(return_value="")

    def starts(self):
        return [c for c in self.runner.shell.call_args_list
                if c.args[0] == "am" and c.args[1] == "start"]

    def stops(self):
        return [c for c in self.runner.shell.call_args_list if c.args[1] == "force-stop"]

    def test_only_the_bridge_package_is_stopped_before_its_own_activity_starts(self):
        self.runner.launch_bridge()
        self.assertEqual(self.stops(), [call("am", "force-stop", dualwire.BRIDGE)])
        self.assertEqual(self.starts(), [call(
            "am", "start", "-W", "-n", dualwire.BRIDGE + "/eu.darken.porter.probe.ProbeActivity",
            timeout=dualwire.base.LAUNCH_TIMEOUT)])
        self.assertEqual(self.runner.probe_pid, "4711")

    def test_an_expected_binder_is_waited_for_after_the_mode_line(self):
        self.runner.launch_bridge()
        self.assertEqual(self.runner.expect_log.call_args_list, [
            call(dualwire.BRIDGE, "MODE daemon=false peek=false"),
            call(dualwire.BRIDGE, "BINDER uid=2000 version="),
        ])

    @patch.object(dualwire.time, "sleep")
    def test_no_expected_binder_settles_and_asserts_absence_instead_of_waiting_for_a_line(self, sleep):
        self.runner.launch_bridge(expect_binder=False)
        self.runner.expect_log.assert_called_once_with(
            dualwire.BRIDGE, "MODE daemon=false peek=false")
        sleep.assert_called_once_with(dualwire.NO_BINDER_SETTLE)
        self.runner.logs.assert_called_once_with()

    @patch.object(dualwire.time, "sleep")
    def test_a_binder_that_did_arrive_fails_the_launch_that_expected_none(self, sleep):
        self.runner.logs = Mock(return_value=dualwire.BRIDGE + " BINDER uid=2000 version=13\n")
        with self.assertRaises(AssertionError):
            self.runner.launch_bridge(expect_binder=False)

    @patch.object(dualwire.time, "sleep")
    def test_a_launch_that_expected_no_binder_stops_the_package_it_leaves_behind(self, sleep):
        # A probe left running keeps its sticky binder listeners registered, so a server a later
        # case starts pushes a binder into it and that process answers a permission dialog this
        # suite is no longer watching. Stopping it before the next launch is too late: the push
        # happens in between.
        order = Mock()
        order.attach_mock(self.runner.shell, "shell")
        order.attach_mock(self.runner.logs, "logs")
        self.runner.launch_bridge(expect_binder=False)
        self.assertEqual(order.mock_calls[-2:],
                         [call.logs(), call.shell("am", "force-stop", dualwire.BRIDGE)],
                         "the no-binder branch must force-stop the bridge after asserting that no "
                         "binder arrived, not only before the launch")
        self.assertEqual(self.stops(), [call("am", "force-stop", dualwire.BRIDGE)] * 2)

    def test_an_extra_rides_behind_the_component_it_is_passed_to(self):
        self.runner.launch_bridge(extras=("--ez", "redeliver", "true"))
        self.assertEqual(self.starts(), [call(
            "am", "start", "-W", "-n", dualwire.BRIDGE + "/eu.darken.porter.probe.ProbeActivity",
            "--ez", "redeliver", "true", timeout=dualwire.base.LAUNCH_TIMEOUT)])

    def test_the_activity_names_the_component_that_starts(self):
        self.runner.launch_bridge(activity=".SecondaryActivity")
        self.assertEqual(self.starts(), [call(
            "am", "start", "-W", "-n",
            dualwire.BRIDGE + "/eu.darken.porter.probe.SecondaryActivity",
            timeout=dualwire.base.LAUNCH_TIMEOUT)])


class ProcessLogTest(unittest.TestCase):
    MAIN = "4711"
    SECONDARY = "4712"

    def setUp(self):
        self.runner = dualwire.Dualwire.__new__(dualwire.Dualwire)
        self.buffers = {
            self.MAIN: [dualwire.BRIDGE + " MODE daemon=false peek=false",
                        dualwire.BRIDGE + " BINDER uid=2000 version=13"],
            self.SECONDARY: [dualwire.BRIDGE + " SECONDARY STARTED",
                             dualwire.BRIDGE + " SECONDARY BINDER uid=2000 backend=SHIZUKU"],
        }
        self.runner.adb = Mock(side_effect=lambda *args: "\n".join(
            self.buffers[next(a for a in args if a.startswith("--pid=")).removeprefix("--pid=")]))

    def test_each_process_reads_only_its_own_lines(self):
        self.assertIn("BINDER uid=2000 version=13", self.runner.logs_for(self.MAIN))
        self.assertNotIn("SECONDARY", self.runner.logs_for(self.MAIN))
        self.assertIn("SECONDARY BINDER", self.runner.logs_for(self.SECONDARY))
        self.assertEqual(self.runner.adb.call_args_list[0],
                         call("logcat", "-d", "--pid=4711", "-s", "PorterProbe:I", "*:S"))

    def test_a_line_from_before_the_boundary_is_not_a_fresh_one(self):
        boundary = self.runner.since(self.MAIN)
        with patch.object(dualwire.time, "sleep"), \
                patch.object(dualwire.time, "monotonic", side_effect=[0, 0, 31]):
            with self.assertRaisesRegex(AssertionError, "Timed out"):
                self.runner.fresh(self.MAIN, boundary, "BINDER uid=2000 version=")

    def test_the_same_line_logged_again_after_the_boundary_is_fresh(self):
        boundary = self.runner.since(self.MAIN)
        self.buffers[self.MAIN].append(dualwire.BRIDGE + " BINDER_DEAD")
        self.buffers[self.MAIN].append(dualwire.BRIDGE + " BINDER uid=2000 version=13")
        with patch.object(dualwire.time, "sleep"), \
                patch.object(dualwire.time, "monotonic", side_effect=[0, 1]):
            self.assertTrue(self.runner.fresh(self.MAIN, boundary, "BINDER uid=2000 version="))

    def test_one_process_s_boundary_does_not_hide_the_other_s_lines(self):
        boundary = self.runner.since(self.SECONDARY)
        self.buffers[self.SECONDARY].append(dualwire.BRIDGE + " SECONDARY BINDER_DEAD")
        with patch.object(dualwire.time, "sleep"), \
                patch.object(dualwire.time, "monotonic", side_effect=[0, 1]):
            self.assertTrue(self.runner.fresh(self.SECONDARY, boundary, "SECONDARY BINDER_DEAD"))

    def test_lines_the_buffer_dropped_from_the_front_do_not_replay_as_new(self):
        boundary = [str(line) for line in range(4)]
        # The first two aged out while the last two stayed, so only "4" is new.
        self.assertEqual(dualwire.added(boundary, ["2", "3", "4"]), ["4"])
        self.assertEqual(dualwire.added(boundary, boundary), [])
        self.assertEqual(dualwire.added([], ["0"]), ["0"])


class ShizukuPermissionLifecycleTest(unittest.TestCase):
    """The permission case's call order, harvested by running run() with case() stubbed.

    Coupled to case bodies staying inside run(): if they ever move into methods, this needs
    rewriting rather than adjusting.
    """

    REVOKE = call.shell("pm", "revoke", dualwire.BRIDGE, dualwire.SHIZUKU_PERMISSION)

    def setUp(self):
        self.runner = dualwire.Dualwire.__new__(dualwire.Dualwire)
        self.runner.args = Mock()
        # launch_bridge is mocked away here, so the pid it would have recorded is set directly.
        self.runner.probe_pid = "4711"
        bodies = {}
        self.runner.case = lambda name, action, restore=(): bodies.setdefault(name, action)
        self.order = Mock()
        for name, mock in (
                ("shell", Mock(return_value="")),
                ("adb", Mock(return_value="")),
                ("pid", Mock(return_value="4711")),
                ("logs", Mock(return_value="")),
                # The real until() calls its condition, so running it here records on the parent
                # which process each wait polls.
                ("until", Mock(side_effect=lambda description, condition, **kwargs:
                               condition() or "4711")),
                ("start_service", Mock()),
                ("launch_bridge", Mock()),
                ("tap", Mock()),
                ("expect_log", Mock()),
                ("authorized", Mock())):
            setattr(self.runner, name, mock)
            self.order.attach_mock(mock, name)
        self.runner.run()
        bodies["shizuku-permission-lifecycle"]()

    def between_the_revoke_and_the_relaunch(self):
        calls = self.order.mock_calls
        revoked = calls.index(self.REVOKE)
        relaunched = next(index for index, entry in enumerate(calls[revoked:], revoked)
                          if entry[0] == "launch_bridge")
        return calls[revoked + 1:relaunched]

    def test_the_revoked_client_and_its_user_service_are_waited_out_before_the_relaunch(self):
        # pm revoke returns before Android has killed the revoked uid. Relaunching inside that
        # window lets the queued kill land on the replacement process instead, and the assertion
        # that follows sits outside launch_bridge's retry loop, so the case times out rather than
        # retrying.
        # A wait's own arguments are not the subject here, the process its condition polls is.
        polled = [(name, args[0] if name == "pid" else None)
                  for name, args, _ in self.between_the_revoke_and_the_relaunch()]
        self.assertEqual(polled, [
            ("until", None), ("pid", dualwire.BRIDGE),
            ("until", None), ("pid", dualwire.BRIDGE + ":porter-probe"),
        ], "between the revoke and the relaunch the case must wait for the client process and then "
           "for its user service to go away, so that the revoke's queued uid kill cannot land on "
           "the process the relaunch starts")

    def test_each_of_those_waits_holds_while_its_process_is_still_there(self):
        # Which process a wait polls says nothing about which answer it waits for, and a wait for
        # the process to appear is satisfied by the very process the kill is still queued against.
        conditions = [c.args[1] for c in self.between_the_revoke_and_the_relaunch()
                      if c[0] == "until"]
        self.assertEqual(len(conditions), 2,
                         "the revoke is not followed by two waits before the relaunch")
        for index, condition in enumerate(conditions):
            self.runner.pid = Mock(return_value="4711")
            self.assertFalse(condition(), f"wait {index} is over while its process is still alive")
            self.runner.pid = Mock(return_value="")
            self.assertTrue(condition(), f"wait {index} never ends once its process is gone")


class MockedDevice:
    """One emulator's worth of state, enough to run a whole case body against.

    A case body is a closure run() declares, so it has to close over this runner: run() is called
    with case() replaced by a collector, which records every body without executing any of them.
    The body then drives the same mocked primitives LaunchBridgeTest attaches, over a process table
    and one probe log buffer instead of a device.
    """

    PORTER_SERVER = "400"
    SHIZUKU_SERVER = "300"
    SECONDARY = "4712"

    def __init__(self, processes=(), launches=(), on_start_service=None, on_launch=None,
                 on_keyevent=None, on_tap=None, buffer=(), buffers=()):
        self.processes = dict(processes)
        self.launches = list(launches)
        self.buffer = list(buffer)
        self.buffers = {pid: list(lines) for pid, lines in dict(buffers).items()}
        self.bodies = {}
        self.on_start_service = on_start_service
        self.on_launch = on_launch
        self.on_keyevent = on_keyevent
        self.on_tap = on_tap
        runner = dualwire.Dualwire.__new__(dualwire.Dualwire)
        runner.args = Mock()
        runner.adb = Mock(return_value="")
        runner.shell = Mock(side_effect=self.shell)
        runner.tap = Mock(side_effect=self.tap)
        runner.pid = Mock(side_effect=lambda name: self.processes.get(name, ""))
        runner.logs = Mock(side_effect=lambda: "\n".join(self.buffer))
        runner.logs_for = Mock(side_effect=self.logs_for)
        runner.until = Mock(side_effect=self.until)
        runner.start_service = Mock(side_effect=self.start_service)
        runner.launch_bridge = Mock(side_effect=self.launch_bridge)
        runner.case = lambda name, action, restore=(): self.bodies.__setitem__(name, action)
        self.runner = runner
        runner.run()

    def logs_for(self, pid):
        """A pid given no buffer of its own reads the launched probe's, which is the one process
        this device models unless a case needs a second."""
        return "\n".join(self.buffers.get(pid, self.buffer))

    def tap(self, *args, **kwargs):
        """A dialog no case answers has no effect here; one a case answers gets its hook."""
        if self.on_tap:
            self.on_tap(self)

    def shell(self, *args, **kwargs):
        # Revoking the permission kills the client uid, and the non-daemon user service goes with
        # it when its last client does.
        if args[:2] == ("pm", "revoke"):
            self.processes.pop(dualwire.BRIDGE, None)
            self.processes.pop(dualwire.BRIDGE + ":porter-probe", None)
        # Backing out destroys the activity, and its teardown unbinds the user service. The client
        # process stays, because nothing stopped it.
        if args[:2] == ("input", "keyevent"):
            if self.on_keyevent:
                self.on_keyevent(self.processes)
            else:
                self.processes.pop(dualwire.BRIDGE + ":porter-probe", None)
        if args[:2] == ("am", "force-stop"):
            for name in [n for n in self.processes
                         if n == args[2] or n.startswith(args[2] + ":")]:
                self.processes.pop(name)
        if args[:2] == ("kill", "-9"):
            for name in [n for n, pid in self.processes.items() if pid == args[2]]:
                self.processes.pop(name)
        if args[:2] == ("am", "start") and str(args[-1]).endswith(".SecondaryActivity"):
            self.processes[dualwire.BRIDGE + ":secondary"] = self.SECONDARY
        return ""

    def until(self, description, condition, timeout=None):
        # This device changes only when a case body calls something, so a condition that is false
        # now stays false however long it is polled.
        value = condition()
        if value:
            return value
        raise AssertionError("Timed out: " + description)

    def start_service(self, package=dualwire.base.MANAGER):
        porter = package == dualwire.base.MANAGER
        self.processes["porter_server" if porter else "shizuku_server"] = (
            self.PORTER_SERVER if porter else self.SHIZUKU_SERVER)
        if self.on_start_service:
            self.on_start_service(self.processes, package)

    def launch_bridge(self, activity=".ProbeActivity", expect_binder=True, extras=()):
        """A fresh probe process, whose buffer holds that launch's lines and nothing older."""
        self.runner.probe_pid = "4711"
        self.buffer = [dualwire.BRIDGE + " MODE daemon=false peek=false", *self.launches.pop(0)]
        if self.on_launch:
            self.on_launch(self.processes)

    def run_case(self, name):
        return self.bodies[name]()


class CaseBodyTest(unittest.TestCase):
    """Each case body, run against a device that satisfies the property it claims and against one
    that breaks it. A body that passes both is not asserting the property."""

    PORTER_BINDER = f"BINDER uid=2000 version={dualwire.base.PORTER_PROTOCOL_VERSION}"
    SHIZUKU_BINDER = f"BINDER uid=2000 version={dualwire.base.SHIZUKU_API_VERSION}"
    USER_SERVICE = "USER_SERVICE uid=2000 file=" + dualwire.base.PAYLOAD

    def lines(self, *messages):
        return [dualwire.BRIDGE + " " + message for message in messages]

    def selection_device(self, on_start_service=None, on_launch=None):
        return MockedDevice(
            processes={"shizuku_server": MockedDevice.SHIZUKU_SERVER},
            launches=[self.lines("BACKEND PORTER", self.PORTER_BINDER)],
            on_start_service=on_start_service, on_launch=on_launch)

    def test_a_porter_startup_that_removes_shizuku_fails_the_selection_case(self):
        # The case exists to observe a process that is offered both binders taking Porter's. On a
        # device holding one backend there is no choice to observe, so the case has to notice that
        # the Shizuku server it recorded at entry is gone by the time the probe chose.
        self.assertEqual(self.selection_device().run_case("selection-prefers-porter"),
                         {"porter_pid": MockedDevice.PORTER_SERVER,
                          "shizuku_pid": MockedDevice.SHIZUKU_SERVER})

        def porter_replaces_shizuku(processes, package):
            if package == dualwire.base.MANAGER:
                processes.pop("shizuku_server", None)

        with self.assertRaisesRegex(
                AssertionError, "Shizuku",
                msg="selection-prefers-porter passed on a device where starting Porter removed "
                    "the Shizuku server, so it reported a preference over a backend that was no "
                    "longer running and returned the pid it read before the removal"):
            self.selection_device(porter_replaces_shizuku).run_case("selection-prefers-porter")

        # Surviving the startup is not enough: the pid has to still be there once the probe has
        # logged its choice, which is a second reading rather than a second use of the first.
        with self.assertRaisesRegex(
                AssertionError, "Shizuku",
                msg="selection-prefers-porter passed on a device whose Shizuku server survived "
                    "Porter's startup and then exited while the probe was choosing, so nothing "
                    "was offering the binder the probe is said to have turned down"):
            self.selection_device(
                on_launch=lambda processes: processes.pop("shizuku_server", None)
            ).run_case("selection-prefers-porter")

    def lifecycle_device(self, post_revoke, on_keyevent=None):
        def launched(processes):
            # A launch brings the client process back, and its bind brings the user service with it.
            processes[dualwire.BRIDGE] = "4711"
            processes[dualwire.BRIDGE + ":porter-probe"] = "500"

        device = MockedDevice(
            launches=[
                self.lines("DENIED"),
                self.lines(self.SHIZUKU_BINDER, "BACKEND SHIZUKU",
                           "AUTHORIZED managerOperationDenied=", self.USER_SERVICE),
                self.lines(*post_revoke),
            ],
            on_launch=launched, on_keyevent=on_keyevent)
        device.processes[dualwire.BRIDGE + ":porter-probe"] = "500"
        return device

    def test_a_post_revoke_relaunch_that_binds_no_user_service_fails_the_lifecycle_case(self):
        # ProbeActivity logs AUTHORIZED before it binds the user service, so AUTHORIZED alone says
        # only that the probe was told it may bind. The fact the revoke is meant to pin is that the
        # bind still succeeds, which only the USER_SERVICE line shows.
        # What this cannot reach: that ProbeActivity reports AUTHORIZED before bindUserService
        # returns lives in the probe's Java, so if that ordering changed this would keep passing
        # while the case stopped meaning what its name says.
        healthy = self.lifecycle_device(
            (self.SHIZUKU_BINDER, "AUTHORIZED managerOperationDenied=", self.USER_SERVICE))
        self.assertEqual(healthy.run_case("shizuku-permission-lifecycle"),
                         {"shizuku_pid": MockedDevice.SHIZUKU_SERVER,
                          "unbound_user_service_pid": "500"})

        unbound = self.lifecycle_device(
            (self.SHIZUKU_BINDER, "AUTHORIZED managerOperationDenied="))
        with self.assertRaisesRegex(
                AssertionError, "USER_SERVICE",
                msg="shizuku-permission-lifecycle passed on a device whose post-revoke relaunch "
                    "logged AUTHORIZED and then bound no user service, so its tail checks that "
                    "the probe was permitted to bind rather than that the bind worked"):
            unbound.run_case("shizuku-permission-lifecycle")

    def test_a_probe_that_died_with_its_user_service_fails_the_unbind_tail(self):
        # Every other path stops the probe with am force-stop, which kills the process before
        # onDestroy runs, so the service goes away because its client died: the mechanism the
        # revoke earlier in the same case already covers. The tail is evidence of unbinding only
        # while the client process is still there once the service is gone.
        def stops_the_package(processes):
            processes.pop(dualwire.BRIDGE, None)
            processes.pop(dualwire.BRIDGE + ":porter-probe", None)

        died = self.lifecycle_device(
            (self.SHIZUKU_BINDER, "AUTHORIZED managerOperationDenied=", self.USER_SERVICE),
            on_keyevent=stops_the_package)
        with self.assertRaisesRegex(
                AssertionError, "force-stop",
                msg="shizuku-permission-lifecycle passed on a device where finishing the activity "
                    "took the whole package with it, so its tail read a dead client as an unbind"):
            died.run_case("shizuku-permission-lifecycle")

    def grants_the_porter_permission(self, device):
        """Answering the dialog runs connect() again in the process that is already up, which logs
        its own BINDER line ahead of the two lines authorized() waits for."""
        device.buffer.extend(self.lines(self.PORTER_BINDER,
                                        "AUTHORIZED managerOperationDenied=true",
                                        self.USER_SERVICE))

    def duplicate_device(self, *lines, on_tap=None):
        # The case enters on selection-prefers-porter's probe, which reported its connection and
        # then stopped at the permission dialog, so that is the buffer the grant lands in.
        return MockedDevice(processes={"porter_server": MockedDevice.PORTER_SERVER},
                            launches=[self.lines(*lines)],
                            buffer=self.lines(self.PORTER_BINDER, "BACKEND PORTER"),
                            on_tap=on_tap or self.grants_the_porter_permission)

    def test_a_dialog_answer_that_never_grants_fails_the_duplicate_case(self):
        # tap returns once the tap is dispatched, not once the server has recorded the grant. A
        # case that launched on that alone would force-stop the probe the grant has to reach and
        # count a launch whose probe stops at the prompt, never redelivering anything.
        with self.assertRaisesRegex(
                AssertionError, "AUTHORIZED",
                msg="duplicate-delivery passed on a device where answering the Porter permission "
                    "dialog never authorized the probe, so it counted a launch made before the "
                    "grant it needs had landed"):
            self.duplicate_device(self.PORTER_BINDER, "BACKEND PORTER",
                                  "AUTHORIZED managerOperationDenied=true", "REDELIVERED",
                                  self.USER_SERVICE,
                                  on_tap=lambda device: None).run_case("duplicate-delivery")

    @patch.object(dualwire.time, "sleep")
    def test_a_redelivery_that_opened_a_second_connection_fails_the_duplicate_case(self, sleep):
        once = self.duplicate_device(self.PORTER_BINDER, "BACKEND PORTER",
                                     "AUTHORIZED managerOperationDenied=true", "REDELIVERED",
                                     self.USER_SERVICE)
        self.assertEqual(once.run_case("duplicate-delivery"),
                         {"binder_lines": [dualwire.BRIDGE + " " + self.PORTER_BINDER]})

        # connect() logs a BINDER line every time it runs, so a redelivery that was attached to
        # rather than refused shows up as a second one.
        twice = self.duplicate_device(self.PORTER_BINDER, "BACKEND PORTER",
                                      "AUTHORIZED managerOperationDenied=true", "REDELIVERED",
                                      self.PORTER_BINDER, self.USER_SERVICE)
        with self.assertRaisesRegex(
                AssertionError, "second time",
                msg="duplicate-delivery passed on a device whose probe announced a second "
                    "connection over the binder it already held"):
            twice.run_case("duplicate-delivery")

    @staticmethod
    def reaches_the_secondary(device):
        device.buffers[MockedDevice.SECONDARY].append(
            dualwire.BRIDGE + " SECONDARY BINDER uid=2000 backend=SHIZUKU")

    def secondary_device(self, *, already_delivered=(), on_push=None):
        device = MockedDevice(
            processes={"shizuku_server": MockedDevice.SHIZUKU_SERVER, dualwire.BRIDGE: "4711"},
            buffers={MockedDevice.SECONDARY: self.lines(
                "SECONDARY STARTED", "SECONDARY AVAILABILITY INSTALLED_NOT_CONNECTED",
                *already_delivered)})
        push = on_push or self.reaches_the_secondary
        device.on_start_service = lambda processes, package: push(device)
        return device

    @patch.object(dualwire.time, "sleep")
    def test_the_cold_start_case_pins_both_halves_of_the_secondary_delivery(self, sleep):
        self.assertEqual(self.secondary_device().run_case("secondary-before-delivery"),
                         {"secondary_pid": MockedDevice.SECONDARY,
                          "killed_server_pid": MockedDevice.SHIZUKU_SERVER})

        early = self.secondary_device(
            already_delivered=("SECONDARY BINDER uid=2000 backend=SHIZUKU",))
        with self.assertRaisesRegex(
                AssertionError, "no server running",
                msg="secondary-before-delivery passed on a device whose secondary already held a "
                    "binder before any server was started, so the empty first fetch it claims to "
                    "observe never happened"):
            early.run_case("secondary-before-delivery")

        with self.assertRaisesRegex(
                AssertionError, "Timed out",
                msg="secondary-before-delivery passed on a device where starting the server never "
                    "reached the secondary process"):
            self.secondary_device(on_push=lambda device: None).run_case("secondary-before-delivery")

        def replaces_the_secondary(device):
            self.reaches_the_secondary(device)
            device.processes[dualwire.BRIDGE + ":secondary"] = "9999"

        with self.assertRaisesRegex(
                AssertionError, "replaced",
                msg="secondary-before-delivery passed on a device that restarted the secondary "
                    "process to deliver into it, which is a fresh fetch rather than a push"):
            self.secondary_device(
                on_push=replaces_the_secondary).run_case("secondary-before-delivery")


if __name__ == "__main__":
    unittest.main()
