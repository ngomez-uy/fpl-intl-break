// Transfermarkt's current national-team squads, as an independent call-up check. Players
// who withdraw are removed from these pages, unlike FotMob's squad lists. Transfermarkt's
// terms forbid scraping, so this makes one request per nation and caches it for hours.
import { cached, HOUR } from "./cache.js";
import type { FplElement, FplTeam } from "./fpl.js";
import { getText } from "./http.js";
import { normalize, sameClub } from "./mapping.js";
import type { SourceCheck } from "./national.js";

const BASE = "https://www.transfermarkt.com";

interface TmTeam {
  slug: string;
  id: number;
}

// Nations already looked up, to save a search request each.
const KNOWN: Record<string, TmTeam> = {
  england: { slug: "england", id: 3299 },
  germany: { slug: "deutschland", id: 3262 },
  netherlands: { slug: "niederlande", id: 3379 },
  ireland: { slug: "irland", id: 3509 },
  italy: { slug: "italien", id: 3376 },
  belgium: { slug: "belgien", id: 3382 },
  norway: { slug: "norwegen", id: 3440 },
  "dr congo": { slug: "dr-kongo", id: 3854 },
};

interface TmPlayer {
  id: number;
  name: string;
  club: string | null;
}

const decode = (s: string) =>
  s
    .replace(/&#0?39;|&apos;/g, "'")
    .replace(/&amp;/g, "&")
    .replace(/&quot;/g, '"')
    .replace(/&#(\d+);/g, (_, n) => String.fromCharCode(Number(n)))
    .trim();

async function findTeam(nation: string): Promise<TmTeam | null> {
  const key = normalize(nation);
  if (KNOWN[key]) return KNOWN[key];
  return cached(`transfermarkt_search_${key}`, 30 * 24 * HOUR, async () => {
    const page = await getText(`${BASE}/schnellsuche/ergebnis/schnellsuche?query=${encodeURIComponent(nation)}`);
    for (const m of page.matchAll(/<a title="([^"]+)" href="\/([a-z0-9-]+)\/startseite\/verein\/(\d+)"/g)) {
      if (normalize(decode(m[1])) === key) return { slug: m[2], id: Number(m[3]) };
    }
    return null;
  });
}

// Squads change when players withdraw, but rarely more than once a day.
const getSquad = (team: TmTeam) =>
  cached(`transfermarkt_squad_${team.id}`, 3 * HOUR, async (): Promise<TmPlayer[]> => {
    const page = await getText(`${BASE}/${team.slug}/kader/verein/${team.id}`);
    const players: TmPlayer[] = [];
    // One <tr class="odd|even"> per player; the nested position table's rows have no class.
    for (const row of page.split(/<tr class="(?:odd|even)">/).slice(1)) {
      const link = row.match(/<td class="hauptlink">\s*<a href="\/[^/"]+\/profil\/spieler\/(\d+)">\s*([^<]+?)\s*<\/a>/);
      if (!link) continue;
      const id = Number(link[1]);
      const club = row.match(/<a title="([^"]*)" href="\/[^"]+\/startseite\/verein\/\d+"/);
      if (!players.some((p) => p.id === id)) players.push({ id, name: decode(link[2]), club: club ? decode(club[1]) : null });
    }
    return players;
  });

const lastWord = (s: string) => normalize(s).split(" ").at(-1) ?? "";

function findPlayer(squad: TmPlayer[], el: FplElement, club: FplTeam, fotmobName: string | undefined) {
  const names = [fotmobName, `${el.first_name} ${el.second_name}`, el.web_name].filter(Boolean).map((n) => normalize(n!));
  const exact = squad.find((p) => names.includes(normalize(p.name)));
  if (exact) return exact;
  // Name spelt differently (e.g. dropped middle name): same surname and same club.
  return squad.find((p) => lastWord(p.name) === lastWord(el.second_name) && p.club !== null && sameClub(club, p.club));
}

export async function transfermarktCallUp(
  nation: string,
  el: FplElement,
  club: FplTeam,
  fotmobName: string | undefined,
): Promise<SourceCheck> {
  try {
    const team = await findTeam(nation);
    if (!team) return { source: "Transfermarkt", status: "unknown", detail: `Couldn't find ${nation} on Transfermarkt` };
    const url = `${BASE}/${team.slug}/kader/verein/${team.id}`;
    const squad = await getSquad(team);
    if (squad.length === 0) {
      return { source: "Transfermarkt", status: "unknown", detail: `Transfermarkt's ${nation} squad page couldn't be read`, url };
    }
    const hit = findPlayer(squad, el, club, fotmobName);
    return hit
      ? { source: "Transfermarkt", status: "called", detail: `In Transfermarkt's current ${nation} squad`, url }
      : { source: "Transfermarkt", status: "not_called", detail: `Not in Transfermarkt's current ${nation} squad`, url };
  } catch (err) {
    return { source: "Transfermarkt", status: "unknown", detail: `Transfermarkt unavailable: ${(err as Error).message}` };
  }
}
