---
name: PTY echo configuration in pty_helper.c
description: The PTY terminal flags setup in pty_helper.c — echo must be explicitly enabled or typed input is invisible
---

The PTY is configured via `termios` in `app/src/main/cpp/pty_helper.c`.

**Rule:** `c_lflag` MUST include `ECHO | ECHOE | ECHOK` or all typed input is silently swallowed (like a password prompt). The original code intentionally omitted these flags — this was a bug causing typed text to be invisible.

**Why:** `forkpty()` takes a `termios` struct. If ECHO flags are absent, the PTY slave never echoes characters back to the master fd, so xterm.js never sees what the user types. The shell still receives and runs the command, but nothing appears in the terminal.

**How to apply:** Any future change to `c_lflag` in `pty_helper.c` must preserve `ECHO | ECHOE | ECHOK` unless deliberately disabling echo for a specific use case (e.g. password input UI).
