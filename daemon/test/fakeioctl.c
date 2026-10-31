/* LD_PRELOAD shim: FIFOs in /dev/input act like a HID remote with a keymap,
 * and a regular file at /dev/uinput acts like uinput.
 *
 * State per FIFO inode lives in $FAKE_DIR/<ino>.dev:
 *   name <text> / id <vendor hex> <product hex> / usage <hex> <keycode>
 * Every keymap change and grab is appended to $FAKE_DIR/ioctl.log, and uinput calls to
 * $FAKE_DIR/uinput.log. Events written to the virtual device land in the
 * /dev/uinput file itself. Creating the virtual device adds a FIFO node
 * /dev/input/event50 named like it (no keymap), destroying it removes it.
 * All of these live under $REMAPD_TEST_ROOT, like remapd's own paths. */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <linux/input.h>
#include <linux/uinput.h>

struct fdev { char name[256]; unsigned vendor, product; int n; unsigned usage[64]; int code[64]; };

static const char *fake_dir(void) { const char *d = getenv("FAKE_DIR"); return d ? d : "/tmp/fake"; }

static const char *under_root(const char *path)
{
	static char buf[4][512];
	static int n;
	const char *root = getenv("REMAPD_TEST_ROOT");

	n = (n + 1) % 4;
	snprintf(buf[n], sizeof buf[n], "%s%s", root ? root : "", path);
	return buf[n];
}

static int load(unsigned long ino, struct fdev *f)
{
	char path[256], line[512];
	FILE *fp;

	snprintf(path, sizeof path, "%s/%lu.dev", fake_dir(), ino);
	fp = fopen(path, "r");
	if (!fp)
		return -1;
	memset(f, 0, sizeof *f);
	while (fgets(line, sizeof line, fp)) {
		line[strcspn(line, "\n")] = 0;
		if (!strncmp(line, "name ", 5))
			snprintf(f->name, sizeof f->name, "%.250s", line + 5);
		else if (!strncmp(line, "id ", 3))
			sscanf(line + 3, "%x %x", &f->vendor, &f->product);
		else if (!strncmp(line, "usage ", 6) && f->n < 64 &&
			 sscanf(line + 6, "%x %d", &f->usage[f->n], &f->code[f->n]) == 2)
			f->n++;
	}
	fclose(fp);
	return 0;
}

static void save(unsigned long ino, const struct fdev *f)
{
	char path[256], tmp[300];
	FILE *fp;
	int i;

	/* Written whole, then renamed into place: the tests read these files
	 * while remapd changes them, and must see the old keymap or the new
	 * one, never part of one. */
	snprintf(path, sizeof path, "%s/%lu.dev", fake_dir(), ino);
	snprintf(tmp, sizeof tmp, "%s.%ld.tmp", path, (long)getpid());
	fp = fopen(tmp, "w");
	if (!fp)
		return;
	fprintf(fp, "name %s\nid %04x %04x\n", f->name, f->vendor, f->product);
	for (i = 0; i < f->n; i++)
		fprintf(fp, "usage %08x %d\n", f->usage[i], f->code[i]);
	fclose(fp);
	rename(tmp, path);
}

static void logf_(const char *file, const char *fmt, ...)
{
	char path[256];
	va_list ap;
	FILE *lg;

	snprintf(path, sizeof path, "%s/%s", fake_dir(), file);
	lg = fopen(path, "a");
	va_start(ap, fmt);
	vfprintf(lg, fmt, ap);
	va_end(ap);
	fclose(lg);
}

static int is_uinput(int fd)
{
	char link[64], target[256];
	ssize_t n;

	snprintf(link, sizeof link, "/proc/self/fd/%d", fd);
	n = readlink(link, target, sizeof target - 1);
	if (n < 0)
		return 0;
	target[n] = 0;
	return !strcmp(target, under_root("/dev/uinput"));
}

static int fake_uinput(unsigned long req, void *arg)
{
	if (getenv("FAKE_UINPUT_FAIL") && req == UI_DEV_CREATE) {
		errno = EPERM;
		return -1;
	}
	if (req == UI_SET_EVBIT) {
		logf_("uinput.log", "evbit %u\n", (unsigned)(uintptr_t)arg);
	} else if (req == UI_SET_KEYBIT) {
		logf_("uinput.log", "keybit %u\n", (unsigned)(uintptr_t)arg);
	} else if (req == UI_DEV_SETUP) {
		struct uinput_setup *us = arg;
		logf_("uinput.log", "setup name=%s bus=%x vendor=%x product=%x\n", us->name,
		      us->id.bustype, us->id.vendor, us->id.product);
	} else if (req == UI_DEV_CREATE) {
		char path[256];
		struct stat st;

		logf_("uinput.log", "create\n");
		unlink(under_root("/dev/input/tmp-event50"));
		if (mkfifo(under_root("/dev/input/tmp-event50"), 0660) == 0 && stat(under_root("/dev/input/tmp-event50"), &st) == 0) {
			snprintf(path, sizeof path, "%s/%lu.dev", fake_dir(), (unsigned long)st.st_ino);
			FILE *f = fopen(path, "w");
			fprintf(f, "name remapd virtual remote\nid 0000 0000\n");
			fclose(f);
			rename(under_root("/dev/input/tmp-event50"), under_root("/dev/input/event50"));
		}
	} else if (req == UI_DEV_DESTROY) {
		logf_("uinput.log", "destroy\n");
		unlink(under_root("/dev/input/event50"));
	} else {
		errno = ENOTTY;
		return -1;
	}
	return 0;
}

int ioctl(int fd, unsigned long req, ...)
{
	static int (*real)(int, unsigned long, ...);
	struct stat st;
	struct fdev f;
	va_list ap;
	void *arg;

	va_start(ap, req);
	arg = va_arg(ap, void *);
	va_end(ap);
	if (is_uinput(fd))
		return fake_uinput(req, arg);
	if (fstat(fd, &st) == 0 && S_ISFIFO(st.st_mode)) {
		if (load(st.st_ino, &f) < 0) { errno = ENOTTY; return -1; }
		if (_IOC_TYPE(req) == 'E' && _IOC_NR(req) == 0x06) {
			size_t len = _IOC_SIZE(req);
			strncpy(arg, f.name, len);
			return (int)strlen(f.name) + 1;
		}
		if (req == EVIOCGID) {
			struct input_id *id = arg;
			memset(id, 0, sizeof *id);
			id->bustype = BUS_BLUETOOTH;
			id->vendor = f.vendor;
			id->product = f.product;
			return 0;
		}
		if (req == EVIOCGKEYCODE_V2 || req == EVIOCSKEYCODE_V2) {
			struct input_keymap_entry *ke = arg;
			unsigned u;
			int i;

			if (ke->len != 4) { errno = EINVAL; return -1; }
			memcpy(&u, ke->scancode, 4);
			for (i = 0; i < f.n; i++) {
				if (f.usage[i] != u)
					continue;
				if (req == EVIOCGKEYCODE_V2) {
					ke->keycode = f.code[i];
					return 0;
				}
				if (getenv("FAKE_DENY_SET")) { errno = EPERM; return -1; }
				logf_("ioctl.log", "SET ino=%lu %08x %d -> %u\n", (unsigned long)st.st_ino, u, f.code[i], ke->keycode);
				f.code[i] = ke->keycode;
				save(st.st_ino, &f);
				return 0;
			}
			errno = EINVAL;
			return -1;
		}
		if (req == EVIOCGRAB) {
			logf_("ioctl.log", "GRAB ino=%lu %ld\n", (unsigned long)st.st_ino, (long)arg);
			return 0;
		}
		errno = ENOTTY;
		return -1;
	}
	if (!real)
		real = (int (*)(int, unsigned long, ...))dlsym(RTLD_NEXT, "ioctl");
	return real(fd, req, arg);
}
