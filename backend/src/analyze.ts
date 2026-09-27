import { withDataAges } from "./cache.js";
import { getMatch, getPlayer, getTeam, substitutionInfo, type PlayerData, type RecentMatch } from "./fotmob.js";
import {
  findBreakWindow,
  getBootstrap,
  getEntry,
  getFixtures,
  getPicks,
  type BreakWindow,
  type FplElement,
} from "./fpl.js";
import { mapPlayer, type PlayerMapping } from "./mapping.js";
import { transfermarktCallUp } from "./transfermarkt.js";
import { nextFixtures, price, rest, type NextFixture, type Price, type Rest } from "./club.js";
import { liveMatch, type LiveMatch } from "./live.js";
import {
  breakFixtures,
  combineChecks,
  fotmobCallUp,
  resolveNation,
  type BreakFixture,
  type CallUp,
  type Nation,
} from "./national.js";

export type Source = "fotmob" | "fpl";
export type Severity = "high" | "medium" | "info";
export type Risk = "red" | "amber" | "green";

export interface Signal {
  source: Source;
  severity: Severity;
  text: string;
}

export interface IntlMatch {
  fotmobMatchId: number;
  date: string;
  competition: string;
  team: string;
  opponent: string;
  home: boolean;
  score: string;
  played: boolean;
  started: boolean;
  onBench: boolean;
  minutes: number;
  goals: number;
  assists: number;
  yellowCards: number;
  redCards: number;
  rating: string | null;
  subbedOnMinute: number | null;
  subbedOffMinute: number | null;
  offReason: string | null;
  injuredOff: boolean;
}

export type Involvement = "started" | "sub" | "bench" | "absent" | "upcoming";

/** A national-team fixture in the break, with how this player featured. */
export interface BreakMatch extends BreakFixture {
  involvement: Involvement;
  minutes: number | null;
  live: boolean;
}

export interface PlayerReport {
  fplId: number;
  pickPosition: number;
  name: string;
  webName: string;
  club: string;
  position: string;
  squadRole: "starter" | "bench";
  isCaptain: boolean;
  isViceCaptain: boolean;
  fotmob: PlayerMapping;
  nationalTeam: string | null;
  nation: Nation | null;
  callUp: CallUp;
  breakFixtures: BreakMatch[];
  matches: IntlMatch[];
  nextFixtures: NextFixture[];
  price: Price;
  rest: Rest;
  live: LiveMatch | null;
  fpl: { status: string; news: string; newsAdded: string | null; chance: number | null };
  fotmobInjury: { name: string; expectedReturn: string | null; lastUpdated: string | null } | null;
  signals: Signal[];
  risk: Risk;
  confirmedByBoth: boolean;
  error?: string;
}

export interface DataSource {
  id: string;
  name: string;
  url: string;
  usedFor: string[];
  status: "live" | "planned";
  oldestDataAt: string | null; // when the oldest cached piece of data used from it was fetched
}

const SOURCES: Omit<DataSource, "oldestDataAt">[] = [
  {
    id: "fpl",
    name: "Fantasy Premier League",
    url: "https://fantasy.premierleague.com",
    usedFor: ["Your squad and captaincy", "Gameweek dates (break window)", "Injury news and chance of playing"],
    status: "live",
  },
  {
    id: "fotmob",
    name: "FotMob",
    url: "https://www.fotmob.com",
    usedFor: [
      "National team squads (call-ups, withdrawals)",
      "Matches played: minutes, subs, goals, cards, ratings",
      "Remaining national-team fixtures",
      "Injury reports",
    ],
    status: "live",
  },
  {
    id: "transfermarkt",
    name: "Transfermarkt",
    url: "https://www.transfermarkt.com",
    usedFor: ["Independent check of call-ups: current national-team squads, with withdrawn players removed"],
    status: "live",
  },
];

export interface TeamReport {
  team: { id: number; name: string; manager: string; picksFromEvent: number };
  window: BreakWindow;
  generatedAt: string;
  players: PlayerReport[];
  sources: DataSource[];
}

// A starter replaced before this minute without an injury flag is still worth a look.
const EARLY_SUB_MINUTE = 60;

const FPL_STATUS: Record<string, string> = {
  a: "Available",
  d: "Doubtful",
  i: "Injured",
  s: "Suspended",
  u: "Unavailable",
  n: "Not in squad",
};

const fmtDate = (iso: string) =>
  new Date(iso).toLocaleDateString("en-GB", { day: "numeric", month: "short", timeZone: "UTC" });

export async function buildReport(teamId: number, override?: Partial<BreakWindow>): Promise<TeamReport> {
  const [report, ages] = await withDataAges(() => buildReportData(teamId, override));
  const sources = SOURCES.map((s) => {
    const at = ages.get(s.id);
    return { ...s, oldestDataAt: at === undefined ? null : new Date(at).toISOString() };
  });
  return { ...report, sources };
}

async function buildReportData(
  teamId: number,
  override?: Partial<BreakWindow>,
): Promise<Omit<TeamReport, "sources">> {
  const [bootstrap, entry, detected, fixtures] = await Promise.all([
    getBootstrap(),
    getEntry(teamId),
    findBreakWindow(),
    getFixtures(),
  ]);
  if (!detected && !(override?.from && override?.to)) {
    throw new Error("No international break found yet this season.");
  }
  const window = { ...detected!, ...override } as BreakWindow;

  // Picks from the gameweek before the break: the squad as it went into the break.
  const picksEvent = Math.min(window.afterEvent ?? entry.current_event, entry.current_event);
  const picks = await getPicks(teamId, picksEvent);

  const elements = new Map(bootstrap.elements.map((e) => [e.id, e]));
  const teams = new Map(bootstrap.teams.map((t) => [t.id, t]));
  const positions = new Map(bootstrap.element_types.map((t) => [t.id, t.singular_name_short]));

  const players = await Promise.all(
    picks.picks.map(async (pick): Promise<PlayerReport> => {
      const el = elements.get(pick.element)!;
      const club = teams.get(el.team)!;
      const base: PlayerReport = {
        fplId: el.id,
        pickPosition: pick.position,
        name: `${el.first_name} ${el.second_name}`,
        webName: el.web_name,
        club: club.name,
        position: positions.get(el.element_type) ?? "",
        squadRole: pick.position <= 11 ? "starter" : "bench",
        isCaptain: pick.is_captain,
        isViceCaptain: pick.is_vice_captain,
        fotmob: { fotmobId: null, confidence: "none" },
        nationalTeam: null,
        nation: null,
        callUp: { status: "unknown", checks: [], agreement: "single" },
        breakFixtures: [],
        matches: [],
        nextFixtures: nextFixtures(fixtures, club, window.beforeEvent, teams),
        price: price(el),
        rest: { level: "n/a", lastIntlMatch: null, clubKickoff: null, restDays: null, breakMinutes: 0, longTrip: null, note: "" },
        live: null,
        fpl: { status: el.status, news: el.news, newsAdded: el.news_added, chance: el.chance_of_playing_next_round },
        fotmobInjury: null,
        signals: [],
        risk: "green",
        confirmedByBoth: false,
      };
      try {
        base.fotmob = await mapPlayer(el, club);
        if (base.fotmob.fotmobId) {
          const player = await getPlayer(base.fotmob.fotmobId);
          base.matches = await internationalMatches(player, window);
          base.nationalTeam = base.matches[0]?.team ?? null;
          const { nation, spellEnded } = await resolveNation(player);
          if (nation) {
            const team = await getTeam(nation.fotmobTeamId);
            base.nation = nation;
            base.nationalTeam = nation.name;
            const fixturesInBreak = breakFixtures(team, window);
            base.breakFixtures = fixturesInBreak.map((f) => withInvolvement(f, base.matches));
            const appearedVs = base.matches.filter((m) => m.team === team.name && (m.played || m.onBench)).map((m) => m.opponent);
            base.callUp = combineChecks([
              fotmobCallUp(player, team, spellEnded, appearedVs, window),
              await transfermarktCallUp(nation.name, el, club, base.fotmob.fotmobName ?? player.name),
            ]);
            if (base.callUp.status === "called") {
              base.live = await liveMatch(fixturesInBreak, player.id);
              const liveId = base.live?.fotmobMatchId;
              base.breakFixtures = base.breakFixtures.map((f) => ({ ...f, live: f.fotmobMatchId === liveId }));
            }
          }
          const inj = player.injuryInformation;
          if (inj) {
            base.fotmobInjury = {
              name: inj.name,
              expectedReturn: inj.expectedReturn?.expectedReturnFallback ?? null,
              lastUpdated: inj.lastUpdated?.utcTime ?? null,
            };
          }
        }
      } catch (err) {
        base.error = (err as Error).message;
      }
      base.rest = rest({
        withSquad: base.callUp.status === "called",
        fixtures: base.breakFixtures.map((f) => ({
          date: f.date,
          finished: f.finished,
          involved: ["started", "sub", "bench"].includes(f.involvement),
          competition: f.competition,
        })),
        breakMinutes: base.breakFixtures.reduce((sum, f) => sum + (f.minutes ?? 0), 0),
        clubKickoff: base.nextFixtures[0]?.kickoff ?? null,
      });
      return assess(base, el, window);
    }),
  );

  // FPL squad order: starting XI from GK forwards, then the bench.
  players.sort((a, b) => a.pickPosition - b.pickPosition);

  return {
    team: {
      id: entry.id,
      name: entry.name,
      manager: `${entry.player_first_name} ${entry.player_last_name}`,
      picksFromEvent: picksEvent,
    },
    window,
    generatedAt: new Date().toISOString(),
    players,
  };
}

function withInvolvement(f: BreakFixture, matches: IntlMatch[]): BreakMatch {
  const m = matches.find((x) => x.fotmobMatchId === f.fotmobMatchId);
  let involvement: Involvement = "upcoming";
  if (f.finished) {
    if (!m) involvement = "absent";
    else if (m.played) involvement = m.started ? "started" : "sub";
    else involvement = m.onBench ? "bench" : "absent";
  }
  return { ...f, involvement, minutes: m?.played ? m.minutes : null, live: false };
}

async function internationalMatches(player: PlayerData, window: BreakWindow): Promise<IntlMatch[]> {
  const from = Date.parse(window.from);
  const to = Date.parse(window.to);
  const clubId = player.primaryTeam?.teamId;
  const inWindow = (player.recentMatches ?? []).filter((m: RecentMatch) => {
    const t = Date.parse(m.matchDate.utcTime);
    return t >= from && t < to && m.teamId !== clubId;
  });

  const matches = await Promise.all(
    inWindow.map(async (m): Promise<IntlMatch> => {
      const sub = m.playedInMatch
        ? substitutionInfo(await getMatch(m.id), player.id)
        : { subbedOnMinute: null, subbedOffMinute: null, injuredOff: false, offReason: null };
      const [home, away] = m.isHomeTeam ? [m.teamName, m.opponentTeamName] : [m.opponentTeamName, m.teamName];
      return {
        fotmobMatchId: m.id,
        date: m.matchDate.utcTime,
        competition: m.leagueName,
        team: m.teamName,
        opponent: m.opponentTeamName,
        home: m.isHomeTeam,
        score: `${home} ${m.homeScore}–${m.awayScore} ${away}`,
        played: m.playedInMatch,
        started: m.playedInMatch && sub.subbedOnMinute === null,
        onBench: m.onBench,
        minutes: m.minutesPlayed ?? 0,
        goals: m.goals ?? 0,
        assists: m.assists ?? 0,
        yellowCards: m.yellowCards ?? 0,
        redCards: m.redCards ?? 0,
        rating: m.ratingProps?.rating ? String(m.ratingProps.rating) : null,
        ...sub,
      };
    }),
  );
  return matches.sort((a, b) => Date.parse(a.date) - Date.parse(b.date));
}

function assess(p: PlayerReport, el: FplElement, window: BreakWindow): PlayerReport {
  const signals: Signal[] = [];
  const from = Date.parse(window.from);
  let fotmobInjuryFlag = false;

  for (const m of p.matches) {
    const vs = `vs ${m.opponent} (${fmtDate(m.date)})`;
    if (m.injuredOff) {
      fotmobInjuryFlag = true;
      signals.push({ source: "fotmob", severity: "high", text: `Came off injured at ${m.subbedOffMinute ?? "?"}' ${vs}` });
    } else if (m.started && m.subbedOffMinute !== null && m.subbedOffMinute < EARLY_SUB_MINUTE) {
      const why = m.offReason ? ` (reason: ${m.offReason})` : " (no injury flag)";
      signals.push({ source: "fotmob", severity: "medium", text: `Subbed off early at ${m.subbedOffMinute}' ${vs}${why}` });
    }
    if (m.redCards > 0) signals.push({ source: "fotmob", severity: "info", text: `Sent off ${vs}` });
  }

  if (p.fotmobInjury) {
    const updated = p.fotmobInjury.lastUpdated;
    const during = updated !== null && Date.parse(updated) >= from - 24 * 3_600_000; // FotMob dates are day-precision
    const back = p.fotmobInjury.expectedReturn ? `, expected back ${p.fotmobInjury.expectedReturn}` : "";
    if (during) fotmobInjuryFlag = true;
    signals.push({
      source: "fotmob",
      severity: during ? "high" : "medium",
      text: `${during ? "Injury reported during the break" : "Existing injury"}: ${p.fotmobInjury.name}${back}`,
    });
  }

  let fplFlag = false;
  if (el.status !== "a") {
    const newDuringBreak = el.news_added !== null && Date.parse(el.news_added) >= from;
    const serious = ["i", "s", "u"].includes(el.status) || (el.chance_of_playing_next_round ?? 100) <= 50;
    fplFlag = true;
    signals.push({
      source: "fpl",
      severity: serious || newDuringBreak ? "high" : "medium",
      text: `${FPL_STATUS[el.status] ?? el.status}${newDuringBreak ? " (updated during break)" : ""}: ${el.news || "no details"}`,
    });
  }

  if (p.callUp.status === "withdrawn") {
    const why = p.callUp.checks.find((c) => c.status === "withdrawn")?.detail ?? "Withdrew from the squad";
    signals.push({ source: "fotmob", severity: "medium", text: why });
  }
  if (p.callUp.agreement === "conflict") {
    const tm = p.callUp.checks.find((c) => c.source === "Transfermarkt");
    signals.push({
      source: "fotmob",
      severity: "medium",
      text: `FotMob and Transfermarkt disagree on the call-up${tm ? ` (Transfermarkt: ${tm.detail.replace("Transfermarkt's ", "")})` : ""} — check the latest squad news`,
    });
  }

  p.confirmedByBoth = fotmobInjuryFlag && fplFlag;
  if (fotmobInjuryFlag && !fplFlag) {
    signals.push({ source: "fpl", severity: "info", text: "FPL still lists the player as available — FPL often updates a day or two later" });
  }
  if (p.fotmob.fotmobId === null) {
    signals.push({ source: "fotmob", severity: "info", text: "Couldn't find this player on FotMob — add an override in data/overrides.json" });
  } else if (p.fotmob.confidence === "low") {
    signals.push({ source: "fotmob", severity: "info", text: `FotMob match is a guess (${p.fotmob.fotmobName}) — check it's the right player` });
  }
  if (p.error) signals.push({ source: "fotmob", severity: "info", text: `FotMob data unavailable: ${p.error}` });

  p.signals = signals;
  p.risk = signals.some((s) => s.severity === "high") ? "red" : signals.some((s) => s.severity === "medium") ? "amber" : "green";
  return p;
}
