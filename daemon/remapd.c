/*
 * remapd - make the extra buttons on a Bluetooth TV remote usable on
 * Android TV / Google TV without root. Run it from an ADB shell.
 *
 * Many remotes send HID usage codes the Linux kernel doesn't know, so the
 * kernel reports all of them as KEY_UNKNOWN and Android (and every remapping
 * app) sees them as one and the same button. remapd uses the kernel's own
 * HID keymap ioctl (EVIOCSKEYCODE) to give each of those buttons a real key
 * code, re-applies it whenever the remote reconnects, and can run a shell
 * command (open an app, send a key, ...) when a button is pressed.
 *
 * `check` and `run` also show what Android makes of each key (from the key
 * layout file Android uses for the remote), and whether the system keeps a
 * key for itself, so you can tell a remote problem from an app problem.
 *
 * When Android reads the remote with a key layout file that lacks a key (a
 * vendor .kl made for another remote with the same ID, say), `send` presses
 * the Android key on a virtual remote (uinput) instead, which Android reads
 * with its standard key layout. The real remote is left alone otherwise.
 *
 *   remapd list            show input devices
 *   remapd check CONF      show what CONF would change (changes nothing)
 *   remapd run CONF        apply CONF in the foreground, show presses (Ctrl+C restores)
 *   remapd start CONF      apply CONF in the background
 *   remapd stop | status   stop / check the background copy
 *
 * Build (static, runs on 32- and 64-bit ARM Android):
 *   zig cc -target arm-linux-musleabihf -mcpu=cortex_a7 -static -Os -o remapd remapd.c
 */
#define _GNU_SOURCE
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#include <linux/input.h>
#include <linux/uinput.h>

#include "keynames.h"
#include "androidkeys.h"

#define VERSION     "1.3"
#define VIRT_NAME   "remapd virtual remote"
#define STATE_DIR   "/data/local/tmp"
#define PID_FILE    STATE_DIR "/remapd.pid"
#define LOG_FILE    STATE_DIR "/remapd.log"
#define INPUT_DIR   "/dev/input"
#define MAX_RULES   64
#define MAX_DEVS    32
#define MAX_FILTERS 8
#define RESCAN_MS   2000
#define OPEN_TRIES  10
#define LOG_MAX     (256 * 1024)
#define NKEYNAMES   ((int)(sizeof keynames / sizeof keynames[0]))

/* The kernel's event layout for this process's word size (16 bytes on
 * 32-bit ARM, 24 on arm64), independent of the libc's time_t size. */
struct raw_event {
	unsigned long sec, usec;
	unsigned short type, code;
	int value;
};
_Static_assert(sizeof(struct raw_event) == 2 * sizeof(long) + 8, "input_event layout");
_Static_assert(sizeof(struct input_keymap_entry) == 40, "input_keymap_entry layout");

struct rule {
	unsigned usage;   /* HID usage = the MSC_SCAN value, e.g. 0x000c001c */
	int map_to;       /* key code the kernel should report, -1 = not set */
	char *cmd;        /* shell command to run on press, NULL = none */
	char *what;       /* the action as written, for the log */
	char *send;       /* Android key to press instead, NULL = none */
	char *send_cmd;   /* `input keyevent` fallback for it */
	int send_code;    /* kernel key the virtual remote presses, -1 = use send_cmd */
	int line;
};

struct dev {
	int used, seen;
	char path[64];
	dev_t rdev;
	ino_t ino;
	int fd;           /* >= 0 while the device is tracked */
	int ignored;      /* looked at it, nothing for us there */
	int tries;        /* failed open attempts */
	char name[128];
	unsigned vendor, product, version;
	int orig[MAX_RULES]; /* key code before we changed it, -1 = not on device */
	unsigned char changed[MAX_RULES];
	unsigned char held[MAX_RULES];   /* send keys held down on the virtual remote */
	int sibling;      /* another node of an already tracked remote */
	unsigned last_scan;
	int have_scan;
	const struct klfile *kl; /* Android's key layout for it (run mode only) */
};

static struct rule rules[MAX_RULES];
static int nrules;
static char *filters[MAX_FILTERS];
static int nfilters;
static struct dev devs[MAX_DEVS];
static int verbose;
static const char *shell_path = "/system/bin/sh";
static volatile sig_atomic_t quit_flag;
static const char *fsroot = "";   /* REMAPD_TEST_ROOT: tests put the Android paths in a sandbox */

/* The real location of an Android path (always the same path outside tests). */
static const char *fs(const char *path)
{
	static char buf[4][512];
	static int n;

	if (!*fsroot)
		return path;
	n = (n + 1) % 4;
	snprintf(buf[n], sizeof buf[n], "%s%s", fsroot, path);
	return buf[n];
}

/* ------------------------------------------------------------------ */

static void logm(const char *fmt, ...)
{
	static int lines;
	char ts[16] = "";
	time_t t = time(NULL);
	struct tm tm;
	va_list ap;

	if (gmtime_r(&t, &tm))
		strftime(ts, sizeof ts, "%H:%M:%S", &tm);
	printf("[%s] ", ts);
	va_start(ap, fmt);
	vprintf(fmt, ap);
	va_end(ap);
	putchar('\n');
	fflush(stdout);

	if (++lines % 50 == 0) {
		struct stat st;

		if (fstat(1, &st) == 0 && S_ISREG(st.st_mode) &&
		    st.st_size > LOG_MAX && ftruncate(1, 0) == 0)
			printf("[%s] (log trimmed)\n", ts);
	}
}

static int key_from_name(const char *s)
{
	char buf[64];
	char *end;
	long v;
	int i;

	if (isdigit((unsigned char)s[0])) {
		v = strtol(s, &end, 0);
		return (*end || v < 0 || v > KEY_MAX) ? -1 : (int)v;
	}
	for (i = 0; i < NKEYNAMES; i++)
		if (!strcasecmp(s, keynames[i].name))
			return keynames[i].code;
	snprintf(buf, sizeof buf, "KEY_%s", s);
	for (i = 0; i < NKEYNAMES; i++)
		if (!strcasecmp(buf, keynames[i].name))
			return keynames[i].code;
	return -1;
}

static const char *key_name(int code)
{
	static char buf[4][16];
	static int n;
	int i;

	for (i = 0; i < NKEYNAMES; i++)
		if (keynames[i].code == code)
			return keynames[i].name;
	n = (n + 1) % 4;
	snprintf(buf[n], sizeof buf[n], "key %d", code);
	return buf[n];
}

static const char *short_path(const char *path)
{
	const char *s = strrchr(path, '/');

	return s ? s + 1 : path;
}

/* ---------------------------- config ------------------------------ */

static char *trim(char *s)
{
	char *e;

	while (isspace((unsigned char)*s))
		s++;
	e = s + strlen(s);
	while (e > s && isspace((unsigned char)e[-1]))
		*--e = 0;
	return s;
}

static void strip_comment(char *s)
{
	char *h = strchr(s, '#');

	if (h)
		*h = 0;
}

/* Splits off the first word of *s. */
static char *next_word(char **s)
{
	char *w = *s, *p = *s;

	while (*p && !isspace((unsigned char)*p))
		p++;
	if (*p)
		*p++ = 0;
	*s = trim(p);
	return w;
}

static int parse_usage(const char *s, unsigned *out)
{
	unsigned long v;
	char *end;

	if (!strncasecmp(s, "0x", 2))
		s += 2;
	if (!isxdigit((unsigned char)*s))
		return -1;
	errno = 0;
	v = strtoul(s, &end, 16);
	if (*end || errno || v == 0 || v > 0xffffffffUL)
		return -1;
	*out = (unsigned)v;
	return 0;
}

static struct rule *rule_for(unsigned usage, int line)
{
	int i;

	for (i = 0; i < nrules; i++)
		if (rules[i].usage == usage)
			return &rules[i];
	if (nrules == MAX_RULES)
		return NULL;
	rules[nrules].usage = usage;
	rules[nrules].map_to = -1;
	rules[nrules].send_code = -1;
	rules[nrules].line = line;
	return &rules[nrules++];
}

static int valid_package(const char *p)
{
	if (!*p)
		return 0;
	for (; *p; p++)
		if (!isalnum((unsigned char)*p) && *p != '.' && *p != '_')
			return 0;
	return 1;
}

/* What the kernel should report for a rule's button, -1 = leave it. */
static int target_of(const struct rule *r)
{
	if (r->map_to >= 0)
		return r->map_to;
	if (r->cmd || r->send)
		return KEY_UNKNOWN;   /* an action replaces the button's own key */
	return -1;
}

static int android_key_valid(const char *name)
{
	size_t i;

	for (i = 0; i < sizeof androidkeys / sizeof androidkeys[0]; i++)
		if (!strcmp(androidkeys[i].name, name))
			return 1;
	return 0;
}

static int load_config(const char *path)
{
	char line[1024];
	int ln = 0, errors = 0;
	FILE *f = fopen(path, "r");

	if (!f) {
		fprintf(stderr, "can't open %s: %s\n", path, strerror(errno));
		return -1;
	}
	while (fgets(line, sizeof line, f)) {
		char *s = line, *kw, *arg;
		struct rule *r;
		unsigned usage;

		ln++;
		if (!strchr(line, '\n') && !feof(f)) {
			fprintf(stderr, "%s line %d: line too long\n", path, ln);
			errors++;
			while (fgets(line, sizeof line, f) && !strchr(line, '\n'))
				;
			continue;
		}
		if (ln == 1 && !strncmp(s, "\xef\xbb\xbf", 3))
			s += 3;   /* UTF-8 BOM from some Windows editors */
		s = trim(s);
		if (!*s || *s == '#')
			continue;
		kw = next_word(&s);

		if (!strcasecmp(kw, "device")) {
			strip_comment(s);
			s = trim(s);
			if (!*s || nfilters == MAX_FILTERS) {
				fprintf(stderr, "%s line %d: device needs a name or vendor:product\n", path, ln);
				errors++;
				continue;
			}
			filters[nfilters++] = strdup(s);
			continue;
		}
		if (strcasecmp(kw, "map") && strcasecmp(kw, "send") && strcasecmp(kw, "run") &&
		    strcasecmp(kw, "launch")) {
			fprintf(stderr, "%s line %d: unknown keyword '%s' (use device, send, map, run or launch)\n", path, ln, kw);
			errors++;
			continue;
		}
		arg = next_word(&s);
		if (parse_usage(arg, &usage) < 0) {
			fprintf(stderr, "%s line %d: '%s' is not a button code (use the MSC_SCAN value from getevent, e.g. 000c001c)\n", path, ln, arg);
			errors++;
			continue;
		}
		r = rule_for(usage, ln);
		if (!r) {
			fprintf(stderr, "%s line %d: too many buttons (max %d)\n", path, ln, MAX_RULES);
			errors++;
			continue;
		}

		if (!strcasecmp(kw, "map")) {
			char *key;
			int code;

			strip_comment(s);
			s = trim(s);
			key = next_word(&s);
			if (*s || !*key) {
				fprintf(stderr, "%s line %d: use: map <code> <KEY_NAME>\n", path, ln);
				errors++;
			} else if ((code = key_from_name(key)) < 0) {
				fprintf(stderr, "%s line %d: unknown key '%s' (Linux names like KEY_PROGRAM, or a number)\n", path, ln, key);
				errors++;
			} else if (r->map_to >= 0) {
				fprintf(stderr, "%s line %d: %08x already has a map line\n", path, ln, usage);
				errors++;
			} else if (r->send) {
				fprintf(stderr, "%s line %d: %08x has a send line; use either map or send\n", path, ln, usage);
				errors++;
			} else {
				r->map_to = code;
			}
			continue;
		}

		if (r->cmd || r->send) {
			fprintf(stderr, "%s line %d: %08x already has an action (send, run or launch)\n", path, ln, usage);
			errors++;
			continue;
		}
		if (!strcasecmp(kw, "send")) {
			char *key, *p;

			strip_comment(s);
			s = trim(s);
			key = next_word(&s);
			if (!strncasecmp(key, "KEYCODE_", 8))
				key += 8;
			for (p = key; *p; p++)
				*p = (char)toupper((unsigned char)*p);
			if (*s || !*key) {
				fprintf(stderr, "%s line %d: use: send <code> <ANDROID_KEY>\n", path, ln);
				errors++;
			} else if (!android_key_valid(key)) {
				fprintf(stderr, "%s line %d: '%s' is not an Android key name (like PROG_RED, MEDIA_PLAY_PAUSE or PAGE_UP)\n",
					path, ln, key);
				errors++;
			} else if (r->map_to >= 0) {
				fprintf(stderr, "%s line %d: %08x has a map line; use either map or send\n", path, ln, usage);
				errors++;
			} else if (asprintf(&r->send, "%s", key) < 0 ||
				   asprintf(&r->send_cmd, "input keyevent KEYCODE_%s", key) < 0 ||
				   asprintf(&r->what, "send Android %s", key) < 0) {
				exit(1);
			}
			continue;
		}
		if (!strcasecmp(kw, "launch")) {
			strip_comment(s);
			s = trim(s);
			if (!valid_package(s)) {
				fprintf(stderr, "%s line %d: use: launch <code> <package.name>\n", path, ln);
				errors++;
				continue;
			}
			if (asprintf(&r->cmd,
				     "for c in LEANBACK_LAUNCHER LAUNCHER; do "
				     "a=$(cmd package resolve-activity --brief -a android.intent.action.MAIN "
				     "-c android.intent.category.$c %s 2>/dev/null | tail -n 1); "
				     "case \"$a\" in */*) exec am start -f 0x10200000 -n \"$a\" >/dev/null;; esac; "
				     "done; echo \"launch: %s is not installed (or has no launcher activity)\" >&2",
				     s, s) < 0 ||
			    asprintf(&r->what, "launch %s", s) < 0)
				exit(1);
		} else {
			char *show;

			if (!*s) {
				fprintf(stderr, "%s line %d: use: run <code> <shell command>\n", path, ln);
				errors++;
				continue;
			}
			/* The shell ignores a trailing "# comment"; hide it in logs. */
			r->cmd = strdup(s);
			show = strdup(s);
			if (strstr(show, " #"))
				*strstr(show, " #") = 0;
			if (asprintf(&r->what, "run %s", trim(show)) < 0)
				exit(1);
			free(show);
		}
	}
	fclose(f);
	if (!errors && !nrules) {
		fprintf(stderr, "%s: no map, run or launch lines\n", path);
		errors++;
	}
	return errors ? -1 : 0;
}

static void print_rules(void)
{
	int i;

	printf("%d button(s) configured:\n", nrules);
	for (i = 0; i < nrules; i++) {
		const struct rule *r = &rules[i];

		printf("  %08x  ", r->usage);
		if (r->map_to >= 0)
			printf("report as %s", key_name(r->map_to));
		else if (r->send)
			printf("own key off, sends Android %s", r->send);
		else
			printf("own key off");
		if (r->cmd)
			printf(", on press: %s", r->what);
		putchar('\n');
	}
	if (nfilters) {
		printf("Only devices matching:");
		for (i = 0; i < nfilters; i++)
			printf(" \"%s\"", filters[i]);
		putchar('\n');
	}
}

/* --------------------------- Android side --------------------------- */
/*
 * Android turns each kernel key into an Android key with the device's key
 * layout (.kl) file, matching the HID usage first and the kernel key code
 * second. The system can also keep some keys for itself ("global keys") so
 * apps never get them. remapd only reads all this, to explain things in
 * `check` and `run`.
 */

struct klentry {
	unsigned code;
	char key[32];
};

struct klfile {
	char path[256];
	struct klentry *scan, *usage;
	int nscan, nusage;
};

struct globalkey {
	char key[48];       /* Android key name without KEYCODE_ */
	char owner[160];    /* the component the system sends it to */
};

#define MAX_KL     8
#define MAX_GLOBAL 48
static struct klfile klfiles[MAX_KL];
static int nklfiles;
static struct globalkey globalkeys[MAX_GLOBAL];
static int nglobalkeys = -1;   /* -1 = not looked up yet */

/* Runs a shell command and returns its output (stderr is dropped). */
static FILE *spawn_read(const char *cmd, pid_t *pid)
{
	int p[2];
	FILE *f;

	if (pipe2(p, O_CLOEXEC) < 0)
		return NULL;
	*pid = fork();
	if (*pid == 0) {
		struct sigaction sa;
		int nul = open("/dev/null", O_RDWR | O_CLOEXEC);

		memset(&sa, 0, sizeof sa);
		sa.sa_handler = SIG_DFL;
		sigaction(SIGCHLD, &sa, NULL);
		sigaction(SIGPIPE, &sa, NULL);
		dup2(p[1], 1);
		if (nul >= 0) {
			dup2(nul, 0);
			dup2(nul, 2);
		}
		execl(shell_path, "sh", "-c", cmd, (char *)NULL);
		_exit(127);
	}
	close(p[1]);
	if (*pid < 0 || !(f = fdopen(p[0], "r"))) {
		close(p[0]);
		if (*pid > 0)
			waitpid(*pid, NULL, 0);
		return NULL;
	}
	return f;
}

static void spawn_close(FILE *f, pid_t pid)
{
	fclose(f);
	waitpid(pid, NULL, 0);
}

/* The key layout file Android uses for a device node, as dumpsys reports it. */
static int kl_path_from_dumpsys(const char *devpath, char *out, size_t len)
{
	char line[512], cur[128] = "";
	int found = 0;
	pid_t pid;
	FILE *f = spawn_read("dumpsys input", &pid);

	if (!f)
		return 0;
	while (fgets(line, sizeof line, f)) {
		char *s = trim(line);

		if (!strncmp(s, "Path: ", 6)) {
			snprintf(cur, sizeof cur, "%s", trim(s + 6));
		} else if (!found && !strcmp(cur, devpath) && !strncmp(s, "KeyLayoutFile: ", 15)) {
			snprintf(out, len, "%s", trim(s + 15));
			found = out[0] == '/';
		}
	}
	spawn_close(f, pid);
	return found;
}

/* Android's own search for a device's key layout file, for when dumpsys
 * doesn't list the device (yet). */
static int kl_path_probe(const struct dev *d, char *out, size_t len)
{
	static const char *const dirs[] = {
		"/product/usr/keylayout/", "/system_ext/usr/keylayout/", "/odm/usr/keylayout/",
		"/vendor/usr/keylayout/", "/system/usr/keylayout/", "/data/system/devices/keylayout/",
	};
	char names[4][160], *p;
	int nn = 0, i, j;

	if (d->vendor && d->product) {
		if (d->version)
			snprintf(names[nn++], sizeof names[0], "Vendor_%04x_Product_%04x_Version_%04x",
				 d->vendor, d->product, d->version);
		snprintf(names[nn++], sizeof names[0], "Vendor_%04x_Product_%04x", d->vendor, d->product);
	}
	snprintf(names[nn], sizeof names[0], "%s", d->name);
	for (p = names[nn]; *p; p++)
		if (!isalnum((unsigned char)*p) && *p != '-' && *p != '_')
			*p = '_';
	nn++;
	snprintf(names[nn++], sizeof names[0], "Generic");
	for (i = 0; i < nn; i++) {
		for (j = 0; j < (int)(sizeof dirs / sizeof dirs[0]); j++) {
			snprintf(out, len, "%s%.159s.kl", dirs[j], names[i]);
			if (access(fs(out), R_OK) == 0)
				return 1;
		}
	}
	out[0] = 0;
	return 0;
}

static const struct klfile *kl_load(const char *path)
{
	char line[512];
	struct klfile *k;
	FILE *f;
	int i;

	for (i = 0; i < nklfiles; i++)
		if (!strcmp(klfiles[i].path, path))
			return &klfiles[i];
	if (nklfiles == MAX_KL || !(f = fopen(fs(path), "r")))
		return NULL;
	k = &klfiles[nklfiles];
	memset(k, 0, sizeof *k);
	snprintf(k->path, sizeof k->path, "%s", path);
	while (fgets(line, sizeof line, f)) {
		char *s = line, *code, *key, *end;
		struct klentry **arr = &k->scan;
		int *n = &k->nscan;
		unsigned long v;

		strip_comment(s);
		s = trim(s);
		if (strcmp(next_word(&s), "key"))
			continue;   /* axis, led, sensor, ... */
		code = next_word(&s);
		if (!strcmp(code, "usage")) {
			arr = &k->usage;
			n = &k->nusage;
			code = next_word(&s);
		}
		key = next_word(&s);
		if (!*code || !*key)
			continue;
		errno = 0;
		v = strtoul(code, &end, 0);
		if (*end || errno)
			continue;
		if (!(*n % 64)) {
			struct klentry *grown = realloc(*arr, (*n + 64) * sizeof **arr);

			if (!grown)
				break;
			*arr = grown;
		}
		(*arr)[*n].code = (unsigned)v;
		snprintf((*arr)[*n].key, sizeof (*arr)[*n].key, "%s", key);
		(*n)++;
	}
	fclose(f);
	nklfiles++;
	return k;
}

static const struct klfile *kl_for(const struct dev *d)
{
	char path[256];

	if (kl_path_from_dumpsys(d->path, path, sizeof path) || kl_path_probe(d, path, sizeof path))
		return kl_load(path);
	return NULL;
}

/* The Android key for a key event, the way Android's key layout map finds
 * it: by HID usage first, then by kernel key code. NULL = none (UNKNOWN). */
static const char *kl_lookup(const struct klfile *k, unsigned usage, int code)
{
	int i;

	if (usage)
		for (i = 0; i < k->nusage; i++)
			if (k->usage[i].code == usage)
				return k->usage[i].key;
	for (i = 0; i < k->nscan; i++)
		if (k->scan[i].code == (unsigned)code)
			return k->scan[i].key;
	return NULL;
}

/* A kernel key that a key layout file turns into the given Android key, or
 * -1. Mouse/joystick/gamepad button codes are skipped, so the virtual remote
 * stays a plain keyboard-type device. */
static int kl_reverse(const struct klfile *k, const char *key)
{
	int i;

	for (i = 0; i < k->nscan; i++) {
		unsigned c = k->scan[i].code;

		if (!strcmp(k->scan[i].key, key) && c > 0 &&
		    (c < BTN_MISC || (c >= KEY_OK && c <= KEY_MAX)))
			return (int)c;
	}
	return -1;
}

static void load_global_keys(void)
{
	char line[512];
	int in_map = 0;
	pid_t pid;
	FILE *f;

	nglobalkeys = 0;
	f = spawn_read("dumpsys window policy", &pid);
	if (!f)
		return;
	while (fgets(line, sizeof line, f)) {
		char *s = trim(line), *eq, *end;

		if (strstr(s, "mKeyMapping")) {
			in_map = strchr(s, '{') != NULL;
			continue;
		}
		if (!in_map)
			continue;
		if (*s == '}') {
			in_map = 0;
			continue;
		}
		if (strncmp(s, "KEYCODE_", 8) || !(eq = strchr(s, '=')) || nglobalkeys == MAX_GLOBAL)
			continue;
		*eq = 0;
		end = eq + 1 + strcspn(eq + 1, ", \t");
		*end = 0;
		snprintf(globalkeys[nglobalkeys].key, sizeof globalkeys[0].key, "%s", s + 8);
		snprintf(globalkeys[nglobalkeys].owner, sizeof globalkeys[0].owner, "%s", eq + 1);
		nglobalkeys++;
	}
	spawn_close(f, pid);
}

static const char *global_key_owner(const char *key)
{
	int i;

	if (nglobalkeys < 0)
		load_global_keys();
	for (i = 0; i < nglobalkeys; i++)
		if (!strcmp(globalkeys[i].key, key))
			return globalkeys[i].owner;
	return NULL;
}

/* An Android key, plus a warning if the system keeps it for itself. */
static const char *describe_key(const char *key, char *buf, size_t len)
{
	const char *owner = global_key_owner(key);

	if (owner)
		snprintf(buf, len, "%s, but the system sends it to %s, never to the app on screen",
			 key, owner);
	else
		snprintf(buf, len, "%s", key);
	return buf;
}

/* What Android makes of a key, e.g. "PAGE_UP"; NULL when we can't tell. */
static const char *android_of(const struct klfile *k, unsigned usage, int code, char *buf, size_t len)
{
	const char *key;

	if (!k)
		return NULL;
	key = kl_lookup(k, usage, code);
	if (!key) {
		snprintf(buf, len, "UNKNOWN (apps ignore it)");
		return buf;
	}
	return describe_key(key, buf, len);
}

static void print_accessibility(void)
{
	char line[1024], *s, *tok, *save;
	pid_t pid;
	FILE *f = spawn_read("settings get secure enabled_accessibility_services", &pid);

	if (!f)
		return;
	if (!fgets(line, sizeof line, f))
		line[0] = 0;
	spawn_close(f, pid);
	s = trim(line);
	if (!*s)
		return;
	if (!strcmp(s, "null")) {
		printf("\nNo accessibility services are on, so no other app is catching button presses.\n");
		return;
	}
	printf("\nThese accessibility services can see (and swallow) button presses before\n"
	       "apps do. Turn off any remapper you're not using for these buttons:\n");
	for (tok = strtok_r(s, ":", &save); tok; tok = strtok_r(NULL, ":", &save))
		printf("  %s\n", tok);
}

/* -------------------------- virtual remote -------------------------- */
/*
 * `send` keys are pressed on a uinput device with no vendor/product ID, so
 * Android reads it with its standard key layout (normally Generic.kl). The
 * kernel key to press for an Android key comes from that same file.
 */

static int vfd = -1;                /* the virtual remote, -1 = none */
static const struct klfile *vkl;    /* the key layout Android reads it with */

static int has_send_rules(void)
{
	int i;

	for (i = 0; i < nrules; i++)
		if (rules[i].send)
			return 1;
	return 0;
}

/* Finds the kernel key for each send rule; returns how many have one. */
static int setup_send(void)
{
	struct dev v;
	char path[256];
	int i, n = 0;

	memset(&v, 0, sizeof v);
	snprintf(v.name, sizeof v.name, "%s", VIRT_NAME);
	vkl = kl_path_probe(&v, path, sizeof path) ? kl_load(path) : NULL;
	for (i = 0; i < nrules; i++) {
		if (!rules[i].send)
			continue;
		rules[i].send_code = vkl ? kl_reverse(vkl, rules[i].send) : -1;
		if (rules[i].send_code >= 0)
			n++;
	}
	return n;
}

static void create_virtual(void)
{
	struct uinput_setup us;
	int i, fd, n = 0;

	fd = open(fs("/dev/uinput"), O_WRONLY | O_NONBLOCK | O_CLOEXEC);
	if (fd < 0 && errno == ENOENT)
		fd = open(fs("/dev/input/uinput"), O_WRONLY | O_NONBLOCK | O_CLOEXEC);
	if (fd < 0) {
		logm("can't create the virtual remote (/dev/uinput: %s); sending keys with "
		     "the slower `input keyevent` instead", strerror(errno));
		return;
	}
	if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0)
		goto fail;
	for (i = 0; i < nrules; i++) {
		if (!rules[i].send || rules[i].send_code < 0)
			continue;
		if (ioctl(fd, UI_SET_KEYBIT, rules[i].send_code) < 0)
			goto fail;
		n++;
	}
	memset(&us, 0, sizeof us);
	us.id.bustype = BUS_VIRTUAL;
	us.id.version = 1;
	snprintf(us.name, sizeof us.name, "%s", VIRT_NAME);
	if (ioctl(fd, UI_DEV_SETUP, &us) < 0) {
		struct uinput_user_dev ud;   /* kernels older than 4.5 */

		memset(&ud, 0, sizeof ud);
		snprintf(ud.name, sizeof ud.name, "%s", VIRT_NAME);
		ud.id = us.id;
		if (write(fd, &ud, sizeof ud) != (ssize_t)sizeof ud)
			goto fail;
	}
	if (ioctl(fd, UI_DEV_CREATE) < 0)
		goto fail;
	vfd = fd;
	logm("virtual remote ready for %d key(s); Android reads it with %s", n, vkl->path);
	return;
fail:
	logm("can't create the virtual remote (%s); sending keys with the slower "
	     "`input keyevent` instead", strerror(errno));
	close(fd);
}

static void destroy_virtual(void)
{
	if (vfd < 0)
		return;
	ioctl(vfd, UI_DEV_DESTROY);
	close(vfd);
	vfd = -1;
}

static void virt_key(int code, int value)
{
	struct raw_event ev[2];

	memset(ev, 0, sizeof ev);
	ev[0].type = EV_KEY;
	ev[0].code = (unsigned short)code;
	ev[0].value = value;
	ev[1].type = EV_SYN;
	ev[1].code = SYN_REPORT;
	if (write(vfd, ev, sizeof ev) != (ssize_t)sizeof ev)
		logm("virtual remote: can't press %s: %s", key_name(code), strerror(errno));
}

static void run_cmd(const char *cmd, unsigned usage)
{
	pid_t pid = fork();

	if (pid == 0) {
		struct sigaction sa;

		memset(&sa, 0, sizeof sa);
		sa.sa_handler = SIG_DFL;
		sigaction(SIGCHLD, &sa, NULL);
		sigaction(SIGPIPE, &sa, NULL);
		sigaction(SIGHUP, &sa, NULL);
		setsid();
		execl(shell_path, "sh", "-c", cmd, (char *)NULL);
		_exit(127);
	}
	if (pid < 0)
		logm("can't run the action for %08x: %s", usage, strerror(errno));
}

/* Presses or releases a send rule's key for a button on device d. */
static void send_key(struct dev *d, int i, int down)
{
	const struct rule *r = &rules[i];

	if (!down) {
		if (d->held[i] && vfd >= 0)
			virt_key(r->send_code, 0);
		d->held[i] = 0;
		return;
	}
	if (d->held[i])
		return;
	if (vfd >= 0 && r->send_code >= 0) {
		virt_key(r->send_code, 1);
		d->held[i] = 1;
	} else {
		run_cmd(r->send_cmd, r->usage);   /* press and release in one go */
	}
}

static void release_all(struct dev *d)
{
	int i;

	for (i = 0; i < nrules; i++)
		if (d->held[i])
			send_key(d, i, 0);
}

/* ---------------------------- devices ----------------------------- */

static int get_keycode(int fd, unsigned usage, int *code)
{
	struct input_keymap_entry ke;

	memset(&ke, 0, sizeof ke);
	ke.len = sizeof usage;
	memcpy(ke.scancode, &usage, sizeof usage);
	if (ioctl(fd, EVIOCGKEYCODE_V2, &ke) < 0)
		return -1;
	*code = (int)ke.keycode;
	return 0;
}

static int set_keycode(int fd, unsigned usage, int code)
{
	struct input_keymap_entry ke;

	memset(&ke, 0, sizeof ke);
	ke.len = sizeof usage;
	memcpy(ke.scancode, &usage, sizeof usage);
	ke.keycode = (unsigned)code;
	return ioctl(fd, EVIOCSKEYCODE_V2, &ke);
}

static void identify(int fd, char *name, size_t len, unsigned *vendor, unsigned *product,
		     unsigned *version)
{
	struct input_id id;

	memset(name, 0, len);
	if (ioctl(fd, EVIOCGNAME(len - 1), name) < 0)
		snprintf(name, len, "?");
	*vendor = *product = 0;
	if (version)
		*version = 0;
	if (ioctl(fd, EVIOCGID, &id) == 0) {
		*vendor = id.vendor;
		*product = id.product;
		if (version)
			*version = id.version;
	}
}

static int matches_filter(const char *name, unsigned vendor, unsigned product)
{
	char id[16];
	int i;

	if (!nfilters)
		return 1;
	snprintf(id, sizeof id, "%04x:%04x", vendor, product);
	for (i = 0; i < nfilters; i++)
		if (!strcasecmp(filters[i], id) || strcasestr(name, filters[i]))
			return 1;
	return 0;
}

static void apply(struct dev *d, int restore)
{
	int i, changed = 0, failed = 0, err = 0;

	for (i = 0; i < nrules; i++) {
		int want = target_of(&rules[i]);

		if (d->orig[i] < 0 || want < 0)
			continue;
		if (restore) {
			/* Only undo what we actually changed. */
			if (!d->changed[i])
				continue;
			if (set_keycode(d->fd, rules[i].usage, d->orig[i]) == 0)
				d->changed[i] = 0;
			else
				logm("  %08x  could not put %s back: %s", rules[i].usage,
				     key_name(d->orig[i]), strerror(errno));
			continue;
		}
		if (d->orig[i] == want) {
			/* Already set, e.g. through another node of the same remote.
			 * Set it again through this node too: the kernel only lets the
			 * new key through on the node it was set on. */
			set_keycode(d->fd, rules[i].usage, want);
			continue;
		}
		if (set_keycode(d->fd, rules[i].usage, want) == 0) {
			char a[320];
			const char *ad = android_of(d->kl, rules[i].usage, want, a, sizeof a);

			d->changed[i] = 1;
			changed++;
			if (rules[i].send)
				logm("  %08x  %s -> %s (own key off; sends Android %s instead)",
				     rules[i].usage, key_name(d->orig[i]), key_name(want), rules[i].send);
			else if (rules[i].map_to < 0)
				logm("  %08x  %s -> %s (own key off; its action runs instead)",
				     rules[i].usage, key_name(d->orig[i]), key_name(want));
			else
				logm("  %08x  %s -> %s%s%s", rules[i].usage, key_name(d->orig[i]),
				     key_name(want), ad ? " = Android " : "", ad ? ad : "");
		} else {
			err = errno;
			failed++;
			logm("  %08x  %s -> %s FAILED: %s", rules[i].usage,
			     key_name(d->orig[i]), key_name(want), strerror(err));
		}
	}
	if (restore)
		return;
	if (failed && (err == EPERM || err == EACCES))
		logm("%s: Android refused to change %d button(s). map lines can't work here without "
		     "root; send, run and launch lines still work, but those buttons' own keys stay on.",
		     short_path(d->path), failed);
	else if (failed)
		logm("%s: %d button(s) could not be changed", short_path(d->path), failed);
	else if (!changed && !d->sibling)
		logm("%s: nothing to change (already set)", short_path(d->path));
}

/* A remote often has several nodes (keyboard, mouse, ...) sharing one
 * keymap. A node whose buttons already have the values another tracked node
 * of the same vendor:product just set is part of that same remote. */
static struct dev *sibling_of(const struct dev *d)
{
	int i, j;

	for (i = 0; i < MAX_DEVS; i++) {
		const struct dev *o = &devs[i];
		int changed = 0;

		if (o == d || !o->used || o->fd < 0 || o->sibling ||
		    o->vendor != d->vendor || o->product != d->product)
			continue;
		for (j = 0; j < nrules; j++) {
			int want = target_of(&rules[j]);

			if ((o->orig[j] < 0) != (d->orig[j] < 0) ||
			    (d->orig[j] >= 0 && want >= 0 && d->orig[j] != want))
				break;
			changed |= o->changed[j];
		}
		if (j == nrules && changed)
			return &devs[i];
	}
	return NULL;
}

static struct dev *find_dev(const char *path)
{
	int i;

	for (i = 0; i < MAX_DEVS; i++)
		if (devs[i].used && !strcmp(devs[i].path, path))
			return &devs[i];
	return NULL;
}

static void drop_dev(struct dev *d, const char *why)
{
	if (d->fd >= 0) {
		if (why)
			logm("%s: \"%s\" %s", short_path(d->path), d->name, why);
		release_all(d);   /* don't leave a key stuck down */
		close(d->fd);
	}
	memset(d, 0, sizeof *d);
	d->fd = -1;
}

static void ignore_dev(struct dev *d)
{
	if (d->fd >= 0)
		close(d->fd);
	d->fd = -1;
	d->ignored = 1;
}

static void examine(struct dev *d)
{
	int i, fd, n = 0;

	fd = open(fs(d->path), O_RDWR | O_NONBLOCK | O_CLOEXEC);
	if (fd < 0 && (errno == EACCES || errno == EPERM || errno == EROFS))
		fd = open(fs(d->path), O_RDONLY | O_NONBLOCK | O_CLOEXEC);
	if (fd < 0) {
		/* Right after a remote connects, the node can exist before its
		 * permissions are set; we retry on the next scan. */
		if (++d->tries >= OPEN_TRIES) {
			logm("%s: can't open it (%s), skipping it", short_path(d->path), strerror(errno));
			d->ignored = 1;
		} else if (verbose && d->tries == 1) {
			logm("%s: can't open it yet (%s), will retry", short_path(d->path), strerror(errno));
		}
		return;
	}
	d->fd = fd;
	identify(fd, d->name, sizeof d->name, &d->vendor, &d->product, &d->version);
	if (!strcmp(d->name, VIRT_NAME)) {
		if (verbose) {
			const struct klfile *k = kl_for(d);

			logm("%s: this is the virtual remote; Android reads it with %s",
			     short_path(d->path), k ? k->path : "(couldn't tell)");
		}
		ignore_dev(d);
		return;
	}
	if (!matches_filter(d->name, d->vendor, d->product)) {
		ignore_dev(d);
		return;
	}
	memset(d->changed, 0, sizeof d->changed);
	for (i = 0; i < nrules; i++) {
		int code;

		d->orig[i] = get_keycode(fd, rules[i].usage, &code) == 0 ? code : -1;
		if (d->orig[i] >= 0)
			n++;
	}
	if (!n) {
		ignore_dev(d);
		return;
	}
	d->have_scan = 0;
	{
		struct dev *sib = sibling_of(d);

		if (sib) {
			d->sibling = 1;
			d->kl = sib->kl;
			logm("%s: \"%s\" is another part of the same remote as %s",
			     short_path(d->path), d->name, short_path(sib->path));
			apply(d, 0);
			return;
		}
	}
	logm("%s: \"%s\" (%04x:%04x) has %d of the %d configured buttons",
	     short_path(d->path), d->name, d->vendor, d->product, n, nrules);
	if (verbose) {
		d->kl = kl_for(d);
		if (d->kl) {
			logm("%s: Android reads its keys with %s", short_path(d->path), d->kl->path);
			if (nglobalkeys < 0)
				load_global_keys();
		} else {
			logm("%s: couldn't tell which key layout file Android uses for it",
			     short_path(d->path));
		}
	}
	apply(d, 0);
}

static int cmp_int(const void *a, const void *b)
{
	return *(const int *)a - *(const int *)b;
}

/* Event node numbers in /dev/input, sorted. */
static int list_event_nodes(int *nums, int max)
{
	DIR *dir = opendir(fs(INPUT_DIR));
	struct dirent *de;
	int n = 0;

	if (!dir)
		return -1;
	while ((de = readdir(dir)) && n < max) {
		char *end;
		long v;

		if (strncmp(de->d_name, "event", 5))
			continue;
		v = strtol(de->d_name + 5, &end, 10);
		if (*end || end == de->d_name + 5)
			continue;
		nums[n++] = (int)v;
	}
	closedir(dir);
	qsort(nums, n, sizeof nums[0], cmp_int);
	return n;
}

static void rescan(void)
{
	int nums[MAX_DEVS * 2];
	int i, n = list_event_nodes(nums, MAX_DEVS * 2);

	if (n < 0) {
		logm("can't read %s: %s", INPUT_DIR, strerror(errno));
		return;
	}
	for (i = 0; i < MAX_DEVS; i++)
		devs[i].seen = 0;
	for (i = 0; i < n; i++) {
		char path[64];
		struct stat st;
		struct dev *d;
		int j;

		snprintf(path, sizeof path, "%s/event%d", INPUT_DIR, nums[i]);
		if (stat(fs(path), &st) < 0)
			continue;
		d = find_dev(path);
		if (d && (d->rdev != st.st_rdev || d->ino != st.st_ino)) {
			drop_dev(d, "disconnected");
			d = NULL;
		}
		if (!d) {
			for (j = 0; j < MAX_DEVS && devs[j].used; j++)
				;
			if (j == MAX_DEVS)
				continue;
			d = &devs[j];
			d->used = 1;
			d->fd = -1;
			snprintf(d->path, sizeof d->path, "%s", path);
			d->rdev = st.st_rdev;
			d->ino = st.st_ino;
		}
		d->seen = 1;
		if (d->fd < 0 && !d->ignored)
			examine(d);
	}
	for (i = 0; i < MAX_DEVS; i++)
		if (devs[i].used && !devs[i].seen)
			drop_dev(&devs[i], "disconnected");
}

/* ---------------------------- actions ----------------------------- */

static const struct rule *rule_for_usage(unsigned usage)
{
	int i;

	for (i = 0; i < nrules; i++)
		if (rules[i].usage == usage)
			return &rules[i];
	return NULL;
}

static void handle_event(struct dev *d, const struct raw_event *ev)
{
	const struct rule *r;

	if (ev->type == EV_MSC && ev->code == MSC_SCAN) {
		d->last_scan = (unsigned)ev->value;
		d->have_scan = 1;
		return;
	}
	if (ev->type == EV_SYN) {
		d->have_scan = 0;
		return;
	}
	if (ev->type != EV_KEY || ev->value == 2)   /* Android repeats keys itself */
		return;
	r = d->have_scan ? rule_for_usage(d->last_scan) : NULL;

	if (ev->value == 0) {
		if (r && r->send)
			send_key(d, (int)(r - rules), 0);
		else if (!d->have_scan)
			release_all(d);   /* a release that didn't say which button */
		return;
	}
	if (r && r->send) {
		if (verbose && !d->held[r - rules]) {
			char a[320];

			logm("%08x pressed -> Android %s%s", r->usage, describe_key(r->send, a, sizeof a),
			     (vfd >= 0 && r->send_code >= 0) ? "" : " (with input keyevent)");
		}
		send_key(d, (int)(r - rules), 1);
	} else if (r && r->cmd) {
		logm("%08x pressed -> %s", r->usage, r->what);
		run_cmd(r->cmd, r->usage);
	} else if (verbose) {
		char a[320];
		const char *ad = android_of(d->kl, d->have_scan ? d->last_scan : 0,
					    ev->code, a, sizeof a);

		if (d->have_scan)
			logm("%08x pressed: %s%s%s", d->last_scan, key_name(ev->code),
			     ad ? " = Android " : "", ad ? ad : "");
		else
			logm("%s pressed%s%s", key_name(ev->code),
			     ad ? " = Android " : "", ad ? ad : "");
	}
}

/* Returns -1 when the device has gone away. */
static int read_events(struct dev *d)
{
	struct raw_event ev[64];

	for (;;) {
		ssize_t n = read(d->fd, ev, sizeof ev);
		size_t i;

		if (n < 0) {
			if (errno == EINTR)
				continue;
			return (errno == EAGAIN || errno == EWOULDBLOCK) ? 0 : -1;
		}
		if (n == 0)
			return -1;
		for (i = 0; i < (size_t)n / sizeof ev[0]; i++)
			handle_event(d, &ev[i]);
		if ((size_t)n < sizeof ev)
			return 0;
	}
}

/* ----------------------------- main loop --------------------------- */

static void on_quit(int sig)
{
	(void)sig;
	quit_flag = 1;
}

static long long now_ms(void)
{
	struct timespec ts;

	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static int read_pid(void);

static int serve(int daemon)
{
	struct sigaction sa;
	long long last_scan;
	int i, ifd;

	memset(&sa, 0, sizeof sa);
	sa.sa_handler = on_quit;
	sigaction(SIGINT, &sa, NULL);
	sigaction(SIGTERM, &sa, NULL);
	if (!daemon)
		sigaction(SIGHUP, &sa, NULL);
	sa.sa_handler = SIG_IGN;
	if (daemon)
		sigaction(SIGHUP, &sa, NULL);
	sigaction(SIGPIPE, &sa, NULL);
	sigaction(SIGCHLD, &sa, NULL);   /* finished actions are reaped automatically */

	for (i = 0; i < MAX_DEVS; i++)
		devs[i].fd = -1;

	logm("remapd %s started with %d button rule(s) (log times are UTC)", VERSION, nrules);
	if (has_send_rules()) {
		int n = setup_send();

		if (!vkl)
			logm("can't find the key layout file Android would use for a virtual remote; "
			     "sending keys with the slower `input keyevent` instead");
		for (i = 0; vkl && i < nrules; i++)
			if (rules[i].send && rules[i].send_code < 0)
				logm("%08x: %s has no line in %s, so it's sent with the slower `input keyevent`",
				     rules[i].usage, rules[i].send, vkl->path);
		if (n)
			create_virtual();
	}
	ifd = inotify_init1(IN_NONBLOCK | IN_CLOEXEC);
	if (ifd >= 0 && inotify_add_watch(ifd, fs(INPUT_DIR), IN_CREATE | IN_DELETE | IN_ATTRIB |
					  IN_MOVED_TO | IN_MOVED_FROM) < 0) {
		close(ifd);
		ifd = -1;
	}
	if (ifd < 0)
		logm("can't watch %s for new devices, checking every %d s instead", INPUT_DIR, RESCAN_MS / 1000);

	rescan();
	for (i = 0; i < MAX_DEVS && !(devs[i].used && devs[i].fd >= 0); i++)
		;
	if (i == MAX_DEVS)
		logm("no connected device has these buttons yet; waiting for the remote to connect");
	last_scan = now_ms();

	while (!quit_flag) {
		struct pollfd pfd[MAX_DEVS + 1];
		int owner[MAX_DEVS + 1];
		int n = 0, r, k, need_scan = 0;
		long long wait = RESCAN_MS - (now_ms() - last_scan);

		if (ifd >= 0) {
			pfd[n].fd = ifd;
			pfd[n].events = POLLIN;
			owner[n++] = -1;
		}
		for (i = 0; i < MAX_DEVS; i++) {
			if (!devs[i].used || devs[i].fd < 0)
				continue;
			pfd[n].fd = devs[i].fd;
			pfd[n].events = POLLIN;
			owner[n++] = i;
		}
		r = poll(pfd, n, wait < 0 ? 0 : (int)wait);
		if (r < 0) {
			if (errno != EINTR) {
				logm("poll: %s", strerror(errno));
				sleep(1);
			}
			continue;
		}
		for (k = 0; k < n && r > 0; k++) {
			struct dev *d;

			if (!pfd[k].revents)
				continue;
			if (owner[k] < 0) {
				char buf[4096];

				while (read(ifd, buf, sizeof buf) > 0)
					;
				need_scan = 1;
				continue;
			}
			d = &devs[owner[k]];
			if ((pfd[k].revents & (POLLERR | POLLHUP | POLLNVAL)) || read_events(d) < 0) {
				drop_dev(d, "disconnected");
				need_scan = 1;
			}
		}
		if (need_scan || now_ms() - last_scan >= RESCAN_MS) {
			rescan();
			last_scan = now_ms();
		}
	}

	logm("stopping; putting the original button codes back");
	for (i = 0; i < MAX_DEVS; i++) {
		if (devs[i].used && devs[i].fd >= 0) {
			release_all(&devs[i]);
			apply(&devs[i], 1);
			close(devs[i].fd);
		}
	}
	destroy_virtual();
	if (daemon && read_pid() == getpid())
		unlink(fs(PID_FILE));
	return 0;
}

/* ----------------------------- commands ---------------------------- */

static int read_pid(void)
{
	char path[64], cmdline[256], *p;
	int pid = 0, fd;
	ssize_t n;
	FILE *f = fopen(fs(PID_FILE), "r");

	if (!f)
		return 0;
	if (fscanf(f, "%d", &pid) != 1)
		pid = 0;
	fclose(f);
	if (pid <= 0 || kill(pid, 0) < 0)
		return 0;
	snprintf(path, sizeof path, "/proc/%d/cmdline", pid);
	fd = open(path, O_RDONLY | O_CLOEXEC);
	if (fd < 0)
		return 0;
	n = read(fd, cmdline, sizeof cmdline - 1);
	close(fd);
	if (n <= 0)
		return 0;
	cmdline[n] = 0;
	/* Arguments are NUL-separated; look at each (argv[0] may be a wrapper). */
	for (p = cmdline; p < cmdline + n; p += strlen(p) + 1)
		if (strstr(p, "remapd"))
			return pid;
	return 0;
}

static int cmd_stop(int quiet)
{
	int pid = read_pid(), i;

	if (!pid) {
		if (!quiet)
			printf("remapd is not running\n");
		unlink(fs(PID_FILE));
		return 0;
	}
	kill(pid, SIGTERM);
	for (i = 0; i < 30 && kill(pid, 0) == 0; i++)
		usleep(100000);
	if (kill(pid, 0) == 0) {
		kill(pid, SIGKILL);
		usleep(200000);
	}
	unlink(fs(PID_FILE));
	if (!quiet)
		printf("Stopped the background remapd (pid %d); buttons are back to normal.\n", pid);
	return 0;
}

static int cmd_status(void)
{
	int pid = read_pid();

	if (pid)
		printf("remapd is running (pid %d). Log: %s\n", pid, LOG_FILE);
	else
		printf("remapd is not running\n");
	return pid ? 0 : 1;
}

static void cat_file(const char *path)
{
	char buf[4096];
	size_t n;
	FILE *f = fopen(path, "r");

	if (!f)
		return;
	while ((n = fread(buf, 1, sizeof buf, f)) > 0)
		fwrite(buf, 1, n, stdout);
	fclose(f);
}

static int cmd_start(const char *conf)
{
	pid_t pid;
	int status, fd;

	if (load_config(conf) < 0)
		return 1;
	cmd_stop(1);
	fflush(stdout);
	pid = fork();
	if (pid < 0) {
		perror("fork");
		return 1;
	}
	if (pid == 0) {
		FILE *f;

		setsid();
		pid = fork();
		if (pid != 0)
			_exit(pid < 0);
		fd = open("/dev/null", O_RDWR);
		if (fd >= 0)
			dup2(fd, 0);
		fd = open(fs(LOG_FILE), O_WRONLY | O_CREAT | O_TRUNC | O_APPEND, 0644);
		if (fd < 0)
			_exit(1);
		dup2(fd, 1);
		dup2(fd, 2);
		if (fd > 2)
			close(fd);
		if (chdir("/") < 0)
			_exit(1);
		f = fopen(fs(PID_FILE), "w");
		if (f) {
			fprintf(f, "%d\n", getpid());
			fclose(f);
		}
		setvbuf(stdout, NULL, _IOLBF, 0);
		_exit(serve(1));
	}
	waitpid(pid, &status, 0);
	sleep(1);   /* let it find the remote, then show what it did */
	cat_file(fs(LOG_FILE));
	pid = read_pid();
	if (!pid) {
		printf("remapd did not start; see the messages above.\n");
		return 1;
	}
	printf("\nremapd is running in the background (pid %d). Log: %s\n", pid, LOG_FILE);
	return 0;
}

static int cmd_run(const char *conf)
{
	int pid;

	if (load_config(conf) < 0)
		return 1;
	pid = read_pid();
	if (pid) {
		printf("Stopping the background remapd (pid %d) first; start it again with: remapd start %s\n",
		       pid, conf);
		cmd_stop(1);
	}
	verbose = 1;
	setvbuf(stdout, NULL, _IOLBF, 0);
	print_rules();
	printf("Press buttons to see their codes and the Android key each one becomes.\n"
	       "Ctrl+C stops and puts the buttons back.\n\n");
	return serve(0);
}

static int cmd_check(const char *conf)
{
	int nums[MAX_DEVS * 2], prev_codes[MAX_RULES];
	unsigned prev_vendor = 0, prev_product = 0;
	int i, j, n, shown = 0, prev_node = -1;

	if (load_config(conf) < 0)
		return 1;
	print_rules();
	if (has_send_rules()) {
		setup_send();
		if (vkl)
			printf("send keys are pressed on a virtual remote that Android reads with %s\n",
			       vkl->path);
		else
			printf("(Can't find the key layout file Android would use for a virtual remote; "
			       "send lines will use the slower input keyevent.)\n");
	}
	n = list_event_nodes(nums, MAX_DEVS * 2);
	for (i = 0; i < n; i++) {
		struct dev d;
		const struct klfile *kl;
		int fd, codes[MAX_RULES], have = 0;

		memset(&d, 0, sizeof d);
		snprintf(d.path, sizeof d.path, "%s/event%d", INPUT_DIR, nums[i]);
		fd = open(fs(d.path), O_RDONLY | O_NONBLOCK | O_CLOEXEC);
		if (fd < 0) {
			printf("\nevent%d: can't open (%s)\n", nums[i], strerror(errno));
			continue;
		}
		identify(fd, d.name, sizeof d.name, &d.vendor, &d.product, &d.version);
		if (!strcmp(d.name, VIRT_NAME)) {
			close(fd);
			continue;
		}
		for (j = 0; j < nrules; j++) {
			if (get_keycode(fd, rules[j].usage, &codes[j]) < 0)
				codes[j] = -1;
			else
				have++;
		}
		close(fd);
		if (!have || !matches_filter(d.name, d.vendor, d.product))
			continue;
		shown++;
		/* A remote often shows up as several nodes (keyboard, mouse, ...)
		 * sharing one keymap; don't print the same block again. */
		if (prev_node >= 0 && d.vendor == prev_vendor && d.product == prev_product &&
		    !memcmp(codes, prev_codes, nrules * sizeof codes[0])) {
			printf("\nevent%d \"%s\": another part of the same remote as event%d (same buttons)\n",
			       nums[i], d.name, prev_node);
			continue;
		}
		prev_node = nums[i];
		prev_vendor = d.vendor;
		prev_product = d.product;
		memcpy(prev_codes, codes, nrules * sizeof codes[0]);
		kl = kl_for(&d);
		printf("\nevent%d \"%s\" (%04x:%04x) has %d of these buttons.\n",
		       nums[i], d.name, d.vendor, d.product, have);
		if (kl)
			printf("Android reads its keys with %s\n", kl->path);
		else
			printf("(Couldn't find or read the key layout file Android uses for it.)\n");
		for (j = 0; j < nrules; j++) {
			const struct rule *r = &rules[j];
			int want = target_of(r);
			char a[320];
			const char *ad;

			if (codes[j] < 0) {
				printf("  %08x  not on this device\n", r->usage);
			} else if (r->map_to < 0) {
				const char *still = kl ? kl_lookup(kl, r->usage, KEY_UNKNOWN) : NULL;

				if (r->send && r->send_code >= 0)
					printf("  %08x  %s -> own key off; sends Android %s (virtual remote key %s)\n",
					       r->usage, key_name(codes[j]), describe_key(r->send, a, sizeof a),
					       key_name(r->send_code));
				else if (r->send && vkl)
					printf("  %08x  %s -> own key off; sends Android %s (with the slower "
					       "input keyevent: %s has no line for it)\n", r->usage,
					       key_name(codes[j]), describe_key(r->send, a, sizeof a),
					       short_path(vkl->path));
				else if (r->send)
					printf("  %08x  %s -> own key off; sends Android %s (with the slower "
					       "input keyevent)\n", r->usage, key_name(codes[j]),
					       describe_key(r->send, a, sizeof a));
				else
					printf("  %08x  %s -> own key off; on press: %s\n", r->usage,
					       key_name(codes[j]), r->what);
				if (still)
					printf("            note: Android also maps this button by its HID usage "
					       "(to %s), so its own key stays on\n", still);
			} else {
				ad = android_of(kl, r->usage, want, a, sizeof a);
				if (codes[j] == want)
					printf("  %08x  %s (already set)", r->usage, key_name(want));
				else
					printf("  %08x  %s -> %s", r->usage, key_name(codes[j]), key_name(want));
				printf("%s%s", ad ? " = Android " : "", ad ? ad : "");
				if (r->cmd)
					printf("; on press also: %s", r->what);
				putchar('\n');
			}
		}
	}
	if (!shown)
		printf("\nNo connected device has these buttons. Is the remote connected? (try: remapd list)\n");
	print_accessibility();
	return 0;
}

/* One input node that learn listens to. Its identity is read when it's
 * opened, since the node may be gone by the time the press is reported. */
struct lnode {
	int fd, num;
	char name[128];
	unsigned vendor, product, version;
	unsigned scan;
	int have_scan;
};

static int is_modifier(int code)
{
	return code == KEY_LEFTCTRL || code == KEY_RIGHTCTRL || code == KEY_LEFTSHIFT ||
	       code == KEY_RIGHTSHIFT || code == KEY_LEFTALT || code == KEY_RIGHTALT ||
	       code == KEY_LEFTMETA || code == KEY_RIGHTMETA;
}

/* Opens and grabs a node for learn, once nothing on it is held down (so
 * Android doesn't miss the release of a key that's already down). */
static int learn_open(struct lnode *out, int num)
{
	char path[64];
	unsigned char keys[KEY_MAX / 8 + 1];
	struct lnode node, *n = &node;
	int fd, i, tries;

	snprintf(path, sizeof path, "%s/event%d", INPUT_DIR, num);
	fd = open(fs(path), O_RDONLY | O_NONBLOCK | O_CLOEXEC);
	if (fd < 0)
		return -1;
	memset(n, 0, sizeof *n);
	identify(fd, n->name, sizeof n->name, &n->vendor, &n->product, &n->version);
	if (!strcmp(n->name, VIRT_NAME)) {
		close(fd);
		return -1;
	}
	for (tries = 0; tries < 40; tries++) {
		int down = 0;

		memset(keys, 0, sizeof keys);
		if (ioctl(fd, EVIOCGKEY(sizeof keys), keys) < 0)
			break;
		for (i = 0; i < (int)sizeof keys; i++)
			down |= keys[i];
		if (!down)
			break;
		usleep(50 * 1000);
	}
	if (ioctl(fd, EVIOCGRAB, 1) < 0)
		printf("warning event%d (%s): couldn't hold on to it (%s), so its presses still reach Android\n",
		       num, n->name, strerror(errno));
	n->fd = fd;
	n->num = num;
	*out = node;
	return 0;
}

/* Opens any input node that isn't open yet (a remote that was asleep
 * reconnects as a new node when a button wakes it). */
static void learn_scan(struct lnode *nodes, int *count, int max)
{
	int nums[MAX_DEVS * 2], n = list_event_nodes(nums, MAX_DEVS * 2), i, j;

	for (i = 0; i < n; i++) {
		for (j = 0; j < *count; j++)
			if (nodes[j].num == nums[i])
				break;
		if (j < *count) {
			if (nodes[j].fd < 0)   /* a node that went away, and came back */
				learn_open(&nodes[j], nums[i]);
		} else if (*count < max && learn_open(&nodes[*count], nums[i]) == 0) {
			(*count)++;
		}
	}
}

/* Waits for one button press, with the input devices grabbed so the press
 * doesn't reach Android, and says which button it was:
 *   learned <usage|none> <KEY_NAME|number> <vendor:product> <Android key|UNKNOWN|?> <global owner|-> <device name>
 * or "timeout". The devices are let go as soon as the button is released,
 * or when whoever started learn goes away (its output closes). */
static int cmd_learn(int seconds)
{
	struct lnode nodes[MAX_DEVS * 2];
	struct pollfd pfd[MAX_DEVS * 2 + 2];
	int count = 0, i, found = -1, code = -1, frame_open = 0, released = 0, gone = 0, ifd;
	unsigned usage = 0;
	long long deadline, next_scan;
	struct sigaction sa;

	memset(&sa, 0, sizeof sa);
	sa.sa_handler = on_quit;
	sigaction(SIGINT, &sa, NULL);
	sigaction(SIGTERM, &sa, NULL);
	sigaction(SIGHUP, &sa, NULL);
	sa.sa_handler = SIG_IGN;
	sigaction(SIGPIPE, &sa, NULL);

	{
		DIR *dir = opendir(fs(INPUT_DIR));

		if (!dir) {
			printf("error can't read %s: %s\n", INPUT_DIR, strerror(errno));
			return 1;
		}
		closedir(dir);
	}
	ifd = inotify_init1(IN_NONBLOCK | IN_CLOEXEC);
	if (ifd >= 0 && inotify_add_watch(ifd, fs(INPUT_DIR), IN_CREATE | IN_ATTRIB | IN_MOVED_TO) < 0) {
		close(ifd);
		ifd = -1;
	}
	learn_scan(nodes, &count, MAX_DEVS * 2);
	printf("listening for %d seconds: press the button\n", seconds);
	fflush(stdout);

	deadline = now_ms() + seconds * 1000LL;
	next_scan = now_ms() + 1000;
	while (!quit_flag && !released && !gone) {
		long long left = deadline - now_ms(), wait = next_scan - now_ms();
		int n = 0, r;

		if (left <= 0)
			break;
		for (i = 0; i < count; i++) {
			pfd[n].fd = nodes[i].fd;
			pfd[n++].events = POLLIN;
		}
		pfd[n].fd = STDOUT_FILENO;   /* only errors: whoever reads our output has gone */
		pfd[n++].events = 0;
		if (ifd >= 0) {
			pfd[n].fd = ifd;
			pfd[n++].events = POLLIN;
		}
		r = poll(pfd, n, (int)(wait < left ? (wait > 0 ? wait : 0) : left));
		if (r < 0) {
			if (errno == EINTR)
				continue;
			break;
		}
		if (pfd[count].revents & (POLLERR | POLLHUP)) {
			gone = 1;
			break;
		}
		if ((ifd >= 0 && (pfd[count + 1].revents & POLLIN)) || now_ms() >= next_scan) {
			char buf[4096];

			while (ifd >= 0 && read(ifd, buf, sizeof buf) > 0)
				;
			learn_scan(nodes, &count, MAX_DEVS * 2);
			next_scan = now_ms() + 1000;
		}
		for (i = 0; i < count && !released && pfd[i].fd == nodes[i].fd; i++) {
			struct raw_event ev[64];
			ssize_t got;
			size_t j;

			if (nodes[i].fd < 0 || !(pfd[i].revents & (POLLIN | POLLERR | POLLHUP | POLLNVAL)))
				continue;
			got = (pfd[i].revents & POLLIN) ? read(nodes[i].fd, ev, sizeof ev) : 0;
			if (got == 0 || (got < 0 && errno != EAGAIN && errno != EINTR)) {
				close(nodes[i].fd);   /* gone (the remote disconnected) */
				nodes[i].fd = -1;
				continue;
			}
			for (j = 0; got > 0 && j < (size_t)got / sizeof ev[0]; j++) {
				const struct raw_event *e = &ev[j];

				if (e->type == EV_MSC && e->code == MSC_SCAN) {
					nodes[i].scan = (unsigned)e->value;
					nodes[i].have_scan = 1;
				} else if (e->type == EV_SYN) {
					nodes[i].have_scan = 0;
					if (i == found)
						frame_open = 0;
				} else if (e->type == EV_KEY && e->value == 1 &&
					   (found < 0 || (i == found && frame_open && is_modifier(code) &&
							  !is_modifier(e->code)))) {
					/* A button that sends a key combination: the key, not its modifier. */
					found = i;
					code = e->code;
					usage = nodes[i].have_scan ? nodes[i].scan : 0;
					frame_open = 1;
					/* Hold on to the devices until the button is let go. */
					if (deadline > now_ms() + 3000)
						deadline = now_ms() + 3000;
				} else if (e->type == EV_KEY && e->value == 0 && i == found && e->code == code) {
					released = 1;
					break;
				}
			}
		}
	}
	for (i = 0; i < count; i++) {
		if (nodes[i].fd >= 0) {
			ioctl(nodes[i].fd, EVIOCGRAB, 0);
			close(nodes[i].fd);
		}
	}
	if (ifd >= 0)
		close(ifd);
	if (gone)
		return 1;
	if (found < 0) {
		printf("timeout\n");
		return 3;
	}

	{
		struct dev d;
		const struct klfile *k;
		const char *key, *owner, *kn = key_name(code);
		char u[16];

		/* What Android makes of it now (for a button remapd already changes, after the change). */
		memset(&d, 0, sizeof d);
		snprintf(d.path, sizeof d.path, "%s/event%d", INPUT_DIR, nodes[found].num);
		snprintf(d.name, sizeof d.name, "%s", nodes[found].name);
		d.vendor = nodes[found].vendor;
		d.product = nodes[found].product;
		d.version = nodes[found].version;
		k = kl_for(&d);
		key = k ? kl_lookup(k, usage, code) : NULL;
		owner = key ? global_key_owner(key) : NULL;
		if (!strncmp(kn, "key ", 4))
			kn += 4;
		if (usage)
			snprintf(u, sizeof u, "%08x", usage);
		else
			snprintf(u, sizeof u, "none");
		printf("learned %s %s %04x:%04x %s %s %s\n", u, kn, d.vendor, d.product,
		       key ? key : (k ? "UNKNOWN" : "?"), owner ? owner : "-", d.name);
	}
	return 0;
}

static int cmd_list(void)
{
	int nums[MAX_DEVS * 2];
	int i, n = list_event_nodes(nums, MAX_DEVS * 2);

	if (n < 0) {
		fprintf(stderr, "can't read %s: %s\n", INPUT_DIR, strerror(errno));
		return 1;
	}
	for (i = 0; i < n; i++) {
		char path[64], name[128];
		unsigned vendor, product;
		int fd;

		snprintf(path, sizeof path, "%s/event%d", INPUT_DIR, nums[i]);
		fd = open(fs(path), O_RDONLY | O_NONBLOCK | O_CLOEXEC);
		if (fd < 0) {
			printf("event%-3d  can't open: %s\n", nums[i], strerror(errno));
			continue;
		}
		identify(fd, name, sizeof name, &vendor, &product, NULL);
		close(fd);
		printf("event%-3d  %04x:%04x  %s\n", nums[i], vendor, product, name);
	}
	return 0;
}

static void usage(void)
{
	printf("remapd %s - fix the extra buttons on a Bluetooth TV remote (Android TV, no root)\n\n"
	       "  remapd list          list input devices\n"
	       "  remapd check CONF    show what CONF would change, without changing anything\n"
	       "  remapd run CONF      apply CONF and show each press and the Android key it\n"
	       "                       becomes (Ctrl+C to stop)\n"
	       "  remapd start CONF    apply CONF in the background (log: %s)\n"
	       "  remapd stop          stop the background copy and restore the buttons\n"
	       "  remapd status        is the background copy running?\n"
	       "  remapd learn [SECS]  wait for one button press (default 15 s) and say which\n"
	       "                       button it was; the press doesn't reach Android\n",
	       VERSION, LOG_FILE);
}

int main(int argc, char **argv)
{
	const char *cmd = argc > 1 ? argv[1] : "";

	if (access(shell_path, X_OK) != 0)
		shell_path = "/bin/sh";
	if (!getenv("PATH"))
		setenv("PATH", "/product/bin:/apex/com.android.runtime/bin:/system_ext/bin:"
			       "/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin", 1);

	if (getenv("REMAPD_TEST_ROOT"))
		fsroot = getenv("REMAPD_TEST_ROOT");

	if (!strcmp(cmd, "list") && argc == 2)
		return cmd_list();
	if (!strcmp(cmd, "stop") && argc == 2)
		return cmd_stop(0);
	if (!strcmp(cmd, "status") && argc == 2)
		return cmd_status();
	if (!strcmp(cmd, "learn") && (argc == 2 || argc == 3)) {
		int secs = argc == 3 ? atoi(argv[2]) : 15;

		if (secs >= 1 && secs <= 60)
			return cmd_learn(secs);
	}
	if (argc == 3) {
		if (!strcmp(cmd, "check"))
			return cmd_check(argv[2]);
		if (!strcmp(cmd, "run"))
			return cmd_run(argv[2]);
		if (!strcmp(cmd, "start"))
			return cmd_start(argv[2]);
	}
	usage();
	return 2;
}
