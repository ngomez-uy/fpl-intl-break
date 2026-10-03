import { cached, HOUR } from "./cache.js";
import { getBootstrap, getEntry, getFixtures, getPicks, type FplFixture } from "./fpl.js";
import { getJson, getText } from "./http.js";

// The next gameweek's matches, seen through history: the last meetings between the two clubs,
// who did the damage in them, and how each of your squad players has done against that
// opponent, season by season. The current season comes from FPL. FPL only keeps season totals
// for earlier seasons, so those come from vaastav/Fantasy-Premier-League on GitHub, a
// long-running archive of the same FPL API data, saved every gameweek.

const PAST_SEASONS = 5;
const LAST_MEETINGS = 5;
const TOP_N = 3;
const ARCHIVE = "https://raw.githubusercontent.com/vaastav/Fantasy-Premier-League/master/data";

export interface H2HMatch {
  kickoff: string;
  home: boolean;
  result: string; // "W 2-1", from the player's side
  minutes: number;
  goals: number;
  assists: number;
  points: number;
}

export interface H2HSeason {
  season: string; // "2025/26"
  // played: stats below; no-player: not in the Premier League; no-opponent: opponent not in the
  // Premier League; own-club: played for the opponent; not-met: the clubs haven't played each other
  // (yet); unavailable: archive fetch failed
  status: "played" | "no-player" | "no-opponent" | "own-club" | "not-met" | "unavailable";
  club: string | null; // the player's club that season (short name)
  apps: number;
  minutes: number;
  goals: number;
  assists: number;
  points: number;
  matches: H2HMatch[];
}

export interface H2HTotals {
  apps: number;
  goals: number;
  assists: number;
  points: number;
}

/** One of your squad players in a matchup: their record against this opponent. */
export interface H2HPlayer {
  id: number;
  name: string;
  position: string;
  club: string;
  bench: boolean;
  total: H2HTotals;
  seasons: H2HSeason[]; // newest first
}

export interface Meeting {
  season: string;
  kickoff: string;
  home: string; // short names
  away: string;
  homeGoals: number;
  awayGoals: number;
}

/** A current player's goals and assists for their club in the last meetings. */
export interface Contributor {
  name: string;
  goals: number;
  assists: number;
  mine: boolean; // in your squad
}

export interface Matchup {
  kickoff: string | null;
  home: { name: string; short: string; top: Contributor[] };
  away: { name: string; short: string; top: Contributor[] };
  meetings: Meeting[]; // newest first
  players: H2HPlayer[];
}

export interface H2HReport {
  event: number;
  seasons: string[];
  matchups: Matchup[];
  blanks: { id: number; name: string; club: string }[]; // squad players without a match
  generatedAt: string;
}

interface HistoryRow {
  opponent: number; // that season's team id
  kickoff: string;
  home: boolean;
  teamH: number;
  teamA: number;
  minutes: number;
  goals: number;
  assists: number;
  points: number;
}

interface ArchivePlayer {
  id: number;
  code: number;
  folder: string;
  team: number;
}

interface ArchiveTeam {
  id: number;
  code: number;
  short: string;
}

type Tally = [element: number, value: number][];

/** A finished match with goal and assist credits, in one season's ids. */
interface PlayedFixture {
  kickoff: string;
  teamH: number;
  teamA: number;
  homeGoals: number;
  awayGoals: number;
  goals: { h: Tally; a: Tally };
  assists: { h: Tally; a: Tally };
}

/** Minimal CSV parser: quoted fields, doubled quotes, no newlines inside fields. */
function parseCsv(text: string): Record<string, string>[] {
  const lines = text.split(/\r?\n/).filter((l) => l.length > 0);
  const split = (line: string) => {
    const out: string[] = [];
    let field = "";
    let quoted = false;
    for (let i = 0; i < line.length; i++) {
      const ch = line[i];
      if (quoted) {
        if (ch === '"' && line[i + 1] === '"') field += line[++i];
        else if (ch === '"') quoted = false;
        else field += ch;
      } else if (ch === '"') quoted = true;
      else if (ch === ",") {
        out.push(field);
        field = "";
      } else field += ch;
    }
    out.push(field);
    return out;
  };
  const [header, ...rows] = lines.map(split);
  return rows.map((r) => Object.fromEntries(header.map((h, i) => [h, r[i] ?? ""])));
}

const num = (v: string | undefined) => Number(v) || 0;

/**
 * One stat's credits for one side from the archive's `stats` column, which is a Python repr:
 * "[{'identifier': 'goals_scored', 'a': [{'value': 1, 'element': 82}], 'h': [...]}, ...]".
 */
function reprTally(stats: string, identifier: string, side: "h" | "a"): Tally {
  const start = stats.indexOf(`'identifier': '${identifier}'`);
  if (start < 0) return [];
  const end = stats.indexOf("'identifier'", start + 1);
  const section = stats.slice(start, end < 0 ? undefined : end);
  const from = section.indexOf(`'${side}': [`);
  if (from < 0) return [];
  const list = section.slice(from, section.indexOf("]", from));
  return [...list.matchAll(/'value': (\d+), 'element': (\d+)/g)].map((m) => [Number(m[2]), Number(m[1])]);
}

// Past seasons never change, so archive files are cached for good.
const archivePlayers = (season: string) =>
  cached(`archive_squad_${season}`, null, async () =>
    parseCsv(await getText(`${ARCHIVE}/${season}/players_raw.csv`, "text/csv")).map(
      (r): ArchivePlayer => ({ id: num(r.id), code: num(r.code), folder: `${r.first_name}_${r.second_name}_${r.id}`, team: num(r.team) }),
    ),
  );

const archiveTeams = (season: string) =>
  cached(`archive_clubs_${season}`, null, async () =>
    parseCsv(await getText(`${ARCHIVE}/${season}/teams.csv`, "text/csv")).map(
      (r): ArchiveTeam => ({ id: num(r.id), code: num(r.code), short: r.short_name }),
    ),
  );

const archiveFixtures = (season: string) =>
  cached(`archive_fixtures_${season}`, null, async () =>
    parseCsv(await getText(`${ARCHIVE}/${season}/fixtures.csv`, "text/csv"))
      .filter((r) => r.finished === "True")
      .map(
        (r): PlayedFixture => ({
          kickoff: r.kickoff_time,
          teamH: num(r.team_h),
          teamA: num(r.team_a),
          homeGoals: num(r.team_h_score),
          awayGoals: num(r.team_a_score),
          goals: { h: reprTally(r.stats, "goals_scored", "h"), a: reprTally(r.stats, "goals_scored", "a") },
          assists: { h: reprTally(r.stats, "assists", "h"), a: reprTally(r.stats, "assists", "a") },
        }),
      ),
  );

const toRow = (r: Record<string, string | number | boolean>): HistoryRow => ({
  opponent: Number(r.opponent_team),
  kickoff: String(r.kickoff_time),
  home: r.was_home === true || r.was_home === "True",
  teamH: Number(r.team_h_score),
  teamA: Number(r.team_a_score),
  minutes: Number(r.minutes),
  goals: Number(r.goals_scored),
  assists: Number(r.assists),
  points: Number(r.total_points),
});

const archiveHistory = (season: string, folder: string) =>
  cached(`archive_history_${season}_${folder}`, null, async () =>
    parseCsv(await getText(`${ARCHIVE}/${season}/players/${encodeURIComponent(folder)}/gw.csv`, "text/csv")).map(toRow),
  );

// Changes after every match the player plays.
const currentHistory = (id: number) =>
  cached(`fpl_summary_${id}`, HOUR, async () => {
    const body = await getJson<{ history: Record<string, string | number | boolean>[] }>(
      `https://fantasy.premierleague.com/api/element-summary/${id}/`,
    );
    return body.history.map(toRow);
  });

const sum = <T>(xs: T[], f: (x: T) => number) => xs.reduce((s, x) => s + f(x), 0);

function seasonStats(season: string, club: string | null, rows: HistoryRow[]): H2HSeason {
  if (rows.length === 0) return empty(season, "not-met", club);
  const played = rows.filter((r) => r.minutes > 0);
  return {
    season,
    status: "played",
    club,
    apps: played.length,
    minutes: sum(played, (r) => r.minutes),
    goals: sum(played, (r) => r.goals),
    assists: sum(played, (r) => r.assists),
    points: sum(played, (r) => r.points),
    matches: played.map((r) => {
      const [us, them] = r.home ? [r.teamH, r.teamA] : [r.teamA, r.teamH];
      const outcome = us > them ? "W" : us < them ? "L" : "D";
      return {
        kickoff: r.kickoff,
        home: r.home,
        result: `${outcome} ${us}-${them}`,
        minutes: r.minutes,
        goals: r.goals,
        assists: r.assists,
        points: r.points,
      };
    }),
  };
}

const empty = (season: string, status: H2HSeason["status"], club: string | null = null): H2HSeason => ({
  season,
  status,
  club,
  apps: 0,
  minutes: 0,
  goals: 0,
  assists: 0,
  points: 0,
  matches: [],
});

/** "2025-26" folder name and "2025/26" label for the season starting in `year`. */
const seasonNames = (year: number) => {
  const end = String((year + 1) % 100).padStart(2, "0");
  return { folder: `${year}-${end}`, label: `${year}/${end}` };
};

/** One past season of the archive, indexed for lookups. Null if it couldn't be loaded. */
interface ArchiveSeason {
  label: string;
  folder: string;
  byCode: Map<number, ArchivePlayer>;
  codeById: Map<number, number>;
  teams: Map<number, ArchiveTeam>;
  teamIdByCode: Map<number, number>;
  fixtures: PlayedFixture[];
}

async function loadSeason(year: number): Promise<ArchiveSeason | null> {
  const { folder, label } = seasonNames(year);
  try {
    const [players, teams, fixtures] = await Promise.all([archivePlayers(folder), archiveTeams(folder), archiveFixtures(folder)]);
    return {
      label,
      folder,
      byCode: new Map(players.map((p) => [p.code, p])),
      codeById: new Map(players.map((p) => [p.id, p.code])),
      teams: new Map(teams.map((t) => [t.id, t])),
      teamIdByCode: new Map(teams.map((t) => [t.code, t.id])),
      fixtures,
    };
  } catch {
    return null;
  }
}

async function pastSeason(year: number, s: ArchiveSeason | null, playerCode: number, opponentCode: number): Promise<H2HSeason> {
  const label = seasonNames(year).label;
  if (!s) return empty(label, "unavailable");
  const player = s.byCode.get(playerCode);
  if (!player) return empty(label, "no-player");
  const club = s.teams.get(player.team);
  if (club?.code === opponentCode) return empty(label, "own-club", club.short);
  const opponentId = s.teamIdByCode.get(opponentCode);
  if (opponentId === undefined) return empty(label, "no-opponent", club?.short ?? null);
  try {
    const rows = await archiveHistory(s.folder, player.folder);
    return seasonStats(label, club?.short ?? null, rows.filter((r) => r.opponent === opponentId));
  } catch {
    return empty(label, "unavailable", club?.short ?? null);
  }
}

/** A finished meeting in one season, with credits translated to stable player codes. */
interface CodedMeeting extends Meeting {
  credits: { code: number; side: "home" | "away"; goals: number; assists: number }[];
}

function codedMeeting(
  f: PlayedFixture,
  season: string,
  shortOf: (teamId: number) => string,
  codeOf: (element: number) => number | undefined,
): CodedMeeting {
  const credits = new Map<string, { code: number; side: "home" | "away"; goals: number; assists: number }>();
  const add = (tally: Tally, side: "home" | "away", stat: "goals" | "assists") => {
    for (const [element, value] of tally) {
      const code = codeOf(element);
      if (code === undefined) continue;
      const key = `${side}:${code}`;
      const c = credits.get(key) ?? { code, side, goals: 0, assists: 0 };
      c[stat] += value;
      credits.set(key, c);
    }
  };
  add(f.goals.h, "home", "goals");
  add(f.goals.a, "away", "goals");
  add(f.assists.h, "home", "assists");
  add(f.assists.a, "away", "assists");
  return {
    season,
    kickoff: f.kickoff,
    home: shortOf(f.teamH),
    away: shortOf(f.teamA),
    homeGoals: f.homeGoals,
    awayGoals: f.awayGoals,
    credits: [...credits.values()],
  };
}

export async function headToHead(teamId: number): Promise<H2HReport> {
  const [bootstrap, entry, fixtures] = await Promise.all([getBootstrap(), getEntry(teamId), getFixtures()]);
  const nextEvent = bootstrap.events.find((e) => !e.finished)?.id;
  if (!nextEvent) throw new Error("The season is over: there's no next gameweek.");
  const picks = await getPicks(teamId, entry.current_event);

  const teams = new Map(bootstrap.teams.map((t) => [t.id, t]));
  const teamByCode = new Map(bootstrap.teams.map((t) => [t.code, t]));
  const elements = new Map(bootstrap.elements.map((e) => [e.id, e]));
  const elementByCode = new Map(bootstrap.elements.map((e) => [e.code, e]));
  const positions = new Map(bootstrap.element_types.map((t) => [t.id, t.singular_name_short]));
  const startYear = new Date(bootstrap.events[0].deadline_time).getUTCFullYear();
  const current = seasonNames(startYear).label;
  const pastYears = Array.from({ length: PAST_SEASONS }, (_, i) => startYear - 1 - i);
  const archive = await Promise.all(pastYears.map(loadSeason));
  const squadCodes = new Set(picks.picks.map((p) => elements.get(p.element)!.code));

  /** Every finished meeting between two clubs, this season and the archived ones, newest first. */
  function meetingsBetween(codeA: number, codeB: number): CodedMeeting[] {
    const pair = (h: number, a: number, x: number | undefined, y: number | undefined) =>
      x !== undefined && y !== undefined && ((h === x && a === y) || (h === y && a === x));
    const a = teamByCode.get(codeA)!.id;
    const b = teamByCode.get(codeB)!.id;
    const tally = (f: FplFixture, id: string, side: "h" | "a"): Tally =>
      (f.stats?.find((s) => s.identifier === id)?.[side] ?? []).map((x) => [x.element, x.value]);
    const thisSeason = fixtures
      .filter((f) => f.finished && pair(f.team_h, f.team_a, a, b))
      .map((f) =>
        codedMeeting(
          {
            kickoff: f.kickoff_time!,
            teamH: f.team_h,
            teamA: f.team_a,
            homeGoals: f.team_h_score ?? 0,
            awayGoals: f.team_a_score ?? 0,
            goals: { h: tally(f, "goals_scored", "h"), a: tally(f, "goals_scored", "a") },
            assists: { h: tally(f, "assists", "h"), a: tally(f, "assists", "a") },
          },
          current,
          (id) => teams.get(id)!.short_name,
          (el) => elements.get(el)?.code,
        ),
      );
    const past = archive.flatMap((s) => {
      if (!s) return [];
      const x = s.teamIdByCode.get(codeA);
      const y = s.teamIdByCode.get(codeB);
      return s.fixtures
        .filter((f) => pair(f.teamH, f.teamA, x, y))
        .map((f) => codedMeeting(f, s.label, (id) => s.teams.get(id)?.short ?? "?", (el) => s.codeById.get(el)));
    });
    return [...thisSeason, ...past].sort((m, n) => n.kickoff.localeCompare(m.kickoff));
  }

  /** A club's top contributors in these meetings, among players still at that club. */
  function topFor(clubCode: number, meetings: CodedMeeting[]): Contributor[] {
    const club = teamByCode.get(clubCode)!;
    const totals = new Map<number, { goals: number; assists: number }>();
    for (const m of meetings) {
      const side = m.home === club.short_name ? "home" : "away";
      for (const c of m.credits) {
        if (c.side !== side || elementByCode.get(c.code)?.team !== club.id) continue;
        const t = totals.get(c.code) ?? { goals: 0, assists: 0 };
        t.goals += c.goals;
        t.assists += c.assists;
        totals.set(c.code, t);
      }
    }
    return [...totals.entries()]
      .map(([code, t]) => ({ name: elementByCode.get(code)!.web_name, ...t, mine: squadCodes.has(code) }))
      .sort((x, y) => y.goals + y.assists - (x.goals + x.assists) || y.goals - x.goals)
      .slice(0, TOP_N);
  }

  async function playerRecord(pickPosition: number, elementId: number, opponentCode: number): Promise<H2HPlayer> {
    const el = elements.get(elementId)!;
    const club = teams.get(el.team)!;
    const opponent = teamByCode.get(opponentCode)!;
    const history = await currentHistory(el.id);
    const seasons = [
      seasonStats(current, club.short_name, history.filter((r) => r.opponent === opponent.id)),
      ...(await Promise.all(pastYears.map((y, i) => pastSeason(y, archive[i], el.code, opponentCode)))),
    ];
    return {
      id: el.id,
      name: el.web_name,
      position: positions.get(el.element_type) ?? "",
      club: club.short_name,
      bench: pickPosition > 11,
      total: {
        apps: sum(seasons, (s) => s.apps),
        goals: sum(seasons, (s) => s.goals),
        assists: sum(seasons, (s) => s.assists),
        points: sum(seasons, (s) => s.points),
      },
      seasons,
    };
  }

  const next = fixtures
    .filter((f) => f.event === nextEvent)
    .sort((x, y) => (x.kickoff_time ?? "").localeCompare(y.kickoff_time ?? ""));
  const matchups: Matchup[] = [];
  for (const f of next) {
    const mine = picks.picks.filter((p) => {
      const team = elements.get(p.element)!.team;
      return team === f.team_h || team === f.team_a;
    });
    if (mine.length === 0) continue;
    const home = teams.get(f.team_h)!;
    const away = teams.get(f.team_a)!;
    const meetings = meetingsBetween(home.code, away.code).slice(0, LAST_MEETINGS);
    const players = await Promise.all(
      mine.map((p) => {
        const isHome = elements.get(p.element)!.team === f.team_h;
        return playerRecord(p.position, p.element, (isHome ? away : home).code);
      }),
    );
    matchups.push({
      kickoff: f.kickoff_time,
      home: { name: home.name, short: home.short_name, top: topFor(home.code, meetings) },
      away: { name: away.name, short: away.short_name, top: topFor(away.code, meetings) },
      meetings: meetings.map(({ credits: _, ...m }) => m),
      players,
    });
  }

  const playing = new Set(next.flatMap((f) => [f.team_h, f.team_a]));
  const blanks = picks.picks
    .map((p) => elements.get(p.element)!)
    .filter((el) => !playing.has(el.team))
    .map((el) => ({ id: el.id, name: el.web_name, club: teams.get(el.team)!.short_name }));

  return {
    event: nextEvent,
    seasons: [current, ...pastYears.map((y) => seasonNames(y).label)],
    matchups,
    blanks,
    generatedAt: new Date().toISOString(),
  };
}
