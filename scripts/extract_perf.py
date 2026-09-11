#!/usr/bin/env python3
"""Extract benchmark lines from JUnit XML results and inject them into the wiki
Performance pages (ci-benchmarks blocks).

Usage:
  extract_perf.py extract --results build/test-results/test > bench.md
  extract_perf.py inject --bench bench.md --wiki-zh Performance.md \
      --wiki-en Performance-en.md --tag v1.0.0 --date 2026-09-11
"""
import argparse
import glob
import re
import sys
import xml.etree.ElementTree as ET

PERF = re.compile(r"^\[perf\]\s*(.*)$")
LIGHTNING = re.compile(
    r"SUMMARY cases=(\d+) supported=(\d+) falsePositive=(\d+) "
    r"engineError=(\d+) timeout=(\d+) totalElapsedMs=([\d.]+)")
BOUNDARY = re.compile(r"SUMMARY cases=(\d+) ok=(\d+)(?: feasible=(\d+))?")

BEGIN = "<!-- ci-benchmarks:start -->"
END = "<!-- ci-benchmarks:end -->"


def collect_lines(path):
    """Yield every text line contained in a JUnit XML file."""
    root = ET.parse(path).getroot()
    stack = [root]
    while stack:
        el = stack.pop()
        for text in (el.text, el.tail):
            if text:
                for line in text.splitlines():
                    yield line.strip()
        stack.extend(list(el))


def extract(results_dir):
    perf_lines = []
    lightning = None
    boundary = None
    tests_total = 0
    failures = 0
    for path in sorted(glob.glob(f"{results_dir}/TEST-*.xml")):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        tests_total += int(root.get("tests", 0))
        failures += int(root.get("failures", 0)) + int(root.get("errors", 0))
        for line in collect_lines(path):
            m = PERF.match(line)
            if m:
                perf_lines.append("[perf] " + m.group(1))
                continue
            m = LIGHTNING.search(line)
            if m:
                lightning = m.groups()
                continue
            m = BOUNDARY.search(line)
            if m:
                boundary = m.groups()

    out = []
    status = "OK" if failures == 0 else f"{failures} FAILURES"
    out.append(f"Tests: {tests_total} ({status})")
    out.append("")
    if lightning:
        out.append("| Suite | Result |")
        out.append("|---|---|")
        out.append(
            f"| Lightning benchmark | {lightning[1]}/{lightning[0]} SUPPORTED, "
            f"falsePositive={lightning[2]}, engineError={lightning[3]}, "
            f"timeout={lightning[4]}, total {lightning[5]} ms |")
    if boundary:
        out.append(
            f"| Boundary benchmark | {boundary[1]}/{boundary[0]} ok "
            f"(feasible={boundary[2]}) |")
    if perf_lines:
        out.append("")
        out.append("**Micro-benchmarks**")
        out.append("")
        out.append("```")
        out.extend(perf_lines)
        out.append("```")
    if not lightning and not boundary and not perf_lines:
        out.append("_No benchmark lines found in test results._")
    return "\n".join(out)


def inject(bench_path, wiki_zh, wiki_en, tag, date):
    with open(bench_path, encoding="utf-8") as f:
        bench = f.read().strip()
    header = (f"**{tag}** · {date} · GitHub Actions runner (ubuntu-latest, "
              f"JDK 25) — values are for cross-release trend comparison only")
    block = f"{BEGIN}\n{header}\n\n{bench}\n{END}"
    pattern = re.compile(re.escape(BEGIN) + r".*?" + re.escape(END), re.DOTALL)
    for path in (wiki_zh, wiki_en):
        try:
            with open(path, encoding="utf-8") as f:
                text = f.read()
        except FileNotFoundError:
            print(f"[inject] {path} not found, skipped", file=sys.stderr)
            continue
        if BEGIN in text and END in text:
            new = pattern.sub(lambda _: block, text, count=1)
        else:
            print(f"[inject] {path}: no ci-benchmarks block, skipped",
                  file=sys.stderr)
            continue
        with open(path, "w", encoding="utf-8") as f:
            f.write(new)
        print(f"[inject] {path} updated")


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p_ex = sub.add_parser("extract")
    p_ex.add_argument("--results", default="build/test-results/test")

    p_in = sub.add_parser("inject")
    p_in.add_argument("--bench", required=True)
    p_in.add_argument("--wiki-zh", required=True)
    p_in.add_argument("--wiki-en", required=True)
    p_in.add_argument("--tag", required=True)
    p_in.add_argument("--date", required=True)

    args = ap.parse_args()
    if args.cmd == "extract":
        print(extract(args.results))
    else:
        inject(args.bench, args.wiki_zh, args.wiki_en, args.tag, args.date)


if __name__ == "__main__":
    main()
