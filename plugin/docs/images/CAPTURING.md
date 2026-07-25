# Capturing the README screenshots — runbook

Everything needed to recreate the images in this folder from scratch, including
the environment quirks that are easy to trip over. Written for a Linux/X11
sandbox driven entirely from a shell — `xdotool` and `wmctrl` to drive the IDE,
ImageMagick `import`/`convert` to grab and compose windows. No IDE plugin or
external automation is needed. Adjust the concrete window id / coordinates — they
differ every run; discover them dynamically as shown.

## Demo project layout

- Sandbox IDE runs `./gradlew :plugin:runIde` with the project `~/beanshell-demo` open.
- `~/beanshell-demo/samples` is a **symlink** to this repo's `samples/`, so the
  showcase file is edited here but the IDE sees it at
  `~/beanshell-demo/samples/showcase.bsh`. **This symlink matters for breakpoints
  (see below).**
- All shots use `samples/showcase.bsh` (the hero file) and `pom.xml`
  (Maven injection). Default Darcula theme, IDE window ~1200×800.

## The three environment gotchas (read first)

1. **Full-screen grabs are black.** `import -window root` and `scrot` capture a
   black rectangle where the IDE is (the IDE renders on a compositor surface the
   root grab can't read). **Only per-window capture works:**
   `import -window <id> out.png`. This is why popups must be captured as their
   own windows and composited (below), not screenshotted "through" the IDE.
2. **Synthetic keys need real focus.** `xdotool key --window <id> …` is ignored
   by the IDE (Swing). You must focus the window first and send keys to the
   focused window:
   ```bash
   wmctrl -i -a 0x03800a97      # focus by hex id (xdotool windowactivate may fail on this WM)
   xdotool key ctrl+Home        # NO --window: goes to the focused window
   xdotool type "text"
   ```
   Get the id + geometry: `wmctrl -lG` (title contains "beanshell-demo"),
   `printf '0x%x\n' <decimal-id>`, `xdotool getwindowgeometry <id>`.
3. **External edits don't auto-reload.** After editing `showcase.bsh` on disk,
   the IDE keeps its stale buffer. Force a reload: focus editor →
   `Ctrl+Shift+A` (Find Action) → type `Reload from Disk` → Enter. Screenshot the
   editor to confirm before continuing. (Do NOT clean up typed
   text with `Home`,`Shift+End`,`Delete` — a trailing `Delete` on an empty line
   eats the newline and merges the next line. Prefer editing the file on disk +
   reload, or `Ctrl+Z`.)

## Capturing a popup and compositing it onto the editor

Completion, Quick Documentation and the Alt+Enter quick-fix are **separate
override-redirect windows**, absent from the IDE window capture. Recipe:

1. Trigger the popup (keystroke below). Wait ~2s for it to render.
2. Capture the editor **behind** it: `import -window <IDE_ID> base.png`
   (the popup won't appear in this — that's expected).
3. Find the popup window and its absolute geometry. Diffing the window tree is
   flaky (ids get reused), so scan by geometry instead:
   ```bash
   xwininfo -root -tree | sed 's/^ *//' \
     | awk '{for(i=1;i<=NF;i++) if($i ~ /^[0-9]+x[0-9]+\+[0-9]+\+[0-9]+$/) print $1,$i}' \
     | grep -vE '10x10|1x1|3x3|8192|1200x800|1920x'   # drop chrome; keep popup-sized
   ```
   Read `Absolute upper-left X/Y` and `Width/Height` with `xwininfo -id <win>`.
4. Capture just the popup: `import -window <POPUP_ID> pop.png`.
5. Composite onto the editor at the **offset = popup_abs − IDE_abs**:
   ```bash
   convert base.png pop.png -geometry +$((PX-IX))+$((PY-IY)) -composite out.png
   ```
   The quick-fix (Alt+Enter) shows **two** windows — the action list (~213×69)
   **and** a preview strip (~302×30) to its right. Composite **both**:
   ```bash
   convert base.png bulb.png -geometry +Xb+Yb -composite \
                    preview.png -geometry +Xp+Yp -composite inspection.png
   ```

## Per-screenshot recipes

Coordinates below are IDE-window-relative; `Ctrl+G` jumps to `line:col`.
Line numbers assume the current `showcase.bsh` (factorial spans lines 23–31).

- **editor.png** — open `showcase.bsh`, `Alt+7` (Structure), `Ctrl+Home`.
  Capture the IDE window. Hero shot; keep the Debug tool window closed (bug icon
  in the left stripe toggles it).
- **completion.png** — put the caret on the blank line just below `factorial`
  (line 32), type `f`, `Ctrl+Space`. The list shows `factorial()` (method icon)
  and `first` (variable icon) alongside keywords — so the function body stays
  visible above the popup. NB: completion **after a dot** (`greeter.`) only
  offers keywords, not members, so use a bare-prefix trigger instead. Composite.
- **maven-injection.png** — open `pom.xml`, `Ctrl+Home`. The BeanShell inside
  `<script>` shows the injection background + BSH syntax. Capture the IDE window.
- **navigation.png** — caret on an `append` in the chain (`57:10`), `Ctrl+Q`
  (Quick Documentation) → `java.lang.StringBuilder append(Object)`. Composite the
  popup (~375×425). Make sure no stray breakpoint dot is in the gutter.
- **inspection.png** — add a throwaway `int unused = 42;` on its own line (edit
  on disk + reload), caret on `unused`, `Alt+Enter` → "Remove declaration" bulb +
  preview strip. Composite both windows, then delete the throwaway line.
- **debugger.png** — see next section.

## Debugging (debugger.png)

- **Breakpoints must be on the path the IDE uses — the symlink**
  (`~/beanshell-demo/samples/showcase.bsh`), **not** the repo's canonical path
  (`…/bsh-plugin/samples/showcase.bsh`). A breakpoint on the canonical path
  verifies in the UI but the BeanShell debugger never matches it (hit count
  stays 0 and the script runs to completion). Set it by clicking the editor
  gutter next to the line — that binds to the file the editor has open, i.e. the
  symlink path.
- **A run configuration must exist first.** None exist on a fresh project;
  create the temporary `showcase.bsh` config (type BeanShell) by focusing the
  editor and pressing `Ctrl+Shift+F10` (Run context configuration) once. Then
  start debugging with `Shift+F9` (debugs the selected configuration), or
  right-click the file → *Debug 'showcase.bsh'*.
- **Two debug sessions open per run:** `showcase.bsh` (the **BeanShell** session
  — line breakpoints + script variables, single frame) and
  `BeanShell (JVM attach)` (the **Java** JDWP session). The BeanShell one is the
  one that pauses on `.bsh` breakpoints: when it hits, the script blocks and the
  editor highlights the line — screenshot to confirm you're paused. The Java tab
  just runs through unless you set breakpoints in Java code.
- `factorial` has a `print(...)` trace (so the Console shows output) and locals
  `sub`/`result` (so the Variables panel shows `n`, `sub`, `result`). Put the
  breakpoint on `return result;`; it first pauses for `n=2, sub=1, result=2`
  after the console has printed `factorial(1) / factorial(2) / factorial(1)`.
- **Show variables and console together:** the debug panel shows one tab at a
  time. Drag the **Console** tab onto the right half of the debug panel content
  to split it — then Variables (left) and Console (right) are visible in one
  frame. `xdotool mousemove <console-tab> mousedown 1 … mousemove <right-side> …
  mouseup 1`.
- A leftover inline "factorial" hint sometimes floats in the editor while paused;
  click an empty editor spot + `Escape` to clear it before capturing.

## Cleanup after a session

- Stop every debug session and remove breakpoints.
- Delete any throwaway lines you added for a shot (e.g. `int unused = 42;`).

## Note: the JetBrains MCP tools (optional)

The sandbox may also expose JetBrains MCP tools, but the procedure above
deliberately doesn't use them: screenshots need the per-window `import` grab
regardless, and keystrokes cover the rest. The one place MCP is genuinely
convenient is the debugger — it can start a session, tell which of the two
sessions is paused, and read breakpoints/variables as structured data. Even
there it is optional, and note its `set_breakpoint` binds to the canonical repo
path, which the BeanShell debugger does **not** match — click the gutter instead.
