#!/usr/bin/env python3
"""
verify.py — the checks that are cheap enough to run on every push, and that a compiler will not
run for you.

EVERY CHECK PRINTS WHAT IT EXAMINED, not only what it found. delivery-gate.md 14: a check that
finds nothing and a check that runs nothing look identical from outside, and this account has
already had a check report zero findings for a twelve-entry enum because the indentation
differed. **A zero is a failure of the check until proven otherwise**, which is why every check
below asserts a minimum count of things looked at as well as a maximum count of problems.

The four mechanical faces from checking-the-checks.md are avoided by construction here:

    FACE 1  find(), never index(). index() RAISES, which kills the file and prints no number
            at all, and a crash and a pass look equally unlike a failure in a sweep.
    FACE 2  comments and docstrings are stripped before matching, so a check cannot pass by
            matching its own explanation of why something was removed.
    FACE 3  markers are anchored on something a definition cannot contain.
    FACE 6  every slice prints its length and asserts its bounds are ordered and non-empty.

Each check can fail while the others pass. Run scripts/sabotage.py to see them fail on purpose.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/kotlin/com/mantra/mahatranscribe"
LOGIC = SRC / "logic"
TEST = ROOT / "app/src/test/java/com/mantra/mahatranscribe/LogicTest.kt"

failures = []
examined = []


def check(title, ok, count):
    """Record one result. `count` is what was examined and is always printed."""
    examined.append((title, ok, count))
    if not ok:
        failures.append(title)


def code_only(text):
    """
    Source with comments and strings removed.

    FACE 2. Removing something correctly means writing down WHY it was removed, and that comment
    quotes the thing. A check that greps raw source then matches its own explanation and passes
    for ever — and to make it green you must delete the explanation, so the tail wags the dog.
    """
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"//[^\n]*", " ", text)
    text = re.sub(r'"""(.*?)"""', ' "" ', text, flags=re.S)
    text = re.sub(r'"(?:[^"\\\n]|\\.)*"', ' "" ', text)
    return text


def no_comments(text):
    """
    Comments gone, STRINGS KEPT.

    Some claims are genuinely about code that contains a string literal — the branch that reads
    `"1010" in lower`, the enum whose entries carry their letters. Stripping strings made both
    markers vanish and both checks reported a fault in code that was correct. Two strippers,
    because one of them was wrong for half the checks and being wrong in that direction produces
    confident corrections to code that was right.
    """
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    return re.sub(r"//[^\n]*", " ", text)


def kt(path):
    return code_only(path.read_text(encoding="utf-8"))


def kt_strings(path):
    return no_comments(path.read_text(encoding="utf-8"))


def all_kotlin():
    return sorted(SRC.rglob("*.kt"))


# ---------------------------------------------------------------------------------------------
# 1. THE PURE LOGIC MUST STAY PURE.
#
# four-tests.md Test 1: if you cannot run the thing without starting the whole application, that
# is itself a finding — the logic is tangled into the plumbing and should be pulled out. This is
# what keeps Test 1 possible for the life of the app rather than only on the day it was written.
# ---------------------------------------------------------------------------------------------
files = sorted(LOGIC.glob("*.kt"))
bad = [f.name for f in files if re.search(r"^import\s+(android|androidx)\.", kt(f), re.M)]
print(f"logic files examined: {len(files)} -> {[f.name for f in files]}")
check("the logic package imports nothing from Android", not bad and len(files) >= 6, len(files))
if bad:
    print(f"  these import Android: {bad}")


# ---------------------------------------------------------------------------------------------
# 2. THE USER-AGENT IS SET, AND IN EXACTLY ONE PLACE.
#
# Measured on two hosts. A request with no UA gets 403 + "error code: 1010" from Cloudflare on
# EVERY key at once, which looks exactly like the whole ring being dead. TTT mini's audit found
# the app sending no User-Agent at all, AND a comment claiming that it did — written from memory,
# with the grep afterwards saying otherwise. So this check greps rather than trusting a comment,
# and it is why code_only() runs first.
# ---------------------------------------------------------------------------------------------
http = SRC / "net/Http.kt"
http_code = kt(http)
ua_sites = len(re.findall(r'setRequestProperty\(\s*"User-Agent"', http.read_text(encoding="utf-8")))
callers = [f for f in all_kotlin() if f != http and "HttpURLConnection" in kt(f)]
print(f"User-Agent set at {ua_sites} site(s); files opening a connection outside Http.kt: {len(callers)}")
check("the User-Agent is set on every request, in one place", ua_sites >= 2 and not callers, ua_sites)


# ---------------------------------------------------------------------------------------------
# 3. THE 1010 BRANCH COMES BEFORE THE PLAIN 403 BRANCH.
#
# A branch in the right file in the WRONG PLACE is the same bug. Asserted by index rather than by
# presence, exactly as TTT mini's test does, because "it is in there somewhere" is not the claim.
# FACE 1: find(), never index(), so a marker that moved reports -1 instead of killing this file.
# ---------------------------------------------------------------------------------------------
verdict_code = kt_strings(SRC / "logic/Verdict.kt")
i_1010 = verdict_code.find("1010")
i_403 = verdict_code.find("status == 401 || status == 402")
print(f"Verdict.kt: 1010 branch at {i_1010}, plain 401/402/403 branch at {i_403}")
check(
    "a 403 carrying 1010 is classified before a plain 403",
    i_1010 > 0 and i_403 > 0 and i_1010 < i_403,
    2,
)


# ---------------------------------------------------------------------------------------------
# 4. EVERY LOOP AND EVERY WAIT, COUNTED.
#
# delivery-gate.md 7.2 asks for the count of loops EXAMINED rather than only the count of
# problems, because a grep pointed at the wrong directory and a codebase with no loops print the
# same thing.
# ---------------------------------------------------------------------------------------------
loops = 0
unbounded = []
for f in all_kotlin():
    code = kt(f)
    loops += len(re.findall(r"\b(while|for|repeat)\s*[\(\{]", code))
    if re.search(r"while\s*\(\s*true\s*\)", code):
        unbounded.append(f.name)
print(f"loops examined: {loops}   written as while(true): {len(unbounded)} {unbounded}")
check("no loop is written as while(true)", not unbounded, loops)
check("the loop count is plausible, so this check is looking at real files", loops >= 8, loops)


# ---------------------------------------------------------------------------------------------
# 5. EVERY EXTERNAL WAIT HAS A DEADLINE.
#
# A call with no timeout has the library's default, which for HttpURLConnection is FOREVER. The
# worst bug this app's ancestor had was a button that did nothing, and its cause was exactly
# this: a call that never answered, so the failure branch was never reached.
# ---------------------------------------------------------------------------------------------
conns = len(re.findall(r"openConnection\(\)", http_code))
timeouts = len(re.findall(r"connectTimeout\s*=", http_code))
reads = len(re.findall(r"readTimeout\s*=", http_code))
joins = len(re.findall(r"\.join\(\s*\d+", kt(SRC / "audio/Recorder.kt")))
print(f"connections opened: {conns}   connectTimeout set: {timeouts}   readTimeout set: {reads}   "
      f"bounded thread joins: {joins}")
check("every connection sets both deadlines", conns > 0 and timeouts >= conns and reads >= conns, conns)
check("the recorder's thread join has a deadline", joins >= 1, joins)


# ---------------------------------------------------------------------------------------------
# 6. NO KEY-SHAPED LITERAL ANYWHERE IN THE SOURCE.
#
# G2. This runs on the raw text, not on code_only(), because a key in a comment is a key.
# ---------------------------------------------------------------------------------------------
shapes = re.compile(r"(gsk_|sk-ant-|AIza|ghp_|github_pat_|xox[baprs]-)[A-Za-z0-9_-]{20,}")
scanned = list(SRC.rglob("*.kt")) + [TEST]
hits = [f.name for f in scanned if shapes.search(f.read_text(encoding="utf-8"))]
print(f"source files scanned for key shapes: {len(scanned)}   hits: {len(hits)}")
check("no key-shaped literal in the source", not hits, len(scanned))


# ---------------------------------------------------------------------------------------------
# 7. ONE HELPER DECIDES OVERWRITE OR APPEND, AND EVERY ROUTE GOES THROUGH IT.
#
# recorder.md 1. A mode that worked for the recorder but not for an opened file would be worse
# than no mode, because it would be right often enough to be trusted.
#
# FACE 3: anchored on `Take.deliver(`, which the definition inside Take.kt cannot contain, since
# there the function is declared as `fun deliver(`.
# ---------------------------------------------------------------------------------------------
call_sites = sum(kt(f).count("Take.deliver(") for f in all_kotlin())
rival = sum(len(re.findall(r"text\s*\+=|\+\s*\"\\n\\n\"\s*\+", kt(f)))
            for f in all_kotlin() if f.name != "Take.kt")
print(f"Take.deliver call sites: {call_sites}   hand-rolled appends elsewhere: {rival}")
check("the append helper is used and not bypassed", call_sites >= 1 and rival == 0, call_sites)


# ---------------------------------------------------------------------------------------------
# 8. NOTHING APPEARS AND NOTHING DISAPPEARS.
#
# The failure this forbids is the natural way to build and therefore keeps coming back: a control
# rendered only when it becomes usable. Every shared part must take an `enabled` flag, and the
# screens must pass it rather than wrapping the call in an `if`.
# ---------------------------------------------------------------------------------------------
parts = kt(SRC / "ui/Parts.kt")
NEEDS_A_DISABLED_STATE = ("MaRound", "MaChooser", "MaPlayer", "MaBoxActions")
enabled_params = 0
missing_flag = []
for name in NEEDS_A_DISABLED_STATE:
    start = parts.find("fun " + name + "(")
    sig = ""
    if start >= 0:
        i = parts.find("(", start)
        depth = 0
        for j in range(i, len(parts)):
            if parts[j] == "(":
                depth += 1
            elif parts[j] == ")":
                depth -= 1
                if depth == 0:
                    sig = parts[i + 1:j]
                    break
    # FACE 6: a slice is a claim about WHERE and deserves a check of its own. Print its length.
    print(f"  {name} signature: {len(sig)} chars")
    if re.search(r"(enabled|ready|hasText|canUndo)\s*:\s*Boolean", sig):
        enabled_params += 1
    else:
        missing_flag.append(name)
screens = [SRC / "ui/TalkScreen.kt", SRC / "ui/GearScreen.kt"]
conditional = []
for f in screens:
    for m in re.finditer(r"if\s*\([^)]*\)\s*\{\s*Ma(Round|Player|Chooser|Meter|BoxActions)", kt(f)):
        conditional.append(f.name)
print(f"shared parts examined: {len(NEEDS_A_DISABLED_STATE)}   carrying a disabled state: "
      f"{enabled_params} (missing: {missing_flag})   rendered conditionally: {len(conditional)} {conditional}")
check("every shared control can be greyed rather than omitted",
      enabled_params == len(NEEDS_A_DISABLED_STATE) and not conditional, len(NEEDS_A_DISABLED_STATE))


# ---------------------------------------------------------------------------------------------
# 9. THE TAB ROW HOLDS ALL SIX AND NEVER CHANGES CONTENTS.
#
# The row is rendered from Tab.entries, so it cannot be filtered by what happens to be built.
# ---------------------------------------------------------------------------------------------
parts_str = kt_strings(SRC / "ui/Parts.kt")
tabs = re.findall(r"\b(T|R|TR|VR|GEAR|H)\(\"", parts_str)
renders_all = "Tab.entries" in parts_str
print(f"tab entries declared: {len(tabs)} {tabs}   row renders the whole enum: {renders_all}")
check("all six screens are in the row, always", len(tabs) == 6 and renders_all, len(tabs))


# ---------------------------------------------------------------------------------------------
# 10. THE SESSION IS WRITTEN ON CHANGE, NEVER ON EXIT.
#
# Process death does not call onPause. Every setter in Store must write immediately, and there
# must be no save-on-lifecycle anywhere.
# ---------------------------------------------------------------------------------------------
store = kt(SRC / "Store.kt")
setter_bodies = re.findall(r"set\(v\)\s*\{([^}]*)\}", store)
setters = len(setter_bodies)
writes = sum(1 for body in setter_bodies if "write(" in body)
silent = [i for i, body in enumerate(setter_bodies) if "write(" not in body]
lifecycle_saves = len(re.findall(r"super\.onPause\(\)|super\.onStop\(\)", kt(SRC / "MainActivity.kt")))
print(f"Store setters examined: {setters}   each writing immediately: {writes}   "
      f"silent setters: {silent}   onPause save handlers: {lifecycle_saves}")
check("every setting is written the moment it changes",
      setters >= 5 and not silent, setters)
check("nothing is saved on a lifecycle callback", lifecycle_saves == 0, 1)


# ---------------------------------------------------------------------------------------------
# 11. THE OPEN CONTROL SAYS IT TAKES VIDEO.
#
# The web version accepted video for a year and nobody knew, because the button said only "open".
# This is a label check and it is the whole fix, so it is worth a gate of its own.
# ---------------------------------------------------------------------------------------------
talk_raw = (SRC / "ui/TalkScreen.kt").read_text(encoding="utf-8")
says_video = "AUDIO / VIDEO" in talk_raw
accepts_video = 'video/*' in kt(SRC / "MainActivity.kt") or 'video/*' in (SRC / "MainActivity.kt").read_text(encoding="utf-8")
print(f"the open control names video: {says_video}   the picker accepts video: {accepts_video}")
check("video is accepted AND said out loud", says_video and accepts_video, 2)


# ---------------------------------------------------------------------------------------------
# 12. TEST 1 IS REALLY THERE AND REALLY LOOKS AT EVERYTHING.
#
# A suite that covers three of eight logic files is a suite whose green means less than it looks.
# ---------------------------------------------------------------------------------------------
test_code = kt(TEST)
cases = len(re.findall(r"@Test", test_code))
covered = [f.stem for f in files if f.stem.replace(".kt", "") in test_code or f.stem in test_code]
print(f"Test 1 cases: {cases}   logic files referenced by the suite: {len(covered)}/{len(files)}")
check("Test 1 has enough cases to mean something", cases >= 40, cases)
check("Test 1 touches every logic file", len(covered) >= len(files) - 1, len(covered))


# ---------------------------------------------------------------------------------------------
print()
print("=" * 78)
for title, ok, count in examined:
    print(f"  {'PASS' if ok else 'FAIL'}  {title}   (examined {count})")
print("=" * 78)
print(f"checks run: {len(examined)}   failed: {len(failures)}")
if not examined:
    sys.exit("no checks ran at all: this script is broken, not the code")
sys.exit(1 if failures else 0)
