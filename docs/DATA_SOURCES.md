# Data sources

Tested from the command line (desktop Chrome User-Agent) on 2026-09-27.

## In use

| Source | Used for | Access |
| --- | --- | --- |
| **FPL** `fantasy.premierleague.com/api` | Squad and picks, gameweek dates (break window), injury news | Public JSON |
| **FotMob** `www.fotmob.com/api/data` | Player ↔ national team, squads, matches (minutes, subs, cards, ratings), fixtures, injuries | Unofficial JSON: `search/suggest`, `playerData?id=`, `matchDetails?matchId=`, `teams?id=` |
| **Transfermarkt** `www.transfermarkt.com` | Independent call-up check. The current squad (`/{slug}/kader/verein/{id}`) drops withdrawn players | HTML. Nation lookup via `/schnellsuche/ergebnis/schnellsuche?query=`. Terms forbid scraping, so keep it to one request per nation, cached 3 h |

FotMob's squad list can be stale. It still listed Palmer after he withdrew. The call-up
verdict therefore also uses the date FotMob closed the player's national-team spell, and
it's cross-checked against Transfermarkt.

Transfermarkt national team IDs: ENG 3299, GER 3262, NED 3379, IRL 3509, ITA 3376, BEL 3382,
NOR 3440, COD 3854.

## Candidates for later

- **ESPN JSON API.** Use host `site.web.api.espn.com`; plain `site.api.espn.com` returns 403.
  - `/apis/site/v2/sports/soccer/{uefa.nations|caf.nations_qual|fifa.friendly}/scoreboard?dates=YYYYMMDD` (one day per call)
  - `.../summary?event=ID` gives rosters with starter/subbedIn/subbedOut
  - `/apis/site/v2/sports/soccer/all/teams/{id}/schedule?fixture=true` (England = 448)
  - Good for cross-checking minutes and fixtures. Covers the CAF qualifiers.
- **worldfootball.net**
  - The `/teams/teNNN/{name}/squad/` page lists everyone named this *season*, so it
    accumulates across windows.
  - Its search and team-slug URLs stopped working, so nations can't be looked up by name.
- **Flashscore:** injury flags on squad pages. Needs the `x-fsign` header for feeds, the
  format is fragile, and its terms forbid scraping.
- **UEFA** (`match.uefa.com/v5`, Europe only) and **FIFA** (`api.fifa.com/api/v3`, missing CAF
  qualifiers).
- **Blocked:** Sofascore (403), eu-football.info (empty body), BeSoccer and zerozero (403),
  OneFootball (client-rendered). national-football-teams.com lags by months.
- Sources differ by ±1 minute on substitutions.
