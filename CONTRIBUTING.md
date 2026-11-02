# Contributing

Thanks for helping. Bug reports, fixes and small features are all welcome.
For anything bigger, open an issue first so we can agree on the shape.

## Building and testing

See [Building](README.md#building). `mise run test` must pass, and please try
your change on a device or emulator; debug builds install beside the release
app as "Zagal dev". [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) explains how
the code is laid out.

`mise run e2e` runs the end-to-end flows in `e2e/flows` (Maestro) on the
device or emulator `adb` sees, against a throwaway host: a container
(`e2e/host`) with sshd and herdr-core, reached through `adb reverse`. It needs
podman or docker, and installs "Zagal e2e", an app of its own, so a debug
install keeps its hosts. Agents in the flows are shells that report themselves
as one; no real agent runs. `ZAGAL_E2E_HOLD=1` keeps the host up afterwards.
Scenarios in `e2e/scenarios` check what the terminal draws where only the
whole app can show it: they run a test pattern on the host (`e2e/host/zt`)
and look at screenshots. Run one with the connect flow before it:
`mise run e2e e2e/flows/connect.yaml e2e/scenarios/background.sh`. What a
unit test can check (`mise run test`: the terminal's drawing on the JVM, the
native session with `zig build test`) is checked there instead.

## Guidelines

- Match the code around you: its naming, comments and idioms.
- UI uses stock Material 3 components and Herdr's colors. Hand-drawn UI is
  kept to what Material doesn't have (attention marks, space avatars).
- Zagal follows herdr-core's protocol and herdr-app's behavior. A feature
  that needs something `hc` doesn't offer belongs in
  [herdr-core](https://github.com/herdrdev/herdr-core) first.
- Tests use mock data: no real host, machine or user names.
- Keep pull requests to one change, with a description of what it does and
  how you tried it.

## License

Contributions are licensed under Apache-2.0, the same as the project.
