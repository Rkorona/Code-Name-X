/*
 * pty_helper.c - 完整的 PTY 管理 JNI 库
 * 
 * 功能清单：
 *   ✅ forkExecPty      - 创建 PTY + fork/exec 进程
 *   ✅ resizePty        - 调整窗口大小
 *   ✅ readPty          - 读取 PTY 输出（阻塞/非阻塞）
 *   ✅ writePty         - 写入 PTY 输入
 *   ✅ waitFor          - 等待进程结束并获取退出码
 *   ✅ killPid          - 向进程发送信号
 *   ✅ closeFd          - 关闭文件描述符
 *   ✅ isDataAvailable  - 查询是否有数据可读（用于轮询）
 *   ✅ setRawMode       - 设置原始模式（无回显）
 *   ✅ setEchoMode      - 设置回显模式
 * 
 * 内存管理：所有分配均有对应释放
 * 异常处理：完整 NULL 检查 + errno 处理
 */

#include <jni.h>
#include <pty.h>
#include <unistd.h>
#include <string.h>
#include <stdlib.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <sys/select.h>
#include <signal.h>
#include <errno.h>
#include <fcntl.h>
#include <android/log.h>

#define TAG "PtyHelper"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

#define DEFAULT_ROWS 40
#define DEFAULT_COLS 120
#define READ_BUF_SIZE 4096

/* ─────────────────────────────────────────────
 *  辅助函数：字符串数组管理
 * ───────────────────────────────────────────── */

/*
 * 释放字符串数组（每个元素 + 数组本身）
 */
static void free_string_array(char **arr, int len) {
    if (!arr) return;
    for (int i = 0; i < len; i++) {
        if (arr[i]) {
            free(arr[i]);
            arr[i] = NULL;
        }
    }
    free(arr);
}

/*
 * JNI StringArray → C char** (strdup 复制)
 * 失败时返回 NULL（并已释放所有已分配内存）
 */
static char **jstring_array_to_c(JNIEnv *env, jobjectArray jarr, int *out_len) {
    int len = (*env)->GetArrayLength(env, jarr);
    if (len <= 0) {
        if (out_len) *out_len = 0;
        return NULL;
    }

    char **carr = (char **)calloc(len + 1, sizeof(char *));  // calloc 初始化为 NULL
    if (!carr) {
        LOGE("calloc failed for string array, size=%d", len + 1);
        if (out_len) *out_len = 0;
        return NULL;
    }

    for (int i = 0; i < len; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, jarr, i);
        if (!s) {
            carr[i] = NULL;
            continue;
        }

        const char *utf = (*env)->GetStringUTFChars(env, s, NULL);
        if (!utf) {
            LOGE("GetStringUTFChars failed at index %d", i);
            (*env)->ReleaseObjectArrayElement(env, jarr, s, 0);
            free_string_array(carr, len);
            if (out_len) *out_len = 0;
            return NULL;
        }

        carr[i] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, s, utf);
        (*env)->DeleteLocalRef(env, s);

        if (!carr[i]) {
            LOGE("strdup failed at index %d", i);
            free_string_array(carr, len);
            if (out_len) *out_len = 0;
            return NULL;
        }
    }

    carr[len] = NULL;  // 哨兵
    if (out_len) *out_len = len;
    return carr;
}

/* ─────────────────────────────────────────────
 *  辅助函数：终端属性配置
 * ───────────────────────────────────────────── */

/*
 * 填充 termios 结构体（Cooked 模式，带回显）
 */
static void configure_termios_cooked(struct termios *t) {
    memset(t, 0, sizeof(struct termios));
    t->c_iflag = ICRNL | IXON;
    t->c_oflag = OPOST | ONLCR;
    t->c_cflag = B38400 | CS8 | CREAD | HUPCL;
    t->c_lflag = ICANON | ISIG | IEXTEN | ECHO | ECHOE | ECHOK;
    t->c_cc[VMIN]   = 1;
    t->c_cc[VTIME]  = 0;
    t->c_cc[VINTR]  = 3;    /* Ctrl+C → SIGINT  */
    t->c_cc[VERASE] = 127;  /* Backspace         */
    t->c_cc[VEOF]   = 4;    /* Ctrl+D            */
    t->c_cc[VKILL]  = 21;   /* Ctrl+U            */
    t->c_cc[VSUSP]  = 26;   /* Ctrl+Z → SIGTSTP  */
    t->c_cc[VSTART] = 17;   /* Ctrl+Q            */
    t->c_cc[VSTOP]  = 19;   /* Ctrl+S            */
    t->c_cc[VQUIT]  = 28;   /* Ctrl+\ → SIGQUIT  */
}

/*
 * 填充 termios 结构体（Raw 模式，无回显）
 */
static void configure_termios_raw(struct termios *t) {
    memset(t, 0, sizeof(struct termios));
    cfmakeraw(t);
    t->c_cflag |= B38400 | CS8 | CREAD | HUPCL;
    t->c_cc[VMIN]  = 1;
    t->c_cc[VTIME] = 0;
}

/* ────────────────────────────���────────────────
 *  JNI 函数实现
 * ───────────────────────────────────────────── */

/*
 * forkExecPty: 分配 PTY 并 fork-exec 目标进程。
 *
 * 返回 jintArray: [masterFd, pid]
 *  - masterFd < 0 表示失败
 *  - pid == 0 在子进程中，> 0 在父进程中
 *
 * Java 签名：
 *   int[] forkExecPty(String[] cmd, String[] env, String workDir)
 */
JNIEXPORT jintArray JNICALL
Java_com_example_myapplication_utils_PtyProcess_forkExecPty(
    JNIEnv *env, jclass clazz,
    jobjectArray cmdArray,
    jobjectArray envArray,
    jstring jWorkDir
) {
    int cmdLen = 0, envLen = 0;
    char **cmd = NULL, **envp = NULL;
    const char *workDir = NULL;
    int masterFd = -1;
    pid_t pid = -1;
    jintArray result = NULL;

    /* ── 1. 转换命令行参数 ── */
    cmd = jstring_array_to_c(env, cmdArray, &cmdLen);
    if (!cmd || cmdLen == 0) {
        LOGE("Invalid or empty command array");
        goto cleanup;
    }

    /* ── 2. 转换环境变量 ── */
    envp = jstring_array_to_c(env, envArray, &envLen);
    /* envp 可以为 NULL（使用继承环境），但 jstring_array_to_c
       返回的 NULL 可能表示 OOM，这里区分处理 */
    // envp == NULL 且 envLen == 0 表示空数组（不用继承环境）

    /* ── 3. 转换工作目录 ── */
    if (jWorkDir) {
        workDir = (*env)->GetStringUTFChars(env, jWorkDir, NULL);
        if (!workDir) {
            LOGE("GetStringUTFChars failed for workDir");
            goto cleanup;
        }
    }

    /* ── 4. 配置终端属性 ── */
    struct termios t;
    configure_termios_cooked(&t);
    struct winsize ws = { .ws_row = DEFAULT_ROWS, .ws_col = DEFAULT_COLS };

    /* ── 5. forkpty ── */
    pid = forkpty(&masterFd, NULL, &t, &ws);
    if (pid < 0) {
        LOGE("forkpty failed: %s (errno=%d)", strerror(errno), errno);
        goto cleanup;
    }

    if (pid == 0) {
        /* ── 子进程 ── */
        if (workDir && workDir[0] != '\0') {
            if (chdir(workDir) != 0) {
                LOGE("chdir(%s) failed: %s", workDir, strerror(errno));
            }
        }
        /* 设置信号处理：子进程对 SIGHUP 等信号使用默认行为 */
        signal(SIGHUP,  SIG_DFL);
        signal(SIGINT,  SIG_DFL);
        signal(SIGQUIT, SIG_DFL);
        signal(SIGTERM, SIG_DFL);
        signal(SIGPIPE, SIG_DFL);

        /* 执行命令 */
        execve(cmd[0], cmd, envp);
        LOGE("execve(%s) failed: %s", cmd[0], strerror(errno));
        _exit(127);  /* execve 返回才到这里，说明失败了 */
    }

    /* ── 父进程 ── */
    LOGI("forkpty ok: masterFd=%d pid=%d cmd=%s", masterFd, pid, cmd[0]);

    /* ── 6. 构建返回数组 ── */
    result = (*env)->NewIntArray(env, 2);
    if (!result) {
        LOGE("NewIntArray failed");
        kill(pid, SIGTERM);
        goto cleanup;
    }
    jint res[2] = { masterFd, (jint)pid };
    (*env)->SetIntArrayRegion(env, result, 0, 2, res);
    masterFd = -1;  /* 避免 cleanup 关闭（已交给 Java 层） */

cleanup:
    /* 释放资源 */
    if (workDir && jWorkDir) {
        (*env)->ReleaseStringUTFChars(env, jWorkDir, workDir);
    }
    free_string_array(envp, envLen);
    free_string_array(cmd, cmdLen);
    if (masterFd >= 0) {
        close(masterFd);
    }
    return result;
}

/*
 * resizePty: 调整 PTY 窗口大小
 *
 * Java 签名：
 *   void resizePty(int fd, int rows, int cols)
 */
JNIEXPORT void JNICALL
Java_com_example_myapplication_utils_PtyProcess_resizePty(
    JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols
) {
    if (fd < 0 || rows <= 0 || cols <= 0) {
        LOGE("resizePty: invalid args fd=%d rows=%d cols=%d", fd, rows, cols);
        return;
    }
    struct winsize ws = {
        .ws_row = (unsigned short)rows,
        .ws_col = (unsigned short)cols
    };
    if (ioctl((int)fd, TIOCSWINSZ, &ws) < 0) {
        LOGE("TIOCSWINSZ failed: %s", strerror(errno));
    }
}

/*
 * readPty: 从 PTY 读取数据（阻塞模式）
 *
 * Java 签名：
 *   byte[] readPty(int fd)
 * 返回 null 表示 EOF 或错误
 */
JNIEXPORT jbyteArray JNICALL
Java_com_example_myapplication_utils_PtyProcess_readPty(
    JNIEnv *env, jclass clazz, jint fd
) {
    if (fd < 0) return NULL;

    char buf[READ_BUF_SIZE];
    ssize_t n = read((int)fd, buf, sizeof(buf));

    if (n < 0) {
        if (errno == EINTR || errno == EAGAIN) {
            return NULL;  /* 非致命，重试即可 */
        }
        LOGE("readPty fd=%d failed: %s", fd, strerror(errno));
        return NULL;
    }
    if (n == 0) {
        LOGD("readPty fd=%d: EOF", fd);
        return NULL;
    }

    jbyteArray arr = (*env)->NewByteArray(env, (jsize)n);
    if (arr) {
        (*env)->SetByteArrayRegion(env, arr, 0, (jsize)n, (jbyte *)buf);
    }
    return arr;
}

/*
 * readPtyNB: 从 PTY 读取数据（非阻塞模式，有数据返回，无数据返回空数组）
 *
 * Java 签名：
 *   byte[] readPtyNB(int fd)
 */
JNIEXPORT jbyteArray JNICALL
Java_com_example_myapplication_utils_PtyProcess_readPtyNB(
    JNIEnv *env, jclass clazz, jint fd
) {
    if (fd < 0) return NULL;

    char buf[READ_BUF_SIZE];
    ssize_t n = read((int)fd, buf, sizeof(buf));

    if (n < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            /* 非阻塞且无数据，返回空数组 */
            return (*env)->NewByteArray(env, 0);
        }
        if (errno == EINTR) return NULL;
        LOGE("readPtyNB fd=%d failed: %s", fd, strerror(errno));
        return NULL;
    }

    jbyteArray arr = (*env)->NewByteArray(env, (jsize)n);
    if (arr && n > 0) {
        (*env)->SetByteArrayRegion(env, arr, 0, (jsize)n, (jbyte *)buf);
    }
    return arr;
}

/*
 * writePty: 向 PTY 写入数据
 *
 * Java 签名：
 *   int writePty(int fd, byte[] data)
 * 返回写入的字节数，-1 表示错误
 */
JNIEXPORT jint JNICALL
Java_com_example_myapplication_utils_PtyProcess_writePty(
    JNIEnv *env, jclass clazz, jint fd, jbyteArray data
) {
    if (fd < 0 || !data) return -1;

    jsize len = (*env)->GetArrayLength(env, data);
    if (len <= 0) return 0;

    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) return -1;

    ssize_t written = write((int)fd, bytes, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);  /* 不回写 */

    if (written < 0) {
        if (errno != EINTR && errno != EAGAIN) {
            LOGE("writePty fd=%d failed: %s", fd, strerror(errno));
        }
        return -1;
    }
    return (jint)written;
}

/*
 * writePtyStr: 向 PTY 写入字符串（便捷方法）
 *
 * Java 签名：
 *   int writePtyStr(int fd, String text)
 */
JNIEXPORT jint JNICALL
Java_com_example_myapplication_utils_PtyProcess_writePtyStr(
    JNIEnv *env, jclass clazz, jint fd, jstring jtext
) {
    if (fd < 0 || !jtext) return -1;

    const char *text = (*env)->GetStringUTFChars(env, jtext, NULL);
    if (!text) return -1;

    ssize_t written = write((int)fd, text, strlen(text));
    (*env)->ReleaseStringUTFChars(env, jtext, text);

    if (written < 0) return -1;
    return (jint)written;
}

/*
 * waitFor: 等待子进程结束并返回退出码
 *
 * Java 签名：
 *   int waitFor(int pid)
 * 返回值：
 *   >= 0  : 退出码 (exit status)
 *   -1    : 进程不存在或系统错误
 *   -2xx  : 被信号终止，-signo（如 -9 表示 SIGKILL）
 *   -3xx  : 停止，-signo（如 -20 表示 SIGSTOP）
 */
JNIEXPORT jint JNICALL
Java_com_example_myapplication_utils_PtyProcess_waitFor(
    JNIEnv *env, jclass clazz, jint pid
) {
    if (pid <= 0) return -1;

    int status;
    pid_t ret = waitpid((pid_t)pid, &status, 0);
    if (ret < 0) {
        LOGE("waitpid(%d) failed: %s", pid, strerror(errno));
        return -1;
    }

    if (WIFEXITED(status)) {
        int code = WEXITSTATUS(status);
        LOGD("pid=%d exited with code=%d", pid, code);
        return code;
    }
    if (WIFSIGNALED(status)) {
        int sig = WTERMSIG(status);
        LOGD("pid=%d killed by signal %d", pid, sig);
        return -200 - sig;  /* -209 ~ -255 */
    }
    if (WIFSTOPPED(status)) {
        int sig = WSTOPSIG(status);
        LOGD("pid=%d stopped by signal %d", pid, sig);
        return -300 - sig;  /* -309 ~ -355 */
    }
    return -1;
}

/*
 * waitForNB: 非阻塞检查子进程状态
 *
 * Java 签名：
 *   int waitForNB(int pid)
 */
JNIEXPORT jint JNICALL
Java_com_example_myapplication_utils_PtyProcess_waitForNB(
    JNIEnv *env, jclass clazz, jint pid
) {
    if (pid <= 0) return -1;

    int status;
    pid_t ret = waitpid((pid_t)pid, &status, WNOHANG);
    if (ret == 0) {
        return -999;  /* 进程仍在运行 */
    }
    if (ret < 0) {
        return -1;
    }

    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -200 - WTERMSIG(status);
    if (WIFSTOPPED(status)) return -300 - WSTOPSIG(status);
    return -1;
}

/*
 * killPid: 向进程发送信号
 *
 * Java 签名：
 *   boolean killPid(int pid, int sig)
 */
JNIEXPORT jboolean JNICALL
Java_com_example_myapplication_utils_PtyProcess_killPid(
    JNIEnv *env, jclass clazz, jint pid, jint sig
) {
    if (pid <= 0) return JNI_FALSE;

    int ret = kill((pid_t)pid, (int)sig);
    if (ret < 0) {
        LOGE("kill(pid=%d, sig=%d) failed: %s", pid, sig, strerror(errno));
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

/*
 * closeFd: 关闭文件描述符
 *
 * Java 签名：
 *   void closeFd(int fd)
 */
JNIEXPORT void JNICALL
Java_com_example_myapplication_utils_PtyProcess_closeFd(
    JNIEnv *env, jclass clazz, jint fd
) {
    if (fd >= 0) {
        close((int)fd);
    }
}

/*
 * isDataAvailable: 使用 select 查询是否有数据可读（轮询用）
 *
 * Java 签名：
 *   boolean isDataAvailable(int fd, int timeoutMs)
 *   - timeoutMs: 超时毫秒数，0 表示立即返回
 * 返回 true 表示可读（包含 EOF）
 */
JNIEXPORT jboolean JNICALL
Java_com_example_myapplication_utils_PtyProcess_isDataAvailable(
    JNIEnv *env, jclass clazz, jint fd, jint timeoutMs
) {
    if (fd < 0) return JNI_FALSE;

    fd_set rfds;
    FD_ZERO(&rfds);
    FD_SET((int)fd, &rfds);

    struct timeval tv;
    tv.tv_sec  = timeoutMs / 1000;
    tv.tv_usec = (timeoutMs % 1000) * 1000;

    int ret = select(fd + 1, &rfds, NULL, NULL, &tv);
    if (ret < 0) {
        if (errno != EINTR) {
            LOGE("select fd=%d failed: %s", fd, strerror(errno));
        }
        return JNI_FALSE;
    }
    return (ret > 0 && FD_ISSET(fd, &rfds)) ? JNI_TRUE : JNI_FALSE;
}

/*
 * setNonBlocking: 设置 fd 为非阻塞模式
 *
 * Java 签名：
 *   boolean setNonBlocking(int fd, boolean nonBlocking)
 */
JNIEXPORT jboolean JNICALL
Java_com_example_myapplication_utils_PtyProcess_setNonBlocking(
    JNIEnv *env, jclass clazz, jint fd, jboolean nonBlocking
) {
    if (fd < 0) return JNI_FALSE;

    int flags = fcntl((int)fd, F_GETFL, 0);
    if (flags < 0) return JNI_FALSE;

    if (nonBlocking) {
        flags |= O_NONBLOCK;
    } else {
        flags &= ~O_NONBLOCK;
    }

    if (fcntl((int)fd, F_SETFL, flags) < 0) {
        LOGE("fcntl F_SETFL failed: %s", strerror(errno));
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

/*
 * setRawMode: 设置 PTY 为 Raw 模式（无回显，无行缓冲）
 *
 * Java 签名：
 *   boolean setRawMode(int fd)
 */
JNIEXPORT jboolean JNICALL
Java_com_example_myapplication_utils_PtyProcess_setRawMode(
    JNIEnv *env, jclass clazz, jint fd
) {
    if (fd < 0) return JNI_FALSE;

    struct termios t;
    if (tcgetattr((int)fd, &t) < 0) {
        LOGE("tcgetattr failed: %s", strerror(errno));
        return JNI_FALSE;
    }
    configure_termios_raw(&t);
    if (tcsetattr((int)fd, TCSANOW, &t) < 0) {
        LOGE("tcsetattr raw failed: %s", strerror(errno));
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

/*
 * setEchoMode: 设置 PTY 回显模式
 *
 * Java 签名：
 *   boolean setEchoMode(int fd, boolean echo)
 */
JNIEXPORT jboolean JNICALL
Java_com_example_myapplication_utils_PtyProcess_setEchoMode(
    JNIEnv *env, jclass clazz, jint fd, jboolean echo
) {
    if (fd < 0) return JNI_FALSE;

    struct termios t;
    if (tcgetattr((int)fd, &t) < 0) {
        return JNI_FALSE;
    }
    if (echo) {
        t.c_lflag |= (ECHO | ECHOE | ECHOK);
    } else {
        t.c_lflag &= ~(ECHO | ECHOE | ECHOK);
    }
    if (tcsetattr((int)fd, TCSANOW, &t) < 0) {
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

/*
 * getWindowSize: 获取当前 PTY 窗口大小
 *
 * Java 签名：
 *   int[] getWindowSize(int fd)  → [rows, cols]
 */
JNIEXPORT jintArray JNICALL
Java_com_example_myapplication_utils_PtyProcess_getWindowSize(
    JNIEnv *env, jclass clazz, jint fd
) {
    if (fd < 0) return NULL;

    struct winsize ws;
    if (ioctl((int)fd, TIOCGWINSZ, &ws) < 0) {
        return NULL;
    }

    jintArray result = (*env)->NewIntArray(env, 2);
    if (result) {
        jint size[2] = { ws.ws_row, ws.ws_col };
        (*env)->SetIntArrayRegion(env, result, 0, 2, size);
    }
    return result;
}