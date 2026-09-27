// FotMob's public web endpoints. Unofficial and undocumented: if FotMob changes
// them, this is the only module that should need fixing.
import { cached, HOUR, MINUTE } from "./cache.js";
import { getJson } from "./http.js";

const BASE = "https://www.fotmob.com/api/data";

export interface SearchSuggestion {
  type: string;
  id: string;
  name: string;
  score: number;
  teamId?: number;
  teamName?: string;
}

export interface RecentMatch {
  id: number;
  teamId: number;
  teamName: string;
  opponentTeamName: string;
  isHomeTeam: boolean;
  matchDate: { utcTime: string };
  leagueName: string;
  homeScore: number;
  awayScore: number;
  minutesPlayed: number;
  goals: number;
  assists: number;
  yellowCards: number;
  redCards: number;
  ratingProps?: { rating?: string | number }; // 0 (a number) when the player wasn't rated
  onBench: boolean;
  playedInMatch: boolean;
}

export interface InjuryInformation {
  name: string;
  expectedReturn?: { expectedReturnFallback?: string };
  lastUpdated?: { utcTime: string };
}

export interface CareerEntry {
  teamId: number;
  team: string;
  startDate: string | null;
  endDate: string | null;
}

export interface PlayerData {
  id: number;
  name: string;
  primaryTeam?: { teamId: number; teamName: string };
  injuryInformation: InjuryInformation | null;
  recentMatches: RecentMatch[];
  playerInformation?: { title: string; value: { fallback: unknown } }[];
  careerHistory?: { careerItems?: Record<string, { teamEntries?: CareerEntry[] } | undefined> };
}

export interface TeamFixture {
  id: number;
  home: { id: number; name: string; score?: number };
  away: { id: number; name: string; score?: number };
  tournament?: { name: string };
  status: { utcTime: string; finished: boolean; started: boolean; cancelled: boolean; scoreStr?: string };
}

export interface SquadMember {
  id: number;
  name: string;
  role?: { key: string };
}

/** The slice of FotMob's team page we use; the full payload is ~300 KB. */
export interface TeamData {
  id: number;
  name: string;
  squad: SquadMember[];
  fixtures: TeamFixture[];
}

interface SubEvent {
  type: string;
  time: number;
  injuredPlayerOut?: boolean;
  swap?: { id: string; name: string }[]; // [player on, player off]
}

interface LineupPlayer {
  id: number;
  performance?: { substitutionEvents?: { time: number; type: string; reason?: string }[] };
}

interface LineupTeam {
  starters: LineupPlayer[];
  subs: LineupPlayer[];
}

export interface MatchDetails {
  general: { finished: boolean; started?: boolean };
  header?: {
    status?: {
      scoreStr?: string;
      liveTime?: { short?: string };
      reason?: { short?: string };
    };
  };
  content: {
    matchFacts?: { events?: { events: SubEvent[] } };
    lineup?: { homeTeam?: LineupTeam; awayTeam?: LineupTeam };
  };
}

async function suggest(term: string, type: "player" | "team"): Promise<SearchSuggestion[]> {
  const groups = await cached(`fotmob_search_${term}`, 7 * 24 * HOUR, () =>
    getJson<{ suggestions: SearchSuggestion[] }[]>(
      `${BASE}/search/suggest?term=${encodeURIComponent(term)}&lang=en`,
    ),
  );
  const seen = new Set<string>();
  return groups
    .flatMap((g) => g.suggestions ?? [])
    .filter((s) => s.type === type && !seen.has(s.id) && seen.add(s.id));
}

export const searchPlayers = (term: string) => suggest(term, "player");
export const searchTeams = (term: string) => suggest(term, "team");

export const getPlayer = (id: number) =>
  cached(`fotmob_player_${id}`, 20 * MINUTE, () => getJson<PlayerData>(`${BASE}/playerData?id=${id}`));

// National squads change when players withdraw, so re-check hourly.
export const getTeam = (id: number) =>
  cached(`fotmob_team_${id}`, HOUR, async (): Promise<TeamData> => {
    const raw = await getJson<{
      details: { id: number; name: string };
      squad?: { squad?: { title: string; members: SquadMember[] }[] };
      fixtures?: { allFixtures?: { fixtures?: TeamFixture[] } };
    }>(`${BASE}/teams?id=${id}`);
    return {
      id: raw.details.id,
      name: raw.details.name,
      squad: (raw.squad?.squad ?? [])
        .filter((g) => g.title !== "coach")
        .flatMap((g) => g.members.map(({ id, name, role }) => ({ id, name, role }))),
      fixtures: (raw.fixtures?.allFixtures?.fixtures ?? []).map(({ id, home, away, tournament, status }) => ({
        id,
        home,
        away,
        tournament,
        status,
      })),
    };
  });

// Finished matches never change, so cache them forever; live ones are re-checked every minute.
export const getMatch = (id: number) =>
  cached(
    `fotmob_match_${id}`,
    (m: MatchDetails) => (m.general?.finished ? null : m.general?.started ? MINUTE : 10 * MINUTE),
    () => getJson<MatchDetails>(`${BASE}/matchDetails?matchId=${id}`),
  );

export interface SubstitutionInfo {
  subbedOnMinute: number | null;
  subbedOffMinute: number | null;
  injuredOff: boolean;
  offReason: string | null;
}

/** How a player's match ended, from match events plus lineup substitution reasons. */
export function substitutionInfo(match: MatchDetails, playerId: number): SubstitutionInfo {
  const info: SubstitutionInfo = { subbedOnMinute: null, subbedOffMinute: null, injuredOff: false, offReason: null };
  const pid = String(playerId);

  for (const e of match.content.matchFacts?.events?.events ?? []) {
    if (e.type !== "Substitution" || !e.swap) continue;
    const [on, off] = e.swap;
    if (on?.id === pid) info.subbedOnMinute = e.time;
    if (off?.id === pid) {
      info.subbedOffMinute = e.time;
      if (e.injuredPlayerOut) info.injuredOff = true;
    }
  }

  const lineup = match.content.lineup;
  const players = [lineup?.homeTeam, lineup?.awayTeam].flatMap((t) => [...(t?.starters ?? []), ...(t?.subs ?? [])]);
  const me = players.find((p) => p.id === playerId);
  const subOut = me?.performance?.substitutionEvents?.find((s) => s.type === "subOut");
  if (subOut) {
    info.subbedOffMinute ??= subOut.time;
    info.offReason = subOut.reason ?? null;
    if (subOut.reason && /injur/i.test(subOut.reason)) info.injuredOff = true;
  }
  return info;
}
