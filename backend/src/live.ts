// "Live now": a player's international match in progress, and whether they're on the pitch.
import { getMatch, substitutionInfo, type MatchDetails } from "./fotmob.js";
import type { BreakFixture } from "./national.js";

export type OnPitch = "playing" | "subbed_off" | "bench" | "not_in_squad" | "unknown";

export interface LiveMatch {
  fotmobMatchId: number;
  opponent: string;
  home: boolean;
  minute: string; // "67'", "HT", …
  score: string | null; // "1 - 0", home first
  onPitch: OnPitch;
}

// A match can only be live from kick-off until roughly two and a half hours later.
const MAX_MATCH_MS = 2.5 * 3_600_000;

export function onPitch(match: MatchDetails, playerId: number): OnPitch {
  const lineup = match.content.lineup;
  if (!lineup?.homeTeam && !lineup?.awayTeam) return "unknown";
  const teams = [lineup.homeTeam, lineup.awayTeam];
  const starter = teams.some((t) => t?.starters.some((p) => p.id === playerId));
  const sub = teams.some((t) => t?.subs.some((p) => p.id === playerId));
  const { subbedOnMinute, subbedOffMinute } = substitutionInfo(match, playerId);
  if (subbedOffMinute !== null) return "subbed_off";
  if (starter || subbedOnMinute !== null) return "playing";
  return sub ? "bench" : "not_in_squad";
}

/** The fixture being played right now, if any. Only fetches match details inside the kick-off window. */
export async function liveMatch(fixtures: BreakFixture[], playerId: number, now = Date.now()): Promise<LiveMatch | null> {
  const candidate = fixtures.find((f) => {
    const t = Date.parse(f.date);
    return !f.finished && t <= now && now - t < MAX_MATCH_MS;
  });
  if (!candidate) return null;
  const match = await getMatch(candidate.fotmobMatchId);
  if (!match.general.started || match.general.finished) return null;
  const status = match.header?.status;
  return {
    fotmobMatchId: candidate.fotmobMatchId,
    opponent: candidate.opponent,
    home: candidate.home,
    minute: status?.liveTime?.short ?? status?.reason?.short ?? "Live",
    score: status?.scoreStr ?? null,
    onPitch: onPitch(match, playerId),
  };
}
