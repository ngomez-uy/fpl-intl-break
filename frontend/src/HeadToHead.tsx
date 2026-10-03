import { useEffect, useState } from 'react'
import type { Contributor, H2HPlayer, H2HReport, H2HSeason, Matchup, MatchupSide } from './types'

const fmtKickoff = (iso: string) =>
  new Date(iso).toLocaleString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })

const fmtDate = (iso: string) => new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' })

/** "2025/26" → "25/26" */
const shortSeason = (s: string) => s.slice(2)

function emptyNote(s: H2HSeason, opponent: string) {
  switch (s.status) {
    case 'no-player':
      return 'Not in the PL'
    case 'no-opponent':
      return `${opponent} not in the PL`
    case 'own-club':
      return `Played for ${opponent}`
    case 'not-met':
      return 'No meeting yet'
    case 'unavailable':
      return "Couldn't load"
    default:
      return null
  }
}

function TopList({ side }: { side: MatchupSide }) {
  return (
    <div className="h2h-top">
      <div className="h2h-label">{side.short} top G+A</div>
      {side.top.length === 0 ? (
        <span className="muted small">None from current players</span>
      ) : (
        <ol>
          {side.top.map((c: Contributor) => (
            <li key={c.name} className={c.mine ? 'mine' : undefined} title={c.mine ? 'In your squad' : undefined}>
              <span className="h2h-top-name">{c.name}</span>
              <span className="h2h-ga">
                {c.goals > 0 && <span>{c.goals}G</span>}
                {c.assists > 0 && <span>{c.assists}A</span>}
              </span>
            </li>
          ))}
        </ol>
      )}
    </div>
  )
}

function PlayerRow({ p, opponent }: { p: H2HPlayer; opponent: string }) {
  const [open, setOpen] = useState(false)
  const t = p.total
  return (
    <>
      <tr className={`h2h-row${open ? ' open' : ''}`} onClick={() => setOpen((o) => !o)}>
        <td>
          <span className="h2h-caret">{open ? '▾' : '▸'}</span>
          <strong>{p.name}</strong> <span className="muted small">{p.position}{p.bench && ' · bench'}</span>
        </td>
        <td className="num">{t.apps}</td>
        <td className={`num${t.goals ? ' hit' : ''}`}>{t.goals}</td>
        <td className={`num${t.assists ? ' hit' : ''}`}>{t.assists}</td>
        <td className="num pts">{t.points}</td>
      </tr>
      {open &&
        p.seasons.map((s) => {
          const note = emptyNote(s, opponent)
          return (
            <tr key={s.season} className="h2h-season">
              <td>
                <span className="h2h-season-name">{shortSeason(s.season)}</span>
                {note ? (
                  <span className="muted">{note}</span>
                ) : (
                  <span className="muted">
                    {s.club}
                    {s.matches.length === 0
                      ? ' · unused'
                      : s.matches.map((m) => (
                          <span key={m.kickoff} title={fmtDate(m.kickoff)}>
                            {' · '}
                            {m.result} {m.home ? 'H' : 'A'} {m.minutes}'
                          </span>
                        ))}
                  </span>
                )}
              </td>
              {note ? (
                <td colSpan={4} />
              ) : (
                <>
                  <td className="num">{s.apps}</td>
                  <td className="num">{s.goals}</td>
                  <td className="num">{s.assists}</td>
                  <td className="num pts">{s.points}</td>
                </>
              )}
            </tr>
          )
        })}
    </>
  )
}

function MatchupCard({ m }: { m: Matchup }) {
  // Your players, grouped by side, so each row's opponent is the other club.
  const sides = [
    { side: m.home, opponent: m.away.short },
    { side: m.away, opponent: m.home.short },
  ]
  return (
    <article className="h2h-card">
      <header className="h2h-head">
        <h3>
          {m.home.name} <span className="muted">v</span> {m.away.name}
        </h3>
        {m.kickoff && <span className="muted small">{fmtKickoff(m.kickoff)}</span>}
      </header>

      <div className="h2h-meetings">
        <span className="h2h-label">Last {m.meetings.length || ''} meetings</span>
        {m.meetings.length === 0 ? (
          <span className="muted small">None in the last six seasons</span>
        ) : (
          m.meetings.map((x) => (
            <span key={x.kickoff} className="h2h-score" title={fmtDate(x.kickoff)}>
              <span className="h2h-score-season">{shortSeason(x.season)}</span>
              {x.home} <strong>{x.homeGoals}-{x.awayGoals}</strong> {x.away}
            </span>
          ))
        )}
      </div>

      {m.meetings.length > 0 && (
        <div className="h2h-tops">
          <TopList side={m.home} />
          <TopList side={m.away} />
        </div>
      )}

      <table className="h2h-table">
        <thead>
          <tr>
            <th>Your players · last 6 seasons vs opponent</th>
            <th className="num" title="Appearances">Apps</th>
            <th className="num" title="Goals">G</th>
            <th className="num" title="Assists">A</th>
            <th className="num" title="FPL points">Pts</th>
          </tr>
        </thead>
        <tbody>
          {sides.flatMap(({ side, opponent }) =>
            m.players.filter((p) => p.club === side.short).map((p) => <PlayerRow key={p.id} p={p} opponent={opponent} />),
          )}
        </tbody>
      </table>
    </article>
  )
}

/** The next gameweek's matches through history: last meetings, who did the damage, and your players' record. */
export function HeadToHead({ teamId }: { teamId: number }) {
  const [report, setReport] = useState<H2HReport | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    fetch(`/api/team/${teamId}/h2h`)
      .then(async (res) => {
        const body = await res.json()
        if (!res.ok) throw new Error(body.error ?? `Request failed (${res.status})`)
        return body as H2HReport
      })
      .then((r) => !cancelled && setReport(r))
      .catch((err) => !cancelled && setError((err as Error).message))
    return () => {
      cancelled = true
    }
  }, [teamId])

  return (
    <main className="h2h">
      <section className="league-controls">
        <h2>Historic numbers against next rivals</h2>
        <p className="muted small">
          {report ? `Gameweek ${report.event}. ` : ''}For each of your matches: the last five Premier League meetings,
          each side's top scorers and assisters in them (current players only), and your players' record against the
          opponent with any club since {report?.seasons.at(-1) ?? 'five seasons ago'}. Tap a player for each season.
        </p>
        {!report && !error && <p className="muted small">Loading match histories… the first time takes a few seconds.</p>}
        {error && <p className="error">{error}</p>}
      </section>
      {report && (
        <>
          <div className="h2h-list">
            {report.matchups.map((m) => (
              <MatchupCard key={`${m.home.short}-${m.away.short}`} m={m} />
            ))}
          </div>
          {report.blanks.length > 0 && (
            <p className="muted small">No match this gameweek: {report.blanks.map((b) => `${b.name} (${b.club})`).join(', ')}.</p>
          )}
          <p className="muted small">
            This season from FPL; earlier seasons from the vaastav/Fantasy-Premier-League archive of FPL data.
          </p>
        </>
      )}
    </main>
  )
}
