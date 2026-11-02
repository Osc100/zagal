# Contributing

Thanks for helping. Bug reports, fixes and small features are all welcome.
For anything bigger, open an issue first so we can agree on the shape.

## Building and testing

See [Building](README.md#building). `mise run test` must pass, and please try
your change on a device or emulator; debug builds install beside the release
app as "Zagal dev". [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) explains how
the code is laid out.

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
