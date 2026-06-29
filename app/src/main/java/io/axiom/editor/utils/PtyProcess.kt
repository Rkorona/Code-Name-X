package io.axiom.editor.utils

object PtyProcess {
    init {
        System.loadLibrary("pty-helper")
    }

    @JvmStatic external fun forkExecPty(
        cmd: Array<String>,
        env: Array<String>,
        workDir: String
    ): IntArray?

    @JvmStatic external fun resizePty(fd: Int, rows: Int, cols: Int)

    @JvmStatic external fun closeFd(fd: Int)

    @JvmStatic external fun killPid(pid: Int, sig: Int): Int
}
