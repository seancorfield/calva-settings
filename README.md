# Calva Settings Audit (Babashka)

This project provides a Babashka script that compares Calva configuration values from three sources:

1. Calva defaults from the published Calva package on GitHub.
2. User VS Code settings.
3. Project VS Code settings.

The script prints one line per Calva property, with values in this order:

property | default | user | project

Fields are blank when a value is not present in that source.

## What It Reads

- Defaults URL:
  - https://raw.githubusercontent.com/BetterThanTomorrow/calva/published/package.json
- Project settings path:
  - <cwd>/.vscode/settings.json
- User settings candidates:
  - Explicit path from --user-settings (if provided)
  - Linux-style path: ~/.config/Code/User/settings.json
  - Optional Windows path built from --windows-user-dir
  - WSL scan path(s): /mnt/c/Users/*/AppData/Roaming/Code/User/settings.json

Notes:
- User/project settings are parsed as JSONC (comments and trailing commas supported).
- Warnings are printed to stderr when sources are missing/unreadable.

## Usage

### Running directly

Run from the project directory:

```bash
bb calva.bb
```

### Running as a dependency

Add the following to your `bb.edn` file:

```clojure
{:deps {io.github.seancorfield/calva-settings
        {:git/sha "35a5d67806e82e68f878554d529f9553ef622486"}}}
```

Then run:

```bash
bb -m calva
```

## Command-Line Arguments

The script accepts --key value pairs. These keys affect behavior:

- --cwd <path>
  - Working directory used to resolve project settings.
  - Project settings path becomes: <path>/.vscode/settings.json

- --user-settings <path>
  - Explicit user settings file to try first.

- --windows-user-dir <path>
  - Base Windows user profile directory used to build:
    - <path>/AppData/Roaming/Code/User/settings.json
  - Useful in WSL or mixed Linux/Windows setups.

## Examples

Use current directory defaults:

```bash
bb calva.bb
```

Point to a specific project folder:

```bash
bb calva.bb --cwd /path/to/project
```

Point to a specific user settings file:

```bash
bb calva.bb --user-settings /mnt/c/Users/your-user/AppData/Roaming/Code/User/settings.json
```

Provide Windows user profile directory instead of full file path:

```bash
bb calva.bb --windows-user-dir /mnt/c/Users/your-user
```

Combine arguments:

```bash
bb calva.bb --cwd /path/to/project --windows-user-dir /mnt/c/Users/your-user
```

## Output Example

```text
calva.autoConnectRepl | false | true |
calva.autoOpenInspector | false | false |
calva.paredit.defaultKeyMap | "strict" | "strict" |
```

This means:
- Column 1: setting name
- Column 2: Calva default value
- Column 3: user override value
- Column 4: project override value

## Note

All code in this repository was written by GitHub Copilot in this chat session.
