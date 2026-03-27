#!/usr/bin/env python3
"""
logcat_analyzer.py — ペイントアプリ用ログ解析スクリプト

使い方:
    python3 logcat_analyzer.py <logfile>
    python3 logcat_analyzer.py <logfile> --category Brush
    python3 logcat_analyzer.py <logfile> --crashes-only
    python3 logcat_analyzer.py <logfile> --perf-threshold 16
    python3 logcat_analyzer.py <logfile> --json
    cat logcat.txt | python3 logcat_analyzer.py -

ログ形式 (PaintDebug 構造化ログ):
    PaintDebug.Brush: [DAB] x=100 y=200 r=5 p=0.5 color=#ffff0000 opacity=1.0
    PaintDebug.Perf:  [STROKE] dabs=120 elapsed=45ms avgDab=0.375ms
    PaintDebug.ASSERT: radius out of range: -3

標準 logcat 形式も解析可能:
    E/AndroidRuntime: FATAL EXCEPTION: main
    W/System.err: java.lang.NullPointerException
"""

import sys
import re
import json
import argparse
from collections import defaultdict, Counter
from dataclasses import dataclass, field, asdict
from typing import List, Optional, Dict, Tuple


# ─── データクラス ───

@dataclass
class Issue:
    severity: str        # CRITICAL / WARNING / INFO
    category: str        # Brush, Tile, Layer, Input, GL, Undo, Perf, Crash
    bug_id: str          # BUG-B001 等 (カタログ参照)
    title: str
    detail: str
    line_number: int
    raw_line: str

@dataclass
class PerfSample:
    category: str
    metric: str
    value: float
    unit: str
    line_number: int

@dataclass
class AnalysisReport:
    total_lines: int = 0
    paint_debug_lines: int = 0
    issues: List[Issue] = field(default_factory=list)
    crashes: List[dict] = field(default_factory=list)
    perf_samples: List[PerfSample] = field(default_factory=list)
    category_counts: Dict[str, int] = field(default_factory=lambda: defaultdict(int))
    summary: str = ""


# ─── パターン定義 ───

CRASH_PATTERNS = [
    (r"FATAL EXCEPTION", "CRITICAL", "Crash", "CRASH-FATAL", "Fatal exception detected"),
    (r"ANR in", "CRITICAL", "Crash", "CRASH-ANR", "Application Not Responding"),
    (r"OutOfMemoryError", "CRITICAL", "Crash", "BUG-M001", "Out of memory — check canvas/layer memory usage"),
    (r"StackOverflowError", "CRITICAL", "Crash", "CRASH-SOF", "Stack overflow — check recursive calls"),
]

EXCEPTION_PATTERNS = [
    (r"ArrayIndexOutOfBoundsException", "CRITICAL", "Tile", "BUG-T001",
     "Array index out of bounds — likely tile coordinate calculation error"),
    (r"ConcurrentModificationException", "CRITICAL", "Thread", "BUG-S001",
     "Concurrent modification — thread safety issue with shared collection"),
    (r"IllegalStateException.*GL", "CRITICAL", "GL", "BUG-S003",
     "GL illegal state — context lost or wrong thread"),
    (r"NullPointerException", "WARNING", "General", "NPE",
     "Null pointer — check for uninitialized references"),
    (r"IllegalArgumentException", "WARNING", "General", "IAE",
     "Illegal argument — check parameter validation"),
]

ASSERT_PATTERN = re.compile(r"PaintDebug\.ASSERT[:\s]+(.+)")

# PaintDebug structured log patterns
DAB_PATTERN = re.compile(
    r"PaintDebug\.Brush.*\[DAB\]\s+"
    r"x=([-\d.]+)\s+y=([-\d.]+)\s+r=(\d+)\s+"
    r"p=([\d.]+)\s+color=#([0-9a-fA-F]+)\s+opacity=([\d.]+)"
)

DIRTY_PATTERN = re.compile(
    r"PaintDebug\.Tile.*\[DIRTY\]\s+"
    r"tileX=([-\d]+)\s+tileY=([-\d]+)\s+layer=(\d+)\s+dirtyCount=(\d+)"
)

PERF_PATTERN = re.compile(
    r"PaintDebug\.Perf.*\[(\w+)\]\s+(.+)"
)

TRANSFORM_PATTERN = re.compile(
    r"PaintDebug\.Input.*\[TRANSFORM\]\s+"
    r"screen=\(([-\d.]+),([-\d.]+)\)\s+doc=\(([-\d.]+),([-\d.]+)\)\s+"
    r"zoom=([-\d.]+)"
)

GL_ERROR_PATTERN = re.compile(r"GL_INVALID_\w+|glError\s+0x[0-9a-fA-F]+|EGL_BAD_\w+")

FRAME_PERF_PATTERN = re.compile(
    r"\[FRAME\]\s+composite=(\d+)ms\s+upload=(\d+)ms\s+render=(\d+)ms"
)

STROKE_PERF_PATTERN = re.compile(
    r"\[STROKE\]\s+dabs=(\d+)\s+elapsed=(\d+)ms"
)


# ─── 解析エンジン ───

class LogcatAnalyzer:
    def __init__(self, perf_threshold_ms: float = 16.0):
        self.perf_threshold = perf_threshold_ms
        self.report = AnalysisReport()
        self._consecutive_zero_radius = 0
        self._consecutive_zero_dirty = 0
        self._crash_block: List[str] = []
        self._in_crash = False
        self._dab_count = 0
        self._nan_count = 0

    def analyze(self, lines: List[str]) -> AnalysisReport:
        self.report.total_lines = len(lines)

        for i, line in enumerate(lines, 1):
            line = line.rstrip()

            # PaintDebug lines count
            if "PaintDebug" in line:
                self.report.paint_debug_lines += 1

            # Check all pattern categories
            self._check_crashes(line, i)
            self._check_exceptions(line, i)
            self._check_assertions(line, i)
            self._check_dab_issues(line, i)
            self._check_dirty_issues(line, i)
            self._check_transform_issues(line, i)
            self._check_gl_errors(line, i)
            self._check_performance(line, i)

        # Finalize: check accumulated state
        self._finalize()
        self._generate_summary()
        return self.report

    def _check_crashes(self, line: str, lineno: int):
        for pattern, severity, category, bug_id, title in CRASH_PATTERNS:
            if re.search(pattern, line):
                self.report.issues.append(Issue(
                    severity=severity, category=category, bug_id=bug_id,
                    title=title, detail="", line_number=lineno, raw_line=line
                ))
                self._in_crash = True
                self._crash_block = [line]
                return

        if self._in_crash:
            self._crash_block.append(line)
            if "Caused by:" in line or len(self._crash_block) > 30:
                if "Caused by:" in line:
                    return  # keep collecting
            if len(self._crash_block) > 30 or (line.strip() == "" and len(self._crash_block) > 5):
                self.report.crashes.append({
                    "start_line": lineno - len(self._crash_block) + 1,
                    "trace": "\n".join(self._crash_block[:30])
                })
                self._in_crash = False
                self._crash_block = []

    def _check_exceptions(self, line: str, lineno: int):
        for pattern, severity, category, bug_id, title in EXCEPTION_PATTERNS:
            if re.search(pattern, line):
                self.report.issues.append(Issue(
                    severity=severity, category=category, bug_id=bug_id,
                    title=title, detail=line.strip(), line_number=lineno, raw_line=line
                ))

    def _check_assertions(self, line: str, lineno: int):
        m = ASSERT_PATTERN.search(line)
        if m:
            self.report.issues.append(Issue(
                severity="CRITICAL", category="Assert", bug_id="ASSERT",
                title=f"Runtime assertion failed: {m.group(1)[:80]}",
                detail=m.group(1), line_number=lineno, raw_line=line
            ))

    def _check_dab_issues(self, line: str, lineno: int):
        m = DAB_PATTERN.search(line)
        if not m:
            return

        self._dab_count += 1
        x, y, r, p, color, opacity = (
            float(m.group(1)), float(m.group(2)), int(m.group(3)),
            float(m.group(4)), m.group(5), float(m.group(6))
        )

        # BUG-B001: radius=0
        if r == 0:
            self._consecutive_zero_radius += 1
            if self._consecutive_zero_radius == 5:
                self.report.issues.append(Issue(
                    severity="WARNING", category="Brush", bug_id="BUG-B001",
                    title="Consecutive zero-radius dabs detected (brush draws nothing)",
                    detail=f"5+ dabs with radius=0. pressure={p}, check pressure mapping.",
                    line_number=lineno, raw_line=line
                ))
        else:
            self._consecutive_zero_radius = 0

        # Opacity check
        if opacity == 0.0:
            self.report.issues.append(Issue(
                severity="WARNING", category="Brush", bug_id="BUG-B001",
                title="Dab with opacity=0.0 (invisible)",
                detail="Check brush opacity parameter source.",
                line_number=lineno, raw_line=line
            ))

        # Alpha=0 in color
        if len(color) >= 8 and color[:2] == "00":
            self.report.issues.append(Issue(
                severity="WARNING", category="Color", bug_id="BUG-C001",
                title="Dab color has alpha=0 (invisible)",
                detail=f"color=#{color}. Brush color alpha channel is zero.",
                line_number=lineno, raw_line=line
            ))

        # NaN/Infinity check
        for val, name in [(x, "x"), (y, "y"), (p, "pressure"), (opacity, "opacity")]:
            if val != val or val == float('inf') or val == float('-inf'):
                self._nan_count += 1
                self.report.issues.append(Issue(
                    severity="CRITICAL", category="Brush", bug_id="BUG-MATH",
                    title=f"NaN/Infinity detected in dab {name}={val}",
                    detail="Check coordinate transform or pressure curve calculation.",
                    line_number=lineno, raw_line=line
                ))

    def _check_dirty_issues(self, line: str, lineno: int):
        m = DIRTY_PATTERN.search(line)
        if not m:
            return

        dirty_count = int(m.group(4))
        if dirty_count == 0:
            self._consecutive_zero_dirty += 1
            if self._consecutive_zero_dirty == 3:
                self.report.issues.append(Issue(
                    severity="WARNING", category="Tile", bug_id="BUG-T002",
                    title="Dirty tile count is 0 during stroke (display won't update)",
                    detail="Dabs may be placed outside canvas bounds, or dirty tracking is broken.",
                    line_number=lineno, raw_line=line
                ))
        else:
            self._consecutive_zero_dirty = 0

    def _check_transform_issues(self, line: str, lineno: int):
        m = TRANSFORM_PATTERN.search(line)
        if not m:
            return

        sx, sy = float(m.group(1)), float(m.group(2))
        dx, dy = float(m.group(3)), float(m.group(4))
        zoom = float(m.group(5))

        if zoom <= 0:
            self.report.issues.append(Issue(
                severity="CRITICAL", category="Transform", bug_id="BUG-X001",
                title=f"Zoom is non-positive: {zoom}",
                detail="Inverse transform will fail or produce inverted coordinates.",
                line_number=lineno, raw_line=line
            ))

        for val, name in [(dx, "doc.x"), (dy, "doc.y")]:
            if val != val:  # NaN check
                self.report.issues.append(Issue(
                    severity="CRITICAL", category="Transform", bug_id="BUG-X001",
                    title=f"NaN in document coordinates ({name})",
                    detail="Inverse matrix may be singular. Check determinant.",
                    line_number=lineno, raw_line=line
                ))

    def _check_gl_errors(self, line: str, lineno: int):
        m = GL_ERROR_PATTERN.search(line)
        if m:
            self.report.issues.append(Issue(
                severity="WARNING", category="GL", bug_id="BUG-G001",
                title=f"GL error: {m.group(0)}",
                detail="Check texture format, thread context, and state.",
                line_number=lineno, raw_line=line
            ))

    def _check_performance(self, line: str, lineno: int):
        # Frame timing
        m = FRAME_PERF_PATTERN.search(line)
        if m:
            composite, upload, render = int(m.group(1)), int(m.group(2)), int(m.group(3))
            total = composite + upload + render
            self.report.perf_samples.append(PerfSample(
                category="Frame", metric="total", value=total,
                unit="ms", line_number=lineno
            ))
            if total > self.perf_threshold:
                self.report.issues.append(Issue(
                    severity="WARNING", category="Perf", bug_id="BUG-M003",
                    title=f"Frame time {total}ms exceeds {self.perf_threshold}ms threshold",
                    detail=f"composite={composite}ms upload={upload}ms render={render}ms. "
                           f"Bottleneck: {'composite' if composite > upload and composite > render else 'upload' if upload > render else 'render'}",
                    line_number=lineno, raw_line=line
                ))

        # Stroke timing
        m = STROKE_PERF_PATTERN.search(line)
        if m:
            dabs, elapsed = int(m.group(1)), int(m.group(2))
            if dabs > 0:
                avg = elapsed / dabs
                self.report.perf_samples.append(PerfSample(
                    category="Stroke", metric="avgDabMs", value=avg,
                    unit="ms", line_number=lineno
                ))
                if avg > 1.0:
                    self.report.issues.append(Issue(
                        severity="WARNING", category="Perf", bug_id="BUG-M003",
                        title=f"Slow dab processing: {avg:.2f}ms/dab ({dabs} dabs in {elapsed}ms)",
                        detail="Consider optimizing dab mask generation or tile blending.",
                        line_number=lineno, raw_line=line
                    ))

    def _finalize(self):
        # Flush pending crash block
        if self._in_crash and self._crash_block:
            self.report.crashes.append({
                "start_line": self.report.total_lines - len(self._crash_block) + 1,
                "trace": "\n".join(self._crash_block[:30])
            })

        # Count by category
        for issue in self.report.issues:
            self.report.category_counts[issue.category] += 1

    def _generate_summary(self):
        critical = sum(1 for i in self.report.issues if i.severity == "CRITICAL")
        warning = sum(1 for i in self.report.issues if i.severity == "WARNING")
        info = sum(1 for i in self.report.issues if i.severity == "INFO")

        lines = [
            f"=== PaintDebug Log Analysis ===",
            f"Total lines analyzed: {self.report.total_lines}",
            f"PaintDebug lines: {self.report.paint_debug_lines}",
            f"Issues found: {critical} CRITICAL, {warning} WARNING, {info} INFO",
            f"Crashes: {len(self.report.crashes)}",
        ]

        if self.report.perf_samples:
            frame_samples = [s.value for s in self.report.perf_samples if s.category == "Frame"]
            if frame_samples:
                avg_frame = sum(frame_samples) / len(frame_samples)
                max_frame = max(frame_samples)
                lines.append(f"Frame time: avg={avg_frame:.1f}ms max={max_frame:.1f}ms ({len(frame_samples)} samples)")

        if self.report.category_counts:
            cats = ", ".join(f"{k}:{v}" for k, v in sorted(self.report.category_counts.items(), key=lambda x: -x[1]))
            lines.append(f"By category: {cats}")

        self.report.summary = "\n".join(lines)


# ─── 出力フォーマッタ ───

def format_text(report: AnalysisReport) -> str:
    out = [report.summary, ""]

    if report.crashes:
        out.append("━━━ CRASHES ━━━")
        for crash in report.crashes:
            out.append(f"  Line {crash['start_line']}:")
            for trace_line in crash['trace'].split('\n')[:10]:
                out.append(f"    {trace_line}")
            out.append("")

    # Group issues by severity
    for severity in ["CRITICAL", "WARNING", "INFO"]:
        issues = [i for i in report.issues if i.severity == severity]
        if not issues:
            continue
        out.append(f"━━━ {severity} ({len(issues)}) ━━━")
        # Deduplicate by bug_id + title
        seen = set()
        for issue in issues:
            key = (issue.bug_id, issue.title)
            if key in seen:
                continue
            seen.add(key)
            out.append(f"  [{issue.bug_id}] {issue.title}")
            if issue.detail:
                out.append(f"    → {issue.detail[:120]}")
            out.append(f"    Line: {issue.line_number}")
            out.append("")

    if not report.issues and not report.crashes:
        out.append("✅ No issues detected in log output.")

    return "\n".join(out)


def format_json(report: AnalysisReport) -> str:
    return json.dumps({
        "total_lines": report.total_lines,
        "paint_debug_lines": report.paint_debug_lines,
        "issues": [asdict(i) for i in report.issues],
        "crashes": report.crashes,
        "perf_samples": [asdict(s) for s in report.perf_samples],
        "category_counts": dict(report.category_counts),
        "summary": report.summary
    }, indent=2, ensure_ascii=False)


# ─── メイン ───

def main():
    parser = argparse.ArgumentParser(description="PaintDebug logcat analyzer")
    parser.add_argument("logfile", help="Log file path (or '-' for stdin)")
    parser.add_argument("--category", help="Filter by category (Brush, Tile, Layer, Input, GL, Undo, Perf)")
    parser.add_argument("--crashes-only", action="store_true", help="Show only crashes")
    parser.add_argument("--perf-threshold", type=float, default=16.0, help="Frame time threshold in ms (default: 16)")
    parser.add_argument("--json", action="store_true", help="Output as JSON")
    args = parser.parse_args()

    # Read input
    if args.logfile == "-":
        lines = sys.stdin.readlines()
    else:
        with open(args.logfile, "r", encoding="utf-8", errors="replace") as f:
            lines = f.readlines()

    # Analyze
    analyzer = LogcatAnalyzer(perf_threshold_ms=args.perf_threshold)
    report = analyzer.analyze(lines)

    # Filter
    if args.category:
        report.issues = [i for i in report.issues if i.category.lower() == args.category.lower()]
    if args.crashes_only:
        report.issues = [i for i in report.issues if i.category == "Crash"]

    # Output
    if args.json:
        print(format_json(report))
    else:
        print(format_text(report))

    # Exit code: 2 if critical, 1 if warnings, 0 if clean
    if any(i.severity == "CRITICAL" for i in report.issues) or report.crashes:
        sys.exit(2)
    elif any(i.severity == "WARNING" for i in report.issues):
        sys.exit(1)
    else:
        sys.exit(0)


if __name__ == "__main__":
    main()
