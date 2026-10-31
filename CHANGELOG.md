# Changelog

Releases are named after the app's version. The app carries remapd, whose own version is in brackets.

## 2.1 (remapd 1.3)

The first release.

- **remapd** makes the extra buttons on a Bluetooth TV remote work from an ADB shell, without root. It gives each button the key it's meant to send, presses keys Android would otherwise ignore, and can open apps or run commands.
- **The app sets up buttons when you press them.** The Buttons screen listens for a press and shows the button's code and what Android makes of it. You choose what the button does: send an Android key, open an app, do nothing, or run a shell command. The app writes `remapd.conf` and restarts remapd.
- **remapd starts by itself after a restart.** The app starts it through wireless debugging on the TV, signing in with its own adb key. After the one-time setup it needs no root, no Shizuku and no computer.
- **remapd comes inside the app.** The app puts it on the TV, and replaces it when the app brings a newer version.
