#!/usr/bin/env python3
"""
sabotage.py — break one rule at a time and confirm the thing that watches it goes red.

    A TEST YOU HAVE NEVER SEEN FAIL IS A RUMOUR.

four-tests.md meta-rule 1 and delivery-gate.md 14 say the same thing in different words: after a
check passes, make it fail on purpose. This walks every rule the app claims to follow, breaks
exactly one, and reports whether anything noticed.

**A mutation that SURVIVES is a rule nobody is actually checking**, and it is reported as a
failure of this script rather than as a curiosity.

FACE 5, and it is the reason this file uses Python string replacement rather than sed: a `sed`
whose pattern does not match exits 0 and changes nothing, the tests then pass because the code is
untouched, and that reads exactly like proof the check is useless. **Every mutation below asserts
its target exists before cutting, and asserts the bytes changed afterwards.** A mutation that did
not change the file is reported as a broken mutation, never as a passing check.

The first run of this script found three real faults in verify.py itself — a marker that its own
branch contained, a slice that ended at the wrong paren, and a stripper that removed the very
strings two checks were looking for. That is the expected yield. Your own checks are the most
likely thing to be wrong.
"""
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/kotlin/com/mantra/mahatranscribe"
TEST = ROOT / "app/src/test/java/com/mantra/mahatranscribe/LogicTest.kt"

# Each entry: what rule is being broken, which file, the exact text to cut, what to put back.
# The `old` string is asserted present before the cut, so a mutation whose target has moved is
# loud rather than silent.
MUTATIONS = [
    (
        "the logic package imports nothing from Android",
        SRC / "logic/Take.kt",
        "package com.mantra.mahatranscribe.logic",
        "package com.mantra.mahatranscribe.logic\n\nimport android.content.Context",
    ),
    (
        "a 403 carrying 1010 is classified before a plain 403",
        SRC / "logic/Verdict.kt",
        'if (status == 403 && "1010" in lower) {',
        'if (status == 403 && "one-zero-one-zero" in lower) {',
    ),
    (
        "no loop is written as while(true)",
        SRC / "logic/Ring.kt",
        "for (step in slots.indices) {",
        "while (true) { val step = 0",
    ),
    (
        "every connection sets both deadlines",
        SRC / "net/Http.kt",
        "connectTimeout = CONNECT_MS\n            readTimeout = READ_MS\n            setRequestProperty",
        "setRequestProperty",
    ),
    (
        "the User-Agent is set on every request, in one place",
        SRC / "net/Http.kt",
        'setRequestProperty("User-Agent", UA)\n            headers.forEach',
        "headers.forEach",
    ),
    (
        "no key-shaped literal in the source",
        SRC / "logic/Keys.kt",
        "enum class Provider",
        'const val EXAMPLE = "' + "g" + "sk_" + "A" * 30 + '"\n\n    enum class Provider',
    ),
    (
        "the append helper is used and not bypassed",
        SRC / "MainActivity.kt",
        "applyText(Take.deliver(text, result.text, mode))",
        'applyText(text + "\\n\\n" + result.text)',
    ),
    (
        "every shared control can be greyed rather than omitted",
        SRC / "ui/Parts.kt",
        "fun MaChooser(options: List<String>, chosen: String, enabled: Boolean = true, onPick: (String) -> Unit) {",
        "fun MaChooser(options: List<String>, chosen: String, onPick: (String) -> Unit) {\n    val enabled = true",
    ),
    (
        "all six screens are in the row, always",
        SRC / "ui/Parts.kt",
        'enum class Tab(val letter: String) { T("T"), R("R"), TR("TR"), VR("VR"), GEAR("\u2699"), H("H") }',
        'enum class Tab(val letter: String) { T("T"), GEAR("\u2699") }',
    ),
    (
        "every setting is written the moment it changes",
        SRC / "Store.kt",
        'set(v) { session.put("text", v); write(sessionFile, session) }',
        'set(v) { session.put("text", v) }',
    ),
    (
        "nothing is saved on a lifecycle callback",
        SRC / "MainActivity.kt",
        "    override fun onDestroy() {\n        super.onDestroy()",
        "    override fun onPause() {\n        super.onPause()\n        store.text = text\n    }\n\n"
        "    override fun onDestroy() {\n        super.onDestroy()",
    ),
    (
        "video is accepted AND said out loud",
        SRC / "ui/TalkScreen.kt",
        '"AUDIO / VIDEO"',
        '"OPEN"',
    ),
    (
        "Test 1 has enough cases to mean something",
        TEST,
        # ALL of them, not the first. See the note in the docstring: a mutation that changes the
        # file without changing the claim reads exactly like a check that cannot fail.
        "@Test",
        "@Ignore",
        True,
    ),
]


def run_verify():
    """verify.py's exit code. 0 is green, anything else is red."""
    r = subprocess.run(
        [sys.executable, str(ROOT / "scripts/verify.py")],
        capture_output=True, text=True,
    )
    return r.returncode


def main():
    print("Confirming the baseline is green before breaking anything.")
    if run_verify() != 0:
        sys.exit("verify.py is already red. Fix that before running the sabotage.")
    print("baseline: green\n")

    caught = 0
    survived = []
    broken_mutation = []

    for rule, path, old, new, *rest in MUTATIONS:
        every = bool(rest and rest[0])
        original = path.read_text(encoding="utf-8")
        if old not in original:
            # The target moved, which is why the cut would have missed. Loud, not silent.
            broken_mutation.append(rule)
            print(f"  MUTATION BROKEN  {rule}\n                   target not found in {path.name}")
            continue
        path.write_text(original.replace(old, new) if every else original.replace(old, new, 1),
                        encoding="utf-8")
        changed = path.read_text(encoding="utf-8") != original
        if not changed:
            broken_mutation.append(rule)
            print(f"  MUTATION BROKEN  {rule}   the file did not change")
            path.write_text(original, encoding="utf-8")
            continue

        red = run_verify() != 0
        path.write_text(original, encoding="utf-8")

        if red:
            caught += 1
            print(f"  CAUGHT     {rule}")
        else:
            survived.append(rule)
            print(f"  SURVIVED   {rule}   <- nothing is watching this")

    print()
    print("=" * 78)
    print(f"mutations attempted: {len(MUTATIONS)}   caught: {caught}   "
          f"survived: {len(survived)}   broken: {len(broken_mutation)}")
    print("=" * 78)

    if run_verify() != 0:
        sys.exit("the source was not restored cleanly: verify.py is red after the run")
    print("source restored, verify.py green again")

    # A survived mutation and a broken mutation are both failures of this script's purpose, and
    # they fail it, because a sabotage run that reports nothing looks exactly like a good one.
    sys.exit(1 if (survived or broken_mutation) else 0)


if __name__ == "__main__":
    main()
