// Maps FPL players to FotMob player IDs. Results are cached; wrong matches can be
// pinned in data/overrides.json as { "<fplId>": <fotmobId> }.
import { readFile } from "node:fs/promises";
import path from "node:path";
import { readCache, writeCache } from "./cache.js";
import { searchPlayers, type SearchSuggestion } from "./fotmob.js";
import type { FplElement, FplTeam } from "./fpl.js";

const OVERRIDES_FILE = path.resolve(import.meta.dirname, "../data/overrides.json");

// FPL short club names -> FotMob club names, where they differ.
const CLUB_ALIASES: Record<string, string> = {
  "man city": "manchester city",
  "man utd": "manchester united",
  "nott'm forest": "nottingham forest",
  spurs: "tottenham hotspur",
  wolves: "wolverhampton wanderers",
  newcastle: "newcastle united",
  leeds: "leeds united",
  brighton: "brighton hove albion",
  "west ham": "west ham united",
  bournemouth: "afc bournemouth",
  leicester: "leicester city",
  "sheffield utd": "sheffield united",
};

const normalize = (s: string) =>
  s
    .normalize("NFD")
    .replace(/[̀-ͯ]/g, "")
    .toLowerCase()
    .replace(/&/g, " ")
    .replace(/[^a-z0-9' ]/g, " ")
    .replace(/\s+/g, " ")
    .trim();

function sameClub(fplTeam: FplTeam, fotmobTeamName: string | undefined) {
  if (!fotmobTeamName) return false;
  const a = normalize(CLUB_ALIASES[normalize(fplTeam.name)] ?? fplTeam.name);
  const b = normalize(fotmobTeamName);
  return a === b || b.startsWith(a) || a.startsWith(b);
}

export type MatchConfidence = "override" | "high" | "low";

export interface PlayerMapping {
  fotmobId: number | null;
  fotmobName?: string;
  confidence: MatchConfidence | "none";
}

async function loadOverrides(): Promise<Record<string, number>> {
  try {
    return JSON.parse(await readFile(OVERRIDES_FILE, "utf8"));
  } catch {
    return {};
  }
}

export async function mapPlayer(el: FplElement, team: FplTeam): Promise<PlayerMapping> {
  const override = (await loadOverrides())[String(el.id)];
  if (override) return { fotmobId: override, confidence: "override" };

  const cacheKey = `map_${el.id}`;
  const hit = await readCache<PlayerMapping>(cacheKey);
  if (hit) return hit;

  const terms = [
    `${el.first_name} ${el.second_name}`,
    el.known_name,
    el.web_name,
    el.second_name,
  ].filter((t, i, all): t is string => !!t && all.indexOf(t) === i);

  let fallback: SearchSuggestion | undefined;
  let result: PlayerMapping = { fotmobId: null, confidence: "none" };
  for (const term of terms) {
    const suggestions = await searchPlayers(term);
    const hitSameClub = suggestions.find((s) => sameClub(team, s.teamName));
    if (hitSameClub) {
      result = { fotmobId: Number(hitSameClub.id), fotmobName: hitSameClub.name, confidence: "high" };
      break;
    }
    fallback ??= term === terms[0] && suggestions.length === 1 ? suggestions[0] : undefined;
  }
  if (result.fotmobId === null && fallback) {
    result = { fotmobId: Number(fallback.id), fotmobName: fallback.name, confidence: "low" };
  }

  // Unmatched players get retried after a day; matches are kept for a month.
  await writeCache(cacheKey, result, result.fotmobId ? 30 * 24 * 3_600_000 : 24 * 3_600_000);
  return result;
}
