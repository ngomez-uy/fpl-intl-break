import { cached, HOUR, MINUTE } from "./cache.js";
import { getJson } from "./http.js";

const BASE = "https://fantasy.premierleague.com/api";

export interface FplElement {
  id: number;
  first_name: string;
  second_name: string;
  web_name: string;
  known_name?: string;
  team: number;
  element_type: number;
  status: string; // a=available, d=doubtful, i=injured, s=suspended, u=unavailable, n=not in squad
  news: string;
  news_added: string | null;
  chance_of_playing_next_round: number | null;
}

export interface FplTeam {
  id: number;
  name: string;
  short_name: string;
}

export interface FplEvent {
  id: number;
  deadline_time: string;
  finished: boolean;
  is_current: boolean;
}

interface Bootstrap {
  elements: FplElement[];
  teams: FplTeam[];
  events: FplEvent[];
  element_types: { id: number; singular_name_short: string }[];
}

interface FplFixture {
  event: number | null;
  kickoff_time: string | null;
}

export interface FplEntry {
  id: number;
  name: string;
  player_first_name: string;
  player_last_name: string;
  current_event: number;
}

export interface FplPicks {
  picks: { element: number; position: number; is_captain: boolean; is_vice_captain: boolean }[];
}

// Player status/news changes a lot during a break, so keep this short.
export const getBootstrap = () =>
  cached("fpl_bootstrap", 15 * MINUTE, () => getJson<Bootstrap>(`${BASE}/bootstrap-static/`));

const getFixtures = () => cached("fpl_fixtures", 6 * HOUR, () => getJson<FplFixture[]>(`${BASE}/fixtures/`));

export const getEntry = (id: number) =>
  cached(`fpl_entry_${id}`, 15 * MINUTE, () => getJson<FplEntry>(`${BASE}/entry/${id}/`));

export const getPicks = (id: number, event: number) =>
  cached(`fpl_picks_${id}_${event}`, 15 * MINUTE, () =>
    getJson<FplPicks>(`${BASE}/entry/${id}/event/${event}/picks/`),
  );

export interface BreakWindow {
  from: string; // just after the last match of the gameweek before the break
  to: string; // first match of the gameweek after the break
  afterEvent: number;
  beforeEvent: number;
}

const MIN_BREAK_DAYS = 9;
const MATCH_LENGTH_MS = 2.5 * HOUR;

/**
 * The most recent gap of 9+ days between consecutive gameweeks that has
 * already started. Normal weeks have ~3–7 day gaps.
 */
export async function findBreakWindow(now = new Date()): Promise<BreakWindow | null> {
  const fixtures = await getFixtures();
  const byEvent = new Map<number, number[]>();
  for (const f of fixtures) {
    if (!f.event || !f.kickoff_time) continue;
    const list = byEvent.get(f.event) ?? [];
    list.push(Date.parse(f.kickoff_time));
    byEvent.set(f.event, list);
  }
  const events = [...byEvent.keys()].sort((a, b) => a - b);
  let found: BreakWindow | null = null;
  for (let i = 0; i < events.length - 1; i++) {
    const lastKickoff = Math.max(...byEvent.get(events[i])!);
    const nextKickoff = Math.min(...byEvent.get(events[i + 1])!);
    const from = lastKickoff + MATCH_LENGTH_MS;
    if (from > now.getTime()) break;
    if (nextKickoff - lastKickoff >= MIN_BREAK_DAYS * 24 * HOUR) {
      found = {
        from: new Date(from).toISOString(),
        to: new Date(nextKickoff).toISOString(),
        afterEvent: events[i],
        beforeEvent: events[i + 1],
      };
    }
  }
  return found;
}
