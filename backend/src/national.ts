// Works out a player's senior national team, whether they're in its squad for the
// break, and which of its fixtures fall inside the break.
import { searchTeams, type CareerEntry, type PlayerData, type TeamData } from "./fotmob.js";
import type { BreakWindow } from "./fpl.js";

export type CallUpStatus = "called" | "withdrawn" | "not_called" | "unknown";

/** One source's view of the call-up. More sources get added alongside FotMob. */
export interface SourceCheck {
  source: string;
  status: CallUpStatus;
  detail: string;
  url?: string;
}

export interface CallUp {
  status: CallUpStatus;
  checks: SourceCheck[];
  agreement: "agree" | "conflict" | "single";
}

export interface Nation {
  fotmobTeamId: number;
  name: string;
  capped: boolean; // false = only youth caps, so the senior team came from the player's country
}

export interface BreakFixture {
  fotmobMatchId: number;
  date: string;
  opponent: string;
  home: boolean;
  competition: string;
  finished: boolean;
  score: string | null; // "England 2–3 Spain" once finished
}

const YOUTH_OR_OTHER = /\bU\s?\d{2}\b|under\s?\d{2}|\(W\)|olympic/i;
// A national-team spell FotMob closed around the break usually means the player left the squad.
const LEFT_SQUAD_GRACE_MS = 7 * 24 * 3_600_000;

function seniorEntry(player: PlayerData): CareerEntry | undefined {
  const entries = player.careerHistory?.careerItems?.["national team"]?.teamEntries ?? [];
  return entries.find((e) => e.teamId > 0 && !YOUTH_OR_OTHER.test(e.team));
}

function country(player: PlayerData): string | null {
  const info = player.playerInformation?.find((i) => i.title === "Country");
  return typeof info?.value.fallback === "string" ? info.value.fallback : null;
}

export async function resolveNation(player: PlayerData): Promise<{ nation: Nation | null; spellEnded: string | null }> {
  const senior = seniorEntry(player);
  if (senior) {
    return { nation: { fotmobTeamId: senior.teamId, name: senior.team, capped: true }, spellEnded: senior.endDate };
  }
  const name = country(player);
  if (!name) return { nation: null, spellEnded: null };
  const team = (await searchTeams(name)).find((t) => t.name === name);
  return { nation: team ? { fotmobTeamId: Number(team.id), name, capped: false } : null, spellEnded: null };
}

export function breakFixtures(team: TeamData, window: BreakWindow): BreakFixture[] {
  const from = Date.parse(window.from);
  const to = Date.parse(window.to);
  return team.fixtures
    .filter((f) => {
      const t = Date.parse(f.status.utcTime);
      return t >= from && t < to && !f.status.cancelled;
    })
    .map((f) => {
      const home = f.home.id === team.id;
      return {
        fotmobMatchId: f.id,
        date: f.status.utcTime,
        opponent: home ? f.away.name : f.home.name,
        home,
        competition: f.tournament?.name ?? "",
        finished: f.status.finished,
        score: f.status.finished ? `${f.home.name} ${f.home.score ?? "?"}–${f.away.score ?? "?"} ${f.away.name}` : null,
      };
    })
    .sort((a, b) => Date.parse(a.date) - Date.parse(b.date));
}

const fmtDay = (iso: string) =>
  new Date(iso).toLocaleDateString("en-GB", { day: "numeric", month: "short", timeZone: "UTC" });

/** FotMob's verdict, from its squad list, the player's matchday appearances and career dates. */
export function fotmobCallUp(
  player: PlayerData,
  team: TeamData,
  spellEnded: string | null,
  appearedIn: string[],
  window: BreakWindow,
): SourceCheck {
  const inSquad = team.squad.some((m) => m.id === player.id);
  const ended = spellEnded !== null && Date.parse(spellEnded) >= Date.parse(window.from) - LEFT_SQUAD_GRACE_MS;
  const base = { source: "FotMob", url: `https://www.fotmob.com/teams/${team.id}/squad` };

  if (ended && (inSquad || appearedIn.length > 0)) {
    return { ...base, status: "withdrawn", detail: `Left the ${team.name} squad on ${fmtDay(spellEnded!)}` };
  }
  if (inSquad || appearedIn.length > 0) {
    const how = appearedIn.length > 0 ? `in the matchday squad vs ${appearedIn.join(", ")}` : `listed in the ${team.name} squad`;
    return { ...base, status: "called", detail: `Called up — ${how}` };
  }
  if (spellEnded !== null) {
    return { ...base, status: "not_called", detail: `No longer in the ${team.name} set-up — FotMob ended the spell on ${fmtDay(spellEnded)}` };
  }
  return { ...base, status: "not_called", detail: `Not in the current ${team.name} squad` };
}

// "Withdrawn" and "not called" both mean "not with the squad now"; a source that drops
// withdrawn players (Transfermarkt) reports them as simply not in the squad.
const withSquad = (s: CallUpStatus) => s === "called";

export function combineChecks(checks: SourceCheck[]): CallUp {
  const known = checks.filter((c) => c.status !== "unknown");
  if (known.length === 0) return { status: "unknown", checks, agreement: "single" };
  const agree = known.every((c) => withSquad(c.status) === withSquad(known[0].status));
  return {
    // FotMob's is the headline answer (it knows withdrawals); conflicts are flagged, not hidden.
    status: known[0].status,
    checks,
    agreement: known.length === 1 ? "single" : agree ? "agree" : "conflict",
  };
}
