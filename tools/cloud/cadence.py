#!/usr/bin/env python3
"""Cadence summary of a natural run: how regularly do mine sections and build steps happen, where are the long idle gaps?

Usage: tools/cloud/cadence.py [--until=<tick>] <log> [<log2> ...]   (logs of ONE world in order, e.g. label.log label-cont1.log)

Input (no rcon needed, everything comes from the server log):
  * "Mine <name>: Abschnitt N gegraben (Tick T, ..." lines (always there)
  * "Bauplan <type> gestartet/fertiggestellt" lines (always there)
  * optional: "CADENCE t=<tick> d=<daytime> mine=... plan=<type>/<step> projects=... | name:profession:activity@x,y,z#jobsDone ..." lines, one every
    100 ticks, and "MTRACE t=<tick> <name> <state> ..." lines from the miner code. They come from temporary instrumentation that is NOT part
    of the mod (an END_SERVER_TICK probe that logs JobManager.activityOf for every villager near the bell, plus a trace call per mine decision).
    Without them only mine sections and plan events are available; start natural-run.sh with VP_STATUS_DUMP=1 to also get the per-sprint
    `villagerplus status` snapshots in <log>.status (taken between sprints, no rcon needed while a sprint runs).
Working hours = the work phases of Config (day time 1000-2800, 3300-5000, 6500-8300, 8800-10500 = 7000 ticks a day; breaks, lunch, evening and
night do not count; ticks where >=40% of the villagers shelter from a thunderstorm do not count either). With the probe's d= field the real day time
is used, otherwise game time + 992 because natural-run.sh sets the clock to 1000 at game tick ~8).
Idle interval = working ticks between two consecutive real steps (mine section, plan/project step); night ticks are not counted.
"""
import re
import statistics
import sys
from collections import Counter

DAY = 24000
OFFSET = 992  # natural-run.sh: 'time set 1000' at game tick ~8


DAYTIME = {}  # game tick -> day time (from the probe's d= field); empty = fall back to OFFSET


def in_work_window(d):
    """Work phases of Config (WORK_A/WORK_B), without morning, breaks, lunch, evening and night."""
    return 1000 <= d < 2800 or 3300 <= d < 5000 or 6500 <= d < 8300 or 8800 <= d < 10500


STORM = set()  # sample ticks where most villagers shelter from a thunderstorm (not a village fault)


def working(t):
    if DAYTIME:
        d = DAYTIME.get(t - t % 100)
        if d is not None:
            return in_work_window(d) and (t - t % 100) not in STORM
    return in_work_window((t + OFFSET) % DAY)


def work_ticks(a, b):
    """Working-hour ticks in [a, b)."""
    n = 0
    t = a
    while t < b:
        step = min(b, t + 100)
        if working(t):
            n += step - t
        t = step
    return n


def parse(paths):
    sections = []  # (tick, index, worker, blocks)
    samples = []  # (t, mine_index, plan, projects, {name: (prof, activity, pos, jobs)})
    plan_events = []
    traces = []
    for p in paths:
        for line in open(p, errors="replace"):
            m = re.search(r"Mine (\S+): Abschnitt (\d+) gegraben \(Tick (\d+), (\d+) Bloecke", line)
            if m:
                sections.append((int(m.group(3)), int(m.group(2)), m.group(1), int(m.group(4))))
                continue
            m = re.search(r"Bauplan (\S+) (gestartet|fertiggestellt).*?(?:Tick (\d+))?", line)
            if m and "Bauplan" in line and ("gestartet bei" in line or "fertiggestellt" in line):
                t = re.search(r"Tick (\d+)", line)
                plan_events.append((int(t.group(1)) if t else None, m.group(1), m.group(2)))
                continue
            if "MTRACE t=" in line:
                mt = re.search(r"MTRACE t=(\d+) (\S+) (\S+)", line)
                if mt:
                    traces.append((int(mt.group(1)), mt.group(2), mt.group(3)))
                continue
            if "CADENCE t=" in line:
                head, _, rest = line.partition("CADENCE ")
                parts = rest.rstrip("\n").split(" | ")
                h = parts[0]
                t = int(re.search(r"t=(\d+)", h).group(1))
                dm = re.search(r" d=(\d+)", h)
                if dm:
                    DAYTIME[t] = int(dm.group(1))
                mi = re.search(r"Abschnitt (\d+)", h)
                plan = re.search(r"plan=(\S+)", h)
                proj = re.search(r"projects=(\S*)", h)
                vill = {}
                for v in parts[1:]:
                    mm = re.match(r"(.*?):(.*?):(.*)@(-?\d+),(-?\d+),(-?\d+)#(\d+)$", v.strip())
                    if mm:
                        vill[mm.group(1)] = (mm.group(2), mm.group(3), (int(mm.group(4)), int(mm.group(5)), int(mm.group(6))), int(mm.group(7)))
                if vill and sum(1 for v in vill.values() if "Unwetter" in v[1]) * 10 >= len(vill) * 4:
                    STORM.add(t)
                samples.append((t, int(mi.group(1)) if mi else 0, plan.group(1) if plan else "", proj.group(1) if proj else "", vill))
    sections.sort()
    return sections, samples, plan_events, traces


def stats(label, events, t_end):
    """events: sorted list of ticks of real steps. Prints median / max / p90 of idle intervals in working ticks."""
    if len(events) < 2:
        print(f"  {label}: {len(events)} events - no interval statistics")
        return []
    gaps = []
    for a, b in zip(events, events[1:]):
        gaps.append((work_ticks(a, b), a, b))
    tail = (work_ticks(events[-1], t_end), events[-1], t_end)
    vals = sorted(g[0] for g in gaps)
    med = statistics.median(vals)
    p90 = vals[int(0.9 * (len(vals) - 1))]
    print(f"  {label}: {len(events)} steps, idle between steps (working ticks) median {med:.0f}, p90 {p90}, max {vals[-1]}; open tail {tail[0]}")
    return gaps + [tail]


def main(paths, until=None):
    sections, samples, plan_events, traces = parse(paths)
    if until is not None:
        sections = [x for x in sections if x[0] <= until]
        samples = [x for x in samples if x[0] <= until]
        traces = [x for x in traces if x[0] <= until]
        plan_events = [x for x in plan_events if x[0] is None or x[0] <= until]
    t_end = until if until is not None else max([s[0] for s in sections] + [s[0] for s in samples] + [0])
    print(f"== CADENCE over {len(paths)} log(s), last tick seen {t_end} ({t_end / DAY:.1f} days)")
    print(f"mine sections dug: {len(sections)} (cursor {sections[-1][1] if sections else 0}), "
          f"{len(sections) / max(1e-9, t_end / DAY):.2f} per day")
    if sections:
        print("sections per sprint-window of 24000 ticks:")
        per = Counter(s[0] // DAY for s in sections)
        print("  " + " ".join(f"d{d}:{per.get(d, 0)}" for d in range(0, t_end // DAY + 1)))
        by_worker = Counter(s[2] for s in sections)
        print("  by worker: " + ", ".join(f"{k} {v}" for k, v in by_worker.most_common()))
    # bursts: sections within 1500 ticks of the previous one belong to one burst
    bursts = []
    for s in sections:
        if bursts and s[0] - bursts[-1][-1][0] <= 1500:
            bursts[-1].append(s)
        else:
            bursts.append([s])
    print(f"section bursts (<=1500 ticks apart): {len(bursts)}; sizes {[len(b) for b in bursts]}")
    sec_ticks = [s[0] for s in sections]
    gaps = stats("mine sections", sec_ticks, t_end)
    # build/plan steps from the probe
    build_ticks = []
    if samples:
        last = None
        for t, mi, plan, proj, vill in samples:
            key = (plan, proj)
            if last is not None and key != last:
                build_ticks.append(t)
            last = key
        stats("build steps (plan step or project step changed)", build_ticks, t_end)
        jobs_ticks = []
        lastj = None
        for t, mi, plan, proj, vill in samples:
            j = sum(v[3] for v in vill.values())
            if lastj is not None and j > lastj:
                jobs_ticks.append(t)
            lastj = j
        stats("any villager job counter increased", jobs_ticks, t_end)
        both = sorted(set(sec_ticks) | set(build_ticks))
        allgaps = stats("sections + build steps", both, t_end)
        # idle intervals analysis
        print("\nlongest idle intervals (sections + build steps), working ticks, with what the villagers did (samples inside the interval):")
        top = sorted(allgaps, reverse=True)[:6]
        for w, a, b in top:
            inside = [s for s in samples if a <= s[0] < b and working(s[0])]
            print(f"- ticks {a}..{b}: {w} working ticks idle ({len(inside)} day samples)")
            acts = Counter()
            miners = Counter()
            for s in inside:
                for name, (prof, act, pos, jobs) in s[4].items():
                    acts[act] += 1
                    if "Miner" in prof or "Mine" in act or "Stein" in act:
                        miners[(name, act)] += 1
            if inside:
                tot = sum(acts.values())
                print("    all villagers: " + "; ".join(f"{a[:48]} {100 * c // tot}%" for a, c in acts.most_common(6)))
                print("    mine/stone related: " + "; ".join(f"{n}/{a[:40]} {c}" for (n, a), c in miners.most_common(6)))
    else:
        print("(no CADENCE probe lines: build steps / activities not available; only mine sections and plan events)")
    if traces:
        print("\nmine helper time budget from MTRACE (working ticks per state, until the helper's next state change):")
        per = {}
        last = {}
        for t, name, tag in traces:
            if name in last:
                t0, tag0 = last[name]
                per.setdefault(tag0.split(" ")[0], Counter())
                per[tag0.split(" ")[0]][name] += work_ticks(t0, min(t, t0 + 400))
            last[name] = (t, tag)
        tot = Counter()
        for tag, c in per.items():
            tot[tag] = sum(c.values())
        allw = sum(tot.values()) or 1
        for tag, w in tot.most_common():
            print(f"  {tag:<14} {w:>8} working ticks {100 * w // allw:>3}%")
    print("\nplan events: " + ", ".join(f"{e[2]}:{e[1]}@{e[0]}" for e in plan_events[:12]))
    if gaps:
        print("\nlongest gaps between mine sections (working ticks):")
        for w, a, b in sorted(gaps, reverse=True)[:5]:
            print(f"  {a}->{b}: {w}")


if __name__ == "__main__":
    args = sys.argv[1:]
    until = None
    if args and args[0].startswith("--until="):
        until = int(args.pop(0).split("=", 1)[1])
    if not args:
        sys.exit(__doc__)
    main(args, until)
