/**
 * yyds.keep - Native守护进程
 *
 * 编译为独立可执行文件，不依赖zygote/app_process，避免系统对脚本进程的干扰。
 * 启动后自动派生 yyds.auto 和 yyds.py 两个工作进程，并持续监控它们的存活状态。
 *
 * 用法: libyyds_keep.so <apk_path>
 *   apk_path: APK文件路径，用于设置子进程的CLASSPATH
 *
 * 进程守护策略:
 *   - 每10秒检查 yyds.auto 是否存活，不存活则重启
 *   - 每15秒检查 yyds.py 是否存活（先pidof再HTTP探测），不存活则重启
 *   - 检测APK更新（文件修改时间变化），更新后重启所有工作进程
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <signal.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <time.h>
#include <errno.h>
#include <fcntl.h>
#include <dirent.h>
#include <stdarg.h>

#define PROCESS_NAME       "yyds.keep"
#define AUTO_PROCESS_NAME  "yyds.auto"
#define PY_PROCESS_NAME    "yyds.py"

#define CHECK_INTERVAL_SEC    5
#define AUTO_CHECK_PERIOD    10   // 每10秒检查 yyds.auto
#define PY_CHECK_PERIOD      15   // 每15秒检查 yyds.py
#define PY_RESTART_COOLDOWN  10   // yyds.py 重启后等待秒数
#define INITIAL_DELAY_SEC     3   // 首次启动前等待
#define PY_START_DELAY_SEC    5   // yyds.auto启动后等待再启动yyds.py

#define LOG_FILE       "/data/local/tmp/yyds.log"
#define LOG_MAX_SIZE   (512 * 1024)  // 日志文件最大512KB，超过则截断
#define PID_FILE       "/data/local/tmp/.yyds.keep.pid"
#define APK_KEEP_PATH  "/data/local/tmp/cache/keep.apkpath"
#define PY_ENGINE_PORT 61140
#define KILL_MAX_RETRY 10  // kill_process最大重试次数，防止死循环

static char g_apk_path[512] = {0};
static char g_ld_preload[256] = {0};
static char g_self_path[512] = {0};  // 自身二进制路径，用于APK更新后exec重载
static time_t g_apk_mtime = 0;
static char *g_argv0 = NULL;         // 保存argv[0]原始指针（fork前）
static int   g_argv0_len = 0;        // argv[0]可写长度

// ================================================================
// 日志
// ================================================================

// P5: 日志轮转 - 超过最大大小时截断保留尾部
static void log_rotate_if_needed() {
    struct stat st;
    if (stat(LOG_FILE, &st) == 0 && st.st_size > LOG_MAX_SIZE) {
        // 简单策略：直接截断（生产环境中日志主要用于排障，旧日志价值低）
        truncate(LOG_FILE, 0);
    }
}

static void log_msg(const char *msg) {
    log_rotate_if_needed();
    FILE *fp = fopen(LOG_FILE, "a");
    if (!fp) return;
    time_t now = time(NULL);
    struct tm tm_buf;
    localtime_r(&now, &tm_buf);
    char timebuf[64];
    strftime(timebuf, sizeof(timebuf), "%Y-%m-%d %H:%M:%S", &tm_buf);
    fprintf(fp, "\n[%s][yyds.keep-native] %s", timebuf, msg);
    fclose(fp);
}

static void log_fmt(const char *fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    log_msg(buf);
}

// ================================================================
// 进程工具
// ================================================================

static int pidof(const char *name) {
    DIR *dp = opendir("/proc");
    if (!dp) return -1;
    struct dirent *entry;
    while ((entry = readdir(dp)) != NULL) {
        // 只看数字目录
        int pid = 0;
        const char *p = entry->d_name;
        while (*p) {
            if (*p < '0' || *p > '9') { pid = -1; break; }
            pid = pid * 10 + (*p - '0');
            p++;
        }
        if (pid <= 0) continue;

        char path[64];
        snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
        FILE *fp = fopen(path, "r");
        if (!fp) continue;
        char cmdline[256] = {0};
        fread(cmdline, 1, sizeof(cmdline) - 1, fp);
        fclose(fp);
        if (strcmp(cmdline, name) == 0) {
            closedir(dp);
            return pid;
        }
    }
    closedir(dp);
    return -1;
}

static int is_process_alive(const char *name) {
    return pidof(name) > 0;
}

// P3: 加入最大重试限制，防止进程无法杀死时死循环
static void kill_process(const char *name) {
    int pid;
    int retry = 0;
    while ((pid = pidof(name)) > 0 && retry < KILL_MAX_RETRY) {
        kill(pid, SIGKILL);
        usleep(200000); // 200ms
        retry++;
    }
    if (retry >= KILL_MAX_RETRY) {
        log_fmt("警告: 杀死进程 %s 超过最大重试次数(%d)", name, KILL_MAX_RETRY);
    }
}

// ================================================================
// Shell 执行
// ================================================================

static int shell_exec(const char *cmd) {
    pid_t pid = fork();
    if (pid < 0) return -1;
    if (pid == 0) {
        // 子进程
        setsid();
        // 重定向stdout/stderr到/dev/null
        int devnull = open("/dev/null", O_WRONLY);
        if (devnull >= 0) {
            dup2(devnull, STDOUT_FILENO);
            dup2(devnull, STDERR_FILENO);
            close(devnull);
        }
        execl("/system/bin/sh", "sh", "-c", cmd, (char *)NULL);
        _exit(127);
    }
    // 父进程不等待（nohup效果）
    return 0;
}

// P7: 周期性回收僵尸子进程（比SIGCHLD=SIG_IGN更可移植）
static void reap_zombies() {
    while (waitpid(-1, NULL, WNOHANG) > 0) {}
}

// P2: 使用原生socket做TCP端口探活，零外部依赖（不依赖curl）
static int tcp_port_alive(int port, int timeout_sec) {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) return 0;

    // 设置非阻塞连接超时
    struct timeval tv;
    tv.tv_sec = timeout_sec;
    tv.tv_usec = 0;
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

    int ret = connect(sock, (struct sockaddr *)&addr, sizeof(addr));
    close(sock);
    return ret == 0;
}

static int http_check_py_engine() {
    return tcp_port_alive(PY_ENGINE_PORT, 3);
}

// ================================================================
// 引擎启动
// ================================================================

static void start_auto_engine() {
    char cmd[1024];
    // 不使用nohup，通过shell_exec中的setsid+fd重定向实现后台运行
    snprintf(cmd, sizeof(cmd),
        "cd /data/local/tmp; %s CLASSPATH=%s app_process /system/bin uiautomator.ExportApi </dev/null >/dev/null 2>&1 &",
        g_ld_preload, g_apk_path);
    log_fmt("启动 yyds.auto: %s", cmd);
    shell_exec(cmd);
}

static void start_py_engine() {
    // 从 keeper 自身路径推导 lib 目录（keeper 和 libpython3.13.so 在同一目录）
    char lib_dir[512] = {0};
    if (g_self_path[0]) {
        strncpy(lib_dir, g_self_path, sizeof(lib_dir) - 1);
        char *last_slash = strrchr(lib_dir, '/');
        if (last_slash) *last_slash = '\0';
    } else {
        strcpy(lib_dir, "/data/local/tmp/cache/lib");
    }

    char cmd[1024];
    // LD_LIBRARY_PATH 指向 libpython3.13.so 所在目录，使动态链接器能找到它
    // 不使用nohup，通过shell_exec中的setsid+fd重定向实现后台运行
    snprintf(cmd, sizeof(cmd),
        "cd /data/local/tmp; %s LD_LIBRARY_PATH=%s CLASSPATH=%s app_process /system/bin pyengine.Main </dev/null >/dev/null 2>&1 &",
        g_ld_preload, lib_dir, g_apk_path);
    log_fmt("启动 yyds.py: %s", cmd);
    shell_exec(cmd);
}

// ================================================================
// APK监控
// ================================================================

static time_t get_file_mtime(const char *path) {
    struct stat st;
    if (stat(path, &st) == 0) {
        return st.st_mtime;
    }
    return 0;
}

// ================================================================
// 守护进程初始化
// ================================================================

static void daemonize() {
    pid_t pid = fork();
    if (pid < 0) {
        perror("fork");
        exit(1);
    }
    if (pid > 0) {
        // 父进程退出
        _exit(0);
    }
    // 子进程继续
    setsid();
    umask(0);
    // 关闭标准文件描述符
    close(STDIN_FILENO);
    int devnull = open("/dev/null", O_RDWR);
    if (devnull >= 0) {
        dup2(devnull, STDOUT_FILENO);
        dup2(devnull, STDERR_FILENO);
        if (devnull > STDERR_FILENO) close(devnull);
    }
}

// P4: 使用全局保存的argv0指针（fork前保存，fork后仍然有效，因为子进程继承内存）
static void set_process_name() {
    prctl(PR_SET_NAME, PROCESS_NAME, 0, 0, 0);
    if (g_argv0 && g_argv0_len > 0) {
        memset(g_argv0, 0, g_argv0_len);
        strncpy(g_argv0, PROCESS_NAME, g_argv0_len - 1);
    }
}

static void save_pid() {
    FILE *fp = fopen(PID_FILE, "w");
    if (fp) {
        fprintf(fp, "%d", getpid());
        fclose(fp);
    }
}

static void save_apk_path() {
    FILE *fp = fopen(APK_KEEP_PATH, "w");
    if (fp) {
        fprintf(fp, "%s", g_apk_path);
        fclose(fp);
    }
}

/**
 * 刷新APK路径 - 当旧路径不存在时（Android更新APK会改变随机目录），
 * 通过 `pm path` 命令获取当前真实路径并更新全局变量和持久化文件。
 * 不依赖sed/awk/cut等外部命令，用C代码直接解析 "package:/path" 格式。
 * @return 1=路径已刷新, 0=路径未变或获取失败
 */
static int refresh_apk_path() {
    char line_buf[512] = {0};
    char new_path[512] = {0};
    FILE *fp = popen("pm path com.yyds.auto 2>/dev/null", "r");
    if (!fp) return 0;
    if (fgets(line_buf, sizeof(line_buf), fp)) {
        char *nl = strchr(line_buf, '\n');
        if (nl) *nl = '\0';
        // 去除 "package:" 前缀：找到第一个 ':' 后的内容即为路径
        char *colon = strchr(line_buf, ':');
        const char *path_start = colon ? colon + 1 : line_buf;
        // 去除首尾空白
        while (*path_start == ' ' || *path_start == '\t') path_start++;
        strncpy(new_path, path_start, sizeof(new_path) - 1);
        char *end = new_path + strlen(new_path) - 1;
        while (end > new_path && (*end == ' ' || *end == '\t' || *end == '\r')) *end-- = '\0';
    }
    pclose(fp);

    if (new_path[0] == '\0') {
        log_msg("refresh_apk_path: pm path 返回空，无法刷新");
        return 0;
    }

    if (strcmp(g_apk_path, new_path) == 0) {
        return 0; // 路径未变
    }

    log_fmt("APK路径已更新: %s -> %s", g_apk_path, new_path);
    strncpy(g_apk_path, new_path, sizeof(g_apk_path) - 1);
    g_apk_path[sizeof(g_apk_path) - 1] = '\0';
    save_apk_path();
    return 1;
}

/**
 * 检测APK是否已更新。
 * 策略：
 *   1. 检查当前路径的mtime是否变化 -> 有变化说明APK被覆盖安装
 *   2. 如果stat失败（文件不存在）-> Android更新后旧路径被删除，
 *      调用 refresh_apk_path() 获取新路径，获取成功即视为已更新
 * @return 1=已更新, 0=未更新
 */
static int has_apk_updated() {
    struct stat st;
    if (stat(g_apk_path, &st) == 0) {
        // 文件存在，检查mtime
        if (g_apk_mtime > 0 && st.st_mtime != g_apk_mtime) {
            log_fmt("APK mtime变化: %ld -> %ld", (long)g_apk_mtime, (long)st.st_mtime);
            g_apk_mtime = st.st_mtime;
            return 1;
        }
        return 0;
    }

    // stat失败 - 旧路径已不存在（Android更新APK改变了随机目录）
    log_fmt("APK旧路径不存在: %s, 尝试刷新...", g_apk_path);
    if (refresh_apk_path()) {
        // 路径已刷新，更新mtime
        g_apk_mtime = get_file_mtime(g_apk_path);
        return 1;
    }
    return 0;
}

// ================================================================
// 信号处理
// ================================================================

static volatile sig_atomic_t g_running = 1;

static void sig_handler(int sig) {
    if (sig == SIGTERM || sig == SIGINT) {
        g_running = 0;
    }
}

// ================================================================
// 主入口
// ================================================================

int main(int argc, char *argv[]) {
    if (argc < 2) {
        fprintf(stderr, "Usage: %s <apk_path> [ld_preload]\n", argv[0]);
        // 尝试自动获取APK路径（纯C字符串处理，不依赖sed）
        FILE *fp = popen("pm path com.yyds.auto 2>/dev/null", "r");
        if (fp) {
            char buf[512] = {0};
            if (fgets(buf, sizeof(buf), fp)) {
                char *nl = strchr(buf, '\n');
                if (nl) *nl = '\0';
                // 去除 "package:" 前缀
                char *colon = strchr(buf, ':');
                const char *path_start = colon ? colon + 1 : buf;
                strncpy(g_apk_path, path_start, sizeof(g_apk_path) - 1);
            }
            pclose(fp);
        }
        if (g_apk_path[0] == '\0') {
            fprintf(stderr, "Error: Cannot determine APK path\n");
            return 1;
        }
    } else {
        strncpy(g_apk_path, argv[1], sizeof(g_apk_path) - 1);
    }

    if (argc >= 3) {
        strncpy(g_ld_preload, argv[2], sizeof(g_ld_preload) - 1);
    }

    // P4: 在fork前保存argv[0]指针和长度（fork后子进程继承同一块内存）
    if (argc > 0) {
        g_argv0 = argv[0];
        g_argv0_len = strlen(argv[0]) + 1;
    }

    // P6: 保存自身路径，APK更新后用于exec重载
    if (argc > 0 && argv[0][0] == '/') {
        strncpy(g_self_path, argv[0], sizeof(g_self_path) - 1);
    } else {
        // 尝试从/proc/self/exe获取
        ssize_t len = readlink("/proc/self/exe", g_self_path, sizeof(g_self_path) - 1);
        if (len > 0) g_self_path[len] = '\0';
    }

    // 先杀掉已有的 yyds.keep
    kill_process(PROCESS_NAME);

    // 守护进程化
    daemonize();

    // P4: 设置进程名（使用全局保存的指针）
    set_process_name();

    // 信号处理
    signal(SIGTERM, sig_handler);
    signal(SIGINT, sig_handler);
    signal(SIGHUP, SIG_IGN);
    signal(SIGPIPE, SIG_IGN);
    // P7: 不使用SIGCHLD=SIG_IGN（部分内核行为不一致），改用周期性waitpid回收

    // 保存PID和APK路径
    save_pid();
    save_apk_path();
    g_apk_mtime = get_file_mtime(g_apk_path);

    log_fmt("Native守护进程启动 PID=%d APK=%s", getpid(), g_apk_path);

    // 初始延迟
    sleep(INITIAL_DELAY_SEC);

    // ====== 首次启动两个工作进程 ======
    if (!is_process_alive(AUTO_PROCESS_NAME)) {
        log_msg("首次启动 yyds.auto...");
        start_auto_engine();
    } else {
        log_msg("yyds.auto 已在运行");
    }

    sleep(PY_START_DELAY_SEC);

    if (!is_process_alive(PY_PROCESS_NAME)) {
        log_msg("首次启动 yyds.py...");
        start_py_engine();
    } else {
        log_msg("yyds.py 已在运行");
    }

    // ====== 主监控循环 ======
    int tick = 0;
    while (g_running) {
        sleep(CHECK_INTERVAL_SEC);
        tick += CHECK_INTERVAL_SEC;

        // P7: 周期性回收僵尸子进程
        reap_zombies();

        // 检查APK是否更新
        if (has_apk_updated()) {
            log_msg("检测到APK更新，重启所有工作进程...");
            kill_process(AUTO_PROCESS_NAME);
            kill_process(PY_PROCESS_NAME);
            sleep(2);

            // P6: APK更新后keeper自身二进制也可能已更新，exec重载自身
            if (g_self_path[0] != '\0') {
                log_fmt("APK更新，exec重载自身: %s", g_self_path);
                // 重新释放SO文件中可能更新的keeper二进制
                // exec会替换当前进程映像，新进程会重新启动子进程
                execl(g_self_path, g_self_path, g_apk_path, g_ld_preload, (char *)NULL);
                // exec失败则继续当前进程
                log_fmt("exec重载失败(errno=%d)，继续当前进程", errno);
            }

            start_auto_engine();
            sleep(PY_START_DELAY_SEC);
            start_py_engine();
            tick = 0;
            continue;
        }

        // 每 AUTO_CHECK_PERIOD 秒检查 yyds.auto
        if (tick % AUTO_CHECK_PERIOD == 0) {
            if (!is_process_alive(AUTO_PROCESS_NAME)) {
                log_fmt("检测到 yyds.auto 未运行(tick=%d)，正在重启...", tick);
                start_auto_engine();
            }
        }

        // 每 PY_CHECK_PERIOD 秒检查 yyds.py
        if (tick % PY_CHECK_PERIOD == 0) {
            if (!is_process_alive(PY_PROCESS_NAME)) {
                // 双重确认：再通过HTTP检查
                if (!http_check_py_engine()) {
                    log_fmt("检测到 yyds.py 未运行(tick=%d)，正在重启...", tick);
                    start_py_engine();
                    sleep(PY_RESTART_COOLDOWN);
                    tick += PY_RESTART_COOLDOWN;
                }
            }
        }

        // 防止tick溢出
        if (tick >= 3000) tick = 0;
    }

    log_msg("Native守护进程退出");
    unlink(PID_FILE);
    return 0;
}
