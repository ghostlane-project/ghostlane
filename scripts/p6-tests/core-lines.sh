#!/usr/bin/env bash
# The Rust core's config writer: shared Kotlin under net/core, one file per
# kind of line under net/core/lines, and every reference config of it read by
# what asks the core. Fails when
#   - a file of the writer is missing, no kind stands under lines/, a kind's
#     file does not declare its object or its own refusal, or IosCore.kt still
#     writes JSON itself;
#   - a file under net/core, or IosCore.kt, names SingBoxConfig: the Go builder
#     goes, and what the Rust writer needs of it is its own;
#   - a shared file of net/core names the lines package: a kind's shape, refusal
#     and outbound are its own file's, and the shared files know no kind;
#   - a kind came without its files: a kind is lines/<Kind>.kt in the app,
#     lines/<Kind>Fixture.kt and lines/<Kind>Surface.kt beside the tests, and
#     its reference configs, each with its Bypass Russia twin; a fixture or a
#     fragment of no kind fails too, and so does a list of kinds kept by hand
#     (the build makes it from the directories) or a build that does not;
#   - scripts/check-rust-core-configs.sh does not hand every *core-*.json
#     reference config to the core, or hands it another file;
#   - the rust_core scope of PR Checks does not start on such a file.
# It then proves itself: each rule is broken in a copy and must fail, and a
# kind added to a copy as files alone, with no other file edited, must pass.
# (Which reference a case needs is Kotlin's to say: CoreRegistry, run by the
# Gradle checks.)
#
#   scripts/p6-tests/core-lines.sh [root]     root defaults to the repository
# Needs bash, python3 and grep.
set -euo pipefail
root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
exec python3 -I - "$root" <<'PY'
import os, re, shutil, subprocess, sys, tempfile

NET = "sharedUI/src/commonMain/kotlin/org/olcbox/app/net"
CORE = NET + "/core"
LINES = CORE + "/lines"
IOS = NET + "/IosCore.kt"
SHARED = ["CoreConfig", "CoreLine", "CoreRefusal", "CoreDns", "CoreRouting", "CoreInbound", "CoreStream", "CoreSupport"]
REFERENCES = "sharedUI/src/jvmTest/resources/reference-configs"
PARSE = "scripts/check-rust-core-configs.sh"
CHECKS = ".github/workflows/pr-checks.yml"
LINES_PACKAGE = "org.olcbox.app.net.core.lines"
TESTS = "sharedUI/src/commonTest/kotlin/org/olcbox/app/net/core"
TEST_LINES = TESTS + "/lines"
BUILD = "sharedUI/build.gradle.kts"
# The one reference config that is no case's, and so has no twin.
ALONE = "bypass-ios-core-bundled-lists.json"
# What a kind's two files beside the tests are, by the end of their names.
BESIDE = {"Fixture": "CoreLineFixture", "Surface": "CoreSurfaceFragment"}

# What the parse script must take and must leave, by name.
CORE_NAMES = ["core-a.json", "bypass-core-a.json", "ios-core-b.json", "bypass-ios-core-b.json"]
OTHER_NAMES = ["desktop-tun.json", "ios-socks-b.json", "bypass-tun-b.json", "vless-reality.json"]

# A stand-in for the core's CLI: it writes down each file it is asked to check
# and says it bound the port of each config it is asked to run.
FAKE_CORE = r'''#!/usr/bin/env bash
if [ "$1" = config ]; then basename "$4" >> "$RECORD"; exit 0; fi
if [ "$1" = run ]; then
  port="$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["inbounds"][0]["port"])' "$3")"
  echo "bound inbound in at 127.0.0.1:$port"
  exec sleep 30
fi
exit 2
'''


def read(p):
    try:
        with open(p, encoding="utf-8") as f:
            return f.read()
    except (UnicodeDecodeError, OSError):
        return None


def kinds(root):
    d = os.path.join(root, LINES)
    return sorted(n[:-3] for n in os.listdir(d) if n.endswith(".kt")) if os.path.isdir(d) else []


def layout(root):
    bad = []
    for name in SHARED:
        if read(os.path.join(root, CORE, name + ".kt")) is None:
            bad.append("layout: %s/%s.kt is missing" % (CORE, name))
    found = kinds(root)
    if not found:
        bad.append("layout: no kind of line under %s" % LINES)
    for kind in found:
        text = read(os.path.join(root, LINES, kind + ".kt")) or ""
        if not re.search(r"\bobject\s+%s\s*:\s*CoreLine\b" % re.escape(kind), text):
            bad.append("layout: %s/%s.kt does not declare `object %s : CoreLine`" % (LINES, kind, kind))
        if "override fun refusal(" not in text:
            bad.append("layout: %s/%s.kt does not hold its kind's refusal" % (LINES, kind))
    ios = read(os.path.join(root, IOS))
    if ios is None:
        bad.append("layout: %s is missing" % IOS)
    elif "buildJsonObject" in ios:
        bad.append("layout: %s still writes JSON itself (buildJsonObject): the writer is net/core" % IOS)
    return bad


def no_go_builder(root):
    bad = []
    paths = [os.path.join(root, IOS)]
    for d, _, names in os.walk(os.path.join(root, CORE)):
        paths += [os.path.join(d, n) for n in names if n.endswith(".kt")]
    for p in sorted(paths):
        for i, line in enumerate((read(p) or "").splitlines(), 1):
            if "SingBoxConfig" in line:
                bad.append("go-builder: %s:%d names SingBoxConfig" % (os.path.relpath(p, root), i))
    return bad


def shared_know_no_kind(root):
    bad = []
    d = os.path.join(root, CORE)
    for n in sorted(os.listdir(d)) if os.path.isdir(d) else []:
        if not n.endswith(".kt"):
            continue
        for i, line in enumerate((read(os.path.join(d, n)) or "").splitlines(), 1):
            if LINES_PACKAGE in line:
                bad.append("shared: %s/%s:%d names the lines package: a kind's own rules stay in its file" % (CORE, n, i))
    return bad


def registry(root):
    bad = []
    found = kinds(root)
    d = os.path.join(root, TEST_LINES)
    beside = sorted(n[:-3] for n in os.listdir(d) if n.endswith(".kt")) if os.path.isdir(d) else []
    for kind in found:
        for ending, base in BESIDE.items():
            name = kind + ending
            text = read(os.path.join(d, name + ".kt"))
            if text is None:
                bad.append("registry: the kind %s has no %s/%s.kt" % (kind, TEST_LINES, name))
            elif not re.search(r"\bobject\s+%s\s*:\s*%s\b" % (re.escape(name), base), text):
                bad.append("registry: %s/%s.kt does not declare `object %s : %s`" % (TEST_LINES, name, name, base))
    for name in beside:
        for ending in BESIDE:
            if name.endswith(ending) and name[:-len(ending)] not in found:
                bad.append("registry: %s/%s.kt is of no kind under %s" % (TEST_LINES, name, LINES))
    # The list of kinds is the build's, made from the two directories.
    for src in (CORE, TESTS):
        if os.path.exists(os.path.join(root, src, "index")):
            bad.append("registry: %s/index is a list kept by hand: the build makes it from lines/" % src)
    build = read(os.path.join(root, BUILD)) or ""
    for need in ("kotlin.srcDir(generateCoreLineIndex)", "kotlin.srcDir(generateCoreTestIndex)",
                 '"%s"' % LINES.split("/", 1)[1], '"%s"' % TEST_LINES.split("/", 1)[1]):
        if need not in build:
            bad.append("registry: %s does not make the lists of kinds (%s is not in it)" % (BUILD, need))
    refs = os.path.join(root, REFERENCES)
    names = set(n for n in os.listdir(refs) if re.fullmatch(r".*core-.*\.json", n)) if os.path.isdir(refs) else set()
    if not names:
        bad.append("registry: no reference config (*core-*.json) under %s" % REFERENCES)
    for n in sorted(names - {ALONE}):
        twin = n[len("bypass-"):] if n.startswith("bypass-") else "bypass-" + n
        if twin not in names:
            bad.append("registry: the reference config %s has no %s beside it" % (n, twin))
    return bad


def parse_script(root):
    """The real script, run on stand-in references with a stand-in core."""
    script = read(os.path.join(root, PARSE))
    if script is None:
        return ["parse: %s is missing" % PARSE]
    tmp = tempfile.mkdtemp()
    try:
        os.makedirs(os.path.join(tmp, "scripts"))
        refs = os.path.join(tmp, REFERENCES)
        os.makedirs(refs)
        with open(os.path.join(tmp, PARSE), "w") as f:
            f.write(script)
        for name in CORE_NAMES + OTHER_NAMES:
            with open(os.path.join(refs, name), "w") as f:
                f.write('{"inbounds":[{"port":1}]}')
        fake = os.path.join(tmp, "fake-core")
        with open(fake, "w") as f:
            f.write(FAKE_CORE)
        os.chmod(fake, 0o755)
        record = os.path.join(tmp, "record")
        open(record, "w").close()
        run = subprocess.run(["bash", os.path.join(tmp, PARSE), fake], env=dict(os.environ, RECORD=record),
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=120)
        asked = sorted(read(record).split())
        bad = []
        if run.returncode != 0:
            bad.append("parse: %s failed on stand-in references: %s" % (PARSE, run.stdout.strip()[-300:]))
        for name in sorted(CORE_NAMES):
            if name not in asked:
                bad.append("parse: %s does not hand %s to the core" % (PARSE, name))
        for name in asked:
            if name not in CORE_NAMES:
                bad.append("parse: %s hands %s to the core, which is not a core.json" % (PARSE, name))
        return bad
    finally:
        shutil.rmtree(tmp)


def scope(root):
    text = read(os.path.join(root, CHECKS))
    if text is None:
        return ["scope: %s is missing" % CHECKS]
    m = re.search(r"if grep -qE '([^']+)' <<< \"\$changed\"; then\n\s*echo 'rust_core=true'", text)
    if not m:
        return ["scope: the rust_core scope of %s is not where this test reads it" % CHECKS]
    starts = lambda path: subprocess.run(["grep", "-qE", m.group(1)], input=path + "\n", text=True).returncode == 0
    bad = []
    for name in CORE_NAMES + ["bypass-ios-core-bundled-lists.json"]:
        if not starts("%s/%s" % (REFERENCES, name)):
            bad.append("scope: a change to %s does not start the core's check" % name)
    for path in [PARSE, CHECKS]:
        if not starts(path):
            bad.append("scope: a change to %s does not start the core's check" % path)
    for name in OTHER_NAMES:
        if starts("%s/%s" % (REFERENCES, name)):
            bad.append("scope: a change to %s starts the core's check, and it is no core.json" % name)
    return bad


# Each rule under the word its failures begin with.
RULES = {"layout": layout, "go-builder": no_go_builder, "shared": shared_know_no_kind, "registry": registry,
         "parse": parse_script, "scope": scope}


def check(root):
    return [b for rule in RULES.values() for b in rule(root)]


def edit(root, rel, old, new):
    p = os.path.join(root, rel)
    text = read(p)
    assert old in text, "nothing to break in %s: %r" % (rel, old)
    with open(p, "w") as f:
        f.write(text.replace(old, new, 1))


def append(root, rel, more):
    with open(os.path.join(root, rel), "a") as f:
        f.write(more)


def write(root, rel, text):
    p = os.path.join(root, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w") as f:
        f.write(text)


def new_kind(root):
    """A kind added as its files alone: no file that was there is touched."""
    write(root, LINES + "/Added.kt", "internal object Added : CoreLine {\n    override fun refusal(spec: OutboundSpec): String? = null\n}\n")
    write(root, TEST_LINES + "/AddedFixture.kt", "object AddedFixture : CoreLineFixture\n")
    write(root, TEST_LINES + "/AddedSurface.kt", "object AddedSurface : CoreSurfaceFragment\n")
    write(root, TEST_LINES + "/AddedTest.kt", "class AddedTest\n")
    for name in ("core-added.json", "bypass-core-added.json"):
        write(root, REFERENCES + "/" + name, "{}")


def selftest(root):
    """Break each rule in a copy of what the rules read: every one must be found."""
    kind = kinds(root)[0]
    reference = sorted(n for n in os.listdir(os.path.join(root, REFERENCES)) if n.startswith("bypass-") and n != ALONE)[0]
    probes = {
        "layout: a shared file gone": lambda d: os.remove(os.path.join(d, CORE, "CoreStream.kt")),
        "layout: a kind's file that declares another object": lambda d: edit(
            d, "%s/%s.kt" % (LINES, kind), "object %s" % kind, "object Other%s" % kind),
        "layout: a kind's file with no refusal": lambda d: edit(
            d, "%s/%s.kt" % (LINES, kind), "override fun refusal(", "fun refuses("),
        "layout: IosCore.kt writing JSON": lambda d: append(d, IOS, "private val x = buildJsonObject { }\n"),
        "go-builder: a shared file": lambda d: append(d, CORE + "/CoreDns.kt", "// as SingBoxConfig does\n"),
        "go-builder: a kind's file": lambda d: append(d, "%s/%s.kt" % (LINES, kind), "private val x = SingBoxConfig.X\n"),
        "go-builder: IosCore.kt": lambda d: append(d, IOS, "private val x = SingBoxConfig.X\n"),
        "shared: a shared file importing a kind": lambda d: append(
            d, CORE + "/CoreRefusal.kt", "import %s.%s\n" % (LINES_PACKAGE, kind)),
        "registry: a kind with no fixture": lambda d: os.remove(os.path.join(d, TEST_LINES, kind + "Fixture.kt")),
        "registry: a kind with no surface fragment": lambda d: os.remove(os.path.join(d, TEST_LINES, kind + "Surface.kt")),
        "registry: a fixture that declares another object": lambda d: edit(
            d, "%s/%sFixture.kt" % (TEST_LINES, kind), "object %sFixture" % kind, "object Some%sFixture" % kind),
        "registry: a fixture of no kind": lambda d: write(d, TEST_LINES + "/GhostFixture.kt", "object GhostFixture : CoreLineFixture\n"),
        "registry: a fragment of no kind": lambda d: write(d, TEST_LINES + "/GhostSurface.kt", "object GhostSurface : CoreSurfaceFragment\n"),
        "registry: a kind's file alone": lambda d: write(
            d, LINES + "/Bare.kt", "internal object Bare : CoreLine {\n    override fun refusal(spec: OutboundSpec): String? = null\n}\n"),
        "registry: a list of kinds kept by hand": lambda d: write(
            d, CORE + "/index/CoreLineIndex.kt", "internal val coreLineIndex = listOf(%s)\n" % kind),
        "registry: a build that lists no kinds": lambda d: edit(d, BUILD, "kotlin.srcDir(generateCoreLineIndex)", ""),
        "registry: a build that lists no fixtures": lambda d: edit(d, BUILD, "kotlin.srcDir(generateCoreTestIndex)", ""),
        "registry: a reference with no Bypass Russia twin": lambda d: os.remove(os.path.join(d, REFERENCES, reference)),
        "registry: a Bypass Russia reference alone": lambda d: write(d, REFERENCES + "/bypass-core-alone.json", "{}"),
        "parse: the old glob": lambda d: edit(d, PARSE, '"$references"/*core-*.json', '"$references"/ios-core-*.json'),
        "parse: every reference": lambda d: edit(d, PARSE, '"$references"/*core-*.json', '"$references"/*.json'),
        "scope: the old names": lambda d: edit(d, CHECKS, "[^/]*core-[^/]*", "(bypass-)?ios-core-"),
    }
    def copy():
        tmp = tempfile.mkdtemp()
        for rel in (CORE, TEST_LINES, PARSE, CHECKS, IOS, BUILD):
            src, dst = os.path.join(root, rel), os.path.join(tmp, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copytree(src, dst) if os.path.isdir(src) else shutil.copy(src, dst)
        # The references by their names: no rule here reads what is in them.
        for name in os.listdir(os.path.join(root, REFERENCES)):
            write(tmp, REFERENCES + "/" + name, "{}")
        return tmp

    tmp = copy()
    try:
        if check(tmp):
            return ["self-test: the copy fails before anything is broken in it"]
        # A kind is its files: added to the copy with nothing else edited, every rule still passes.
        new_kind(tmp)
        added = check(tmp)
        if added:
            return ["self-test: a kind added as files alone fails (%s)" % added[0]]
    finally:
        shutil.rmtree(tmp)
    for what, plant in probes.items():
        tmp = copy()
        try:
            plant(tmp)
            # The broken rule alone: the others were seen to pass on the copy.
            if not RULES[what.split(":")[0]](tmp):
                return ["self-test: broken '%s' was not found" % what]
        finally:
            shutil.rmtree(tmp)
    return []


root = sys.argv[1]
bad = check(root)
if not bad:
    bad = selftest(root)
for b in bad:
    print("FAIL " + b)
if bad:
    sys.exit(1)
print("ok   the core's config writer is net/core, each kind is its files and nothing else, and every *core-*.json"
      " is asked about (and the test finds each rule broken)")
PY
