# weealert

> **AI disclosure:** this app was written with AI-assisted coding (Claude by Anthropic). I direct the design, review the code and test it on my own device.

A tiny Android app that makes a different notification sound for each contact, from one source sound, so you can tell who it is without looking at the screen. Pure Java, no libraries, no permissions, tiny APK.

## How it works

Pick a source sound (any audio file Android can play, or the built-in bell) and how many sounds you need. weealert builds that many 1-3 note patterns out of the source: the rhythm and the pitch of each note change from sound to sound. Patterns are picked to be as far apart as possible, because rhythm and melody contour are much easier to learn by ear than tiny pitch shifts.

- The sounds are deterministic. Asking for more later keeps the earlier ones exactly as they were.
- Each sound is named with a random-looking adjective-noun name (`keen-acorn.wav`) that comes from the pattern itself, so a sound keeps its name.
- Tap a sound in the list to hear it at notification volume.
- **Export all** writes the WAV files into the folder you choose. Then set each file as a contact's notification tone inside WhatsApp, Telegram, Signal, Contacts and so on. Choosing the `Notifications` folder helps system tone pickers find the files.

Keep it to roughly 5-12 contacts you really need to tell apart by ear.

## Build

GitHub Actions only. `test.yml` (manual) builds a signed test APK that installs next to the real app, `release.yml` (manual) bumps the version, tags and publishes a signed APK. Both need the secrets `KEYSTORE_B64`, `KEYSTORE_PASSWORD` and `KEY_ALIAS`.

## License

GPL-3.0
