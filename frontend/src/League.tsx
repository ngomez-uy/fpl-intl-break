import { useEffect, useState } from 'react'
import type { LeagueOption, LeagueReport, RivalImpact } from './types'

const LAST_LEAGUE_KEY = 'fpl-break:lastLeagueId'

function readLastLeague() {
  try {
    return localStorage.getItem(LAST_LEAGUE_KEY) ?? ''
  } catch {
    return ''
  }
}

function saveLastLeague(id: string) {
  try {
    localStorage.setItem(LAST_LEAGUE_KEY, id)
  } catch {
    // Storage unavailable; remembering the league is only a convenience.
  }
}

type SortKey = 'injured' | 'withdrew' | 'rank'

// Counts sort worst-first, league rank best-first; ties fall back to the other count, then rank.
function sortRivals(rivals: RivalImpact[], key: SortKey, desc: boolean) {
  const other: SortKey = key === 'injured' ? 'withdrew' : 'injured'
  return [...rivals].sort((a, b) => {
    if (!!a.error !== !!b.error) return a.error ? 1 : -1
    const primary = (a[key] - b[key]) * (desc ? -1 : 1)
    if (primary || key === 'rank') return primary
    return b[other] - a[other] || a.rank - b.rank
  })
}

/** Mini-league break impact: which rivals the break hit hardest. */
export function League({ teamId }: { teamId: number }) {
  const [leagues, setLeagues] = useState<LeagueOption[] | null>(null)
  const [leagueId, setLeagueId] = useState(readLastLeague)
  const [limit, setLimit] = useState(10)
  const [report, setReport] = useState<LeagueReport | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [sort, setSort] = useState<{ key: SortKey; desc: boolean }>({ key: 'injured', desc: true })

  function sortBy(key: SortKey) {
    setSort((s) => (s.key === key ? { key, desc: !s.desc } : { key, desc: key !== 'rank' }))
  }

  function header(key: SortKey, label: string, title: string, className: string) {
    const active = sort.key === key
    return (
      <button
        className={`${className}${active ? ' active' : ''}`}
        onClick={() => sortBy(key)}
        title={title}
        aria-sort={active ? (sort.desc ? 'descending' : 'ascending') : 'none'}
      >
        {label}
        <span className="sort-arrow">{active ? (sort.desc ? '▼' : '▲') : ''}</span>
      </button>
    )
  }

  useEffect(() => {
    let cancelled = false
    fetch(`/api/team/${teamId}/leagues`)
      .then(async (res) => {
        const body = await res.json()
        if (!res.ok) throw new Error(body.error ?? `Request failed (${res.status})`)
        return body as LeagueOption[]
      })
      .then((list) => {
        if (cancelled) return
        setLeagues(list)
        setLeagueId((current) => (list.some((l) => String(l.id) === current) ? current : String(list[0]?.id ?? '')))
      })
      .catch((err) => !cancelled && setError((err as Error).message))
    return () => {
      cancelled = true
    }
  }, [teamId])

  async function compare() {
    setLoading(true)
    setError(null)
    try {
      const res = await fetch(`/api/league/${leagueId}/break?team=${teamId}&limit=${limit}`)
      const body = await res.json()
      if (!res.ok) throw new Error(body.error ?? `Request failed (${res.status})`)
      setReport(body)
      saveLastLeague(leagueId)
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setLoading(false)
    }
  }

  return (
    <main className="league">
      <section className="league-controls">
        <h2>Who did the break hit hardest?</h2>
        <p className="muted small">
          Checks the top teams in one of your mini-leagues for injuries, withdrawals and short rest before the next
          gameweek. Tap a column to sort.
        </p>
        {leagues?.length === 0 && <p className="muted">You're not in any private mini-leagues.</p>}
        {leagues && leagues.length > 0 && (
          <div className="league-form">
            <label>
              <span className="sr-only">Mini-league</span>
              <select value={leagueId} onChange={(e) => setLeagueId(e.target.value)} disabled={loading}>
                {leagues.map((l) => (
                  <option key={l.id} value={l.id}>
                    {l.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              <span className="sr-only">How many teams</span>
              <select value={limit} onChange={(e) => setLimit(Number(e.target.value))} disabled={loading}>
                <option value={10}>Top 10</option>
                <option value={20}>Top 20</option>
              </select>
            </label>
            <button onClick={compare} disabled={loading || !leagueId}>
              {loading ? 'Checking…' : 'Compare'}
            </button>
          </div>
        )}
        {loading && (
          <p className="muted small">
            Checking every squad in the top {limit}. The first time can take a minute or two; rivals share many players, so
            it gets faster.
          </p>
        )}
        {error && <p className="error">{error}</p>}
      </section>

      {report && !loading && (
        <section className="rivals">
          <h3>{report.league.name}</h3>
          <div className="rivals-table">
            <div className="rivals-head">
              {header('injured', 'Inj', 'Injury concerns (starting XI + bench)', 'rival-count')}
              {header('withdrew', 'Out', 'Withdrew from their national team', 'rival-count')}
              {header('rank', 'League rank', 'Sort by league rank', 'rival-team')}
            </div>
            <ol>
              {sortRivals(report.rivals, sort.key, sort.desc).map((r) => (
                <li key={r.entry} className={r.isYou ? 'you' : undefined}>
                  <span className={`rival-count${r.injured ? ' hit' : ''}`}>{r.error ? '–' : r.injured}</span>
                  <span className={`rival-count${r.withdrew ? ' hit' : ''}`}>{r.error ? '–' : r.withdrew}</span>
                  <div className="rival-main">
                    <div className="rival-head">
                      <strong>{r.teamName}</strong>
                      {r.isYou && <span className="tag">You</span>}
                      <span className="muted small">
                        {r.manager} · #{r.rank} in league
                      </span>
                    </div>
                    {r.error ? (
                      <p className="muted small">Couldn't check this squad: {r.error}</p>
                    ) : (
                      <div className="rival-flags">
                        {r.flagged.length === 0 && <span className="muted small">Nothing flagged</span>}
                        {r.flagged.map((f) => (
                          <span key={f.name} className={`flag${f.bench ? ' bench' : ''}`} title={f.bench ? 'On the bench' : 'Starting XI'}>
                            {f.name} <span className="muted">· {f.reason}</span>
                          </span>
                        ))}
                      </div>
                    )}
                  </div>
                </li>
              ))}
            </ol>
          </div>
          <p className="muted small">
            Checked the top {report.limit}
            {report.rivals.some((r) => r.isYou && r.rank > report.limit) && ' plus you'} at {new Date(report.generatedAt).toLocaleTimeString('en-GB')}.
          </p>
        </section>
      )}
    </main>
  )
}
