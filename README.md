<p align="center">
<img src="static/logo/logo.png" height="150" title="KryptEY Logo">
</p>

<h1 align="center">KryptEY - Secure E2EE communication</h1>

![GitHub version](https://img.shields.io/badge/version-v0.1.5-brightgreen)
![Chatkontrolle stoppen](https://img.shields.io/badge/chatkontrolle-stoppen-blueviolet)
![Stop scanning me](https://img.shields.io/badge/stop-scanning%20me-blueviolet)

An Android keyboard for secure end-to-end-encrypted messages through the signal protocol in any messenger.
Communicate securely and independent, regardless of the legal situation or whether messengers use
E2EE. No server needed.

KryptEY was created by [mellitopia](https://github.com/mellitopia)
and [amnesica](https://github.com/amnesica).

## Motivation

Breaking of end-to-end encryption (E2EE) by laws such as the planned EU chat control is an ongoing
issue. Content in messengers that use E2EE, such as Whatsapp or Signal, could thus be monitored by
third parties. E2EE is often, but not always, standard in messengers. There are proven methods for
E2EE such as PGP. However, these methods are sometimes cumbersomely integrated and require a lot of
effort to use.

KryptEY is an Android keyboard that implements the Signal protocol. The keyboard works
messenger-independently and both the PQXDH Key Agreement Protocol and the Double Ratchet Algorithm
work without a server, thus it enables a highly independent use of the protocol.

## Screenshots

<div style="display:flex" align="center">
  <img alt="App image" src="fastlane/metadata/android/en-US/images/phoneScreenshots/01.jpg" width="10.5%">
  <img alt="App image" src="fastlane/metadata/android/en-US/images/phoneScreenshots/02.jpg" width="10.5%">
  <img alt="App image" src="fastlane/metadata/android/en-US/images/phoneScreenshots/03.jpg" width="10.5%">
  <img alt="App image" src="fastlane/metadata/android/en-US/images/phoneScreenshots/04.jpg" width="10.5%">
  <img alt="App image" src="fastlane/metadata/android/en-US/images/phoneScreenshots/05.jpg" width="10.5%">
  <img alt="App image" src="fastlane/metadata/android/en-US/images/phoneScreenshots/06.jpg" width="10.5%">
  <img alt="App image" src="fastlane/metadata/android/en-US/images/phoneScreenshots/07.jpg" width="10.5%">
  <img alt="App image" src="fastlane/metadata/android/en-US/images/phoneScreenshots/08.jpg" width="10.5%">
  <img alt="App image" src="fastlane/metadata/android/en-US/images/phoneScreenshots/09.jpg" width="10.5%">
</div>

## Features

Based upon the [Simple Keyboard](https://github.com/rkkr/simple-keyboard) KryptEY adds a view above
the Keyboard for the E2EE functionality.

- use E2EE through Signal Protocol in any messenger
- encryption/decryption of messages
- enter message through separate text field in keyboard
- use clipboard to read messages
- manage contacts in own contact list in keyboard
- message log to view sent/received messages
- send messages as a compact binary envelope (raw mode) or behind a fairy-tale sentence with the
  payload in invisible characters (fairytale mode)
- keys are stored encrypted, sealed under an Android Keystore key that does not leave the device
- verification of your chat partner by comparing a security number with them by voice
- Q&A view helps with questions
- dark & light theme

See [this](/KRYPTEY.md) document for further information on how KryptEY is working.

## Demo

Conversation between Alice (left) and Bob (right) in the Signal Messenger using KryptEY.

<div style="display:flex;" align="center">
<img alt="App image" src="static/screenshots/demo.gif" width="80%">
</div>

## Download

<a href='https://f-droid.org/en/packages/com.amnesica.kryptey/'><img alt='Get it on F-Droid' src='https://gitlab.com/fdroid/artwork/-/raw/master/badge/get-it-on-en.png' height='60'/></a>
<a href='https://android.izzysoft.de/repo/apk/com.amnesica.kryptey'><img alt='Get it on IzzyOnDroid' src='https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png' height='60'/></a>
<a href='https://github.com/amnesica/KryptEY/releases'><img alt='Get it on Github' src='static/github/get-it-on-github.png' height='60'/></a>

KryptEY requires Android 8.0 or newer. If you need instructions on how to use the app, see our
help [here](/HELP.md)

## Privacy

Read our privacy statement [here](/PRIVACY.md)

## Permissions

- VIBRATE: Required for vibrations on key press

## Security

The Signal Protocol's cryptographic properties carry over to the keyboard: the same ratchet, the
same forward secrecy, the same post-quantum key agreement.

What does not carry over is how keys are distributed. Signal delivers them through its own server;
here they travel through the messenger you are protecting yourself from, and a key arriving for the
first time is accepted because there is nothing yet to compare it against. That is why comparing
security numbers by voice matters, and it is the only step that tells your chat partner apart from
the messenger.

**Key agreement is PQXDH**, the post-quantum variant, using libsignal 0.86.5. Each handshake combines
the elliptic curve X25519 with a Kyber-1024 key encapsulation, so an attacker who records traffic
today and gains a quantum computer later still cannot derive the session key. Earlier versions of
this app used X3DH, which is X25519 alone. The distinction is invisible from the outside — an X3DH
session establishes and carries messages identically — so the app asserts the negotiated session
version rather than assuming it.

The hash function SHA-256 is used for the various chains and AES-256 with CBC (Pkcs#7) is used for
the encryption of the messages. SHA-512 is also used to generate the fingerprint, the representation
of the public key used for encryption — the "security number" the two of you compare.

**Keys at rest are encrypted.** The identity key, sessions and pre-keys are sealed with AES-256-GCM
under a key held in the Android Keystore, which never leaves the device's secure hardware where the
device provides it. Earlier versions stored this material in cleartext.

Comparing security numbers by voice is what establishes that the key you hold is your chat partner's
rather than one the messenger supplied, and it is the only step that does. The app asks for it and
explains why in its own help.

## Limitations

The keyboard was designed as a POC and only allows 1-to-1 conversations. However, the application
can also be used in a group chat to a limited extent. Here, a message can be directed to a
specific chat partner and not to all people. Other participants of the group chat cannot decrypt
the message.

Text messages in Telegram are getting copied as HTML and not as plain text. When decoding the
message with the fairytale mode the copied message is compromised and can't be read properly.
Therefore, it can't be decoded at all. However, the raw mode works properly. When using KryptEY
with Telegram we recommend the raw mode.

Some messengers cap how much one message may carry - Threema, for example, at 3500 bytes.
KryptEY caps what you type at 500 bytes in both raw and fairytale mode, and that is a cap on your
plaintext, not on what the messenger carries. What travels is the encrypted envelope, which is
several times larger.

Measured, for a full 500-byte message: raw sends 3068 bytes, which fits. Fairytale sends about
13,800 bytes, which does not - every character it emits is an invisible one costing three bytes
each. An invite or an update message is larger again, and its size comes from the protocol rather
than from anything you typed.

So on a messenger with a limit that low, use raw mode. Fairytale mode is for messengers that will
carry it.

## Used libraries

- [Signal Protocol (android)](https://github.com/signalapp/libsignal)
- [Jackson](https://github.com/FasterXML/jackson)
- [Protobuf (lite)](https://github.com/protocolbuffers/protobuf/tree/main/java)
- [JUnit4](https://github.com/junit-team/junit4)

## Credits

- [AOSP Keyboard](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/)
- [Simple Keyboard](https://github.com/rkkr/simple-keyboard)
- [OpenBoard](https://github.com/openboard-team/openboard)
- [FlorisBoard](https://github.com/florisboard/florisboard)
