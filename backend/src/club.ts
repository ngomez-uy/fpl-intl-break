// Getting back to the club: the next gameweek's fixture(s), price movement, and how much
// rest a player gets between their last international match and their club's kick-off.
import type { FplElement, FplFixture, FplTeam } from "./fpl.js";

export interface NextFixture {
  opponent: string; // short name, e.g. "BOU"
  home: boolean;
  kickoff: string;
  difficulty: number; // FPL's 1 (easy) – 5 (hard)
}

export interface Price {
  now: number; // £m
  changeThisGw: number; // £m since the last deadline
  netTransfers: number; // transfers in minus out since the last deadline
}

export type RestLevel = "ok" | "watch" | "tight" | "n/a";

export interface Rest {
  level: RestLevel;
  lastIntlMatch: string | null; // kick-off of the last international match they're (expected to be) in
  clubKickoff: string | null;
  restDays: number | null; // to the nearest half day
  breakMinutes: number;
  longTrip: string | null; // region they fly back from, when outside Europe
  note: string;
}

const MATCH_MS = 2 * 3_600_000;
const DAY_MS = 24 * 3_600_000;

// Confederations whose matches mean a long flight back to England.
const LONG_TRIP: [RegExp, string][] = [
  [/africa|\bcaf\b|afcon/i, "Africa"],
  [/conmebol|south america|copa am/i, "South America"],
  [/concacaf|gold cup|north.*america|central america/i, "North & Central America"],
  [/\bafc\b|asia/i, "Asia"],
  [/\bofc\b|oceania/i, "Oceania"],
];

export function nextFixtures(fixtures: FplFixture[], club: FplTeam, event: number, teams: Map<number, FplTeam>): NextFixture[] {
  return fixtures
    .filter((f) => f.event === event && f.kickoff_time && (f.team_h === club.id || f.team_a === club.id))
    .map((f) => {
      const home = f.team_h === club.id;
      return {
        opponent: teams.get(home ? f.team_a : f.team_h)?.short_name ?? "?",
        home,
        kickoff: f.kickoff_time!,
        difficulty: home ? f.team_h_difficulty : f.team_a_difficulty,
      };
    })
    .sort((a, b) => Date.parse(a.kickoff) - Date.parse(b.kickoff));
}

export const price = (el: FplElement): Price => ({
  now: el.now_cost / 10,
  changeThisGw: el.cost_change_event / 10,
  netTransfers: el.transfers_in_event - el.transfers_out_event,
});

const fmtDay = (iso: string) =>
  new Date(iso).toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short", timeZone: "UTC" });

export function rest(opts: {
  withSquad: boolean;
  fixtures: { date: string; finished: boolean; involved: boolean; competition: string }[];
  breakMinutes: number;
  clubKickoff: string | null;
}): Rest {
  const { withSquad, fixtures, breakMinutes, clubKickoff } = opts;
  // Still with the squad: assume they're in every remaining match. Otherwise only count
  // matches they actually featured in (e.g. played, then withdrew).
  const relevant = fixtures.filter((f) => (withSquad && !f.finished) || f.involved);
  const last = relevant.map((f) => f.date).sort().at(-1) ?? null;
  const longTrip = LONG_TRIP.find(([re]) => relevant.some((f) => re.test(f.competition)))?.[1] ?? null;

  if (!last || !clubKickoff) {
    return { level: "n/a", lastIntlMatch: last, clubKickoff, restDays: null, breakMinutes, longTrip: null, note: "" };
  }
  const days = Math.round(((Date.parse(clubKickoff) - Date.parse(last) - MATCH_MS) / DAY_MS) * 2) / 2;
  let level: RestLevel = "ok";
  if (days < 3 || (longTrip && days < 4)) level = "tight";
  else if (days < 4 || breakMinutes >= 270 || (longTrip && days < 5)) level = "watch";

  const trip = longTrip ? `, flying back from ${longTrip}` : "";
  const heavy = breakMinutes >= 270 ? ` after ${breakMinutes}' this break` : "";
  const note = `Last international ${fmtDay(last)}, club game ${fmtDay(clubKickoff)}: ${days} days' rest${trip}${heavy}`;
  return { level, lastIntlMatch: last, clubKickoff, restDays: days, breakMinutes, longTrip, note };
}
