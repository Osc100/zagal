# Security

Zagal holds an SSH key that logs into your machines, so security reports are
taken seriously.

## Reporting a vulnerability

Please don't open a public issue. Report it privately through
[GitHub's vulnerability reporting](https://github.com/Osc100/zagal/security/advisories/new),
with the steps to reproduce it and the Zagal and Android versions.

You'll get an answer within a week. Once it's fixed and released, the
advisory is published with credit to you, unless you'd rather not.

## Supported versions

Only the latest release gets fixes.

## In scope

- The phone's SSH key: anything that gets the private key out of the app
  other than through "Show the private key" in Settings, and its fingerprint
  or screen-lock check.
- Host-key checking: connecting to a host whose key doesn't match the pinned
  one, or trusting a key without showing it.
- Passwords: the one entered to install the key being stored, logged or sent
  anywhere but the host.
- The terminal: escape sequences from a host that read the phone's data,
  run anything on the phone, or reach another host.
- Deep links (`zagal://`) that do more than navigate.

Bugs in herdr-core itself belong to
[its project](https://github.com/herdrdev/herdr-core). Bugs in ghostty's VT
core belong to [ghostty](https://github.com/ghostty-org/ghostty), though
reports about how Zagal uses it are welcome here.
