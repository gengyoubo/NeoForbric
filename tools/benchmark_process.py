"""Own the entire client process tree, including after the JVM exits."""
import ctypes
import os
import signal
import subprocess


class ProcessTree:
    def __init__(self, command, cwd, output):
        self.process = None
        self.job = None
        if os.name == "nt":
            self._windows_start(command, cwd, output)
        else:
            self.process = subprocess.Popen(command, cwd=cwd, stdout=output,
                                            stderr=subprocess.STDOUT, start_new_session=True)

    def _windows_start(self, command, cwd, output):
        from ctypes import wintypes as w

        class BasicLimits(ctypes.Structure):
            _fields_ = [("processTime", ctypes.c_int64), ("jobTime", ctypes.c_int64),
                        ("flags", w.DWORD), ("minWorkingSet", ctypes.c_size_t),
                        ("maxWorkingSet", ctypes.c_size_t), ("activeProcesses", w.DWORD),
                        ("affinity", ctypes.c_size_t), ("priority", w.DWORD), ("scheduling", w.DWORD)]

        class IoCounters(ctypes.Structure):
            _fields_ = [(name, ctypes.c_uint64) for name in
                        ("readOps", "writeOps", "otherOps", "readBytes", "writeBytes", "otherBytes")]

        class ExtendedLimits(ctypes.Structure):
            _fields_ = [("basic", BasicLimits), ("io", IoCounters),
                        ("processMemory", ctypes.c_size_t), ("jobMemory", ctypes.c_size_t),
                        ("peakProcessMemory", ctypes.c_size_t), ("peakJobMemory", ctypes.c_size_t)]

        class ThreadEntry(ctypes.Structure):
            _fields_ = [("size", w.DWORD), ("usage", w.DWORD), ("threadId", w.DWORD),
                        ("processId", w.DWORD), ("basePriority", w.LONG),
                        ("deltaPriority", w.LONG), ("flags", w.DWORD)]

        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        signatures = {
            "CreateJobObjectW": ([ctypes.c_void_p, w.LPCWSTR], w.HANDLE),
            "SetInformationJobObject": ([w.HANDLE, ctypes.c_int, ctypes.c_void_p, w.DWORD], w.BOOL),
            "AssignProcessToJobObject": ([w.HANDLE, w.HANDLE], w.BOOL),
            "TerminateJobObject": ([w.HANDLE, w.UINT], w.BOOL),
            "CloseHandle": ([w.HANDLE], w.BOOL),
            "CreateToolhelp32Snapshot": ([w.DWORD, w.DWORD], w.HANDLE),
            "Thread32First": ([w.HANDLE, ctypes.POINTER(ThreadEntry)], w.BOOL),
            "Thread32Next": ([w.HANDLE, ctypes.POINTER(ThreadEntry)], w.BOOL),
            "OpenThread": ([w.DWORD, w.BOOL, w.DWORD], w.HANDLE),
            "ResumeThread": ([w.HANDLE], w.DWORD),
        }
        for name, (args, result) in signatures.items():
            function = getattr(kernel, name)
            function.argtypes, function.restype = args, result
        self.kernel = kernel
        self.job = kernel.CreateJobObjectW(None, None)
        if not self.job:
            raise ctypes.WinError(ctypes.get_last_error())
        try:
            limits = ExtendedLimits()
            limits.basic.flags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
            if not kernel.SetInformationJobObject(self.job, 9, ctypes.byref(limits), ctypes.sizeof(limits)):
                raise ctypes.WinError(ctypes.get_last_error())
            # Suspend before assigning the job: even very early mod subprocesses
            # must inherit ownership. Popen closes the initial thread handle.
            self.process = subprocess.Popen(command, cwd=cwd, stdout=output,
                                            stderr=subprocess.STDOUT, creationflags=0x204)  # suspended, separate control group
            if not kernel.AssignProcessToJobObject(self.job, w.HANDLE(self.process._handle)):
                raise ctypes.WinError(ctypes.get_last_error())
            snapshot = kernel.CreateToolhelp32Snapshot(0x4, 0)  # TH32CS_SNAPTHREAD
            if snapshot == ctypes.c_void_p(-1).value:
                raise ctypes.WinError(ctypes.get_last_error())
            try:
                entry = ThreadEntry()
                entry.size = ctypes.sizeof(entry)
                available = kernel.Thread32First(snapshot, ctypes.byref(entry))
                while available:
                    if entry.processId == self.process.pid:
                        thread = kernel.OpenThread(0x2, False, entry.threadId)
                        if not thread:
                            raise ctypes.WinError(ctypes.get_last_error())
                        try:
                            if kernel.ResumeThread(thread) == 0xFFFFFFFF:
                                raise ctypes.WinError(ctypes.get_last_error())
                        finally:
                            kernel.CloseHandle(thread)
                        break
                    available = kernel.Thread32Next(snapshot, ctypes.byref(entry))
                else:
                    raise RuntimeError("Cannot find suspended client thread")
            finally:
                kernel.CloseHandle(snapshot)
        except BaseException:
            self.close()
            raise

    def close(self):
        error = None
        if os.name == "nt" and self.job:
            if not self.kernel.TerminateJobObject(self.job, 1):
                error = ctypes.WinError(ctypes.get_last_error())
            if not self.kernel.CloseHandle(self.job):
                error = ctypes.WinError(ctypes.get_last_error())
            self.job = None
        elif self.process and os.name != "nt":
            try:
                os.killpg(self.process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        if self.process:
            if self.process.poll() is None:
                self.process.kill()
            self.process.wait(timeout=10)
        if error:
            raise error
