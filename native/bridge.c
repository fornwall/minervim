#include <jni.h>
#include <errno.h>
#include <uv.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <android/log.h>

int nvim_main(int argc, char **argv);
static pthread_mutex_t state_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t failed = PTHREAD_COND_INITIALIZER;
static bool started;
static char failure_message[256];
struct startup { char *files; char *socket; };

static void fail(const char *message) {
  pthread_mutex_lock(&state_lock);
  snprintf(failure_message, sizeof failure_message, "%s", message);
  pthread_cond_broadcast(&failed);
  pthread_mutex_unlock(&state_lock);
  __android_log_print(ANDROID_LOG_INFO, "AndroidNeovim", "%s", message);
}

// Neovim is a library guest: its exit paths must never kill Android's process.
// One instance is supported for the process lifetime; upstream uses global state.
__attribute__((noreturn)) void __wrap_exit(int status) {
  char message[128];
  snprintf(message, sizeof message, "Neovim exited (%d). Restart the app to start a new editor.", status);
  fail(message);
  pthread_exit(NULL);
}
__attribute__((noreturn)) void __wrap__exit(int status) { __wrap_exit(status); }

static void *run_editor(void *opaque) {
  pthread_setname_np(pthread_self(), "neovim-core");
  struct startup *args = opaque;
  char *runtime = NULL, *tmp = NULL, *config = NULL;
  asprintf(&runtime, "%s/runtime", args->files);
  asprintf(&tmp, "%s/tmp", args->files);
  asprintf(&config, "%s/config", args->files);
  if (!runtime || !tmp || !config) {
    fail("Unable to allocate Neovim startup paths");
    return NULL;
  }
  mkdir(tmp, 0700);
  mkdir(config, 0700);
  setenv("HOME", args->files, 1);
  setenv("VIMRUNTIME", runtime, 1);
  setenv("TMPDIR", tmp, 1);
  setenv("XDG_CONFIG_HOME", config, 1);
  setenv("XDG_DATA_HOME", args->files, 1);
  setenv("XDG_STATE_HOME", args->files, 1);
  setenv("XDG_CACHE_HOME", tmp, 1);
  setenv("SHELL", "/system/bin/sh", 1);
  unlink(args->socket);
  // Normal startup: the user's $XDG_CONFIG_HOME/nvim config, bundled runtime plugins and ShaDa.
  char *argv[] = { "nvim", "--headless", "--listen", args->socket,
                   "--cmd", "set noswapfile shell=/system/bin/sh", NULL };
  nvim_main(6, argv);
  fail("Neovim stopped. Restart the app to start a new editor.");
  free(runtime); free(tmp); free(config);
  free(args->files); free(args->socket); free(args);
  return NULL;
}

JNIEXPORT void JNICALL
Java_net_fornwall_androidneovim_NativeBridge_start(JNIEnv *env, jclass type,
                                                 jstring files_dir, jstring socket_path) {
  (void)type;
  pthread_mutex_lock(&state_lock);
  if (started) { pthread_mutex_unlock(&state_lock); return; }
  started = true;
  pthread_mutex_unlock(&state_lock);
  const char *files = (*env)->GetStringUTFChars(env, files_dir, NULL);
  if (!files) return;
  const char *socket = (*env)->GetStringUTFChars(env, socket_path, NULL);
  if (!socket) { (*env)->ReleaseStringUTFChars(env, files_dir, files); return; }
  struct startup *args = calloc(1, sizeof *args);
  if (args) { args->files = strdup(files); args->socket = strdup(socket); }
  (*env)->ReleaseStringUTFChars(env, files_dir, files);
  (*env)->ReleaseStringUTFChars(env, socket_path, socket);
  if (!args || !args->files || !args->socket) { fail("Unable to allocate Neovim startup state"); return; }
  pthread_t thread;
  int error = pthread_create(&thread, NULL, run_editor, args);
  if (error) { fail("Unable to start the Neovim thread"); return; }
  pthread_detach(thread);
}

JNIEXPORT jstring JNICALL
Java_net_fornwall_androidneovim_NativeBridge_failure(JNIEnv *env, jclass type) {
  (void)type;
  pthread_mutex_lock(&state_lock);
  jstring result = failure_message[0] ? (*env)->NewStringUTF(env, failure_message) : NULL;
  pthread_mutex_unlock(&state_lock);
  return result;
}

// Blocks until the editor has failed or ended. Its thread can end without closing
// its sockets (an exit() skips teardown), so the UI cannot rely on EOF alone.
JNIEXPORT jstring JNICALL
Java_net_fornwall_androidneovim_NativeBridge_awaitFailure(JNIEnv *env, jclass type) {
  (void)type;
  char message[sizeof failure_message];
  pthread_mutex_lock(&state_lock);
  while (!failure_message[0]) pthread_cond_wait(&failed, &state_lock);
  memcpy(message, failure_message, sizeof message);
  pthread_mutex_unlock(&state_lock);
  return (*env)->NewStringUTF(env, message);
}

// The first iteration intentionally supports an in-process editor only. Reject
// conventional job/shell/terminal launch paths before they can create children.
pid_t __wrap_fork(void) { errno = ENOSYS; return -1; }
pid_t __wrap_forkpty(int *master, char *name, const struct termios *term,
                     const struct winsize *size) {
  (void)master; (void)name; (void)term; (void)size;
  errno = ENOSYS;
  return -1;
}
int __wrap_system(const char *command) {
  if (!command) return 0;
  errno = ENOSYS;
  return -1;
}
FILE *__wrap_popen(const char *command, const char *mode) {
  (void)command; (void)mode;
  errno = ENOSYS;
  return NULL;
}
