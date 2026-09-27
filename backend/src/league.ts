// Mini-league break impact: which rivals the international break hit hardest.
import { buildReport, type PlayerReport, type TeamReport } from "./analyze.js";
import { getEntry, getLeague } from "./fpl.js";

export interface LeagueOption {
  id: number;
  name: string;
  rank: number | null;
}

export interface RivalImpact {
  entry: number;
  teamName: string;
  manager: string;
  rank: number;
  total: number;
  isYou: boolean;
  score: number; // "break damage": higher = hit harder
  injured: number; // red, starting XI + bench
  watch: number; // amber
  withdrew: number;
  tightRest: number;
  away: number;
  flagged: { name: string; reason: string; bench: boolean }[];
  error?: string;
}

export interface LeagueReport {
  league: { id: number; name: string };
  generatedAt: string;
  rivals: RivalImpact[];
  limit: number;
}

/** The user's private mini-leagues. Public ones (Overall, country, club) are far too big to compare. */
export async function teamLeagues(teamId: number): Promise<LeagueOption[]> {
  const entry = await getEntry(teamId);
  return (entry.leagues?.classic ?? [])
    .filter((l) => l.league_type === "x")
    .map((l) => ({ id: l.id, name: l.name, rank: l.entry_rank }));
}

// Starters matter more than the bench; injuries matter more than tiredness.
function damage(p: PlayerReport) {
  let points = 0;
  if (p.risk === "red") points = 3;
  else if (p.risk === "amber") points = 1.5;
  if (p.rest.level === "tight") points += 1;
  else if (p.rest.level === "watch") points += 0.5;
  return p.squadRole === "bench" ? points / 2 : points;
}

function reason(p: PlayerReport): string | null {
  if (p.callUp.status === "withdrawn") return "withdrew";
  if (p.risk === "red") return "injury concern";
  if (p.risk === "amber") {
    if (p.callUp.agreement === "conflict") return "call-up unclear";
    if (p.fotmobInjury) return "existing injury";
    if (p.fpl.status !== "a") return p.fpl.chance !== null ? `FPL ${p.fpl.chance}%` : "FPL flag";
    if (p.matches.some((m) => m.started && m.subbedOffMinute !== null && m.subbedOffMinute < 60)) return "subbed off early";
    return "keep an eye";
  }
  if (p.rest.level === "tight") return `${p.rest.restDays}d rest`;
  return null;
}

function summarise(report: TeamReport): Omit<RivalImpact, "rank" | "total" | "isYou" | "entry"> {
  const ps = report.players;
  return {
    teamName: report.team.name,
    manager: report.team.manager,
    score: Math.round(ps.reduce((s, p) => s + damage(p), 0) * 10) / 10,
    injured: ps.filter((p) => p.risk === "red").length,
    watch: ps.filter((p) => p.risk === "amber").length,
    withdrew: ps.filter((p) => p.callUp.status === "withdrawn").length,
    tightRest: ps.filter((p) => p.rest.level === "tight").length,
    away: ps.filter((p) => p.callUp.status === "called").length,
    flagged: ps.flatMap((p) => {
      const r = reason(p);
      return r ? [{ name: p.webName, reason: r, bench: p.squadRole === "bench" }] : [];
    }),
  };
}

/** Runs `fn` over `items` with at most `n` in flight, keeping order. */
async function pool<T, R>(items: T[], n: number, fn: (item: T) => Promise<R>): Promise<R[]> {
  const out: R[] = new Array(items.length);
  let next = 0;
  await Promise.all(
    Array.from({ length: Math.min(n, items.length) }, async () => {
      while (next < items.length) {
        const i = next++;
        out[i] = await fn(items[i]);
      }
    }),
  );
  return out;
}

export async function leagueReport(leagueId: number, you: number | null, limit: number): Promise<LeagueReport> {
  const league = await getLeague(leagueId);
  const all = league.standings.results;
  const picked = all.slice(0, limit);
  const yours = all.find((r) => r.entry === you);
  if (yours && !picked.includes(yours)) picked.push(yours);

  // Rivals share many players, so later reports mostly hit the cache. Keep bursts small anyway.
  const rivals = await pool(picked, 3, async (r): Promise<RivalImpact> => {
    const base = { entry: r.entry, rank: r.rank, total: r.total, isYou: r.entry === you };
    try {
      return { ...base, ...summarise(await buildReport(r.entry)) };
    } catch (err) {
      return {
        ...base,
        teamName: r.entry_name,
        manager: r.player_name,
        score: 0,
        injured: 0,
        watch: 0,
        withdrew: 0,
        tightRest: 0,
        away: 0,
        flagged: [],
        error: (err as Error).message,
      };
    }
  });

  rivals.sort((a, b) => b.score - a.score || a.rank - b.rank);
  return { league: league.league, generatedAt: new Date().toISOString(), rivals, limit };
}
